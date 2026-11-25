# 备份体系改造（修订版）：保留增量备份 + 双向对称局域网同步 + 本地自动备份

## Context

上一版方案（`586664c48`）主张移除增量备份，再用 cuplivo 的对称协议从零做局域网同步。
本版按用户决策修订：**保留增量备份**，局域网同步走 **cuplivo 式的真双向对称**。

保留增量备份的理由（已核对代码）：它已经提供了双向同步最贵的两块底座——

- [BackupObjectStore.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/sync/repo/BackupObjectStore.kt#L23-L79)：
  `listChildren/stat/statFresh/missingKeysFresh/get/put/delete/ensureDir` + `destinationId`，
  已有 SAF / WebDAV / S3 三个实现。
- [BackupSnapshotManifest.kt](file:///f:/build/projects/RikkaHub-Neomix/app/src/main/java/app/androidrik/rikkahub/data/sync/repo/BackupSnapshotManifest.kt#L40-L105)：
  内容寻址 `v3/objects/<sha256>`、清单 `v3/snapshots/<id>.json.gz`、完成标记 `<id>.complete.json`、
  `deviceId/dbVersion/formatVersion`，以及 P2 稳定逻辑键 `conversation:<uuid>`（会话没变就不重传）。

代价（一并接受）：仓库层是备份域最复杂的一块（Engine/Service + 4 个 object store 实现 + Packing +
Codec + 6 个测试），需要长期维护；FTS 重建链必须保留。

## 不做的事（相对上一版的差异）

原方案的以下删除动作**全部取消**，原样保留：

- `data/sync/repo/` 下 `IncrementalBackupEngine / IncrementalBackupService / SafBackupObjectStore /
  BackupObjectStore / RemoteBackupObjectStores / SnapshotPacking / SnapshotObjectCodec /
  BackupSnapshotManifest / BackupSnapshotCapture / FtsRebuildPending`
- FTS 重建链：`RikkaHubApp.rebuildSearchIndexIfPending()/retrySearchIndexRebuild()` →
  `SearchIndexRebuildBanner` → `RouteActivity` → `SearchIndexRebuildSignal` → `SnapshotBackupTab`
- `SnapshotBackupTab.kt` / `Screen.BackupSnapshot` / `BackupDetailPages.BackupSnapshotPage` 路由
- `BackupVM` 的 snapshot 状态与方法、`BackupRunCoordinator` 的 `SNAPSHOT_*` kind、
  `DataSourceModule:407-411` 的 `IncrementalBackupService` 注册
- `FileFolders.BACKUP_INDEX` 常量与 `BackupFolderRegistry` 的豁免 spec
- `backup_page_section_experimental` / `backup_page_snapshot_title/desc`（三 locale）
- 全部增量相关测试（`IncrementalBackupEngineTest` 等 7 个 unit + 3 个 androidTest）

唯一可选的文案调整（不阻塞）：把入口卡从「实验功能」区块移到「备份管理」，措辞从"实验"改为
「快照仓库」。留作后续产品决定。

---

# Phase 1：本地自动备份（kelivo「本地副本」式）

与增量仓库**并列共存**，职责不同：本地副本=防丢（全量 zip + 保留 N 份）；快照仓库=去重/跨设备传输。

## 决策（沿用已确认结论）

| 决策点 | 结论 |
|---|---|
| 存储位置 | SAF 用户选定目录（`ACTION_OPEN_DOCUMENT_TREE` + persistable permission，拒绝本应用 workspace）。卸载不丢；授权失效时 UI 提示且自动备份跳过并记失败 |
| 触发模型 | 应用内 resume 延迟检查：冷启延迟 8s、resume 延迟 3s、最小检查间隔 1 分钟；`RikkaHubApp` 注册 `ActivityLifecycleCallbacks`，协程跑 AppScope。不引入 WorkManager |
| 命名 | `local_backup_yyyyMMdd_HHmmss.zip`（本地时区），文件名即排序依据，无索引文件 |
| 留存 | 纯函数 `LocalSnapshotRetentionPolicy`：最近 N 份（默认 3，1-10）+ 1 个 weekly 槽（满 7 天最新）+ 1 个 monthly 槽（满 30 天最新）；**最新一份非空副本永不删**；容量上限（0=不限）从最旧删起、保护集不越界 |
| 打包/恢复 | 复用 `BackupArchiveEngine` 全量打包（尊重 `backupItems/backupScope` + 加密 tile）；恢复复用 `BackupVM.restoreFromLocalFile`（merge/overwrite 原样） |
| 变更门控 | 指纹 = conversations `COUNT+MAX(update_time)` + assistants `COUNT`；一致则跳过且不推进 lastSuccessAt |
| 失败退避 | 失败始终提示；连续失败 1/2/4/8/16h 指数退避 |
| 设置 | `DisplaySettings.kt` 紧邻 `BackupReminderConfig`（349-353 行）新增 `@Serializable LocalSnapshotConfig`；运行态 `LocalSnapshotState` 存 PreferencesStore 独立 key；写入一律字段级 `settingsStore.update { it.copy(...) }` |
| 执行通道 | 新增 `BackupRunKind.LOCAL_SNAPSHOT` 进 `BackupRunCoordinator`（复用 FGS + 进度通知 + 单任务互斥） |

## 新增代码

- `data/sync/localsnapshot/LocalSnapshotRetentionPolicy.kt`（纯函数）
- `data/sync/localsnapshot/LocalSnapshotService.kt`（SAF attach/detach + 打包 + 留存 + 指纹门控）
- `ui/pages/backup/LocalSnapshotsPage.kt` + `Screen.LocalSnapshots` + `AppNavHost` entry
- `BackupPage.kt` 新增「自动备份」section（开关/频率/保留份数/容量上限/结果提示/占用/立即备份/管理副本）
- DI 注册、`local_snapshot_*` 三语字符串、单测（留存纯函数 + 门控）

---

# Phase 2：双向对称局域网同步（cuplivo 模式）

## 2.1 复用边界

| 环节 | 复用现有实现 |
|---|---|
| 对象身份 | `conversation:<uuid>` 稳定逻辑键 + sha256 内容寻址 |
| 捕获 | `BackupSnapshotCapture` / `DatabaseObjectCapture.captureConversationsOnly` |
| 落库 | `DatabaseObjectMerge.mergeMissingConversationsLive`（新增路径） |
| 范围 | `BackupScope` / `BackupItem` |
| 加密 | 现有加密 tile |
| 进度/互斥/FGS | `BackupRunCoordinator` + `BackupForegroundService` |
| 服务端引擎 | `ktor-server-cio`（`gradle/libs.versions.toml:196` 已有，web 模块 `Entry.kt` 是现成用法） |

**不能复用的一处**：`DatabaseObjectMerge` 只有"缺失即插入"的加性语义，**没有删除传播**。
RikkaHub 也没有回收站（已 grep 确认）。双向同步必须新增墓碑机制（见 2.4）。

## 2.2 新增模块 `data/sync/lan/`

- `LanIdentity.kt`：首次启动生成密钥对 + 自签证书（AndroidKeyStore），`certSha256` 即设备身份；
  `deviceId` 沿用仓库 manifest 的 `deviceId` 字段，若为空则以 `certSha256` 补齐。
- `LanSyncServer.kt`：Ktor CIO，**只绑定局域网私有地址**（枚举 `NetworkInterface`，取 site-local IPv4），
  端口默认随机（配对时告知），可配置固定端口。
- `LanSyncClient.kt`：OkHttp + 自定义 `X509TrustManager`，**只信 peer registry 中 pin 的 SHA-256 指纹**，
  不信任系统 CA；连接超时按 cuplivo 的经验值（LAN dial 短超时、传输长超时）。
- `LanPeerRegistry.kt`：DataStore JSON，记录 `{name, lastEndpoints[≤8], certSha256, token, lastSyncAt,
  protocolVersion}`。
- `LanManifest.kt` / `LanManifestCodec.kt`：线协议模型与序列化。
- `LanMergePlan.kt`：**纯函数**，输入双方 manifest，输出 `toPush / toPull / toDelete / conflicts`。
- `LanMergeApply.kt`：把 plan 落到数据库（新增走现有 merge，冲突/删除走新增能力）。
- `LanPairingController.kt`：PIN + 二维码。
- UI：`ui/pages/settings/SyncPage.kt`（本机指纹卡 + peer 卡列表 + 添加设备 + 每 peer「立即同步」），
  peer 卡显示阶段与结果（对齐 cuplivo 的阶段枚举）。

## 2.3 协议 v1

统一信封带 `protocol: 1`；对方 `protocol` 更高 → 拒绝并提示升级（不做降级）。

```
POST /v1/pair        {pin, protocol, deviceId, name}          → {token, deviceId, name}
POST /v1/manifest    LanManifest                              → {manifest, plannedAt}
HEAD /v1/objects/{sha256}                                     → X-Rikka-Object-Size
GET  /v1/objects/{sha256}          (支持 Range，断点续传)     → bytes
HEAD /v1/inbox/{sha256}                                       → X-Rikka-Offset
PUT  /v1/inbox/{sha256}            （Content-Range 分段上传）  → 204 + X-Rikka-Offset
POST /v1/applied                                              → {added, updated, deleted, conflicts}
```

所有已配对请求带 `X-Rikka-Token` 和 `X-Rikka-Protocol`。配对窗口开放两分钟，PIN 错误五次锁定 60 秒。接收同步时选定一台已配对设备，页面在前台最多开放五分钟并显示本次地址。

`LanManifest` 形状：

```json
{
  "protocol": 1,
  "device": {"id": "<certSha256>", "name": "...",
             "dbVersion": 81, "formatVersion": 1},
  "conversations": [{"key": "conversation:<uuid>", "sha256": "...", "updateTime": 0,
                     "size": 0, "attachmentShas": ["..."]}],
  "assistants":    [{"key": "assistant:<uuid>", "sha256": "...", "updateTime": 0}],
  "tombstones":    [{"key": "conversation:<uuid>", "deletedAt": 0}]
}
```

`dbVersion` / `formatVersion` 不一致 → 拒绝同步并提示先升级（避免跨 schema 合并）。

## 2.4 合并规则（确定性、无方向性）

规则对称，双方用同一函数、同一输入各自 plan，结果互补（cuplivo `sync_merge.dart` 的原则）：

1. 双方都有、`sha256` 相同 → 跳过。
2. 双方都有、`sha256` 不同 → 比较 `updateTime`，新者胜；**相等则比 sha256 字典序较小者胜**
   （消抖，避免双方同时改写导致来回震荡）。败者写入「冲突留存」（复用 `RecoveryBackupExporter`
   的安全备份模型），会话败者额外把其 `messageNodes` 作为分支候选追加，不丢内容。
3. 单方有、对方无、且无更晚墓碑 → 缺失方拉取（内容寻址去重，只传差集）。
4. 单方有、对方有墓碑：`tombstone.deletedAt > object.updateTime` → 删除；否则保留（复活优先）。
5. 会话墓碑保留 30 天。Room v81 将墓碑与删除放在同一事务里，撤销删除会清除墓碑并将会话更新时间推进到删除时间之后。

删除墓碑产生点：现有删除入口（会话批量删除、单会话删除、助手删除）在 `AppScope` 内追加写墓碑。
按项目既有约定，附件删除前仍要做引用计数检查。

## 2.5 传输与应用流程

```
1 connecting  拨号（记忆端点逐个试，短超时），TLS 握手后校验 certSha256 pin
2 exchanging  发起方提交自己的清单，接收方返回冻结清单和用于双方规划的时间
3 sending     上传对方需要的对象，包含用于保留冲突分支的版本和所引用附件
4 receiving   下载本机需要的对象；两个方向按内容寻址并续传
5 applying    接收方确认对象齐全后先提交，发起方收到回执后提交；各自使用 Room 事务
6 report      写 lastSyncAt、展示摘要（新增/更新/删除/跳过/冲突）
```

附件：随会话对象引用传输，落 `files/upload`、`images` 等现有目录并保留 mtime；传输后校验 sha256。
字节预算：单次会话上限（例如 64 MB），超出则记录并在下一次续传，避免长任务被 Doze 打断。

## 2.6 安全不变量

- 证书 pin 先于任何业务 API；未配对设备无法访问任何端点。
- token 只经配对通道（PIN + 二维码同屏验证）交换，不出现在日志。
- 服务端只 bind 私有地址，不监听 `0.0.0.0`。
- 所有附件入本地前校验 sha256。
- 合并幂等：重复同步零副作用（内容寻址 + 墓碑单调）。
- 删除/覆盖前先落安全备份（沿用 `RecoveryBackupExporter`）。

## 2.7 风险与取舍

| 风险 | 处置 |
|---|---|
| 长传输被 Doze/后台限制 | 复用 `BackupForegroundService` + `BackupRunCoordinator` 的 FGS 与通知；单次预算 + 续传 |
| 无 mDNS，IP 变化 | MVP 手输 `IP:port` + 记忆端点列表（≤8）；mDNS/NsdManager 留作后续 |
| 证书轮换 / 重装 | 视为新设备，需重新配对；文档与 UI 明确提示 |
| 双方同时改同一会话 | `updateTime` + sha256 字典序 tiebreak；败者留冲突备份与分支候选 |
| 跨 schema 合并 | `dbVersion/formatVersion` 不一致直接拒绝 |
| Ktor server 常驻开销 | 仅同步页/同步期间启动 server，空闲 5 分钟后自动停止（可配置常驻） |
| 移除离线设备的数据 | 不做强制删除；墓碑只从对端收到的删除事件内产生 |

## 2.8 首期数据范围（最小集）

会话（含分支树）+ 被引用附件 + 助手。设置、供应商、技能、世界书等**不同步**（设备个性配置），
留待后续按 family 扩展（对齐 cuplivo 的 family registry 思路）。

---

# 执行顺序

1. **Phase 1**：`LocalSnapshotConfig/State` → 留存纯函数 + 单测 → `LocalSnapshotService` + DI →
   调度器 + `BackupRunKind.LOCAL_SNAPSHOT` → UI + 三语字符串 → 编译 + `python scripts/audit_ime_trap.py`
   + 真机验证（立即备份/自动触发/留存清理/授权失效/双模式恢复）
2. **Phase 2A**：`LanIdentity` + `LanSyncServer`（仅 `/v1/pair`）+ `LanPeerRegistry` + 配对 UI，
   两台设备能配对成功
3. **Phase 2B**：`LanManifest` + `LanMergePlan`（纯函数）+ 单测（对称收敛、并列时不震荡、墓碑四种组合）
4. **Phase 2C**：`LanSyncClient` + 对象传输（Range/重试）+ `LanMergeApply`（含冲突 upsert 与墓碑删除）
5. **Phase 2D**：同步页 + peer 卡 + 阶段进度 + 报告摘要 + 三语字符串
6. **Phase 2E**：双机真机验证矩阵（新增/更新/冲突/删除传播/断网重连/跨版本拒绝）

# 验证

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests "app.androidrik.rikkahub.data.sync.*"
.\gradlew.bat :app:testDebugUnitTest
python scripts/audit_ime_trap.py
```

Phase 2 需两台真机同局域网：配对 → 首次全量同步 → 各自改一条会话再同步（双向收敛）→ 删除一条
会话再同步（对端消失，附件不被误删）→ 断网重连续传 → 一端升 `dbVersion` 后同步被拒绝。

## 实施准备（2026-11-25）

当前基线通过 `:app:compileDebugKotlin`、305 项备份相关 JVM 单测和 IME 审计（A／A′ 均为 0）。ADB 当前连接一台 Android 15 真机，可用于 Phase 1；Phase 2 同步验收需要第二台设备，Android 17 权限分支另需相应系统版本。

### Phase 1 首批改动

1. 接入 `LocalSnapshotConfig`、独立运行态和留存纯函数，先覆盖留存与触发门控的边界用例。目录 URI 和运行态属于本机，走 `BackupPortability` 时保留本机值；更换目录后重新判断首次备份。达不到容量上限时，建议先保住受保护副本并显示超额。
2. 走 `BackupRunCoordinator` 和 `BackupArchiveEngine` 完成一次手动备份，再接入自动调度。SAF 写入先用临时文件，完整写入并核验后再发布为正式文件名；留存在发布后执行。取消、同秒文件名冲突和授权撤销均需保住已有副本。增量仓库也可能使用同一树 URI，解绑时需保留其他功能仍使用的授权。
3. 接入备份页、恢复入口和三语文案。恢复沿用现有预检、合并／覆盖选择和重启收尾。自动调度在延迟结束后复核真实设置已加载、应用还在前台；协调器正忙时延后，失败退避只计算实际运行失败。真机使用 `-PbackupIsolatedTests=true` 构建隔离包，验证失败副本不会触发留存清理，并实际恢复一次合并和一次覆盖。

### 开工时需要处理的实现差异

- `BackupArchiveWriter` 生成普通 ZIP，当前备份入口没有整包加密选项。若首期要求跨安装恢复加密副本，需先定加密格式与解锁方式。现有 ZIP 的读取和恢复兼容性纳入回归。
- Room 会话更新时间列是 `update_at`。标题和建议有单列更新路径，助手编辑也会维持原数量，附件、设置和工作区改动同样可能不改变会话聚合值。门控应按 `backupItems`／`backupScope` 判断已覆盖的变化；对无法可靠判定的内容，到期应实际备份，避免长期漏备。取消、失败、跳过均不更新 `lastSuccessAt`。

### Phase 2 开始前的验证

- 本仓的 Ktor CIO 3.5.1 会拒绝 HTTPS connector。2A 先验证能在 Android 上使用 AndroidKeyStore 私钥进行 TLS 握手的服务端实现，并复用现有 `ACCESS_LOCAL_NETWORK` 权限入口，覆盖拒绝和撤销。首次配对须从可信的同屏信息预先取得服务端指纹，并在发出 PIN 前核验；双方设备身份也要绑定到各自证书。[Ktor 3.5.1 源码](https://github.com/ktorio/ktor/blob/3.5.1/ktor-server/ktor-server-cio/common/src/io/ktor/server/cio/CIOApplicationEngine.kt#L191-L213) · [Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)
- 冻结线协议前补齐发起方如何提交清单、响应方何时应用、超限后如何续传与确认。助手当前没有可用于冲突排序的更新时间，需先定义持久化版本及已有数据的初始值。
- 落库前验证墓碑与实际删除可恢复地提交，覆盖历史页撤销删除，以及助手删除引发的会话级联。补测重复同步同一冲突不会反复生成分支。现有 `mergeMissingConversationsLive` 在事务提交后才查外键，局域网应用路径需在提交前完成完整性检查，并验证 Room 观察者、FTS 与附件引用能看到更新。

### 实施进度

- Phase 1 已接入备份页。隔离 Android 15 真机通过手动备份、冷启动自动备份、留存清理和无授权目录退避。重启后的合并恢复保留本地测试会话，覆盖恢复用备份中的会话库替换本地库。
- 本地快照支持整包密码加密。外层 ZIP 使用单个 WinZip AES-256 条目 `rikka-backup.zip` 保存完整的原始备份，恢复页解锁后沿用原有预检及合并／覆盖流程。普通 ZIP 仍可导入。Android 15 真机生成的副本已在隔离模拟器验证错误密码拒绝、正确密码解锁和覆盖恢复重启。
- Phase 2A 的 `/v1/pair` 使用平台 `SSLServerSocket` 和 AndroidKeyStore 证书，客户端先校验证书指纹，再发送 PIN。配对页支持短时邀请码、二维码和设备列表。真机和同局域网桌面测试端验证了发起与接收，并覆盖错误 PIN、错误指纹、二维码导入和重复请求。Android 15 真机作为邀请方、Android 15 模拟器作为加入方，已验证扫码后两端均显示对方。
- Phase 2B–2D 接入清单交换、双向对象传输、Room 应用、删除传播和冲突分支。已配对设备页可打开同步页，显示接收地址、传输阶段和双方结果。当前同步范围为会话和所引用的本地附件。若接收设备没有同 ID 的助手，会建立本地占位助手以保留会话归属。同步删除保留附件文件，供其他会话和恢复使用。
- Android 15 真机和 Android 15 模拟器使用独立的 v80 测试库，经应用启动迁移到 v81 后完成扫码配对。首轮同步两端各新增一条带图片的会话，第二轮两边均为零变更、零冲突。退出进程后核对了两份 Room 数据库及双向图片字节，并确认收到的附件进入文件引用索引。同步写入保留发送端的末条消息时间和激活分支，重试时不会按设备时区重新计算时间。
- 模拟器仪器测试覆盖两份 Room 库之间的上传中断续传、重复回执、冲突分支收敛、删除传播、共用附件保留和撤销删除。

### 双实体设备验收（2026-11-25）

- Android 15 的 PJX110 与 23076RA4BC 安装同一 ARM64 隔离版，两台设备的已安装 APK 与本地产物 SHA-256 一致。扫码后两端显示对方。两份独立 v80 测试库升级到 v81，双方各取得对端的会话和图片。交换发起端重复同步时，两边均为零变更、零冲突。
- 两机分别修改各自的会话，两份修改均落库。同时修改同一会话后，两边各报告一处冲突，两份内容均可从各自的 Room 分支读取。
- 两个附件共 66 MiB。接收端暂存约 6 MiB 时中止应用进程，重新开启接收后续传完成。两台设备上的两个附件均通过 SHA-256 检查。
- 验收发现输入法可产生全角地址标点，地址解析兼容了该输入。传输可复用本机清单中的同哈希对象，应用前仍校验内容。清掉两端的传输暂存后，带有这 66 MiB 附件的会话在撤销删除后一次同步完成。
- 从 Xiaomi 的历史页删除测试会话后，同步将删除传播到 OnePlus，两边均有对应墓碑。删除端到期清除了该会话独占的附件，两端仍可读取另一条会话所引用的共用附件，文件哈希一致。
- Xiaomi 先用已配对身份读取 OnePlus 的 v81 清单，再发送声明 v82 的模拟清单，OnePlus 返回 HTTP 426。两台设备的数据库均为 v81。最终 JVM 回归为 3,784 项通过、2 项跳过，Xiaomi 上的 Android 仪器测试 4 项通过。
- Android 17 局域网权限验收另行开展。
