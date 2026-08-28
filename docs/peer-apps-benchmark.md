# 同类应用借鉴评估：rikkahub-agent 与 SoLab（结合上游 3a533a6a）

> 生成时间：2026-08-28
> 分析对象：
> - [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)（GitHub 仓库，基于上游 2.4.10，最后提交 2026-08-22；桌面 APK 为其构建产物 + `excp.rikkahub.zhcn` 资源覆盖包汉化）
> - SoLab v2.1.0（MT 论坛分发，基于 [Kelivo](https://github.com/Chevey339/kelivo) 二次开发的「AI 驱动 APK 逆向工作台」；源码包 `F:\solab-2.1.zip`，Flutter + Kotlin）
> - 上游 `3a533a6a` 工作区终端后台运行 + 多 Tab（**已在 master**，特性分支未吸收）
>
> **许可证结论：三个项目均为 AGPL-3.0**（我们仓库根 LICENSE 即 AGPL-3.0），代码级互相摘取许可兼容。
> 本地参考副本：`%TEMP%\rikkahub-agent-src\`（git clone）、`%TEMP%\solab-src\solab-open-source-2.0\`（源码包解压）、`%TEMP%\apk-out\`（两个 APK 的 jadx 反编译产物）。

## 一、两个应用是什么

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
| `data/codex|gemini|grok/` | 三家对称的 OAuth 账号体系：KeyStore AES-GCM 加密凭据 + 多账号 + 用量窗口跟踪 |
| `data/db/fts/` | FTS5 + `simple` 中文分词词典的消息全文检索 |
| `skills/` | Skill 目录 + FastPathRouter + **ToolNameTranscoder**（Claude Code 工具名 → 本地工具映射） |

### SoLab：AI 驱动的 APK 逆向工作台

Flutter（Kelivo 底座：世界书/记忆/指令注入/多 provider）+ Kotlin 逆向引擎（rizin 全套 so + Ghidra Sleigh、Unicorn/Unidbg、Keystone、DexKit、LIEF、xAnSo、Blutter ×19 个 Flutter 版本 runner）。核心流程：选中 APK → 内置助手完成 分析 → 定位 → 修改 → 打包 → 签名；并支持作 MCP server 让桌面 AI 远程调用手机上的逆向能力。附带 20 类去广告规则 JSON（广告 SDK / 会员校验 / 反调试检测特征）与 `apk_mod_entry_*` Playbook 知识书。

## 二、结合上游 3a533a6a 的终端分析

`3a533a6a`（已在 master，特性分支待吸收）：`WorkspaceTerminalSessionManager` 提升为 AppScope 单例、会话生命周期与页面解耦、`SecondaryScrollableTabRow` 多 Tab、`closeWorkspace` 在 rootfs 替换前串行化创建任务。

rikkahub-agent 的终端（`WorkspaceTerminalSession.kt`，336 行）**仍是单会话**（proot rootfs + `/workspace`、`/skills` 绑定挂载 + RootfsPatcher 写 DNS），落后上游一代；且因 Termux `TerminalView` 无公开换绑 API，反编译版可见其直接跨包改内部字段（`mTermSession`/`mEmulator`），属脆弱写法。**结论：多 Tab 底座以吸收上游 3a533a6a 为准。**

其上有三个上游没有的增量值得补齐：

1. **终端内 URL 点击**（上游 7da69770 中 grep 为 0，直接可摘）：`getColumnAndRow` 定位 → `getLineWrap()` 上下扩展软换行还原长 URL（上限 50 行防全 transcript 遍历）→ 宽字符用 `[0, column]` 文本长度精确换算点击下标 → 尾部标点裁剪（`.,;:!?)]}'"`）→ `ACTION_VIEW` 打开。
2. **Agent shell 四重预算**：单命令超时（60s）+ 单轮总预算（600s）+ 最大步数（32）+ stdout/stderr 截断（8KB），配 `workspace_run_background / _status / _kill` 三工具 + `TailBuffer` 输出尾缓冲追踪后台进程。上游 3a533a6a 只解决"页面不杀会话"，未约束"Agent 跑命令失控"。
3. **Termux 双路线失败归类**：内嵌 terminal 库之外有外部 `RUN_COMMAND Intent` 路线，探测命令 + 枚举失败原因（未安装 / 无权限 / `allow-external-apps` 未开 / 超时）。

## 三、值得借鉴的机制（源码级验证）

### 来自 rikkahub-agent（Kotlin，与 RikkaHub 同源，摘取成本最低）

1. **ContextBudgetPlanner**（152 行 + 单测，`data/ai/ContextBudgetPlanner.kt`，无依赖）：**usage 锚点估算**——取最后一条带真实 API usage 的消息作锚点（含 provider 系统开销），只对尾部增量启发式估算（ASCII 3 字符/token、媒体 1024）；锚点后追加的工具输出单独估算（provider 报 usage 时工具结果尚未计入）；压缩后走全量估算（锚点属压缩前请求）；`chooseTailStartIndex` 按目标 token 选保留尾部（保底最近 4 条）。
2. **LoopGuard**（`data/ai/GenerationHandler.kt` 内 object + 单测）：按工具语义设新鲜 TTL（`get_time_info`/截图 5s、存储信息 60s），只读观察工具白名单豁免"连续观察段"，仅**同一工具+参数签名在 TTL 内重复 ≥3 次**才停。误杀率极低，可整体移植。
3. **上下文压缩模型**：`ConversationCompactionEntity{summary, tailStartNodeId, sourceEndNodeId, summaryModelId, isAuto, sourceTokenEstimate}`——按节点 ID 定位（消息树下安全），**原始消息不删只换视图**，`newlyCreatedAutoCompaction` 让生成层感知"这轮截断过"。阈值双模式（窗口百分比 / 绝对 token）。
4. **AgentRun 运行账本**：五入口共用一张 Room 表，`awaiting_approval` 是一等持久化状态，**`process_lost` 终态**——重启后把搁浅运行诚实标记并发通知，而非静默丢；`parentRunId` 支持父子链；仓库层单 Mutex 串行写。
5. **工具审批模型**：约 120 工具默认 `ALWAYS_ASK` + **`NO_ALWAYS_ALLOW` 高危子集**（eval_js/装技能/解密/NFC 写入/授权目录等 10 个禁止"永久授权"）+ 会话内白名单（`chatId::tool`）+ 无头会话自动审批持久化（SP）。
6. **多策略降级 Edit 工具**：`ExactReplacer → line_trimmed → block_anchor` 三级匹配（容忍缩进漂移/锚点块），命中多处报错并教模型"加上下文或 replace_all"——错误文案即修复指令；`ReplaceTextResult` 回传实际策略供模型自省。
7. **ChatGenerationForegroundService 握手**：`shouldRun` AtomicBoolean + `CompletableDeferred` readiness 门闩 + WakeLock——先起 FGS、await ready、再开流式 socket（`startForegroundService()` 不保证 Home 后的时序）；stale stop 与新 Send 竞态靠共享标志变 no-op；被 OEM 杀死时 `markUnavailable()` 让等待方立刻感知。
8. 小件：工作流 4 种 `SKIPPED_*` 语义状态（可观测"为什么没跑"）；FTS5 + simple 中文分词；Telegram 轮询三档自愈（重启循环 → 升级告警）；三家对称 KeyStore 加密 OAuth（Codex 伪装 CLI 的 PKCE localhost 回调 / Grok 设备流）。

### 来自 SoLab（理念为主，Kotlin 侧可直接参考）

1. **工具 schema 三档加载**：`ToolLoadPolicy{none/light/full}` + 纯规则域路由（`tool_router.dart`，零额外 LLM 调用）——闲聊不携带工具 schema；`get_tool_map` 元工具两级懒查（目录 → 单工具详查 → LRU 缓存，按 assistant 启用集做 cacheKey）。本地工具超过 20 个后这套决定可用性。
2. **工具结果外置 + 反翻页**：超长结果截 16KB 预览 + resultId，`get_tool_result` 续读**仅一次**（offset 必须 == previewChars），再翻页报 `paging_blocked` 并指示"回原工具用更窄 query"——把长结果 token 成本压到常数级。
3. **previewToken 变更审批**（~200 行，`apk_mutation_preview_service.dart`）：dryRun 发证（operation + 参数指纹 = 去掉控制参数后的 canonical JSON，30 分钟过期）→ 执行验签 → 指纹不匹配返回 expectedArguments 让模型恢复原参数（防"批准 A 执行 B"）→ 写成功后失效同产物旧 token。对任何破坏性工具通用。
4. **ToolCallLoopGuard**（~150 行可逐行翻译）：滑动窗口（12）存 `工具名|参数指纹`；polling 豁免；**写成功后 `advanceState()` 清空读历史**（状态推进语义）；与预算状态机（四阶段 token 上限）联动，预算耗尽返回**结构化 ask_user 载荷**申请扩容而非死错误。
5. **KeepAliveService 保活四件套**（479 行 Kotlin，注释即 ROM 制裁对策手册）——对我们保活功能的直接补强：
   - **API 35+ 必须用 specialUse 而非 dataSync**：Android 15 对 dataSync 有 6h 强限时，到点进程冻结。**印证我们 KeepAliveService 选 specialUse 是对的；上游 2.4.14 的 dataSync 方案在 Android 15 有上限**。
   - **WifiLock(low-latency)**：Wi-Fi radio 省电后"进程活着但入站 SYN 收不到、工具全部超时"——我们保活只做了 wake lock，这是增量。
   - `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 用法可照抄；"引擎死了但通知还在"检测思路与我们 onTaskRemoved 重断言一致。
6. **MCP server（host 模式）**：双协议兼容（Streamable HTTP + HTTP+SSE）、token 可选鉴权、工具定义与聊天内**单一来源**（含 loop guard/审批/截断全复用）、写工具共享 1 条串行门 + 读工具 2 条门 + 挂死熔断 2 分钟冷却（事故教训注释：native 挂死 = MCP 永久不可用）、重型工具 taskId 异步 + `mcp_task_status` 轮询。RikkaHub 已有 Ktor web 模块，是现成地基。
7. **世界书 seed 三重迁移**：版本号 key + 内容 sha256 表（识别用户手里的旧条目）+ staleMarkers（旧文本片段匹配后仅替换种子内容、**保留用户开关状态**）。我们 fork 的 prompt injection/lorebook 体系几乎无缝可套。
8. **Blutter 隔离 runner 模式**：`isolatedProcess="true"` Service（98 行）+ AIDL + ParcelFileDescriptor 零拷贝传 fd，每作业一进程用完即弃（native segfault 不伤主进程）；四级降级版本匹配（exact_engine → exact_snapshot → exact_dart → minor fallback）并把匹配证据返回给 AI；大索引 jsonl.gz 流式解析（O(1) 内存），教训注释：全量载 List 直接 OOM。
9. **记忆管线**（Kelivo 系，~6000 行，模块边界干净）：Gatekeeper → Extract → Smart Add → Distiller 四段式，水位线 + 窗口推进；**gatekeeper malformed 不推进水位线**（宁重跑不漏记）；Smart Add 的 MERGE/CONFLICT 目标必须在本地检索候选集内（堵 LLM 幻觉 id）；旧会话只注入 summary 不注入原文。
10. 小件：政策文本常量单一来源（Agent 系统提示与 MCP 工具描述共用同一份，防漂移）；规则字典 JSON + 用户规则页 + 远程订阅源；PROCESS_TEXT 全局划词进 AI。

## 四、落地顺序建议

| # | 来源 | 内容 | 成本 |
|---|---|---|---|
| 1 | 上游 `3a533a6a` | 终端多 Tab 会话管理（已在 master，待包名适配吸收） | 低 |
| 2 | rikkahub-agent | 终端 URL 点击（单文件）、ContextBudgetPlanner（152 行 + 测试） | 低 |
| 3 | rikkahub-agent | LoopGuard、工具审批模型、AgentRun 账本（等有 Cron/后台入口） | 中 |
| 4 | SoLab | 保活补强（WifiLock + specialUse 决策依据）、ToolCallLoopGuard、工具结果外置 + 反翻页 | 低-中 |
| 5 | SoLab | 世界书 seed 三重迁移、previewToken 审批、schema 三档加载 | 中 |
| 6 | SoLab | MCP host 模式（手机当桌面 AI 工具箱） | 高，按需 |

关联文档：[upstream-2.4.14-analysis.md](upstream-2.4.14-analysis.md)（A1 前台服务取舍——本文第五.5 条为其补充了 Android 15 dataSync 6h 限时的决策依据）。
