# 整轮折叠（思考 + 执行过程收进一个可展开块）

> 状态（2026-10-17）：**设计定稿，待实现**。用户已拍板全部取舍（见 §2）。
> 参考实现：`Yuihub` 的 `ui/components/message/ChatMessageCot.kt` + `ui/components/ui/ChainOfThought.kt`
> —— 它已被真实模型（Qwen 的空白文本）打磨过，**结论是照搬规则、不改组件**。
>
> 相关：`design-memory-manual-order.md`（同为 UI 交互类）、
> `roadmap.md` 2026-10-17「降低操作与发现复杂度」待办。

## 1. 要做什么

把一条 assistant 回复里的 **思考 + 工具调用 + 过程中的解说文字** 收进**一个**可展开块，
只留**最后一次工具调用之后的文本**作为最终回答。

## 2. 已定的取舍（用户拍板）

| # | 决策 |
|---|---|
| 1 | **B 方案：首尾包住** —— 过程 part 之间的解说文字一起折叠，只留末尾"最后一段连续文本" |
| 2 | **模型以工具调用结尾（没有尾随文本）⇒ 整条消息不折叠**（没有"回答"可留，折叠只会把全部内容藏起来） |
| 3 | **生成中展开、结束后按设置收起**（复用现有 `loading` 时序） |
| 4 | **整轮折叠开时，`autoCloseThinking` 置灰**，并显示「由整轮折叠接管」 |
| 5 | 折叠状态用 **`rememberSaveable` / hoisted**（`remember` 会被 `LazyColumn` 滚动销毁） |
| 6 | 折叠头摘要行带 **「中间有几段说明」**（例：`3 步 · 2 工具 · 2 段说明`），提示里面还有东西可看 |
| 7 | **错误常驻红色标记，但不强制展开**；只在生成中自动展开 |
| 8 | **`isPending` 步骤强制展开，且把整链的 `canCollapse` 置 false**（整块不收） |
| 9 | **`generate_image` 切断思考链**（同 `chart_display`） |

## 3. 现状：基础设施已在，缺三条规则

本仓**已有** `ui/components/message/ChatMessageCot.kt`（83 行）、`ChainOfThought.kt`，
且 `chart_display` / `ChartBlock` 早已移植（`ChatMessage.kt:446-449`、`BuiltinToolUIs.kt:636`）。

| 缺失 | 现状 |
|---|---|
| `lastToolIndex` 切分 | 无 —— 所有 `Text` 一律进 `ContentBlock` |
| `NarrationStep` | `ThinkingStep` 只有 2 个变体，没有"过程解说" |
| `ServerToolStep` | `ServerTool` 落到 `else` → 渲染成正文常驻状态条（`ChatMessage.kt:661-697`） |
| 空白文本跳过 | 空白 `Text` 变成 `ContentBlock` → **把链撕成两块**（参考仓注释里记下的坑） |

## 4. 移植规则（来自 Yuihub `groupMessageParts()`）

1. `lastToolIndex = indexOfLast { it is Tool || it is ServerTool }`
2. `Reasoning` / `Tool` / `ServerTool` / 落在 `lastToolIndex` **之前**的 `Text` ⇒ 并入当前 `ThinkingBlock`
3. `Text` 在 `lastToolIndex` **之后** ⇒ `flush` + `ContentBlock`（最终回答）
4. **`Text.isBlank()` ⇒ 直接跳过，既不渲染也不参与分组**（否则 Qwen 会把一条链撕成多块）
5. 其它 part（Image/Video/Audio/Document/已废弃类型）⇒ `flush` + `ContentBlock`
6. 收尾再 `flush` 一次
7. `chart_display` 成功 ⇒ `flush` + `ChartBlock`（切断链）；生成中/失败 ⇒ 仍是 `ToolStep`

**照搬 vs 需改**：
- 可直接照搬：规则 1–6、`NarrationStep` 类型
- 需适配：`ServerToolStep`（本仓 `ServerTool` **无 `isFinished`、无 `FAILED`**，用 `status != IN_PROGRESS`）
- 需适配：删掉正文里的 `ServerTool` 内联块（`:661-697`）及随之失效的 3 个 import
- **不要改** `ChainOfThought` 组件本体（本仓是 Card+时间线，参考仓是扁平流程行 —— 别为了"照搬"把样式改回去）

## 5. 本仓特有的四条硬要求（参考仓没有）

### 5.1 审批步骤不能被折叠吃掉（**最严重**）

待审批按钮在步骤的 `extra` 里（`ChatMessageTools.kt:141-182`），而链折叠只保留 `takeLast(2)`。

> 工具 A（**待审批**）→ B → C，`steps.size=3 > 2` ⇒ **A 被隐藏 ⇒ 审批入口消失 ⇒ 回合静默卡住**。

本仓比参考仓更危险（还多一个 `onApproveAll`）。
**实现**：分组/渲染时检测存在 `isPending` 的 step（以及 `ask_user`）⇒ **整链 `canCollapse = false`**，并强制展开。

### 5.2 角色守卫

`groupMessageParts()` 目前无 `role` 参数，而本仓**对 USER 消息也调用**。
若某条用户消息同时含 `Text` + `Tool`（导入器路径），**用户自己的话会被折进折叠块**。
**实现**：加 `role == MessageRole.ASSISTANT` 判断，否则全部走 `ContentBlock`。

### 5.3 `generate_image` 切断链

否则生图前常伴随搜索/读文件，`steps.size>2` 时**生成的图被 `takeLast(2)` 隐藏**，本次交付物看不见。
**实现**：把 `ChartBlock` 泛化为 `ToolCardBlock` + 谓词（`ToolUIRenderer.cutsChain(): Boolean = false`，
`ChartDisplayToolUI` 与 `GenerateImageToolUI` 返回 true）。成本约 +10 行，避免在 `when` 里堆 `if`。
注意：切断链**不影响** `lastToolIndex`（它仍算 `Tool`），两者正交。

### 5.4 `rememberSaveable` / hoisted 状态

本仓与参考仓都用 `remember`，`LazyColumn` 滚出视口即重置。按决策 5 改为 `rememberSaveable`。

## 6. 其它已知问题（实现时顺手处理 / 至少知情）

| 问题 | 说明 |
|---|---|
| **折叠块与回答同色** | 两者都是 `surfaceContainerHigh` ⇒ 折叠后过程块与回答**看起来一样**。建议折叠块改 `surfaceContainerLow` |
| **`Export.kt` 是第二个消费者** | `:1179-1193` 的 `when` 必须同步补两个分支，**否则编译不过** |
| **`isReasoningOnlyBlock` 会恒为 false** | `ChatMessage.kt:403` 的 `fastAll { ReasoningStep }` 在加入解说后失效 ⇒ 纯"思考+解说"块从自适应宽度变满宽。若要保形，改成 `fastAll { it is ReasoningStep \|\| it is NarrationStep }` |
| **位置性 `remember` 错配** | `groupedParts.fastForEach` 无 `key`，展开/收起会重排槽位 ⇒ 步骤级状态按位置错配。建议顺手加 `key(block)` / `key(step)` |
| **`todo_write` 进度被隐藏** | 它是普通步骤，`steps>2` 时进度计数不可见；由输入栏 `PlanBar` 兜底，可接受但需知晓 |
| **复制文本仍含解说** | `ChatMessageCopySheet.kt:94` 拼接所有非空白 Text —— 折叠后**复制仍带解说**，属预期 |

## 7. 明确不做

- **不改 `ChainOfThought` 组件本体**（Card+时间线是本仓形态）
- **不在数据层实现折叠**（不删/不重排 `parts`）—— 会误伤 FTS 索引、导出、复制、压缩输入、`ChatService` 等
- **不做"折叠状态跨会话持久化"**（`rememberSaveable` 已满足"短时"要求）
- **不引入新依赖、无 schema 迁移**

## 8. 工作量

| 类别 | 规模 |
|---|---|
| 改文件 | `ChatMessageCot.kt`（83→~145）、`ChatMessage.kt`、`ChatMessageTools.kt`、`ChatMessageReasoning.kt`、`Export.kt`、`ToolUI.kt`（`cutsChain`） |
| 新增 | `ChatMessageThinkingSteps.kt`（可选，避免两个大文件继续膨胀）+ 1 个测试文件 |
| 总计 | **6–7 文件 / +250~350 行**，无 schema、无迁移、无新依赖 |

## 9. 测试计划（`groupMessageParts()` 是纯函数，可跑 JVM 单测）

1. 单工具 + 前后文本
2. 无工具纯文本（全部 `ContentBlock`）
3. 工具前后各一段文本（前段折叠、后段保留）
4. **空白文本中断（Qwen 场景）** —— 链不被撕开
5. 顶层 `Image` 夹在工具之间（会撕链，属已知行为）
6. `chart_display` 成功 → `ChartBlock` 且切断
7. `chart_display` 失败/生成中 → 仍是 `ToolStep`
8. `ServerTool` 入链且参与 `lastToolIndex`
9. 连续 Reasoning+Tool+Text 合成**单块**
10. `todo_write` 作为最后工具
11. **`isPending` 存在 ⇒ `canCollapse = false`**
12. **`generate_image` 成功 ⇒ 切断链**
13. **USER 消息含 Tool ⇒ 不折叠**（角色守卫）
14. 摘要统计：步数 / 工具数 / 说明数

## 10. 未验证

- 参考实现的"空白文本跳过"规则来自其注释与真实机型反馈，**本仓未复现过 Qwen 场景**。
- `isPending 强制不收` 与 `生成中展开` 的**时序交互未实测**（生成中 + 待审批同时存在时会怎样）。
- 折叠块换色后的**视觉层次**未设计（只记了问题）。
- `SubagentToolUI` / `WorkspaceToolUIs` 的 `Summary` 是否含"必须常驻可见"的语义**未逐个通读**，若存在同样受 5.1 影响。
- MCP 工具是否会在消息顶层产出 `Image` part **未追到终点**（若会，则触发撕链）。
