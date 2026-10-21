# 记忆的三种类型：画像 / 话题 / 事务（设计稿）

> 状态：**A1 / A2 与原版导入映射已实现并分批本地提交，未推送**（2026-10-21）；B 未实现。
> 本稿保留设计与验收口径；实际落地与验证边界见 §14 / §15，实施顺序见 §9。
>
> 相关文档：分层注入 [`design-memory-injection-tiering.md`](design-memory-injection-tiering.md)、
> 打分 [`design-memory-scoring.md`](design-memory-scoring.md)、
> 写入纪律与渐进披露 [`design-memory-discipline.md`](design-memory-discipline.md)、
> 做梦整理 [`design-memory-consolidation.md`](design-memory-consolidation.md)、
> 检索/提案 [`design-memory-retrieval-and-consolidation.md`](design-memory-retrieval-and-consolidation.md)。
>
> **数据库当前为 47 版**（`app/src/main/java/app/androidrik/rikkahub/data/db/AppDatabase.kt`）。
> 本仓旧文档里写的"DB 42/44"都是历史值（2026-10-21 本地已落地翻译历史 44→45 与上游会话快照 45→46）；
> 本次 A1 已实现 **46 → 47** 手写迁移。

## 0. 决策记录

### 0.1 起点

用户提出：`profile、话题、事务——即使不是 Claude 式文件记忆，这种分类也是可以借鉴的——讨论记忆功能改进`。

### 0.2 用户拍板（2026-10-21）

1. **作用域：按助手。** 沿用现有 per-assistant 记忆库；`useGlobalMemory = true` 的助手仍用
   `__global__` 共享库，三类在哪个库里就属于哪个库。
2. **AI 能写画像候选，不能直接进 system。** 进 system 仍只由用户「固化」（`isAnchor`），
   保持"AI 不得自填锚点"的既有不变量。
3. **分类来源：模型写入时给 + 用户可改 + 整理器纠正**，缺省/无法判定时一律落到**话题**。
4. **事务的到期提醒/通知留待后续**，本稿不做通知、不做 WorkManager 调度。

### 0.3 一句话结论

不借 Claude 的**文件形态**（文件记忆解决不了的原子性、并发、预算、遥测、回收站，本仓已经有了），
只借"按用途分三类"这个**维度**：把它作为记录上的第二维度加在现有单表上，用来把三类的
**生命周期、注入位置、整理规则**分开。存储层与四层注入框架都保留，只扩不换。

## 1. 问题：单池把三种生命周期压成一种

### 1.1 现状（事实核对）

| 机制 | 现状 | 出处 |
|---|---|---|
| 记录 | 单表 `memoryentity`：`content` / `description`(≤150) / `importance` / `updated_at` / 读取遥测 / `is_anchor` / `revision` / `public_id` | `data/db/entity/MemoryEntity.kt` |
| 作用域 | 每助手一份；`__global__` 为共享库 | `MemoryRepository.GLOBAL_MEMORY_ID` |
| 打分 | `有效重要度 × (0.55 + 0.45 × 活跃度)`；活跃度 = `max(写入衰减, 读取衰减)`，半衰期 30 天；读取信用对数饱和（8 次封顶） | `GenerationPrompts.kt` 的 `memoryScore` / `memoryReadCredit` |
| 入选下限 | 低于 `MIN_MEMORY_INJECTION_SCORE = 0.2` 不进全文层 | 同上 |
| 注入 | L0 纪律（system）→ L1 锚点（system，最多 5 条，按会话冻结 id 集合）→ L2 全文（尾部，剩余可变预算 75%）→ L3 清单（尾部，吃光剩余） | `MemoryInjectionPlan.kt`、`design-memory-injection-tiering.md` |
| 容量 | 每库上限 200 条；超限按**分数**淘汰、进回收站（锚点也在淘汰范围内） | `MemoryRepository.enforceMaxCount` |
| 写入 | `memory_tool`：`create` / `edit` / `delete` / `read` / `search`；CAS revision；提案模式；内容守卫 | `data/ai/tools/MemoryTools.kt` |
| 整理 | 做梦 agent 读库快照 + 新轮次，按"补记 / 合并 / 修正 / 校准 / 退役"五类动作产出建议，审批后应用 | `MemoryConsolidationPrompts.kt` |

关键缺口：**没有任何"类型"维度**。一条身份事实、一条项目知识、一条三天前的待办，
共用同一套打分、同一条注入通道、同一套整理规则。

### 1.2 三个失败模式（可复算）

**① 事务不会完成，也不会过期；还会挤掉画像。**

- 0.6 重要度的记录即使完全衰减，分数也还有 `0.6 × 0.55 = 0.33`，**永远高于注入下限 0.2**
  ⇒ 这类记录不会被下限淘汰，只会在预算竞争里让位。
- 一条**三天前**的待办（`0.6`，无读取）：活跃度 `0.5^(3/30) = 0.933` ⇒
  `0.6 × (0.55 + 0.45 × 0.933) = 0.582`。
- 一条**半年前**的身份事实（`0.9`，没人读过）：活跃度 `0.5^(180/30) = 0.0156` ⇒
  `0.9 × (0.55 + 0.45 × 0.0156) = 0.501`。
  ⇒ **待办压过画像**。而模型做完事之后只能 `edit`/`delete`，没有"完成"这个状态；
  整理器的规则还写着"永不因为老而删"（`MemoryConsolidationPrompts.kt` 的 Retire 段），
  于是过期待办会一直留在库里。

**② 画像没有权威位。**

- L1 只收用户**手动固化**的锚点（≤5），而锚点解决的是"缓存稳定"，不是"这是谁"。
- 没固化的身份事实与普通笔记同池竞争。真机实测（50 条库，助手 `0950e2dc`）：
  尾部块 8107 token 吃满 8000 预算，**L2 只装下 7 条、L3 20 条、23 条完全不出现**。
  画像事实只要不在 L2，模型就只能在清单里看到一行描述。

**③ 话题没有骨架。**

- L3 清单行只有 `id ｜ 描述 ｜ 更新日`，没有类型/主题信息；
- 整理器只能按"相似 / 被覆盖"合并，不能按主题聚合；话题多了以后，
  既检索不到（关键词相关性覆盖不了的表达），也整理不动（不知道哪几条属于同一主题）。

### 1.3 三类的真正差异是三个维度

| 维度 | 画像 | 话题 | 事务 |
|---|---|---|---|
| **变化率** | 极低（身份/长期设定） | 中（随对话更新） | 高（创建→完成→关闭） |
| **权威性** | 需要 system 级断言 | 普通上下文 | 行动指令 |
| **生命周期** | 不过期，靠矛盾修正 | 按相关性/重要度衰减 | 按完成状态关闭，不按分数 |
| 当前机制能否表达 | 只能靠用户手动固化 | 可以（但只是默认类） | **不能**：没有状态、没有完成动作 |

所以分类不是 UI 上的标签，而是**让同一条记录按类型走不同的生命周期与注入通道**。

## 2. 概念与边界

### 2.1 三类定义

| kind | 定义 | 典型内容 | 注入位置 | 整理规则 |
|---|---|---|---|---|
| `profile` 画像 | 长期稳定、跨会话仍成立的"用户是谁 / 长期设定"，用户亲口陈述 | 名字、语言、时区、职业、关系、沟通偏好、硬规则 | 候选在 L2；**用户固化后**进 L1 system | 矛盾修正、合并、建议固化；永不因"老"删除 |
| `topic` 话题 | 知识、项目、决定、领域偏好——描述"是什么 / 发生过什么" | "TikZ 渲染方案"、"Flutter 学习路径"、"用户选了这个库" | L2 全文 / L3 清单（现状不变） | 合并重复、覆盖修正 |
| `task` 事务 | 有完成判据或时间承诺的"要做的事" | "周五前交作业"、"下周去买 X"、"记得回复 Y" | **专用「Open tasks」块**（尾部，固定预留预算） | 完成、过期、清理；永不把 open 事务当"老记录"删掉 |

### 2.2 判定句与反例

**判据：能不能说出"它什么时候算完成"。**

- 能 ⇒ 事务；不能 ⇒ 话题或画像。
- "在学 Flutter" = 话题（状态/知识）；"周五前交 Flutter 作业" = 事务（有完成判据）。
- "用户是素食者" = 画像（身份）；"用户上周说想学 Flutter" = 话题（发生过的对话内容）。
- "用户要求回答用中文" = 画像（长期设定）；"这次回答用中文" = 话题（一次性语境）。
- 反例（**不记**，沿用现有工具纪律）：模型自己的推断、搜索结果、待确认的下一步。

边界冲突的裁决：**同一件事同时像两类时，按"用户能否自己判断它完成"选事务**；
事务完成后的结论若要长期保留，由整理器把它沉淀成话题（A2）。

### 2.3 与 isAnchor 的关系：正交

- `isAnchor` 是**进 system 的机制**（用户点「固化」；按会话冻结 id 集合，保前缀缓存）。
- `kind` 是**语义分类**（决定打分/淘汰/注入通道/整理规则）。
- **`kind = profile` 不是进 system 的通行证。** 模型写的画像只在尾部作为候选出现，
  `isAnchor` 仍然只能由用户（或未来的整理器提拔）设置；`addMemory` 依旧没有 `isAnchor` 参数。
- 反过来，用户可以把一条**话题**固化进 system（既有行为，保留）。
- **事务不得固化**：完成后的任务若仍在 system 里，会与"已完成"状态自相矛盾。
  UI 对 `kind = task` 隐藏固化开关；`setAnchor` 对事务直接拒绝（新增守卫）。

### 2.4 与作用域的关系

- 三类都落在**当前助手使用的记忆库**（`assistant.memoryId()`）：普通助手 = 助手自己的库，
  `useGlobalMemory` = `__global__`。
- 因此"画像跨助手共享"这件事不需要新机制：想让几个助手共用一份画像，就让它们使用全局记忆库
  （现有 UI 开关"全局记忆"）。本稿不做"画像自动全局"——那会改动隔离不变量（见
  `MemoryIsolationTest`）。
- 不做跨存储合并注入：同一请求只读一个库，和现在一致。

### 2.5 谁写、谁改、谁纠

| 角色 | 能做什么 | 边界 |
|---|---|---|
| 模型（聊天） | `create` 时可给 `kind` / `due`；`complete` 标记完成；`edit` 可改分类 | 不能设锚点；不能绕过 CAS；不能写被守卫拦截的内容 |
| 用户（记忆页） | 改分类、改截止日、勾选完成/重新打开、固化画像、删除 | 所有写入走 CAS + 历史 + 回收站 |
| 整理器（做梦，A2） | 纠正分类、合并、完成任务、清理已完成的旧任务 | 只能按 kind 规则；不能设锚点；审批模式下只提案 |
| 缺省 | 无 `kind`、无法解析、旧数据 | 一律 `topic`（不阻碍升级，也最容易在 UI 里被纠正） |

## 3. 数据模型

### 3.1 `MemoryEntity` 新列

```kotlin
@Serializable
@Entity
data class MemoryEntity(
    // ... 既有字段不变 ...
    /** 语义类型：profile / topic / task。未知值按 topic 读（向前兼容）。 */
    @ColumnInfo("kind", defaultValue = "'topic'")
    val kind: String = KIND_TOPIC,
    /** 仅 kind=task 有意义：open / done / dropped。非任务恒为 open（忽略该列）。 */
    @ColumnInfo("task_status", defaultValue = "'open'")
    val taskStatus: String = TASK_OPEN,
    /** 仅 kind=task 有意义：截止时刻（epoch millis，取本地日期 00:00；0 = 无截止）。 */
    @ColumnInfo("due_at", defaultValue = "0")
    val dueAt: Long = 0L,
)
```

- **用字符串而不是 int**：迁移/排障时直接可读（`kind='task'` 一眼可见）；非法值读时回落
  `topic` / `open`，不抛异常。枚举在 Kotlin 侧定义为 `MemoryKind` / `MemoryTaskStatus`
  （新文件 `data/model/MemoryKind.kt`），带 `storage` 与 `fromStorage`。
- `kind` 只对**记录**有意义；`isAnchor` 继续作为独立布尔列，不合并。
- 三个列都 NOT NULL + DEFAULT：存量行自动填默认值，属纯列新增；但**锚点回填**需要一条 UPDATE，
  所以迁移用手写 `Migration_46_47`（见 §3.3）。

### 3.2 `AssistantMemory` 与序列化口径

`AssistantMemory`（`data/model/Assistant.kt`）新增字段：

```kotlin
@Transient val kind: MemoryKind = MemoryKind.TOPIC,
@Transient val taskStatus: MemoryTaskStatus = MemoryTaskStatus.OPEN,
@Transient val dueAt: Long = 0L,
```

**为什么是 `@Transient`**：工具回执用的 `Json` 是 `encodeDefaults = true`
（`utils/Json.kt`）；若直接序列化，每条**话题**记忆的回执都会多出
`"kind":"topic","task_status":"open","due_at":0`（约 12–18 token/条），而它们对模型没有信息量。
口径与现有 `isAnchor` / `sortKey` / 遥测一致：**由 payload 显式决定给模型看什么**：

- `memory_tool` 的 create/edit/read/search 回执：显式 `put("kind", ...)`；
  任务再 `put("task_status")` / `put("due")`。话题回执只多一个 `kind:"topic"`（≈5 token）。
- L2 注入 JSON：仅对 `profile` 追加 `"kind":"profile"`（见 §5.4）；话题条目字节不变。
- L3 清单：仅 `profile` 行加 `[profile] ` 前缀。
- 事务走独立块（§5.3），不重复。

### 3.3 迁移 46 → 47

**手写迁移**（不放进 `autoMigrations`），与 `Migration_40_41` / `Migration_43_44` 同款：

```sql
ALTER TABLE `memoryentity` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'topic';
ALTER TABLE `memoryentity` ADD COLUMN `task_status` TEXT NOT NULL DEFAULT 'open';
ALTER TABLE `memoryentity` ADD COLUMN `due_at` INTEGER NOT NULL DEFAULT 0;
UPDATE `memoryentity` SET `kind` = 'profile' WHERE `is_anchor` = 1;
-- 提案层同一批（否则整理器写出的分类会在应用时被静默丢弃）：
ALTER TABLE `memory_proposal` ADD COLUMN `kind` TEXT NOT NULL DEFAULT '';
ALTER TABLE `memory_proposal` ADD COLUMN `due_at` INTEGER NOT NULL DEFAULT 0;
```

- **为什么锚点回填成画像**：用户已经显式固化过这些记录 = 用户认定它们是"长期设定"，
  按画像归类最接近其语义；同时让老用户升级后立刻有画像数据。**这是一次性语义默认值**，
  用户在记忆页改回话题即可；若判断相反（全部 `topic`），也不影响注入行为
  （锚点仍然在 L1，`kind` 只影响打分与 UI 分组）。
- **为什么不放进 AutoMigration**：纯列新增本身可以 auto，但回填 UPDATE 需要 `onPostMigrate`
  spec 或手写迁移；本仓 40→41 / 41→42 / 43→44 / 46→47 都是手写对象（44→45、45→46 为 AutoMigration），有 `DatabaseMigrationTracker` 日志
  可查，保持一致。
- 注册点：`data/di/DataSourceModule.kt` 的 `addMigrations(...)`、`data/sync/LegacyBackupConverter.kt`
  的 `addMigrations(...)`（两处都要加，漏一处旧备份导入会在 46→47 上失败）。
- Room 导出 `app/schemas/app.androidrik.rikkahub.data.db.AppDatabase/47.json` 随构建生成；
  新增 `Migration_46_47_Test`（androidTest，与 42→43 / 43→44 同款：建 46 库 → 塞数据 → 打开 →
  断言列默认值与锚点回填）。

### 3.4 历史 / 回收站 / 备份兼容

- `memory_history.snapshot` / `memory_trash.snapshot` 存的是 `MemoryEntity` 的 JSON
  （`snapshotJson`，`encodeDefaults = true`）。新写入的快照自动带三列；
  **旧快照没有这三列**，`decodeSnapshot` 会按 `@Serializable` 默认值解成
  `topic / open / 0` ⇒ 从旧历史/旧回收站恢复出来的记录是话题，需要用户再改。
  这是诚实边界：迁移**不改写**历史 JSON（文本重写成本高、失败不可恢复、还会破坏审计原文）。
- 整库备份是数据库级拷贝 ⇒ 列自然保留；旧包导入走 `LegacyBackupConverter.importMemoryFiles`，
  `INSERT` 列清单不含新列 ⇒ 数据库默认值 `topic/open/0` 生效，无需改导入 SQL。
- `copyMemories`（复制助手）要显式带上 `kind/taskStatus/dueAt`，否则复制出来的任务会变话题。
- `restoreRevision` / `restoreTrash` 走 `replaceBusiness`：历史恢复会**连同类型/状态一起恢复**
  （与现有 `isAnchor` 同口径），旧快照则落默认值。

### 3.5 容量（200）与淘汰顺序

现有 `enforceMaxCount` 按 `memoryScoreBucket` 升序淘汰，**锚点也在淘汰范围内**（历史遗留）。
本稿一并收紧：

1. **受保护集合**（不自动淘汰）：`isAnchor`、`kind = profile`、`kind = task && status = open`。
2. 淘汰顺序：
   1. `done` / `dropped` 的任务（先按完成时间升序，最老的先走）；
   2. 话题按 `memoryScoreBucket` 升序（现状口径）；
   3. 仍超限 ⇒ 明确抛出 `Memory capacity exceeded`，整笔事务回滚，**不淘汰受保护集合**。
      A1 实施时收紧原设计中“受保护记录最后也可淘汰”的矛盾：配额仍是硬约束，
      但通过拒绝新增保证，而不是静默牺牲既有画像、锚点或未完成事务。
3. `addMemory` 在满库时新增的记录也可能被同一次 `enforceMaxCount` 淘汰。现有实现会
   `check(...)` 抛 "Memory capacity exceeded"；新增要求：**新增后复核新记录仍在库中**
   （受保护集合必须活得下来）；若全库都受保护、新记录确实被挤掉，按既有契约抛错并回滚，
   不静默丢失。
4. 容量上限 200 不变（本稿不调参）。任务 `done` 后可以一直留在库里直到被淘汰/用户删除——
   这是有意的：完成记录可检索（"上个月这件事我做了没"），且可回收。

## 4. 写入面

### 4.1 `memory_tool` 契约

在现有参数上**只增不改**：

| 参数 | 适用 action | 说明 |
|---|---|---|
| `kind` | create / edit | `"profile"` / `"topic"` / `"task"`；缺省 `topic`；edit 缺省 = 保持原值 |
| `due` | create / edit | `YYYY-MM-DD`（仅 `kind=task` 有意义；`""` = 清除） |
| `action = "complete"` | — | 新动作：`id` + `expected_revision`，把任务置 `done` |

工具描述追加三类定义（判定句 + 反例 + 缺省规则），并明确：

- **画像候选**：可以写，但不会自动进 system；用户会在记忆页看到并决定是否固化。
- **事务**：只记用户自己说出的承诺/计划；不要记模型自己的待办（现有"never record your own
  to-dos"规则继续有效，且现在有了正确落点）。
- **完成**：用户说自己做完了才 `complete`；不要因为日期过了就自动完成。
- **分类不确定**：写 `topic`，不要猜。

工具描述是**稳定前缀**的一部分：这批文字变化会让升级后的第一个请求前缀失配一次（一次性），
之后照常命中缓存；不是每轮成本。

### 4.2 `complete` 动作

- 参数：`id`、`expected_revision`（必须来自模型实际看过的 revision，与 edit/delete 同款）。
- 语义：`task_status: open → done`；**不修改 content/description/importance/updatedAt**
  （完成不是"内容变更"，不能让任务靠完成刷新时效；一个独立的 `completed_at` 只在需要时再评估，
  本稿不新增列，完成时间可由 history `recorded_at` 得到）。
- 重复完成：`done → done` 幂等返回成功（revision 不推进）。
- 非任务：明确报错 `"not a task"`（不静默改成话题）。
- 回执：`{success:true, id, revision, kind:"task", task_status:"done"}`。
- 审批：`complete` 归入 `memoryModifyApproval`（`memoryToolNeedsApproval` 的 `edit/delete` 档）。

### 4.3 审批、CAS、守卫

- 不新增审批开关：`create` 仍走 `memoryCreateApproval`，`edit/delete/complete` 走
  `memoryModifyApproval`。
- 所有写入保持 CAS：`complete`、改分类、改截止日、用户勾选完成都有 `expected_revision`。
- `MemoryContentGuard` 不变（只拦 content 里的证件号/银行卡号）。
- "AI 不得自填锚点"不变量继续由 `addMemory` 的签名保证（新增 `kind` 参数 ≠ 新增 `isAnchor`）；
  新增源码守卫：`addMemory`/`createMemoryInStore` 之外的写路径不得出现 `is_anchor` 写入。
- 新增守卫：`setAnchor` 拒绝 `kind = task`（事务不得进 system）。

### 4.4 整理建议（提案）必须带上 `kind`

提案模式默认开启（`memoryModifyApproval` 默认 true），所以**分类必须能穿过提案层**，
否则整理器写出的 `task` 会在应用时静默变回 `topic`：

- `MemoryConsolidationProposal`（`MemoryConsolidationScheduler.kt`）增：
  `kind: String? = null`（null/空 = 未指定）、`dueAt: Long? = null`。
- `MemoryProposalEntity` 增列：`kind TEXT NOT NULL DEFAULT ''`、`due_at INTEGER NOT NULL DEFAULT 0`
  （DB 47 同一批迁移，纯列新增）。提案的 `due_at` 编码与记忆实体不同：
  `0` = 未指定（旧提案保留原截止日），`-1` = 明确清除，正数 = 截止时刻；
  转回 `MemoryConsolidationProposal` 时分别解成 `null` / `0L` / 原值，避免编辑清日期丢失。
- `MemoryProposalRepository`：`savePending` 落库、`toProposal` 读回、`applyRow` 传给
  `addMemory` / `updateContent`；确认页摘要（`proposeChangeSummary`）显示类型变化
  （如 `topic → task`）。
- 兼容：旧提案 `kind = ''` ⇒ create 落 `topic`、edit 保持原值，行为与现状一致。

## 5. 注入面

### 5.1 `MemoryInjectionPlan` 新结构

```kotlin
internal data class MemoryInjectionPlan(
    // 既有字段不变 ...
    val tasks: List<AssistantMemory> = emptyList(),
    val taskText: String = "",
    val taskTokens: Int = 0,
    val taskOmittedCount: Int = 0,
)
```

- **任务文本并入 `dataText`**（同一个尾部合成 USER 消息），不新造消息：
  `PromptInspector` 靠 `MEMORY_TAIL_PREAMBLE` 识别尾部记忆块、`ContextCapacitySheet` 靠
  同一段文本归因；并入后这两处**零改动**，`totalEstimatedTokens` 的等式也不变
  （`dataTokens` 就是 `count(dataText)`）。
- 仍满足"绝不截断正文"：任务按整条取舍，不裁剪。

### 5.2 预算切分（精确顺序）

记 `B = budgetTokens`，`L0 = disciplineTokens`，`L1 = anchorTokens`：

1. **L0 / L1 不变**：先算纪律层；连 L0 都装不下 ⇒ 全空（现状）。
2. **事务预留**：`taskCap = min(MEMORY_TASK_BUDGET_TOKENS, max(0, B − L0 − L1))`，
   `MEMORY_TASK_BUDGET_TOKENS = 800`（默认预算 8000 的 10%）。
   开放任务按 §5.3 的顺序整条选取，最多 `MEMORY_TASK_LIMIT = 20` 条；
   首条即使超 `taskCap` 也入选（与 L2 / `selectMemoriesForPrompt` 同款"首条豁免"，
   避免"只有一条任务却因预算为 0 而不显示"）。超出部分记 `taskOmittedCount`。
3. **尾部脚手架**：按"全部省略 + 空任务数组"预留前导/省略告知/空 JSON；
   `remaining = B − L0 − L1 − taskTokens − scaffold`。
4. **L2（话题 + 画像候选）**：`l2Cap = floor(remaining × 0.75)`（沿用 75/25 切分），
   按 `rankedMemoryCandidates` 整条选取；候选排除：锚点（L1 已渲染）、溢出锚点、**所有 TASK**
   （事务只出现在事务块，不重复）。
5. **L3**：剩余额度给清单，排除锚点、L2 已入选项、**所有 TASK**；清单行对 `profile` 加前缀。
6. **精确复核 + 确定性 fallback**（按序）：去掉 L3 → 去掉 L2（保留"全部省略"告知）
   → 去掉事务块（保留告知）→ 尾部整块为空。事务在 L2/L3 之后才被牺牲：
   它是"必须看见"的通道，话题是竞争性内容。

### 5.3 事务块

只收 `kind = task && status = open` 且**本次没有实际在 L1 渲染**的记录（锚点任务被 §4.3 禁止，正常不会出现；
若历史数据里有，按"已在 L1 渲染 ⇒ 不再重复"处理，避免同一条出现两次；
被冻结集合排除、超出锚点名额或放不进 L1 的异常开放事务仍走事务通道，不能静默消失）。

排序：有截止日的按 `dueAt` 升序在前；无截止日的在后；同组按 `sortKey` 升序（写入先后）。
理由：近期截止最需要行动；无截止的是"想起来再做"，稳定排序保证同一集合逐字节可复现。

渲染（`JsonInstantPretty`，与 L2 一致）：

```text
**Open tasks** (the user's own commitments; act on them, do not recite them)
[
  {"id": "a3f1c09e", "revision": 2, "task": "周五前交 Flutter 作业", "due": "2026-10-23"},
  {"id": "7bd20f45", "revision": 1, "task": "记得回复房东", "overdue": true}
]
```

- `due` 只在 `dueAt > 0` 时给（`yyyy-MM-dd`，本地时区）。
- 逾期用 `"overdue": true` 标出（`dueAt` 的当地日期 < 今天）；**模型不必自己算日期**。
- 末尾若还有被略过的开放任务：`**Omitted tasks**: N more open tasks are not shown.`
  （与记忆省略告知分开，避免把两种"没显示"混成一种）。
- **已完成（done）不注入**：完成即从上下文消失；需要时用 `memory_tool` 的
  `action="search"`（必要时再 `read`）取回。
  不注入"最近完成"列表（A2 再评估，见 §12）。

### 5.4 画像候选在 L2 / L3

- 画像候选（`kind = profile`、未固化）参与 L2 排名，与话题同池竞争——**有竞争才有意义**：
  用户不固化时，画像至少比普通笔记更容易入选（不衰减）。
- L2 JSON 对画像追加 `"kind": "profile"`；话题条目**不加字段**（保持既有字节契约）。
- L3 清单行对画像加 `[profile] ` 前缀（`- [profile] a3f1c09e | ...`），话题行不变。
- 固化后进 L1 的锚点：L2/L3 排除（现状），所以同一条不会出现两次。

### 5.5 打分与下限

`memoryScore` 按 kind 只改一个因子：

| kind | 活跃度口径 | 说明 |
|---|---|---|
| `topic` | `max(写入衰减, 读取衰减)`（现状） | 不改 |
| `profile` | **恒为 1** | 身份不因"很久没改"变陈旧；读取信用照常给（`effectiveImportance` 不变） |
| `task` | 不参与 L2 打分 | 事务走固定通道；完成状态由 `open/done` 决定，不由分数决定 |

- 注入下限 `MIN_MEMORY_INJECTION_SCORE = 0.2` 对 L2 候选（话题 + 画像）继续生效；
  画像默认 0.6 ⇒ `0.6 × 1.0 = 0.6`，远高于下限。用户若把画像重要度调到 < 0.2，
  它仍可被固化进 system，但不会进 L2——这是"用户显式降权"的合理结果。
- **事务不受下限影响**（不入 L2 排名）。
- 相关性维度（词元覆盖）只作用于 L2 的话题/画像；事务不参与（避免"没提到就不显示任务"）。

### 5.6 顺序与位置（尾部块内部）

```text
[MEMORY_TAIL_PREAMBLE]
<L2 JSON 数组>            ← 只含话题 + 画像候选（画像条目带 kind）
[Memory index 标题 + 行]   ← 只含未入 L2 的话题/画像
[省略告知（记忆）]
[Open tasks 标题 + JSON]   ← 放在最后
[省略告知（任务）]
```

**为什么任务放最后**：它是整块里最需要"行动"的内容，放在最靠近生成的位置（判断依据与
`design-memory-injection-tiering.md` §3.3 的尾部取舍同源：尾部块本身不变前缀；块内顺序属于
提示工程取舍，不是缓存契约）。**为什么仍然放在同一个尾部合成消息里**：不新增消息、
不改 `PromptInspector`/容量面板的识别口径，也不引入 provider 的 role 差异。

### 5.7 缓存、计费与预览

- 事务块在**尾部**：任务创建/完成都不影响前缀缓存（与现有 L2/L3 同理）。
- 画像候选也在尾部；只有用户**固化**才进 system，且走现有"按会话冻结 id 集合"机制
  （一次固化只影响之后的会话；已有会话要在会话菜单里按固化）。
- 计费：`dataTokens` 直接包含任务文本；`totalEstimatedTokens` 不变。
  `ContextCapacitySheet` 的"尾部记忆"行自动包含任务 token，不需要新行。
- `PromptInspector`：`PromptSegmentKind.MEMORY_TAIL` 仍按 `MEMORY_TAIL_PREAMBLE` 识别，
  不需要新段；若将来要拆分任务归因，再单开（本稿不做）。
- 诊断日志（若有"memory inject: N/M"类日志）：条数口径改为"记忆 N 条 + 任务 K 条"，
  避免日志与实际注入不符（`design-memory-injection-tiering.md` §3.2.1 踩过同款坑）。

### 5.8 关键不变量

1. **没有 TASK、没有 PROFILE 记录时，记忆块逐字节不变**（全部 kind=topic ⇒ 打分、选取、
   渲染与现状一致；现有契约测试不用改）。
2. L2/L3 与事务块**不重叠**：锚点、L2 入选、事务各自排除同一 id。
3. 事务只以 `open` 出现在上下文；`done`/`dropped` 永不注入。
4. 同一请求的 plan 只算一次，装配与预览共用（现状契约）。
5. `isAnchor` 仍只能由用户写；`kind=profile` 不改变这条。

> 注意口径：不变量 1 是**记忆块**层面的；工具描述与 L0 纪律文字的更新会让升级后
> **整个请求**的前缀失配一次（一次性成本，之后稳定）。这是任何工具契约升级的固有权衡，
> 不是每轮成本。

## 6. 整理面（A2）

- 库快照 / 校准表（`buildMemoryCalibrationTable`）增加 `kind` 列；任务再加 `status` / `due`。
- 规则按 kind 分：
  - **画像**：矛盾时用用户最新说法覆盖；同义画像合并；**永不因"老"删除**；
    不能替用户"固化"（没有该动作）；A2 仅允许文字建议用户在有用时考虑固化，不自动执行。
  - **话题**：维持现状（合并重复、被覆盖才退役）。
  - **事务**：只有在**新轮次里用户明说自己做完/取消了**时才 `complete` / 置 `dropped`；
    **永不因为日期过了或记录老而自动完成/删除**；已完成的旧任务可按"被覆盖/无引用"退役。
- 合并纪律：**不跨 kind 合并**（任务不能并进话题；画像不能并进话题）。跨 kind 的重复由
  两条记录分别保留，或由用户手动处理——自动合并跨类会静默改变生命周期。
  可选的「整理时允许调整类型」开关放宽**单条已有记录改类**；**一步内跨类合并仍禁止**，
  但允许「先独立改类、后续步骤再合并」的两步路径（每步独立成立，见 §18）。
- 提案动作新增 `complete` / `drop`（A2）：`MemoryConsolidationProposal.action` 分别映射
  `done` / `dropped`，均要求 `id` + `expected_revision`，走修改审批；DB 动作列是 TEXT，无需迁移。
  `complete/drop` 是状态变更，不能作为删除提案的替代输出依赖；删除依赖仍只能指向同类 `create/edit`。
  A1 阶段仅做 `kind/due` 透传；A2 接通这两个状态动作。

## 7. UI

### 7.1 A1（最小可用）

- 记忆页条目：显示类型标记（`画像` / `话题` / `事务`），事务显示截止日与状态；
  已完成任务置灰、可重新打开。
- 事务条目：复选框 `open ↔ done`（走 CAS）；`due` 逾期用 error 色。
- 编辑/新建对话框：类型选择（三个 FilterChip 或 `SegmentedPill`，默认话题）；
  选事务时显示截止日选择器（Material3 `DatePicker`，日期粒度，不要时间）；
  **事务**隐藏"固化为锚点"开关（事务不得固化；画像和话题保留既有固化能力）。
- 列表顺序：保持现有 `id ASC`（`MemoryDAO`），不引入排序选项（避免与"稳定"目标打架）。

### 7.2 A2（呈现）

- 顶部筛选：`全部 / 画像 / 话题 / 事务`（带计数）——A2 已实现筛选，保持原列表顺序，不额外重排分组。
- 画像区引导文案："画像候选不会自动进入系统提示；点『固化』后才会长期生效"。
- 容量面板：满库时提示"受保护记录（画像/未完成任务/锚点）不会自动淘汰"。
- 回收站/历史：显示类型与状态。
- 文案：新增约 10–12 个 key，覆盖 `values` / `values-zh` / `values-zh-rTW`（本仓实际只有这三个
  语言目录；不是旧文档里写的 6 个）。

### 7.3 不做

- 不做任务提醒/通知（用户已定：留待后续）。
- 不做子任务/优先级/依赖图。
- 不改输入栏 `MemoryPicker`（会话级"不使用记忆"/"固化"语义不变）。

## 8. 测试与守卫清单

**单元测试（JVM）**

- `MemoryKind` 解析：三值 + 未知值回落 topic；`task_status` 未知值回落 open。
- 打分：画像在 0 天与 180 天**同分**；话题打分与现状逐位一致（现有测试不改）；
  画像读取信用仍然生效；L2 下限对画像生效。
- `MemoryInjectionPlan`：
  - 无 TASK/PROFILE ⇒ 与现状**逐字节一致**（golden）；
  - 事务预留先于 L2；任务 cap 20 / 800 token；首条豁免；`taskOmittedCount` 正确；
  - 事务不在 L2 / L3 出现；锚点任务不重复；
  - 排序（due 升序、无 due 后置、同组 sortKey 升序）；逾期标记；
  - fallback 顺序（去 L3 → 去 L2（保留告知）→ 去任务（保留告知）→ 尾部为空）；
    总 token 不超过预算；
  - 同一输入同一 `now` 两次调用逐字节一致。
- 工具：create 默认 topic；create task 带 due；edit 改 kind（task → topic 清空 status/due）；
  `complete` 幂等/非任务报错/CAS 校验/审批映射；非法 `due` 报错；提案回执带 kind/due。
- 仓库：容量保护（open 任务/画像/锚点不被淘汰；done 任务先淘汰；全受保护时抛出既有契约错误）；
  `copyMemories` 保留 kind/status/due；`updateContent` 改类型时清任务字段；
  `setAnchor` 拒绝任务；旧快照恢复为 topic；`complete` 不推进 `updatedAt`。
- 提案：`kind/due` 落库-读回-应用闭环；旧提案（kind 空）行为不变。
- 提示词：库快照/校准表含 kind；L0 文案快照更新（预期变化）。

**仪器测试（androidTest）**

- `Migration_46_47_Test`：46 库 → 47；三列默认值；锚点回填 profile；非锚点 topic。
- 既有 `Memory*InstrumentedTest` 扩展：事务完成/取消的事务性、容量淘汰顺序。
- 迁移链回归：`Migration_42_43_Test` / `43_44_Test` 仍绿（新迁移不能破坏旧链）。

**源码守卫**

- `addMemory` / `createMemoryInStore` 仍不得接受 `isAnchor` 参数（现有 `MemoryAnchorWiringTest`）。
- 新增：写 `is_anchor` 的 SQL 只允许出现在 `updateAnchor`/`replaceBusinessFields` 等既有白名单；
  工具层/整理层源码不得出现锚点写入。
- 新增：`memory_tool` 的 `complete` 必须走 `setTaskStatus`（不得用通用 update 伪造完成）。

**手工验证（实现后）**

- 新建一条任务 → 真机确认尾部出现 `Open tasks`、L2 条数与 token 读数变化；
- 完成任务 → 下一轮请求任务消失、前缀缓存读数仍命中（`(Y cached, Z%)`）；
- 画像候选 → 不进 system；点固化 → 新会话 system 出现、旧会话需手动固化（现状语义）；
- 预算压到 2000 token：确认任务优先于话题，fallback 不超预算。

## 9. 实施顺序与范围

### A1：数据与注入（可独立发布）

1. `MemoryKind` / `MemoryTaskStatus` 枚举 + `MemoryEntity` 三列 + `AssistantMemory` 三字段。
2. `Migration_46_47` + `AppDatabase` 47 + 注册两处 + 迁移测试 + schema JSON。
3. `MemoryDAO`：新增 `updateTaskStatus` / `updateContentFields` 扩展；`MemoryRepository`：
   `addMemory`(kind/due)、`updateContent`(kind/due)、`setTaskStatus`、`copyMemories`、
   `enforceMaxCount` 保护顺序、`setAnchor` 拒绝任务。
4. `MemoryTools`：`kind` / `due` / `complete`；payload 显式字段；提案透传。
5. `MemoryInjectionPlan`：事务块 + 预算切分 + fallback；`GenerationPrompts`：打分分型、
   渲染（事务块、画像标记）、`renderMemoryDataPrompt` 扩展。
6. `MemoryConsolidationScheduler` / `MemoryProposalEntity` / `MemoryProposalRepository`：
   提案 kind/due 落库与应用；DB 47 同一批列（若提案列也放 47，需在同一迁移里加）。
7. 基础 UI：条目类型标记、编辑对话框类型/截止日、事务勾选、禁固化任务。
8. 文案（3 个语言目录）+ 测试 + 文档（本稿落地记录、roadmap）。

预估触及约 16–20 个源码/资源文件 + 6–8 个测试文件。建议**分两次提交**：
先"数据与迁移 + 仓库/工具"（可编译、可测、注入行为在无新记录时不变），
再"注入计划 + 打分 + UI"（有用户可见变化）。

### A2：整理与呈现

- 整理器分 kind 规则 + `complete/drop` 提案动作 + 库快照/校准表类型列；
- 记忆页筛选/分组、画像固化引导、容量提示；
- 全量回归（含既有 ~2000 项单测、lint、迁移链）。

### B：后续（不在本稿）

- 话题标签/话题卡（独立实体或自由标签，先看 A1/A2 的真实使用）；
- 记忆 markdown 导出（借文件形态的"可读可带走"，不换真相源）；
- 事务提醒/通知（用户已定留待后续）。

## 10. 风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 模型分类不准（画像↔话题） | 画像丢了 system 权威或反之 | 默认话题；工具描述给判定句+反例；UI 一眼可见可改；A2 整理器纠正；画像候选仍可被固化 |
| 画像候选过多、挤占 L2 | 话题可见度下降 | 画像候选与话题同池竞争（不额外预留）；A2 可评估"画像单独小配额" |
| 任务块常驻 800 token | 每轮 +10% 记忆预算 | 可调常量；`taskCap` 随预算缩放；首条豁免避免空窗 |
| 满库时受保护记录无法新增 | 用户无法再创建任务/画像 | 保留既有"容量已满"明确错误；容量面板提示；done 任务可淘汰释放槽位 |
| 旧历史/回收站恢复成 topic | 少数恢复记录需重分类 | 文档明说；UI 类型可改；不重写历史 JSON（审计原文优先） |
| 迁移破坏旧链 | 升级失败 | 手写迁移 + 46→47 测试 + 44→45/45→46 回归；两处 `addMigrations` 都加 |
| 工具描述变大 | 每请求固定 token + 一次性前缀失效 | 文案精简、控制在 ~200 token；与现有工具描述同属缓存前缀 |
| 用户已有 200 条记忆且全是 topic | 升级后无画像/任务 | 锚点回填 profile；其余用户自行分类或由整理器 A2 纠正 |

## 11. 非目标

- 不做 Claude 式文件记忆（不把文件当真相源）；可选导出是 B 阶段。
- 不改存储引擎、不加 embedding 检索（词元相关性继续）。
- 不做话题实体/层级/自动聚类（B 阶段再议）。
- 不做跨助手自动共享画像（用现有"全局记忆"开关表达）。
- 不做事务提醒/通知/日历集成。
- 不做子任务、优先级、依赖、重复任务。
- 不允许 AI 自填锚点、不允许事务进 system。

## 12. 开放问题（实现前可再定，不影响 A1）

1. 是否要**助手级"任务注入开关"**（有些用户不想要待办块）？
2. 已完成任务是否要注入一个"最近完成"短列表（避免模型重复问同一件事）？
3. 画像是否需要一张结构化"用户卡"（姓名/语言/时区等固定字段），还是保持自由文本记录？
4. 整理器纠正分类是否要默认开启（会额外花模型调用）？
5. `due` 是否允许时间粒度（目前只到日期）？

## 13. 附录：常量与触点

| 项 | 值/位置 |
|---|---|
| 新常量 | `MEMORY_TASK_BUDGET_TOKENS = 800`、`MEMORY_TASK_LIMIT = 20`（`GenerationPrompts.kt` 或 `MemoryInjectionPlan.kt`） |
| 类型存储值 | `profile` / `topic` / `task`；状态 `open` / `done` / `dropped` |
| DB | 46 → 47（`Migration_46_47`，注册 `DataSourceModule` + `LegacyBackupConverter`） |
| 注入 | `MemoryInjectionPlan.kt`、`GenerationPrompts.kt`（`memoryScore` / `rankedMemoryCandidates` / `renderMemoryDataPrompt`） |
| 整理快照/校准 | `MemoryConsolidationPrompts.kt`（`buildLibraryPagePrompt` / `buildMemoryCalibrationTable`） |
| 工具 | `MemoryTools.kt`、`ChatToolFactory.kt`（`memoryToolNeedsApproval`） |
| 仓库/DAO | `MemoryRepository.kt`、`MemoryDAO.kt` |
| 提案 | `MemoryConsolidationScheduler.kt`、`MemoryProposalEntity.kt`、`MemoryProposalRepository.kt`、`MemoryProposalText.kt` |
| UI | `AssistantMemoryPage.kt`、`res/values*/strings.xml` |
| 迁移测试 | `app/src/androidTest/.../db/migrations/Migration_46_47_Test.kt` |

## 14. A1 落地记录（2026-10-21）

- **数据与仓库**：DB 47 三个记忆列 + 两个提案列、两处迁移注册、Room 生成的 schema；
  字符串枚举容错、旧快照默认话题、复制/恢复保留类型、事务状态 CAS、任务禁止固化。
  完成/重开不刷新内容 `updatedAt`；关闭状态再次设置幂等但仍校验版本。
- **容量**：仅淘汰非保护记录，关闭事务先于话题；受保护记录不足以腾出容量时拒绝新增并回滚。
  这是 §3.5 的实施澄清，不采用原稿中“最后也淘汰保护集合”的冲突分支。
  事务同文去重只复用“开放且截止日相同”的事务，已关闭或不同截止日允许新建。
- **工具与提案**：`kind` / 严格本地日期 `due` / `complete`；两处生产装配接 typed 回调；
  完成走修改审批及仓库 CAS。直接操作的版本校验与既有 edit/delete 同口径（描述要求来自已看过的版本，
  仓库检测陈旧版本，不额外持久化“模型确实看过”的证明）。A1 阶段提案模式明确拒绝 complete，A2 在 §15 接通。
  kind/due 提案保存—读回—应用闭环，清除日期与未指定分开编码；确认摘要披露规范化类型与隐式清任务字段。
- **注入**：开放事务独立尾部块，800 token / 20 条软限额与最终精确硬预算；
  画像不衰减并保留读取信用；全话题旧格式 golden；关闭事务不注入。
  异常历史“事务+锚点”只对实际进入 L1 的 id 去重，冻结排除/名额溢出不再静默丢失。
- **基础 UI**：三类标记与类型选择、Material3 日期选择/清除、逾期色、完成/重开、关闭事务正文置灰。
  仅事务隐藏固化；已有锚点须先由用户显式关闭固化再改为事务，同一次 CAS 保存。
  三语各增 13 个资源键，通过 locale-tui CLI 写入；环境无 uv/翻译 API key，
  使用现有 venv 与人工译文 fallback，未改工具配置。
- **自动验证**：`:app:testDebugUnitTest` **240 类 / 2170 项，0 失败、0 错误、0 跳过**；
  `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 成功（`-PmemoryIsolatedTests=true`）。
  真机 `6c841f95` 隔离 runner 的 **50 项仪器测试全部通过**：46→47、42→43、43→44 迁移，
  新事务生命周期/容量回滚及既有版本/事务测试；只使用测试自有库或内存库，不操作生产库。
  最终 arm64 debug APK 已覆盖安装，debug Activity 启动命令成功；release 包未覆盖。
- **验证边界**：未逐项截图/操作基础 UI，未用真实模型端到端跑创建/完成及缓存计费，
  未验证跨时区切换后的日期语义，未运行 lint 或所有 androidTest；不把这些计作通过。
  A1 阶段不做 A2 整理器分型规则/complete 提案/筛选分组（后续进度见 §15），也不做通知调度。
- **提交状态**：实施当时保留工作树；后经用户授权于2026-10-21分批本地提交（见 §17），未推送。

## 15. A2 落地记录（2026-10-21）

- **整理输入与纪律**：库快照每条带 kind，事务附 task_status / 本地日期 due；校准表增加同类字段，
  展示描述换行折为空格、竖线转义（不改存储原文）。画像按用户最新明确陈述纠正，话题保持原整理逻辑；
  禁跨 kind 合并，开放事务不因老、逾期或助手声称而关闭；只有 NEW TURNS 的用户明确完成/取消证据可触发状态动作。
- **动作闭环**：`complete`→DONE、`drop`→DROPPED，两处生产装配均接 CAS/修改审批。
  提案需实际观察过的 id/revision；存储、读回、应用、状态回执和详情全部接通，无新增 DB 列。
  状态动作不能充当替代内容依赖；同批状态记录被后来新增淘汰、或已消费的替代输出被状态变化推进版本，均整批回滚。
- **合并守卫边界**：显式删除提案的替代输出必须与目标同 kind，否则 stale；
  **直接整理模式的普通 delete 没有合并意图/替代输出协议，跨类合并仍由提示词约束，不宣称全路径代码强制**。
- **呈现**：全部/画像/话题/事务 FilterChip 含计数，筛选不改变仓库顺序、切库重置；画像候选说明区分
  普通上下文与 system 固化；容量显示当前/200，满库明确受保护记录不自动淘汰、容量不足新增失败。
  开放事务增加取消入口和二次确认；历史/回收站及恢复确认按所选 snapshot 显示类型、状态、截止日。
  locale-tui CLI 三语各增 9 键，沿用现有 venv/人工译文 fallback；不改工具配置。
- **测试范围**：新增筛选/计数/快照纯函数测试、分型提示与 worker 请求测试、工具/仓库/摘要测试，
  以及真实 Room complete/drop 提案、回执失败/依赖版本/容量回滚 8 项与 44→47 连续迁移测试。
  扩大迁移回归时修正旧 Migration_40_41_Test 的当前版本断言及遗漏的迁移注册，未改旧迁移生产逻辑。
- **最终验证**：全量 JVM **241 类 / 2188 项，0 失败、0 错误、0 跳过**；
  debug / androidTest APK 构建成功；`:app:lintDebug` 成功，**0 errors / 367 warnings / 6 hints**，
  不把 lint 成功解释为零警告。最终 debug 与测试 APK 已覆盖安装，release 未覆盖。
  真机隔离数据库/完整迁移包 **84 项，83 通过、1 失败**；唯一失败为既有
  `Migration_11_12_Test.migrate11To12_handlesVeryLargeConversations` 的 `SQLiteBlobTooBigException`
  （超大行超 CursorWindow），本轮未更改该旧迁移或弱化其测试。新 A2 与 44→47 测试全部通过。
- **边界**：不操作真实记忆制造 UI 样本，未逐项点击/截图真实 UI，未用真实模型端到端验证整理证据判断；
  纯 helper 通过不等于 Compose 点击验收。无提醒通知/子任务/依赖图。
  工具定义相对 A1 粗增约 25 token，整理静态提示粗增约 350–450 token（字符估算，非 tokenizer 实测）。
- **提交状态**：A1/A2 后经用户授权分批本地提交（见 §17），未推送；原有上下文净化设计稿未改。

## 16. 原版文件式记忆导入映射（2026-10-21）

核对本地 `upstream/master = 815e9c20b` 的 `MemoryPrompts.kt` 与 `MemoryFile.kt`：
原版约定五类文件位置，但**没有标准事务文件，也没有结构化 task status / due 字段**。
因此不能把项目目录等同于可关闭事务，或从一句正文推断完成/截止日。

| 原版路径 | 导入分类 | 说明 |
|---|---|---|
| `/profile.md` | profile | 身份长期事实，作为候选；不自动固化 |
| `/preferences.md` | profile | 长期回答偏好，同样不自动固化 |
| `/topics/<domain>.md` | topic | 领域事实 |
| `/areas/<name>.md` | topic | 项目/持续事项混合约束、决策、进度，不整篇转 task |
| `/people/<name>.md` | topic | 人物关系与共同事项，不等同用户画像 |
| 未知路径（包括自定义 `/tasks/…`） | topic | 没有已核实结构化约定，不猜生命周期 |

- 仅精确根文件识别，兼容省略起始 `/` 与路径两端空白；不做不区分大小写或 basename 匹配，
  `/topics/profile.md`、`//profile.md`、路径穿越等不会被误识别为画像。
- 保留既有顶层条目拆分和来源标记处理、正文、助手作用域、更新时间；新行 revision=1、独立 publicId、
  isAnchor=false。SQL 显式写 kind/task_status/due_at，状态仍为 open、日期为0，不从 checkbox/正文/YAML猜。
- 不重写已导入的存量话题记录；此映射用于后续原版备份转换。本仓旧库仍走46→47既有迁移规则。
- **验证**：全量 JVM **241 类 / 2190 项全部通过**；debug / androidTest APK 构建成功并安装。
  真机隔离 runner 的 `LegacyBackupConversionTest` **3 项全部通过**（含新分类映射、既有兼容表/附件、未知库拒绝），
  使用自造备份库，无真实备份或生产记忆数据；只读独立审查未发现新增问题。本轮未重跑 lint/全部迁移包，
  A2 记录的旧11→12超大行失败不因这3项通过而消失。
  本次映射已实现，但不对已经导入的存量记录做追溯改类；后经用户授权本地提交，未推送。

## 17. 分批本地提交（2026-10-21）

用户授权「分批提交（暂不推送）」；A1/A2交叉的工具/仓库协议按依赖归入核心批次，未机械拆成不完整历史版本。

1. `49be22aab` — `feat(memory): 新增三类记忆、事务生命周期与分层注入`
2. `06ffa0b70` — `feat(memory): 整理器按记忆类型校准与处理事务`
3. `85c580d72` — `feat(memory): 增加分类筛选、事务操作及恢复元数据展示`
4. `1ead505c9` — `fix(backup): 原版记忆文件导入映射画像与话题`（同时注册转换器DB47迁移）
5. 文档批次 — 同步本设计、验收边界及roadmap。

验证针对最终组合工作树，未逐个checkout中间提交重跑构建。未执行push；无关的上下文净化设计稿未纳入提交。

## 18. 整理时调整类型的开关（2026-10-21）

**问题**：A1/A2 的整理提示允许「明确证据下纠正错类」，但没有代码门禁，也没有用户入口决定
「整理能不能动类型」；一旦模型误判，改类会静默改变生命周期（保护、过期、画像候选）。
**方案**：助手级独立开关 `memoryConsolidationReclassify`（默认 `false`），位于记忆设置里、仅在
「空闲后整理记忆」已开启时显示。它**只影响后台整理**，不影响聊天内 `memory_tool`、手动编辑或已有数据。

**默认关闭是一处有意的收紧**：关闭态主提示改为「已有记录的类型固定」，仓库也拒绝不同 `kind` 的写入；
不再保留旧提示里「明确证据下可纠正错类」的模糊许可。用户要这项能力必须显式打开开关。
这比 A1/A2 的「提示允许、代码不拦」更可预测；代价是默认情况下整理不再自动纠错类。

### 18.1 开启后的允许范围与硬约束

- **只改已有单条记录的类型**：`edit` 带 `kind`；不新增动作，不改工具 schema。
- **证据必须是新轮次里用户自己的说法**：助手措辞、模型推断、年龄、截止日、沉默都不算。
- **不得用于规避限制**：不为压到 200 条上限、不为把事务移出/移入保护集合、不为让合并通过。
- **未完成事务根本不能改类**：仓库门禁拒绝 open task → 非 task；做完用 `complete`、取消用 `drop`。
  这是代码约束，不只是提示。
- **已关闭事务**优先保持事务；有新轮次表明记录**本来就不是承诺**时才可改类，仅「已成为长期知识」
  应新建话题而不是替换原任务。
- **跨类合并**：**一步内禁止**；允许「先单独改类、后续步骤再合并」的两步路径，但每一步都要独立成立，
  且审批模式下每一步都由用户确认。提案依赖守卫只比较应用后的类型，因此两步路径会通过——这是有意的。
- **task → profile/topic 会清空状态与截止日**，必须作为同一决策的一部分，不能当副作用。
- **锚点不能变事务**：仓库既有守卫不变，模型不应尝试；需要先由用户解除固化。

### 18.2 两层执行

| 层 | 行为 |
|---|---|
| 系统提示 | 关闭：类型固定，不追加例外；开启：追加窄例外段，并明确覆盖主提示的类型固定规则 |
| 仓库写入门禁 | 整理直写传 `allowKindChange` 与 `allowOpenTaskKindChange=false`：关闭时不同 `kind` 报错拒绝（同值放行）；未完成事务改类始终拒绝 |

**审批/只提案模式**：改类会作为建议落库，确认摘要展示 `profile → topic` 等类型变化；应用时以用户
批准为写授权。**关闭开关只约束直写与提示生成**，不追溯过滤旧建议；若模型在关闭态违反提示提出改类建议，
它仍需用户逐条确认，不是静默生效。这一边界在文档中明确声明。

**共享记忆库**（`useGlobalMemory`）：在**真正参与整理的助手**（开启记忆且整理开关已开）中要求全部开启；
未开整理/已停用的共享助手不投票——它们的开关在 UI 不可见，否则会形成无法操作的静默否决。

### 18.3 验证与代价

- 测试覆盖：关闭态提示不含改类许可；开启态含例外且保留反绕过句；共享库全开放行、混开关闭、
  非参与者不投票；仓库关闭时不同 kind 写入失败、同 kind 编辑不受影响、open task 改类被拒、
  关闭后的 task 改类放行；旧 JSON 缺字段默认关闭；源码守卫钉住提示与门禁接线。
- **自动验证**：全量 JVM **241 类 / 2198 项，0 失败、0 错误、0 跳过**；
  `:app:assembleDebug` 成功；`:app:lintDebug` 成功，**0 errors / 367 warnings / 6 hints**。
  本轮未新增仪器测试（无 DB/迁移变化），未单独跑真机 UI 点击。
- 提示增量（仅开启时）：约 440–470 token（字符估算，非 tokenizer 实测）；关闭时零增量。
  工具 schema 与首轮工具描述不变。
- 未改 DB、无需迁移；未改聊天工具能力。
- **剩余边界**：门禁是整理调用方的 opt-out 参数；聊天工具与 UI 手动编辑仍可改类。
  运行中切换开关不影响已启动整理捕获的值。「不得为规避 200 条上限/保护而改类」与
  「一步内不跨类合并」仍是**提示层纪律**；代码只硬拦截整理关闭时的改类、整理对未完成事务改类，
  以及提案依赖的同类型校验。
