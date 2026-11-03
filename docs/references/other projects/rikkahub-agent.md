# rikkahub-agent（ExTV/rikkahub-agent）借鉴评估

> 评估：**2026-08-28**（原 §一 / §三 中的 rikkahub-agent 部分）。许可证 AGPL-3.0（与本仓一致）。
> 定位：与 RikkaHub **同源**的 Kotlin 项目（基于上游 `2.4.10`），Agent 化改造；**摘取成本最低**的一个样本。
> 本地参考副本：`<work>\projects\rikkahub-agent-src\`。
>
> 拆分说明：本文件对应原 §一 的 rikkahub-agent 部分与 §三 的第一个来源；同节中的 SoLab 部分见 [`solab.md`](solab.md)。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 一、它是什么

### rikkahub-agent：Agent 化的 RikkaHub

README 自述 "Your phone, automated"——把 RikkaHub 改成端侧 Agent：80+ 设备工具、AI 编写的工作流（类 Tasker）、定时任务、AI 驱动的内置浏览器、Linux workspace、SSH、屏幕自动化、Telegram Bot 遥控、子 Agent。相比 stock 多出 446 个类，主要新功能域：

| 域 | 内容 |
|---|---|
| `data/agentrun/` | 后台 Agent 运行账本（Room），五入口共用：Cron/工作流/子Agent/Telegram/外部自动化 |
| `workflow/` | 触发器（20 种，密封类）→ 条件（13 种）→ 动作（复用本地工具体系）引擎，含 cooldown/每日上限 |
| `subagent/` | SubAgentProfile（Agent 卡片）/Request/Run 三层，maxTrips + timeout 双限制 |
| `browser/` | WebView 双模（前台可见 / Agent 无头共用同一 profile），18 个 browser_* 工具，Readability 正文提取 |
| `service/` | 无障碍设备操控（迭代式树遍历 + takeScreenshot + 手势串行化）、通知监听转发、悬浮窗、DirectMode 批量动作执行 |
| `data/ai/` | ContextBudgetPlanner、LoopGuard、上下文压缩（摘要 + tailStartNodeId）、AIRequestInterceptor |
| `data/telegram/` | Telegram Bot 长轮询（自愈式卡死监控三档决策） |
| `data/codex\|gemini\|grok/` | 三家对称的 OAuth 账号体系：KeyStore AES-GCM 加密凭据 + 多账号 + 用量窗口跟踪 |
| `data/db/fts/` | FTS5 + `simple` 中文分词词典的消息全文检索 |
| `skills/` | Skill 目录 + FastPathRouter + **ToolNameTranscoder**（Claude Code 工具名 → 本地工具映射） |

## 二、值得借鉴的机制（源码级验证）

### 来自 rikkahub-agent（Kotlin，与 RikkaHub 同源，摘取成本最低）

1. **ContextBudgetPlanner**（152 行 + 单测，`data/ai/ContextBudgetPlanner.kt`，无依赖）：**usage 锚点估算**——取最后一条带真实 API usage 的消息作锚点（含 provider 系统开销），只对尾部增量启发式估算（ASCII 3 字符/token、媒体 1024）；锚点后追加的工具输出单独估算（provider 报 usage 时工具结果尚未计入）；压缩后走全量估算（锚点属压缩前请求）；`chooseTailStartIndex` 按目标 token 选保留尾部（保底最近 4 条）。
2. **LoopGuard**（`data/ai/GenerationHandler.kt` 内 object + 单测）：按工具语义设新鲜 TTL（`get_time_info`/截图 5s、存储信息 60s），只读观察工具白名单豁免"连续观察段"，仅**同一工具+参数签名在 TTL 内重复 ≥3 次**才停。误杀率极低，可整体移植。
3. **上下文压缩模型**：`ConversationCompactionEntity{summary, tailStartNodeId, sourceEndNodeId, summaryModelId, isAuto, sourceTokenEstimate}`——按节点 ID 定位（消息树下安全），**原始消息不删只换视图**，`newlyCreatedAutoCompaction` 让生成层感知"这轮截断过"。阈值双模式（窗口百分比 / 绝对 token）。
4. **AgentRun 运行账本**：五入口共用一张 Room 表，`awaiting_approval` 是一等持久化状态，**`process_lost` 终态**——重启后把搁浅运行诚实标记并发通知，而非静默丢；`parentRunId` 支持父子链；仓库层单 Mutex 串行写。
5. **工具审批模型**：约 120 工具默认 `ALWAYS_ASK` + **`NO_ALWAYS_ALLOW` 高危子集**（eval_js/装技能/解密/NFC 写入/授权目录等 10 个禁止"永久授权"）+ 会话内白名单（`chatId::tool`）+ 无头会话自动审批持久化（SP）。
6. **多策略降级 Edit 工具**：`ExactReplacer → line_trimmed → block_anchor` 三级匹配（容忍缩进漂移/锚点块），命中多处报错并教模型"加上下文或 replace_all"——错误文案即修复指令；`ReplaceTextResult` 回传实际策略供模型自省。
7. **ChatGenerationForegroundService 握手**：`shouldRun` AtomicBoolean + `CompletableDeferred` readiness 门闩 + WakeLock——先起 FGS、await ready、再开流式 socket（`startForegroundService()` 不保证 Home 后的时序）；stale stop 与新 Send 竞态靠共享标志变 no-op；被 OEM 杀死时 `markUnavailable()` 让等待方立刻感知。
8. 小件：工作流 4 种 `SKIPPED_*` 语义状态（可观测"为什么没跑"）；FTS5 + simple 中文分词；Telegram 轮询三档自愈（重启循环 → 升级告警）；三家对称 KeyStore 加密 OAuth（Codex 伪装 CLI 的 PKCE localhost 回调 / Grok 设备流）。

## 三、2026-08-22 → 09-24 增量评估（2026-11-03，`e8f10b70..a88f5854`，169 提交）

`e8f10b70`（上次评估点 08-22）→ `a88f5854`（09-24，`2.5.1-agent.1`）。`--no-merges` 169 条中 **129 条为上游 rikkahub 提交**（re-ovo 120 条里 73 条在 `upstream-analysis.md` 被直引，余 47 条落在批次表/版本类；其他作者另有 5 条直引），按「上游已评估」不重复；**ExTv 自有 35 条**为本轮新面（全部读 stat，20 条精读 patch）。总评：**吸收 3 / 候选 6 / 已有等价 5 / 不适用 12 组**。

### 吸收（3）

| 提交 | 内容 | 本仓落点 |
|---|---|---|
| `349f2908f` | 流式解码器 image data URI 改 mime 无关解析，非 data/http 跳过而不抛 | `ai/.../ChatCompletionsStreamDecoder.kt:142-143` 仍是 `require(data:image)` + png 硬编码 substring——中转返 https 图会**整轮生成中断**；非流式 `ChatCompletionsAPI.kt:913-937` 已于 10-16 修好同两因，流式漏了 |
| `e1a51b93e` | 渐变背景 30fps 限速时间源 + 绘制期读相位 + `STARTED` 外暂停 | `ui/pages/chat/MeshGradientBackground.kt:40-55` 与修前逐字相同；需与 BiliPai `ReduceMotion` 一起做（自驱循环不再自动遵循系统减弱动效） |
| `f85ab6670`（前半） | 标题/建议生成 `AuxJobRegistry` 按会话单飞，新生成/重生成前取消在飞请求 | `service/ChatService.kt:2195-2201` 现为裸 fire-and-forget，连点会重复请求、顶掉本地 prompt cache |

### 候选（6）

`f088df5a1` 工具结果图片整批 lift 到 `role:"user"`（本仓 `ChatCompletionsAPI.kt:646-655`/`:817-865` 仍把图塞进 `role:"tool"` 数组 content，部分 provider 400；中）· `1913333a6` 被中断的 Auto 工具不重跑（本仓 `UIMessagePart.Tool.startedAt` 已有字段但无消费方；需先确认进程重启后是否真的恢复工具循环）· `a6b25c624` 标题兜底为首条用户首行（本仓失败走错误卡、标题保持空，空标题会让重试反复触发）· `9c91af7f8` 子代理 `no_result`（本仓终稿仍进父上下文；后台通知也要同步抑制）· `83e6a6f84` 单图模型逐张调用（本仓已走 `/images/generations`，缺口仅 `n>1` 与宽高比）· `f85ab6670` 后半插件类 `onGenerationFinish` 在停止时执行（本仓停止落库已手工补齐 `finishReasoning`/base64 落文件，插件 transformer 仍不跑，P3）。

### 已有等价（5）

同批 Auto 工具执行 / 取消上抛（`GenerationHandler.kt:237-238`/`:1063`）· MCP 工具名集中（`McpNameSanitizer.kt:53-70`）· 模型名含 `/` 不拼文件名（`GeneratedImage.kt:27,51`）· 压缩重放 / 合成摘要身份 / 压缩模型回退（`Conversation.kt:177-230`、`PreferencesStore.kt:1693`）· 停止落库（`ChatService.kt:3446-3465`）。

### 不适用（12 组）

shizuku 三件 · termux 两件 · 设备工具（本仓无无障碍设备工具体系）· Gemini Code Assist/Antigravity（本仓无 Cloud Code 通道）· DB 30→31 迁移（本仓迁移体系分叉，且有 staged restore 启动 gate）· zip 中文条目名（本仓只读自产归档）· 内置技能删除记录（本仓内置技能只读）· 文件 move/copy 流关闭（本仓无对应文件管理器工具）· 工作区导出 500 条上限 · MCP 无工具可调（本仓命名/状态已集中）· i18n/发版/docs · 前端 web-ui 样式两条（本仓 web-ui 版本分叉）。
