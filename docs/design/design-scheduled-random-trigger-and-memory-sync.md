# 定时任务随机触发 + 主动消息记忆同步（design-scheduled-random-trigger-and-memory-sync）

> 状态：**rev 1——草稿，未实现，三个分叉点待拍板（见 §〇）**。
> 挂靠 `design-scheduled-ai-tasks.md`（已发布的定时任务特性线，beta.13 / DB v56）的增量稿，
> 与 `design-proactive-task-management.md`（隐形会话 + AI 管理任务）并列；不改动那条线的
> 已定案语义，只在其上叠两层能力：
> **① 调度随机化**——「每天在某个时间窗内随机找我 N 次 / 间隔加抖动」，即用户口中的
> 「宏随机触发的主动消息」；**② 记忆同步**——主动消息（尤其 NEW_EACH_RUN 独立会话）产生的
> 内容能沉淀回助手级记忆，而不是随自动清理蒸发。
>
> **一句话**：时间层加「确定性抽签」的随机槽位，内容层把「主动消息 → 助手记忆」这条
> 已存在的暗管道点亮（提示词引导 + 审批策略）；摘要回写主会话列为二期、暂不做。
>
> 来源：2026-10-31 会话的需求评估结论——**无现成项目可用**（上游 rikkahub 无定时任务特性、
> RikkaHub 无插件生态；最接近的 ST 生态 JS-Slash-Runner + st-memory-enhancement 是浏览器
> 扩展架构，代码不可移植），需在本仓自研，但基建已备、属小特性（估 2~4 天）。

## 〇、决议记录（待拍板）

| # | 分叉 | 选项 | 本稿倾向 |
|---|---|---|---|
| 1 | 随机语义 | A. 每日窗口内 N 个随机槽；B. INTERVAL 加抖动；A+B 都做 | **v1 只做 A**（「角色主动来找我」的主诉求），B 留尾单 |
| 2 | 后台记忆写入审批 | a. 沿用通知审批（现状即通）；b. per-task 开关允许自动放行写审批 | **v1 只做 a**（零开发），b 观察后再说 |
| 3 | 摘要回写主会话（B3） | 做 / 不做 / 二期 | **二期**——会话树写入是重活，先验证 B1 是否够用 |

## 一、要解决的问题

两个真机可复现的断点：

1. **定时必准点、主动必刻板**。现有 `ScheduleType` 五种（ONCE/DAILY/WEEKLY/MONTHLY/INTERVAL，
   `ScheduleSpec.kt`）全是确定性槽位；`{{random}}/{{roll}}` 宏（`TavernMacroTransformer.kt`）
   只作用于**消息内容**，影响不了**触发时刻**。想「让角色随机时间来打招呼」做不到。
2. **主动消息的记忆会蒸发**。`ScheduledTaskRunner` 走 `chatService.sendMessage` 标准管线
   （Runner:143），记忆注入与 memory_tool 本就随助手配置挂接——**管道存在，但没人提示模型用**：
   `buildTaskPrompt`（Runner:402）只拼长期目标，不引导写记忆。而 NEW_EACH_RUN + 自动清理
   （Runner:186，`keepLastConversations`）会在成功轮后删会话——模型没主动落记忆的内容
   **随会话一起消失**，用户视角即「主动消息没有记忆」。

## 二、目标与非目标

**目标**：G1 每日时间窗内随机槽位调度；G2 任务提示词默认携带记忆引导段；G3 明确后台
记忆写入的审批语义并写进文档；G4（二期）成功轮摘要回写主会话。

**非目标**：
- 不做 SillyTavern 记忆表格（st-memory-enhancement）数据格式导入——格式按楼层存储于
  ST chat 元数据，与 Rikka 的助手级记忆模型不同构；有 lorebook 导入（`ExportSerializer.kt`
  SillyTavern 分支）在前，若将来要做是独立稿。
- 不做「宏控制触发时间」——宏保持在内容层，时间随机是调度参数，不引入 ST 宏语义。
- 不做真无头审批（沿用 `design-scheduled-ai-tasks.md` §六「真无头明确不做」的既有边界）。

## 三、G1：随机触发调度

### 3.1 语义：每日窗口 + N 个随机槽（选项 A）

「每天 09:00–23:00 之间随机找我 3 次」。建模为 DAILY 的随机变体，而不是新 ScheduleType：
payload 加 `randomWindow: RandomWindow?`（null = 老行为，向后兼容）：

```kotlin
@Serializable
data class RandomWindow(
    val startMinute: Int,   // 窗口起点（当天分钟数，0..1439）
    val endMinute: Int,     // 窗口终点（> start，跨零点不做——诚实边界见 §五）
    val slotsPerDay: Int,   // 每天抽几个槽（1..20，去重防御）
)
```

`RandomWindow != null` 时 `hour/minute/extraMinutesOfDay` 忽略（与现有「按类型只读对应字段」
的约定一致）。选项 B（INTERVAL 加 `jitterMinutes`，next = current + interval ± 均匀抖动）
结构上只是 `ScheduleMath.advance` 的另一个小分支，留作尾单。

### 3.2 核心难点：抽签必须可重算——确定性种子

`ScheduleMath.advance/firstRunAt` 是**纯函数**，且认领时才推进 `next_run_at`；随机槽位
不能「抽了存起来」再推进（那要加每任务状态列）。解法：**种子化伪随机**——

```
RNG = Random(seed = taskId.hashCode() * 31 + LocalDate.toEpochDay())
```

同一天同任务永远抽出同一组槽位（升序去重，槽间最小间隔防御可后加）：
- `advance` 内纯函数按 `(taskId, 当天日期)` 重算当日剩余槽位、明天全量槽位；
- 签名加一个 `seed: Int` 参数（调用方传 taskId hash），纯度不破，单测可断言
  「同种子同序列」；
- `nextRunAt` 仍是唯一事实源，AlarmScheduler / Sweeper / 认领乐观锁**零改动**。

代价：不是密码学随机（无所谓）+ 换 taskId 会换序列（无所谓）。

### 3.3 触发层与错过语义

- 抽出的槽位落到 `nextRunAt` 后，三层触发（AlarmManager 主路径 + per-task WorkManager +
  15 分钟 sweep）原样工作，随机槽与固定槽无差别。
- `decideMiss` 宽限窗（6h）照用——随机槽被 Doze 推迟到宽限内照跑，超窗走 per-task
  MissPolicy，语义不新增分支。
- ColorOS `OplusAppStartupManager` 丢广播的既有边界（scheduled 稿 §6.2.1）原样继承。

### 3.4 UI

任务表单周期选项加「每天随机（窗口 + 次数）」：两个时刻选择器（窗口起止）+ 次数 stepper。
展示槽位本身没有意义（明天才抽），列表页「下次运行」照显示当前 `nextRunAt`，
副标题标「随机」。

## 四、G2/G3：记忆同步

### 4.1 B0（现状，零开发）：管道已通，先写进文档

助手 `enableMemory` 开启时，后台任务轮次**已经**具备：记忆分层注入（`ChatService.kt:2797
currentMemoriesFor` → `GenerationHandler`）与 memory_tool 读写（`ChatToolFactory.kt:187`），
写入落同一条助手级记忆库。这一条要补的只是**文档与用户认知**：在定时任务设置页加一句
「记忆跟随助手设置」。

### 4.2 B1：任务提示词加记忆引导段（G2）

`buildTaskPrompt` 追加固定段（仅当助手 `enableMemory` 为真时拼入，避免给关记忆的助手
递一个不存在的工具）：

> 本次是你在后台主动发起的消息（无人先说话）。若本轮出现了值得长期记住的信息（用户近况、
> 明确偏好、待办事务），请用 memory_tool 落记忆；不要为寒暄强行写。

放在任务 prompt **之前**（与「长期目标」段同级），单测锁住拼接顺序与 enableMemory 条件。
这是「自动清理模式下记忆是唯一载体」断点的最小修复，估半天。

### 4.3 B2：后台记忆写入的审批语义（G3）

现状核对：工具审批走**通知栏批准/拒绝**（`SubagentApprovalReceiver` 通道，scheduled 稿 §六
无头审批路径）——记忆写审批在后台不是「卡死」，是「弹通知等批，错过按既有超时语义收尾」。
因此 **v1 不加新机制**（决议 2 选 a）：文档写明「后台记忆写入会弹通知审批」；
per-task 自动放行（选 b）留观察单——若真机验证发现通知审批打断体验再立项，
届时可用 Assistant 既有三档审批字段（create/delete/modify）对齐，不新造开关体系。

### 4.4 B3（二期，暂不做）：成功轮摘要回写主会话

形态：OK 轮结束后，向任务目标主会话插入一条带 `source=task` 标记的节点（摘要由本轮
assistant 回复截取或二次生成，与自动清理共享「跳过有新消息会话」判定）。
**不做的理由**：会话树写入路径重（Conversation 逐列存储六处同步的坑）、与隐形会话
（`design-proactive-task-management.md`）的目标分配有耦合，先让 B1 跑两周看够不够。

## 五、诚实声明的边界

- **伪随机**：种子化抽签序列固定，同一天重装/迁移任务（taskId 变）槽位会变——功能语义
  是「不可预测的问候」，不是安全随机，接受。
- **窗口不跨零点**（`startMinute < endMinute` 强约束）——跨零点窗的语义分叉（属于今天还是
  明天）不值得在 v1 处理，表单直接校验拦住。
- **槽间最小间隔**不做（v1）——slotsPerDay 大 + 窗口小时可能两槽相邻几分钟；表单提示
  「窗口时长建议 ≥ 次数 × 30 分钟」，先观察。
- **WorkManager 兜底粒度**：sweep 15 分钟一轮，Doze 下随机槽仍可能被推迟——与固定槽
  完全同边界，随机不改善也不恶化准点性。
- **记忆引导不保证写入**：模型可以不调 memory_tool（提示词非闸门）；自动清理开着的任务
  若模型不写记忆，内容仍会丢——这是提示词方案的诚实上限，B3 是兜底方向的二期。

## 六、测试策略

- `ScheduleMathTest` 扩展：随机分支的**同种子稳定性**（同 (seed, date) 两次调用同序列）、
  窗口边界（槽位 ∈ [start, end]）、跨日推进（当天剩余槽 → 明天新抽）、
  `RandomWindow` 与固定时刻字段互斥的解码兼容（老 JSON 无字段行为不变）。
- `buildTaskPrompt` 守卫测试：enableMemory 开/关、withGoal 组合下的拼接顺序。
- 纯函数为主（同 scheduled 线惯例：无 Robolectric，Fake DAO + 源码守卫）。

## 七、分期

| 期 | 内容 | 估时 |
|---|---|---|
| P1 | B0 文档 + B1 提示词引导 + B2 语义写明（决议 2 选 a） | 0.5~1 天 |
| P1.5 | G1 随机窗口调度（payload + ScheduleMath + UI + 单测） | 1~2 天 |
| P2 | B3 摘要回写主会话（独立小稿）、选项 B 间隔抖动 | 视 P1 观察结果 |

## 关联

- 上位特性线：`design-scheduled-ai-tasks.md`（调度器/状态机/触发层/无头审批的既定语义）
- 兄弟稿：`design-proactive-task-management.md`（隐形会话——B3 回写目标需与其对齐）
- 需求评估来源：2026-10-31 会话（上游无此特性、ST 生态不可移植的调研结论）
