# 设计稿：隐形会话 + AI 管理任务（含「真无头」不做声明）

> 状态：**rev 2——P1/P2 主体已实现（2026-10-31，本地未提交/未推送）**：
> `Conversation.hidden`（v55 合批，六处同步 + 查询面过滤 14 处 + web DTO）、
> 隐形横幅（ChatPage 覆盖层，v1 UI 只露「移出隐藏」，普通会话无隐藏入口）、
> `createTaskConversation` 默认 `hidden = true`、
> 任务表单「交付物」字段、`created_by` / `source_conversation_id`（v56）、
> `task_manage` 单工具五 action（越权红线纯函数 `taskManageVerdict`）、
> 助手页「允许 AI 管理定时任务」开关组（能力 + 三档审批）、管理页「AI 建」徽标、动作通知。
> 单测 `TaskManageScopeTest` 全绿；真机安装/迁移/启动通过。
> **与 rev 1 的一处偏差**：§二.3「会话菜单开关」落成**会话内横幅**——聊天页没有既有的
> 会话下拉菜单可挂，横幅同时承担「解释这是任务工作台」与「移出隐藏」两个职责；
> 「再移入隐藏」v1 不露入口（数据层支持，将来需要时挂会话列表菜单即可）。另立设计，不挂在
> `design-scheduled-ai-tasks.md` 名下；该稿 §十 P3 只留指针。
> 来源：设计稿 §十 P3 尾单（隐形会话「四两拨千斤」）、Yuihub 形态
> （`docs/references/other projects/yuihub.md` 四.5：AI 可在对话中管理本助手的定时任务）、
> purrcat A4 的「主动 agent」分界。
>
> **一句话**：任务跑完的东西放哪（隐形会话）+ 谁来创建任务（AI 工具化管理），
> 两者耦合在「AI 建的任务默认隐藏」上；真无头明确不做。

## 〇、决议记录（2026-10-31 会话拍板）

| 分叉 | 决议 |
|---|---|
| 收养语义 | **手动切换**：默认隐藏，会话内提供「移出/移入隐藏」开关，不自动收养 |
| 审批分档 | **能力 + 动作双层开关**：per-assistant 总开关；delete/modify **默认开审批**；每次 AI 动作发通知 |
| 排序 | 隐形会话先行（P1），AI 管理任务踩其上（P2） |
| 真无头 | **明确不做**（§五），只留缝 |

## 一、为什么这两件事绑在一篇

Yuihub 形态让 AI 批量建任务（助手 cron agent）。若 AI 建的 DEDICATED/NEW_EACH_RUN
任务是普通可见会话，每建一个就往会话列表刷一条——刷屏问题在源头消失的唯一办法是
**AI 建的任务默认走隐形会话**。反过来，隐形会话没有「谁创建」的产量，价值也兑现不了。
所以：P1 隐形会话打地基，P2 AI 管理任务收割。

## 二、P1：隐形会话

### 2.1 数据模型（DB v55）

`ConversationEntity` / `Conversation` 加 `hidden`（`INTEGER NOT NULL DEFAULT 0` /
`Boolean = false`）：

- 迁移：`ALTER TABLE conversationentity ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0`，
  AutoMigration 无需 spec（对齐 `memory_disabled` 先例，`ConversationDao.kt:173` 有
  专用 `UPDATE ... SET memory_disabled` 语句可参照写 `setHidden`）；
- **六处同步坑**（[[conversation-column-storage-pitfall]]）：模型、实体、mapper、
  `DAO.updateMetadata` 的 SQL、web DTO、（如有）导出序列化——逐处核对，漏一处即静默丢列；
- 语义边界：`hidden` 是**会话的表现层属性**，与任务表零耦合——定时任务的执行语义
  （认领/账本/通知）全部挂在任务表上，会话只当工作台。这是给 §五「真无头留缝」的
  具体形态。

### 2.2 查询面审计（本议题工作量大头）

`ConversationDao` 的查询面（2026-10-31 盘点）：

| 查询面 | 位置 | v1 处置 |
|---|---|---|
| 会话列表（全量/按助手/按文件夹） | `ConversationDao.kt:58-77` | **过滤 hidden** |
| 最近会话（LIMIT） | `:80` | 过滤 |
| 标题搜索（全库/按助手，两套列） | `:83-92` | 过滤（隐藏是工作台，不是检索对象） |
| 标题清单 | `:68` | 过滤 |
| 目标选择器（定时任务 EXISTING 选会话，按助手分组） | `groupConversationsByAssistant` | 过滤 |
| 使用统计 / token 统计 | statistics 域 | 过滤 |
| web 模块会话 DTO | web 模块 | 过滤 |
| 运行历史详情页「查看最近一次会话」 | 定时任务详情 | **不过滤**（直达入口就是要看它） |
| 完成通知点进会话 | 通知 deep-link | **不过滤** |
| 备份 | 整库文件级（§八.5 主设计稿） | 天然覆盖，无增补 |

审计方式：沿用 `CoverageTest` 惯例——能枚举的调用方逐一改 + 守卫测试钉「新查询不
得漏 hidden 条件」做不到自动化，以**清单复查 + 真机验收**兜底（对齐主设计稿 §九的
诚实分层）。

### 2.3 UI 语义（手动切换，rev 决议）

- 隐藏会话**不出现在任何列表**，只能从两个入口直达：任务详情页「查看运行会话 /
  运行历史」、完成通知；
- 会话菜单提供**「移出隐藏 / 移入隐藏」**开关：移出 = 收养，此后与普通会话无异
  （任务继续往里写，用户介入是正常语义）；用户也可对已收养的会话再移入；
- v1 **不**给任意普通会话提供「移入隐藏」入口（隐藏是任务会话的属性，不是通用的
  归档功能——通用归档走既有 folder/pin，不混用）；数据模型通用，将来要放开只是 UI 事；
- `NEW_EACH_RUN` 自动清理（§4.2 主设计稿）对隐藏会话**原样生效**：两条保护
  （未收尾账本行不删、用户接手不删）不变。

## 三、P2：AI 管理任务（Yuihub 形态）

### 3.1 范围门控（最重要的安全面）

- 助手**只能操作挂在自己名下**的任务（`ScheduledTaskEntity.assistantId` = 当前助手），
  与记忆库按助手隔离同一哲学；
- 目标模式只允许 **DEDICATED / NEW_EACH_RUN**，且会话**默认隐藏**（§二）；
  **EXISTING 不开放**——AI 无权把定时注入指到任意既有会话；
- 用户建的同助手任务：AI 可 `list` / `enable` / `disable`，**不可 delete / 修改**
  （AI 删用户的东西不靠审批兜底，直接不允许——审批是给人犹豫用的，不是给越权用的）。

### 3.2 开关与审批（决议：能力 + 动作双层 + 通知）

`Assistant` 增加字段（Settings JSON，无迁移）：

```kotlin
val taskManageEnabled: Boolean = false,   // 能力层：工具面是否挂载，默认关
val taskCreateApproval: Boolean = true,   // 动作层：create 默认审批（首次授权持续烧钱）
val taskDeleteApproval: Boolean = true,   // delete 默认审批
val taskModifyApproval: Boolean = true,   // 修改（enable/disable 也算）默认审批
```

- 复用**既有工具审批通道**，零新机制：会话内走工具调用审批 UI；无头场景
  （定时任务会话里的 AI 再建任务）走既有通知审批（`SubagentApprovalReceiver` 模式）；
- **通知**：每次 AI 动作（无论是否经审批）发一条轻量通知（对齐任务完成通知通道），
  内容 = 动作 + 任务名；经审批的由审批通知承担，未审批的直接执行后通知——
  「AI 在排我的任务」这件事必须随时可见。

### 3.3 工具面

`task_list / task_create / task_enable / task_disable / task_delete`（可选 `task_runs`
查账本，P2.5）：

- 参数直接映射任务表单字段；校验**复用表单同一套**（标题长度、prompt 非空、周期解析
  走 `ScheduleMath` 同源函数），不另写一套宽松版；
- 枚举（`ScheduleType` / `TargetMode` / `MissPolicy`）走**存库名稳定**约定，
  tool schema 里只暴露稳定名（[[lorebook-import-enum-case]] 的教训前置规避）；
- `task_create` 建出的任务带 `created_by = 'ai'` + 源会话 id（3.4）；
- `task_list` 只回本助手任务，输出含 `nextRunAt / lastStatus / createdBy`——
  模型排巡检前先看存量，避免重复建。

### 3.4 可审计性（DB v56）

`scheduled_task_entity` 加两列：`created_by TEXT NOT NULL DEFAULT 'user'`（'user'/'ai'）
+ `source_conversation_id TEXT NULL`（AI 建任务时的会话溯源）：

- 管理页任务行显示来源徽标（「AI 建」）——用户必须能一眼分出「这条不是我建的」；
- 老数据默认 `'user'`，语义无损。

## 四、分期

- **P1（DB v55）**：`Conversation.hidden` 列 + 六处同步 + 查询面审计（§2.2 清单）+
  UI 手动切换 + 任务详情/通知直达；与 `design-deliverable-gate.md` 的
  `deliverables` / `gate_retries` 列**合同一次迁移**（v55 一批，避免 55/56/57 连跳）。
- **P2（DB v56）**：`created_by` / `source_conversation_id` + 工具面 + 双层开关 +
  通知 + 管理页来源徽标。
- **P2.5（按需）**：`task_runs` 账本查询工具。
- **真无头**：不做（§五）。

## 五、真无头：明确不做（留缝声明）

**结论：不立项。** 隐形会话已覆盖 ~90% 的「无会话全局任务」用户面需求；真无头
（解耦 ChatService 集中式装配方：session 生命周期、transformer 管线、awaitLoaded、
完成回调，让生成脱离「会话」概念运行）动摇所有生成入口，返工风险大、边际收益低。

**留缝**（本稿落地时必须守住的三条纪律，将来甩壳时执行层零改动）：

1. 定时任务的执行语义（认领、账本、通知、重排）**只挂在任务表**，不读会话的任何
   属性做业务判断；
2. `hidden` 只进查询面过滤，不进任何执行路径判断（守卫：`ScheduledTaskRunner` /
   `Executor` 的源码扫描不得出现 hidden 引用）;
3. 闸门配置随任务走（`deliverables` 在任务行上），不随会话走。

触发重议的条件（记下免得反复）：某天出现「一轮任务要并发跑 N 个独立生成且互相
不可见」或「生成要脱离 Android 会话模型被服务端复用」的真实需求时，再开设计稿。

## 六、测试策略

| 覆盖点 | 方式 |
|---|---|
| hidden 列六处同步（模型↔实体↔mapper↔updateMetadata↔DTO） | 逐处源码核对 + 真机存取回归 |
| 列表/搜索/统计/选择器/web DTO 过滤 hidden；任务详情与通知**不过滤** | 调用方逐点真机验收（清单即 §2.2 表） |
| AI 工具越权：操作他助手任务 / 对用户任务 delete / EXISTING 目标 → 一律拒绝 | 纯函数单测（scope 解析） |
| 双层开关：能力关 → 工具不挂载；动作审批默认值（create/delete/modify = true） | 单测 + ChatToolFactory 装配守卫 |
| 枚举稳定名往返 | 序列化回归 |
| created_by 老数据默认 'user' | 迁移后抽查 |

## 七、诚实边界

1. **隐藏不是加密**：隐藏会话照常占库、照常进备份、照常烧 token；隐藏只是列表噪音
   治理，不是隐私边界。
2. **AI 建任务 = 无人监督的定时烧钱**：兜底是三层——create 默认审批、每次动作通知、
   管理页来源徽标可一键停用；不做 AI 建任务的自动配额限制（v1 观察期，`gate_retries`
   与账本已够暴露问题）。
3. **收养后的回写污染**：用户移出隐藏后在任务会话里聊了私人内容，下轮任务照常注入
   ——与 EXISTING 模式「用户接手」语义一致（keep_last 的 `lastMessageAt > finishedAt`
   保护同款），不是新问题，但用户未必预期，通知文案里带一句提醒。
4. **Yuihub 的完成悬浮通知不照搬**：本仓已有完成通知 + 运行账本二级页，信息密度更高，
   悬浮通知属重复建设。
