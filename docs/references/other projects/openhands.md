# OpenHands（OpenHands/OpenHands + OpenHands/software-agent-sdk）评估 —— 上下文管理 / 净化

> 对应总表 §一 #16。深度：**源码**（本地双仓克隆 `F:\build\projects\OpenHands`（前端产品层）与
> `F:\build\projects\software-agent-sdk`（condenser 引擎算法层），2026-11-18 逐文件读核验）。
> 基准提交：OpenHands `9590adf0a`（2026-11-17）、software-agent-sdk `06f643ca4`（2026-11-17）。
> 许可：两仓均 **MIT**（`OpenHands/LICENSE`、`sdk LICENSE`）——宽松度高于本仓常引的 AGPL 伙伴，
> 但本次仍**只取设计不摘代码**（Python 引擎 ↔ Kotlin 客户端，代码可移植性本就 ≈ 0）。
> 本仓对应的自身历史背景：长会话压缩体系已三阶段落地（pathNodes 索引缓存 → 摘要轻量投影 + 稀疏加载 →
> 保存契约显式 deletedNodeIds），压缩前缀物理清理五道守卫已上线。
> [返回索引](../../peer-apps-benchmark.md)

## 一句话结论

OpenHands 的价值**不在压缩架构**（View/事件重放与本仓检查点 + 摘要投影已同构互证），而在三件本仓缺的
**工程防线**：①压缩边界不得切开工具调用等原子单元的 manipulation_indices 安全边距体系；
②provider 报上下文溢出时自动触发压缩重试的反应式兜底；③结构化摘要模板。

## 架构背景（读仓所得，防二手误传）

OpenHands 主仓 2025 底已重构为 TypeScript 前端，**condenser 引擎本体全部在独立仓 software-agent-sdk**
（Python，`openhands-sdk/openhands/sdk/context/`）。前端只保留产品层：压缩事件协议、计量可视化、
手动压缩 UX、脱敏兜底。评估算法层必须看 SDK 仓，主仓没有 keep_first / max_tokens 等任何策略实现。

## SDK 引擎核心设计（`openhands/sdk/context/`）

**1. 全量历史 + 派生 View**：事件流永远全量持久化，`View.from_events()` 重放事件流派生「LLM 可见投影」；
`Condensation` 事件携带 `forgotten_event_ids` + 可选 `summary` + `summary_offset`，重放时删被遗忘事件、
插摘要。压缩结果本身是流里的一等事件——与本仓「压缩检查点 + 摘要轻量投影」设计同构（互相印证，
无需移植）。

**2. 三类触发原因，软硬分级**（`condenser/llm_summarizing_condenser.py`）：

| Reason | 条件 | 级别 | 行为 |
|---|---|---|---|
| REQUEST | 用户/agent 显式请求 | HARD | 必须压缩，失败降级 |
| TOKENS | 超 `min(condenser.max_tokens, agent LLM 有效输入上限)` | HARD | 同上 |
| EVENTS | 事件数超 `max_size`（默认 80） | SOFT | 不可压缩先用未压缩视图顶住，下轮再试 |

HARD 失败自动进 `hard_context_reset`：全量摘要重置，最多重试 5 次、每次把单事件截断长度乘 0.8 递减。
多原因并存取最严格（保留最小后缀）。

**3. 压缩目标量化**（`condenser/utils.py`）：事件数触发 → 保留后缀 `max_size//2 - keep_first - 1`
（压到一半）；token 触发 → **二分搜索最短前缀**使删除量恰好「回到阈值一半」。`minimum_progress`
（默认 10%）门槛：遗忘事件少于视图 10% 判「无可用压缩」，省一次无效 LLM 调用——与本仓曾踩的
「压缩边界后没有可保留的原文」错误同域，但这里是设计级门槛而非事故。

**4. manipulation_indices——原子单元安全边距**（`view/properties/`，**本次评估含金量最高的一条**）：
四个独立性质（ToolCallMatching / BatchAtomicity / ObservationUniqueness / ToolLoopAtomicity）各自
派生「可安全删除的下标集合」取交集；删除区间起止 snap 到合法下标；事后 `enforce_properties` 迭代兜底
（清孤儿 tool_call / 重复观察直到 view 稳定）。关键洞见：**压缩边界落在 tool call 与其 result 之间
会直接构造出坏请求**（Anthropic 等协议强制 tool_use/tool_result 成对）。
→ 本仓缺口：压缩五道守卫只验证摘要路径 / 保留集 / 指纹，**没有「边界不得切开工具调用配对、
推理签名块、分段原子单元」的检查**。分支会话里边界恰好落在配对中间是真实风险。

**5. 反应式错误恢复**（`agent/agent.py` 两处 `context window exceeded` 分支）：LLM 抛上下文溢出 →
注入 `CondensationRequest` 事件 → 下一轮自动压缩后重试；malformed history 先 `rebuild_view()` 再
请求压缩。→ 本仓自动压缩是每轮后台预执行（预应式），**缺 provider 报溢出时的自动压缩重试兜底**。

**6. 压缩 LLM 与对话 LLM 解耦**：condenser 独立持 LLM（摘要调用强制关流），agent LLM 仅用于 token
计数。比本仓「压缩固定低推理档」更彻底——压缩模型完全独立可配。

**7. 结构化摘要模板**（`condenser/prompts/summarizing_system.j2`）：固定结构
USER_CONTEXT / TASK_TRACKING（**强约束保留任务 ID 与状态**）/ COMPLETED / PENDING / CURRENT_STATE，
代码场景追加 CODE_STATE / TESTS / CHANGES / VERSION_CONTROL_STATUS；头尾有 `prior_history` /
`events` 分段（[summarizing_events.j2] 内层循环包 `<EVENT>` 块）。

## 前端产品层设计（OpenHands 主仓，`src/`）

**1. 手动「压缩上下文」完成判定协议**（`use-compact-context-action.ts` + `use-await-context-compaction.ts`）：
- 发请求**前**先快照 `per_turn_token` + 事件 id 基线（防竞态：POST ack 可能晚于 Condensation 事件到达）
- 订阅事件流等 Condensation 落地，再等 metrics 稳定 2.5s 确认 token 实际下降
- **三态结果**：`compacted`（token 确实降）/ `no_change`（事件到了但没省）/ `timeout`（90s 无事件 =
  **失败**而非无操作）；完成后 toast 报「节省 X token（前 Y → 后 Z）」
- ⚠️ 勘误（2026-11-18 复核）：早前口头评估称「90s 超时」，源码常量实为 **60s**（`COMPACT_TIMEOUT_MS`）

**2. 上下文窗口实时计量**（`use-context-window-usage.ts` + `context-window-ring.tsx`）：
`per_turn_token / context_window` 从 usage 取数，输入框旁环形进度条 neutral / warning / danger
双阈值变色，计量弹层内嵌「压缩上下文」按钮（计量告警与压缩入口同位）。

**3. 脱敏双层防线**（`utils/redact-custom-secrets.ts`）：后端已在流中掩码密钥，前端再做一层**兜底
再脱敏**——只处理 `<CUSTOM_SECRETS>` 块内 `KEY: value` 行，**闭合标签缺失（截断块）也照样脱敏不泄漏**。

**4. 压缩配置挂 agent profile**：`enable_default_condenser` + `condenser_max_size`，前端
`condenser-settings.tsx` schema 驱动渲染——与本仓「压缩设置偏全局」对照。

## 落地清单（2026-11-18 评估；2026-11-21 起分批实施）

| # | 项 | 优先级 | 备注 |
|---|---|---|---|
| 1 | **压缩边界原子单元守卫（守卫六）**：删除区间不得切开 tool call↔result 配对、推理签名块、多段拆分单元；实施前对边界做「原子区间」校验，任一切开则边界 snap 或放弃 | **P2** | ✅ 已实施（`470253ee4` 阶段一）：`ContextCutSafety.alignCutIndex` + planner/limitContext/中途压缩/守卫六四接入点 |
| 2 | **上下文溢出反应式兜底**：provider 报 context window exceeded / 长度类错误时自动触发一次压缩再重试（限一次，失败透传原错误） | **P2** | ✅ 已实施（`e8b9a1fed` 阶段二）：`isInputContextOverflowError` + runLoop 恢复编排 + ChatService 立即落盘闭包 |
| 3 | **结构化压缩摘要模板**：摘要 prompt 改 USER_CONTEXT / TASK_TRACKING / COMPLETED / PENDING / CURRENT_STATE 分段，强约束保留任务/收藏等 ID | P3 | ✅ 已搭车（`e8b9a1fed`）：默认模板已八段结构化，收窄为 Rules 补任务状态迁移强约束 |
| 4 | **压缩完成报告协议**：前置 token 快照 + 压缩后对比 + 三态结果（成功/无变化/失败），toast 报节省量 | P3 | ✅ 已实施轻量版（同日第四笔提交）：`CompressionSavings`（前后同口径估算）+ `onSavings` 回调——手动压缩转 toast「估算节省 X token（Y→Z）」、轮前自动压缩失败可见（addError）、全路径 Log；timeout 判失败判已有等价不抄；自动压缩成功态保持安静（折叠行+容量条即反馈） |
| 5 | **输入区上下文计量环**：per-conversation context fill 指示（usage 数据链已有）+ 双阈值变色 + 满载跳压缩入口 | P3 | ✅ 关闭（2026-11-21 判已有等价，不移植）：`ContextUsageRingButton` 圆环+整数百分比（per-conversation）+ 80% 阈值变红（`CONTEXT_USAGE_WARNING_RATIO`，与面板/标题读数同常量）+ 点击跳容量面板（内嵌「压缩上下文」入口）——四要素真机均已验；OpenHands 双阈值的 danger 第二色不取（单阈值 + error 色已覆盖警告语义，确有需要再议） |
| 6 | **净化兜底层**：压缩 prompt 侧与渲染侧各一层密钥再脱敏（截断容忍正则） | P3 | ✅ 已实施（`design-redaction-pipeline.md`，in-progress；拍板五项收齐：混合判定/仅压缩+导出侧/与 NMoi 待办④ 合并/仅导出+默认开/trace 计数进入；压缩侧+导出+Web API 全接线，NMoi ④ 一并收官） |

**明确不做**：
- **View/事件重放架构**——本仓消息树 + 检查点模型已等效覆盖，换架构是纯回退；
- **condenser 独立 LLM 配置**——本仓「固定低推理档」已是其特例，全独立配置属过度设计（可并入 #2 顺带考虑）；
- **前端 condenser 设置面（enable_default_condenser / max_size per-profile）**——本仓压缩配置粒度已够，等 #2 落地后按需再看；
- **Memory notes 蒸馏路线**（`.openhands/memory/`）——长期方向，与本仓助手记忆重叠，暂不动。

## 与既有台账的关系

- **billion-context.md §核心**（「单 checkpoint 替换式压缩是结构性反模式」）：OpenHands 恰好证明
  追加式事件流 + 派生视图在一线产品里可工程化——但本仓已用检查点 + 稀疏加载走完三阶段，
  两份评估合读的结论是：**架构不改，防线要补**（即上表 #1/#2）。
- **yuihub.md**（工具循环上下文闸）：与本仓 #1 同域，OpenHands 的 manipulation_indices 是更系统的
  下标级方案，落地 #1 时可合读。
- 十六源克隆注意：OpenHands 主仓 ~727MB / 25.8 万对象，ghfast.top 可下；SDK 仓 79.5MB，
  gh-proxy.com 可下（ghfast.top 当日对它卡 0 字节）。两仓均绕过本机 127.0.0.1 代理直连镜像。

## 2026-11-18 增量备忘

- 基准推进：OpenHands `9590adf0a` → **`6fa7686d3`**（+1：`fix(automations)` webhook 源校验恢复——automations 域外，不适用，无增量待评）；software-agent-sdk 仍 `06f643ca4`，零新增。
- SDK 仓还有 `test_agent_context_window_condensation.py` 等触发链测试，落地 #2 时可对照其
  分类判定（何时算「长度类错误」）抄判据。

## 2026-11-21 实施备忘

- #1/#2/#3 已随两笔本地提交落地（`470253ee4` 阶段一安全切点+守卫六、`e8b9a1fed` 阶段二溢出恢复+#3 搭车），单测全量绿；**真机验证已收官（2026-11-21 debug 包）**：溢出恢复 mock 全链路实弹（400→压缩→checkpoint→重试→savings 3817t→回复落库）、遗留配对+prune 实弹（removed 9/13、配对不变量成立、会话完好）。实施记录与语义定案见设计稿 [`design-context-boundaries-and-overflow-recovery.md`](../../design/in-progress/design-context-boundaries-and-overflow-recovery.md) §7/§8。
- #5 关闭登记（2026-11-21）：判已有等价，不移植——计量圆环/80% 变红/容量面板/压缩入口四要素本仓已在位且真机可见；OpenHands 双阈值里的 danger 第二色属外观微调，明确不取（需要时单议）。
- #6 已立项 [`design-redaction-pipeline.md`](../../design/drafts/design-redaction-pipeline.md)（与 NMoi 待办④ 合并），拍板：标记块约定+高置信正则白名单混合、仅压缩 prompt 侧、合并立项。
- 溢出判据落地时对照过 SDK `classifier.py`（`LONG_PROMPT_PATTERNS` 8 条全量移植）；`MALFORMED_HISTORY_PATTERNS` 存档于 `GenerationErrorPolicy.kt` 注释不进 v1。
- #4 轻量版已实施（同日第四笔提交）：完成报告 = 手动压缩 toast + 轮前失败可见 + 全路径节省量 Log；三态中的「timeout 判失败」判已有等价（手动压缩是直接 suspend 调用，失败本来就抛）。
- #6 已实施（同日第三笔提交）：`Redactor` 判定层 + 压缩侧双落点 + 导出三出口 + Web API 三端点；NMoi 待办④（内容级脱敏，仅导出/备份）随之收官——两线合并结算。
