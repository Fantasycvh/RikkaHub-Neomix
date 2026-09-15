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

> **2026-09-15 补充：第 1 项的详细设计已落盘 → `docs/design-memory-scoring.md`（六项决策已全部确认，可直接动手）**（含与本仓代码/真实数据的逐条核对、tokenizer 可替换性分析、回收站的两个坑）。
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

关联文档：[upstream-analysis.md](upstream-analysis.md)（上游综合分析，A1 前台服务取舍——本文第五.5 条为其补充了 Android 15 dataSync 6h 限时的决策依据）。
