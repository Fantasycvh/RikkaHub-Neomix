# 同类应用借鉴评估：rikkahub-agent、SoLab 与 Yuihub（结合上游 3a533a6a）

> 生成时间：2026-08-28（Yuihub 记忆机制评估见「四」，2026-09-14 补）
> 分析对象：
> - [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)（GitHub 仓库，基于上游 2.4.10，最后提交 2026-08-22；桌面 APK 为其构建产物 + `excp.rikkahub.zhcn` 资源覆盖包汉化）
> - SoLab v2.1.0（MT 论坛分发，基于 [Kelivo](https://github.com/Chevey339/kelivo) 二次开发的「AI 驱动 APK 逆向工作台」；源码包 `F:\solab-2.1.zip`，Flutter + Kotlin）
> - [xiaoyuili/Yuihub](https://github.com/xiaoyuili/Yuihub)（RikkaHub fork，本地副本 `<work>\projects\Yuihub\`，包名 `me.yui.yuihub`，仅维护 en/zh；记忆机制评估见「五」）
> - 上游 `3a533a6a` 工作区终端后台运行 + 多 Tab（**已在 master**，特性分支未吸收）
>
> **许可证结论：三个项目均为 AGPL-3.0**（我们仓库根 LICENSE 即 AGPL-3.0），代码级互相摘取许可兼容。
> 本地参考副本：`<work>\projects\rikkahub-agent-src\`（git clone）、`<work>\projects\solab-open-source-2.0\` 与 `<work>\projects\solab-open-source-2.3\`（源码包解压，2.3 增量见附录）。

## 附录：SoLab 2.3 增量分析（2026-08-31，vs 2.0）

源码包 `F:\Solab2.3.zip`（version 2.3.0+72 vs 2.0 的 2.1.0+70）。规模：1243 文件有改动 + 72 项新增，无删除；Kotlin 侧 305、core/services 125、测试 301（goldens/perf 测试目录新增，测试文化建设明显）。逆向域新增（DPatch 模式、CryptoSig 密码常量扫描、FLAG_SECURE 补丁、BlutterDiff 等）与我们无关，以下是聊天侧可借鉴增量：

1. **`HttpSendRetry`（45 行，`lib/core/services/api/http_send_retry.dart`）**：发送层重试——瞬态故障（Socket/Timeout/ClientException）+ {408,429,500,502,503,504}，指数退避 500ms×2、maxAttempts=3；buildRequest 每次重构（body 不可复用）；**明确"只在响应头到达、流未消费前判定，不对已建立 SSE 流做中途续传"**；业务 4xx 不重试（"重放同样的请求不会成功"）。与我们重试实现同思路但更极简，且"不碰中途流"的取舍与我们 #1812 设计稿（design-partial-failure-draft.md）的分流判定互补。
2. **`ToolCallLoopGuard.forget()`（tool_call_loop_guard.dart 新增 6 行）**：失败调用（超时/异常/网络中断）从指纹窗口移除——"失败的调用没有可信结果，网络恢复后相同参数重试是合理恢复路径，不应被 LOOP_DETECTED 拦截"。**将来移植 LoopGuard 时必须带上的补丁**。
3. **`mcp_structured_image.dart`（363 行）**：MCP 工具结果图片处理——结构化 metadata `mcpResult{version, imageUris}` + markdown 平铺（保持原始内容顺序）+ 旧格式（私有码点定界符/JSON envelope）只读迁移 + **文本转义防伪造附件**。直接对应上游 issue #1703/#1761（MCP 图片结果在 Gemini 报错、下一轮 `Invalid type for 'input'`），是现成的迁移范式。
4. **`safe_resize_image.dart`（353 行）**：图片解码尺寸钳制——maxEdge + maxPixels 双上限、contain/cover 两种 fit、递减式逼近。对应上游 issue #1710（发送前图片压缩）与我们 turbo R 系列 OOM 防护的补强。
5. **行为测试契约（值得照抄的测试用例，非代码）**：
   - `chat_input_bar_conversation_switch_send_test.dart`：「发送未决时切换会话，发送锁必须解锁」——上游 #1820（切对话回复消失）同族问题的正确语义；
   - `message_generation_service_regeneration_test.dart`：「用户 re 生成不能认领下一组用户消息之后的回复」——再生成的归属语义。
   - 另有 message_list_view 的 file_processing/tool_extent/card_visibility 系列测试。
6. **timeline_projection.dart**：消息列表投影层——ToolUIPart 的 `providerId/fallbackOrdinal`（无 id 时用序号且与真实 id 不相交）+ `memoToken` 记忆化令牌，渲染与估算共享一次遍历。长会话消息列表性能设计，可对照我们的 MessagePartBlock 渲染。
7. 小件：frosted 毛玻璃组件、thinking_sheen 思考光泽动效、token_detail_popup。

（新增 Dart 目录 `lib/features/chat/widgets/frosted/`、`test/goldens/`、`test/perf/`；Kotlin 新增 ApkArchiveTool/ApkZipCache/AsmFastIndex/PpPostings 等均为逆向引擎性能件。）

## 一、rikkahub-agent 与 SoLab 是什么

（Yuihub 见「四」——其为 RikkaHub fork，同源关系无需单独介绍。）

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

## 四、Yuihub 记忆机制评估（2026-09-14，源码级）

**来源**：`<work>\projects\Yuihub\`（`me.yui.yuihub`，RikkaHub fork）。**结论：值得借鉴，但价值在「检索与写入的组织方式」，不在「语义向量」——恰恰相反，它上线语义检索后第二天整套删除了。**

### 4.1 关键事实：语义检索上过又被删（时间线仅一天）

| 提交 | 日期 | 动作 |
|---|---|---|
| `a8c33b2d` | 2026-08-31 | **上线**语义检索：`MemoryEntity` 加 `embedding` 向量列（迁移 26→27→28）、新增 `EmbeddingService.kt`（OpenAI 兼容 `/embeddings`，10s 超时、失败降级关键词）、检索改打分制 `重要度 × 时效衰减(30天半衰) × (1 + 0.3×关键词overlap + 0.6×余弦相似度)`、按字符预算截断注入、`PreferencesStore` 加 `EmbeddingConfig` 三件套（URL/Key/Model + 测试按钮） |
| `fbd15331` | 2026-09-12 | **删除**：`EmbeddingService.kt` 整文件移除（-81）、`MemoryEntity` 去掉 `embedding` 与访问统计列（迁移 32→33 显式重建表，且 `WHERE assistant_id != '__global__'` 顺手清掉全局记忆）、`EmbeddingConfig` 及三处接线全删、`MemoryRepository` -170 行 |

当前代码里 `embedding` 仅剩三条残留（迁移注释、通用的 `ProviderConnectionTester`、`ModelType.EMBEDDING` 列表标签），**链路已删干净非停用**。

**意义**：Yuihub 条件比我们好（有 OpenAI 兼容路径、多作用域），仍选择放弃——这是对「记忆语义检索不做」的**独立二次印证**（与本仓 `design-auto-compaction.md` §6.3 的成本结论、以及 `roadmap.md` §一 的 `take(60)` 缺陷分析三方一致）。

### 4.2 值得借鉴（按价值排序）

**① 打分校准 + 字符预算，替掉「取前 N 条」—— 最推荐**

现状 fork 是 `memories.take(60)`，而 `MemoryDAO` 查询无 `ORDER BY`（SQLite 按 rowid 即**插入顺序**返回）→ 超过 60 条后进 prompt 的是**最旧**的 60 条，最新记忆反而进不去（缺陷详见 `roadmap.md` 一·中等价值）。Yuihub 的 `selectForPrompt` 完全不用向量：

```
score = importance × (0.55 + 0.45 × exp(-ln2 × age / 30天))
```

再按 score 降序、以 1800 字符（约 600 token）**预算**逐个纳入（非数条数）。`importance` 由模型写入时给（0.9+ 身份/强偏好、0.6-0.8 普通偏好/进行中项目、0.2-0.5 次要），`updatedAt` 驱动时效衰减。**注意 Yuihub 的 DAO 明确写了 `ORDER BY updated_at DESC`**——它连这个坑也踩过并修了。方案比单纯加 `ORDER BY id DESC` 更进一层：不只取最新，而是取**最该取**的。

**② 被动提取管道 `MemoryExtractor`**

定位是「与模型自觉调用 `memory_tool` 互补」：每轮回复生成完成后异步跑，取最近 6 条消息（过滤记忆快照本身），带**现有记忆索引**（`id | [category] content`，≤100 条）让模型输出 `{"operations":[{"action":"add|update|delete",...}]}`——因提示词含现有记忆，**LLM 天然完成去重与冲突消解**，不靠模型「想起来该调工具」。工程稳健处：

- **并发守卫**：`runningScopes` 单飞 + `pendingScopes` 记「最后触发的会话」，运行中再触发不丢弃、结束后重跑（注释：*「避免连续对话丢事实」*）。
- **防幻觉 id**：`if (existing.none { it.id == id }) continue`——模型编的 id 改不到别的助手（与本仓记忆审批同族顾虑）。
- **失败全 fail-open**：整条 `runCatching` 包住，解析失败直接 return，绝不因记忆提取搞坏正常回复。
- 走 `backgroundTextGenerationParams(model, ReasoningLevel.AUTO)` 后台生成参数。

**成本**：每轮回复多一次 LLM 调用（复用当前对话模型），由阈值兜底——自动整理要求「总数 ≥30 且自上次整理后新增 ≥10」，手动整理门槛 8。要做须配开关或仅长对话触发。

**③ 整理器 `MemoryConsolidator`**

把重复/相似/零散记忆合并成一条、修正归类、控制注入体积。落地讲究：合并**先落库新条再删源条**（避免中途失败丢信息）、`sources` 去重且校验真实存在、`consumed` 集合防一源被两组重复消费、合并条 importance 取源**最大值**（不因合并降权）、手动与自动整理间单飞保护。

三者合起来是自洽闭环：**写入靠提取管道（可靠、去重）→ 存量靠整理器（压缩、纠类）→ 注入靠打分校准（按价值取）**。fork 目前三者皆无。

### 4.3 不建议照搬：记忆快照（append-only 合成消息）

Yuihub 把记忆从「每次请求临时拼进 system」改为**持久化的合成 USER 消息**（`isSynthetic=true`，插在最新用户消息之前，`withMemorySnapshot`），意图是「内容仅变化时追加、既有字节永不重写 → 请求前缀稳定 → 持续命中供应商前缀缓存」。识别分两层（`isSynthetic` + 固定前导语，供 UI/搜索/导出过滤）。

思路与本仓**压缩检查点同族**（append-only、合成消息、前缀字节稳定、发送窗口组装），说明双方对前缀缓存的认知一致。但嫁接代价高：需动 ChatService 请求组装、GenerationHandler transformer 跳过、导出/搜索/UI 过滤，**与本仓压缩窗口机制同层**，叠加后交互面不小（快照节点是否算进压缩区域？指纹怎么算？导出含不含快照？）。附带好处是记忆进历史、用户会话内直接可见（现状藏 system prompt 只能去记忆页查），但这点收益不值上述改动。

### 4.4 建议顺序

1. **先拿打分校准 + 预算截断**（含 DAO 补 `ORDER BY`）——成本小、直击现存 `take(60)` 实缺陷、无需新依赖，投产比最高；
2. **再评估提取管道**——须先决定是否接受「每轮多一次 LLM 调用」（其可靠性来源也是最贵部分），建议做成可选开关 + 阈值触发；
3. **整理器随提取管道一起上**（无提取则无膨胀压力，单独做意义不大）；
4. **语义向量：不做**（三方独立结论一致）；**记忆快照：暂不做**，等压缩窗口真需统一时再议。

> **2026-09-15 补充：第 1 项已实现（`69453f5e`）+ 设计落盘 → `docs/design-memory-scoring.md`**（含与本仓代码/真实数据的逐条核对、tokenizer 可替换性分析、回收站的两个坑）。
> **重要更正**：核对后问题重心不是「换排序」而是「全量注入需砍到预算」——本仓两个上限（60 条 / 100K 字符）在实测数据（54 条 / 60K 字符）下**都没触发**，即当前把全部记忆约 4 万 token 塞进 system prompt；且 Yuihub 的 1800 **字符**预算会把 54 条砍到 7 条，**不可照搬**（已定为 token 计、起步 5K–10K）。

## 五、落地顺序建议

> **落地状态（2026-09-14 复核）**：① 终端多 Tab **已落地**（`fbfcde5e`）；④ 的 **WifiLock 已落地**（`ee1c9736`，KeepAliveService 持 `WIFI_MODE_FULL_HIGH_PERF`）；②⑤③⑥ 仍待动手（② 的 ContextBudgetPlanner 与终端 URL 点击待做；⑥ MCP host 前置=AI 后台任务三件套）。

| # | 来源 | 内容 | 成本 |
|---|---|---|---|
| 1 | 上游 `3a533a6a` | 终端多 Tab 会话管理 | ~~低~~ ✅ **已落地**（`fbfcde5e`） |
| 2 | rikkahub-agent | 终端 URL 点击（单文件）、ContextBudgetPlanner（152 行 + 测试） | 低 |
| 3 | rikkahub-agent | LoopGuard、工具审批模型、AgentRun 账本（等有 Cron/后台入口） | 中 |
| 4 | SoLab | 保活补强（**WifiLock ✅ 已落地 `ee1c9736`** + specialUse 决策依据）、ToolCallLoopGuard、工具结果外置 + 反翻页 | 低-中 |
| 5 | SoLab | 世界书 seed 三重迁移、previewToken 审批、schema 三档加载 | 中 |
| 6 | SoLab | MCP host 模式（手机当桌面 AI 工具箱） | 高，按需；**一旦立项即成为第 4 个工具装配方**（远程调用者需设备工具子集、复用审批/loop guard，见 `upstream-analysis.md`「注意点」第 9 条装配方盘点） |
| 7 | Yuihub | **记忆打分校准 + 字符预算**（替掉 `take(60)` 全量注入，含 DAO `ORDER BY`） | 低（**推荐先做**，见 §四） |
| 8 | Yuihub | 记忆提取管道 + 整理器（须接受「每轮多一次 LLM 调用」） | 中-高 |

**记忆机制结论（Yuihub，详见 §四）**：值得借鉴的是**检索与写入的组织方式**（打分校准 / 提取管道 / 整理器），**不是语义向量**——Yuihub 上线语义检索后第二天整套删除（`a8c33b2d` → `fbd15331`），构成「记忆语义检索不做」的独立二次印证（与 `design-auto-compaction.md` §6.3 成本结论一致）。

**LoopGuard 移植提醒**：必须带 SoLab 2.3 的 `ToolCallLoopGuard.forget()` 补丁（失败调用移出指纹窗口，见本文附录）——否则网络恢复后相同参数的合理重试会被误判 `LOOP_DETECTED`。

## 六、SillyTavern 注入 / 预设评估（2026-09-16，源码级）

**来源**：`<work>\projects\SillyTavern\`（v1.19.0，`06bde939f`；核心 `public/scripts/world-info.js` 6408 行、`openai.js` 7396 行）。

**提问背景**：用户判断「ST 的预设原本好好的，后来都去搞花里胡哨的各种面板」——问 ST 的**注入与预设**部分若借鉴，值不值得。**结论先行：用户这个判断对，而且它恰好划出了分界线——ST 值得抄的恰恰是「不是面板」的那部分（世界书激活质量 + 预设的槽位序），需要避开的正是后来堆上去的那些字段与面板。**

### 6.1 世界书：fork 13 字段 vs ST 35 字段，但差距不在数量

fork 的 `PromptInjection.RegexInjection`（`Assistant.kt:226-240`）共 13 字段：
`id/name/enabled/priority/position/content/injectDepth/role` + `keywords/useRegex/caseSensitive/scanDepth/constantActive`。

ST 的 `newWorldInfoEntryDefinition`（`world-info.js:4079-4124`）共约 35 字段。**逐项归类后差距收敛为「4 个该抄、一批该避、其余无关」**：

| 类别 | ST 字段 | 判断 |
|---|---|---|
| **该抄** | `keysecondary` + `selectiveLogic`（4 模式）、`world_info_budget` + `ignoreBudget`、`scanDepth` 的**多源扫描**（`matchCharacterDescription`/`matchPersonaDescription`/`matchScenario` 等 6 个 flag） | 见 6.2 |
| **该避** | `sticky`/`cooldown`/`delay`、`probability`/`useProbability`、`group`/`groupWeight`/`groupOverride`/`useGroupScoring`、`vectorized`、`outletName`、`automationId`、`matchWholeWords`、`addMemo`、`position` 的 EM/AN/outlet 三段 | 见 6.4——**这就是用户说的「花里胡哨」** |
| **无关** | `characterFilterNames`/`characterFilterTags`/`displayIndex`/`uid`/`triggers` 等 ST 内部记账 | 不涉及 |

### 6.2 值得借鉴（按价值排序）

**① 次要关键词 + 选择逻辑（`keysecondary` + `selectiveLogic`）——最推荐，且是「导入保真」问题而非锦上添花**

这是世界书的**核心质量机制**：主键命中后，再用次要键二次收窄。ST 有四种逻辑（`world-info.js:33-38`）：
`AND_ANY`(0，任一次要键满足) / `NOT_ALL`(1) / `NOT_ANY`(2) / `AND_ALL`(3，全部满足)。

**关键事实：fork 完全没有这个概念，而 ST 的 `selective` 默认是 `true`**（`world-info.js:4089`），
第 4925 行判据是 `entry.selective && entry.keysecondary.length`——**即 ST 世界里大量条目依赖次要键来抑制误触发**。

⇒ **后果是确定的：fork 导入 ST 世界书时丢弃 `keysecondary`，这些条目会在只命中主键时照常触发（过度注入）**。
导入者看到的不是「少了个可选项」，而是**同一个世界书在 fork 里比在 ST 里注入更多、更乱的内容**——直接推高 token、稀释 prompt、且难排查（因为看起来「规则都在」）。

**成本**：模型 +2 字段（`keysecondary: List<String>`、`selectiveLogic: 枚举`）、`isTriggered` 改纯函数（主键命中 → 查次要键 → 按 4 模式判定）、UI 加一段次要键编辑 + 逻辑下拉、导入映射补两行。**估：约 1 提交**（含单测：4 种逻辑 × 主/次键命中的真值表）。

**② 关键词的扫描源不止「聊天消息」（ST 的 6 个 `match*` flag）——同一类「导入即行为改变」问题**

fork 的 `extractContextForMatching`（`Assistant.kt:291-298`）**只扫描消息文本**：
`messages.takeLast(scanDepth).joinToString { it.toText() }`（且调用处先 `filter { role != SYSTEM }`）。

ST 则允许条目声明**额外从哪些非对话内容里找关键词**（`newWorldInfoEntryDefinition`）：
`matchPersonaDescription`（用户人设）、`matchCharacterDescription`（角色描述）、`matchCharacterPersonality`、
`matchScenario`、`matchCharacterDepthPrompt`、`matchCreatorNotes`。

⇒ **后果同 ①：条目在 ST 里能被角色描述触发的，在 fork 里永远不触发**（关键词只出现在
`description`/`scenario` 里而未出现在聊天中时）。这是**漏注入**（与 ① 的过度注入方向相反），
二者合起来说明「导入 ST 世界书」目前在 fork 中是**行为不等价**的。

fork 的相关内容在角色卡导入时被压进 `systemPrompt`（见 ③），**源码里拿得到**（`assistant.systemPrompt`
含 Description/Personality/Scenario 段），所以实现上只需把匹配源从「消息列表」扩为「消息列表 + 指定助手字段」，
**不需要新解析层**。估：约 1 提交（与 ① 可合并同批做，因都改 `isTriggered`/上下文组装）。

**③ 世界书 token 预算（`world_info_budget` + `budget_cap` + 条目 `ignoreBudget`）——fork 现在零预算**

ST 的预算语义（`world-info.js:4736-4743`）：`budget = round(world_info_budget × maxContext / 100)`，
默认 **25%**，另有绝对值上限 `world_info_budget_cap`（0=不设）；超预算的条目**被丢弃**，
按 `order` 排序后处理，`ignoreBudget` 的条目豁免。**激活是「竞争预算」的**（第 5024-5040 行）。

**fork 现状：注入侧完全没有预算/裁剪**（`PromptInjectionTransformer.kt` 内检索 token/budget 为零命中，
只有一处 `takeIf` 是索引判断）。⇒ 世界书条目一旦多起来（或导入一个大型 ST 世界书），
**全部命中条目无条件进 prompt**，无任何上界。

**本仓已有现成范式可抄**：`memoryInjectBudgetTokens`（`PreferencesStore.kt:701`）就是同一形状
（token 计 + `Settings` 三件套 + `estimateTokenCount` 复用，见 `design-memory-scoring.md`）。
**估：约 1 提交**（预算字段 + 按 `priority` 排序截断 + 设置项 + 6 语言）。

**④ 预设的「槽位序」思想——只借思想，不借格式**

ST 预设（`default/content/presets/openai/Default.json`）的骨架是：
`prompt_order`（**有序槽位**，按 `character_id` 分组）+ `prompts`（identifier → `{name, role, system_prompt, content}`）。
真正漂亮的地方是 **`chatHistory` 只是这个有序列表里的一个槽位**：

```
main → worldInfoBefore → charDescription → charPersonality → scenario
     → worldInfoAfter → dialogueExamples → chatHistory → jailbreak
```

即「系统提示 / 角色描述 / 世界书 / 聊天历史 / 尾部指令」**是用户可拖拽重排的一维序列**，而非代码里写死的拼装顺序。

**fork 对照**：装配顺序**硬编码**——`before-system 注入 → assistant.systemPrompt（一整条巨串）→ after-system 注入`，
再接 `TOP_OF_CHAT → 历史 → BOTTOM_OF_CHAT/AT_DEPTH`（`applyInjections`）。
而角色卡导入（`AssistantImporter.kt:157-196`）更是把 `description`/`personality`/`scenario`/`mes_example`
**用 `buildString` 压成一条 `systemPrompt` 字符串**——**槽位信息在导入那一步就丢了**，之后无法单独重排或开关。

⇒ **值得借的是「顺序应当是显式的、可重排的」，不值得借 ST 的整个 prompt manager。**
但要注意：**这与 #1542「统一上下文插件」是同一个问题**，我在 `upstream-analysis.md` 已判「全量重写＝架构级（周级起）、不排期」。
所以此处**只建议窄义动作**：至少别在角色卡导入时把 4 个字段压成一条不可分的字符串
（保留为独立部分或独立注入条目），代价很小、且为将来留出重排可能。**不做全量 prompt 管理器。**

### 6.3 顺带发现的现有 ST 导入器缺陷（可立即修，与「借鉴」无关）

核实 `ExportSerializer.kt:166-204` 的 `tryImportSillyTavern`，发现**三处保真问题**：

1. **`useRegex = false` 且注释写错**：第 187 行注释「SillyTavern 格式不支持 useRegex」——**事实相反**。
   ST 的 key 支持 `/pattern/flags` 写法（`parseRegexFromString`，`world-info.js:2901-2927`：
   正则匹配优先于其它所有匹配选项，`world-info.js:337-341`）。**该注释是错的**，且导致带正则键的条目
   被当作**字面量关键词**匹配 → 触发条件整体改变。修法：识别 `/.../flags` 形态并置 `useRegex`。
2. **位置映射有损**：`mapSillyTavernPosition`（第 197-205 行）把 ST 的 `2`(ANTop) 与 `3`(ANBottom)
   **都映射为 `TOP_OF_CHAT`**。ST 的 AN 是独立的「作者注释」锚点（非「聊天开头」），二者语义不同 —— 映射后位置含义变了。
3. **`keysecondary`/`selectiveLogic` 丢弃**：见 6.2 ①，属过度触发。

> 这三条里 1、2 是纯 bug（改映射与解析即可），3 是「要么补字段、要么至少在导入时提示会失真」。
> **即便最终不借鉴任何 ST 机制，这三条也值得修**——它们让「导入 ST 世界书」这个既有功能的产出与源不一致。

### 6.4 不建议照搬（正是用户说的「花里胡哨」）

| ST 机制 | 为何不抄 |
|---|---|
| `sticky` / `cooldown` / `delay` | 需**会话级持久化状态**（ST 存 `chat_metadata.timedWorldInfo`，`world-info.js:508-530`）；fork 的 `Conversation` 无对应 meta 列，且「条目激活持续 N 轮/冷却 M 轮」的跨轮语义与 fork 的**压缩窗口/区域指纹**机制正交、易互坏。**复杂度全在状态机与持久化，收益是极少数场景** |
| `probability` / `useProbability` | **非确定性激活**——同一输入两次请求结果可能不同，直接破坏前缀缓存、且让「为何这条没生效」无法复现。与 fork 已确立的「前缀稳定」方向相反 |
| `group` / `groupWeight` / `groupOverride` / `useGroupScoring` | 「同组只取一条（按权重）」——偏创作型玩法，用户基数小，且需再引入权重抽取与组评分 |
| `vectorized` | 语义检索。**本仓已三方独立结论「不做」**（见 §四、`design-auto-compaction.md` §6.3） |
| `outletName`（outlet 位置） | 依赖 ST 的扩展/插件生态来消费该锚点；fork 无此体系 |
| `automationId` | 依赖 ST 的 automation 系统 |
| `matchWholeWords` / `addMemo` | 边缘开关（整词匹配 / 备忘标记），价值低 |
| `position` 的 EM/AN 三段 | EM=example messages 锚点、AN=作者注释锚点。fork **无「示例消息区」这一独立区块**（角色卡的 `mes_example` 已并入 systemPrompt），没有可挂靠的锚点 → 抄了也无处可插 |

**这张表就是「ST 后来堆上去的东西」**：它们多数要么需要额外的持久化/生态，要么引入不确定性，
**共同点是「字段数增长很快、实际用到的很少」**——正是「又大又不好用」的来源。fork 的窄字段集
（13 个）反而更接近 ST 早期「预设原本好好的」的形态。

### 6.5 另注：ST 已有 `assistant_prefill`，印证 #1918 评估

ST 预设顶部有 `assistant_prefill` 字段（`Default.json`；实现 `openai.js:1325`）——**但它同样只对
Claude 生效**（`supportsAssistantPrefill = chat_completion_source === CLAUDE`，第 1323 行）。
这与 `upstream-analysis.md` #1918 节的结论一致：**「末尾 assistant 预填充」是各家 provider 语义
不一致的能力**（Claude 原生支持、Chat Completions 需 `prefix:true`、Gemini 用 `model` role），
ST 也是靠「仅对支持的源开放」来回避。⇒ **#1918 的判断得到独立印证，不改结论。**

### 6.6 建议顺序（若动手）

1. **修 6.3 的三个导入缺陷**（① 正则键识别、② 位置映射、③ 次要键丢弃的替代处理）——小、纯正确性、无架构风险；
2. **补 `keysecondary` + `selectiveLogic` + 多源扫描**（6.2 ①②，可合并同批，都改 `isTriggered`/上下文组装）——世界书质量的核心，且直接消除「导入即行为不等价」；
3. **补 token 预算**（6.2 ③）——抄 `memoryInjectBudgetTokens` 现成范式，给世界书一个上界；
4. **角色卡导入不再压平 4 字段**（6.2 ④ 的窄义版）——小改动、为将来留白；
5. **全部跳过 6.4**；**preset 管理器不做**（= #1542 全量，已判不排期）；
6. **加「拼装后提示词预览」**（见 6.7 ③）——纯新增、零行为改动，且是上面各项（位置/缓存/截断）能被**看见与验证**的前提。

**总估**：第 1 项半天；2、3 各 1 提交；4 半天；6 约 1 提交（只读拼装，不碰生成）。**全部落在现有架构内，不动 provider、不动压缩线。**

### 6.7 社区讨论补充（2026-09-17，结合群聊截图）

> 来源：用户提供的两张群聊截图（讨论酒馆类应用/「类脑」预设与 Tavo）。以下四条**逐条对到 fork 代码**核对，其中第 ④ 条与群聊结论**相反**，已给出依据。

**① 「做精简一点就可以了，主要是注入，很多都用不到」+「ST 预设没用的东西太多，我都剔除好多」——§6.4 该避清单得到社区印证**

截图里那位用户的做法就是「自己删掉 ST 绝大部分字段，只留最常用的注入项」。这与 §6.4 的结论同向，**不需要改判断**。额外收获是它确认了「ST 的问题不是字段少，而是字段多到用户要手工做减法」。

**② 「图片生成那些东西都直接做成工具算了，再注入这注入那的，麻烦成狗了」——原则对，且 fork 现状已优于 ST**

该说法针对的是 ST 里「靠注入/提示词去驱动图片生成」的做法。核实 fork：

- 图片生成是**独立页面**（`ui/pages/imggen/ImgGenPage.kt` + `GenMediaEntity`），**不是注入**；
- 另有 provider 侧能力标志 `BuiltInTools.ImageGeneration`（`ai/.../provider/Model.kt:60`），只被
  `ResponseAPI.kt:288`（OpenAI Responses 原生 image_generation）与设置页/Web 路由消费，
  **不是普通聊天里模型可调用的 Kotlin `Tool`**。

⇒ 结论：**fork 不在这个问题上**（既没走注入，也没做成工具）。**是否补一个 `generate_image` 工具属独立议题**（要同时考虑 provider 原生能力与自建图片接口两条路），**本轮不建议顺带做**。

**③ 「Tavo 给了实际拼装后提示词，用来优化预设」——这是最有价值的一条，fork 完全没有**

核实：全仓**无**「拼装后提示词预览」实现——grep `promptPreview`/`assembledPrompt` 等**零命中**，
`web-ui/` 亦无。现有的**唯一**可见途径是 `LogPage`（`ui/pages/log/LogPage.kt`）里的原始 HTTP
请求日志（`RequestLoggingInterceptor.kt:23` 捕获完整 `requestBody`），但它有三个硬伤：

1. **要先开两个开关**（`log_page_record_requests` + 写文件），默认不开；
2. 展示的是**原始 JSON**（provider 协议形状），不是「按槽位/按来源」的可读视图——看不出
   哪段来自 systemPrompt、哪段来自世界书、哪段是压缩检查点合成消息；
3. **事后**属性：要真发一次请求才能看。

**为什么这条价值最高**：§6.2 ③（预算）、§六 6.7 ④（位置与缓存）、以及 `upstream-analysis.md`
「注意点」第 8 条（#1542 前缀稳定性）**全都要先能看到拼装结果才好判**。现在用户想调世界书位置、
想知道自己的 `{{cur_date}}` 是否在毁缓存，只能翻原始 JSON。
**建议形态**：复用 `PromptInjectionTransformer` 之后的**同一条 transformer 链**做一次只读拼装
（`messages` → 各 `InputMessageTransformer` → 渲染），**只读、不落库、不发请求**；按 stage
标注来源（system / 注入名 / 世界书名 / 检查点 / 历史），暴露为设置页或调试页入口。
**成本**：约 1 提交（复用现成链，主要是渲染与来源标注）。

**④ 「历史消息放在世界书前面 → 世界书都命不中」——观察属实，但值判断是反的；fork 的默认位置才是该改的那个**

先把 fork 的机制核实清楚（这是判断依据）：

| 事实 | 位置 |
|---|---|
| `RegexInjection`（世界书条目）**默认位置 = `AFTER_SYSTEM_PROMPT`** | `Assistant.kt:231` |
| 该位置**改写 system 消息本体**（`result[systemIndex] = … copy(parts = Text(新串))`） | `PromptInjectionTransformer.kt:128-156` |
| fork 的 Claude 集成**有显式缓存断点**：① system 最后一个 block、② **倒数第二条真实 user 消息**的最后一个 block（另有顶层自动断点） | `ClaudeProvider.kt:293-320`、`390-430` |

**推论（这是关键）**：世界书条目**会随对话滑动而频繁启停**（`scanDepth` 默认仅 4，窗口一滑出就失活）。
在 fork 的默认配置下这等于：**system（位置 0）每几轮就变一次 → 两个缓存断点同时失效 → 整段历史重算**。

而「把世界书放到历史之后（尾部）」时：system 稳定不动 → 断点 ① 命中；世界书插在最后一条消息之前，
**断点 ②（倒数第二条 user）仍在世界书之前** → 历史整段命中，只有「世界书 + 新消息」这一小段重算。

⇒ **群聊那句「世界书都命不中」在事实上成立**（世界书在尾部时，它总落在「新增 token」区、永远进不了缓存前缀），
**但它优化的对象选错了**：世界书条目通常**只有几百 token**，而历史是**几万 token**。
「牺牲小世界书的缓存，换取整段历史的缓存」是对的；反过来（世界书放前面）则会让世界书的每次启停
**掀掉整个历史缓存**。**正确规则是「稳定在前、易变在后」，世界书属易变项 ⇒ 应靠后。**

**这与 fork 自己的两条既有先例一致**（可直接引用为设计依据）：
- **Yuihub 的分段轨迹**把世界书触发做成「**当前轮次尾部补充段**」（见 `upstream-analysis.md` 注意点第 8 条引述）；
- 上游 **PR #1630** 推出的正是「turn-tail 注入」这一缓存友好方向。

**⚠️ 同一根因的既有实例（fork 自带）**：默认助手的 systemPrompt 含 `- Date: {{cur_date}}`
（`PreferencesStore.kt:941`），而 `PlaceholderTransformer` **每次请求都重写占位符**（`:61`、`:130`）
→ **每天（乃至任何触发重算时）从位置 0 作废整段缓存**。**它与「世界书放 system」是同一个 bug 的两个面**：
**位置 0 放了易变内容**。

**建议（不静默改质量默认）**：位置影响模型行为（靠前/靠后对效果有主观差异），**不宜悄悄改默认值**。
可行做法按代价从小到大：
1. **先做 6.7 ③ 的预览**，让「世界书落在哪、system 是否在变」变得可见、可自证；
2. 文档/UI 提示把**易变注入**（关键词触发型世界书）优先放 `BOTTOM_OF_CHAT`/`AT_DEPTH`，
   把 `constantActive` 的稳定条目留在 `AFTER_SYSTEM_PROMPT`；
3. 单独评估 `{{cur_date}}` 的迁位（该值不需要在 system 里，属可安全后移的项）。

**⑤ 「做成和 ST 里一样可开关的吗」——结构槽位缺，但「逐条开关」其实已经有了**

截图那台设备展示的是 ST-PM 风格的**槽位列表 + 每项开关**（Main Prompt / 框架认知 / 创作结构 / 构思 /
转场扩写续写 / 结尾摘要 …）。核实 fork：

- **「逐条开关」fork 已具备**：每条 `ModeInjection`/`RegexInjection` 自带 `enabled` 字段
  （`Assistant.kt:213`/`:229`），UI 上就是每条注入的开关——**截图里那种「一堆可开关的提示项」，
  fork 用模式注入就能表达**；
- **缺的是「结构槽位」**：ST 的 `prompt_order` 里 `charDescription`/`charPersonality`/`scenario`/
  `dialogueExamples`/`chatHistory` 是**可单独开关与重排的槽位**；fork 这四项被角色卡导入
  **压成一条 `systemPrompt` 字符串**（`AssistantImporter.kt:167-195`）→ **无法单独开关，也无法重排**；
- **也没有统一视图**：fork 的注入分散在两处（设置页的模式注入/世界书、助手详情的绑定），
  没有「一条按拼装顺序排列、每项带开关」的总览。

⇒ 这条与 §6.2 ④（槽位序）是同一件事。**最小改法是 §6.2 ④ 的窄义版**（导入不再压平），
**统一槽位视图可与 6.7 ③ 的预览合并做**（预览天然要按顺序列出各段，加开关即成为「槽位管理器」）——
**这是把 ③ 和 ⑤ 合并成一次实现的机会**，也是本组建议里性价比最高的组合。

### 6.8 落地记录（2026-09-17）

按 6.6 顺序动手，已完成**第 1 项（6.3 的两个真 bug）**与 **6.7 ③（拼装预览）**：

**① 拼装预览（6.7 ③）——`9e033c9d`**

- **抽取共享组装 `data/ai/PromptAssembly.kt`**：把原先**内联在 `GenerationHandler.generateInternal`**
  的系统消息组装与发送窗口抽成 `buildSystemPromptText` / `buildSendWindowMessages` / `buildInternalMessages`。
  **真实请求改调同一函数** ⇒ 预览不可能漂移（这是本次实现的核心手法——若预览另写一份组装，
  它会随时间与真实请求分叉，比没有预览更糟）。
- **`data/ai/PromptInspector.kt`**：`inspectPrompt()` 走同一组装 + **同一个 transformer 链**，
  把结果按来源标注为 `SYSTEM` / `COMPRESSION_CHECKPOINT` / `INJECTION` / `CHAT_HISTORY` 段。
  **只读**：不落库、不发请求。检查点识别复用 `isCompactionCheckpointPreamble`
  （**不能只看 `isSynthetic`**——它是 `@Transient`，持久化后丢失，见 ce311156 踩坑）。
- **入口**：聊天页附件面板（「更多选项」）内，「压缩历史」下方新增「拼装后提示词」。
  页面显示：概览（分段数 / 估算 token / **历史 N / M** / 上下文上限）+ 缓存提示卡 + 每段来源卡 + 说明卡。
- **单测 13 项**（`PromptAssemblyTest`）：钉住组装契约（system 在前且标 synthetic、会话级提示词
  仅在助手允许时覆盖、工具 schema 与记忆仅在启用时注入、上下文截断保留尾部、**检查点不参与截断**、
  幂等性、**历史计数不含 system/注入**）。
- **实现中自查出一个真 bug**（真机截图发现）：概览的「消息条数」把**带占位符的 value 串当标签用**，
  渲染出裸 `%1$d / %2$d`；且原口径把 system 计入历史，空会话显示「1 / 0」。
  已改为独立 label 键 + 只数历史（空会话 = `0 / 0`）。

**② ST 导入器两处真 bug（6.3 ① ②）**

- **`useRegex` 修正**：删掉错误注释「SillyTavern 格式不支持 useRegex」——ST 的 key **支持**
  `/pattern/flags`（`parseRegexFromString`，`world-info.js:2901`，且正则**优先于**其它匹配选项）。
  新增 `parseStKeywords()`：识别该形态 → 置 `useRegex=true` 并去掉包裹（`\/` 还原为 `/`）。
  **代价已知**：fork 只有一个 `useRegex` 开关，混合列表无法精确表达 ⇒ 整体走正则语义（宁可如此，
  也不要丢掉正则语义——那会让触发条件整体改变）。同时对齐 ST 的拒绝规则（未转义分隔斜杠不算正则）。
- **AN 位置映射修正**：原实现把 `ANTop(2)`/`ANBottom(3)` **都**映射为 `TOP_OF_CHAT`，
  且注释误写为「After Examples」。核实 ST：两者是包在**作者注释**前后（`world-info.js:5270`），
  而作者注释默认在 **in-chat depth 4**（`authors-note.js:272-273`：DEFAULT_POSITION=1=IN_CHAT、
  DEFAULT_DEPTH=4）——**不是对话最开头**。改映射为 `AT_DEPTH`（配合 `entry.depth`）。
  **诚实损失**：fork 无「作者注释」锚点，故 ANTop/ANBottom/atDepth 三者不再可区分。
- **单测 11 项**（`SillyTavernKeywordParseTest` 7 + `SillyTavernPositionMapTest` 4）。

**③ 六项全部落地（2026-09-17，同日第二批）**

| 批次 | 内容 | 提交 |
|---|---|---|
| A | **世界书次要关键词 + 选择逻辑**（`secondaryKeywords` + `SelectiveLogic` 四模式） | `b04cf0af` |
| B | **关键词多源扫描**（`matchAssistantPrompt`，对应 ST 的 6 个 `match*`） | `51104388` |
| C | **世界书注入 token 预算**（`limitByTokenBudget`，默认 0 = 不限制） | `494b0143` |
| D | **角色卡导入保留分区字段**（`card*` 五个可空字段 + 重建按钮） | `a8af0d3c` |
| — | **修：拒绝非世界书文件**（真机发现的静默失败，见下） | `a0f5c4f2` |

**A（过度触发）**：`isTriggered` 改为「主键命中 → 有次要键才按逻辑二次判定」；主/次键共用
`matchesKeyword()`（大小写/正则/坏正则按不命中语义一致）。**次要键为空 = 跳过第 2 步**，
存量条目零回归。ST 导入侧补 `keysecondary`/`selectiveLogic`/`selective` 三个字段（**必须
`@SerialName` 对齐小写名**——此前 `ignoreUnknownKeys` 使其**静默丢弃**，这正是过度触发的成因）。
保真细节：ST 的 `selective=false` 时不做二次判定 ⇒ 次要键整体作废，且**必须在算 `useRegex`
之前作废**（否则次键的正则会抬起 `useRegex`，连累主键字面量按正则匹配）。

**B（漏注入）**：`extractContextForMatching` 加 `extraTexts`（追加而非替换，`scanDepth` 仍只作用于
消息）。**刻意用 `assistant.systemPrompt` 而非最终发出的 system 消息**——后者含记忆/工具 schema/
渲染提示，是请求期合成的易变内容，纳入扫描会让条目随机命中自己或工具描述。ST 的 6 个 `match*`
在 fork 无对应字段（已压进一条 `systemPrompt`）⇒ **任一为真即并成一个开关**，诚实的近似，
代价是无法只扫其中某一段。

**C（零预算）**：`limitByTokenBudget()` 按 priority 降序取舍；超预算**跳过本条继续**而非 `break`
（一条超长条目不该堵死后面）；首条即使超预算也入选（避免「预算极小 → 一条都不注入」）；
**常驻条目（`constantActive`）不计预算、不淘汰**（用户显式声明「永远要在」的东西被预算淘汰很反直觉）。
**默认 0 = 不限制**（保持旧行为）——刻意不照搬 ST 的 25%：那会**静默**改变用户既有注入量。

**D（槽位丢失）**：Assistant 加 `cardDescription`/`cardPersonality`/`cardScenario`/
`cardExampleDialogue`/`cardSystemPrompt` 五个**可空**字段（`@Serializable` ⇒ **零 DB 迁移**）。
渲染抽成纯函数 `buildCardSystemPrompt()`，**与原内联 `buildString` 逐字节等价**（有特征测试锁死）。
**权威关系刻意保持单一**：`systemPrompt` 仍是唯一被发送的文本（保持既有行为与自由编辑），
`card*` 只是「零件」+ 一个显式重建按钮——若让零件参与组装就会出现两个真相源，且用户手改
`systemPrompt` 后会被静默覆盖。

**⚠️ 真机发现的既有静默失败（`a0f5c4f2`）**：装机验证时看到设备上两个世界书都是「0 条目」，
其中「【Tavo酒馆通用】柯尔的妙想_v1.5」实为**用户导入的 ST 预设文件**（不是世界书）。根因：
`SillyTavernLorebook.entries` 声明为**默认空 map** + `ignoreUnknownKeys = true` ⇒ **任意 JSON
都能解码成「0 条目的世界书」并静默导入成功**。这比直接失败更糟（用户以为成功了）。改为
`entries: Map<...>? = null`（无默认值）：缺失该键 = 不是世界书 → 返回 null → 报「格式不支持」。
**刻意保留「ST 世界书但 0 条目 = 合法」**（空世界书是合法状态）。

**验证**：新增 54 项单测（A 23 / B 10 / C 9 / D 5 / 拒绝 7，含**真值表**与**特征测试**）；
app 模块 662 项仅剩基线 `MarkdownMathParseTest` 失败（`git stash` 双向核对确认与本次无关）；
真机 `<device>` 逐项目视验证：预算卡渲染「不限制（0）」、编辑条目弹窗出现**次要关键词**区
+ **匹配逻辑 = 任一次要词命中**、`0 条目` 的世界书确认为静默失败实例；**数据零损失**
（install -r，uid/size/upload/skills 全不变）。

**④ 槽位管理器已合并落地（2026-09-17，`e9c75dbd`）**——6.7 ⑤ 与 6.2 ④ 合并成一次实现：

角色卡字段区升级为「提示词分段」**槽位视图**，对齐 ST `prompt_order` 的槽位概念：

- 新增 `CardSlot` 枚举（greeting / system_prompt / description / personality / scenario / example）
  + `Assistant.cardSlotOrder`（**空 = 默认序**，故存量助手自动获得未来新增槽位、无需迁移）
  + `disabledCardSlots`。
- `buildCardSystemPrompt` 泛化为「按槽位列表 → 块列表 → `\n\n` 连接」，
  **与原 `buildString` 逐字节等价**（特征测试锁死）。
- **「关闭槽位」与「内容为 null」是两件事**，刻意保留区分：null → 渲染 `Empty` 占位符（历史行为），
  关闭 → 整段不出现。
- `cardPartsDivergeFromSystemPrompt()`：字段改了但 `systemPrompt` 未重建时给提示——
  权威仍是 `systemPrompt`，不提示的话用户改完字段发现请求没变会以为坏了。
- UI 用 `ReorderableColumn`（与本页正则区同款，已解决嵌套滚动）；每槽 = 拖拽手柄 + 序号 +
  独立开关 + 内容框；开场白只给开关（内容由姓名插值）；关闭的槽位仍可查看内容（灰显）。

**真机验证**：导入测试角色卡后 6 个槽位全部渲染，关闭「场景」后开关灰显且漂移提示出现。

**至此 ST 借鉴清单（6.2 四项 + 6.3 三缺陷 + 6.7 ③⑤）全部清零。**

关联文档：[upstream-analysis.md](upstream-analysis.md)（上游综合分析，A1 前台服务取舍——本文第五.5 条为其补充了 Android 15 dataSync 6h 限时的决策依据；「提示词注入/世界书统一重写」议题见其「注意点」第 8 条）、[design-memory-scoring.md](design-memory-scoring.md)（§六 6.2 ③ 的预算范式来源）。
