# 备份体系改造：移除增量备份 + 本地自动备份（一期）+ 局域网同步（二期设计）

## Context

当前项目 RikkaHub-Neomix 有一套增量备份功能（实验入口「快照备份」），用户决定将其整体移除，
以两条更简单、经过验证的路径替代数据安全能力：

1. **本地自动备份**（一期）：借鉴 kelivo/cuplivo 的「本地副本」模式——应用启动/恢复后延迟检查、
   自动频率（每日/每 N 天）、保留份数 + 上周/上月各留一份、容量上限、复用 `BackupArchiveEngine`
   全量打包。参照实现：`F:\build\projects\kelivo\lib\core\services\backup\local_folder_backup.dart`、
   `F:\build\projects\cuplivo\lib\core\services\backup\local_snapshot_retention.dart` 与
   `local_snapshot_scheduler.dart`。
2. **局域网同步**（二期，本次只做设计稿）：借鉴 cuplivo 的 LAN sync——HTTPS 自签证书 + SHA-256
   指纹绑定 + PIN 配对 + 对称合并，最小实体集（会话含分支树 + 引用附件 + 助手），协议带版本号。

交付节奏：**分两期**。一期 = 1A 移除 + 1B 本地自动备份；二期 = 局域网同步（另开实现周期）。

## 核验后的删除边界（重要修正）

- **必须保留**：`DatabaseObjectCapture.kt` / `DatabaseObjectMerge.kt` / `DatabaseObjectProtocol.kt`
  —— `BackupArchiveEngine.kt:612/643/652/765` 的恢复合并路径依赖它们（已核验）。
- **FTS 重建链整链是增量附属物，可删**：`FtsRebuildPending` 唯一生产者是
  `IncrementalBackupService.kt:293`；消费链 `RikkaHubApp.rebuildSearchIndexIfPending()/retry…` →
  `SearchIndexRebuildBanner` → `RouteActivity.kt:88,264` → `SearchIndexRebuildSignal` →
  `SnapshotBackupTab.kt:64`。
- **增量专属可删**：`BackupObjectStore.kt`、`RemoteBackupObjectStores.kt`（S3/WebDAV 对象存储适配
  器，WebDavTab/S3Tab 走独立 `WebDavSync`/`S3Sync` 全量引擎）、`SnapshotPacking.kt`、
  `SnapshotObjectCodec.kt`、`BackupSnapshotManifest.kt`、`BackupSnapshotCapture.kt`。

---

## Phase 1A：移除增量备份

### 1A.1 删除与接线清理（SafeDelete 顺序）

**第 1 步 — UI 叶子**
- 删 [SnapshotBackupTab.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/tabs/SnapshotBackupTab.kt) 整文件
- 删 [BackupDetailPages.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/BackupDetailPages.kt) 中 `BackupSnapshotPage`（约 95-101 行）及其 import
- [BackupPage.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/BackupPage.kt) 删 `backupExperimental` 卡片（107-119 行，已核验）与 `HugeIcons.Database02` import
- [Screen.kt:87](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/Screen.kt) 删 `Screen.BackupSnapshot`
- [AppNavHost.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/AppNavHost.kt) 删 import + 路由 entry（约 196-198 行）
- 删 `ui/pages/backup/components/SearchIndexRebuildBanner.kt`
- [RouteActivity.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/RouteActivity.kt):88（import）与 :264（解开 banner 包裹，保留 `RestoreRestartGate`）

**第 2 步 — BackupVM 瘦身**
[BackupVM.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/ui/pages/backup/BackupVM.kt)：
删构造参数 `incrementalBackup`（78 行）、repo 包 import（62 行）、状态
`snapshotDestination/localBackupTarget/snapshots/snapshotBusy/Error/Notice/Plan/Retention`
（90-98 行）与 `snapshotSchemaVersion`（110 行）、方法 `snapshotTask/loadSnapshots/
selectSnapshotRetention/selectSnapshotDestination/attachBackupFolder/detachBackupFolder/
prepareSnapshot/cancelSnapshotPlan/commitSnapshot/restoreSnapshot/garbageCollectSnapshots/
exportSnapshot/deleteSnapshot/refreshSnapshots`（112-295 行，已抽样核验行号）及 `onCleared` 注释。
WebDAV/S3/导入导出成员全部保留。

**第 3 步 — BackupRunCoordinator 瘦身**
[BackupRunCoordinator.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/sync/BackupRunCoordinator.kt)：
删 `BackupSnapshotPlan` import、`_pendingPlan/pendingPlan/storePlan/clearPlan/closePlanAsync`（83-134 行）、
`PLAN_TTL`（284 行）；`BackupRunKind` 删 `SNAPSHOT_PLAN/COMMIT/RESTORE/GC/EXPORT`（40-44 行）并同步
`titleFor`/`finishedTextFor` 分支；androidTest `BackupRunCoordinatorTest.kt` 同步改。

**第 4 步 — DI**：[DataSourceModule.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/di/DataSourceModule.kt):407-411 删 `IncrementalBackupService` single 注册。

**第 5 步 — repo 包核心删除**（整删以下文件）
`IncrementalBackupEngine.kt`、`IncrementalBackupService.kt`（内含 `BackupDestination/LocalBackupTarget/
SnapshotRetentionConfig/DEFAULT_SNAPSHOT_RETENTION`）、`SafBackupObjectStore.kt`（内含
`AndroidBackupDocumentAccess/CountingBackupDocumentAccess`）、`BackupObjectStore.kt`、
`RemoteBackupObjectStores.kt`、`SnapshotPacking.kt`、`SnapshotObjectCodec.kt`、
`BackupSnapshotManifest.kt`、`BackupSnapshotCapture.kt`、`FtsRebuildPending.kt`。
**保留** `DatabaseObjectCapture/Merge/Protocol` 三件套。

**第 6 步 — FTS 重建链收尾**
- [RikkaHubApp.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/RikkaHubApp.kt)：删 import（41 行）、`rebuildSearchIndexIfPending()`（295-317）、`retrySearchIndexRebuild()`（319-325）及调用点（287）
- 删 `data/db/fts/SearchIndexRebuildSignal(.kt)` + 对应单测
- `ConversationRepository.rebuildAllIndexes` 无调用方则顺手删

**第 7 步 — 目录常量与注册表**
- [FilesManager.kt:968-975](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/files/FilesManager.kt) 删 `FileFolders.BACKUP_INDEX`
- [BackupFolderRegistry.kt:106-110](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/sync/BackupFolderRegistry.kt) 删 BACKUP_INDEX 豁免 spec（`BackupCoverageTest` 反射比对全集，两边同步改）

**第 8 步 — 字符串（3 locale 同步）**
`values/strings.xml:301,305-306`、`values-zh/strings.xml:310,314-315`、`values-zh-rTW/strings.xml:310,314-315`：
删 `backup_page_section_experimental`、`backup_page_snapshot_title/desc`。

**第 9 步 — 测试清理**
- unit 删：`IncrementalBackupEngineTest/IncrementalBackupRetentionTest/IncrementalDatabaseBackupTest/
  SnapshotPackingTest/SafBackupObjectStoreTest/BackupSnapshotCaptureTest/FtsRebuildPendingTest`
- unit 留：`DatabaseObjectProtocolTest` 及其余 data/sync 测试（BackupCoverageTest、
  BackupScopeGranularityTest 等护栏）
- androidTest 删：`IncrementalStorageInstrumentedTest/IncrementalPerformanceMeasurement/FixtureBackupServer`
- androidTest 留：`DatabaseObject*InstrumentedTest` 三件

### 1A.2 陈旧状态目录清理（升级首启，fail-open）

`RikkaHubApp.boot()` 早期（`PendingRestoreStore.rollbackIfUnconfirmed` 之前的第 0 步位置）加：
`runCatching { File(filesDir, "backup_index").deleteRecursively(); File(cacheDir, "incremental_backup").deleteRecursively() }`。
纯文件状态、无 Room/DataStore 迁移；目录不存在时为 no-op，幂等且不拦启动。

### 1A.3 验证

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests "app.androidrik.rikkahub.data.sync.*"
.\gradlew.bat :app:testDebugUnitTest
```
人工：备份页无「实验功能」卡片；WebDAV/S3/导入导出/备份提醒四页无回归；首启后 backup_index 与
incremental_backup 目录被清。

---

## Phase 1B：本地自动备份（kelivo「本地副本」式）

### 已定决策

| 决策点 | 结论 | 理由 |
|---|---|---|
| 存储位置 | **SAF 用户选定目录** | 卸载不丢；复用原 `attachLocal` 的 SAF 授权流程（`ACTION_OPEN_DOCUMENT_TREE` + persistable permission，拒绝选本应用 workspace）；零 `FileFolders`/`BackupFolderRegistry` 联动。授权失效时 UI 显示「权限丢失」、自动备份跳过并记失败 |
| 触发模型 | **应用内 resume 延迟检查** | 照搬 cuplivo `local_snapshot_scheduler.dart`：冷启延迟 8s、resume 延迟 3s、最小检查间隔 1 分钟；`RikkaHubApp` 注册 `ActivityLifecycleCallbacks`，协程跑 AppScope；无 FGS/Doze 问题。WorkManager 兜底留作后续增强 |
| 命名 | `local_backup_yyyyMMdd_HHmmss.zip`（本地时区） | 文件名时间戳即留存排序依据，无索引文件、无账目漂移 |
| 留存算法 | 纯函数 `LocalSnapshotRetentionPolicy`（新包 `data/sync/localsnapshot/`） | 最近 N 份（默认 3，1-10）+1 weekly 槽（满 7 天最新）+1 monthly 槽（满 30 天最新）；**最新一份非空副本永不删**；容量上限（0=不限）从最旧开始删、保护集不越界 |
| 打包/恢复 | 复用 `BackupArchiveEngine` 全量打包（尊重 `backupItems/backupScope` + 加密 tile）；恢复复用 `BackupVM.restoreFromLocalFile`（merge/overwrite 原样） | 与 `WebDavSync.prepareBackupFile` 路径同构 |
| 变更门控 | 指纹 = conversations `COUNT+MAX(update_time)` + assistants `COUNT`；一致则跳过（跳过不推进 lastSuccessAt） | cuplivo `recordUnchanged` 语义，避免真实变更推迟一个周期 |
| 失败退避 | 失败始终提示（toast + coordinator 通知）；连续失败 1/2/4/8/16h 指数退避 | 防磁盘满时每次 resume 空转 |
| 设置持久化 | `DisplaySettings.kt` 紧邻 `BackupReminderConfig`（349-353 行）新增 `@Serializable LocalSnapshotConfig(enabled, intervalDays=1, keepRecent=3, keepWeekly=true, keepMonthly=true, maximumTotalBytes=0, announceResult=false)`；运行态 `LocalSnapshotState`（lastSuccessAt/fingerprint/failureStreak）存 PreferencesStore 独立 JSON key | 字段级 `settingsStore.update { it.copy(...) }` transform，禁止整对象写（AGENTS 规范） |
| 执行通道 | 新 `BackupRunKind.LOCAL_SNAPSHOT` 进 `BackupRunCoordinator` | 复用前台服务/进度通知/单任务互斥，防与手动备份并发 |

### 新增代码

- `data/sync/localsnapshot/LocalSnapshotRetentionPolicy.kt`（纯函数）+ `LocalSnapshotRetentionPolicyTest`
  （槽位/保护/容量/「空副本不挤掉非空」用例）+ `LocalSnapshotGateTest`（指纹不变跳过、间隔未到跳过、退避推进）+ 文件名↔时间戳解析测试
- `data/sync/localsnapshot/LocalSnapshotService.kt`：SAF attach/detach（自 IncrementalBackupService 搬迁
  约 40 行授权逻辑）+ 打包写临时文件 → 流式拷贝进 SAF → 删临时；指纹门控；留存执行
- `ui/pages/backup/LocalSnapshotsPage.kt` + `Screen.LocalSnapshots` + AppNavHost entry：副本列表
  （时间/大小/是否含内容；恢复走 `RestoreConfirmDialog`、删除、导出）；lazy item key 用**文件名**
- `BackupPage.kt`：以「自动备份」section 替换原「实验功能」section（开关/频率/保留份数/容量上限/
  结果提示/占用显示/立即备份/管理副本入口）；选项选择用 ModalBottomSheet（AGENTS TextField Host Rules）
- DI：`DataSourceModule` 注册 `LocalSnapshotService`
- i18n：`local_snapshot_*` 前缀一组 key，en/zh/zh-rTW 三语同步

### 验证

编译 + 全量单测 + `python scripts/audit_ime_trap.py`（新增 UI 过审计）+ 真机：立即备份、自动触发、
留存清理（造 5 份旧副本验证 N+周+月）、授权失效提示、恢复 merge/overwrite 双模式。

---

## Phase 2：局域网同步（设计稿，另开实现周期）

包布局 `data/sync/lan/`：

- `LanIdentity.kt`：每安装生成密钥对 + 自签证书；`certSha256` 即设备身份
- `LanSyncServer.kt`：Ktor embedded HTTPS，仅监听局域网接口；`POST /v1/pair`、`GET /v1/manifest`、
  `POST /v1/objects`、`GET /v1/objects/{key}`；统一 `X-Rikka-Token` 头
- `LanPeerRegistry.kt`：DataStore JSON 记录 peer（名称/host:port/certSha256/token/lastSyncAt）
- `LanSyncClient.kt`：OkHttp + 自定义 TrustManager，只信 pin 的 SHA-256 指纹
- `LanManifest.kt`：会话指纹 `{id, updateTime, nodeCount, contentHash, attachments[]}`；信封带
  `version: 1`，更高版本拒绝合并
- `LanMerge.kt`：对话粒度对称合并——双方换 manifest → 对方有本地无直接插入 → 双有且不一致新者胜
  （旧版内容保留为分支候选 messageNode，复用 MessageNode 树）→ 缺失对象走 blob 传输；落库复用
  `DatabaseObjectMerge.mergeMissingConversationsLive`（Phase 1A 保留它的原因之一）
- 配对：6 位 PIN + 二维码（`{host, port, certSha256, token, version}`）双确认入 registry
- 数据范围（MVP）：会话含分支树 + 被引用附件（files/upload、images）+ 助手；设置不进同步
- UI：备份页新增「局域网同步」入口 → 同步页（本机指纹卡 + peer 卡列表 + 添加设备扫码/手输
  IP:port + 每 peer「立即同步」）；MVP 不做 mDNS

安全不变量：证书 pin 先于任何 API；token 仅经配对通道交换；附件传输前校验哈希；合并幂等；
被覆盖数据先走 `RecoveryBackupExporter` 安全备份。

开放风险：长传输需前台服务（复用 BackupForegroundService 模式）+ Doze 压速；无 mDNS 时 IP 变动
需重配对；证书轮换 = 重新配对；Ktor server 常驻内存/电量需实测。

---

## 执行顺序

1. 1A 第 1-3 步（UI/VM/Coordinator）→ 编译（repo 包还在，应过）
2. 1A 第 4-6 步（DI + repo 核心 + FTS 链）→ 再编译（引用残留在此暴露）
3. 1A 第 7-9 步（常量/注册表/字符串/测试）→ 单测 + 全量单测 + 首启清理验证
4. 1B：Settings 字段 → 留存纯函数 + 单测 → Service + DI → 调度器 + coordinator kind → UI + 3 locale
   → 编译 + audit_ime_trap + 全量单测 + 真机验证
5. Phase 2：独立周期另起，按设计稿落地
