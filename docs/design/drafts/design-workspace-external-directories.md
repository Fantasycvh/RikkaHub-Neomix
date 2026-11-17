# 工作区「访问外部指定目录」设计稿

> 状态：**本稿重方案（SAF/快照）未实施，留档备查**；2026-11-17 更新——「外部目录进工作区」的需求已按**上游轻量方案**落地（`5c37aa595` 吸收 `fab0c077d`）：`/storage` 下目录 bind 进 Rootfs（LIVE 直读直写，无 SNAPSHOT 档、限共享存储路径），见 `WorkspaceStorageMount` + 工作区详情页存储挂载卡。本稿的 LIVE/SNAPSHOT 两档与任意路径授权属**更完整的原设想**，若轻量方案不够用再重启。
> 原状态：设计稿（未实现），2026-11-15。范围限定为「把用户指定的外部目录挂进本地 PRoot 工作区」。
> 已确认的产品决策：① 配置**按工作区独立**；② 提供**实时读写（LIVE）**与**只读快照（SNAPSHOT）**两档；
> ③ 本轮**不做**恢复层（trash / checkpoint / side-car git）。
> 刻意不做：Shizuku / root shell、SAF URI 实时映射（非 root 无 `/dev/fuse`）、E/Android 37 文档回收站。

## 1. 要解决什么

现在 AI 只能看到 `/workspace`（应用私有文件区）、`/skills`、`/tool_outputs`、`/upload` 这几处，
用户真正的文档/代码目录（`/sdcard/Documents`、外置 SD 卡上的仓库等）既不可见也不可写。
目标是让用户在**工作区详情页**授权一个外部目录后，该目录以稳定路径出现在 shell 与文件工具里，
例如 `/mnt/ext/Camera`。

成功标准（按优先级）：

1. `workspace_shell` 能 `ls/cat/grep/rg/git status` 到授权目录；
2. LIVE 模式下能在授权目录里 `git commit`、`npm run build` 这类真实写操作；
3. SNAPSHOT 模式下原件在物理上不可被 shell 修改（复用现有快照机制）；
4. 权限被撤销 / SD 卡拔出 / 目录被删时，失败**可见且可解释**，不静默降级成「挂了个空目录」。

## 2. 现状盘点（均对照源码）

- **挂载表是静态的**：DI 里一次性构造四个 `WorkspaceBindMount`（`/skills` `/builtin_skills`
  `/tool_outputs` `/upload`），交给 `WorkspaceManager` 单例
  （[RepositoryModule.kt:96-112](../../../app/src/main/java/app/androidrik/rikkahub/di/RepositoryModule.kt#L96)）。
  单例被 `WorkspaceManager` 的 `bindMounts` 字段持有并按 target 长度排序（`sortedBindMounts`，
  [WorkspaceManager.kt:63](../../../workspace/src/main/java/app/androidrik/workspace/WorkspaceManager.kt#L63)）。
- **两处消费同一张表**：① `executeCommand` / `startBackgroundCommand` 构造
  `WorkspaceShellContext.bindMounts`（[WorkspaceManager.kt:310](../../../workspace/src/main/java/app/androidrik/workspace/WorkspaceManager.kt#L310)、
  [:363](../../../workspace/src/main/java/app/androidrik/workspace/WorkspaceManager.kt#L363)）；
  ② 文件工具的 rootfs 路径解析 `resolveRootfsPath`
  （[WorkspaceManager.kt:170](../../../workspace/src/main/java/app/androidrik/workspace/WorkspaceManager.kt#L170)）。
- **只读挂载 = 私有快照**：`WorkspaceBindMount.readOnly` 只是声明，binding 前必须经
  `ReadOnlyMountSnapshots.prepareLeased` 换成副本，runner 用 `require(!readOnly)` 强制
  （[ProotShellRunner.kt:77](../../../workspace/src/main/java/app/androidrik/workspace/ProotShellRunner.kt#L77)、
  [WorkspaceManager.kt:416](../../../workspace/src/main/java/app/androidrik/workspace/WorkspaceManager.kt#L416)）。
  预算是 **1GB / 64 代**（[ReadOnlyMountSnapshots.kt:72](../../../workspace/src/main/java/app/androidrik/workspace/ReadOnlyMountSnapshots.kt#L72)），
  快速签名不变时复用同一代，源一变就整棵重拷。
- **交互终端另拼一份挂载参数**，只挂 `/workspace` `/skills` `/builtin_skills`
  （[WorkspaceTerminalSession.kt:54-74](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSession.kt#L54)），
  由 `WorkspaceTerminalSessionManager.terminalTransport` 在 `createTab`（suspend）里构造
  （[WorkspaceTerminalSessionManager.kt:202](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSessionManager.kt#L202)）。
  ⇒ 新挂载若要出现在终端，**必须同步改这处**，否则「工具里看得见、终端里看不见」。
- **清单没有全盘权限**：只有 `WRITE_EXTERNAL_STORAGE` 且 `maxSdk=28`，无 `MANAGE_EXTERNAL_STORAGE`、
  无 `READ_EXTERNAL_STORAGE`（[AndroidManifest.xml:8-10](../../../app/src/main/AndroidManifest.xml#L8)）。
  minSdk 26 / targetSdk 37（[app/build.gradle.kts:32-33](../../../app/build.gradle.kts#L32)）。
- **写外部路径走 shell**：`LocalWorkspaceBackend.writeAbsoluteText` 用 `mkdir -p && cat >`
  （[LocalWorkspaceBackend.kt:69-97](../../../workspace/src/main/java/app/androidrik/workspace/LocalWorkspaceBackend.kt#L69)），
  即文件工具的写最终落进 shell 视图——SNAPSHOT 模式下会写进临时副本。
- **工作区实体可扩展**：`WorkspaceEntity` 已有若干 JSON 列（`tool_approvals`、`package_mirrors`），
  迁移是手写/自动 ADD COLUMN（DB `version = 71`，`SCHEMA_VERSION = 71`，
  [AppDatabase.kt:96](../../../app/src/main/java/app/androidrik/rikkahub/data/db/AppDatabase.kt#L96)、
  [:279](../../../app/src/main/java/app/androidrik/rikkahub/data/db/AppDatabase.kt#L279)）。

## 3. 三个决定性事实（决定了设计形态）

1. **PRoot 的 `-b` 没有只读语义。** 「只读」只能靠「绑一份私有副本」实现；
   想让 shell 看到**实时**内容，就必须绑原件，而原件对 app uid 可写。
   ⇒ 「实时」与「不可写」不可兼得，只能做成用户按目录二选一。
2. **按路径访问共享存储需要全盘权限。** SAF 的 tree 授权只给 `content://`，不给文件路径；
   要让 PRoot 绑真实路径（以及 `File` API 直读），Android 11+ 必须
   `MANAGE_EXTERNAL_STORAGE`（`Environment.isExternalStorageManager()`）。SAF 只负责「选目录 + 快照模式的读授权」。
3. **外部目录不保证可解析成路径。** 云盘 / 部分三方 provider 的 tree document id 不是
   `<volumeId>:<relativePath>`，无法映射成文件路径 ⇒ 这些目录只能走 SNAPSHOT。

## 4. 设计

### 4.1 数据模型（每工作区一份）

```kotlin
// workspace 模块：纯数据，不依赖 Android
@Serializable
data class WorkspaceExternalMount(
    val treeUri: String,       // SAF tree URI（授权凭证 + SNAPSHOT 源标识）
    val name: String,          // 选择时的显示名，例如 "Camera"
    val target: String,        // 稳定挂载点，例如 "/mnt/ext/Camera-a1b2c3"，添加后不再变
    val mode: ExternalMountMode, // LIVE / SNAPSHOT
    val addedAt: Long,
)

enum class ExternalMountMode { LIVE, SNAPSHOT }
```

`WorkspaceEntity` 增列（NOT NULL + DEFAULT `[]`，零回填）：

```kotlin
@ColumnInfo("external_mounts", defaultValue = "[]")
val externalMounts: String = "[]"

fun externalMountList(): List<WorkspaceExternalMount>   // 解析失败一律 emptyList（同 toolApprovals）
```

- **target 在添加时固定**：由显示名生成，非法字符（含非 ASCII 走 `dir` 前缀）时回退
  `dir-<hash6>`；冲突再加 `-2`。之后用户改名/重选目录都不改 target，避免对话里已生成的路径失效。
- **去重键 = treeUri**：同一目录重复添加直接覆盖原条目的 mode（不新增第二条）。
- 与 `tool_approvals` 一致：JSON 损坏时回落空列表，不阻断工作区打开。

### 4.2 路径解析（SAF tree → 真实路径）

新增 `app/.../data/workspace/ExternalMountResolver.kt`（纯映射函数可单测）：

| tree document id | 真实路径 |
|---|---|
| `primary:DCIM/Camera` | `${Environment.getExternalStorageDirectory()}/DCIM/Camera` |
| `1234-ABCD:repos/x` | `/storage/1234-ABCD/repos/x` |
| 无 `:` 前缀 / 其他 provider | 不可解析 ⇒ 只允许 SNAPSHOT |

- 解析后要求 `isDirectory && canRead`；失败视为「目录不可用」。
- 明确不可用区域（即使有全盘权限）：`Android/data`、`Android/obb` 及系统限制目录。
- 解析结果**不落库**（SD 卡卷 id 稳定，但设备重挂载后路径可能变），每次按 treeUri 重算。

### 4.3 两种模式的语义（写进 UI 文案与工具提示）

| | LIVE | SNAPSHOT |
|---|---|---|
| 需要权限 | `MANAGE_EXTERNAL_STORAGE`（API ≤29 用存储运行时权限） | 仅 SAF 读授权 |
| shell 看到 | 原件（实时） | 私有副本（每条命令按需刷新） |
| 写入 | 真实落盘 | 只落进临时副本，命令结束/换代即丢 |
| 文件工具读 | 原件 | 原件（`resolveRootfsPath` 不经过快照） |
| 交互终端 | 可见 | **不可见**（无租约生命周期，不挂） |
| 成本 | 零拷贝 | 源一变整棵重拷；共享 1GB / 64 代预算 |
| 适用 | 代码仓库、要交付的目录 | 小体量、基本静态的文档目录 |

SNAPSHOT 准入门槛（添加时做一次有界扫描，避免必然失败）：

- 目录内存在 symlink ⇒ 拒绝（现有快照 fail-closed，会报 `symlink escapes source`）；
- 总大小 > **512MB** 或条目 > **20000** ⇒ 拒绝，并提示改用 LIVE；
- 扫描在 IO 线程、达到上限立即中止（不遍历整棵树）。

### 4.4 挂载表动态化（核心改动，尽量小）

`WorkspaceManager` 增加**按 root 取附加挂载**的提供者，静态表保持不动：

```kotlin
class WorkspaceManager(
    ...,
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /** 每工作区的外部挂载（app 侧 registry 提供；workspace 模块不依赖 Android/DB） */
    private val externalMountsProvider: (String) -> List<WorkspaceBindMount> = { emptyList() },
) {
    private fun mountsFor(root: String): List<WorkspaceBindMount> =
        (bindMounts + externalMountsProvider(root)).sortedByDescending { it.target.trimEnd('/').length }
}
```

替换点（全部一处口径）：

- `resolveRootfsPath` / `resolveRootfsFile`：`sortedBindMounts` → `mountsFor(root)`；
- `executeCommand` / `startBackgroundCommand`：取 `mountsFor(root)`，透传给 `preparedBindMountLease(mounts)`；
- `preparedBindMountLease` 改签名接收 mounts（仍是「有 `readOnly` 才准备快照」）。

`sortedBindMounts` 字段删除（避免两套排序口径）。

### 4.5 app 侧 registry：把实体里的配置变成挂载

新增 `app/.../data/workspace/WorkspaceExternalMountRegistry.kt`（Koin single，依赖 `Context`）：

- 内存表 `root -> List<WorkspaceExternalMount>`，由数据层在**读到/改到实体时**刷新：
  - `WorkspaceRepository.getById`、`create`、`checkIntegrity`；
  - `addExternalMount` / `removeExternalMount` / `setExternalMountMode`；
  - 删除工作区时清空。
- `bindMountsFor(root)`（同步，供 provider 调用）：逐条判状态，只下发**可用**的：
  - SNAPSHOT：`readOnly = true`（交给现有快照换副本）；
  - LIVE：`readOnly = false`，且必须 `isExternalStorageManager()`（或 API ≤29 有存储权限）且路径可解析，
    否则该条不下发（UI 同时显示「不可用」，不让模型看到一个不存在的路径）。
- 权限检查每次调用都做：`ContentResolver.persistedUriPermissions` + `isExternalStorageManager()`，
  数量是个位数，相对 fork 进程可忽略；不做缓存，避免撤销权限后仍挂旧路径。

DI（[RepositoryModule.kt:91](../../../app/src/main/java/app/androidrik/rikkahub/di/RepositoryModule.kt#L91)）：

```kotlin
single { WorkspaceExternalMountRegistry(get()) }
single {
    WorkspaceManager(
        ...,
        externalMountsProvider = { root -> get<WorkspaceExternalMountRegistry>().bindMountsFor(root) },
    )
}
single { WorkspaceRepository(get(), get(), get(), get(), get(), get()) } // +registry
```

### 4.6 终端接线（必须同步，否则两套视图漂移）

- `WorkspaceTerminalSessionManager.createTab` 已是 suspend：构造 transport 前取
  `registry.bindMountsFor(root).filter { !it.readOnly }`，传给 `createLocalTerminalTransport(..., mounts)`；
- `createLocalTerminalTransport` 的 `args` 在 `/builtin_skills` 之后追加
  `-b <source>:<target>`（只加 LIVE；SNAPSHOT 不挂）。
- 现状里终端也**没有** `/upload` 与 `/tool_outputs`（只读/只写区），本稿不顺手改（避免扩大面）。

### 4.7 写保护：SNAPSHOT 必须拒写

- `WorkspaceRepository.writeTextInRootfs` / `writeAbsoluteText` 入口检查：目标路径命中的挂载若为
  SNAPSHOT，直接 `error("SNAPSHOT mount is read-only: <target>")`，**不落到 shell**。
- shell 内的写无法拦截（命令执行前不可知），落进临时副本后随换代消失；
  UI 与提示词必须写明「只读快照：shell 的写入不会落盘」。
- `workspace_write_file` 既有的 `needsApproval = ... || pathOutsideWritableRoots` 保持不变：
  `/mnt/ext/**` 不在 `writableRoots`（`/workspace`、`/tmp`）内 ⇒ LIVE 下写外部目录**默认要审批**，
  这正好是「写面最小化」的现成闸门（[WorkspaceTools.kt:342](../../../app/src/main/java/app/androidrik/rikkahub/data/ai/tools/WorkspaceTools.kt#L342)）。

### 4.8 提示词：让模型知道有哪些外部目录

`WorkspaceReminderTransformer.buildWorkspacePrompt`（本地分支，
[WorkspaceReminderTransformer.kt:55](../../../app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt#L55)）
追加一段挂载清单，逐条给出 `路径 · 模式 · 是否可写`，例如：

```
External directories:
- /mnt/ext/Camera (read-only snapshot: writes inside it are discarded)
- /mnt/ext/repos (read-write: changes are applied to the real directory)
```

只列**当前可用**的条目（与 `bindMountsFor` 同口径），不可用条目不出现在提示词里——
否则模型会去读一个不存在的路径并反复试错。

### 4.9 UI（工作区详情页「基本信息」Tab）

新增 `WorkspaceExternalMountCard`（[WorkspaceBasicTab.kt](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceBasicTab.kt) 内，
作为 `WorkspaceBasicPage` 里「启用 Shell」卡（[:162](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceBasicTab.kt#L162) 起的 `item`）之后的新 item，
仅本地工作区渲染）：

- 行内容：显示名 + target（等宽）+ 模式 chip + 状态行（正常 / 需要全盘权限 / 授权已失效 / 路径不可解析）；
  右侧「切换模式」「移除」；
- 底部「添加目录」→ `ActivityResultContracts.OpenDocumentTree()`（[WorkspaceDetailPage.kt:241-247](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt#L241) 已有同款用法），
  选中后 **`takePersistableUriPermission(READ|WRITE)`**，再弹出模式选择 sheet：
  - LIVE：解析成功且已有全盘权限才可选；否则显示「去授权」（`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`）；
  - SNAPSHOT：始终可选，附扫描结果（大小/条目数）与「写入不落盘」说明；
- 添加/切换/移除都走 VM（新增 3 个方法），成功后刷新卡片；
- 返回页面时（`LifecycleResume`）重新计算状态——用户可能刚从系统设置里授权/取消；
- 权限失效 / 路径不可用两种状态都提供「重新绑定」：重选 treeUri，保留 target 与 mode；
- 移除时：若没有其他工作区引用同一 treeUri，`releasePersistableUriPermission`（避免留下用户看不见的长期授权）。

### 4.10 持久化迁移

- `WorkspaceEntity` 加列（见 4.1）；
- `AppDatabase`：`version = 72`、`SCHEMA_VERSION = 72`、`autoMigrations += AutoMigration(from = 71, to = 72)`
  （NOT NULL + DEFAULT `[]`，纯 ADD COLUMN，无需 spec）；
- schema JSON 由构建生成，**不手写**；`SchemaVersionConsistencyTest` 与启动哨兵会兜住漂移。

## 5. 失败与边界

| 场景 | 行为 |
|---|---|
| 全盘权限未授予 / 被撤销 | LIVE 条目不下发；卡片状态「需要所有文件访问」，shell 里没有该目录 |
| SAF 授权被系统回收（清数据、恢复备份、provider 撤销） | SNAPSHOT 源不可达 + 状态「授权已失效」，提供「重新绑定」 |
| SD 卡拔出 / 目录被删 | `canRead` 失败 ⇒ 不下发；状态「目录不可用」 |
| treeUri 无法映射路径 | LIVE 不可选；只能 SNAPSHOT |
| SNAPSHOT 超预算（源变化后重拷顶到 1GB/64 代） | 现有 `requireCapacity` 抛错，shell 命令以错误文本收场（含原因），不静默挂原件 |
| SNAPSHOT 目录含 symlink | 添加时扫描即拒绝；运行时若新增 symlink，快照 fail-closed 报错 |
| 同一目录被多个工作区挂载 | treeUri 去重后各自独立；SNAPSHOT 代按「存储目录 + 源 + target」共享 |
| 删除工作区 | 清 registry 条目 + 条件释放持久授权；不删外部目录本身 |
| 备份 / 恢复 | 列随实体备份；跨设备恢复后授权必然失效 ⇒ 显示「重新绑定」，不静默挂空 |
| SSH 工作区 | 本特性完全不可见（无 PRoot、无本地路径） |

## 6. 验证计划

**workspace 模块单测（JVM）**

1. `mountsFor` 合并静态表 + 动态表，且按 target 长度降序（`/mnt/ext/a/b` 优先于 `/mnt/ext/a`）；
2. `resolveRootfsPath("/mnt/ext/x/y")` 落到外部源；`/mnt/extx` 不误命中；
3. 动态挂载在 `executeCommand` / `startBackgroundCommand` 的上下文里可见（假 runner 断言）；
4. SNAPSHOT 挂载仍是 `readOnly=true` → 无快照存储时 runner 必须拒绝（既有 require 生效）。

**app 模块单测（JVM）**

5. `resolveTreeDocumentId`：primary / 卷 id / 无冒号 / 非法字符 / 空 relative；
6. 实体 JSON：正常、空、损坏（回落 `[]`）、重复 treeUri 去重；
7. target 生成：非法名回退 `dir-<hash>`、冲突加序号、ASCII 名保留；
8. 写保护：SNAPSHOT 目标的 `writeTextInRootfs` / `writeAbsoluteText` 抛错；
9. 模式判定：未授权 LIVE 不下发、SNAPSHOT 永远下发（READ_ONLY 声明）。

**真机验收（不进 CI）**

10. 内建存储选 `Documents` + LIVE：`git init/commit`、`npm i` 落盘可见（另用文件管理器确认）；
11. 外置 SD 卡 + LIVE：同上，然后拔卡 → 状态转「目录不可用」、命令报错清晰；
12. SNAPSHOT：原件在 shell 内 `rm` 后原件仍在；shell 写文件后重启应用内容消失；
13. 中途去系统设置撤销「所有文件访问」→ 卡片状态与 shell 视图同步变化；
14. 终端页签能看到 LIVE 目录、看不到 SNAPSHOT 目录。

## 7. 明确不做（本轮）

- **恢复层**：trash / checkpoint / side-car git / `MediaStore.createTrashRequest` 全部不做。
  LIVE 下的误删**不可撤销**，靠 UI 文案与默认审批兜底；后续单独设计。
- **SAF 实时映射**：需要拦 `openat` 或自建双向同步，不做。
- **给 shell 加重启级只读**：PRoot 无此能力，快照是唯一近似。
- **app 私有目录隔离**：`workspace_shell` 仍能读 `filesDir`（API key 等），本稿不改变这个事实；
  那属于独立的安全基线（HARDLINE / 路径白名单）议题。
- **Shizuku / root**：不在本特性范围。
- 顺带补齐终端缺失的 `/upload`、`/tool_outputs` 挂载：不做（避免无关变更）。

## 8. 实施顺序（每步可独立编译/验证）

1. `workspace` 模块：`WorkspaceExternalMount` / `ExternalMountMode`；`WorkspaceManager` 的
   `externalMountsProvider` + `mountsFor` + 两处上下文/路径解析改造 + 单测（1–4）；
2. app 数据层：`ExternalMountResolver`（纯映射 + 状态判定）、`WorkspaceExternalMountRegistry`、
   实体列 + DAO 更新方法 + DB 71→72 + 单测（5–9）；
3. 写保护与提示词：写入口拒 SNAPSHOT、`WorkspaceReminderTransformer` 挂载清单；
4. DI 接线（manager provider、registry、repository 参数）；
5. 终端接线（LIVE-only）；
6. UI：卡片 + SAF 选择 + 模式 sheet + 权限引导 + 状态/重新绑定/移除 + 字符串；
7. 真机验收 10–14，补 `docs/design/verification/` 记录后移入 `completed/`。

### 涉及文件清单

- 改：`workspace/.../WorkspaceManager.kt`、`workspace/.../Workspace.kt`（新数据类）
- 改：`app/.../di/RepositoryModule.kt`、`app/.../data/db/entity/WorkspaceEntity.kt`、
  `app/.../data/db/dao/WorkspaceDAO.kt`、`app/.../data/db/AppDatabase.kt`
- 改：`app/.../data/repository/WorkspaceRepository.kt`
- 改：`app/.../ui/pages/extensions/workspace/WorkspaceBasicTab.kt`、`WorkspaceDetailVM.kt`
- 改：`app/.../ui/pages/extensions/workspace/WorkspaceTerminalSession.kt`、`WorkspaceTerminalSessionManager.kt`
- 改：`app/.../data/ai/transformers/WorkspaceReminderTransformer.kt`
- 改：`app/src/main/AndroidManifest.xml`（`MANAGE_EXTERNAL_STORAGE`；API ≤29 的 `READ_EXTERNAL_STORAGE`）
- 新增：`app/.../data/workspace/ExternalMountResolver.kt`、
  `app/.../data/workspace/WorkspaceExternalMountRegistry.kt`
- 新增：对应单测；`app/schemas/.../72.json`（构建产物）
