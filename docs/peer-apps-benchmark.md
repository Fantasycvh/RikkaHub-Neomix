# 同类应用借鉴评估：rikkahub-agent、SoLab 与 Yuihub（结合上游 3a533a6a）

> **本仓说明**：本文写于私有开发仓的开发过程中，恢复时已按本仓实际改写——提交短哈希已重映射到本仓历史中的对应提交（映射不上的说明该提交未随本仓发布）；`<work>` / `<device>` / `<tmp>` 等尖括号串是路径与设备信息的占位符。本仓基线 = 上游 `2c980642`（RikkaHub `2.4.5`），此后对上游修复/功能按需**人工评估吸收**（不做 ff-only 同步、改动不回推上游）；包名 `app.androidrik.rikkahub`、显示名 **Neomix**、发布分支 `master`、**尚未发版**（当前 `1.0.26 (293)`；本仓不发预编译包，CI 自动发版已禁用）。

> 生成时间：2026-08-28（Yuihub 记忆机制评估见「四」，2026-09-14 补）
> 分析对象：
> - [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent)（GitHub 仓库，基于上游 2.4.10，最后提交 2026-08-22；桌面 APK 为其构建产物 + `excp.rikkahub.zhcn` 资源覆盖包汉化）
> - SoLab v2.1.0（MT 论坛分发，基于 [Kelivo](https://github.com/Chevey339/kelivo) 二次开发的「AI 驱动 APK 逆向工作台」；源码包 `<local>\solab-2.1.zip`，Flutter + Kotlin）
> - [xiaoyuili/Yuihub](https://github.com/xiaoyuili/Yuihub)（RikkaHub fork，本地副本 `<work>\projects\Yuihub\`，包名 `me.yui.yuihub`，仅维护 en/zh；记忆机制评估见「五」）
> - 上游 `3a533a6a` 工作区终端后台运行 + 多 Tab（**已在 master**，特性分支未吸收）
>
> **许可证结论：三个项目均为 AGPL-3.0**（我们仓库根 LICENSE 即 AGPL-3.0），代码级互相摘取许可兼容。
> 本地参考副本：`<work>\projects\rikkahub-agent-src\`（git clone）、`<work>\projects\solab-open-source-2.0\` 与 `<work>\projects\solab-open-source-2.3\`（源码包解压，2.3 增量见附录）。

## 附录：SoLab 2.3 增量分析（2026-08-31，vs 2.0）

源码包 `<local>\Solab2.3.zip`（version 2.3.0+72 vs 2.0 的 2.1.0+70）。规模：1243 文件有改动 + 72 项新增，无删除；Kotlin 侧 305、core/services 125、测试 301（goldens/perf 测试目录新增，测试文化建设明显）。逆向域新增（DPatch 模式、CryptoSig 密码常量扫描、FLAG_SECURE 补丁、BlutterDiff 等）与我们无关，以下是聊天侧可借鉴增量：

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
   - **本仓状态（2026-09-26）**：三工具 + `TailBuffer` **已落地（`7207a1a2`）**；与同源实现不同处：并发上限 4（超限拒绝不排队）、完成即淘汰保留 20、审批分档（run_background/kill 需审批、status 免审批）、LoopGuard 把状态查询加入轮询豁免。单轮字符/步数预算已另落地（`4a91f932` `WorkspaceTurnBudget`，口径=输出字符 + 步数）；**后台任务时长上限已落地**（09-26 收尾，30 分钟看门狗 + `TIMED_OUT` 状态 + 完整输出落盘 `.tasks/`）。详见 `design/design-workspace-background-tasks.md`。
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
7. **世界书 seed 三重迁移**：~~版本号 key + 内容 sha256 表~~ **✅ 已落地（`2d30c528`，09-30）**——实现比原设想更简：固定 entryId 定位（省掉 sha256 表）+ (内容,位置) 历史指纹识别未改动旧副本 + 保留用户开关状态 + 已删不复活（`SeedMigration.kt` 接 PreferencesStore 读路径；现注册 Learning Mode v2，世界书留泛化点）。
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

三者合起来是自洽闭环：**写入靠提取管道（可靠、去重）→ 存量靠整理器（压缩、纠类）→ 注入靠打分校准（按价值取）**。fork 当时三者皆无；**后续落地状态见 §4.4 末的 2026-10-12 复核**（打分校准 + 整理器已落地，提取管道以「模型主动调 `memory_tool`」的形态覆盖）。

### 4.3 不建议照搬：记忆快照（append-only 合成消息）

Yuihub 把记忆从「每次请求临时拼进 system」改为**持久化的合成 USER 消息**（`isSynthetic=true`，插在最新用户消息之前，`withMemorySnapshot`），意图是「内容仅变化时追加、既有字节永不重写 → 请求前缀稳定 → 持续命中供应商前缀缓存」。识别分两层（`isSynthetic` + 固定前导语，供 UI/搜索/导出过滤）。

思路与本仓**压缩检查点同族**（append-only、合成消息、前缀字节稳定、发送窗口组装），说明双方对前缀缓存的认知一致。但嫁接代价高：需动 ChatService 请求组装、GenerationHandler transformer 跳过、导出/搜索/UI 过滤，**与本仓压缩窗口机制同层**，叠加后交互面不小（快照节点是否算进压缩区域？指纹怎么算？导出含不含快照？）。附带好处是记忆进历史、用户会话内直接可见（现状藏 system prompt 只能去记忆页查），但这点收益不值上述改动。

### 4.4 建议顺序

1. **先拿打分校准 + 预算截断**（含 DAO 补 `ORDER BY`）——成本小、直击现存 `take(60)` 实缺陷、无需新依赖，投产比最高；
2. **再评估提取管道**——须先决定是否接受「每轮多一次 LLM 调用」（其可靠性来源也是最贵部分），建议做成可选开关 + 阈值触发；
3. **整理器随提取管道一起上**（无提取则无膨胀压力，单独做意义不大）；
4. **语义向量：不做**（三方独立结论一致）；**记忆快照：暂不做**，等压缩窗口真需统一时再议。

> **2026-10-12 复核（本节三项的最终落地形态）**：**四项建议全部走完，但「提取管道」换了一条路**——
> - **①打分校准 + 预算截断 ✅**（`91d88551`，见下方 09-15 补充）；
> - **②被动提取管道 ⚠️ 未按本节的 `MemoryExtractor` 形态做**：fork 采用**模型在轮内主动调 `memory_tool`**（配合 create/edit 分档审批 + 幂等去重 + 200 条上限淘汰）——**同样的「可靠写入 + 天然去重」目标，但不额外增加每轮一次 LLM 调用**，因此本节「须接受每轮多一次调用」这个前置代价**不再成立**。若将来要补「模型忘了调工具」的兜底，这里的 `runningScopes` 单飞 / `pendingScopes` 防丢事实 / 防幻觉 id / fail-open 四条仍是现成规格；
> - **③整理器 ✅ 已落地**（「做梦」四批，2026-10-09）：`MemoryConsolidationScheduler`（轮末空闲 2 分钟 / 攒够 6 轮 / 切后台 / 启动补漏 / 手动「整理一下」）+ `MemoryConsolidator` worker + 审批降级（写入审批开着 ⇒ 只提案不改库）+ 成本护栏（步数上限、每库 6 小时自动频次、离线跳过）。**与本节建议的差异（有意）**：整理**不改动** `sources`/`consumed` 那套合并记账，改为「整理 agent 直接调 `memory_tool`」；提案形态取**决策 B**（会话内通知 + 记忆页列出建议，不落库），理由见 `design/design-memory-consolidation.md` §7.2/§8.3。**未做**：独立前台服务保活、「离开对话」钩子。
> - **④语义向量不做 ✅ 维持**（本仓独立复算：全仓 `generateEmbedding` 仅声明、零调用方，`design-memory-scoring.md` 与本节结论一致）。

> **2026-09-15 补充：第 1 项已实现（`91d88551`）+ 设计落盘 → `design/design-memory-scoring.md`**（含与本仓代码/真实数据的逐条核对、tokenizer 可替换性分析、回收站的两个坑）。
> **重要更正**：核对后问题重心不是「换排序」而是「全量注入需砍到预算」——本仓两个上限（60 条 / 100K 字符）在实测数据（54 条 / 60K 字符）下**都没触发**，即当前把全部记忆约 4 万 token 塞进 system prompt；且 Yuihub 的 1800 **字符**预算会把 54 条砍到 7 条，**不可照搬**（已定为 token 计、起步 5K–10K）。

## 四.5、Yuihub 2.5.5 → 2.5.7 增量评估（2026-10-02，09-23 后约 45 提交）

**直接可吸收（小件，均 fork 核实存在同款缺口）——✅ ①②③ 已于 2026-10-02 落地（真机验证待用户）**：

| 项 | Yuihub 提交 | fork 现状与要点 |
|---|---|---|
| **备份 DB 快照改 VACUUM INTO** | `47e7dfaf` | fork `addDatabaseToZip` 是**热拷 db/wal/shm 三件套**（无读事务）——备份瞬间有写入即可能拷到不一致状态/半页（09-15 的 WAL 修复只治了**恢复方向**）。VACUUM INTO=单读事务一致性快照；**必须带回退**（fork minSdk 26 < SQLite 3.27 不支持该语句，老设备回退热拷）。~1h |
| **write/edit 二进制误写防护** | `8a3a37ac` ISSUE-08 | fork `WorkspaceTools:582` 用有损 `toString(UTF-8)` 解码后写回——非文本文件被静默写坏。U+FFFD 检测 → 按二进制拒绝引导走 shell。~30min |
| **工具参数解析加宽松重试层** | `0eaf2057` | 与 fork #1985 修复（`ToolArgumentsJson`）**互补不重叠**：Yuihub=strict 失败后 lenient 重试（治未引号 key/尾随逗号），fork=取第一个完整 JSON 值（治双对象拼接）。可串成三层 strict→lenient→首值提取。~30min |
| **workspace rootfs 补 ca-certificates** | `8a3a37ac` | ubuntu-base 裸镜像无 CA 证书 → 容器内 curl https 全挂（000 静默）。fork RootfsPatcher 有字体/数学补丁、证书未见处理（待真机核实），有 Android 系统证书库离线兜底方案可抄 |

**对照项（架构对撞，维持自研不跟）**：
- **子代理独立子会话**（`52a679d5`/`30783483`/`b7e75fb2`/`06738520`）：Yuihub 把子代理做成真实子会话落库（`parent_conversation_id`）+ 抽屉会话树 + `Semaphore(4)` 真并行。fork 设计稿早已论证过「复用 Conversation 表=12 列表查询/3 聚合漏过滤即静默泄漏」的风险而选独立 `agent_run` 表——**Yuihub 10-02 还在修子代理缺陷（终态/工具越权/edit 缩进），反向验证了我们的选择**。可观测性 UI（抽屉会话树）是好想法，fork 已有 run 详情页等价物，暂不跟。
- ~~**空回复补救**~~ **✅ 已落地（2026-10-02，主循环同款机制：一次性 recovery_note 进系统提示不进历史）**。

**功能灵感（大，未立项按需）**：
- **定时任务/自动化**（`2906828e`+`65525df9`+`e585d82b`+`191f5a8d`，~2300 行）：助手级 ScheduledTask（WorkManager 调度）+ **AI 可在对话中管理本助手的定时任务**（工具化）+ 完成悬浮通知 + 按助手过滤。fork 的时间提醒只是会话内提醒，这是真调度执行（=助手 cron agent）。大功能。
- ~~**todo_write 计划工具 + 计划细条**~~ **✅ 已落地（2026-10-02，方案 E 终态：PlanBar 输入栏上方常驻、正文不渲染、按助手门控 TodoPlan）**，单测 7 项。
- **models.dev 目录匹配**（`ecaca331`，~1000 行含 fixture 测试）：按 modelId 自动补全 contextLength/能力/图标——可作 fork 手工 ModelRegistry 的兜底源。
- **附件注入「封面先行」+ 放开类型白名单**（`bee1dd38`）、**workspace 写入冲突检测 mtime**（`f91d3b16`，与 edit_file dry-run 互补的 TOCTOU 维度）→ **✅ 已落地（2026-10-02，write_file expectedMtimeMs/force + read_file 输出 mtime 闭环）**、**release 体积 31.7→23.1MB 清理**（`7b8c918a`，其手段=砍功能，fork 未评估）。

## 四.6、Yuihub 备份体系对照（2026-10-04，`36b719ef`/`34f1a976`/`3eddec42`/`47e7dfaf`）

**真缺口（fork 核实）——✅ ①②③ 已于 2026-10-04 落地并真机验证通过（日志取证：images 恢复/integrity ok/completed）**：
1. **生成的图片不进备份**——fork `BackupItem.FILES` 只含 UPLOAD/FONTS/SKILLS，而
   `FilesManager.getImagesDir()` = `filesDir/images`（生成图片落这里）⇒ 恢复后消息图片全裂
   （与 Yuihub 修的「DB 记录还在文件丢了」同款）。修法=FileFolders.IMAGES + 打包/恢复/预检三处。
2. **目录清单三处手列**——`collectBackupInputs` / `addAppFilesToZip` / restore 分支各自枚举目录，
   新增目录漏一处即静默不备份。抄 `BACKED_UP_FILE_FOLDERS` 常量 + CoverageTest（断言与 FileFolders
   声明一一对应，漂移即测试红）。1+2 可合并一批。
3. **staged DB 缺 integrity_check**——swap 前对暂存主库跑 `PRAGMA integrity_check`，不过则中止
   （旧格式/外来备份兜底）。~30min。

**已有/不吸收**：
- 旧格式备份残留 WAL 清理、tool_outputs zip-slip canonicalPath 边界校验——fork 均已有（09-15 修复 + restoreFlatEntry 现行实现）。
- **PendingRestoreStore（恢复续传）**：完整解「恢复中被杀→半途库」。fork 暴露面更小（重命名原子换入、
  失败保留旧库；.restore-tmp 留 dbDir 不碍 Room），残余窗口=清 sidecar 与 moveIntoPlace 之间被杀丢
  最近未 checkpoint 尾部——**可选低优先**，重构面大（启动期检测要在 startKoin 之前）。
- **RikkaHubImporter 列名交集搬运**：Yuihub 因 schema 分叉（上游≤25/其 36）才需要；fork 是上游迁移链
  超集，文件级替换+Room 迁移本就工作——不适用。
- **自动备份**（默认关/WorkManager/仅留最新一份 auto_backup/）：功能方向可取，中等工作量，按需立项。

**其他新提交简评（10-03/04）**：压缩批次（`542a6762` 开关+/压缩 手动压缩——fork 09-13 已有；
`499be973` 区分显式结束与裸断流、统一输出截断检测——与我们半截草稿/tool 截断同域可细看；
`93f9e7f0` 检查点落库+统一未完成检测+自动续写——与 #1812/队列同域）；`0502e45d` 工具间过程解说
并入思考折叠（UX 细节可借鉴）；`a045cbf9` 工具结果统一 JSON 契约（senera Observation 投影的轻量版）。

## 四.7、Yuihub 压缩/半截/续写批次深评（2026-10-04，先不吸收）

对照对象：`542a6762`（自动压缩开关+/压缩）、`499be973`（显式结束 vs 裸断流）、`93f9e7f0`（检查点落库+统一未完成检测+自动续写）、`3a721242`（手动压缩交互）。

**结论：压缩批次无吸收点；真正值钱的是 499be973/93f9e7f0 揭示的 fork 三个缺口。**

### A. 压缩批次——平级，无借鉴
fork 09-13 的 harness 式自动压缩更早更全（检查点式/轨迹只追加/区域指纹失效/参数配置档/阈值与保留比可配）；手动压缩有配置档与「无可压缩」区分提示。Yuihub 是同域的简化实现。

### B. **静默截断缺口（fork 实锤存在，最重要）**
Yuihub `499be973`：只有收到**显式终止**（[DONE]/message_stop/response.completed）才算正常结束；连接提前关闭（裸 EOF——中转断流常态）标记 `UIMessage.finishIncomplete`，另加 `isOutputLimitTruncation()` 统一 length/MAX_TOKENS/max_output_tokens。
**fork 现状（实测）**：所有 SSE provider 的 `onClosed → close()` **无异常正常收尾** ⇒ 裸断流被当正常结束——#1812 不触发（只接异常）、无横幅、无草稿、用户看到戛然而止的回答。且 UIMessage 不持久化 finishReason ⇒ 连「撞输出上限」都无法与正常结束区分。
吸收方向：decoder 加 explicitEnd（四 provider SSE 监听记录显式结束，onClosed 无 explicitEnd ⇒ 标 incomplete）→ 上游先做「未完成」提示（省），自动续写另议。**中等工作量，跨 ai 模块四 provider。**

### C. 检查点落库——✅ 已落地（2026-10-04/05 B/C/D 批次：流式 2s 检查点/finishIncomplete/自动续写，见 roadmap）
fork 流式期间只走纯内存、落库点仅轮次结束/取消收尾（b1273a72 分析确认）⇒ **崩溃/杀进程丢半截内容**（#1812 只覆盖失败路径）。Yuihub 方案=onCheckpoint 回调（每 chunk 合并后，视觉转换后消息）+ 调用方节流 upsert **只动正在生成的节点**（不删其它）+ 崩溃后重进可见断点。fork 落地注意：①节流间隔（ANR 刚修，落库频率须节制）②与 #1812 的 NonCancellable 收尾合流 ③复用 updateMetadata/updateConversation 原语。

### D. 自动续写——与 #1812 哲学冲突，建议拆开
Yuihub：截断（length）与裸 EOF 注入续写提示（**复用我们刚落的 pendingRecoveryHint 通道**），上限 3 次，续写追加同一条消息 + `mergeTrailingAssistantTextParts`（防续写新建 Text part 导致 markdown 块空行——这个坑值得直接抄）。
fork #1812 的既定哲学=「保留草稿 + 用户手动重试」（成本控制权在用户）。两者不互斥：**裸 EOF（网关问题非模型选择）自动续写合理；length 截断自动续写烧费风险高（长文可连烧 3 轮），宜提示+手动或可配置**。四分类提示（length/content_filter/裸 EOF/只思考）可补充我们刚落的空回复补救（当前只覆盖「只思考」）。

**建议优先级**：B（静默截断，真缺口）> C（崩溃丢失，已知缺口有现成方案）> D（续写，跟 B/C 捆绑决策）。合计约 1–1.5 天。

### ✅ 2026-10-04 落地（B/C/D 三项，分三批提交）

| 批 | 提交 | 内容 |
|---|---|---|
| B | `9d5774ee` | `MessageChunk.explicitEnd` + `terminalChunk()`：四个 provider 在显式结束处发标记（`[DONE]`/`message_stop`/`response.completed`/Google 带 finishReason 末块），`onClosed` 不发 ⇒ 裸断流可判定；`UIMessage.finishReason`/`finishIncomplete`（JSON blob 零迁移，语义=最后一次流的结束状态）；`isOutputLimitTruncation`/`isContentFilterTruncation` 纯函数统一三家命名 |
| C | `457c102d` | `ConversationRepository.checkpointGeneratingNode(conversation, nodeIndex)`：只序列化并 upsert 一个节点（不按前缀比对全会话、不重建 FTS——索引在轮次结束的正常保存时补齐）；ChatService 每 2 秒节流调用，失败只记日志。**nodeIndex 取 `chunk.messages.lastIndex` 而非 messageNodes.lastIndex**：THIS_ONLY 重生成只改动中间节点 |
| D | 本批 | `ContinuationPolicy.autoContinueHintFor` 纯函数：裸断流**恒**自动续写、撞输出上限**默认只提示**（`RetrySetting.autoContinueOnLengthTruncation` 可开）、两类共用上限 3 次；续写走已有的一次性提示通道追加到同一条消息；`ChatService.continueMessage` + 消息不完整横幅三档（内容拦截不给续写按钮）+ 设置开关 + 6 语言 |

**与 Yuihub 的差异（刻意保留）**：Yuihub 对 length 截断也自动续写（上限 3）；fork 保持 #1812 的「成本控制权在用户」取舍——length 默认只提示 + 手动继续。另：Yuihub 的 `mergeTrailingAssistantTextParts`（续写新建 Text part 在正文多出空行）在 fork 不需要——fork 的同角色 chunk 追加规则会把续写文本拼进最后一个 Text part。

## 五、落地顺序建议

> **落地状态（2026-10-02 复核，2026-10-12 更新）**：① 终端多 Tab **已落地**（`86cd6a70`）；② 的 **ContextBudgetPlanner 已落地**（`f821181c`），终端 URL 点击仍待做；③ LoopGuard/AgentRun 账本 **已落地**（`15a4dc8c`/`4adcda07`）；④ WifiLock **已落地**（`935f8b97`）+ LoopGuard 已落地；⑤ 的 seed 迁移 + dry-run 审批 **已落地**（`2d30c528`）、**schema 三档缩水版也已落地（`8bf348d0` + `05571808`，10-06）⇒ 本行「仍待排期」已过期**；⑥ MCP host 仍是真·未立项（其前置后台任务三件套已就绪）；⑦ 记忆打分已落地（`91d88551`）；⑧ **记忆提取管道 + 整理器已落地**（见 §4.4 末复核），不再是「未立项」。

| # | 来源 | 内容 | 成本 |
|---|---|---|---|
| 1 | 上游 `3a533a6a` | 终端多 Tab 会话管理 | ~~低~~ ✅ **已落地**（`86cd6a70`） |
| 2 | rikkahub-agent | 终端 URL 点击（单文件）、~~ContextBudgetPlanner~~ ✅ 已落地（`f821181c`） | 低（剩 URL 点击） |
| 3 | rikkahub-agent | ~~LoopGuard~~ ✅（`15a4dc8c`）、~~AgentRun 账本~~ ✅（`4adcda07`）、工具审批模型（部分已落地） | ~~中~~ 基本收口 |
| 4 | SoLab | 保活补强（**WifiLock ✅ `935f8b97`** + specialUse 决策依据）、~~ToolCallLoopGuard~~ ✅（`15a4dc8c`）、工具结果外置 + 反翻页（部分由 .tasks/ 落盘替代） | ~~低-中~~ 基本收口 |
| 5 | SoLab | ~~世界书 seed 三重迁移、previewToken 审批~~ ✅（`2d30c528`）、~~schema 三档加载（缩水版）~~ ✅ **已落地（`8bf348d0` + `05571808`，10-06）** | 收口 |
| 6 | SoLab | MCP host 模式（手机当桌面 AI 工具箱） | 高，按需；**一旦立项即成为第 4 个工具装配方**（远程调用者需设备工具子集、复用审批/loop guard，见 `upstream-analysis.md`「注意点」第 9 条装配方盘点） |
| 7 | Yuihub | ~~记忆打分校准 + 字符预算~~ ✅ 已落地（`91d88551`，真机验证） | ~~低~~ 完成 |
| 8 | Yuihub | ~~记忆提取管道 + 整理器~~ ✅ **整理器已落地**（V1/V2/V3-b1~b3，`f4c2def2`…`63a92148`，10-09）；提取以 `memory_tool` 主动调用形态覆盖 | 收口 |

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
  （`Assistant.kt:485`/`:501`，基类 `:470`），UI 上就是每条注入的开关——**截图里那种「一堆可开关的提示项」，
  fork 用模式注入就能表达**；
  （**2026-09-19 细化**：条目开关有两套语义——改全局 `enabled`（预设详情页，`97aed655`）与
  改本助手绑定（选择器详情，`d44e90f3`），二者是「与」关系；见 §6.11 ④。）
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

**① 拼装预览（6.7 ③）——`f879a222`**

- **抽取共享组装 `data/ai/PromptAssembly.kt`**：把原先**内联在 `GenerationHandler.generateInternal`**
  的系统消息组装与发送窗口抽成 `buildSystemPromptText` / `buildSendWindowMessages` / `buildInternalMessages`。
  **真实请求改调同一函数** ⇒ 预览不可能漂移（这是本次实现的核心手法——若预览另写一份组装，
  它会随时间与真实请求分叉，比没有预览更糟）。
- **`data/ai/PromptInspector.kt`**：`inspectPrompt()` 走同一组装 + **同一个 transformer 链**，
  把结果按来源标注为 `SYSTEM` / `COMPRESSION_CHECKPOINT` / `INJECTION` / `CHAT_HISTORY` 段。
  **只读**：不落库、不发请求。检查点识别复用 `isCompactionCheckpointPreamble`
  （**不能只看 `isSynthetic`**——它是 `@Transient`，持久化后丢失，见 15142509 踩坑）。
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
| A | **世界书次要关键词 + 选择逻辑**（`secondaryKeywords` + `SelectiveLogic` 四模式） | `672f4e83` |
| B | **关键词多源扫描**（`matchAssistantPrompt`，对应 ST 的 6 个 `match*`） | `67844bcb` |
| C | **世界书注入 token 预算**（`limitByTokenBudget`，默认 0 = 不限制） | `2325a327` |
| D | **角色卡导入保留分区字段**（`card*` 五个可空字段 + 重建按钮） | `c4bbd2d5` |
| — | **修：拒绝非世界书文件**（真机发现的静默失败，见下） | `7f8fa46b` |

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

**⚠️ 真机发现的既有静默失败（`7f8fa46b`）**：装机验证时看到设备上两个世界书都是「0 条目」，
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

**④ 槽位管理器已合并落地（2026-09-17，`d2400452`）**——6.7 ⑤ 与 6.2 ④ 合并成一次实现：

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

### 6.11 预设中心化（2026-09-17 第三批：`d0f15e97` / `f2f6f50e` / `1395270a`）

用户指令：**UI 明说「优先级更高者在前」、支持预设导入、把整个扩展管理建立在注入（预设）之上**；
世界书暂不重要，之后再做。

**① 预设容器（`d0f15e97`）——容器化的同时不动下游**

新增 `PromptPreset { id, name, description, enabled, entries }`（`entries` 仍是原 `ModeInjection`）。
关键手法：`Settings.promptPresets` 是**持久化字段**，而 `modeInjections` 改为**计算属性**
（`flatMap { it.entries }`）⇒ `PromptInjectionTransformer`、Web API 的 id 校验、web-ui 的勾选列表、
`ExtensionSelector`、`AssistantExtensionsPage` **全部零改动**。全仓只有 1 处写 `modeInjections`。

持久化用**回退读**而非迁移器：新 key `prompt_presets` 优先，旧 key `mode_injections`
包成单个预设兜底 ⇒ 老用户原地可用；写入只落新 key。

绑定为**预设级 + 条目级并集**：`Assistant.promptPresetIds`（可空集合 ⇒ 零 DB 迁移）
与 `modeInjectionIds` 并存，并集后 `distinctBy { id }`（同一 id 走两条路径会注入两次）。
预设绑定刻意做成**助手级、不随会话覆盖**（表达「这个助手基于哪套方案」，与会话级逐条勾选正交）。

配套 `reconcileEntries()`：扁平编辑写回容器时按 id 归位、**并按新序重排**（否则拖拽顺序不生效）、
跨预设重复 id 先到先得。

**② 排序语义（`f2f6f50e`）——priority 为唯一来源**

原先拼装按 `priority` 降序，而列表展示的是存储顺序 ⇒ 给某条设过优先级后**拖拽即失效**
（「看起来能拖、实际没变化」）。现改为：列表按 `priority` 降序展示，拖拽后
`normalizeEntryPriorities()` 把视觉序（top→bottom）写回 `n-1 … 0`
⇒ **显示顺序 == 生效顺序**恒成立，并在 UI 明说（用户指定的做法）。

**③ 预设列表/详情 + ST 预设导入（`1395270a`）**

- `PromptPage` 两级：预设列表（拖拽 + 行内开关 + 条目数）→ 详情（条目增删改 + 拖序 + 逐条开关）。
  详情是**页内二级页**，故返回键用 `BackHandler` 先回列表（`BackButton` 会直接退出整页）。
- 新增 `PromptPresetSerializer`（native + ST 双分支）。ST 分支按 `prompt_order` 顺序取
  `prompts` 内容，**跳过空内容的运行时占位槽位**（`chatHistory` / `worldInfoBefore` /
  `charDescription` …——ST 在请求时填充，fork 无需表达），顺序 → priority 降序。
- **世界书 tab 暂时隐藏**（`SHOW_LOREBOOK_TABS = false`）：只关 UI 入口，数据/编辑器/
  导入导出全保留。两处 tab 行改为按 `ExtensionTab.visible` 渲染 ⇒ 隐藏时页面索引自动跟随。

**⚠️ 本轮踩到并修复的真 bug（数据损坏）**：真机导入 ST 预设时，用户既有的 **14 个注入条目
被覆盖成 1 个**。根因是 `rememberImporter` 用 `remember(serializer)` 缓存 `ImporterState`，
**首次组合传入的 onResult 被永久捕获**；而 `PromptVM.settings` 初始值是 `Settings.dummy()`
（只含 1 个默认预设），真实设置晚一两帧才到 ⇒ 回调闭包捕获 dummy 列表，
「dummy + 新导入」被写回。**修复取结构性方案**：`ImporterState` 改持**回调读取器**
（`currentOnResult: () -> (Result<T>) -> Unit`）+ `rememberUpdatedState`
⇒ 所有调用点（含将来新增）自动拿最新回调，不必各自记得防。
**通用教训：把「回调」交给长生命周期对象时，要传「读取器」而不是「回调本身」——
尤其是在 `remember(key)` 缓存、而闭包捕获的数据晚于首帧到达的场景。**

数据已用 protobuf 精准 splice 从旧 key 恢复（14 条经解析验证），并加回归测试
`ImportPreservesExistingDataTest` 锁住「必须基于最新列表而非 dummy 基线」。

**④ 开关层级现状（2026-09-19 核实）——「条目像预设一样在外部直接开关」已具备，但开关共三层且语义不同，另有一个未接线的预设级绑定**

核实缘由：需求「预设的条目也像预设本身一样可在外部直接开关」。结论是**已经具备**，
但三层开关的语义**各不相同**，容易在后续改动中混淆，故记清：

| 开关 | 位置 | 改的是 | 提交 |
|---|---|---|---|
| 预设级（整包停用） | `PromptPage` 预设列表**行内** | `PromptPreset.enabled`——**全局**字段 | `1395270a` |
| 条目级（改全局启用状态） | `PromptPage` 预设**详情**每张卡片 | `ModeInjection.enabled`（`entry.copy(enabled=…)`） | `97aed655` |
| 条目级（改本助手的绑定） | `ExtensionPicker` 预设详情（对话内扩展面板 + 助手详情页共用） | 绑定 id 集合（`modeInjectionIds` / 会话级 `modeInjectionIds`），**绝不碰全局 `enabled`** | `d44e90f3` |

- 生效判定唯一落点是 `PromptInjectionTransformer.collectInjections`：
  `entry.enabled && (effectiveModeInjectionIds.contains(id) || presetEntryIds.contains(id))`
  ——即**全局开关与绑定是「与」关系**：全局关掉的条目，任何助手绑定它都不生效。
- `ExtensionPicker` 详情那处源码注释明确写了边界（「这些开关只改已有绑定 id，绝不改条目的全局 enabled」），
  并**刻意不做聚合开关**——多个回调基于同一份父快照会互相覆盖（同批次的已知陷阱，见 §6.11 ① 的教训）。
- **预设级绑定 `Assistant.promptPresetIds` 目前未接线**：全仓仅 3 处代码引用
  （`Assistant.kt:108` 声明 + `PromptInjectionTransformer.kt:38`、`PromptInspector.kt:193` 两处读），
  **零写入点**，web-ui/web 模块也无（grep 全空）⇒ `promptPresetIds` 恒为空集，
  「整包启用一个预设」这条能力**已预留但尚无入口**。实际生效的只有条目级绑定 + 预设全局 `enabled`。
  **接线点**（若要做）：`ExtensionPicker` 容器行（现在 `trailingContent` 只有 `›`），
  与世界书容器行 `book.id in selectedIds` 的 Switch 同款写法——**成本很低**。
  注意「助手级绑定不随会话覆盖」是既有设计意图（表达「这个助手基于哪套方案」，与会话级逐条勾选正交）。

### 6.12 拼装预览的浏览体验（2026-09-18，`1235a841`）+ **待办 Tier 4**

**已完成 Tier 1–3**：

- **Tier 1 定位/跳转**：把 `ChatList` 私有 `MessageJumper` 抽成共享
  `ui/components/ui/ScrollJumperBar.kt`（顶尖头/上一项/下一项/底尖头 + 可选 `N/M` 进度标签）；
  预览页加**段落目录卡**（一行一段 `#N · 来源 · token`，点按随机访问）。
  段落下标 = 概览(0)+缓存提示(1)+目录(2) 之后的第 n 项 ⇒ **n + 3**（三者都是 `item` 槽位，
  内容为空也占位 ⇒ 偏移是常量）。跳转条沿用聊天页节奏「滚动浮现、静止 1.5s 隐去」，**靠右放**
  （预览页正文满宽，靠左会叠字）。
- **Tier 2 长段折叠 → 手动收纳（2026-09-19 修订，`41ede01d`）**：初版是「>1200 字符默认
  截断 + 卡头展开」（卡头教训：开关放正文下方，展开 11K 字的 system 段后按钮被推到几十屏外
  再也点不到——真机实测踩到）。用户随后决策**去掉自动截断**：概览职责已由目录卡承担，
  截断让「读内容」多点一步且 SelectionContainer 选不到未渲染文本；折叠保留为**手动**动作
  （默认全文展开，卡头开关把整段收起只留卡头，用于把不关心的大段挪出浏览路径）。
  复制/分享始终全文。
- **Tier 3 段内操作**：复制内容 / 复制（含来源表头 `#N label [role, ≈tokens]`）/ 分享。
  **刻意不做**「翻译」「引用」：只读预览里翻译无语义、引用没有可指向的消息对象。
- 顺带把代理对安全截断提为 `common/text/TextTruncation.kt`（`String.truncateSafely`），
  ai 的引用快照与预览页折叠共用（原先 ai 模块那份 internal 实现改为调用 common）。

#### Tier B 落地记录（2026-09-19，同日推进）

用户拍板推进 Tier B，核心是**system 块化 + 卡槽身份升级**：

- **`buildSystemBlocks`**：system 组装从单个 `buildString` 改为「带来源标记的块列表」
  （PERSONA / CARD_SLOT / MEMORY / TOOL / DIAGRAM_HINT / MATH_HINT），`buildSystemPromptText`
  = `joinSystemBlocks(blocks)`（按各块 `separatorAfter` 连接）。**逐字节契约由
  `PromptAssemblyTest` 17 项锁死**——分隔符是单换行（历史 `appendLine` 行为，含空工具块
  的孤立换行），**卡槽块之间是双换行**（`buildCardSystemPrompt` 的历史连接符，
  `separatorAfter` 按相邻卡槽对计算）。
- **卡槽身份升级（零行为变更）**：未漂移的角色卡助手（`hasCardParts` 且
  `systemPrompt == buildCardSystemPrompt(...)`）的 system 由**卡槽逐槽渲染**成
  CARD_SLOT 块——槽位开关/排序**即时生效**（无需点「重新生成提示词」），输出与
  原 systemPrompt 逐字节相同（「未漂移」的定义即是如此）。漂移（手改过 systemPrompt）
  → 手改内容优先（整段 PERSONA），漂移提示照旧。**09-17「systemPrompt 唯一发送文本」
  的约束以「未漂移才走字段」的方式保留了精神（永不静默丢失手改）同时放开了槽位**。
- **预览归因（Tier 4 的 system 部分完成）**：`PromptInspector` 对 system 消息调
  `buildSystemBlocks`（与真实请求同参）细分出「开场白/系统提示/描述/性格/场景/示例对话/
  记忆/工具 Schema/渲染提示」各段；槽名复用角色卡槽位视图的既有六语言字符串。
  真机实测：原 2 段 → 13 段，目录与正文一致。
- **实现教训（两处）**：①卡槽间的**双换行分隔**差点被压扁成单换行——块连接不能在
  测试里手写 join（必与实现漂移），提为 `joinSystemBlocks` 共用；②测试 fixture 的
  `Assistant` 必须带 `card*` 字段——只给 systemPrompt 会让 `hasCardParts=false`
  永远走 PERSONA 兜底，表现为「卡槽归因不生效」。

#### ✅ Tier 4（已落地，`9c18ee07`）——「点注入段 → 跳到产出它的条目」

**价值**：把预览从「只读展示」变成「可归因并可操作」——看到某段世界书/模式注入不合适时直接
跳到它的编辑处。

**⚠️ 2026-09-19 更正（保留）**：「按条目开关」**早已存在**（`97aed655`，预设详情卡片行内 Switch；
选择器侧另有绑定开关，见 §6.11 ④ 的开关层级表）。故 Tier 4 不承担「解锁某能力」的职责，
真缺口只有「从预览跳回条目」。

**规模：中**。两处结构性障碍（均已解决）：

1. **`PromptSegment` 不带来源身份** → 现由 `PromptSegmentSource`（容器类型/id/名 + 条目名）
   承载；`PromptSegment.source` 仅注入段有值。
2. **逐条身份在合并那一步就丢了**：`PromptInjectionTransformer.createMergedInjectionMessages`
   把同一 position 的多条注入 `groupBy { role }` 后 `joinToString("\n")` **合成一条消息**
   ⇒ 三条世界书条目落到预览里就是一段文本。

**解法（关键取舍）：身份走带外标记，不改编排方式。**

- **为什么用 `UIMessageAnnotation.InjectionSources`**：注解不在 part→API 转换的白名单路径上
  ⇒ **请求字节零变化**，合并规则/顺序/分隔符全照旧；且 `messages` 是 JSON blob、
  反序列化开了 `ignoreUnknownKeys` ⇒ 新增注解类型**无需 DB 迁移**。同 `QuotedMessage`
  的取舍（`UIMessageAnnotation.kt` 注释），`QuoteTransformer` 亦印证「注解不参与请求组装」。
- **为什么不改合并方式**：合并是既有请求形状，改动会改变逐字节输出、且可能触碰端点的
  行为假设（如 deepseek 对消息序列的要求）。**逐字节约束守住了**——`PromptAssemblyTest`
  与本次新增的等价单测都锁着契约。
- **覆盖范围**：四个**非 system** 合并点（TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH /
  END_OF_CHAT 的 system 与 user 段）记贡献者；system 区 BEFORE/AFTER **不改结构**——
  它被拼进 system 本体、与原人设正文交错，一条有序 id 列表表达不了位置关系，
  由预览侧直接调 `collectInjections` 归因。
  （注：system 侧的「块化」障碍已由 Tier B 的 `buildSystemBlocks` 解决，见上节。）

**UI 落点**：新路由 `Screen.PromptEntryTarget`（独立路由而非给 `Prompts` 加可空参数——
后者会改变既有 route 签名、影响深链与返回栈恢复）；`PromptPage` 支持
`initialContainerId`/`initialEntryId` 深链 → 直达预设/世界书详情 → 滚动到条目 →
**短暂高亮**（primary 描边而非改底色：描边不改变内容对比度，与「启用/停用」语义不冲突）。
预览页用 `navigate` 而非 `popBackStack`——预览页留在返回栈，改完按返回即回到预览继续看。

**两条正确性约束（均有单测钉住）**：

- **宁可整段显示、不可少显示**：任一贡献者 id 查不到内容时**整段回退**，不半拆。
  半拆会让预览与实际请求不符，比不拆更糟——该页存在的意义就是「与自己发出的一致」。
- **高亮只在首次进入定位**（`rememberSaveable` 消费掉 id）：否则用户手动往下翻时
  任何重组都会把列表拉回目标条目。

**测试**：`PromptSegmentAttributionTest` 11 项（逐字节等价 / 贡献者顺序与拼接顺序一致 /
多位置各自成段 / 尾部按 role 分组 / 无注入不产生注解噪音 / 拆分成逐条 / 未知贡献者回退 /
空贡献者视为无注解 / 条目已删除仍可正确拆出 / 无名条目回退通用 label）。全量 839 项仅
`MarkdownMathParseTest` 既有基线失败。真机验证通过。

#### ⚠️ 发布说明偏差（已消解）

v1.0.7 更新日志写着「新增拼装预览：发送前可查看提示词实际分段与占用，**支持按条目开关**」，
而**预览页里没有任何开关**。核实：对应实现 `d2400452`（提示词分段槽位视图）**完全没碰**
`promptinspect` 目录，它落在**助手详情页的角色卡字段区**。

**2026-09-19 补充（偏差收窄）**：①「按条目开关」作为**能力确实存在**（`97aed655` 预设详情 +
`d44e90f3` 选择器绑定，见 §6.11 ④），只是都不在预览页；②「预览页无开关」这个具体表述
仍成立。

**2026-09-20 结案**：Tier 4 已把预览变成**可操作面**（跳转到条目编辑处），配合既有的条目级
开关，v1.0.7 那句「据此调整」终于名副其实——此前预览只能看、不能改，也就无从「据此调整」。
文案无需再改（「支持按条目开关」+ 预览可跳转 = 用户能从预览出发完成调整闭环）。

#### ✅ 预览页 token 估算偏低的成因分析与修复（2026-09-20 分析 → 2026-09-21 落地 `8f44c34b`）

用户反馈「预览页的 token 估算与实际发送差别比较大」。核实后**主因不是估算器不准，而是覆盖面不足**——
预览数的是「能拆成文本的那些段」，而不是「这次请求实际会发多少」。按影响排序：

**① 工具 schema 在预览里几乎贡献 0 token（最大项）**

预览的 `TOOL_SCHEMA` 段取的是 `tool.systemPrompt()`（`PromptAssembly.kt:149-151`），
而**实际下发给 provider 的是 `description` + `parameters`（JSON schema），不是 `systemPrompt`**
——两者是 `Tool` 上彼此独立的字段（`ai/core/Tool.kt:16`，`systemPrompt` 默认 `{ _, _ -> "" }`）。

- 全仓**仅 2 处**工具设了 `systemPrompt`（`SkillsTools.kt` 的技能清单、`TextToSpeechTool.kt`）；
- **MCP 工具完全不设**（`ChatToolFactory.kt:151-160` 只给 `description`/`parameters`）。

⇒ 实测过的极端场景下（54 个 MCP 工具 ≈33K token，占 4 万总量约 80%，见 `701361d8`），
**这部分在预览里是 0**。这是「差别比较大」的主因，且是**覆盖面**问题而非精度问题。

**② 口径不同：预览只数 `Text` part，占用条含 `Tool`**

- 预览：`message.parts.filterIsInstance<UIMessagePart.Text>()`（`PromptInspector.kt:199`），
  `totalTokens = segments.sumOf { it.tokens }` ⇒ **图片/文档/音视频 part 一律计 0**
  （而实发会经 `FileEncoder` 压 JPEG 再 base64）。
- 占用条：`estimateTokenCount`（`ContextLength.kt:78-84`）**包含 `Tool` part 的输出文本**
  （fork 工具输出上限 32KB）。**同一份对话，两处 UI 两套口径**。

**③ 估算器是字符启发式，对 schema 恰是最不准的场景**

`estimateTokens` = `cjk/1.5 + other/4`（`ContextLength.kt:113-118`）。散文尚可，但 JSON schema
以 `{`/`"`/`:`/`type`/`string` 等短 token 与标点为主，真实 tokenizer 密度远高于 4 字符/token
⇒ **即便把 schema 接进预览，用这个估算器仍会低估**。
仓里其实已有更准的实现：`ai/util/ToolSchemaSize`（按 provider 实际包装形状，实测误差 3.4%），**预览没复用它**。

**④ 协议包装开销未计入**：每条消息的 `{"role":…,"content":…}`、`tool_calls` 包装、
`ensureArrayItems` 补的 `items` 等。单项不大，消息/工具多时累积。

**已落地（`8f44c34b`，真机验证）**：

1. **工具 schema 独立成段**（原建议的 ①，收益最大）：新增 `TOOL_SCHEMA` kind（与
   `TOOL_GUIDANCE` 区分——后者是 system 正文内的引导文本，此前两者混用同名枚举）。
   复用 `ToolSchemaSize` 并按 provider 形状算体积（与诊断日志同口径），且把它内部的
   `wireElement` 提为 `wireJson()` 供预览渲染 ⇒ **估算与展示同源**，不会「数字按 A 算、
   正文按 B 拼」。标 `TOP_LEVEL_FIELD`，UI 显示「请求顶层字段，不在消息里」——
   **原「归因错误」一并修掉**（此前被渲染成 system 块）。真机实测：13 个工具 ≈2894 token。
2. **工具输出接入**（分析时低估了它的量级，实为第二大来源）：`UIMessagePart.Tool.output`
   是**独立的一类 part**，而预览正文只取顶层 `Text` ⇒ 整条工具输出计 0，而
   `estimateTokenCount` 一直计入（两处口径不一致）。现每条输出各自成段（实发时是独立的
   `tool` 角色消息，一条一段才对得上消息序列），段名带工具名。
3. **非文本 part 占位**（原建议的 ②）：段尾追加「［未计入：图片 N、文档 N、音频 N、视频 N］」。
4. **措辞（A 方案）**：标题「拼装后提示词」→「**请求内容**」——既不冒充「上下文」
   （会与输入框的 `usage.prompt_tokens` 对不上），也不让人误以为只是 system 提示词；
   legend 补**「不计入」清单**（附件编码体积 / 协议包装 / 字符启发式 / schema 另算）。
5. 协议包装开销未做（原建议的 ④：量级小）。

**顺带修掉两处**（真机与回归发现）：①**空工具引导块噪音**——`buildSystemBlocks` 为每个工具
各加一块，实测 13 个里 11 个空 ⇒ 渲染成 11 行「工具引导 ≈0」，已过滤（只影响展示；空块在
system 里仍贡献换行的逐字节契约由 `PromptAssemblyTest` 锁着）；②`sentHistoryCount` 改为
**按消息计**（一条消息现在可产出多个段，按段计会重复统计、甚至超过 `totalMessageCount`）。

**测试**：`PromptToolSchemaSegmentTest` 7 项 + `PromptToolResultSegmentTest` 7 项 +
`SystemBlocksAttributionTest` 修枚举改名回归并钉住空块过滤。全量 860 项仅既有基线失败。

**关联**：`701361d8`（上下文构成诊断日志，真实构成与 3.4% 精度实测）、§6.12 Tier 4。

关联文档：[upstream-analysis.md](upstream-analysis.md)（上游综合分析，A1 前台服务取舍——本文第五.5 条为其补充了 Android 15 dataSync 6h 限时的决策依据；「提示词注入/世界书统一重写」议题见其「注意点」第 8 条）、[design-memory-scoring.md](design/design-memory-scoring.md)（§六 6.2 ③ 的预算范式来源）。

## 七、senera 评估（2026-10-02，仅远端仓库调研，本地无源码）

**仓库**：[yuanplussfive/senera](https://github.com/yuanplussfive/senera)（⭐14，TypeScript strict，Node 24，Web 工作台 + Electron 桌面 + Docker/gVisor 沙箱；自述「可观测、可校验、可扩展的 Agent 工作台」；内核=Pi Coding Agent 会话与多步工具循环）。~3250 文件的大系统，架构层面对移动端聊天 fork 不可移植，但有三个机制值得精读。

### 7.1 动态工具检索/元工具协议（最有价值——peer⑤③ schema 三档缩水版的成熟参考实现）

`sensem/ToolSearch` 模块（30+ 文件 + 9 个行为测试 + 独立 benchmark）正是我们 09-26 设想的「MCP 轻量化 + 元工具」的成品形态：

- **工具分加载模式**：`ToolLoadingModes.Dynamic`（不进上下文）vs `Always`（核心工具常驻）。fork 对应=内置工具常驻、MCP 工具全部 Dynamic。
- **四个元工具**：`ToolSearch(query)`（检索）→ `ToolDescribe(tools≤12)`（看 schema）→ `ToolLoad(tools≤12)`（加载进本轮上下文）→ `ToolUnload(tools)`（卸载）。工具注册表对模型暴露的只有这 4 个 + Always 工具 ⇒ **上下文占用从「全部工具 schema」变为「常驻子集 + 4 个元工具」**，正是 MCP 33K 问题的解。
- **排序管线**：BM25/RRF/MMR 三段 + 能力索引（由工具名+描述+schema 构建文档）+ **SQLite 记忆反馈**（EpisodeScorer：历史使用证据提升排序——用得多的工具排名靠前）。fork 若做：BM25 在 Kotlin 里可用 SQLite FTS 或手写倒排；记忆反馈可从 agent_run/工具执行历史取材。
- **catalogRevision**：工具目录版本号贯穿 Search/Describe/Load 参数——**mid-run 工具集变化可检测**，这直接回应我们 09-26 评估的最大顾虑「mid-run 换 schema 作废 Claude 前缀缓存」（至少可感知、可决策）。
- **会话级缓存 + Retain/Replace 策略**：已加载工具集跨轮保持（Retain=默认保留，Replace=整体替换）⇒ 前缀缓存只在真正换集时失效。

**结论**：我们排期 schema 三档缩水版时，此仓库是唯一见过的完整参考实现（含测试与 benchmark），设计要点可直接对照，省一轮设计踩坑。

**7.1.1 源码级补注（2026-10-02，本地 `<work>\projects\senera` 已克隆，`Source/AgentSystem/ToolSearch/` 40 文件）**：

- **修正一处远程评估的误读**：`ToolLoad/ToolUnload` 只作用于 **BAML 规划链路**的 exposure snapshot。**native 工具链路更激进**——动态工具**从不进 provider schema**，模型经固定 `ToolCall(toolName, contractRevision, args)` 桥调用（`ToolDescribe` 拿到 TS-like 视图后），provider schema 恒为「Bootstrap 工具 + ToolSearch/Describe/ToolCall」。fork 全部走 native tool calling ⇒ 对应形态=**schema 恒不增长，元工具桥是唯一入口**（比我们「三档加载」设想更彻底）。
- **ToolSearch 不做 top-K 截断**：返回**全部**合资格动态工具、确定性排序——命中项带 evidence，未命中项压缩为 name/title/summary/零分，让模型可发现而不塞 schema。
- **lexical-first，远程层默认全关**：BM25 精确召回 + `fuzzysort` 只对**工具名/能力 ID/标题/别名**做容错（不扫长描述、无硬编码同义词）；embedding 召回仅在**词法召回为空**时启用；远程 rerank 只在有限召回集上跑。默认值（`AgentDefaultCatalog.ts:141`）：Fuzzy{Enabled:true, MinScore:0.25, CandidateLimit:8}、Embedding{Enabled:false}、Rerank{Enabled:false, CandidateLimit:24}、Ranking{RrfK:60, MmrLambda:0.72, MmrCandidateScoreRatio:0.92, **MaxResults:6**}、记忆反馈{MaxEpisodes:5000, **HalfLifeDays:30**（使用证据按 30 天半衰衰减）, Expansion:Fallback 模式 MinConfidence:0.8/MinEvidence:3}。
- **缓存身份内容寻址**：目录身份=文档 stable ID + revision + 完整 semanticText 的规范哈希；embedding 身份=（模型, docId, SHA-256(semanticText)）哈希 ⇒ **生产者忘 bump revision 也不会脏缓存**；LRU 有界。这对我们的 Claude 缓存断点顾虑是更稳的形态：失效由内容变化触发而非标签。
- **已确认能力复用（argument reuse）**：每 durable session + logicalCacheScope 保存有界证据，**仅只读/无副作用/免审批工具**可自动复用旧参数（写状态/联网/审批类只留诊断不复放）；复用前按目录身份+契约 digest 重校验；显式参数恒优先。证据不因索引刷新清除 ⇒ 稳定前缀缓存。fork 的 MCP 检索类工具（search_web 等）将来可借鉴此形态。
- **安全与排序分离**：审批/OPA/资源投影在执行期强制，搜索排序不得复制安全策略——与 fork DenialGuard 同哲学，写设计稿时值得引用为原则。
- **MCP `tools/list_changed`**：经 SDK 协商处理，按 server 事务性替换 + 失效索引；非法更新保留旧目录。
- 学习（episode）是**可观测旁路**：每条 episode 终态=learned/skipped/failed，**不改变主任务结果**；歧义多 Skill 归因直接跳过而非复制到每个 Skill。

### 7.2 工具结果 → Artifact/证据投影（次优先）

工具执行结果发布为完整 **Artifact**（带完整性 receipt），回写给模型的只是声明式 **Observation v3 投影**（摘要+事实+URI）；历史轮的大输出沉淀为证据投影，需要原文时用 artifact 工具按 URI 取回；Pi 压缩删旧消息但留隐藏索引，每请求只在剩余 token 预算内注入「最近且尚未可见」的 URI。

fork 现状对照：我们已有**文件版近似**（tool_outputs 落盘 + 32KB 截断 + 4KB 预览 + workspace_read_file 取回 + 压缩检查点），形态不同、思想同源。差距在「投影」是结构化摘要而非字符截断——但结构化摘要要么靠模板（信息损失可控性差）要么靠 LLM 再加工（每步多一次调用）。**暂不跟**，等 L2 上下文预算立项时一并评估。

### 7.3 其他值得记的（小）

- **宿主生成工具调用 ID + 依赖投影**：并行工具调用时 call_id 由宿主生成（`call_xxx`）、工具间依赖由宿主侧校验投影——不押在模型自己编 ID 上。与我们 ResponseAPI toolCallId 归一（`3f54240f`）同主题的更激进形态；fork 目前工具串行、子代理并行由宿主管，暂无消费点。
- **CallTools 动作协议**（FinalAnswer/AskUser/CallTools 三分 + CallTools 先出自然语言预回复再规划参数）：「边说边做」的 UX；fork 流式本就支持文本与工具交错，元工具落地时若做「先检索后调用」的提示词工程可参考其动作拆分。
- **执行目标合同**：工具注册时声明网络能力/工作区权限/资源语义/artifact 策略；沙箱不可用 → **明确拒绝该执行目标而非悄悄改本机执行**——与 fork DenialGuard 同哲学，可用于将来 workspace 工具分级。
- **BAML 双链路**（native tool calling / BAML 结构化规划二选一并显式声明，运行中不静默切换）：让不支持工具调用的普通模型获得同等的结构化工具语义。Kotlin 侧无可直接移植的 BAML 运行时，成本大，仅记录思想。
- **Skill 发布前工具引用校验**：SkillManage 创建/更新时验证引用的工具已注册（防悬空）；fork 技能体系无工具引用概念（allowedTools 已删），不适用。

### 7.4 分层上下文与上下文策略（2026-10-02 追加；与上游 #972 同题）

7.1～7.3 只覆盖了工具检索/Artifact/杂项。本节补的是**分层上下文与上下文策略**——因为上游 **#972（无限上下文和分层记忆重构）** 的三问（无限上下文 / 分层记忆 / **懒加载消息**）与 senera 这套机制是同一道题，而 senera 是目前见过**唯一把三件事都落地**的实现。源码位置：`Source/AgentSystem/Prompt/`（上下文编译器）、`TemporalMemory/`（时间摘要）、`Continuity/`（按需展开）、`Memory/`（artifact 投影）。

**分层上下文（`Prompt/`，源码级）**

- **11 层**：`kernel / persona / profile / lore / facts / graph / world / scene / memory / workflow / task`（`AgentPromptContextLayerTypes.ts`），每层带 manifest：`source`（runtime/preset/profile/continuity/request）、`stability`（**stable / turn / event**）、`included`。
- **3 档 + revision 身份**：`stable / context / volatile`，每档一个 revision；定义里写明用途——**区分「可复用的会话上下文」与「必须每轮重建的数据」**。
- **核心手法=稳定前缀与易变状态分离**：stable 档（kernel + 人设 + voice 示例 + 执行环境）**跨会话逐字节一致**；turn 档持久化为**一条隐藏的、仅追加的 `senera.turn_context` 消息，紧跟在所属用户消息之后**（旧快照留作历史证据，最新者权威）⇒ **provider 前缀保持字节稳定**，同时状态变化对模型可见。
- **渲染即校验**：`AgentPromptContextWireRenderer` 让每个块**声明允许的无损编码**（同类数组走 TOON、异构状态留 JSON），**严格解码后与源值比对，回环失败直接报错**（不降级成散文）；每块用**当前模型的 tokenizer** 计量。
- **只缓存稳定档**：`AgentPromptTierRenderCache`——"reuses rendered stable prompt tiers while keeping volatile turn data out of the cache"；cache scope 是宿主侧标识，**不作为指令**给模型。

**上下文策略（记忆/连续性/证据）**

- **TemporalMemory**：digest **层级 = `segment → day → month`**，状态机 `open/pending/sealed/failed/stale`；每条带 `summary / topics / openLoops / uri / childCount`，成员按 `ordinal` 归并 `episode|digest` ⇒ **时间维度的摘要树 + 可寻址 URI**。
- **Continuity 的按需展开**：`AgentContinuityEpisodeWindow` 用 `before/after` 做邻域展开——**模型选范围，宿主保序/分组/来源完整性**；再叠事实与条件求值（authority policy / condition evaluator / entity alias）。本质是**回传派生状态而非原始消息体**。
- **证据句柄**：工具结果发布为 artifact（带 receipt），给模型的只是投影 + URI，且投影时剥掉内部路由字段（宿主路径、manifest 等）。

**对上上游 #972 的映射**（#972 = re-ovo 2026-03-16 自记备忘、label enhancement、**至今零实现**）

| #972 的三问 | senera 的答案 | 我们的位置 |
|---|---|---|
| 分层记忆 | 11 层 + 3 档 + 时间摘要树 | 记忆有「显式可编辑 + 衰减打分 + 预算截断」，但是**单层**（无时间维度层级） |
| 无限上下文 | 摘要层级 + 追加式 turn_context + 字节稳定前缀（不是真无限，而是**可寻址的压缩历史 + 按需回取证据**） | 已有压缩检查点（token 驱动、区域指纹失效）——同一问题的另一条路线 |
| 懒加载消息 | ①不回传原始消息体，只回传派生状态 + 句柄 ②episode 窗口按需邻域展开 | 已有文件版近似（`tool_outputs` 落盘 + 截断 + `workspace_read_file` 回取） |

**对我们的借鉴（按性价比，2026-10-02）**

- **P1（值得做，半天~1 天）：易变状态搬出 system 块 + 隐藏追加式「轮次上下文」。** 我们的 system 块里混着**每轮会变**的内容（记忆注入段 `MEMORY`、注入条目段、渲染提示），于是「记忆刚更新」那一轮会**从 system 起整段前缀失效**——而隐式前缀缓存（OpenAI/Gemini 自动缓存）只看前缀**逐字节**是否相同。senera 的做法在我们这边**有现成先例**：压缩检查点本身就是「合成消息 + 前导标记」的追加式实现，照它加一层轮次上下文合成消息即可。**不依赖 #1971**（该字段已因主力端点 New API 不透传而搁置；隐式缓存不需要它）。**已实测（2026-10-02，真机 debug；见下表）**：稳态 99–100%，**一次记忆写入后掉到 72%**（6.9K 中 5.0K 命中），下一轮立刻回到 99% —— 证实「易变项变动只影响那一轮」，但**那一次的代价是「记忆块之后的全部内容」**。风险=把一部分 system 内容改成消息角色可能影响遵循度，需 A/B 与真机。
- **P2（小纪律，随时可做）**：①给拼装预览/容量面板的分段加 **stability 分组**（stable/turn/event）——正是刚落的「上下文组成明细」的自然延伸，一眼看出「哪些内容每轮都在变、因而不利缓存」；②把「投影必须无损回环校验、失败即报错」借到我们已有投影的两处（工具结果投影、拼装预览分段）。
- **P3（只记账、不排期）：时间维度摘要树**（segment/day/month）。价值是「很久以前仍能粗粒度召回」，但我们是**显式可编辑记忆**、senera 是**自动派生**——哲学不同，引入等于新增一类「日记/时间线」产品面（摘要作业、存储、召回、UI）。等「长期陪伴/时间线回溯」出现真实需求再评估。
- **维持现状**：证据句柄/artifact 投影我们已有文件版近似（见 §7.2）；TOON 那类表格化编码对我们意义有限（易变块不大）。

**实测数据（2026-10-02，同一会话；`glm-4.5-air` / 智谱AI开放平台；隐式前缀缓存）**

| 轮次 | 情况 | prompt | cached | 命中率 |
|---|---|---|---|---|
| M1 | 首轮（跨会话共享前缀） | 6.4K | 6.4K | **100%** |
| M2 | 稳态 | 6.5K | 6.4K | 99% |
| M3 | 稳态对照 | 6.6K | 6.6K | 100% |
| **M4** | **写入一条记忆后** | 6.9K | **5.0K** | **72%** |
| M6 | 记忆不变、恢复正常 | 7.0K | 6.9K | 99% |

- **失效范围＝记忆块之后的一切**：由「缓存量 5.0K ≈ 工具 schema 4.3K + 人设 0.6K」可反推序列顺序为 `工具 schema → 人设 → 记忆 → 引导/提示 → 历史`，所以记忆一变，**其后整段对话历史都要重算**（本次历史约 1.9K，故整体只掉 28%）。
- **放大规律（推断，按前缀缓存语义必然成立，但未实测长会话）**：单次失效量 ≈ 记忆块 + 历史，≈ **历史长度** ⇒ 在 5 万 token 的长会话里，同样一次记忆写入就是约 5 万 token 重算；而记忆是模型**主动**写的，成本按「会话长度 × 写入次数」计。
- **两个附带发现**：①最大的固定块（工具 schema，≈4.3K）排在可变部分**之前**，因此长期保住；②人设+工具这段**跨会话**也命中（M1 首轮即 100%），比预想更好。
- **P1 因此精确化为一句话**：把**记忆注入从 system 块挪到历史之后**（尾部合成消息，沿用压缩检查点「合成消息 + 前导标记」先例）⇒ 记忆变动只重算尾部，而不是整段历史。**未做**：长会话实测（当前结论基于一次小会话 + 前缀语义推断）。

**一句话结论**：senera 这套里**唯一值得现在动手的是「稳定前缀不被易变状态污染」**（且必须先量 cached 比例再定），其余两条是小纪律，第四类（时间摘要树）与我们的记忆哲学不同、只记账；#972 若上游真立项，本节映射表可直接当设计输入。

## 八、华灯（rikkahub-sillytavern-android）评估（2026-10-05，本地源码调研）

**仓库**：[MiaoWuNYA/rikkahub-sillytavern-android](https://github.com/MiaoWuNYA/rikkahub-sillytavern-android)「华灯」——RikkaHub 深度定制分支，**2150+ 提交**（最近合入上游 2.5.6），定位「通用聊天 + 安卓酒馆兼容」双路线（AGPL）。`DIVERGENCE.md` 是公开的**合并手册**（冲突分组/已回退项/工作流）——重度 fork 的工程实践范本，值得借鉴其形式。我们 09-17 的 ST 借鉴批次（槽位/世界书官方语义等）与之同源同域。

**高价值借鉴候选（按价值）**：

1. **前缀分叉诊断（`PromptDebugCache` + 诊断页）**——缓存每次生成实际发送的完整消息列表（按对话隔离、LRU 8 组、含上一次+本次），诊断页**定位上一轮与本次请求的第一条分叉消息并估算可命中前缀 tokens**，把「缓存为什么断在 6.7k 不再涨」变成可观察事实；另有 redaction 机制（插件注入内容在调试页遮蔽，防提取入口）。与拼装预览互补：我们做的是**单次请求逐段归因**（Tier1-4），他们做的是**跨请求差分定位**——对缓存线（#1542/PR #1630 的命中率追踪）是直接升级，小–中工作量。
2. **Jev 智能决策架构**（`data/ai/jev/`）——小模型（TypeSafe System One）路由层：记忆筛选 + 主模型快问快答。精髓在**契约设计**：`JevResult{Ok/Uncertain/Failed}` + 置信度阈值 + **Uncertain/Failed 一律静默回退原逻辑**（隐形决策层，判不出不得影响对话/弹错误）。我们 fastModel 已做标题/建议，该模式可平移（标题质量校验、记忆门控二次确认等）。中。
3. **R8 sealed 序列化 keep 家族 + SerializationKeepTest（5 项防回归）**——他们 release 实测「打开助手设置页闪退：Serializer for subclass LocalToolOption$ShowImage not found」。**触发模式=子类只被解码路径引用、无直接代码引用** ⇒ R8 判不可达删除。**fork 暴露面分析**：我们 `LocalToolOption` 全部子类在 `LocalTools.getTools`/开关页有直接引用 ⇒ 可达、安全（dex 层混淆改名后 grep 不可靠，按可达性分析）；但「新增 LocalToolOption/任何 @Serializable sealed 子类时必须有直接代码引用」应记为教训，或抄他们的 keep 规则 + 防回归测试（继承链 keep/不带 allowshrinking/$serializer+Companion 保留/通配符覆盖）。
4. **群聊**（GroupChatPage +1167 行 + GroupSpeakerSelector 说话人选择器）——多助手群聊，方向级大特性，fork 完全没有。产品决策问题非技术问题。
5. **宏引擎 2.0**（879 行，60+ 官方宏）+ **MVU 逐楼快照**（状态栏/世界书按楼显示）+ 斜杠命令——深酒馆兼容域。我们 09-17 吸收的是 ST 最小语义集（宏运行时/世界书/槽位）；60+ 官方宏与 MVU 变量系统是更深一层，受众=酒馆重度用户，按需。
6. **三层记忆/滚动压缩**（CrossWindowMemory/FixedMemorySection/MemoryEmbeddingService/VectorStoreCache）——与语义检索三方差评（不做）结论对照：他们的 RAG 记忆加了**相关性下限**（`d31c52ed5`，防低相关注入）——这个阈值思想对我们记忆注入通用。
7. **过程性小件**：工具描述去动态日期 + 按模式分叉（`b2748ade5`，与我们 cur_date 破缓存同源——已排查，fork 工具描述零动态日期 ✓）；memory_tool 描述瘦身 844 字符（`390eb225d`）；~~世界书预算不裁角色卡条目~~（`d31c52ed5`，**深评不吸收，2026-10-05**：fork 默认预算 0=不限制本就不裁，且卡内嵌世界书根本未导入（character_book 是 V2/V3 导入器的既有缺口）——它是「补 character_book 导入」立项时的**必带附属件**）；~~卡片正则三连锁修复~~（`5fdb74bc0`，**深评不吸收**：fork 不导入卡自带 `regex_scripts`，没有执行路径——但该提交是将来做卡正则时的**现成实现规格**：Kotlin vs JS 引擎三坑〔`${}` 当组引用抛异常 / `groups[name]` 对不存在组抛异常非 null / 反斜杠折叠毁正则字面量〕+ safeNamedGroup 降级 + StringBuilder 重载陷阱，全部有真实 157KB 卡测试背书）；Office 结构化读写（`342adadd9`，我们 document 模块只读）。

**不适用**：逆向工具链（已移除，APK 109→100MB 的路线）；Chaquopy Python 桥（我们 eval_javascript/MCP/Workspace 已覆盖且不做 Python）；离线 OCR（我们评估过不做）。

**建议优先级**：前缀分叉诊断（缓存线直接升级）> Jev 契约模式（架构级小改造）> R8 keep 家族（防回归测试形式）> 其余按需。前两项合计约 1 天。

## 九、agentd（hue913/agentd）评估（2026-10-08，仅远端 README + 自带评测数字调研）

**仓库**：[hue913/agentd](https://github.com/hue913/agentd)（Apache-2.0，Python，2026-10-08 仍在推）——自托管**运维** Agent：SSH/shell/文件工具、浏览器控制台 + 实时屏幕（Xvfb + x11vnc + noVNC，只经 SSH 隧道）、危险命令人工审批、多模型委员会，卖点是「无需训练、无需 GPU，越用越好」。**与我们不同源、不同域**（我们不是运维客户端），下面只记**可平移的机制**。

### 9.1 经验记忆 + 信用记账（最有价值，直接接我们的记忆线）

- **机制**：`(state, action, discounted_return)` 三元组入 SQLite；n-gram 检索（倒排索引）；advantage 重排；口头自评存档回顾；**每条记忆的信用记账 = 它是否真的被采纳**。作者自报可复现：ops 套件 40 eps × 12 seeds，memory OFF 均值 1.1/40 → ON 14.4/40；默认 seed 1/40 → 11/40（后半段 55%）。
- **对我们的意义两层**：①我们缺的从来不是「记录事实」而是**「上次这么干的结果」**——三个护栏都是硬阈值、**无记忆**，模型为什么失败、上次怎么成功全靠上下文重述；一张轻量「行动经验」表可作为重试/回退排序依据（**不需要梯度**，这正是它的卖点）。②**信用记账**补上记忆线的一个空白：现在的注入是「全塞或截断」，**无法知道一条记忆有没有起作用**；记忆 V2（渐进披露 + 按需 `memory_read`）天然产出**读取遥测**，是它在聊天场景的最小可用形态。
- **限定**：数字来自**合成噪声模型**的 ops 套件；web 套件（真实 Chromium）只有 +0.08、且一个 seed 为负。别当真实模型结论。

### 9.2 委员会（多模型评审）

- **机制**：独立提案 → **有边界的交叉质询**（只能挑错、不能重写方案；前序输出当**不可信输入**；质疑阶段不能改工具权限）→ 裁定；**异议单独成块、绝不平均成假共识**；成员权重 = 该成员在**类似局面**的历史胜率；**条件触发**（只在 top-2 差距很窄／动作危险／用户钉住时开会，其余一步一次调用）。
- **对本仓**：我们**无任何委员会/评审**（grep 零命中）。可借的是**形态而非成本模型**——条件触发那一句足以把它做成「只在高风险工具调用或用户点名的那一轮」才开的小功能；成员权重是一张普通表，不需要训练。**「异议单独成块」**与本仓重视可核对的取向一致。

### 9.3 值得记的小件

能力探针 + **三档解码回退**（`logprobs + top_logprobs` → k 次采样频率 → 模型自评 0–100），且**缺 key 的席位大声弃权、绝不静默失败**；危险命令**分类后才执行**，待批请求显示**命令/理由/主机**（`rm -rf`/`mkfs`/`DROP TABLE`/`force-push`/`curl | sh` 一类的分类可直接用 ⇒ 我们的审批卡可补「为什么要你批」的理由行）；**每步 token 账本**（可与账单核对——我们已有用量显示，可加「本轮估算 vs 上游回报」的对照）；**稳定前缀供 provider 侧缓存** + observation compaction（与本仓缓存线同向）；无依赖 cron（`agentd schedule`）+ 停机后一次性补跑（对应我们「定时任务」待办）；**记忆导出 = 签名 pack + 人类可读 Markdown**，导入幂等、来源不一致时告警（正对我们「对话导出不含记忆库、只能整库备份迁移」的缺口）。

**不借**：SSH/服务器运维/屏幕流/PTY 终端（领域不同）；Tauri 桌面客户端。

**建议**：①信用记账理念并入记忆 V2 的评估（见 `roadmap.md` 2026-10-08 节）；②审批卡「理由行」小且用户可见，可独立做；③委员会与经验记忆**只记账**，等有真实需求（如高风险工具误改）再立项。
