# 增量备份设计稿：文件区内容寻址 + DB 分层增量

> 状态：**设计草稿（2026-11-03）**。**P0 前置修复已落地**（2026-11-03，见 §2.4）；P1/P2 未实施。
> 本稿是「如何实现增量备份」的完整设计输入，不是已完成功能的记录。
>
> 调研依据：本仓只读源码核对 + 真机数据实测（`_measure/m.db` 115 MB / `_measure/settings.pb` 1.45 MB），
> 四份调研底稿在 `.tmp/incremental-backup/`（`.tmp/` 已 gitignore，**不入库**；本稿已内联需要的证据）。
> 上游诉求：rikkahub#2280「feat: 支持增量备份」（open，2026-10-31，正文「每次备份都要打包并上传一个
> 不断膨胀的压缩包，死慢死慢的」）<https://github.com/rikkahub/rikkahub/issues/2280>。
>
> 关联设计稿：`design-backup-workspace-files.md`（备份范围/大文件加固）、
> `design-backup-manifest-and-safety.md`（manifest v2 + 校验）、`design-backup-staged-restore.md`（staged 换库）、
> `design-conversation-import-export.md`（对象导出格式）、`design-export-retention.md`（本地留存）。
>
> 约定：`A/` = `app/src/main/java/app/androidrik/rikkahub/`，`T/` = `app/src/test/java/app/androidrik/rikkahub/`，
> `D/` = `docs/design/`。行号对应当前工作树（2026-11-03）。

## 0. TL;DR（先看这里）

- **现状**：每次备份 = 一份**全量 zip**（settings.json + rikka_hub.db + FILES + WORKSPACE_FILES），
  上传到 WebDAV/S3 或用 SAF 导出。远端**只增不减**、无逐文件清单、无上传断点。
  最坏数据量 2.8 GB（工作区，近不可压缩），20 Mbps 下 ≈ 19 分钟；DB 115 MB **每次整库重传**。
- **方案骨架**：把一次备份拆成「**自包含快照清单** + **内容寻址对象**」：
  - 快照清单列出该次备份的**全部文件**（路径 → 原始内容 SHA-256 → 对象位置）以及 DB/settings 对象；
  - 对象按**内容哈希**命名，内容没变就不重传；快照之间**没有父子链**（每份都能独立恢复），
    因此删旧快照/上传中断都不会断链，WebDAV 缺少 MOVE/条件写也能工作。
- **分两层落地**：
  - **P1（推荐先做）**：文件区（FILES + WORKSPACE_FILES）增量；DATABASE 仍整库快照、settings 整份，
    二者各自作为内容寻址对象；恢复 = 本地拼回一份 v2 zip → **复用现有 `restoreFromBackupFile`**
    （破坏性路径、manifest 校验、safety_backup、staged 换库全部零改动）。
  - **P2（条件成熟再做）**：DB 逻辑增量（会话/设置/记忆等按对象，剔除可重建的 FTS），
    把「每次 ~50 MB DB 重传」降到「只传改动的会话（通常 KB～MB 级）」；恢复在启动期把对象合并进
    现库副本再走 staged 换库，不改 Room 写路径。
- **明确不做**：CDC/restic 全套块级去重（收益窄、状态机重）、`sqlite3session`（需自编 native/JNI）、
  按 `updatedAt` 拉行（本仓 `update_at` 漏写 10+ 处、`message_node` 无时间戳）、
  把「自动定时备份」顺带实现（独立产品决策）、本地 SAF 增量（单文件语义不支持目录树）。
- **顺带发现并已修的 4 个既有缺口（P0，2026-11-03 落地）**：
  ① `backgrounds/` 打包但恢复分支缺失（恢复后助手背景丢）；② `avatar-frames/` 既不打包也不豁免；
  ③ 工作区条目没接 STORED 压缩策略（2.8 GB git pack 白烧 DEFLATE）；
  ④ 远端列举缺口：S3 `maxKeys=1000` 不翻页（>1000 对象旧备份静默不可见）、WebDAV `path` 含 `/` 被编码成 `%2F`
  （放不了多级对象目录）。详见 §2.4。

## 1. 问题与目标

### 1.1 现状与痛点（实测/代码事实）

| 事实 | 数值/位置 | 证据 |
|---|---|---|
| 每次备份都是完整 zip | settings + 可选 DB/FILES/WORKSPACE_FILES + 末条目 `manifest.json` | `A/data/sync/BackupArchiveEngine.kt:62-158` |
| 备份文件名 | `backup_yyyyMMdd_HHmmss.zip`（每次新名字，天然不覆盖） | 同上 `:66-68` |
| 远端保留 | **无**：WebDAV/S3 只增不减，删旧只有 UI 手动 | `A/data/sync/S3Sync.kt:77-126`、`A/data/sync/webdav/WebDavSync.kt:80-130` |
| 远端可列举字段 | 只有名字/大小/修改时间；无逐文件清单 | `A/data/sync/S3Sync.kt:84-93`、`A/data/sync/webdav/WebDavSync.kt:88-97` |
| 逐文件哈希 | **无**（manifest 只有 section 汇总 + settings/主库双 SHA-256） | `A/data/sync/BackupManifest.kt:16-37` |
| 上传断点 | 无（S3 分片 uploadId 只在内存；WebDAV 单 PUT） | `A/data/sync/s3/S3Client.kt:131-206` |
| DB 体量 | 115 MB（`_measure/m.db`，page 1 KiB），其中 98.9% 是消息与 FTS | 本稿 §2.1 实测 |
| DB 增量现状 | `VACUUM INTO` 每次生成整份新快照 → 每次都全量上传 | `A/data/sync/BackupArchiveEngine.kt:641-677` |
| 工作区最坏量级 | 2.8 GB/次（用户实测，不可压缩数据 DEFLATE 1.000x） | `D/completed/design-backup-workspace-files.md:164-171` |
| 恢复前安全备份 | 本地 `safety_backup/` 滚动 3 份（**仅本地**） | `A/data/sync/BackupArchiveEngine.kt:482-497` |

### 1.2 目标

1. **上传量随「新增内容」增长**而不是随「总数据量」增长：首次全量，之后只传新增/变更文件
   （以及 P2 后只传变更的会话对象）。
2. **每份快照可独立恢复**：不依赖上一份快照是否还在，删除旧快照不会让新快照失效。
3. **恢复安全性不降级**：保留现有「校验通过才执行破坏性操作」、恢复前 safety_backup、
   staged 换库、fail-closed 回滚；新增路径不得绕过这些机制。
4. **与旧全量 zip 长期共存**：旧版 App 看不到新结构但不被破坏；新版 App 同时能恢复旧 zip；
   旧版 App 做的 zip 仍走原路径。
5. **不动 Room 写路径**（P1 与 P2 都是）：备份只读查询；恢复在启动窗口内换库/合并。

### 1.3 非目标（本稿明确不解决）

- 自动/定时备份（复用 `service/ScheduledTaskAlarms.kt` + WorkManager 是独立立项，见 §8 Q3）。
- 备份加密（上游 #2254，独立立项，需 AES-GCM + Keystore，加在快照层之上）。
- 双向同步 / 跨设备合并（本方案是单向备份，多设备只共享内容寻址对象，不做合并语义）。
- 本地 SAF 导出改成增量（SAF 是单文件/单目录语义，维护远端式对象树代价不成比例；本地导出仍全量 zip）。
- 块级 CDC/restic/borg 全套（见 §4 方案 D）。

### 1.4 收益预期（粗估，需真机实测校验）

> 假设：DB 压缩率 2.27x（本仓实测）、媒体/工作区近不可压缩（1.0x）；「增量」只相当于改动量，
> 且**首次仍是全量**。以下是量级判断，不是承诺指标。

| 用户画像 | 全量 zip 上传量级 | P1（文件区增量 + DB 整库对象） | P2（再加 DB 对象增量） |
|---|---:|---:|---:|
| 聊天型（DB 115 MB、文件区 300 MB，单次改动 ~10 MB 文件 + 少量会话） | ≈ 350 MB | ≈ 50 MB（DB） + 10 MB ≈ **60 MB**（约 6x↓） | ≈ 10 MB 文件 + 1～2 MB 会话 ≈ **12 MB**（约 29x↓） |
| 工作区型（DB 115 MB、工作区 2.8 GB，单次改动 ~50 MB） | ≈ 2.9 GB | ≈ 50 MB + 50 MB ≈ **100 MB**（约 29x↓） | ≈ 50 MB + 60 MB ≈ **110 MB**（DB 不再是大头） |

关键点：**上传量由「本次改动」决定，而不是由「历史总量」决定**；工作区型用户收益最大，
聊天型用户的 DB 部分要等 P2 才完全消掉（P1 仍每次传整库对象）。收益/成本是否值得，
应在 P1 上线后用真实数据（planner 日志里的「计划上传 / 复用」）校准。

## 2. 现状核实（证据）

### 2.1 备份数据构成与量级（决定增量切哪里）

真机快照 `_measure/m.db`（115,393,536 B，`user_version=38`，250 会话 / 7,428 消息节点 / 7,606 FTS 行）
的 dbstat/列长实测：

| 组成 | 页/字节足迹 | 占比 | 可变性 | 增量策略 |
|---|---:|---:|---|---|
| `message_node.messages` | 49.59 MB（列长实测） | 45.1%（页迹） | 追加为主，编辑/删除会原地改写 | P2：按会话对象增量 |
| `message_fts_data`（FTS5 倒排） | 37.55 MB（列长实测） | 34.1% | **派生数据**，可重建 | **不进备份**，恢复后重建 |
| `message_fts_content`（FTS5 原文副本） | 20.52 MB（列长实测） | 19.7% | 同上 | **不进备份** |
| 其余 22 张表（会话行/记忆/收藏/工作区/…） | ≈0.75 MB | **0.7%** | 低频 | 按对象/整表，P2 |
| 附件二进制 | **不在 DB**：part 只存 `file://` URL | — | — | 走 FILES 文件区 |

结论：
- **DB 的 98.9% 是「消息文本 + 可重建 FTS」**；FTS 不备份可省 53.8%。
- DB 增量是**纯文本**问题，体积远小于工作区/附件。
- 文件区才是上传量支配项（最坏 2.8 GB），且 6/7 个 FILES 目录是 UUID/时间戳命名的**不可变文件**
  （见 §2.2），文件级哈希足够，不需要块级去重。

settings 真机样本 `_measure/settings.pb`：**1.45 MB / 75 个 key**，其中 `prompt_presets` 76.2%、
`mcp_servers` 10.8%、`providers` 4.3%、`lorebooks` 4.2%、`assistants` 3.6%。
备份产物是整份 `settings.json`（`A/data/sync/BackupArchiveEngine.kt:95-103`），一次改任意 key 整段都变 →
P1 用整对象 + SHA-256 门控（未变则不上传），P2 再考虑按 key 存（收益有限）。

### 2.2 文件区清单、形态与可变性

`BACKED_UP_FILE_FOLDERS`（`A/data/sync/BackupItem.kt:42-50`）：
`upload / images / avatars / backgrounds / fonts / skills / media_creation`；
其中 `skills / images / media_creation` 递归（`:62-66`），其余平铺。
`WORKSPACE_FILES` = `workspaces/<root>/files/**` 递归，排除 rootfs（≥30 MB/工作区）与 tmp
（`A/data/sync/WorkspaceBackupHelper.kt:71-117`、`D/completed/design-backup-workspace-files.md:22-46`）。

| 目录 | 写入方/命名 | 内容可变？ | 增量判定 |
|---|---|---|---|
| `upload` | `UUID.ext`（`A/data/files/FilesManager.kt:333`） | **不可变** | 路径+size 即可，hash 兜底 |
| `avatars` / `backgrounds` | UUID（`FilesManager.kt:219,299`） | **不可变** | 同上 |
| `images` | `<ts>_<model>_<i>.png` | **不可变** | 同上（含旧版子目录） |
| `media_creation` | `<sessionUuid>/<recordUuid>/<uuid>.ext` | **不可变**（下载中 `.part` 除外） | 同上 |
| `fonts` | `chat_font.<ts>.<ext>` | 换字体=新名+删旧 | 路径+size |
| `skills` | 技能名目录，保存=staging + 整目录 rename | **可变** | (path,size,mtime)→sha256 |
| `workspaces/*/files/**` | 用户/AI 任意命名、可就地改写 | **可变** | (path,size,mtime)→sha256 |

已有可复用范式：`RemoteFileStore` 的 `(length,lastModified)→sha256` 缓存
（`A/data/files/RemoteFileStore.kt:163-175`）、会话导出 `attachments/<sha256前16>` 内容寻址
（`A/data/export/ExportAttachment.kt:47-56`）、`BackupManifest.sha256Hex`。

**注意**：备份目录内会混入瞬态文件（`atomic-*.tmp`、`skills/.<name>.staging.*.tmp`、`media_creation/**/*.part`、
`fonts/chat_font_import.tmp`），增量清单必须显式跳过，否则每次备份都「有变化」。

### 2.3 现有实现与传输缺口（增量必须骑上去的部分）

| 环节 | 现状 | 证据 |
|---|---|---|
| 打包 | 逐文件写 zip entry；大文件采样判 STORED | `A/data/sync/AppFilesPacking.kt:97-133`、`A/data/sync/BackupZipStrategy.kt:18-53` |
| 工作区打包 | 直接 `putNextEntry`，**没接 STORED** | `A/data/sync/WorkspaceBackupHelper.kt:104-115` |
| DB 快照 | `VACUUM main INTO` 一致性快照；失败回退热拷三件套 | `A/data/sync/BackupArchiveEngine.kt:637-677` |
| 校验 | manifest v2：settings/主库双 SHA-256 + section 条目数；纯函数 `verifyBackupManifest` | `A/data/sync/BackupManifest.kt:87-133` |
| 恢复 | 先读毕校验 → staging DB → safety_backup → settings 写入 → PendingRestoreStore 启动期换库 → 强制重启 | `A/data/sync/BackupArchiveEngine.kt:194-474`、`A/data/sync/PendingRestoreStore.kt:26-176` |
| 上传 | S3 单 PUT/8 MB 分片（片级重试 1 次）；WebDAV 单 PUT | `A/data/sync/s3/S3Client.kt:82-318`、`A/data/sync/webdav/WebDavClient.kt:90-121` |
| S3 列举 | 解析了 ETag/Size/LastModified，客户端支持 continuationToken，但 `S3Sync` **只取首页 1000 条** | `A/data/sync/s3/S3Client.kt:489-527`、`A/data/sync/S3Sync.kt:77-95` |
| WebDAV 列举 | `PROPFIND Depth:1`，只请求 5 个属性，无 `getetag`、无分页、不递归 | `A/data/sync/webdav/WebDavClient.kt:281-345` |
| WebDAV 多级路径 | `buildUrl` 把 `config.path` 当**一个**路径段编码：`path` 或 `path` 参数里的 `/` → `%2F` | `A/data/sync/webdav/WebDavClient.kt:47-59` |
| 条件写/Range/MOVE | 全缺（S3/WebDAV 客户端均无 If-Match、Range GET、MOVE/COPY） | 同两文件方法清单 |

**ETag 不能当内容哈希**：S3 分片上传的 ETag = `md5-of-part-md5-N`（本仓 Complete 请求就是拿分片 ETag 拼的
`A/data/sync/s3/S3Client.kt:278-285`），WebDAV ETag 由服务端定义且当前没请求。
→ 增量清单必须自建 SHA-256；ETag/mtime 只能用于「跳过上传」的快速路径，不能用于恢复校验。

### 2.4 顺带发现的既有缺口（P0：做增量之前先修；**已落地 2026-11-03**）

这些不是「增量」独有，而是调研中核实的现有正确性问题；不先修，增量清单会把它们固化：

1. **`backgrounds/` 打包但恢复无分支**：`BACKED_UP_FILE_FOLDERS` 含 `FileFolders.BACKGROUNDS`
   （`A/data/sync/BackupItem.kt:46`），但 `restoreUserFileEntry` 的 `when` 没有 `backgrounds/`
   分支（`A/data/sync/BackupArchiveEngine.kt:270-316`）⇒ 恢复后助手背景文件不落盘（设置里的引用悬空）。
   该目录 2026-10-31 才独立出来（`A/data/files/FilesManager.kt:922`），恢复侧漏改。
2. **`avatar-frames/` 既不打包也不豁免**：`A/data/files/FilesManager.kt:909` 有真实写入方
   （`:245-279`），但不在 `BACKED_UP_FILE_FOLDERS` 也不在 `EXCLUDED_FILE_FOLDERS`
   （`A/data/sync/BackupItem.kt:42-50,96-108`）⇒ 头像框素材静默不备份。
3. **`BackupCoverageTest` 的目录全集是手写集合**（`T/data/sync/BackupCoverageTest.kt:19-32`），
   不是从 `FileFolders` 枚举派生 ⇒ 上述两类缺口测试不会红；且没有任何测试覆盖恢复分派 `when`。
4. **S3 列表不翻页 / WebDAV 路径不可多级**（见 §2.3 表最后两行）：增量方案要写对象目录树，
   两处都必须先具备；S3 顺带修好「>1000 对象旧备份静默不可见」。

**P0 修法**（小而独立，可单批上线）：
- 把「目录全集 → 打包形态 → 恢复分派 → 豁免理由」收敛为**一份数据**（`BackupFolderRegistry`），
  打包/预检/恢复/测试都由它派生；恢复按 registry 分派，缺分支即编译期/测试期失败。
- 补 `backgrounds/` 恢复分支；`avatar-frames/` 明确「进备份」或「写豁免理由」二选一（推荐进备份，体积小）。
- 工作区打包接 `BackupZipStrategy`（复用 AppFilesPacking 的 `prepareZipEntry` 思路）——**P1 增量不再走
  工作区 zip 也能受益，但全量 zip 路径仍在使用，值得修**。
- `S3Sync.listBackupFiles` 改为循环 `continuationToken`；`WebDavClient.buildUrl` 按 `/` 拆段逐段编码，
  新增 `mkdirs(path)`（逐级 MKCOL）供对象分片目录使用。

**P0 落地记录（2026-11-03，工作树）**：

- 新增 `A/data/sync/BackupFolderRegistry.kt`：`BACKUP_FOLDER_SPECS` 一份数据派生
  `BACKED_UP_FILE_FOLDERS` / `RECURSIVE_FILE_FOLDERS` / `EXCLUDED_FILE_FOLDERS` / `appFilesPackingPlan`，
  并新增 `BackupRestoreKind`（FLAT/FONTS/SKILLS）与 `backupFolderSpecFor(entryName)`。
  `backgrounds` 的恢复分支由 registry 自动产生；`avatar-frames` 决定**进备份**（平铺）；
  新增 `FileFolders.BACKUP_INDEX`（P1 本地索引用）并登记豁免理由。
- `BackupArchiveEngine.restoreUserFileEntry` 改为 registry 分派（不再手写目录 `when`）；
  `tool_outputs` 仅保留 legacy 兼容分支。
- `WorkspaceBackupHelper` 改用 `AppFilesPacking.addFileToZip`，工作区条目接入 STORED 采样策略。
- S3：新增 `S3Client.listAllObjects`（纯函数 `collectS3Pages` 翻页），`S3Sync.listBackupFiles` 走全量。
- WebDAV：新增 `WebDavPaths.kt`（拆段/逐段编码/URL 拼装/cumulativeSegments）；`WebDavClient` 支持多级
  `config.path` 与相对路径，新增 `mkdirs` 逐级 MKCOL，`ensureCollectionExists` 委托它（签名不变）。
- 护栏：`BackupCoverageTest` 改为**反射 `FileFolders` 全集**比对（新增目录不同步 registry 即失败），
  并新增「每个进备份目录都有恢复分派规格」「backgrounds/avatar-frames 回归」断言；
  `WorkspaceBackupHelperTest` 新增 STORED 回归；新增 `WebDavPathsTest` / `S3PaginationTest`。
- 验证：`:app:testDebugUnitTest` 全量 **2916 项通过，0 失败**；sync 包 16 个测试类全绿。
  真机 S3/WebDAV（多级目录、大文件 STORED、>1000 对象）留批次验收。
- 已知行为差异：`mkdirs` 每级一次 PROPFIND（P1 对象层应缓存）；`config.path` 为空白按未配置处理
  （与旧实现一致）；`mkdirs("")` + 空 `config.path` 为 no-op（旧实现会尝试对 base 发 MKCOL，边界差异）。

## 3. 设计约束（先定这些，再谈算法）

| # | 约束 | 理由 / 证据 |
|---|---|---|
| C1 | **快照自包含**：每份快照清单列出全部文件与 DB/settings 对象，恢复不依赖其他快照 | 避免增量链断裂；WebDAV 无 MOVE/条件写、无法可靠维护 `latest` 指针（`WebDavClient.kt` 方法清单） |
| C2 | **DB/settings 整体替换**（P1）；DB 逻辑增量单独立项（P2） | 保住 `VACUUM INTO` + 整库换入 + Room 迁移 + `sqlite_sequence`/记忆 id 语义（`D/completed/design-backup-workspace-files.md:272-285`）；DB 分块重组会引入「半库」风险 |
| C3 | **内容哈希校验，元数据只做跳过上传** | S3 分片 ETag 不是内容 MD5；WebDAV 无 ETag（§2.3） |
| C4 | **旧版共存**：新对象树只放 `v3/` 子目录；旧全量 zip 不动 | 旧版 App 只列 `backup_*.zip`，看到新目录也不会误读；降级不锁死 |
| C5 | **不绕过现有破坏性路径的安全设计**：manifest 校验、safety_backup、staged 换库、`DataOperationLock` | `A/data/sync/BackupArchiveEngine.kt:55-60,194-474`、`A/data/sync/PendingRestoreStore.kt` |
| C6 | **不偷偷接定时器**：增量仍是手动动作 | 自动备份是明确未立项项（`D/completed/design-backup-manifest-and-safety.md:97`） |
| C7 | **快照格式版本化且 fail-closed**：未知/更高版本拒绝，不猜 | 沿用 `formatVersion > 支持版本直接报错`（`A/data/sync/BackupManifest.kt:96-101`） |

## 4. 方案比选

| # | 方案 | 增量单位 | 恢复语义 | 代价/风险 | 结论 |
|---|---|---|---|---|---|
| A | 维持全量 zip | 无 | 现有 | 每次 2.8 GB；上游 #2280 即此痛点 | 基线 |
| B | **文件级清单增量 + 内容寻址对象**（本稿 P1） | 文件（原始内容 SHA-256） | 拼回 v2 zip → 现有引擎 | 需新格式/对象层/清单；DB 仍整库；首备全量 | **采纳（P1）** |
| C | **DB 对象级逻辑增量**（本稿 P2） | 会话/设置/记忆等对象 | 启动期合并进现库副本 → staged 换库 | 需保留 id 的 upsert + 删除语义 + FTS 重建；有版本兼容面 | **采纳（P2，条件成熟）** |
| D | DB 整文件分块去重（CDC，restic 式） | 变长块 | 拼回整库文件 → staged 换库 | 需 block index/GC/Range；`VACUUM INTO` 页布局稳定性与命中率**未实测**；FTS 53.8% 白搬；版本闸门卡链 | 不采纳（仅在实测证明收益后评估固定块） |
| E | 远端镜像 / rsync 式文件树 | 文件 | 目标=源镜像 | DB 单文件每次都变；每文件一次 PUT 在 WebDAV 放大；无服务端同步语义 | 不采纳（B 已覆盖其价值） |
| F | 增量链（parent + delta，restic/borg 式） | 链上 delta | 按链叠加 | 断链即废；WebDAV 无原子指针；删除/GC 复杂 | 不采纳（C1 自包含替代） |

**为什么选 B+C 分层**：
- 上传量支配项是文件区（2.8 GB）→ B 用文件级内容寻址即可拿到主要收益，且**不需要合并语义**；
- DB 是「对象元数据 0.7% + 消息文本」→ 文件级对 DB 无收益（整库对象每次都变），C 才能继续降；
- 两层都保持「**每份快照独立可恢复**」，避免链式风险；
- B 的恢复完全复用现有引擎（风险最低），C 的恢复落在启动期窗口（不碰 Room 写路径）。

## 5. 详细设计

### 5.1 总体结构

```
本地                                          远端（WebDAV / S3，root 下新增 v3/）
┌─────────────────────────────┐               ┌──────────────────────────────────────────────┐
│ 备份数据（现状不变）         │               │ v3/                                          │
│  settings（DataStore）       │               │   snapshots/<createdAt>_<device>.json.gz      │  ← 提交点（最后写）
│  rikka_hub.db（VACUUM 快照） │               │   packs/<sha256>.zip                          │  ← 小文件批量包（内容寻址）
│  files/{upload,images,...}   │  ──上传──▶    │   objects/<aa>/<sha256>                       │  ← 大文件/DB/settings 对象（内容寻址）
│  workspaces/<root>/files/**  │               └──────────────────────────────────────────────┘
│                              │
│ filesDir/backup_index/       │               * 旧全量 backup_yyyyMMdd_HHmmss.zip 仍在 root，不动
│  <destId>/state.json（缓存） │
└─────────────────────────────┘
```

**快照 = 一次备份的完整视图**：清单里每条文件都指向具体内容（独立对象或 pack 内条目），
加上 DB/settings 对象引用。对象按内容寻址 → 跨快照天然去重；清单本身每份都完整 → 快照间无依赖。

### 5.2 远端布局与提交协议

| 路径 | 内容 | 命名 | 写入时机 |
|---|---|---|---|
| `v3/snapshots/<createdAt>_<deviceShort>.json.gz` | 快照清单（压缩 JSON） | UTC 时间戳 + deviceId 短哈希，唯一 | **最后写**（提交点） |
| `v3/packs/<sha256>.zip` | 一批小文件（zip，条目名=备份内相对路径） | **内容哈希**（= 文件字节的 SHA-256） | 清单之前 |
| `v3/objects/<aa>/<sha256>` | 单文件/DB/settings 对象 | **原始内容 SHA-256**（`aa` = 前两位，分片目录） | 清单之前 |

- **提交语义**：先传所有对象/pack，再传清单；清单存在 = 快照有效。中断只留下未引用对象/pack，
  由 GC 清理；**不需要 MOVE、不需要条件写、不需要最新指针**（每份快照可独立列出/恢复）。
- **列举快照**：WebDAV `PROPFIND Depth:1 v3/snapshots/`；S3 `ListObjectsV2(prefix, 翻页)`；
  按文件名（时间戳）倒序，取最新用于增量 diff。多设备：清单含 `deviceId`，默认只对**本设备**快照做
  保留与 diff，其他设备快照在 UI 单列（不自动删）。
- **对象分片**：`objects/<aa>/` 256 个目录，避免单目录十万文件（WebDAV 需要逐级 MKCOL，
  是 P0 要修的 `buildUrl`/`mkdirs`；S3 前缀天然支持）。
- **不变量**：对象名 = 原始内容 SHA-256，写入后不可变；重复 PUT 幂等（内容相同），无需条件写。

### 5.3 快照清单格式（`SnapshotManifest` v1）

新文件 `A/data/sync/repo/BackupSnapshotManifest.kt`（与 zip 内的 `BackupManifest` v2 是两个格式，互不复用版本号）：

```kotlin
@Serializable
data class BackupSnapshotManifest(
    val formatVersion: Int = SNAPSHOT_FORMAT_VERSION, // = 1
    val createdAt: String,            // "yyyyMMdd_HHmmss"（UTC），与文件名一致
    val deviceId: String,             // 本机持久 UUID（首个快照时生成）
    val appVersionCode: Int,
    val dbVersion: Int,               // AppDatabase.SCHEMA_VERSION（备份时）
    val items: List<BackupItem>,      // 本快照包含的备份项
    val sections: Map<BackupManifestSection, BackupSectionStats>, // 预览/交叉校验
    val files: List<SnapshotFileEntry>,          // FILES + WORKSPACE_FILES 全量
    val database: SnapshotObjectRef?,            // P1：整库对象；P2 起可为空 + dbObjects
    val settings: SnapshotObjectRef,             // 整份 settings.json 对象
    val dbObjects: List<SnapshotDbObject> = emptyList(), // P2 预留
    val previous: String? = null,     // 仅 UI/诊断，不参与恢复
)

@Serializable
data class SnapshotFileEntry(
    val path: String,        // zip 内相对路径，如 "upload/<uuid>.png" / "workspace_files/<root>/a/b"
    val size: Long,          // 原始字节数
    val mtime: Long,         // 原始文件 lastModified（毫秒；用于本地缓存快速判定，不参与校验）
    val sha256: String,      // 原始内容 SHA-256（**校验基准**）
    val ref: SnapshotRef,    // 对象或 pack 内条目
)

@Serializable
sealed interface SnapshotRef {
    @Serializable @SerialName("object")
    data class Object(val key: String) : SnapshotRef          // objects/<aa>/<sha256>
    @Serializable @SerialName("pack")
    data class Pack(val pack: String, val entry: String) : SnapshotRef // packs/<sha256>.zip 内条目名
}

@Serializable
data class SnapshotObjectRef(
    val key: String,          // 原始内容 SHA-256
    val rawBytes: Long,       // 原始长度
    val codec: String,        // "stored" | "deflate"（对象 blob 自描述头之外的冗余声明，供 UI/空间估算）
)
```

- **清单体积**：条目 ≈ 150～250 B（JSON 字段名 + 64 hex）。10 万文件 ≈ 15～25 MB，gzip 后约 2～4 MB。
  这是 B 方案的主要规模风险：**P1 先接受**（每次备份上传 2～4 MB 可接受），
  在 `files.size > 20_000` 时打 warn 埋点；若实测突破，P2 改为「按路径哈希分页的清单树」
  （每页一个对象，父清单只存页哈希）——写入/恢复协议不变，只换清单组织。
- **校验规则**（纯函数，JVM 直测，风格同 `verifyBackupManifest`）：
  ① `formatVersion` 高于支持版本 → 拒绝（旧 App 不认新包）；
  ② `items` ⊆ 已知枚举；③ `path` 唯一、非空、必须以已知前缀开头、拒绝 `..`/绝对路径/反斜杠；
  ④ `sha256` 为 64 位小写 hex；⑤ `size`/`mtime` ≥ 0；⑥ pack 条目必须在 pack 索引内（偏移/长度不越界）；
  ⑦ `items` 含 DATABASE 时 `database != null`；⑧ 条目总数/总字节不超过上限（复用 2 GB/条、8 GB 总量思路，
  防止伪造清单把恢复撑爆）；⑨ `sections` 与派生统计一致（可选，用于提前发现清单截断）。
- **清单本身没有签名**：与今天 v2 同信任级（篡改者需同时改对象才能通过内容哈希）。
  加密/HMAC 是独立立项（`docs/upstream-analysis.md:1481`）。

### 5.4 对象与 pack 格式

**单对象（`objects/<aa>/<sha256>`）**：文件名 = 原始内容 SHA-256。blob 自描述：

```
byte 0: 0x00 = 原始字节 | 0x01 = DEFLATE（raw deflate，无 zlib 头）
byte 1..: 编码后的 payload
```

- 压缩决策：对可读内容采样复用 `BackupZipStrategy.shouldStoreUncompressed`（64 KB 头、Deflater 同级），
  不可压缩 → `0x00`；可压缩（DB/settings/文本）→ `0x01`。DB 实测 2.27x 可压，115 MB → 约 50 MB。
- 恢复时先解码、再算 SHA-256 与清单比对；不符即拒绝（单对象坏不会写半套数据）。
- **为什么内容哈希不放在 blob 头里**：对象名已经承载哈希；自描述头只解决「用哪种编码」，
  避免「同一内容在不同设备压缩决策不同 → 同名对象字节不同」的歧义（解码后必回同一原始内容）。

**pack（`packs/<sha256>.zip`）**：
- 一次备份里所有**小于阈值（建议 256 KB）的新增/变更文件**打进一批或几批 zip（每批原始总量 ≤ 32 MB，
  避免单对象过大；S3 分片与 WebDAV 单 PUT 都可承受 32 MB）。
- zip 条目名 = 文件在备份内的相对路径（与快照清单的 `path` 一致），条目内容 = 原始字节；
  条目压缩用现有采样 STORED 策略（P0 抽公共 `prepareZipEntry` 后复用）。
- pack 文件名 = **整个 zip 字节的 SHA-256**；清单 `SnapshotRef.Pack(pack, entry)`。
- 大文件（≥256 KB）走单对象（`0x00`/`0x01` 自描述），避免把 GB 级 git pack 塞进 zip 再读一遍。
- 恢复：每个被引用的 pack 下载到 cacheDir 一次，用 `ZipFile` 逐条抽；同一 pack 只下载一次。

**边界**：沿用现有恢复上限（单条目 2 GB、总量 8 GB，`A/data/sync/BackupManifest.kt:31-35`）——
超过 2 GB 的单文件在现有 zip 恢复里也会失败，增量恢复保持一致并在预检时报错。

### 5.5 对象层接口与两端适配

新文件 `A/data/sync/repo/BackupObjectStore.kt`：

```kotlin
/** 增量仓库的远端原语（只覆盖本方案需要的：列举/读/写/删）。 */
interface BackupObjectStore {
    suspend fun list(prefix: String): Result<List<RemoteObject>>        // S3 内部翻页；WebDAV Depth=1
    suspend fun get(key: String, target: File): Result<Unit>            // 下载到文件
    suspend fun put(key: String, file: File, contentType: String): Result<Unit>
    suspend fun delete(key: String): Result<Unit>
    suspend fun ensureDir(prefix: String): Result<Unit>                 // S3 no-op；WebDAV 逐级 MKCOL
}

data class RemoteObject(val key: String, val size: Long, val lastModified: Instant?)
```

实现：
- `S3BackupObjectStore`：包 `S3Client`；`list` 用 `continuationToken` 循环（修 P0）；
  `put` 复用 multipart（>8 MB）；`delete` 复用。
- `WebDavBackupObjectStore`：包 `WebDavClient`；`put` 需要多级路径（P0 修 `buildUrl` + `mkdirs`）；
  `list` = `PROPFIND Depth:1`；`get` 用现有流式下载。
- **不新增协议**：不要求 If-Match/Range/MOVE；快照提交靠「唯一文件名 + 最后写」。
- **能力探测**：首次启用时 `ensureDir("v3/objects/00")` + `put` 一个 1 B 探针对象再删，失败给出
  「该 WebDAV 服务端不支持多级目录」的明确错误（而不是备份跑到一半失败）。

### 5.6 本地索引（哈希缓存 + 仓库状态）

新目录 `filesDir/backup_index/`（`FileFolders.BACKUP_INDEX`，加入 `EXCLUDED_FILE_FOLDERS`，
理由「本地增量索引，可再生（重算哈希）」；同步更新 `BackupCoverageTest` 的声明集合）：

```
backup_index/
  <destId>/state.json      # destId = sha256(endpoint|bucket/dir)[:16]
```

`state.json` 内容（`@Serializable`，原子写复用 `AtomicFileWrite`）：
```kotlin
data class BackupRepoState(
    val repoFormatVersion: Int = 1,
    val destinationId: String,
    val deviceId: String,
    val lastSnapshot: String?,                 // 快照文件名
    val lastSnapshotCreatedAt: String?,
    val hashCache: Map<String, CachedHash>,    // path -> 缓存
)
data class CachedHash(val size: Long, val mtime: Long, val sha256: String)
```

- **命中条件**：`size` 与 `mtime` 都相同才复用 sha256；否则重算并覆盖。
- **失效/重建**：提供「重建备份索引」（全量重算）入口；恢复后文件 mtime=now（现有恢复不回写时间，
  `A/data/sync/BackupArchiveEngine.kt:737-757`），首次增量会把所有文件重算一遍——与今天打包读一遍同量级，
  可接受，但要在 UI 说明「恢复后首次备份会重新校验」。
- **可信度**：索引只用于**少读盘**，正确性不依赖它——每次仍会与远端最新快照清单比对哈希；
  索引丢失/损坏最多导致重算，不会漏传（漏传 = 恢复缺文件，必须由「远端清单为准」保证）。
- 索引**不进备份**（豁免项），也不进 Android auto-backup（路径不在 `backup_rules.xml` 白名单）。

### 5.7 备份流水线（P1）

新文件 `A/data/sync/repo/IncrementalBackupEngine.kt`（与 `BackupArchiveEngine` 平级，复用其 DB/settings 快照逻辑或抽公共方法）：

```
suspend fun plan(items, onProgress): BackupSnapshotPlan
suspend fun commit(plan, onProgress): BackupSnapshotRef
```

`plan`（只读、可单独用于「预估上传量」）：

1. `DataOperationLock.runWaiting(DataOperation.BACKUP)` 包住整个 plan+commit（与全量备份互斥）。
2. 生成 DB 快照（`VACUUM INTO`，复用 `addDatabaseToZip` 的快照部分）与 settings JSON；算 SHA-256。
3. 枚举文件（**与打包同一份 registry**，跳过瞬态文件：`*.tmp`、`.staging.*`、`*.part`）；
   对每个文件用 §5.6 缓存算 sha256；按阈值分「小文件（进 pack）/大文件（单对象）」。
4. 读最新快照清单（`list v3/snapshots` → 下载最新一个）：
   - `远端内容哈希 → ref` 映射（来自最新清单；同一内容在旧 pack 里也可复用 ref）；
   - 计算 `toUpload`：内容哈希不在映射里的 pack 条目/对象；DB/settings 哈希不同才传。
   - 删除语义：新清单不包含的路径自然消失（清单是新的完整视图），**无需 tombstone**。
5. 输出 `BackupSnapshotPlan`：候选清单 + 待传对象/pack + 复用字节/待传字节（UI 预估）。
6. `plan` 不写远端、不改本地状态；用户取消零副作用。

`commit`：

1. 生成 pack（cacheDir 临时 zip，算文件哈希即得 pack 名）、大对象（cacheDir 临时文件，自描述头）。
2. 逐个上传 pack/对象（串行，失败可重试该对象；不做整包断点）；进度按字节上报。
3. 组装并上传快照清单（**最后写**）→ 成功即提交。
4. 写本地 `state.json`（lastSnapshot + hashCache）。
5. 保留策略：列出本设备快照，超过 `keepLastN`（默认 5）删最旧清单；
   然后 GC：被删清单的引用集 − 保留清单引用集 = 可删对象/pack（**不列举远端全量对象**，
   所以不会误删别的设备/未知对象；孤儿对象留给「深度清理」，见 §5.8）。
6. `onProgress` 阶段：`SCANNING`（枚举+哈希）→ `UPLOADING`（对象/pack）→ `COMMITTING`（清单）→
   可复用 `BackupProgress`；`BackupProgressRow` 只显示字节/百分比，不依赖阶段文案（`:13-18` 注释），
   新增枚举值零 UI 改动。

**失败语义**：
- 清单上传失败 = 本次快照不存在，已传对象成为孤儿；下次计划只与「最新快照」diff → 孤儿会重新上传（浪费一次），
  深度清理可回收；不引入半份快照。
- 进程被杀：同上；不做断点续传（P1 明确不做，长备份建议后续接前台服务/Worker，见 §8）。
- 对象内容写坏：内容寻址 + 恢复时校验兜底；上传后可选回读校验（P1 不做，S3 SigV4 已保证入站完整性）。

**预估/确认交互**：备份按钮 → `plan`（扫描可在秒级到分钟级）→ 弹「本次预计上传 X（其中数据库 Y），
复用 Z」→ 用户确认 → `commit`。首次备份预估 = 全量，文案明确「首次会全量上传，之后只传变更」。

### 5.8 恢复流水线（P1：拼回 v2 zip，复用现有引擎）

新文件 `A/data/sync/repo/SnapshotRestoreAssembler.kt`：

1. 选择快照（列表来自 `v3/snapshots/`，显示时间/大小/sections/设备）；下载清单并校验（§5.3）。
2. 版本闸门：`formatVersion > 支持` → 拒绝；`dbVersion > AppDatabase.SCHEMA_VERSION` → 拒绝
   （与 staged 库 `user_version ∈ 1..SCHEMA_VERSION` 同口径，`A/RikkaHubApp.kt:260-268`）。
3. 逐条下载所需 pack/对象到 cacheDir（同一 pack 只下一次），每个对象/pack 校验 SHA-256；
   条目校验原始内容哈希。
4. 在 cacheDir **组装一份 v2 zip**：
   - `settings.json`（settings 对象解码后写入，计算 SHA-256/字节数）；
   - `rikka_hub.db`（DB 对象解码后写入；P1 无 `-wal/-shm`，因 VACUUM 快照自包含）；
   - 文件条目按路径写入（`upload/…`、`workspace_files/<root>/…` 等，与现有恢复分派完全一致）；
   - 生成 v2 `manifest.json`（sections/settingsSha256/settingsBytes/databaseSha256/databaseBytes），
     **让现有 `verifyBackupManifest` 照常工作**。
5. 调 `BackupArchiveEngine.restoreFromBackupFile(zip, items = snapshot.items)`：
   现有逻辑负责校验、safety_backup、staged DB、settings 写入、文件落盘、强制重启。
6. 清理 cacheDir 临时文件。

**为什么 P1 选择「拼 zip 再走引擎」**：
- 破坏性路径、manifest v2 校验、safety_backup、staged 换库、legacy 转换全部**零改动**，风险最低；
- 空间开销与今天「下载全量 zip 再恢复」同量级（zip + 解压产物），现有 `BackupSpaceCheck` 可复用
  （`A/data/sync/BackupArchiveEngine.kt:222-243`）；
- 代价：多一次本地写 zip（2.8 GB 级约数分钟）与两次 zip 读；P2 的原生恢复可省掉。

**组装细节（实现约束）**：
- zip 条目用 `ZipOutputStream` 写入（DEFLATE 或按采样 STORED；STORED 需预置 CRC，实现上二选一，
  推荐 P1 统一 DEFLATE 求稳，后续再用采样优化）；
- 每个条目写入前完成对象哈希校验（失败立即中止，不产生「部分 zip」）；
- 条目名来自清单 → 必须用与现有恢复同一套 `classifyBackupEntry` + 路径安全规则做二次校验；
- 单文件 > 2 GB 直接拒绝（现有恢复上限）。

**P2 原生恢复（后续，不在 P1）**：逐对象校验后直接落盘（FILES/WORKSPACE），DB 走逻辑合并（§5.9），
可用本地索引跳过「已与目标相同的文件」；省掉 zip 中转与一次全量读。

### 5.9 DB 逻辑增量（P2：把 DB 从「每次整库」变成「按对象」）

> 目标：备份上传量里仍占大头的 DB 快照（P1 约 50 MB/次压缩后）进一步降到「改动的会话」。
> **P2 不是 P1 的前置**；P1 上线并测量后，按数据决定是否做。

**数据事实（实测，§2.1）**：DB 98.9% = `message_node` + 可重建 FTS；对象元数据 0.7%。
FTS 不备份，恢复后重建（`A/data/repository/ConversationRepository.kt:596-607`、
`A/data/db/fts/MessageFtsManager.kt:38-67`）。

**对象划分**（`SnapshotDbObject(objectKey, sha256, ...)`）：

| 对象 | 内容 | 哈希基准 | 优先级 |
|---|---|---|---|
| `conversation:<id>` | `ConversationEntity` 行 + 该会话全部 `message_node` 行 + `compression_checkpoint` 行 | 行规范化字段 + `getNodeSignaturesOfConversation(id)`（现成查询，`A/data/db/dao/MessageNodeDAO.kt:50-54`）+ checkpoint 行 | P2 首做（覆盖 98% 字节） |
| `settings` | 整份 Settings JSON | 字节 SHA-256 | P1 已有（对象） |
| `memory_store:<assistantId>` | 该助手 `MemoryEntity`（稳定键 `public_id`）+ `memory_trash` + `memory_history` | 行序列 | P2 |
| `favorites` / `workspaces` / `conversation_folder` | 小表整表 | 行序列 | P2（<0.1 MB，收益小） |
| append-only 流水（`usage_record`/`translation_history`/`scheduled_task_runs`） | 自增 id/时间游标之后的行 | 游标 + 行数 | P2（注意 trim 会删尾，游标必须容忍） |
| `media_creation_*` / `scheduled_tasks` / `knowledge_*` / `agent_run` | 按对象整份 | 行序列/`update_at` | P2 视体积 |
| **排除** | `message_fts*`（派生）、`room_master_table`/`android_metadata`/`sqlite_sequence`（库簿记）、`memory_consolidation`/`memory_review_cursor`（进度可重建） | — | — |

**变更检测**：备份时对每个对象算哈希（会话用 node signatures；总量 ~50 MB 文本，SHA-256 秒级），
与最新快照清单比对；**不引入触发器、不依赖 `updatedAt`**——
`ConversationEntity.update_at` 有 10+ 个单列 UPDATE 不刷新（`A/data/db/dao/ConversationDAO.kt:176/183/194/202/217/243/254/258/263/345/352`），
`message_node` 根本没有时间戳列（`A/data/db/entity/MessageNodeEntity.kt:21-32`）。

**删除语义**：快照清单是新完整视图；旧清单有、新清单无的对象即删除，**不需要 tombstone**；
恢复侧按 id 删除（现有外键 CASCADE 覆盖子表）。注意：会话是硬删 + CASCADE
（`A/data/repository/ConversationRepository.kt:567-585`）。

**存储**：每个对象作为内容寻址对象（JSON/行序列，DEFLATE），跨快照去重；
快照清单新增 `dbObjects` 映射（对象键 → sha256），不再有 `database: SnapshotObjectRef`。

**恢复（启动期，`startKoin` 之前）**：
1. 校验所有 db 对象下载完整、哈希正确；
2. 以**当前库副本**为基底（当前 schema，已含新表；避免复制期 Room 打开）；
3. 对快照覆盖的表：清空后按「列交集」插入对象行（复用
   `A/data/sync/LegacyBackupConverter.kt:157-209` 的 copyTable 手法；新列用默认值，旧列忽略）；
   注意插入顺序（会话行 → 节点 → 检查点；外键）；
4. 写入与现有 staged restore 同款 `PendingRestoreStore`（现库 rename 让位、失败回滚）；
5. Room 打开后重建 FTS（全库或只重建受影响会话），`confirmApplied`。

**必须保留的语义**：恢复后 `user_version` 保持当前（基底是现库副本，不跨 Room 迁移）；
快照 `dbVersion > 当前` → 拒绝；`< 当前` → 按列交集插入（新表/新列保留本机数据）。

**版本风险**：跨版本「对象来自旧 schema」比「整库文件来自旧 schema」更难验证（列交集只是向前兼容，
不处理语义迁移）。P2 实施前必须做三组实验：
① 用 v38/v58 真机库互导对象（含新表缺列）并跑 `PRAGMA integrity_check` + 关键 UI 冒烟；
② FTS 全库重建耗时（49.6 MB 文本 / 7,606 条，未知，可能分钟级；需可「只重建受影响会话」）；
③ 会话对象 upsert 与活跃会话内存态的冲突（启动期应用天然规避，但需确认时序）。

**P2 的简化退路（P2-lite，若逻辑合并风险过高）**：DB 仍整文件，但快照时**剔除 FTS 表**
（53.8% 字节），恢复后重建 FTS。收益：DB 上传从 ~50 MB 降到 ~22 MB；代价：恢复后多一次 FTS 重建，
且需要「DB 文件来自快照但 FTS 为空」的启动处理。先测量再选。

**明确不做**：
- `sqlite3session`（本仓 SQLite 是 requery fork + 自定义扩展，Java 层无 session API；需自编
  `SQLITE_ENABLE_SESSION` + JNI + FTS shadow table 兼容，成本最高，`A/di/DataSourceModule.kt:89-136`）；
- 按 `updatedAt` 拉行（漏 bump 太多，见上）；
- DB 整文件 CDC（页布局稳定性/命中率未实测，且版本闸门卡链）。

### 5.10 兼容、迁移与回滚

| 场景 | 行为 |
|---|---|
| 旧版 App 看到新结构 | 只列 root 下 `backup_*.zip`，忽略 `v3/`；**不破坏、不误删**（保留策略只在增量模式内执行） |
| 新版 App 恢复旧 zip | 现有路径不变（`S3Sync/WebDavSync.restore*` → `BackupArchiveEngine`） |
| 新版 App 恢复新快照 | 拼 v2 zip → 现有引擎；旧版无法读新快照，文案明确 |
| 新快照 → 旧版 App | 不支持；用户应保留至少一份旧全量 zip（UI 可提示「导出完整备份」） |
| 设置持久化 | `Settings` 新增 `backupConfig`（`enabled`, `keepLastN`, `hashCache` 开关）字段；
旧 JSON 缺省取默认；未知新字段由 `ignoreUnknownKeys` 丢弃（降级不炸） |
| 回滚 | 关闭增量开关即回到全量；`v3/` 数据可保留或删除；DB schema **无变更**，无需迁移 |
| 保留策略删除 | 默认只删**本设备**的旧快照；跨设备快照需手动删；GC 只删「已知未被保留快照引用」的对象 |

### 5.11 UI / UX

入口：备份页（`BackupPage`/`S3Tab`/`WebDavTab`/`ImportExportTab`）新增：
1. **模式开关**：设置 → 备份 → 「增量备份（实验）」。开启后 WebDAV/S3 的备份按钮走增量；
   本地导出/导入仍全量（文案说明）。
2. **首次说明卡**：「首次将上传全量数据，之后只上传新增/变更；恢复方式不变」。
3. **备份前预估**：`plan` 后弹「预计上传 X（数据库 Y / 文件 Z），复用 W」+ 确认；
   取消零副作用。
4. **快照列表**：时间 / 逻辑总大小 / 本次上传 / 设备 / 项目（sections）；操作：恢复、删除、
   导出为完整 zip（可选，复用拼包）。旧全量 zip 列表保留（分 tab 或分组）。
5. **保留设置**：`keepLastN`（默认 5；可选 3/5/10/不限）+「清理未引用对象」（显示可释放大小，
   二次确认）。孤儿对象（中断残留）只在「深度清理」（列举远端，慢）里清。
6. **进度**：复用 `BackupProgressRow`（只显示字节/百分比，阶段枚举新增值零 UI 改动）。
7. **错误文案**：WebDAV 不支持多级目录 / 清单版本过新 / DB 版本过新 / 单文件 >2 GB /
   空间不足（需多少/有多少）——沿用现有错误风格。
8. 字符串：若实施，按 `locale-tui-localization` 补 en/zh/zh-TW。

### 5.12 代码落点与改动清单

**新增**（`A/data/sync/repo/`）：

| 文件 | 职责 |
|---|---|
| `BackupSnapshotManifest.kt` | 快照清单模型 + `verifySnapshotManifest` 纯函数 + 序列化 |
| `BackupObjectStore.kt` | 远端对象层接口 + `RemoteObject` |
| `S3BackupObjectStore.kt` / `WebDavBackupObjectStore.kt` | 两端适配（复用现有 Client） |
| `BackupPackingRegistry.kt` | 目录单一真源（打包形态/恢复分派/豁免理由/瞬态文件过滤） |
| `BackupInventoryStore.kt` | `state.json`（destId、deviceId、哈希缓存）读写 |
| `IncrementalBackupPlanner.kt` | 枚举/哈希/diff/plan（IO 可注入，便于 JVM 测试） |
| `IncrementalBackupEngine.kt` | plan+commit+保留+GC 编排，`DataOperationLock` |
| `SnapshotRestoreAssembler.kt` | 下载/校验/拼 v2 zip/调现有引擎 |

**修改**：

| 文件 | 改动 |
|---|---|
| `A/data/sync/BackupItem.kt` | 由 registry 派生；补 `BACKUP_INDEX` 豁免；`backgrounds` 恢复、`avatar-frames` 决策 |
| `A/data/sync/BackupArchiveEngine.kt` | `backgrounds/` 恢复分支；抽「DB 快照」「settings JSON」公共方法供 planner 复用；事件/阶段 |
| `A/data/sync/WorkspaceBackupHelper.kt` | 接 `BackupZipStrategy`（全量 zip 路径受益） |
| `A/data/sync/S3Sync.kt` / `webdav/WebDavSync.kt` | 新增快照备份/列表/恢复/删除；列表翻页；`path` 多级 |
| `A/data/sync/s3/S3Client.kt` | `listObjectsAll`（内部翻页）或 Sync 内循环 |
| `A/data/sync/webdav/WebDavClient.kt` | `buildUrl` 按 `/` 拆段；`mkdirs`；可选返回 ETag |
| `A/data/sync/BackupProgress.kt` | 新增 `SCANNING`/`COMMITTING` 阶段（纯枚举） |
| `A/data/datastore/PreferencesStore.kt` | `Settings.backupConfig` + key 读写 |
| `A/ui/pages/backup/BackupVM.kt` + tabs/components | 快照列表、预估、进度、保留/清理交互 |
| `app/src/main/res/values*/strings.xml` | 新增文案（en/zh/zh-TW） |
| `T/data/sync/BackupCoverageTest.kt` | 由 registry 派生断言；新增恢复分派覆盖测试 |

**不改**：Room schema/DAO/实体（P1/P2 都不加表、不加触发器、不加迁移）；zip 内 `BackupManifest` v2 格式；
`PendingRestoreStore` 状态机；`restoreFromBackupFile` 的破坏性路径。

### 5.13 测试与护栏

- 纯 JVM：清单序列化/校验（版本、路径、哈希、pack 越界、上限）；planner diff（新增/修改/删除/复用）；
  pack 切分与抽取；对象 blob 编解码（stored/deflate，CRC 与哈希）；哈希缓存命中/失效；
  registry 覆盖 `FileFolders` 全集 + 恢复分派一一对应（补 `backgrounds`/`avatar-frames` 回归）；
  拼装 zip 的条目前缀/分类/计数与 v2 manifest 校验往返。
- 用内存假 `BackupObjectStore` 做端到端（JVM）：plan→commit→list→assemble→`inspectBackup` 校验；
  篡改对象 → 恢复拒绝；中断（不上传清单）→ 不产生快照。
- 真机矩阵（S3: MinIO/R2/阿里 OSS 兼容端；WebDAV: Nextcloud/坚果云/自建）：
  首备全量 → 改 1 会话 + 加 1 文件 → 备 → 断言上传字节 ≈ 变更（planner 日志/远端清单）；
  恢复旧快照与最新快照；杀进程中断；删除快照 + GC；空间不足；旧全量 zip 恢复不受影响。
- 性能断言（目标值，先测量后定）：增量备份上传 ≤ 全量的 10%（工作区型数据）；
  恢复时间 ≤ 现有全量恢复 × 1.2（拼 zip 的额外开销）；哈希缓存命中时扫描 ≤ 数秒。

## 6. 验证计划（分阶段验收）

| 阶段 | 验收 |
|---|---|
| P0 | ✅ 2026-11-03 单测全绿（全量 2916 项）；真机待验收：备份→恢复后 `backgrounds` 存在；`avatar-frames` 有明确归属；工作区大文件备份 CPU 明显下降；S3 对象 >1000 时旧备份可见；WebDAV 服务端可建多级目录 |
| P1 | JVM 端到端 + 假 store；真机 S3/WebDAV 各一次完整「首备→增量→恢复」；恢复后会话/附件/工作区/背景齐全；旧 zip 双恢复；中断后无半快照；上传量对比日志 |
| P1.5 | 保留策略/GC 正确（保留快照可恢复，被删快照消失，引用对象不误删）；预估与实际偏差 < 20% |
| P2 | 三组实验（§5.9）通过；v38/v58 库对象互导 + integrity_check + FTS 重建 + 关键 UI 冒烟；对象增量的上传量 ≤ P1 的 10%（聊天型数据） |

## 7. 分阶段实施与工作量（估算）

| 阶段 | 内容 | 估算 | 可否独立上线 |
|---|---|---|---|
| **P0** | registry 单一真源 + `backgrounds`/`avatar-frames` + 工作区 STORED + S3 翻页 + WebDAV 多级路径/`mkdirs` | 1～2 天 | ✅ **已落地（2026-11-03）**：纯修 bug/基建，无格式变更 |
| **P1** | 快照清单 + 对象层 + 本地索引 + planner/engine + 拼 zip 恢复 + S3/WebDAV 接线 + UI（开关/预估/快照列表）+ 单测 | 5～7 天 | ✅（实验开关，默认关；旧路径不变） |
| **P1.5** | 保留/GC（手动+自动）、深度清理、pack 参数调优、清单体积埋点、前台服务/长任务健壮性（可选） | 2～3 天 | ✅ |
| **P2** | DB 对象模型 + 启动期合并 + FTS 重建 + 三组实验 + 测试/真机 | 6～10 天（含 1～2 天 spike） | ✅（独立开关；失败可退回 P1 整库对象） |
| **P3** | CDC 固定块实验（仅 P2 后仍嫌 DB 大时）、加密、自动定时备份 | 不排期 | 独立立项 |

> 合计到 P1 约 **1.5 周**；到 P2 约 **3 周**。每阶段都保持「关掉开关即回全量」的回滚能力。

## 8. 风险与开放问题

### 8.1 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 清单体积随文件数膨胀（10 万文件 ≈ 2～4 MB gzip） | 每次备份固定上传/解析成本 | P1 埋点 + 阈值；超过则清单树化（P2，协议不变） |
| R2 | WebDAV 小文件 PUT 放大（首备 1 万文件） | 首备慢/失败 | pack 批量（≤32 MB/批）+ 串行重试；限并发 2～4；错误可重试单批 |
| R3 | 组装 zip 的临时空间 + 时间 | 恢复慢/空间不足 | `BackupSpaceCheck` 预检；P2 原生恢复省掉 |
| R4 | P1 仍每次上传 ~50 MB DB（聊天型用户收益变小） | 增量收益打折 | P2（对象级）；P1 先做 DB 哈希门控（未变不上传，但通常每次都会变） |
| R5 | 中断留下孤儿对象/pack | 远端体积涨 | 深度清理（列举删除未引用）；常规 GC 不依赖列举 |
| R6 | 多设备共用同一目录 | 快照互相覆盖/保留误删 | `deviceId` 隔离保留；UI 警告「多设备同目录不合并」；P1 不做合并 |
| R7 | 恢复后 mtime 重置导致哈希缓存失效 | 首次增量重算全量 | 可接受（与今天读一遍同量级）；UI 说明；「重建索引」入口 |
| R8 | 清单/对象被篡改 | 数据损坏 | 每对象 SHA-256 + 恢复前统一校验；与现有 v2 同信任级；加密独立立项 |
| R9 | 单文件 >2 GB | 恢复拒绝 | 与现有上限一致；预检明确报错 |
| R10 | 进程被杀（viewModelScope 无 WorkManager） | 长备份中断 | 明确「失败可重试」；P1.5 可选前台服务；不承诺断点续传 |
| R11 | WebDAV 服务端差异（MKCOL/多级路径/大小上限） | 首备失败 | 能力探测（1 B 探针 + 明确错误）；文档列出已测服务端 |
| R12 | P2 跨 schema 对象合并语义 | 静默数据丢失 | 三组实验 + 列交集 + integrity_check + 失败退回 P1；快照 `dbVersion` 闸门 |
| R13 | 快照清单含敏感信息（路径/大小） | 隐私 | 与现有备份同级（远端已有全量 zip）；加密独立立项 |

### 8.2 开放问题（需要用户拍板）

1. **范围**：增量只做 WebDAV/S3（本地导出保持全量 zip）是否可以？（§1.3 假设）
2. **P1 是否接受「DB 仍每次整库（~50 MB 压缩）」**，还是一开始就投入 P2 的 DB 对象增量？
3. **保留策略默认值**：保留最近 5 份 + 自动 GC 可以吗？还是只手动清理？
4. **多设备**：是否要求两台设备备份到同一 WebDAV/S3 目录并互不干扰（本稿只保证不损坏，不做合并）？
5. **加密**是否要并入本批（否则快照里的 DB/settings 与现有 zip 一样明文）？
6. **是否需要「把快照导出成完整 zip」**（便于交给旧版 App / 归档）？
7. **P1 是否现在开始**（P0 已于 2026-11-03 落地）？若开始，需先确认上面 1–6。

## 9. 明确不做（与既有决策对齐）

- 不做全量逐文件 SHA-256 进**全量 zip**（本稿只在增量仓库做逐文件哈希；
  旧 zip 路径的 manifest v2 不变，`D/completed/design-backup-manifest-and-safety.md:35-36,94`）。
- 不做 S3/WebDAV 恢复前预览 UI（本稿的快照列表提供摘要，但不解析全量）；
  清单体积许可时可在 P1.5 加「恢复前预览快照内容」。
- 不做自动定时备份（独立立项，`D/completed/design-backup-manifest-and-safety.md:97`）。
- 不做 `tool_outputs`/rootfs/exports 进备份（`A/data/sync/BackupItem.kt:96-108`）。
- 不做跨会话/全局附件去重（只在单次导出内，`D/completed/design-conversation-import-export.md:271-272`）；
  本稿的**内容寻址去重是「同一内容不重复上传」**，不改变恢复时的文件归属与删除语义。
- 不做 rsync/双向同步、不做跨设备合并。
- 不做备份加密（独立立项；若做，加在快照清单/对象层之上，不改本稿协议）。

## 附录 A：关键证据索引 / 调研底稿

- 现存实现：`A/data/sync/BackupArchiveEngine.kt`、`BackupManifest.kt`、`BackupItem.kt`、
  `AppFilesPacking.kt`、`WorkspaceBackupHelper.kt`、`BackupZipStrategy.kt`、`BackupProgress.kt`；
  `A/data/sync/S3Sync.kt`、`s3/S3Client.kt`；`A/data/sync/webdav/WebDavSync.kt`、`WebDavClient.kt`。
- 数据实测：`_measure/m.db`（115,393,536 B / v38）、`_measure/settings.pb`（1,451,512 B）；
  本稿 §2.1 的 dbstat/列长数据由只读查询得到（脚本在 `.tmp/incremental-backup/data-delta.md` 描述的
  `dbstat` + `length(cast(col as blob))` 方法；`.tmp/` 不入库）。
- 相关设计稿：`D/completed/design-backup-workspace-files.md`、`design-backup-manifest-and-safety.md`、
  `design-backup-staged-restore.md`、`design-conversation-import-export.md`、`design-export-retention.md`。
- 上游：rikkahub#2280 <https://github.com/rikkahub/rikkahub/issues/2280>；
  上游备份文档（文案写「增量」、实现为全量 zip）<https://docs.rikka-ai.com/zh/settings/backup>。
- 同类实现基准：restic/borg/rsync/AWS CLI `s3 sync`/Chatbox/Cherry Studio 的对比见
  `.tmp/incremental-backup/benchmark.md`（不入库）；本稿 §4 已提炼结论。
- 本仓安全属性：`A/data/sync/PendingRestoreStore.kt`、`A/RikkaHubApp.kt:167-184,260-268`、
  `A/data/sync/LegacyBackupConverter.kt:157-209`。
