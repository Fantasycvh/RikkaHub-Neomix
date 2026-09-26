# 工作空间后台任务三件套（design-workspace-background-tasks）

> 状态：**已落地并通过真机验证**（2026-09-26）。代码：`workspace/.../WorkspaceBackgroundTasks.kt`（核心）、
> `workspace/.../ProcessSignals.kt`（进程树终止——**必读，平台绕行方案**）、
> `workspace/.../ProotShellRunner.kt::start`（预检+启动抽取）、`app/.../data/ai/tools/WorkspaceTools.kt`（三工具）。
> 测试：`workspace/src/test/.../WorkspaceBackgroundTasksTest.kt`（24 项）+ `ProcessSignalsTest.kt`（11 项）、
> `app/src/test/.../WorkspaceBackgroundToolApprovalTest.kt`、`ToolCallLoopGuardTest` 轮询豁免 2 项、
> **真机 instrumented** `app/src/androidTest/.../WorkspaceBackgroundTaskInstrumentedTest.kt`（8 项全绿，
> 含「完整输出落盘」与「超过时长上限被自动终止且无进程残留」两条）。

## 一、要解决的问题

`workspace_shell` 是**同步**工具：命令跑完才回填 stdout，而且超时上限 600s。于是两类命令在对话里
根本无法使用：

1. **比一轮对话活得更久的命令**（`apt install`、`pip install`、编译、下载、训练脚本）——要么被
   600s 上限砍掉，要么模型只能反复跑 `tail` 看进度，每一步都在烧 token。
2. **输出极大的命令**——`MAX_OUTPUT_CHARS = 128KB` 的头部截断结果直接进上下文，一次就能把窗口吃掉。

出处：`docs/peer-apps-benchmark.md` §二.2（rikkahub-agent 的 `workspace_run_background` /
`_status` / `_kill` 三工具 + `TailBuffer`），落地清单见 `docs/roadmap.md` 2026-09-10 条目 ④
「AI 后台任务三件套——长任务不阻塞对话」。

## 二、目标与非目标

**目标**

- 模型能启动长命令并**立刻继续**（不阻塞本轮），稍后用状态工具按需查看输出尾部与退出码。
- 失控任务可被终止，且**并发有上限**，不会把设备资源吃光。
- 输出**不进上下文**（模型主动查询时才取回尾部若干字符）。

**非目标（v1 明确不做，理由写在括号里）**

- **持久化 / 应用重启后接管任务**（需要脱离应用进程的常驻服务；收益不足，风险是「僵尸任务表」）。
- **stdin 支持**（后台任务不接收输入；需要交互的命令请用同步 shell 或终端页）。
- **宿主机 shell 变体**（`HostShellRunner` 无 rootfs，后台化的意义仅剩「不阻塞」，本轮不做）。
- **排队**（见「并发上限」一节的取舍）。
- **终端页联动 / 通知**（后台任务目前只在工具卡片里可见；见 §八 后续）。

## 三、分层与文件

```
WorkspaceTools (workspace_run_background / workspace_task_status / workspace_kill_task)
        ↓
WorkspaceRepository.startBackgroundCommand / listBackgroundTasks / getBackgroundTask / killBackgroundTask
        ↓                              （归属校验：只允许操作本工作空间的任务）
WorkspaceManager.startBackgroundCommand / backgroundTasks / backgroundTask / killBackgroundTask(s)
        ↓
WorkspaceTaskRegistry  ── 任务表 + 状态机 + 并发上限 + 淘汰
        ↓
WorkspaceTaskLauncher (接口)  ── ProotBackgroundLauncher（真实）／FakeLauncher（单测）
        ↓
ProotShellRunner.start()  → Process + 两个采集线程 → TailBuffer(stdout/stderr)
```

关键切分：**真实进程启动被隔离在 `WorkspaceTaskLauncher` 之后**，于是调度与状态机可以在 JVM 上
确定性地单测（不需要 rootfs 与真机）；而「流泵」`pumpText` 用真实 `InputStream` 单测，覆盖编码与
异常路径。这与仓库既有的「纯函数抽取以便单测」惯例一致。

`ProotShellRunner` 里把预检 + 启动抽成 `start(context): ProotStart`，同步 `execute` 改为
`when (start(...))`。抽出来的原因很实际：绑定挂载、`PROOT_NO_SECCOMP`、`PROOT_LOADER`/`PROOT_TMP_DIR`
这些坑两处各拼一遍命令行，早晚会漂移。

## 四、状态机与数据结构

| 状态 | 含义 | 备注 |
|---|---|---|
| `RUNNING` | 进程已启动、未回收 | kill 只在此状态生效 |
| `EXITED` | 进程自行结束 | `exitCode` 可能为 0 也可能非 0 |
| `KILLED` | 收到过 kill 请求后结束 | 与 `EXITED` 分开，避免「用户终止」被当成「命令失败」 |
| `TIMED_OUT` | 超过任务时长上限（默认 30 分钟）被自动终止 | 与 `KILLED` 再分开：**用户没动手**，模型要能分辨「超时」与「被否决」 |
| `FAILED` | 未启动成功，或回收时异常 | 原因写在 `stderr`，模型能当场看到 |

- `killRequested` 是独立标记：**只有它决定终态是 KILLED 还是 EXITED**（进程退出码在强杀路径不可靠）。
- `kill()` 与 `start()` 之间存在一个不可避免的窗口（`launch` 在注册表锁外执行，因为进程创建是重操作）。
  处理方式不是 sleep 等待，而是：`kill()` 先置 `killRequested` 再看 handle；`start()` 拿到 handle 后
  **补一次** `if (killRequested) handle.kill()`。两侧都做，请求不会丢。
- `WorkspaceTaskSnapshot` 是一次性拷贝（文本 + 累计字符数 + 截断标记），不暴露内部可变状态。

## 五、输出缓冲：为什么是「尾部」

| | 同步 `workspace_shell` | 后台任务 |
|---|---|---|
| 缓冲 | `StreamCollector`：**头部** 128KB | `TailBuffer`：**尾部** 8KB |
| 为何 | 一次性命令，结论常在开头（用法错误、报错首行） | 长任务，结论在结尾（构建失败原因、下载完成行） |

`TailBuffer` 同时记录**累计字符数**与**是否丢弃过**——否则模型看到 8K 的尾部，无法判断输出总共
有多少，也就无法判断「是不是该把结果写进文件而不是靠 stdout」。

返回给模型的路径上还有第二道闸：`_status` 单流只回 4000 字符（`TASK_TAIL_RETURN_CHARS`），
避免轮询本身变成上下文消耗的来源。

### 5.1 完整输出落盘（尾部之外的兜底）

只留尾部会让「前后文里的失败原因」凭空消失——长编译的头几条错误、`pip` 早期的依赖冲突都在被丢弃的
那一段里。所以每个任务**双写**：

- 尾部缓冲 → 给上下文（`_status` 轮询）；
- `<工作区文件区>/.tasks/<id>.stdout.log` + `.stderr.log` → 给事后回看。

两条路径由同一个 `TaskLogSink` 承担（`append` 同时写尾部与文件、每块 flush），写入失败只降级为
「没有日志」，绝不影响任务与尾部缓冲。工具结果里带上**rootfs 内可见路径**
（`/workspace/.tasks/<id>.stdout.log`），模型用 `workspace_read_file` 直接读——所以「完整日志」不需要
新增任何工具，也不需要把内容塞进上下文。

生命周期与任务表一致：任务被淘汰（保留 20 条）时其日志一并删除；进程重启后任务表已丢，日志按
**修改时间**淘汰（默认保留 24 小时，每个新任务启动时顺带清一次）。用户也能在工作区文件浏览器里
看到 `.tasks/` 目录（点前缀只用于和用户文件区分，并不隐藏）。

## 六、并发上限与淘汰

- **上限 4**（`DEFAULT_MAX_CONCURRENT_TASKS`），超限时**拒绝**而不是排队：排队会让模型面对
  「为什么还没开始跑」的不可观测状态；拒绝的错误文案里直接给出恢复路径
  （`Inspect them with workspace_task_status, then free a slot with workspace_kill_task.`）。
- **启动失败不占名额**（否则连续几次失败就把工作空间锁死）。
- 已完成任务保留最多 **20** 条，**在任何任务结束的瞬间**淘汰最旧的已完成任务（只在 `start()` 里淘汰
  会有一段时间超限）。运行中的任务**永不淘汰**。
- **时长上限 30 分钟**（`DEFAULT_MAX_TASK_LIFETIME_MS`）：并发上限只管数量、管不住时长——模型起 4 个
  长命令后本轮结束，手机就会一直跑下去。到点由看门狗按**同一条终止路径**（`ProcessSignals` 杀整棵树）
  终止，状态记 `TIMED_OUT`，工具结果里同时给出 `lifetimeLimitMs` 与运行中的 `expiresInMs`，让模型自己
  决定「继续等」还是「先干别的」。`<= 0` 表示不限制（仅测试/特殊配置）。
  选择常量而非设置项，与 LoopGuard 阈值同款取舍（先证明需要再开放配置）。

## 七、与其它护栏/预算的交互

- **LoopGuard 轮询豁免**：`workspace_task_status` 加入 `POLLING_EXEMPT_TOOLS`。这是**必要**的——
  长任务轮询同一 `taskId` 是唯一正确用法，不豁免的话第 3 次轮询就会撞上「相同调用重复 3 次」判停，
  等于把整个功能废掉。相对地，`workspace_run_background` **不豁免**：反复启动同一条后台命令仍是
  要拦的失控形态。
- **轮询结果计入当轮预算**（`WorkspaceTurnBudget`）：后台任务的输出不进上下文，但**查询结果**进；
  这条路径不需要特殊处理，额度耗尽的行为与其它工具一致。
- **审批默认值三档**（`WorkspaceToolDefaultApprovals`）：
  | 工具 | 默认 | 理由 |
  |---|---|---|
  | `workspace_run_background` | **需审批** | 持续占用设备资源，且模型看不到输出——人需要先知道它在跑什么 |
  | `workspace_kill_task` | **需审批** | 破坏性操作，且模型可能误杀别的任务 |
  | `workspace_task_status` | **免审批** | 纯只读；否则长任务轮询会变成审批轰炸 |

  工作空间级 `tool_approvals` 覆盖仍然优先（用户可以关掉后台运行的审批，也可以给状态查询开审批）。

## 七之一、终止必须自己发信号（真机实测结论，2026-09-26 修订）

**最初实现用 `Process.destroyForcibly()`，在真机上完全无效**——后台任务卡在 `RUNNING`、`waitFor()`
永不返回。真机（小米 8 / Android 14）实测证据：

| 场景 | 结果 |
|---|---|
| `Process.destroyForcibly()`（proot 子进程） | kill 后 20s，`ps` 里 proot/bash/sleep **全都还在** |
| `Process.destroy()`（SIGTERM，同样目标） | 20s 后仍在 |
| `Os.kill(pid, SIGKILL)`（同形状目标、自己发信号） | **立刻死亡** ✓ |
| `pkill -9 -f <标记>`（toybox） | 有效（`pkill -f` 按 `/proc/<pid>/cmdline` 匹配） |
| 只杀直接子进程（不杀子孙） | 子进程变孤儿（PPID=1）**继续跑**，`sleep` 实测存活 |

因此终止路径改为（`workspace/ProcessSignals.kt`）：

1. 每条命令启动时带一个**唯一标记** `rikkahub-task-<id>`（进 argv 的 `$0` 槽位，也用于同步 shell）；
2. 终止时在 `/proc` 里**只在自己的后代子树内**按标记找回进程树根（防误杀同 uid 的无关进程）；
3. 读 `/proc/<pid>/stat` 的父子关系，**自底向上** `Os.kill(pid, SIGKILL)`——顺序不能反，先杀父进程
   会让子孙变孤儿、之后再也定位不到；
4. 同时**立即**把任务状态置为 `KILLED`（信号已下发即算终态），回收线程随后只补 `exitCode`——
   这样即便平台在回收上磨蹭，调用方也不会看到「永远 RUNNING」。

Android 的 `java.lang.Process` **没有公开 pid**（`Process.pid()` 不在 SDK API 里，编译期不可用），
所以「标记 → `/proc` 反查 pid」不是绕远路，而是唯一可行解。

**顺带修掉一个既有缺陷**：同步 `workspace_shell` 的超时/取消路径原本也是 `destroyForcibly()`，
即「工具报超时、命令继续在设备上跑」。现在同一条终止路径（标记 + 杀树）也被 `readResult` 使用。

## 八、诚实声明的边界（写清以免误用）

1. **任务表是进程内内存态**。应用进程被杀（用户划掉、系统回收）后：任务表消失，工具再也查不到
   那些任务；而 proot 子进程**不一定随之退出**（它可能以孤儿进程继续跑）。也就是说「查不到」不等于
   「已停止」。彻底解决需要常驻服务或前台服务托管，属后续工作。
2. **应用处于后台时采集线程可能被冻结**（Doze/后台限制），此时输出落后于实际进度，但进程本身
   仍在推进。这是「按需轮询」模型的可接受代价。
3. **rootfs 未安装时**：三件套仍会注册，但 `run_background` 会得到 `status=FAILED` 且 `error` 里
   写明 `Rootfs is not installed`——比抛异常更可诊断。

## 九、测试策略

| 覆盖点 | 方式 |
|---|---|
| `TailBuffer` 尾部语义/累计计数/单批超容/非法容量 | 纯逻辑单测 |
| `pumpText` 编码与「流中途抛 IOException 保留已读」 | 真实 `InputStream`（含自定义抛异常流） |
| 启动→查询→退出码、kill→KILLED、已完成/未知 id 的 kill 结果 | `FakeLauncher` + `FakeProcess`（`CountDownLatch` 控制退出时机） |
| 并发上限拒绝与「kill 后释放名额」 | 同上（`maxConcurrent = 2` 收紧） |
| 启动失败（返回 null / 抛异常）→ FAILED 且不占名额 | 假启动器注入失败 |
| 淘汰策略（运行中不淘汰、最旧已完成先淘汰） | `maxFinished = 2` |
| 按工作空间隔离（list / killAll） | 多个 root |
| 审批默认值三档 + 覆盖优先级 + 未知工具 | `WorkspaceBackgroundToolApprovalTest` |
| 轮询豁免 / 后台启动不豁免 | `ToolCallLoopGuardTest` |

## 十、后续（未做）

1. **可见性**：工作空间详情页或终端页展示运行中任务（含 kill 按钮、剩余时长），目前只有工具卡片。
2. **应用退出时清理**：至少把「已知任务」在 `onTrimMemory`/进程可预测退出点终止；孤儿进程的根治
   仍需常驻服务（`Application.onTerminate()` 在真机不会被调用，所以这一项只能「尽力而为」）。
3. **宿主机 shell 变体**（无 rootfs 场景）。

### 明确不做（并已在工具描述里写明边界）

- **后台任务不给 stdin**：交互命令（`apt install` 无 `-y`、`gh auth login`）会读到 EOF 直接失败。
  需要交互就用同步 `workspace_shell` 或终端页；后台做伪终端等于把终端页复刻一遍，收益不足。
  三个工具的描述里已显式写明这一点，避免模型走弯路。
