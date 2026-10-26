# 只读挂载快照 ReadOnlyMountSnapshots（设计稿，批次 2）

> 状态：**已落地（2026-10-26，单提交）**。来源：Yuihub 3.0.0 吸收项，评估见 `peer-apps-benchmark.md`
> §四.8 A、§五表 11。与设计的偏差：无（唯一调整：runner 的 `require(!readOnly)` 放在 start()
> 预检段而非 `-b` 循环内——更早 fail fast，且单测无需跑过 rootfs patch）。单测 17 项全绿
> （本机 symlink 可用，无跳过）；app 全量 2426 项仅 2 个既有基线失败。真机验收项待批次验收。

## 1. 要解决什么

PRoot 的 `-b` 绑定挂载没有只读语义：`/upload` 的「只读」目前只靠提示词
（`WorkspaceReminderTransformer`）+ 文件工具层（`writableRoots` 不含 `/upload`），
`workspace_shell` 一条 `rm -rf /upload/*` 就能绕过删掉用户上传原件。Yuihub 3.0.0 的解法：
**执行前把只读挂载源复制成私有快照目录再绑定**——shell 看到的 /upload 是快照，原件物理不可达。

## 2. 现状盘点（带证据）

- 挂载表：`RepositoryModule.kt:86-103`，`/skills` `/builtin_skills` `/tool_outputs` `/upload`
  四个 `WorkspaceBindMount`（`workspace/.../ProotShellRunner.kt:15-22`，**无 readOnly 字段**）。
- 消费点：①`WorkspaceManager.executeCommand`（`:300-322`）与 `startBackgroundCommand`
  （`:345-368`）把 `bindMounts` 原样放进 `WorkspaceShellContext`；
  ②`ProotShellRunner.start`（`:116-121`）逐个转成 `-b source:target`。
- 文件工具路径解析用同一张表（`WorkspaceManager.sortedBindMounts`），但写操作被
  `layout.writableRoots = [/workspace, /tmp]` 挡住 ⇒ 文件工具本就不能写 /upload，
  **绕过面只有 shell（前台 + 后台任务）**。
- 本地交互终端（`WorkspaceTerminalSession.kt:47-60`）**不挂 /upload**（只挂
  /workspace、/skills、/builtin_skills）⇒ 终端无此缺口，不需接快照。
- SSH 工作区不走 PRoot，无 bind mounts ⇒ 本特性只涉及本地工作区。

## 3. 设计

### 3.1 数据面：readOnly 字段 + 强制快照

- `WorkspaceBindMount` 增 `readOnly: Boolean = false`；`RepositoryModule` 的 `/upload` 标 true。
- `ProotShellRunner` 的 `-b` 循环加 `require(!mount.readOnly)`（对齐 Yuihub
  `ProotShellRunner.kt:28`）：任何未来调用方把只读挂载直接塞进 shell 上下文都会当场失败，
  而不是静默挂载原件。runner 其余部分不动——快照挂载以 `readOnly=false` 形态下发。

### 3.2 机制：ReadOnlyMountSnapshots（整体移植，包名适配）

`workspace/.../ReadOnlyMountSnapshots.kt`（~190 行），逐条对照 Yuihub 同名文件：

- **指纹**：`inspect()` 递归 SHA-256（目录条目名 + 文件内容/大小/mtime/可执行位，
  NFKC 无关，字段间 0 分隔），附 `bytes <= maxStorageBytes` 预算断言。
- **复用**：内存 `current` map（key = 存储目录\0source\0target），指纹一致且已发布快照
  复验一致且非 symlink ⇒ 复用，不重拷。
- **生成**：`Files.createTempDirectory(generation-)` → 逐文件复制（CREATE_NEW、
  NOFOLLOW_LINKS、可执行位与 mtime 保留）→ 复验指纹一致才发布；复制中源变化 ⇒ 丢弃重试。
- **安全检查**：源必须是目录；源与快照存储互不嵌套；存储目录自身非 symlink；
  symlink 逃逸（`toRealPath` 必须仍在源内）与目录环检测（ancestors 集合）。
- **预算**：1GB / 64 代；已发布代**永不删除**（终端/后台进程可能仍在用——本仓后台任务
  同样成立）；`requireCapacity` 超限抛错（错误文案说明原因）。与 CA 证书补丁的
  「内容变了才写」是同一纪律：指纹变了才新开一代，旧代不动。
- **与本仓的差异（刻意）**：①去掉 Yuihub `reuseUnchanged = !shellCompatibilityMode`
  的兼容模式开关，恒复用——其关复用的动机不可考，恒复用语义更可预测，由测试兜底；
  ②存储目录全局一份（`filesDir/read-only-snapshots`），不分工作区——/upload 源本就是
  全局的，快照跨工作区复用才成立（Yuihub 按工作区隔离是因为它同样按工作区算预算）。

### 3.3 接线：两处上下文构造点

`WorkspaceManager` 增参数 `readOnlySnapshotStorage: File?`（null = 关闭，测试用）；
`executeCommand` 与 `startBackgroundCommand` 在构造 `WorkspaceShellContext` 前执行：

```kotlin
val executionMounts = if (readOnlySnapshotStorage != null)
    snapshots.prepare(bindMounts, readOnlySnapshotStorage) else bindMounts
```

- prepare 只在存在 `readOnly && 源存在` 的挂载时才真正干活；其余挂载原样透传。
- 失败语义：prepare 抛错（预算耗尽/源变化重试/不支持文件）**向上传播**，与现有
  `require(workingDir.exists())` 同层——工具调用以错误文本收场，不静默降级成挂原件。
- 后台任务与前台命令可能并发 prepare：`storageLocks`（按存储目录）串行化复用判定；
  不加 Yuihub 的 `executionLocks`（本仓 per-command 无会话态，现有并发面不变）。

### 3.4 成本与运维

- **每次 shell 命令都会 walk+hash 一遍 /upload**（复用只省拷贝不省指纹）——Yuihub 同款。
  /upload 是用户上传目录，量级小；若将来成为瓶颈，再做 mtime 短路（记下不做）。
- 快照目录是**派生数据**：不进备份（BackupItem.FILES 不含它 ✓）；应在缓存管理里
  登记为可清理项（并入存储管理 Phase 3 只读行的后续，不在本批做）。

### 3.5 语义澄清（写进类注释）

- 保护对象是「经挂载点的原件」，不是「对抗 PRoot 任意宿主路径访问」。
- shell 对快照的写入**在会话内可见**（复用代不重拷），重启或换代后消失——这是
  「只读视图」的可接受近似，与 Yuihub 一致。
- `/skills` 等其余挂载保持可写（AI 需要写 skills），不在本批范围。

## 4. 验证计划

- 单测（`workspace/src/test`，移植 Yuihub `ReadOnlyMountSnapshotsTest` 场景 + 本仓接线）：
  ①prepare 产物 readOnly=false 且 source 指向快照；②原件不被改写（shell 写 /upload 后
  源文件不变）；③内容不变复用同一代（复制次数断言）；④内容变化新开一代且旧代保留；
  ⑤symlink 逃逸拒绝；⑥目录环拒绝；⑦复制中变化报错；⑧预算耗尽报错；⑨源/存储嵌套拒绝；
  ⑩non-readOnly 挂载原样透传；⑪storage=null 时跳过。
- Windows 跳过项沿用现有 `@Ignore`/条件模式（symlink 类用例在 Windows 受限）。
- 编译：`:workspace:compileDebugKotlin`、`:app:compileDebugKotlin`、
  `:workspace:testDebugUnitTest` 全绿。
- 真机（批次验收）：绑定 workspace 的助手 `rm -rf /upload/*` → 上传页文件仍在；
  shell 内 `ls /upload` 内容正常可见。

## 5. 明确不做

- 不给 /skills、/tool_outputs 加只读（AI 需要写）。
- 不做快照代回收（预算错误即兜底；GC 需要引用追踪，Yuihub 也不做）。
- 不做缓存管理登记（并入 Phase 3 后续）。
- 不动 SSH 工作区与交互终端（无此缺口）。
