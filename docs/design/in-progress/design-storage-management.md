# 缓存管理（Cache Management）设计稿

> 状态：**Phase 1 + Phase 2 已实现并通过真机验证**（2026-10-25，PJX110）；
> **Phase 3 部分落地**（共享路径常量 + `temp` 启动清理委托）。
> 验证：单测 13 项通过（1 项在 Windows 上因无符号链接权限跳过）、`:app:assembleDebug` 成功、
> 真机注入文件后的统计/清理/展开/结果提示/入口/搜索均通过（详见 §十一）。
> 本文同时是方案与实现记录；本地暂存，未提交。
> 参照实现：Legado_Max 的「精准管理 → 存储管理」（外部仓库，仅作对照，不移植）。
> 关联：`design-export-retention.md`（`files/exports` 留存区与启动清理）、
> `design-settings-search.md`（设置搜索索引是手工维护的静态数据）、
> `design-workspace-background-tasks.md`（工作区目录与临时目录）。

## 一、背景与结论

本项目目前没有独立的「缓存管理」页，只有：

- 设置 → 连接与数据 → **聊天存储**（`SettingFilesPage`，只管 `upload` / `exports` 两个目录）；
- 设置 → 关于 里的版本信息，以及日志页里的日志文件大小/清空；
- `RikkaHubApp` 启动时的 5 个清理任务（`cache/temp`、过期导出、`tool_outputs`、工作区 tmp、`managed_files` 同步）。

用户能看到「占了多少」的地方只有聊天存储那一行的「N 个文件, X MB」，其余缓存目录不可见、不可清。

**结论：不移植 Legado 的 `StorageCalculator`。** 两边的存储形态差异很大，Legado 的七类口径在本项目有
一半不成立（详见 §二、§附 A）。本项目需要的是**一层很薄的聚合统计 + 白名单清理**，复用现有的
`FilesManager`、`Logging`、启动清理与文件管理页。

| 维度 | Legado_Max | 本项目 |
|---|---|---|
| 存储位置 | 外部私有 `externalFiles/externalCache` | 全部内部私有 `filesDir/cacheDir`（全仓无 `externalFilesDir`/`externalCacheDir`） |
| 权限 | 需注意外部存储可用性 | 无需任何存储权限（`WRITE_EXTERNAL_STORAGE` 仅 `maxSdk=28`，用于旧版媒体导出） |
| 数据库 | `legado.db`，清理 = 删缓存行 | `rikka_hub`，**强制 WAL**，是用户数据，不可清 |
| 已有清理 | 无统一入口，新增整页 | 启动已有 5 项清理，文件页已有删除/批量操作 |
| 已有「文件管理」 | 无 | 有（`SettingFilesPage`），且已发生过整目录误删事故 |

## 二、Legado 实现要点与教训（只作对照）

其实现为三层：Compose 页面（四态）→ ViewModel（`StateFlow` + 7 类枚举 + 10 路 `async` 并行）→
`StorageCalculator` 单例（`File` 遍历 + Room 查询）。有价值的优化：30s 结果缓存、书籍目录名到 `Book`
的 O(1) 映射、手写栈替代递归遍历、30 秒内避免重复遍历。

但代码演进留下了多处「口径与实现失配」，这些是我们**必须规避**的：

| 问题 | 成因 | 本项目的规避方式 |
|---|---|---|
| TTS 明细/按引擎清理恒为空 | 音频平铺为 `<md5>.mp3`，统计却按「引擎子目录」分组 | 分类与清理都基于**同一份白名单定义**，不假设目录结构 |
| ACache 明细/按前缀清理恒为空 | ACache 文件名是 `key.hashCode()`，前缀匹配永远失败 | 不提供「无法验证正确性」的明细展开 |
| 日志分类恒为 0 | 分类指向的目录没有任何写入方 | 只统计**有明确写入方**的路径；日志复用 `Logging` |
| 「临时缓存」清空整个外部缓存目录 | 分类 = 整目录 | 白名单逐项清理，不整目录清空 |
| DB 清理后大小不变、总量永不归零 | 只删行不 checkpoint/VACUUM | DB 只读展示，不提供清理 |
| 孤儿书籍缓存无法单本清理 | 明细 id 是 bookUrl，书已删除则查库失败直接 return | 清理以**路径**为准，不以数据库记录为准 |
| 30s TTL 是全局时间戳 | 任一分类计算会给所有分类「续期」 | 不做全局 TTL；MVP 进页面即扫，若做缓存则按分类独立 |
| 删除失败显示成功、大小显示 0 | 删除返回值被忽略、异常被 `printStackTrace` 吞掉 | 清理返回逐项结果，失败必须在 UI 可见 |

## 三、本项目现状核实（落点清单）

### 3.1 缓存 / 临时（可再生，候选清理项）

| 目录 | 写入方 | 生命周期 | 备注 |
|---|---|---|---|
| `cacheDir/image_cache` | Coil 3（`RouteActivity.kt:212` 未配置 `diskCache`，用默认目录，上限约 2% 可用空间） | 系统/Coil 自行淘汰 | 最安全的一类 |
| `cacheDir/temp` | `Context.appTempFolder`（`common/.../ContextUtil.kt:6`）：裁剪、图片生成、系统 TTS、导出中间件 | 每次冷启动整目录删除（`RikkaHubApp.kt:153,236`） | 页面内清理等价于启动清理 |
| `cacheDir/webview_content` | `WebViewContentCache`（`WebViewContentCache.kt:8-15`） | 7 天 TTL | 与消息渲染相关，删后重建 |
| `cacheDir/workspace_svg_thumb` | `WorkspaceFileBrowser.kt:311` | 无 | 缩略图 |
| `cacheDir/texfmt` | `TectonicNative.kt:82` | 无 | TeX 渲染格式文件，删后首次渲染变慢 |
| `cacheDir/backup_*.zip`、`restore_*`、`backup_db_snapshot.db` | `BackupArchiveEngine.kt:57,232,440` | 备份/恢复结束不保证删除 | 清理需与备份/恢复互斥 |
| `cacheDir` 下 WebDAV/S3 下载临时件 | `WebDavSync` / `S3Sync` | 无 | 同上 |
| `filesDir/logs` | `common/.../Logging.kt:93-105` | 4 MB 轮转 | 直接调 `Logging.clearLogFile()` |
| `filesDir/tikz_cache` | `TikzSvgCache.kt:23` | 有版本标记，版本变更时整体清空 | 可清 |
| `filesDir/token_counts.v1.bin` | `TokenCountDiskCache`（`RikkaHubApp.kt:112` 挂载） | 无 | 小文件，建议**不展示**，避免噪音 |
| Android `app_webview` | 系统 WebView | 系统管理 | 不处理（无稳定 API） |

### 3.2 用户数据（只读或排除）

| 目录 | 归属 | 处理 |
|---|---|---|
| `filesDir/upload`、`filesDir/exports` | 聊天附件、导出留存 | 复用 `SettingFilesPage`，新页只显示大小并可跳转 |
| `filesDir/images`、`avatars`、`media_creation`、`skills`、`builtin_skills`、`fonts` | 头像、生成产物、技能、字体 | **排除**，不进缓存页 |
| `filesDir/tool_outputs` | 会话级临时产物，启动即清 | 可归入「临时」但需文案说明 |
| `filesDir/workspaces/<root>/`（含 `files/`、`linux/`、`tmp/`） | 工作区（Linux rootfs 可能 GB 级） | **默认不遍历**；如展示只做只读单行 |
| `databases/rikka_hub` + `-wal` / `-shm` | 用户数据库，WAL 强制开启（`DataSourceModule.kt:97-98`） | **只读展示**，不提供清理 |
| `filesDir/datastore/settings.preferences_pb` | 用户设置 | 不展示 |

### 3.3 已有清理入口（避免重复造入口）

- `RikkaHubApp.kt:153-168`：`deleteTempFiles` / `purgeExpiredExports` / `cleanupToolOutputs` /
  `cleanupWorkspaceTempDirs` / `syncManagedFiles`。
- `SettingFilesPage.kt:110-121,195-208`：单文件与批量删除、清空当前文件夹（走 `deleteMany`）。
- `FilesManager.deleteAll`（`FilesManager.kt:670`）：目前**无 UI 调用方**；本设计不复用它的「整目录」语义。
- `Logging.clearLogFile`（`Logging.kt:99`）与日志页的「清空文件」。

## 四、目标与非目标

### 目标

1. 一个页面看到**主要空间占用**（按分类 + 总量），只读统计即可用；
2. 能安全清理**可再生**的缓存，清理结果逐项可见（成功/失败/释放字节）；
3. 与现有「聊天存储」页职责不重叠：新页只聚合与清缓存，文件管理仍在原页；
4. 不新增依赖、不新增权限、不引入后台任务。

### 非目标

1. 不清理任何用户数据（附件、头像、产物、工作区文件、数据库）；
2. 不做数据库压缩/VACUUM、不做 WAL checkpoint；
3. 不遍历 `workspaces`、不统计 `linux` rootfs 内部；
4. 不清理其他应用或公共目录；
5. 不做「自动定期清理」（启动清理已存在，保持现状）。

## 五、分类口径与红线

### 5.1 分类定义（`StorageCategoryId`）

| id | 展示名 | 路径 | 可清理 | 清理语义 |
|---|---|---|---|---|
| `IMAGE_CACHE` | 图片缓存 | `cacheDir/image_cache` | 是 | 删除目录内容（保留目录） |
| `TEMP` | 临时文件 | `cacheDir/temp` | 是 | 删除目录内容 |
| `WEBVIEW` | 网页内容缓存 | `cacheDir/webview_content` | 是 | 删除目录内容 |
| `THUMBS` | 缩略图 | `cacheDir/workspace_svg_thumb` | 是 | 删除目录内容 |
| `TEX` | 公式渲染缓存 | `cacheDir/texfmt` | 是 | 删除目录内容 |
| `LOGS` | 日志 | `filesDir/logs` | 是 | `Logging.clearLogFile()` |
| `BACKUP_TEMP` | 备份临时文件 | `cacheDir` 白名单：`backup_*.zip`、`restore_*`、`backup_db_snapshot.db` | 是 | 按白名单删除；备份/恢复进行中禁用 |
| `USER_FILES` | 聊天文件 | `filesDir/upload`、`filesDir/exports` | 否（只读） | 跳转 `Screen.SettingFiles` |
| `DB` | 数据库 | `databases/rikka_hub`(+`-wal`/`-shm`) | 否 | 只读 |
| `WORKSPACES` | 工作区 | `filesDir/workspaces` | 否 | 只读且**默认不计算**（见 §六.6） |

> 明确定义各类「大小」= 目录内所有普通文件长度之和，不含空目录与目录本身；`atomic-` 前缀的中间文件
> （`AtomicFileWrite.kt:16`）不计入，与 `FilesManager.countChatFiles` 口径保持一致。

### 5.2 红线

1. **禁止按目录一刀切删除用户数据。** `FileFolders.AVATARS` 的注释（`FilesManager.kt:810-823`）记录了
   一次真实事故：整目录清理把正被引用的头像删掉。清理实现必须是「id → 白名单路径/动作」的显式映射。
2. **用户数据只读。** `upload`、`exports`、`avatars`、`images`、`media_creation` 等只展示大小，
   入口跳转到既有文件管理页。
3. **数据库只读。** 不删 `-wal`（会丢已提交事务），不做 VACUUM。
4. **工作区不参与一键清理。** rootfs 属用户数据；即使展示也不做递归遍历。
5. **与备份/恢复互斥。** 恢复流程会替换数据库、写入 `restore_*`（`BackupArchiveEngine.kt:232`），
   清理期间必须禁用，反之亦然。
6. **失败必须上报。** 不允许「吞异常后显示 0 B / 清理成功」。

## 六、方案设计

### 6.1 数据模型

```kotlin
enum class StorageCategoryId { IMAGE_CACHE, TEMP, WEBVIEW, THUMBS, TEX, LOGS, BACKUP_TEMP, USER_FILES, DB, WORKSPACES }

data class StorageCategory(
    val id: StorageCategoryId,
    val sizeBytes: Long,
    val itemCount: Int,
    val clearable: Boolean,
    val readable: Boolean = true,   // false = 未统计（如 WORKSPACES）
)

data class ClearResult(
    val id: StorageCategoryId,
    val freedBytes: Long,
    val failures: List<String>,     // 空 = 全部成功
)
```

### 6.2 `StorageUsageManager`（Koin `single`）

```kotlin
class StorageUsageManager(
    private val context: Context,
    private val filesManager: FilesManager,
) {
    suspend fun scan(): List<StorageCategory>
    suspend fun clear(id: StorageCategoryId): ClearResult
}
```

要点：

- **一份白名单定义两件事**：`StorageTarget(id, dir, matcher, clearer)`，统计与清理共用，杜绝
  Legado 那种「统计按 A 结构、清理按 B 结构」的漂移；
- 统计用 `withContext(Dispatchers.IO)`，每个分类一个 `async`，但分类数只有个位数，无需复杂调度；
- 目录遍历抽成 `File.sizeRecursively(): Long`（建议放 `app/utils`，或与 `common` 共用的 `FileSize.kt`），
  使用显式栈，**跳过符号链接**，不可读目录计入 `failures` 而不是静默 0；
- `clear` 逐项执行并汇总，**不使用** `FilesManager.deleteAll` 的整目录语义；`USER_FILES`/`DB`/`WORKSPACES`
  的 `clear` 直接返回「不可清理」，由 UI 隐藏按钮而不是调用后静默无效果；
- 清理日志类走 `Logging.clearLogFile()`，不直接删目录；
- 不做 30s 全局 TTL；若后续需要缓存，改为 `Map<StorageCategoryId, Pair<Long, Long>>`（值 + 时间戳）。

### 6.3 `StorageVM`

```kotlin
class StorageVM(private val manager: StorageUsageManager) : ViewModel() {
    val state: StateFlow<StorageUiState>   // Idle / Scanning / Ready(list) / Clearing(id) / Error(msg)
    fun refresh()
    fun clear(id: StorageCategoryId)
}
```

- `viewModelScope.launch` + `Dispatchers.IO`，与 `BackupVM`（`BackupVM.kt:47-90`）的模式一致；
- 清理是短任务，**留在 `viewModelScope`**，离开页面即取消；不使用 `AppScope`（避免「后台删一半」）；
- 清理完成后重新 `scan()` 并 `toaster.show` 汇总（参考 `SettingFilesPage.operate(...)` 的报告模式）；
- 并发保护：`Clearing` 状态下忽略新的 `refresh`/`clear`。

### 6.4 页面 `SettingStoragePage`

- 骨架复用 `SettingFilesPage`：`Scaffold` + `LargeFlexibleTopAppBar` + `BackButton` + `CustomColors.topBarColors`；
- 顶部汇总卡：总大小 + 可清理项大小（沿用 `CacheSummaryCard` 的信息结构，但用本项目主题色）；
- 列表：`CardGroup.item` 或 `FormItem`，每行「图标 + 名称 + 大小 + 清理按钮/只读标签」；
- 确认框用 `RikkaConfirmDialog`，文案必须列出**将删除什么、不会删除什么**；
- 图标按 AGENTS.md 要求用 Lucide/HugeIcons；
- 只读项（DB/WORKSPACES/USER_FILES）不显示清理按钮；`USER_FILES` 行可点击跳转文件管理页。

### 6.5 接线

1. 路由：`Screen.SettingStorage`（`RouteActivity.kt:900-913` 区域）+ `entry<Screen.SettingStorage>`（`:617` 区域）；
2. 入口：`SettingPage` 的 `connectionAndData` 分组，放在「聊天存储」下方（`SettingPage.kt:236-253` 附近）；
3. DI：`RepositoryModule.kt:118` 附近注册 manager；`ViewModelModule.kt:36` 注册 `viewModelOf(::StorageVM)`；
4. 搜索索引：`SettingSearchIndex.kt:53-57` 目前把「缓存管理 / 存储管理」关键词挂在文件管理页上，新增页后需
   **迁移/拆分关键词**，否则搜索结果指向错页；
5. 字符串：`setting_storage_page_*`，英文默认 + `values-zh` + `values-zh-rTW` 三份齐全。

### 6.6 工作区与统计性能

`workspaces/<root>/linux` 文件数极多（rootfs 解包），全量遍历会让首屏卡到秒级。方案：

- MVP **不遍历**，行显示「未统计」并提供「前往工作区」跳转；
- 若后续必须显示，使用启动后由 `WorkspaceManager` 维护的缓存值，或限制 2 层深度 + 单目录文件数上限，
  超限标记「统计不完整」，**绝不在 UI 线程或首屏同步递归**。

## 七、受影响文件清单（预估）

| 类型 | 文件 | 目的 |
|---|---|---|
| 新增 | `app/.../data/storage/StorageUsageManager.kt` | 白名单统计 + 清理，返回逐项结果 |
| 新增 | `app/.../ui/pages/setting/SettingStoragePage.kt` | 页面 |
| 新增 | `app/.../ui/pages/setting/StorageVM.kt` | 状态与动作 |
| 修改 | `di/RepositoryModule.kt` | 注册 manager |
| 修改 | `di/ViewModelModule.kt` | 注册 VM |
| 修改 | `RouteActivity.kt` | `Screen.SettingStorage` + entry |
| 修改 | `ui/pages/setting/SettingPage.kt` | 设置入口 |
| 修改 | `ui/pages/setting/search/SettingSearchIndex.kt` | 索引与关键词归属 |
| 修改 | `res/values*/strings.xml`（3 份） | 文案 |
| 可选 | `common/.../FileSize.kt` | 共用递归大小工具（避免第三份实现） |

不涉及：`AndroidManifest.xml`、`build.gradle.kts`、权限、新依赖。

## 八、测试计划

### 8.1 JVM 单测（`app/src/test`，JUnit4 + coroutines-test）

1. `sizeRecursively`：空目录 / 嵌套 / 不可读目录 / 符号链接跳过 / `atomic-` 中间文件排除；
2. 白名单映射：给定假目录树，断言每个 `StorageCategoryId` 的路径与大小；
3. **回归用例**：`avatars`、`images`、`media_creation` 在 `clearAll` 后仍然存在；
4. `DB` / `WORKSPACES` / `USER_FILES` 的 `clear` 返回「不可清理」，不产生任何删除；
5. 失败路径：删除返回 false 时 `ClearResult.failures` 非空、`freedBytes` 不计入；
6. 并发保护：`Clearing` 期间重复 `clear` 被忽略（可用假 manager + `runTest`）。

参考现有纯逻辑测试：`ExportRetentionTest`、`ManagedFileBatchOperationsTest`。

### 8.2 手工验收矩阵

| 场景 | 期望 |
|---|---|
| 首次进入 | 显示总量与各类大小；工作区显示「未统计」 |
| 清理图片缓存 | 大小归零、再次进入不反弹；聊天图片重新加载正常 |
| 清理 temp | 与冷静启动后一致；正在进行的导出/生成不受影响（页面内清理时无此类任务） |
| 清理日志 | 日志页显示为空，后续日志正常续写 |
| 备份进行中进入 | 「备份临时文件」清理禁用并给出原因 |
| 恢复进行中进入 | 所有清理禁用或页面给出阻塞提示 |
| 清理后返回设置页 | 「聊天存储」数字需刷新（见 §九 待决 4） |
| 无文件/空缓存 | 显示 0 而不是 Loading 卡死 |

## 九、待决策（实施前需确认）

1. **工作区是否出现在页面上？** 建议：出现但只读且显示「未统计」（避免「缓存」语义误导）。
2. **数据库是只读还是提供「压缩」？** 建议：只读。VACUUM 需独占、耗时长，收益与风险不匹配。
3. **`upload` / `exports` 是否纳入清理？** 建议：不纳入，仅展示 + 跳转既有文件管理页，避免双入口。
4. **`SettingPage` 的「聊天存储」计数是否改为 Flow？** 已按「RESUME 重算」落地（Phase 2），
   不使用 Flow（避免设置页常驻时持续 IO）。
5. **是否把 `RikkaHubApp` 的启动清理收敛进 manager？** 已做**最小收敛**：共享路径常量，
   `deleteTempFiles()` 委托给 `clear(TEMP)`；其余 5 项（exports TTL、tool_outputs、workspace tmp、
   managed_files 对账、头像迁移）仍留在 `RikkaHubApp`——它们是生命周期/数据策略，不是缓存口径。

## 十、分期

- **Phase 1（已实现，2026-10-25）**：只读聚合 + 安全清理（图片缓存 / temp / webview / 缩略图 / texfmt /
  日志 / 备份临时文件）。不做明细展开、不做 DB、不遍历工作区。
- **Phase 2（已实现，2026-10-25）**：分类可展开（备份临时文件列表、日志文件列表）、结果报告、
  与备份/恢复的互斥、设置页计数刷新。
- **Phase 3（部分，2026-10-25）**：启动清理最小收敛（共享常量 + `temp` 委托）已落地；
  工作区/数据库只读展示待定（§九 1、2）。

## 十一、实现结果（Phase 1 + Phase 2 + Phase 3 部分，2026-10-25）

| 文件 | 内容 |
|---|---|
| `app/.../data/storage/StorageUsageManager.kt` | 7 类白名单、递归统计（跳符号链接）、逐项清理、`ClearResult` |
| `app/.../ui/pages/setting/StorageVM.kt` | `StateFlow` 状态、扫描/清理、并发保护、清理事件 |
| `app/.../ui/pages/setting/SettingStoragePage.kt` | 汇总卡、分类列表、确认框、失败报告、错误重试 |
| `app/.../RouteActivity.kt` | `Screen.SettingStorage` + entry |
| `app/.../ui/pages/setting/SettingPage.kt` | 「连接与数据」分组新增入口 |
| `app/.../di/{RepositoryModule,ViewModelModule}.kt` | 注册 manager 与 VM |
| `app/.../search/SettingSearchIndex.kt` | 新条目；「缓存管理 / 缓存空间」关键词从文件管理页迁出 |
| `res/values{,-zh,-zh-rTW}/strings.xml` | 32 条文案，三语言齐全 |
| `app/src/test/.../StorageUsageManagerTest.kt` | 7 项：口径、白名单、目录保留、日志钩子、符号链接 |

### Phase 2 新增/改动

| 文件 | 内容 |
|---|---|
| `app/.../data/DataOperationLock.kt` | 进程级互斥闸门：备份/恢复 `runWaiting`、清理 `runImmediate`、`active` 供 UI |
| `app/.../data/sync/BackupArchiveEngine.kt` | `prepareBackupFile` / `restoreFromBackupFile` 外包闸门（覆盖 WebDAV、S3、本地导出/恢复） |
| `app/.../data/storage/StorageUsageManager.kt` | `details()`：备份临时文件 / 日志逐文件列表；`canExpand` |
| `app/.../ui/pages/setting/StorageVM.kt` | 展开状态、阻塞态、`StorageEvent`（Cleared / Busy）、闸门感知 |
| `app/.../ui/pages/setting/SettingStoragePage.kt` | 可展开卡片、阻塞横幅、带分类名的结果提示 |
| `app/.../ui/pages/setting/SettingPage.kt` | 「聊天存储」计数改为 `LifecycleResumeEffect` 重算 |
| `app/src/test/...` | `StorageUsageManagerTest` 增至 11 项；新增 `DataOperationLockTest` 2 项 |

### Phase 3 部分落地（启动清理）

| 文件 | 内容 |
|---|---|
| `common/.../StorageNames.kt` | `TEMP` 目录名的唯一来源；`Context.appTempFolder` 改用它 |
| `app/.../data/storage/StoragePaths.kt` | 5 个缓存目录名统一出口（`TEMP` 复用 `StorageNames.TEMP`） |
| `app/.../RikkaHubApp.kt` | `deleteTempFiles()` 改为 `StorageUsageManager.clear(TEMP)`，失败写日志；其余启动清理不动 |

刻意只收敛这一项：另外 5 项是生命周期/数据策略（exports TTL 依赖 DataStore、workspace tmp 依赖
`WorkspaceManager`、`managed_files` 对账、头像迁移），不属于缓存统计口径；整体搬进来会把
manager 变成「启动 GC + UI 统计」混合体，并引入启动时序风险。

与草案的差异（刻意收窄）：

1. **仍没有「一键清理」**：互斥（Phase 2）已落地，但产品上仍只做按分类清理——批量入口容易让用户跳过
   确认，收益不抵风险。
2. **没有只读行**：`USER_FILES` / `DB` / `WORKSPACES` 按分期留到 Phase 3；当前页面只有 7 类可清理缓存，
   汇总卡因此叫「可清理缓存」而不是「总占用」。
3. **扫描不做结果缓存**：进页面即扫，避免 Legado 的全局 TTL 缺陷；数据量小，代价可接受。
4. **未做 `atomic-` 前缀过滤**：Phase 1 不含 `filesDir` 用户文件，该规则待 Phase 3 引入 `USER_FILES` 时补。

### 真机验证（PJX110，2026-10-25）

Debug 包 `applicationId` 独立（`app.androidrik.rikkahub.debug`），不影响正式版数据；通过 `run-as` 注入测试文件：

| 注入 | 大小 | 期望 | 实测 |
|---|---|---|---|
| `cache/image_cache/seed.bin` + `sub/seed2.bin` | 100 + 200 KB | 图片缓存 300 KB / 2 个文件 | 一致 |
| `cache/temp/seed.bin` | 50 KB | 50 KB | 一致 |
| `cache/workspace_svg_thumb/thumb.bin` | 10 KB | 10 KB | 一致 |
| `cache/texfmt/tex.bin` | 20 KB | 20 KB | 一致 |
| `cache/backup_seed.zip` | 30 KB | 备份临时文件 30 KB | 一致 |
| `cache/not_a_cache_keep.bin` | 40 KB | **不统计** | 总数 424 KB 未含它 |
| `files/avatars/keep_avatar.png` | 5 KB | **不清理** | 清理后仍在 |

- 清理图片缓存：300 KB → 0 B，Toast「已释放 300 KB」；`image_cache` 目录保留、`sub/` 已删；
  temp / 缩略图 / texfmt / 头像 / 非白名单文件均未受影响。
- 清理备份临时文件：`backup_seed.zip` 删除，非白名单文件保留。
- 清理日志：13.8 KB → 0 B，`files/logs` 为空。
- 空分类的删除按钮点击无响应（已禁用）。
- 入口位于「设置 → 连接与数据 → 缓存管理」；返回与再次进入正常；设置搜索 `cache` 命中该页且排第一。
- 注入的测试文件已清理，页面回到 0 B。

Phase 2 真机验证（同一轮）：

| 注入 | 大小 | 实测 |
|---|---|---|
| `cache/backup_alpha.zip` / `restore_beta` / `backup_db_snapshot.db` | 30 + 10 + 5 KB | 45 KB / 3 个文件；展开后逐项列出且按大小降序 |
| `cache/keep_me.bin` | 40 KB | 不计入；清理后仍在 |
| `files/logs/rikkahub-logs.1.txt` | 8 KB | 日志 8 KB / 1 个文件；展开后显示文件名与大小 |

- 清理备份临时文件：45 KB → 0 B；Toast「「备份临时文件」已清理，释放 45.0 KB」；展开区变为「无文件」。
- 互斥的「备份进行中」分支无真机触发条件（需可用 WebDAV/S3），由 `DataOperationLockTest` 覆盖。
- 设置页计数改为 RESUME 重算；「聊天存储」不受缓存清理影响，故未单独观测数值变化。

已知风险：互斥只覆盖 `BackupArchiveEngine` 的打包/恢复入口；`restoreFromChatBox` /
`restoreFromCherryStudio` 等不走该引擎的导入路径不与清理互斥（它们不写 `restore_*` 白名单文件）。
屏幕旋转/进程重建仍未单独测试。

## 附 A：为什么不直接移植 Legado 的 `StorageCalculator`

1. **口径不可复用**：其 7 类中有 3 类（TTS 明细、ACache 明细、LOG）在当前 Legado 代码里已失效，
   照抄会把 bug 一起搬来；
2. **数据性质不同**：本项目数据库是用户数据且强制 WAL，不能像 Legado 那样「删缓存行」；
3. **目录结构不同**：本项目的用户数据与缓存分得更细（`avatars`/`media_creation` 单独成目录），
   正适合白名单，而不适合「分类 = 目录」；
4. **已有基础设施**：删除、计数、格式化、确认框、日志清理都已存在，缺的只是聚合页；
5. **平台约束更简单**：全内部存储，无需外部存储权限与可用性判断。
