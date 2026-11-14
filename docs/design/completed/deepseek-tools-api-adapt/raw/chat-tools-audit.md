# DeepSeek 内置工具适配 · 聊天工具链路审计（raw）

> 性质：**只读审计草稿**（read-only audit），供后续设计稿/实施单引用。本文不含实现代码，不改任何源码。
> 审计对象：`F:\build\projects\RikkaHub-Neomix`（Kotlin/Compose，模块 `app` / `ai` / `search` / `document` / …）。
> 审计时间：2026-11-13（工作区时间，Asia/Shanghai）。
> 口径说明：**仓内证据**一律给 `文件:行号`；DeepSeek 官方契约事实（计费单价、块形态）不重复摘编，统一引用同目录伙伴文档 [`01-api-reference.md`](../01-api-reference.md) 的 §7/§8，本文只用它做「契约假设」，不当仓内事实。无法从仓库确认的一律标 **未知**。
> 包名提示：本 fork 已把包名从上游 `me.rerere.*` 改为 `app.androidrik.*`，下文全部按实际路径书写。

---

## 0. 结论速览（按实施优先级）

| # | 结论 | 证据 | 影响 |
|---|---|---|---|
| 1 | 仓内**已有** `BuiltInTools`（模型级内置工具集合）与 `UIMessagePart.ServerTool`/`StreamChunk.ServerTool*` 一整套「服务端工具」抽象；DeepSeek 内置工具应挂在**模型级 `BuiltInTools`** 上，而不是新增助手字段或全局开关 | `ai/.../provider/Model.kt:34,77-92`；`ai/.../ui/UIMessagePart.kt:161-169`；`ai/.../ui/StreamChunk.kt:73-101` | 数据模型零迁移（默认 `emptySet()` + `@SerialName`），只加枚举值 |
| 2 | **Chat Completions 链路完全没有**服务端内置工具支持：不发出 `BuiltInTools`、不解析 `server_tool_calls`、回传时 `ServerTool` part 被静默丢弃 | `ChatCompletionsAPI.kt:559-582`（只发 function tools）、`898-978`（只解析 `tool_calls`/`annotations`/`images`）、`620-683`（`addAssistantMessages` 只分组 Content/Tools） | DeepSeek 默认走的就是这条链路（`DefaultProviders.kt:111-122` 未开 `useResponseApi`），是**最大工作量** |
| 3 | Responses 链路**已有**内置工具与服务端工具闭环，但内置工具 `type` 写死为 OpenAI 名字 | `ResponseAPI.kt:476-483`（`{"type":"web_search"}`）、`582-587,738-769`（回传）、`1036-1063`（`*_call` 泛化解析） | 需按 provider/端点做 `web_search` → `deepseek_web_search` 的映射，不能全量改常量 |
| 4 | 现有「内置搜索 ↔ 外挂搜索」互斥守卫只看 `BuiltInTools.Search` 一个值 | `ChatToolFactory.kt:50-52`；`SearchPicker.kt:167-178` | 新增 DeepSeek 变体后若不扩守卫，会**双搜索 + 双重计费** |
| 5 | 计费次数只在末端 `usage` 里（`server_tool_use` 的两套计数器），而 `TokenUsage`/`usage_record` 只存 4 个 token 字段 | `ai/.../core/Usage.kt:6-11`；`UsageRecordEntity.kt:25-52`；`ChatCompletionsStreamDecoder.kt:192-204`；`ChatCompletionsAPI.kt:1001-1012` | 今天**无法表达非 token 工具费**；最小改动是扩 `TokenUsage` + 期末 `usage` 解析；持久化到统计页则再走一次 Room AutoMigration |
| 6 | 流式/非流式注解解析遇未知 `type` **硬抛**，会把整轮生成打成失败 | `ChatCompletionsAPI.kt:981-995`；`ChatCompletionsStreamDecoder.kt:179-189` | DeepSeek 新版引用/块形态一旦不是 `url_citation`，整轮崩；上线前必须放宽为跳过+日志 |
| 7 | `models.dev` 数据在本仓被 **slim**，`cost` 等字段被丢弃；仓内没有任何价格模型 | `ModelsDevCatalog.kt:78-95`；全仓 grep `pricing/cost/pricePer` 无命中 | 工具费价格只能走本地价格表（CNY/千次），不能指望 catalog 自动带价 |
| 8 | 内置工具声明后服务端会**接管温度/top_p 等参数**，且「用过内置工具的回复不能续写」；本仓有自动续写开关 | 伙伴文档 §8「被忽略的参数」；`ProviderSetting.kt:81`（`autoContinueOnLengthTruncation`） | 需在 UI/提示层显式告知，否则「参数无效」「续写 400」被视为 bug |

---

## 1. DeepSeek 供应商定义与协议面

### 1.1 出厂定义（唯一内置条目）

`DefaultProviders.kt:111-122`：

```kotlin
ProviderSetting.OpenAI(
    id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
    name = "DeepSeek",
    baseUrl = "https://api.deepseek.com/v1",   // :114
    apiKey = "",
    builtIn = true,
    quotaQuery = QuotaQuery(enabled = true, url = "{{baseUrl}}/user/balance",
        resultPath = "balance_infos[0].total_balance"),
)
```

要点：

- 类型是 `ProviderSetting.OpenAI`（API Key 类），**没有**显式 `useResponseApi`/`chatCompletionsPath`，因此走默认 `useResponseApi=false` + `/chat/completions`（`ProviderSetting.kt:231-251`，字段见 `:247-250`）。
- 出厂条目**没有** `models`（模型靠「拉取模型列表」或用户手填），也没有任何 `BuiltInTools` 预设。
- 同文件里 `xAI` 条目示范了 `useResponseApi = true` 的写法（`DefaultProviders.kt:244`）——即「同一 OpenAI 兼容提供商可以整体切到 Responses」，这是 DeepSeek 三协议里 Responses 的现成入口。

### 1.2 三种协议在仓内的落点与就绪度

DeepSeek 官方三协议（Chat Completions / Responses / Messages）在仓内**不是三种 provider 类型**，而是三种配置路径：

| 协议 | 仓内配置方式 | 实际实现 | 内置工具现状 | 缺口 |
|---|---|---|---|---|
| Chat Completions | `ProviderSetting.OpenAI` 默认（`baseUrl=https://api.deepseek.com/v1`，path `/chat/completions`） | `OpenAIProvider.streamText` → `ChatCompletionsAPI`（`OpenAIProvider.kt:166-182`） | **无**：`BuiltInTools` 只在 Google 与 ResponseAPI 被消费（`GoogleProvider.kt:457-467`、`ResponseAPI.kt:474-491`） | 发工具、解析 `server_tool_calls`、多轮回传、引用渲染 |
| Responses | 同一条目把 `useResponseApi` 打开，`responsesPath=/responses`（`ProviderSetting.kt:248,250`；UI 见 `ProviderConfigure.kt:328-357`） | `OpenAIProvider` → `ResponseAPI`（`OpenAIProvider.kt:166`） | 有：`BuiltInTools.Search → {"type":"web_search"}`（`:476-479`），并有服务端工具回传（`:582-587,738-769`）与 `*_call` 解析（`:1036-1063`） | `type` 名字映射（DeepSeek 要 `deepseek_web_search`）、`deepseek_read_url` 变体、`status=content_filter` 拒答映射（见 §6） |
| Messages（Anthropic 兼容） | 需要用户**自建** `ProviderSetting.Claude`，`baseUrl=https://api.deepseek.com/anthropic`（`ProviderSetting.kt:388-407`；出厂无此条目） | `ClaudeProvider`（`ProviderManager.kt:62-67`） | 部分：流解码已能识别 `*_tool_use`/`*_tool_result` 服务端块（`ClaudeProvider.kt:632-645`；`ClaudeStreamDecoder.kt:108-122`），但请求侧不发任何内置工具（工具只在 `params.tools` 循环里声明，`ClaudeProvider.kt:326-334`） | 声明 `{"type":"deepseek_web_search","name":...}`、`server_tool_use`/结果块回传（含 `signature`）、URL 只读 `deepseek_read_url` |

> 结论：**不要为 Messages 单独造 provider**；先按「OpenAI 兼容 + 可选 Responses」做，Messages 作为第二条线（用户自定义 Claude 型 provider 已能连上，只是内置工具不全）。

### 1.3 customHeaders / customBody 通道（评估「零代码试水」能力）

- 提供商级自定义头：`ProviderSetting` 抽象字段 `customHeaders`（`ProviderSetting.kt:176`），OpenAI 条目 `:237`、Claude 条目 `:395`；实际应用见 `ChatCompletionsAPI.kt:117,214`（`applyCustomHeaders`）。
- 模型级自定义头 + 自定义请求体：`Model.customHeaders` / `Model.customBodies`（`Model.kt:29-30`）→ 生成时与助手级合并进 `TextGenerationParams`（`GenerationHandler.kt:1608-1624`，`customHeaders=:1615`，`customBody=:1619`）→ 请求体组装末尾 `mergeCustomBody`（`ChatCompletionsAPI.kt:583`、`ResponseAPI.kt:494`；实现 `Request.kt:137-158`）。
- 语义要点：`mergeCustomBody` **同名 key 直接替换**（两者都是 JsonObject 时才递归合并，`Request.kt:137-158`）。因此用户今天就能手写 `customBodies=[{"key":"tools","value":[…]}]` 注入 `deepseek_web_search`——**但这会把整个 `tools` 数组替换掉**，本仓已装配的本地工具（含 `search_web`）会从请求里消失，属于「能跑但危险」的试水手段，不适合作为产品化路径。
- 另一个已踩过的坑：`mergeCustomBody` 覆盖 `reasoning_effort` 等键时无提示，仓内已有标注 `REASONING_OVERRIDE_BODY_KEYS`（`Request.kt:121-135`）用于提示。若把内置工具也走 customBody，应复用同款「覆盖必须提示」策略。

---

## 2. 工具调用全链路（装配 → 发送 → 执行 → 回填）

### 2.1 装配点：`ChatToolFactory`（唯一装配点）

- 入口 `createTools`（`ChatToolFactory.kt:119-160`）与 `createToolSegments`（`:173-203`）按来源分段：Memory / Search / Local / Conversation / Workspace / Skills / Subagent / MCP / Plugin。
- 调用点：`ChatService.kt:1612`（`chatToolFactory.createTools(...)`），把工具列表透传给 `GenerationHandler`。
- 与内置工具直接相关的**唯一闸门**（`ChatToolFactory.kt:50-52`）：

```kotlin
internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}
```

  使用处 `:277-278`：`segments += ToolSegment(ToolSource.Search, createSearchTools(settings).toList())`。
  → **新增 `BuiltInTools.DeepSeekWebSearch` 后，此函数不改就会同时注册外挂搜索**（见 §6.1）。

- 装配产物的来源枚举是类型化契约（`ToolSource.kt:20-60`）；子代理信封按段名（`"search"` 等）授权（`ChatToolFactory.kt:162-172` 注释）。若未来要区分「原生搜索段」，必须新增 `ToolSource` 值而不是复用 `Search`，否则子代理信封语义被改写。

### 2.2 请求面：工具定义怎么发给模型

- `TextGenerationParams` 携带 `tools: List<Tool>`、`customHeaders`、`customBody`（`Provider.kt:66-96`，字段 `:84,86,87`）。
- Chat Completions 只在 **模型具备 TOOL 能力且本地工具非空**时才补 `tools`/`tool_choice`（`ChatCompletionsAPI.kt:559-582`）：

```kotlin
if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
    put("tool_choice", "auto")
    putJsonArray("tools") { params.tools.forEach { /* {"type":"function",...} */ } }
}
```

  → 这是**关键卡点**：DeepSeek 内置工具是「没有 `function` 壳的裸 `{"type": ...}` 项」，若模型没有 TOOL 能力或本轮没有本地工具，当前代码连 `tools` 键都不会写。需要把条件扩成 Response API 那种 `|| params.model.tools.isNotEmpty()`（对照 `ResponseAPI.kt:451-454`）。
- Responses 已有正确形态（`ResponseAPI.kt:451-491`）：function 工具与内置工具**写在同一个 `tools` 数组**，内置工具项是 `{"type":"web_search"}` / `{"type":"image_generation",...}`（`:476-491`）。
- Google 端内置搜索是另一套（`GoogleProvider.kt:457-467`，`google_search` + `url_context`），可作为「按 provider 分派内置工具 type」的参考写法。

### 2.3 执行面：本地工具如何执行（模型内置工具**不经过**这里）

- 步进循环：`GenerationHandler.kt:570`（`for (stepIndex in 0 until maxSteps)`），上限 `maxSteps: Int = 256`（`:380`）。
- 工具执行：`prepareExecutableTool`（`:1003-1069`，去重/循环护栏/上下文预算）+ `runPreparedTool`（`:1070-1130`，`toolsInternal.find { it.name == tool.toolName }` → `executeToolWithTimeout` → 回填 `output`）。
- 审批：`ToolApprovalState`（`Pending/Approved/Denied/Answered`，`:1300-1330` 段）；只读工具可进并行车道（`concurrentSafe`，`Tool.kt:46-53`）。
- **模型内置工具（`BuiltInTools`）不进入这个执行器**：它由服务端执行、以流式 chunk 回来（`StreamChunk.ServerTool*`），本地只负责"显示 + 回传"。这是本次适配必须尊重的边界——不要给 `deepseek_web_search` 造一个本地 `Tool`，否则会重复执行/重复计费。

### 2.4 回填与多轮回传：`ServerTool` 链路

已有的通用抽象（可直接承接 DeepSeek 的服务端工具块）：

| 层 | 类型 | 位置 |
|---|---|---|
| 流事件 | `StreamChunk.ServerToolStart / ServerToolInputDelta / ServerToolInputEnd / ServerToolEnd`（含 `status`、`metadata`） | `ai/.../ui/StreamChunk.kt:73-101` |
| 消息 part | `UIMessagePart.ServerTool(toolCallId, toolName, input, output, status, metadata)` | `ai/.../ui/UIMessagePart.kt:161-169` |
| 折叠 | `StreamChunkHandler` 处理 4 个事件并按 id 合并 | `ai/.../ui/StreamChunkHandler.kt:158-220` |
| UI | 思考链 `ThinkingStep.ServerToolStep` + `ChatMessageServerToolStep` | `ChatMessageCot.kt:17-31,85`；`ChatMessage.kt:613-615` |
| 元数据 | `ServerToolProtocol / ServerToolMetadata`（记录原始协议与 `call` JSON） | `MessageMetadata.kt:89-97` |

现状分裂：

- **Responses 已闭环**：请求侧 `addServerToolItem`（`ResponseAPI.kt:738-769`），只回传 `protocol == OPENAI_RESPONSES` 的 part（`:741`）；解析侧 `isOpenAIServerToolCall` + `toOpenAIServerTool`（`:1036-1063`）。
- **Chat Completions 未闭环**：`addAssistantMessages` 只把 part 分成 `Content`/`Tools`（`ChatCompletionsAPI.kt:620-683`），`ServerTool` 既不是 `Text/Image` 也不在 `group.tools` 里 → 发送时**静默丢弃**。对 DeepSeek 而言这意味着多轮对话里「上一轮搜索到的加密/签名结果」不会回传（伙伴文档 §8 明确 read_link 的 `signature` 必须原样回传），后果可能是服务端 400 或上下文断裂（**具体行为未知**，需真机验证）。
- **Claude/Messages**：流解码能识别服务端工具块（`ClaudeProvider.kt:632-645`），但请求侧没有回传分支（grep `ServerTool` 在 `ClaudeProvider.kt` 仅命中上述判定函数），`signature` 回传只覆盖 thinking 块（`:498-504`）。

---

## 3. 搜索工具注册与「模型原生搜索」共存

### 3.1 现有外挂搜索链（App 侧）

1. 助手开关：`Assistant.enableWebSearch`（`data/model/Assistant.kt:172`，默认 false）——**每助手独立**，聊天页开关写入助手配置（`ChatPage.kt:586-588`）。
2. 选服务：**全局** `Settings.searchServiceSelected: Int` + `searchServices` 列表（`data/datastore/Settings.kt:148-150`），换服务写全局设置（`ChatPage.kt:689-694`）；不是每助手配置。
3. 装配：`createSearchTools(settings)`（`SearchTools.kt:26-127`）
   - 先判可用性：选中服务被停用 ⇒ **不注册任何搜索工具**（`:29-31`，等价「本会话关联网」）；
   - `search_web`（`:45-89`）：工具描述按服务真实 schema 能力拼（日期/域名过滤、是否有 scrape，`:40-52,133-172`）；结果把 `SearchResult` 转 JSON，注入短 `id`/`index` 供引用（`:74-85`）；
   - `scrape_web`（`:91-125`）：仅当服务实现 `scrapingParameters` 才注册（`ChatToolFactory`/`SearchTools.kt:44`）。
   - 引用协议：工具描述里强制模型用 `[citation,domain](id)` 形式（`:159-171`）。
4. 服务注册表：`SearchService.getService(options)`（`SearchService.kt:69-90`），`DeepSeekOptions` 注册在第 `:72` 行；选项定义 `:349-356`；默认服务是 Bing `:178`。

### 3.2 DeepSeek 的两条搜索路（互斥关系）

| 路径 | 触发 | 通道 | 计费 | 现状 |
|---|---|---|---|---|
| A. App 侧搜索 | 助手 `enableWebSearch=true` 且模型无 `BuiltInTools.Search` | `search_web`/`scrape_web` → `SearchService`（可选 `DeepSeekSearchService`） | 独立 Search/Fetch API：¥5/千次成功（伙伴文档 §7） | **已完整实现**：`DeepSeekSearchService.kt:62-63`（`/v1/tools/search`、`/v1/tools/fetch`），与聊天同一把 Key（同文件 `:42-59` 文档注释），账号级 QPS 5、多 Key 不能绕过 |
| B. 模型内置搜索 | 模型 `tools` 含 DeepSeek 原生工具 | 聊天请求 `tools[]` 声明，服务端执行 | 同价，但按「查询词条/成功页数」计且在 token 之外叠加（伙伴文档 §8） | **未实现**（见 §1.2） |

**会双计费的组合**：助手开着联网搜索（路径 A 注册了 `search_web`）+ 模型又声明了 `deepseek_web_search`（路径 B）。今天 `shouldUseExternalWebSearch` 只排除 `BuiltInTools.Search`，`DeepSeekSearchService` 作为搜索服务被选中时也能同时成立。

### 3.3 「per-model 原生工具开关」是否贴合现有设置/数据模型

**贴合，且不需要新设置字段。** 现有数据模型已具备三层：

- 模型级 `Model.tools: Set<BuiltInTools>`（`Model.kt:34`，默认空集）——序列化用 `@SerialName` 的可扩展 sealed class（`Model.kt:77-92`），旧数据零迁移；
- 模型编辑表单已有专属入口 `BuiltInToolsSettings`（`ModelSettingsForm.kt:450-490`，`availableTools` 列表 `:470-486`）；
- 聊天页已有「内置搜索优先、隐藏外挂搜索」的交互（`SearchPicker.kt:170-186`：`hasBuiltInSearchEnabled` 为真时不显示 App 搜索服务设置）。

要补的只有三处判定/清单：

1. `SearchPicker.kt:167-169` 的 `supportsBuiltInSearch` 目前硬编码 `ModelRegistry.GEMINI_SERIES.match(...) || model.modelId.contains("gpt-")` → 需扩展 DeepSeek 判定（或改为「查 capability 表」）；
2. `SearchPicker.kt:77,92,170` 三处 `contains(BuiltInTools.Search)` → 需覆盖新变体（否则图标/开关状态不对）；
3. `ChatToolFactory.kt:51` 的互斥守卫（同上）。

其余广度点（改枚举时必须同步，否则 Web 设置页/导入导出会退化成裸值）：`WebSettingsDto.kt:273-277`、`SettingsRoutes.kt:198-203`（字符串 ↔ 枚举）、`UpstreamConversationConfig.kt:41`（上游会话配置导入时的 `builtInSearch` 映射）、`ModelSettingsForm.kt:470-486`。

---

## 4. 用量与费用记账

### 4.1 现状：只有 token，没有任何价格/费用概念

- 数据结构：`TokenUsage(promptTokens, completionTokens, cachedTokens, totalTokens)`（`ai/.../core/Usage.kt:6-11`）；多步工具轮累计语义见 `accumulateRound`（`:38-56`，completion 累加、prompt/cached 取最新）。
- 落库：`UsageRecorder` 把 provider 包一层（`UsageRecorder.kt:81-82`），`streamText` 在 `finally` 里用 `merge` 后的最大 usage 记一行（`:124-144`，写库点 `:141`；`generateText` 见 `:116-122`），空 usage 不记（`:91`）。
- 来源枚举：`UsageSource` 8 个值（`UsageRecorder.kt:28-52`，注释明确「新增值必须同时在 StatsPage 给展示名」）；归属 `UsageTag(source, assistantId, conversationId)`（`:60-64`）。聊天主路径用 `UsageSource.CHAT`（`ChatService.kt:1872-1873`）。
- 表结构：`usage_record` 只有 4 个 token 列（`UsageRecordEntity.kt:25-52`）；**无费用列、无工具调用次数列**。
- 展示：统计页只按 token/来源/模型聚合（`StatsPage.kt:1039-1049` 来源名映射）；聊天内 token/tok-s/耗时/步数在 nerd line（`ChatMessageNerdLine.kt:66,143,161`）。全仓 grep `pricing|pricePer|costUsd|TokenPrice|modelPrice` **零命中**。

→ **结论：今天无法表达「非 token 的工具费」**，哪怕只做展示也没有承载字段。

### 4.2 DeepSeek 把计费次数放在哪里

契约事实（伙伴文档 §8）：最终 `usage` 里的 `server_tool_use` 有两套计数器——`web_search_requests`/`web_fetch_requests` 是**展示用**，`tool_search`/`tool_fetch`/`tool_image_search` 才是**计费次数**；搜索按查询词条计、抓取按成功网页计；`safety_tokens` 按输出 token 计。

本仓现状：请求侧已带 `stream_options.include_usage=true`（`ChatCompletionsAPI.kt:388-394`），所以末端 usage 能拿到；但解析函数只挑 4 个 token 字段、其余字段丢弃（`ChatCompletionsStreamDecoder.kt:192-204`；非流式 `ChatCompletionsAPI.kt:1001-1012`）。**没有任何地方读取 `server_tool_use`。**

### 4.3 最小设计（三档，按投入递增）

| 档 | 做法 | 改动面 | 能力边界 |
|---|---|---|---|
| A. 仅展示（推荐第一步） | ① `TokenUsage` 增 `toolSearchRequests/toolFetchRequests: Int = 0`（可序列化，旧 JSON `ignoreUnknownKeys` ⇒ 无迁移）；② 两处 usage 解析读 `server_tool_use.tool_search/tool_fetch`；③ nerd line 在 `ChatMessageNerdLine.kt:66` 附近按本地价格表**估算**费用（¥5/千次 ⇒ 每次 ¥0.005；`cachedTokens` 等 token 费不计或另算） | `Usage.kt`、两个 decoder、`ChatMessageNerdLine.kt` + 价格常量 | 单条消息可看「这次搜索花了多少」；统计页仍只有 token；历史消息无该字段 ⇒ 显示「—」 |
| B. 落库进统计 | 在 A 之上给 `usage_record` 加 `tool_search_count`、`tool_fetch_count`、`tool_cost_micros` 三列 | `UsageRecordEntity.kt` + `UsageRecorder.record`（`:84-110`）+ `AppDatabase.kt:96` 版本 68→69 + `AutoMigration(from=68,to=69)`（模式见 `AppDatabase.kt:212-213`）+ `StatsPage`/`StatsVM` 新增一行 | 统计页可出「工具费」与总额；迁移成本低（**本仓 68 版就是一路 AutoMigration 上来的**） |
| C. 通用价格表 | 新建 `data/pricing`：`providerId+modelId+tool` → `{currency, unit, pricePerUnit}`，token 与工具费统一计价，设置页可编辑 | 新表 + 设置页 + 计价器 | 面向其他 providers（Gemini/OpenAI 原生搜索）复用；**不建议首版做**，需求未验证 |

补充口径建议（写进设计稿，避免以后对不上账）：

- 费用是**估算**：以最终 usage 的计费计数器为准，`web_search_requests` 不可用（展示计数器含错误块）；服务端返回缺失时显示「未知」而不是 0。
- 币种与单位：官方中/英文档分别给 CNY/USD，本仓 UI 面向国内用户 → 固定 CNY、按「千次」换到「次」。
- 拒答轮次 token 照常计费（伙伴文档 §8），token 部分沿用现有口径即可，不必特殊处理。

---

## 5. 模型目录与能力位

### 5.1 `ModelRegistry`（规则表，token 匹配）

- DeepSeek 族定义在 `ai/.../registry/ModelRegistry.kt:315-381`，全部在 `ALL_MODELS` 列表里注册（`:703-713`）。关键条目：
  - `DEEPSEEK_FLASH`（`:340-344`）：`tokens("deepseek","flash")` + `visionInput()` + `toolReasoningAbility()`；**无 `contextLength` 声明**；
  - `DEEPSEEK_V4_FLASH`（`:346-349`）、`DEEPSEEK_V4_1_FLASH`（`:351-356`：vision + toolReasoning + `contextLength(1.m)`）、`DEEPSEEK_V4_FLASH_VISION_EXP`（`:359-363`，更长 token ⇒ 优先匹配）；
  - 解析优先级：`models.dev` 命中优先，未命中才回退 token 规则（`ModelRegistry.kt:766-806`，`MODEL_ABILITIES` `:784-795`、`MODEL_CONTEXT_LENGTH` `:797-806`）。
- 能力位只有两类：`ModelAbility.TOOL` / `REASONING`（`Model.kt:70-74`），模态 `TEXT/IMAGE`（`:64-68`）。**没有「原生搜索/原生读链接」能力位**——这正是用 `Model.tools: Set<BuiltInTools>` 表达的部分。

### 5.2 `models.dev` 数据（实际快照）

- 加载：`ModelsDevUpdater` 先读本地缓存、退回 assets 快照，再每日刷新（`data/remote/ModelsDevUpdater.kt:15-56`；启动接线 `RikkaHubApp.kt:733` 附近）；数据源 `https://models.dev/api.json`。
- 快照里官方 `deepseek` provider 的 4 条（`app/src/main/assets/models_dev.json`，本地实测）：

| id | modalities | tool_call | reasoning | limit.context | canonical |
|---|---|---|---|---|---|
| `deepseek-flash` | text+image | true | true | 1048576 | `deepseek/deepseek-v4.1-flash-1017` |
| `deepseek-v4-flash` | text+image | true | true | 1048576 | 同上 |
| `deepseek-v4-flash-vision-exp` | text+image | true | true | 1048576 | 同上 |
| `deepseek-v4-pro` | text | true | true | 1048576 | `deepseek/deepseek-v4-pro-0813` |

- **schema 被裁过**：`ModelsDevCatalog` 只保留 `modalities/tool_call/reasoning/limit/canonical_model_id`（`ModelsDevCatalog.kt:78-95`），上游的 `cost` 字段因 `ignoreUnknownKeys` 被直接丢弃，`slim()` 也不会写回（`:87-95`）。→ 价格不可能从 catalog 自动带出（呼应 §4.3-C）。

### 5.3 适配需要新增/修改什么

| 项 | 位置 | 内容 | 是否必须 |
|---|---|---|---|
| 新 `BuiltInTools` 值 | `Model.kt:77-92` | `@SerialName("deepseek_web_search") data object DeepSeekWebSearch`、`@SerialName("deepseek_read_url") data object DeepSeekReadUrl`；可选 `DeepSeekSafetyCheck`（`safety_check` 是顶层参数而非 tools 项，是否做成枚举待议） | 必须 |
| 表单清单 | `ModelSettingsForm.kt:470-486` | 加两项标题/描述（"+ 字符串资源"） | 必须（否则用户无法开） |
| 支持判定 | `SearchPicker.kt:167-169` | `supportsBuiltInSearch` 扩展 DeepSeek（建议改为「模型是否有任一搜索类 BuiltInTools」） | 必须 |
| 互斥守卫 | `ChatToolFactory.kt:50-52` | 把新变体纳入「已有原生搜索 ⇒ 不注册外挂」 | 必须 |
| Web 设置映射 | `WebSettingsDto.kt:273-277`、`SettingsRoutes.kt:198-203` | 字符串 ↔ 枚举 | 必须（否则 Web 面板读到未知值） |
| 上游导入映射 | `UpstreamConversationConfig.kt:41` | `builtInSearch` → 是否映射到 DeepSeek 变体（建议仍映射 `Search`，按 provider 再翻译协议值） | 建议 |
| registry 能力 | `ModelRegistry.kt` | 不需要加能力位；`deepseek-flash` 已具备 tool+reasoning+vision（来自 catalog） | 不需要 |

> 设计判断：内置工具是**模型属性**（同一 provider 下不同模型得不同工具能力），不是助手属性，也不是全局开关。仓内已有先例（Gemini/GPT 的 `BuiltInTools.Search`），复用同一模式可把迁移/序列化风险降到零。

---

## 6. 风险清单

### 6.1 双重计费（最高优先）

- 触发链：`assistant.enableWebSearch=true`（`Assistant.kt:172`）→ `ChatToolFactory.kt:277-278` 注册 `search_web`/`scrape_web` → 若搜索服务选的是 DeepSeek（`SearchService.kt:72`，`DeepSeekOptions` `:349-356`）→ 走独立 `/v1/tools/search`、`/v1/tools/fetch`（`DeepSeekSearchService.kt:62-63`，¥5/千次）；
  同时若模型 `tools` 含 `deepseek_web_search`/`deepseek_read_url` → 服务端再按查询/页计一次，**token 之外叠加**。
- 现有守卫只认 `BuiltInTools.Search`（`ChatToolFactory.kt:51`），新变体天然逃逸。
- 建议：守卫改为「模型有任一搜索类内置工具 ⇒ 不注册外挂搜索」；UI 侧沿用 `SearchPicker.kt:170-186` 的互斥展示，并在开启时给出「将按次计费」提示。

### 6.2 引用/注释解析硬失败（会打断整轮）

- `ChatCompletionsAPI.kt:981-995` 与 `ChatCompletionsStreamDecoder.kt:179-189` 对未知 annotation `type` 直接 `error("unknown annotation type: $type")`；`type` 缺失也 `error`。
- DeepSeek Chat Completions 的引用是 `annotations[]` 的 `url_citation`（含 `start_index/end_index`），现有 `UrlCitation` 只有 `title/url`（`UIMessageAnnotation.kt:30-36`），**索引字段丢失** → 只能按数组顺序整体渲染（`ChatMessage.kt:830,859`），无法做「句末角标」。
- 建议：解析侧先放宽（未知 `type` 跳过 + 日志，`type` 缺失也跳过），再按需扩 `UrlCitation` 或新增注解类型。⚠️ 这是**行为变更**（现有错误会被掩盖），需要一并决定是否只对 DeepSeek 生效。

### 6.3 参数被服务端接管（用户可见的「设置无效」）

- 伙伴文档 §8：声明内置工具/`safety_check=high`/`title` 后，`temperature/top_p/frequency_penalty/.../parallel_tool_calls` 被忽略。本仓会照常发送这些参数（`ChatCompletionsAPI.kt:383-384` 等）。
- 风险：用户调温度无效果，被当成 bug 报。建议在模型/助手设置页对「已开内置工具的 DeepSeek 模型」显示提示，或与既有 `REASONING_OVERRIDE_BODY_KEYS` 提示机制统一（`Request.kt:121-135`）。

### 6.4 续写路径会 400

- 伙伴文档 §8：用过内置工具的回复**不能续写**（400）。
- 本仓有自动续写开关 `RetrySetting.autoContinueOnLengthTruncation`（`ProviderSetting.kt:72-81`，默认关）+ 手动「继续生成」；另有半截保留/重试策略（`:66-71`）。
- 建议：当该轮出现 `ServerTool` part 且模型开了原生工具时禁用/提示续写；至少不要让自动续写在必 400 的路径上重试烧钱。

### 6.5 拒答/内容安全（合规）

- 三种协议拒答形态不同（Messages `stop_reason=refusal`、Responses `status=incomplete`+`content_filter`、Chat Completions `finish_reason=content_filter`；伙伴文档 §8）。本仓已统一识别 `finishReason`（`Message.kt:749-760` `isContentFilterTruncation`，含 `REFUSAL/CONTENT_FILTER/SAFETY/...`），**Chat Completions 侧可直接复用**；Responses 的 `status=incomplete` 是否已映射到 `finishReason` 需核（**未知**）。
- 合规：官方要求「对公众提供服务」时开启 `safety_check`。本仓内置 Ktor Web 服务器可把聊天能力暴露到 LAN/远端（`web/Entry.kt:15-33`；聊天路由 `app/src/main/java/app/androidrik/rikkahub/web/routes/ConversationRoutes.kt:49,58`），若用户以 `0.0.0.0` 起服务供他人使用，就可能落入该义务。→ 设计稿需明确：(a) 是否默认对 DeepSeek 官方端点强制 `safety_check=high`；(b) 是否在开启 Web server 时提示合规责任；(c) 「AI 生成内容标识」等属于分发侧义务，客户端只能提示。仓内目前**没有任何 `safety_check` 相关代码**（grep 零命中）。

### 6.6 协议差异与回传

| 风险 | 证据 | 说明 |
|---|---|---|
| Chat Completions 不发内置工具 | `ChatCompletionsAPI.kt:559` 条件只看 `params.tools` | 需改条件并新增裸 `{"type":...}` 数组项 |
| Chat Completions 不解析服务端工具 | 全仓 `server_tool` 解析只在 `ResponseApiStreamDecoder.kt:140-190`、`ClaudeStreamDecoder.kt:108-122` | 需新增 `message.server_tool_calls[]` → `StreamChunk.ServerTool*`（形态见伙伴文档 §8，按「步」分组 `index/reasoning_content/calls[]`） |
| 多轮回传丢 `ServerTool` | `ChatCompletionsAPI.kt:620-683` | 需在 assistant 消息里回写 `server_tool_calls`（含服务端要求的加密内容/签名，具体字段 **未知**，需真机抓） |
| Responses 内置工具 `type` 写死 | `ResponseAPI.kt:476-479` | 需映射成 `deepseek_web_search`；`deepseek_read_url` 目前无对应分支（`:481-491` 只有 UrlContext/ImageGeneration） |
| Messages 侧不声明内置工具 | `ClaudeProvider.kt:326-334` | 需新增声明分支；Anthropic 形态要带 `name`（伙伴文档 §8） |
| `tool_choice` 不能点名内置工具 | 伙伴文档 §8；本仓固定 `auto`（`ChatCompletionsAPI.kt:563`） | 现状无害，但别做「点名强制搜索」功能 |
| `tool_choice:none` 会关掉全部（含读链接） | 同上 | 未来若做「禁用工具」开关需注意连带效果 |

### 6.7 工具循环/步数上限

- `maxSteps=256`（`GenerationHandler.kt:380`）对「深度思考搜索约 20 次工具调用」绰绰有余；但内置工具不计入本地步数，本轮只有 1 步却可能产生 20 次服务端调用 → 现有「步数徽标」（`ChatMessageNerdLine.kt:143-155`）与「工具循环护栏」（`ToolCallLoopGuard`）都**看不见**服务端工具，护栏对内置工具无效（**这是设计取舍**：交给服务端限流，但 UI 上要能显示原生工具次数，否则用户无法理解费用）。
- 账号级 QPS（Search/Fetch 各 5）与多 Key 无关（`DeepSeekSearchService.kt:42-59` 注释已记录）；内置工具走聊天端点，QPS 口径不同（**未知**，需实测）。

### 6.8 文件输入：与本地文档解析冲突

- 今天所有文档附件都被 `DocumentAsPromptTransformer` 无条件解析成 `<UploadFile>` 文本（`transformers/DocumentAsPromptTransformer.kt:15-46`，PDF/DOCX/PPTX/EPUB 各自 parser，上限 200k 字符 `:17`），文案与 workspace 路径见 `:29-38`。
- DeepSeek 原生「文件输入解析」若要启用，需要按能力**跳过**该 transformer（否则同一份文件既被本地解析成文本、又作为原生文件输入 → 双份 token）。当前没有「模型是否原生吃文件」的能力位，**未知**如何判定（不能只看 `inputModalities`，那是图片）。
- 建议：首版**不接**原生文件输入，只做 web search / read_url；文件仍走本地解析（行为不变、无新成本）。

### 6.9 其他观测风险

- `CustomBody` 手工注入 `tools` 会整体覆盖（`Request.kt:137-158`），与本地工具互斥；若产品化前用户已在用该 hack，升级后要提示改用开关。
- `UsageRecorder` 的 `record` 在 `finally` 记录（`UsageRecorder.kt:140-142`），取消/失败也记；内置工具已发生的计费在「本轮失败」时同样要能看见 → 与现有口径一致，无需改。
- 统计页来源映射是硬编码 `when`（`StatsPage.kt:1039-1049`），若新增 `UsageSource`（例如 `NATIVE_TOOL`）必须同步，否则退化成裸枚举名（`UsageRecorder.kt:25-27` 已警告）。

---

## 7. 未知项（必须真机/官方确认，设计中按「假设」处理）

1. Chat Completions 流式下 `server_tool_calls[]` 出现在哪个 chunk（末块一次给？还是按步增量？）——伙伴文档只给了最终 `message` 形态。
2. 多轮回传需要携带哪些字段（`encrypted_content` / `signature` / `page`）？少了会 400 还是静默丢上下文？
3. `deepseek_read_url` 的**工具名**在 Chat Completions 是否就是 `deepseek_read_url`（伙伴文档 §8 只给 Messages 带 name、其余只给 type），以及它是否需要 `deepseek_web_search` 同时开启。
4. 内置工具的 `usage.server_tool_use` 在**流式末块**是否稳定返回（含 `tool_search/tool_fetch` 计费计数）；非流式是否给同样字段。
5. 拒答在 Responses 协议下是否映射到本仓 `finishReason`（`status=incomplete` + `incomplete_details.reason` 的解析路径未找到）。
6. DeepSeek `/responses` 端点返回的服务端工具 item 是 `web_search_call` 还是别的名字（决定 `isOpenAIServerToolCall` 的 `*_call` 后缀是否够用；该函数对 `_call` 泛化，但 `deepseek_read_url` 若不带 `_call` 后缀就会漏）。
7. 内置工具是否与本地 `tools`（function 工具）**可以同时声明**（伙伴文档未禁止；若可以，`tools` 数组混排是唯一形态）。
8. 账号级 QPS 对「聊天端点内置工具」的限流口径。
9. 计费计数器的幂等性：一次流式请求若在工具执行中途断开，已执行的服务端工具是否计费（影响「取消也记账」的实现）。
10. 新发布的 `deepseek-flash` 在 `models.dev` 的最新快照是否等同于官方 `deepseek-flash`（本地快照较旧，`canonical_model_id` 仍指向 `v4.1-flash-1017`）。

---

## 8. 最小测试计划（只覆盖本次适配的最小面）

| # | 层级 | 用例 | 断言 |
|---|---|---|---|
| 1 | 纯 JVM（ai module） | `Model` 序列化/反序列化新增 `BuiltInTools` 值 | 旧 JSON（无该字段）读成 `emptySet`；新 JSON 往返一致；`@SerialName` 字符串与契约一致 |
| 2 | 纯 JVM | Chat Completions 请求体：模型有原生工具 + 无本地工具 | body 含 `tools:[{"type":"deepseek_web_search"}]`；有本地工具时 function 项与裸项**共存于同一数组**；无 TOOL 能力时按产品决定的行为断言（参考现状 `ChatCompletionsAPI.kt:559`） |
| 3 | 纯 JVM | 流解码：注入 `message.server_tool_calls` 样例 | 产出 `ServerToolStart/End` + `UIMessagePart.ServerTool`；`status`/`action`/`sources` 落到 `input`/`output`/`metadata`（对齐 `ResponseApiStreamDecoder.kt:140-190` 的断言风格） |
| 4 | 纯 JVM | 注解解析：未知 `type` 与 `url_citation` 混合 | 放宽后：未知跳过且不抛；`start_index/end_index` 按需保留 |
| 5 | 纯 JVM | 回传：assistant 消息含 `ServerTool` | Chat Completions 请求里出现 `server_tool_calls`（或产品决定的等价字段），且**不**被当成 function `tool_calls` |
| 6 | 纯 JVM | `shouldUseExternalWebSearch` 矩阵 | 新变体存在 ⇒ false；仅 `enableWebSearch` ⇒ true；服务停用时 `createSearchTools` 返回空（`SearchTools.kt:29-31`） |
| 7 | 纯 JVM | usage 解析含 `server_tool_use` | `TokenUsage.toolSearchRequests/toolFetchRequests` 正确；缺失时 0；`totalTokens` 不被污染（对照 `Usage.kt:13-36` merge 语义） |
| 8 | 纯 JVM | 费用换算 | ¥5/1000 ⇒ 1 次 = 0.005；只对「计费计数器」计价；`web_search_requests` 不参与（伙伴文档 §8） |
| 9 | 仪器/integration（可选） | 假上游 SSE 回放一轮「声明工具→服务端块→引用注解→末块 usage」 | 消息 part 序列、思考链渲染、nerd line token/费用、多轮第二次请求 body 回传 |
| 10 | 真机（手动） | DeepSeek 官方 key，模型 `deepseek-flash`，分别跑 Chat Completions / Responses / Messages | 记录：`tools` 是否被接受、服务端块形态、usage 字段、拒答形态、续写是否 400（回填 §7） |

> 现有同类测试可作模板：`ai/src/test/java/app/androidrik/ai/provider/providers/openai/ResponseAPIMessageTest.kt`（内置工具/服务端工具相关用例，`:382,421`）、`ChatCompletionsAPIUsageTest.kt`、`ResponseApiStreamDecoder` 的 decoder 测试、`SearchServiceEnabledTest.kt`。

---

## 9. 建议改动点清单（供设计稿拆任务，不在本次实施）

| 阶段 | 改动 | 文件（证据） |
|---|---|---|
| P0 数据模型 | 新 `BuiltInTools` 变体 + 表单项 + 支持判定 + 互斥守卫 | `Model.kt:77-92`、`ModelSettingsForm.kt:450-490`、`SearchPicker.kt:167-178`、`ChatToolFactory.kt:50-52` |
| P0 请求面 | Chat Completions 发送内置工具（条件扩展 + 裸项）；按 provider 映射 `web_search`/`deepseek_web_search` | `ChatCompletionsAPI.kt:559-582`、`ResponseAPI.kt:451-491` |
| P1 响应面 | Chat Completions 解析 `server_tool_calls` → `StreamChunk.ServerTool*`；放宽注解解析；`UrlCitation` 索引（可选） | `ChatCompletionsStreamDecoder.kt:150,179-204`、`ChatCompletionsAPI.kt:898-995`、`UIMessageAnnotation.kt:30-36` |
| P1 回传面 | assistant 消息回写服务端工具（含签名/加密内容，字段待 §7 确认）；Claude 侧声明分支 | `ChatCompletionsAPI.kt:620-683`、`ClaudeProvider.kt:326-334,632-645` |
| P2 用量 | `TokenUsage` 扩工具计数 + 两处 usage 解析 + nerd line 费用 | `Usage.kt:6-11`、`ChatCompletionsStreamDecoder.kt:192-204`、`ChatMessageNerdLine.kt:66` |
| P2 统计（可选） | `usage_record` 加列 + AutoMigration 68→69 + 统计页费用行 | `UsageRecordEntity.kt:25-52`、`UsageRecorder.kt:84-110`、`AppDatabase.kt:96,212-213`、`StatsPage.kt:1039-1049` |
| P2 合规/提示 | `safety_check` 策略、参数被接管提示、续写禁用、Web server 合规提示 | `web/Entry.kt:15-33`、`ProviderSetting.kt:72-81`、`Request.kt:121-135` |
| 不做（首版） | 原生文件输入（`DocumentAsPromptTransformer` 保持无条件）、`title`/`user metadata` 内置能力 | `DocumentAsPromptTransformer.kt:15-46` |

---

## 10. 证据索引（本文引用过的仓内位置）

**Provider / 协议**
`DefaultProviders.kt:111-122,244`；`ProviderSetting.kt:72-81,176,231-251,247-250,388-407`；`Provider.kt:66-96`；`ProviderManager.kt:62-67`；`OpenAIProvider.kt:166-200`；`ProviderConfigure.kt:328-357`。

**模型 / 能力 / 目录**
`Model.kt:29-34,66-92`；`ModelRegistry.kt:315-381,703-713,766-806`；`ModelsDevCatalog.kt:78-95,103-107`；`ModelsDevUpdater.kt:15-56`；`models_dev.json` 的 `deepseek` provider 条目。

**工具装配 / 执行 / 回传**
`ChatToolFactory.kt:50-52,119-160,162-203,277-278`；`ToolSource.kt:20-60`；`SearchTools.kt:26-31,44-125,133-172`；`GenerationHandler.kt:380,570,1003-1130,1608-1624`；`ChatService.kt:1612,1872-1873`；`Tool.kt:25-55`；`Request.kt:121-135,137-158`。

**服务端工具抽象**
`StreamChunk.kt:73-101,154`；`UIMessagePart.kt:161-169`；`StreamChunkHandler.kt:158-220`；`MessageMetadata.kt:89-97`；`ResponseAPI.kt:451-494,582-587,738-769,1036-1063`；`ResponseApiStreamDecoder.kt:140-190`；`ChatCompletionsAPI.kt:559-583,620-683,898-995,1001-1012`；`ChatCompletionsStreamDecoder.kt:150,179-204`；`ClaudeProvider.kt:326-334,498-504,632-645`；`ClaudeStreamDecoder.kt:108-122`；`GoogleProvider.kt:457-467`。

**搜索**
`SearchService.kt:69-90,72,153-160,178,182,349-356`；`DeepSeekSearchService.kt:42-63`；`Settings.kt:148-150`；`Assistant.kt:168-172`；`ChatPage.kt:586-588,689-694`；`SearchPicker.kt:77,92,167-186,355,368`。

**用量 / 展示 / 合规**
`Usage.kt:6-11,38-56`；`UsageRecorder.kt:28-52,60-64,81-110,124-144`；`UsageRecordEntity.kt:25-52`；`StatsPage.kt:1039-1049`；`ChatMessageNerdLine.kt:66,143,161`；`ChatMessage.kt:613-615,830,859`；`ChatMessageCot.kt:17-31,85`；`Message.kt:749-760`；`UIMessageAnnotation.kt:30-36`；`document/DocumentAsPromptTransformer.kt:15-46`；`AppDatabase.kt:96,212-213`；`web/Entry.kt:15-33`；`web/routes/ConversationRoutes.kt:49,58`。

**外部契约（非仓内事实）**
[`01-api-reference.md`](../01-api-reference.md) §7（价目/单位）、§8（内置工具声明、块形态、计费计数器、被忽略参数、内容安全、文件输入）。
