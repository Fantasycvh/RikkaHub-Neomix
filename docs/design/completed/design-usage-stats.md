# 用量统计：从「扫消息 JSON」到「请求流水」

> 状态：**已落地**（DB v44）。2026-10-20。
> 借鉴来源：上游 `5a90c0548 feat(stats): 新增 usage_record 用量流水表…`（2026-10-19），
> 按 fork 架构重写，非 cherry-pick（见 §6 差异表）。

## 1. 问题：旧统计的三个硬伤

旧实现（`MessageNodeDAO` 的 `@RawQuery` + `json_each(messages)`）在**消息 JSON** 上聚合 token：

| 硬伤 | 具体表现 | 根因 |
|---|---|---|
| **删会话 = 历史消失** | 删掉一个会话后，它花掉的 token 从统计里一起没了，总量会**变小** | 用量挂在 `message_node` 上，`ON DELETE CASCADE` 跟着删 |
| **看不见后台请求** | 标题、建议、压缩、翻译、记忆整理、OCR、子代理的花费**完全不计** | 这些请求不写入 `message_node`，只在内存里用一次 |
| **打开统计页要等** | 每次进页面把全库 `messages` 展开成 `json_each()` 虚拟表：行数 = 全部消息 × 分支 | 统计口径是「解析出来的消息」而不是「发生过的请求」 |

第二个硬伤尤其误导：用户看到「输入 3.2M」时以为这就是花掉的钱，但**自动标题/建议每轮都在跑**，
压缩和记忆整理还会额外发请求，实际账单明显更高。

## 2. 方案：一次请求一行流水

新增 `usage_record` 表（实体 `data/db/entity/UsageRecordEntity.kt`）：

| 列 | 含义 |
|---|---|
| `id` | 自增主键 |
| `created_at` | 请求发生时刻（epoch millis），统计按**设备本地时区**分桶 |
| `source` | `UsageSource` 名称：`CHAT` / `SUBAGENT` / `COMPRESSION` / `TITLE` / `SUGGESTION` / `TRANSLATION` / `MEMORY` / `OCR` |
| `provider_id` / `provider_name` | 提供商 id + 名称快照 |
| `model_id` / `model_name` | `Model.id`（UUID，用于按当前设置解析展示名）+ 展示名快照（模型删了也能显示） |
| `assistant_id` / `conversation_id` | 归属（**只为排查**，统计不按它们分组——它们都可能已被删除） |
| `prompt_tokens` / `completion_tokens` / `cached_tokens` | 本次请求的用量 |

**不加外键**：模型/助手/会话删了，流水必须还在。这也正是「真实花费」的定义。
索引只有 `created_at` 一个——读侧全部是区间聚合。

## 3. 记录点：包一层 provider

`data/ai/UsageRecorder.kt` 用**委托包装**（`Provider<T> by delegate`）实现：

```kotlin
usageRecorder.wrap(provider, UsageTag(source, assistantId, conversationId))
```

- `generateText`：拿到 `MessageChunk` 后记一行；
- `streamText`：在 `flow` 里累积 `chunk.usage`（`TokenUsage?.merge` 取非零增量，兼容「末块给一次」和
  「每块累加」两种供应商行为），**`finally` 里记**——中途取消/上游抛错时已经产生的用量同样是真金白银；
- 用量为空（`prompt + completion == 0`）不记，免得流水里全是 0 行；
- 写库在 `AppScope` 上异步、`runCatching` 兜底：**统计失败绝不影响对话本身**；
- embedding / 图片生成 / 余额查询原样透传，不记（它们不返回 token 用量）。

各来源的入口：

| source | 入口 |
|---|---|
| `CHAT` | `ChatService` → `GenerationHandler.generateText`（默认 tag；含多步工具调用、每次重试） |
| `SUBAGENT` | `SubagentRunner.executeRun` → `generateText(usageTag=…)` |
| `COMPRESSION` | `ChatService.compress`（`providerHandler.generateText`，可能并发多块） |
| `TITLE` / `SUGGESTION` | `ChatService.generateTitle` / `generateSuggestion` |
| `TRANSLATION` | `GenerationHandler.translateText`（消息翻译 + 翻译页共用） |
| `MEMORY` | `MemoryConsolidationGenerationAgent` |
| `OCR` | `transformers/OcrTransformer.performOcr` |

`GenerationHandler` 是**所有 provider 请求的唯一出口**，所以聊天侧的包装放在
`providerImpl` / `fallbackProviderImpl` 创建处：主模型与 fallback 模型各自被包，重试的每次尝试都独立记一行。

> 重试也记：那些请求**已经发出去并计费**，只记最后一次会系统性低估花费（这正是上游的设计取舍）。

## 4. 迁移与回填（43 → 44）

手写迁移 `data/db/migrations/Migration_43_44.kt`（不是 `AutoMigration`——它带**数据回填**）：

1. `CREATE TABLE usage_record` + `CREATE INDEX index_usage_record_created_at`；
2. 从 `message_node.messages` 回填：每条 `role = 'assistant'`、带 `usage` 对象、token 非零的消息记一行；
3. **所有分支都回填**（`json_each` 展开数组，不加 `j.key = select_index`）——被丢弃的重生成版本也真的花过钱；
4. 时间：`substr(createdAt, 1, 19)` 截到 `yyyy-MM-ddTHH:mm:ss`（毫秒尾巴会让 SQLite 日期函数解析失败），
   用 `strftime(..., 'utc')` 当本地时间换算成 epoch；解析失败退回会话 `create_at`；
5. 损坏的 `messages`（`json_valid` 为假）按 `[]` 处理——一行坏数据不能让整个升级失败；
6. 回填行的 `source` 统一记 `CHAT`（旧数据分不出来源），`provider_id` 为 NULL。

`AppDatabase.version = 44`；迁移同时注册在 `DataSourceModule`（主库）与 `LegacyBackupConverter`（旧备份转换库）。

## 5. 统计页结构

`ui/pages/stats/StatsVM.kt` + `StatsPage.kt`，**打开页面不再触碰消息 JSON**（只 `COUNT(*)` 数节点）：

1. **热力图**：近 52 周，两个指标——「请求数」（`source=CHAT` 的请求，走 `created_at` 索引）与
   「Token 数」；四分位分档（只看相对活跃度，绝对值由下面的合计给）。
2. **总览**：总对话数 / 总消息数（节点数）/ 启动次数。
3. **用量**：范围选择 `7 / 30 / 90 / 全部`（全部按月分桶，其余按天补 0）。
4. **区间合计**：输入 / 输出 / 缓存 / 缓存命中率。
5. **趋势柱图**：Canvas 绘制，可点选某天/某月，顶部读数行显示该桶的输入/缓存/输出。
6. **排行**：按模型（token 总量 + 占比，超 8 项并「其他」）、按来源（8 个 `UsageSource`）。

## 6. 与上游的差异（有意为之）

| 项 | 上游 | 本 fork | 理由 |
|---|---|---|---|
| `provider_name` 列 | 无 | 有 | fork 的排行一直显示提供商名；名称快照同样在提供商类型被删后仍可读 |
| `SUBAGENT` 来源 | 无（子代理一并算 CHAT） | 有 | fork 有无头子代理 run（可能跑几分钟），必须能单独看到它烧了多少 |
| 热力图指标 | 只有「请求数」 | 「请求数 / Token」两档 | fork 原本就有双指标，且 token 分桶现在只是 `strftime` 聚合，很便宜 |
| 启动次数 | 保留 | 保留 | 与上游一致（fork 原有卡片） |
| **原「实际消耗 / 当前对话」二口径切换** | — | **删除** | 流水是**按请求**记的，「每节点只取选中分支」这个口径在流水里不存在（也没有意义：重试和后台请求不属于任何可见消息）。要可见对话量就往下看消息数，要花费就看流水——两个问题分开回答，而不是让同一个数字在两种口径间跳 |

## 7. 已知边界

- **流式中途取消**：记的是取消前 provider **已给出的** usage；有些供应商只在末块送 usage，此时取消就是 0 → 不记（无法凭空估算）。
- **embedding / 图片生成 / 余额查询**不记（协议里没有 token 用量）。
- **回填行的来源一律 CHAT**：升级前的历史无法区分标题/压缩等后台请求的占比，来源排行只对升级后新产生的数据完整。
- **回填是按消息记而不是按请求记**：多步工具调用的一步一条助手消息 ⇒ 粒度基本对齐；但同一消息若发生过浏览器侧重试合并，回填只有合并后的总量。
- 「请求数」热力图含重试与多步调用，**不等于对话轮数**——这正是它作为活跃度刻度的含义，页面上不做额外解释。

## 8. 验证

- 单测 `UsageRecorderTest`：单次/流式/取消/空用量/两次尝试/DAO 失败不影响请求。
- 仪器测试 `UsageRecordDAOTest`：区间过滤、按天/按月分桶（本地时区）、按来源计数、按模型/来源排行。
- 仪器测试 `Migration_43_44_Test`：43→44 schema 校验 + 回填数值/字段/多分支/全 0 跳过。
- 真机：debug 包从 43 升级，`usage_record` 出现回填行；发一轮对话后新增 `CHAT` 行；打开统计页秒开。

## 关联

[docs/upstream-analysis.md](../upstream-analysis.md) · [design-ui-yuihub-inspired.md](design-ui-yuihub-inspired.md)
