# DeepSeek 延迟加载工具（Deferred Tool Loading）适配设计

> 状态：**已拍板（按 §9 决议实施中）**
> 契约依据：DeepSeek 官方文档 [`Deferred Tool Loading`](https://api-docs.deepseek.com/en/guides/tool_calls/#deferred-tool-loading)（同步核对 `/guides/responses_api`、`/guides/anthropic_api`）及 Anthropic 官方 `defer_loading` / `tool_reference` 规范
> 仓库基准：2026-11-28 主分支现状

---

## §0. 背景、目标与总原则

### 0.1 背景
当前 RikkaHub 在多来源工具场景下（MCP 服务器、JS 插件、Skills、本地扩展工具、工作区与记忆工具）会一次性将所有工具 JSON Schema 下发给模型。仓库此前已在 MCP 侧落地过「动态分层加载」缩水版（`Settings.mcpDynamicSchemaLoading` + `McpDynamicSchema.kt`，大于 `600` token 的重型 MCP 工具折叠为 `get_tool_map` + `mcp_tool_call` 字符串桥接），但存在两项固有局限：
1. **桥接调用质量损失**：模型需将工具参数手动编码为 JSON 字符串塞入 `mcp_tool_call.arguments`，失去原生 JSON Schema 的字段约束与类型引导。
2. **覆盖范围局限于重型 MCP**：插件（`ToolSource.Plugin`）、技能（`ToolSource.Skills` / `SkillInstall`）与低频本地/扩展工具仍全量占用首轮上下文。

DeepSeek 已在 `deepseek-flash` 上提供原生的 **Deferred Tool Loading（延迟工具加载）** 机制（Anthropic API 走 `defer_loading` + `tool_reference`/`tool_addition`，Responses API 走 `tool_search` + `tool_search_call`/`tool_search_output`）。它既能让延迟工具的完整 schema 在被检索加载前完全不进入模型首轮上下文、保住前缀 KV Cache，又能在加载后以**原生 `function` / `tool_use` 形态**直接调用。

### 0.2 设计目标
1. **双协议完整打通**：同时适配 DeepSeek **Responses API**（`ProviderSetting.OpenAI` 且 `useResponseApi = true`）与 **Anthropic Messages API**（`ProviderSetting.Claude`）。
2. **扩大可选延迟范围**：支持按来源范围（重型或全部 MCP、插件、技能、可选低频本地/扩展工具）启用原生延迟加载，核心高频工具保持常驻（eager）。
3. **无损回退**：当目标模型/协议/配置不支持原生延迟加载时（如 Chat Completions、`deepseek-v4-pro`、启用了 DeepSeek 内置 web tools、或非 DeepSeek 提供商），零泄漏、零报错地回退到既有 `mcpDynamicSchemaLoading` 桥接或全量原生模式。
4. **跨模型历史安全**：切模型/切协议继续同一对话时，历史中的搜索调用与加载结果自动降级为标准工具调用与文本摘要，不触发 400。

### 0.3 总原则
1. **默认零破坏**：新增设置字段与序列化模型均带默认值；不改 Room DB 结构（无需 migration）；老版本备份导入导出兼容。
2. **严格能力门控**：只有端点、模型、协议与内置工具互斥条件全部满足时才发送延迟加载协议字段。
3. **单一真相源**：`PromptAssembly.kt`（真实请求与 `PromptInspector` 预览）、`ToolSchemaSize.kt`、`ChatToolFactory.kt` 共用同一套分组与估算口径。
4. **纯函数可测**：检索排序、范围筛选、工具过滤、回退矩阵全部抽成无 Android 依赖的纯函数，由 JVM 单测锁死。

---

## §1. DeepSeek 官方协议契约与硬约束

### 1.1 支持矩阵
| 能力 / 维度 | Anthropic API (`/anthropic/v1/messages`) | Responses API (`/responses`) | Chat Completions API (`/chat/completions`) |
|---|---|---|---|
| 首轮隐藏工具定义 | 支持（顶层 `tools[]` 标 `"defer_loading": true`） | 支持（延迟工具不进顶层 `tools[]`，通过 `tool_search_output` 注入） | **不支持** |
| 对话中途加载工具 | 支持（`tool_result` 内嵌 `tool_reference`，或中段 `system` 含 `tool_addition`） | 支持（`tool_search_call` + `tool_search_output`） | **不支持** |
| 支持模型 | `deepseek-flash` | `deepseek-flash` | — |
| `deepseek-v4-pro` 表现 | 忽略 `defer_loading` / `tool_addition` / `tool_reference`（退化为全量可见，`tool_reference` 当纯文本） | 忽略 `tool_search` 声明与 `tool_search_call` / `tool_search_output` | — |

> 注意：仓库内置的 DeepSeek 提供商（`DefaultProviders.kt:119-130`）出厂默认 `useResponseApi = false`（走 Chat Completions）。使用原生延迟加载需在 DeepSeek 提供商开启 **Responses API**，或使用 **Claude 型提供商**指向 `https://api.deepseek.com/anthropic/v1`。

### 1.2 Anthropic API 线协议格式
1. **工具声明（顶层 `tools[]`）**：
   - 所有可能被加载的延迟工具**必须**预先声明在顶层 `tools[]` 中并附带 `"defer_loading": true`；
   - **至少有一个工具不能延迟**（即必须存在普通工具，如 `search_tools`）；
   - 延迟工具必须在后续每轮请求的顶层 `tools[]` 中继续保持声明（带 `"defer_loading": true`）。
2. **搜索结果加载（`tool_result` 路径）**：
   - 模型调用普通搜索工具 `search_tools` → 产生 `tool_use` 块；
   - 客户端在紧随的 `user` 消息 `tool_result.content` 中返回 `tool_reference` 块：
     ```json
     {
       "type": "tool_result",
       "tool_use_id": "toolu_1",
       "content": [
         { "type": "tool_reference", "tool_name": "get_weather" }
       ]
     }
     ```
   - ⚠️ **字段名差异提醒**：在 `tool_result.content` 中，工具名字段是 **`"tool_name"`**；而在中段系统消息的 `tool_addition` 中，格式为 `{"type": "tool_addition", "tool": {"type": "tool_reference", "name": "get_weather"}}`，字段是 **`"name"`**。
3. **只增不减（`addition-only`）**：
   - 不支持 `tool_removal` 与内联 `tool_definition` 块；
   - 已被 `tool_reference` / `tool_addition` 引用的工具名称**必须**存在于当次请求的顶层 `tools[]` 中，否则服务端会拒绝。

### 1.3 Responses API 线协议格式
1. **工具声明（顶层 `tools[]`）**：
   - 只放**常驻工具**（`type: "function"`）+ 1 个客户端搜索工具：
     ```json
     {
       "type": "tool_search",
       "execution": "client",
       "description": "Search for tools that can help with the user's request.",
       "parameters": {
         "type": "object",
         "properties": { "query": { "type": "string" } },
         "required": ["query"]
       }
     }
     ```
   - 延迟工具**不进入**顶层 `tools[]`；仅支持 `"execution": "client"`（`"execution": "server"` 不支持）。
2. **模型发出搜索请求（`tool_search_call`）**：
   - 模型返回输出项：
     ```json
     {
       "type": "tool_search_call",
       "id": "tsc_1",
       "call_id": "call_1",
       "execution": "client",
       "status": "completed",
       "arguments": { "query": "weather" }
     }
     ```
   - ⚠️ **结构差异**：普通 `function_call.arguments` 是 JSON 字符串，而 DeepSeek `tool_search_call.arguments` 是 **JSON 对象**！
3. **客户端回传搜索结果（`tool_search_call` + `tool_search_output`）**：
   - 后续每轮无状态请求中，必须在 `input[]` 依次重放 `tool_search_call`（至少保留 `type`、`call_id`、`execution`、`arguments`）和配对的 `tool_search_output`：
     ```json
     {
       "type": "tool_search_call",
       "call_id": "call_1",
       "execution": "client",
       "arguments": { "query": "weather" }
     },
     {
       "type": "tool_search_output",
       "call_id": "call_1",
       "execution": "client",
       "tools": [
         {
           "type": "function",
           "name": "get_weather",
           "description": "Get the current weather for a city.",
           "parameters": {
             "type": "object",
             "properties": { "city": { "type": "string" } },
             "required": ["city"]
           }
         }
       ]
     }
     ```
   - 后续该工具即可像普通函数一样被调用，结果仍以 `function_call_output` 回传。

### 1.4 硬互斥、缓存与计费细节
1. **内置 Web Tools 硬互斥**：在 Anthropic API 与 Responses API 上，延迟加载（`defer_loading` / `tool_search`）**均不可与 `deepseek_web_search` 或 `deepseek_read_url` 同时启用**。
2. **无状态重放与 KV Cache**：加载记录属于对话历史，后续每轮必须完整重放。延迟加载将工具 schema 追加在历史尾部，不重写首部常驻 `tools[]` 与 `system`，因此前置缓存（KV Cache）保持命中。
3. **思考模式约束**：Responses API 开启思考模式时，后续请求需回传对应的 `reasoning` item 并保持 API 返回的原始 `call_id`（`ResponseAPI.kt` 现有 reasoning 回传与补全机制需同样覆盖 `tool_search_call`）。
4. **计费口径**：`usage.server_tool_use.tool_search` 统计的是**服务端联网搜索**计费次数，**不统计**客户端 `tool_search_call`（工具发现本身免费，只按 token 计费）。

---

## §2. 仓库现状与缺口审计

| 模块 / 文件 | 现状（已核实） | 适配缺口 / 风险 |
|---|---|---|
| `ai/.../core/Tool.kt` | 仅包含 `name`、`description`、`parameters`、`systemPrompt`、`needsApproval`、`executionTimeoutMillis`、`endTurnOnSuccess`、`concurrentSafe`、`execute` | 缺 `deferLoading: Boolean` 与 `isDeferredToolSearch: Boolean` 标记 |
| `ai/.../provider/Model.kt:107-124` | 已有 `DEEPSEEK_WEB_TOOLS_MODEL_ID = "deepseek-flash"` 与 `Model.supportsDeepSeekWebTools(provider)` | 需补充 `DeferredToolProtocol` 能力判定及与 DeepSeek Web Tools 的互斥判定 |
| `ai/.../ui/MessageMetadata.kt` | 已有 `ClaudeReasoningMetadata`、`OpenAIReasoningMetadata`、`ServerToolMetadata` 等类型化 metadata | 缺 `tool_search_call` 协议元信息与 `search_tools` 命中工具列表的类型化 metadata |
| `ai/.../provider/providers/ClaudeProvider.kt:318-331, 511-517` | `tools[]` 全量下发；`toToolResultBlock()` 只处理 `Text` / `Image`；`buildMessages()` 将非首条 `SYSTEM` 降级为 `USER` | 需按协议输出 `defer_loading: true`；`toToolResultBlock()` 需支持发射 `tool_reference` 块并校验当次顶层 `tools` 可用性 |
| `ai/.../provider/providers/openai/ResponseAPI.kt:456-514, 623-695, 840-960, 1076-1084` | `isOpenAIServerToolCall` 把所有白名单外的 `*_call` 都当作 `ServerTool`；`addAssistantItems` 只发射 `function_call`/`function_call_output`；`parseResponseOutput` 走同一判定 | **关键坑**：`tool_search_call` 目前会被误判为 `UIMessagePart.ServerTool` 导致客户端根本不执行搜索！需将其路由为客户端 `UIMessagePart.Tool` 并在回放时输出 `tool_search_call` + `tool_search_output` |
| `ai/.../provider/providers/openai/ResponseApiStreamDecoder.kt:107-194` | 流式 `output_item.added/done` 中将 `tool_search_call` 归入 `isOpenAIServerToolCall` | 需独立解码 `tool_search_call`（兼容对象型/字符串型 `arguments`），输出标准 `ToolCallStart`/`ToolCallDelta`/`ToolCallEnd` |
| `ai/.../util/ToolSchemaSize.kt` | 对所有 `tools` 统一按线格式估算 token | 需区分「首轮进入上下文的常驻 schema」与「延迟加载池 schema」，避免虚报首轮上下文占用 |
| `app/.../data/ai/tools/ChatToolFactory.kt` & `McpDynamicSchema.kt` | 已有 `mcpDynamicSchemaLoading`（阈值 600t，拆出 `get_tool_map` + `mcp_tool_call` 桥）；`ToolSource` 已完整区分 14 类工具来源 | 需接入原生延迟加载策略：支持原生时取消重型 MCP 桥接改走原生延迟，并把可选范围扩展到 MCP / 插件 / 技能 / 低频本地工具 |
| `app/.../data/ai/GenerationHandler.kt:1292-1440` | `toolsInternal.find { it.name == tool.toolName }` 直接按名字查找并执行工具、校验审批与超时 | 把延迟工具保留在 `params.tools` 中即可原生复用全部执行、审批、并发车道与超时逻辑；但需确保 `maybeTruncateToolOutput` / `finalizeToolOutput` 保留搜索结果的 metadata |
| `app/.../data/ai/PromptAssembly.kt` | 控制 `PromptInspector` 与真实请求的拼装一致 | 需在检查器与日志中区分「常驻工具」与「延迟工具」 |
| `app/.../data/datastore/DefaultProviders.kt:119-130` | 内置 DeepSeek `useResponseApi = false` | 本期不强改出厂默认值（避免影响老用户），但在设置页与说明中明确提示启用 Responses API 或 Anthropic 端点 |

---

## §3. 协议层设计（`ai` 模块）

### 3.1 能力门控（`ai/.../provider/Model.kt`）
新增枚举与扩展判定函数，与现有 `supportsDeepSeekWebTools` 共用同一套官方端点判断口径：

```kotlin
enum class DeferredToolProtocol {
    NONE,
    ANTHROPIC_DEFER_LOADING,
    RESPONSES_TOOL_SEARCH,
}
```

判定函数 `fun Model.deferredToolProtocol(provider: ProviderSetting?): DeferredToolProtocol`：
1. 若 `!abilities.contains(ModelAbility.TOOL)` → 返回 `NONE`。
2. **互斥检查（最高优先）**：若当前模型处于官方 DeepSeek 端点且启用了任一服务端联网工具：
   `supportsDeepSeekWebTools(provider)` 成立 **且** `(BuiltInTools.DeepSeekWebSearch in tools || BuiltInTools.DeepSeekReadUrl in tools)` → 返回 `NONE`。
   （注：Anthropic 兼容端点的 `Claude` provider 若通过 `customBody` 或模型标记启用了 DeepSeek Web Tools，同样应返回 `NONE`。）
3. **官方 DeepSeek 端点与模型校验**：
   - `modelId == DEEPSEEK_WEB_TOOLS_MODEL_ID`（`"deepseek-flash"`）
   - `endpoint?.isHttps == true && endpoint.host == "api.deepseek.com"`
   - 若 `provider is ProviderSetting.OpenAI && provider.useResponseApi` → 返回 `RESPONSES_TOOL_SEARCH`
   - 若 `provider is ProviderSetting.Claude` → 返回 `ANTHROPIC_DEFER_LOADING`
   - 其他（Chat Completions、`deepseek-v4-pro`、第三方中转等）→ 返回 `NONE`。

> 扩展性预留：已同期放行官方 Anthropic Claude 支持 `defer_loading` 的模型系列（Claude 4.5+ / 5 系列：`claude-fable-5`、`claude-mythos-5`、`claude-opus-4-5+`、`claude-sonnet-4-5+`、`claude-haiku-4-5+`），共用 `ANTHROPIC_DEFER_LOADING`；Claude 4.1 及更早型号不支持，保持 `NONE`。

### 3.2 `Tool` 抽象扩展（`ai/.../core/Tool.kt`）
在 `Tool` 上增加两个向后兼容的默认字段：
- `val deferLoading: Boolean = false`：是否标记为延迟加载工具。
- `val isDeferredToolSearch: Boolean = false`：是否为客户端工具目录检索工具（约定规范名称常量 `const val DEFERRED_TOOL_SEARCH_TOOL_NAME = "search_tools"`）。

约束：
- `isDeferredToolSearch == true` 的工具本身永远不得设 `deferLoading = true`；
- 只有当工具集中**同时存在**至少 1 个 `deferLoading == true` 且至少 1 个 `deferLoading == false` 的工具时，协议层才激活延迟加载格式。

### 3.3 类型化 Metadata（`ai/.../ui/MessageMetadata.kt`）
全部实现 `PartMetadata`，所有字段可空且带 `@SerialName`，确保旧会话与跨模型切换完全兼容：

1. **搜索调用元数据**（挂在 `UIMessagePart.Tool.metadata`）：
   ```kotlin
   @Serializable
   data class DeferredToolSearchCallMetadata(
       @SerialName("deferred_tool_search_protocol")
       val protocol: ServerToolProtocol? = null,
       @SerialName("deferred_tool_search_execution")
       val execution: String? = null,
       @SerialName("deferred_tool_search_item_id")
       val itemId: String? = null,
   ) : PartMetadata
   ```
2. **搜索命中结果元数据**（挂在 `Tool.output` 首个 `UIMessagePart.Text.metadata`）：
   ```kotlin
   @Serializable
   data class DeferredToolSearchResultMetadata(
       @SerialName("deferred_loaded_tools")
       val loadedToolNames: List<String>? = null,
   ) : PartMetadata
   ```

### 3.4 Anthropic Messages 协议适配（`ClaudeProvider.kt`）
1. **请求体 `tools[]` 序列化**：
   - 计算 `val useDeferLoading = deferredProtocol == DeferredToolProtocol.ANTHROPIC_DEFER_LOADING && params.tools.any { it.deferLoading } && params.tools.any { !it.deferLoading }`。
   - **稳定排序**：若启用，将常驻工具（`!deferLoading`）排在前面、延迟工具（`deferLoading`）排在后面（保持各自内部顺序不变），再序列化；延迟工具附加 `put("defer_loading", true)`。
   - **缓存断点位置硬约束**：Anthropic 规范禁止在带 `defer_loading: true` 的工具上挂载 `cache_control`；开启 `providerSetting.promptCaching` 时，必须把 `cache_control` 放在**最后一个常驻（非延迟）工具**上，而非 `tools.lastIndex`。
2. **历史回放 `toToolResultBlock(availableToolNames: Set<String>)`**：
   - 当 `useDeferLoading == true` 且 `tool.output` 中存在 `DeferredToolSearchResultMetadata` 时：
     - 取 `meta.loadedToolNames.orEmpty().distinct().filter { it in availableToolNames }`（**必须与当次请求顶层 `tools[]` 求交集**，防止 MCP 断开或插件停用后引用未声明工具触发 400）；
     - 为每个命中的工具生成 `{"type": "tool_reference", "tool_name": name}`；
     - 附带人类可读文本块（若全部命中工具均已失效，或原本未命中任何工具，文本块明确告知模型未加载到可用工具，避免空 `content` 数组）。
   - **降级分支**：若当前请求未启用 `ANTHROPIC_DEFER_LOADING`（如切到了 `deepseek-v4-pro` 或普通 Claude 端点），忽略 `tool_reference`，仅输出 `UIMessagePart.Text` 文本摘要。
3. **关于 `tool_addition`（二期范围）**：
   - 官方还支持在中段 `system` 消息放 `{"type": "tool_addition", "tool": {"type": "tool_reference", "name": "..."}}` 实现宿主主动注入。当前 `ClaudeProvider.buildMessages` 会将非首条 `SYSTEM` 消息降级为 `USER`，且本仓采用模型主动调用 `search_tools` 模式即可覆盖全部场景，故 **`tool_addition` 明确列为二期可选能力**，一期不引入。

### 3.5 Responses API 协议适配（`ResponseAPI.kt` & `ResponseApiStreamDecoder.kt`）
1. **请求体 `tools[]` 序列化（`ResponseAPI.buildRequestBody`）**：
   - 判定 `val useToolSearch = deferredProtocol == DeferredToolProtocol.RESPONSES_TOOL_SEARCH && params.tools.any { it.deferLoading } && params.tools.any { it.isDeferredToolSearch }`。
   - 若 `useToolSearch == true`：
     - `tool.deferLoading == true` 的工具**不写入**顶层 `tools[]`（保留在 `params.tools` 内存供回放与执行层查找）；
     - `tool.isDeferredToolSearch == true` 的工具序列化为：
       ```json
       {
         "type": "tool_search",
         "execution": "client",
         "description": "...",
         "parameters": { ... }
       }
       ```
       （不输出 `name` 与 `strict`）；
     - 其余常驻工具正常按 `type: "function"`, `strict: false` 输出。
   - 若 `useToolSearch == false`：跳过 `isDeferredToolSearch` 标记影响（或仍按普通 function 兜底，取决于工厂侧是否已装配 `search_tools`，见 §4.2）。

2. **流式与非流式解码（`ResponseApiStreamDecoder.kt` & `ResponseAPI.parseResponseOutput`）**：
   - **修复 `isOpenAIServerToolCall` 误判**：
     - 将 `"tool_search_call"` 从默认服务端工具分支中排除，或按 `item["execution"]?.jsonPrimitive?.contentOrNull != "server"` 精确分流：仅当 `execution == "server"` 时走 `ServerTool`，否则走客户端工具调用通道。
   - **流式解码（`ResponseApiStreamDecoder`）**：
     - `response.output_item.added` 遇到客户端 `tool_search_call`：
       - 通过 `toolCallIdResolver.registerItem(itemId, callId, outputIndex)` 统一规范 `canonicalId`（回传优先使用 `call_id`）；
       - 解析初始 `arguments`：`item["arguments"]` 可能是 `JsonObject` 或 `JsonPrimitive`（字符串），统一编码为 JSON 字符串（`argumentsElement as? JsonPrimitive` 取 `contentOrNull`，否则 `json.encodeToString(argumentsElement)`）；
       - 发射 `ToolCallStart(id = canonicalId, toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME, metadata = DeferredToolSearchCallMetadata(ServerToolProtocol.OPENAI_RESPONSES, "client", itemId).toMetadata())`，若初始 `arguments` 非空同步发射 `ToolCallDelta`。
     - `response.output_item.done` 遇到客户端 `tool_search_call`：
       - 若此前 `added` 未带完整 `arguments` 而 `done` 补齐了（且 `canonicalId !in state.toolIdsWithInput`），先补发 `ToolCallDelta`，再发射 `ToolCallEnd(canonicalId)`。
     - 兼容事件：若服务端未来发 `response.tool_search_call_arguments.delta` / `.done`，复用 `toolDelta` / `endTool` 逻辑。
   - **非流式解码（`ResponseAPI.parseResponseOutput`）**：
     - 增加 `when (type)` 的 `"tool_search_call"` 分支，当 `execution != "server"` 时构造 `UIMessagePart.Tool(toolCallId = callId, toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME, input = encodedArgs, metadata = ...)`。

3. **历史回放（`ResponseAPI.addAssistantItems`）**：
   - 识别搜索工具调用：`val isClientToolSearch = tool.metadataAs<DeferredToolSearchCallMetadata>()?.protocol == ServerToolProtocol.OPENAI_RESPONSES || tool.toolName == DEFERRED_TOOL_SEARCH_TOOL_NAME`。
   - **思考链保真**：完全复用现有 `PartGroup.Tools` 里的 `!reasoningEmitted` / `toolEmittedSinceReasoning` 回填与补插逻辑，确保在思考模式下每段 `tool_search_call` 前同样带有合法 `reasoning` item。
   - 当 `deferredProtocol == DeferredToolProtocol.RESPONSES_TOOL_SEARCH` 且 `isClientToolSearch` 为真时：
     - 输出 `tool_search_call`：
       ```json
       {
         "type": "tool_search_call",
         "call_id": "<tool.toolCallId>",
         "execution": "client",
         "arguments": { ... } // 使用 tool.inputAsJson()，若非 JsonObject 则兜底 {}
       }
       ```
     - 从 `tool.output` 读取 `DeferredToolSearchResultMetadata.loadedToolNames`，在当前 `params.tools` 映射表中按名查找工具定义：
       - 过滤掉已不存在或本身是 `isDeferredToolSearch` 的工具；
       - 组装 `tool_search_output`：
         ```json
         {
           "type": "tool_search_output",
           "call_id": "<tool.toolCallId>",
           "execution": "client",
           "tools": [
             {
               "type": "function",
               "name": "...",
               "description": "...",
               "parameters": { ... },
               "strict": false
             }
           ]
         }
         ```
       - 若全部工具已卸载或原本无命中，`tools` 仍合法输出空数组 `[]`，保证 `call_id` 配对不断裂。
   - **跨模型/跨协议降级**：当 `deferredProtocol != RESPONSES_TOOL_SEARCH` 时（例如切换到了普通 OpenAI Responses 模型或 `deepseek-v4-pro`），退回普通 `function_call` + `function_call_output`（文本摘要）。

### 3.6 `ToolSchemaSize.kt` 估算口径更新
- 区分 `estimate(tools, providerType, model, json)`（或新增 `estimateActiveTools` / `estimateDeferredPool`）：
  - `deferLoading == false`：无论何种协议，均计入「首轮常驻工具 token」；
  - `deferLoading == true`：
    - `RESPONSES_TOOL_SEARCH`：首轮**不随请求发送**，首轮请求体积计 `0`，单独统计在「延迟池 token」；
    - `ANTHROPIC_DEFER_LOADING`：随 `tools[]` 发送但带 `"defer_loading": true`，不进入模型首轮 prompt 上下文，同样单独区分「首轮进入上下文 vs 延迟池」。

---

## §4. 工具装配与检索策略设计（`app` 模块）

### 4.1 工具分级与可配置延迟范围
引入 `DeferredToolScope` 枚举（可多选，持久化于 `Settings`）：

| 枚举值 | 覆盖的 `ToolSource` / 工具范围 | 默认 | 说明 |
|---|---|---|---|
| `MCP_HEAVY` | `ToolSource.McpServer` 中 `schemaTokens > 600` 的工具 | 可选 | 与原 `mcpDynamicSchemaLoading` 同阈值；若同时选了 `MCP_ALL` 则覆盖之 |
| `MCP_ALL` | 全部 `ToolSource.McpServer` 工具 | **默认开启** | 全部 MCP 工具按需加载，首轮最大化省下工具 schema 上下文 |
| `PLUGINS` | 全部 `ToolSource.Plugin(*)` 工具 | **默认开启** | 第三方 JS 插件工具通常数量不定、按需触发 |
| `SKILLS` | `ToolSource.Skills`、`ToolSource.SkillInstall` | **默认开启** | 技能安装与技能读取属于特定意图触发 |
| `OPTIONAL_LOCAL` | `ToolSource.Image`、`TaskManage`、`Conversation`、`Knowledge` 及部分低频 `LocalToolOption`（`ScreenTime`、`Calendar`、`Clipboard`、`Tts`、`ChartDisplay`、`JavascriptEngine`、`PhoneUse`） | 可选 | 高频核心交互本地工具（`get_time_info`、`ask_user`、`todo_write`、`await_background_tasks`、`view_image`）**不纳入延迟** |

**恒定常驻（Never Deferred）工具**：
- `ToolSource.Memory`（记忆读写检索）
- `ToolSource.Search`（外挂联网搜索）
- `ToolSource.Subagent`（子代理派发）
- `ToolSource.Workspace`（工作区文件/终端，见 §9 开放问题 3）
- 核心本地工具：`await_background_tasks`、`view_image`、`ask_user`、`todo_write`、`get_current_time`
- `search_tools` 本身
- 子代理信封 `explicitlyAllowedToolNames` 显式点名的工具（对齐 `promoteExplicitlyAllowed` 原则：显式点名永不静默降级或丢失）。

### 4.2 原生延迟加载与 MCP 桥接的回退矩阵
| 条件组合 | MCP 重型工具处理 | 其他选中延迟范围处理 | 是否注入 `search_tools` |
|---|---|---|---|
| `nativeDeferredToolLoading = true` 且 `protocol != NONE` 且 命中至少 1 个延迟工具 | **原生 `deferLoading = true`**（不走 `get_tool_map` + `mcp_tool_call` 桥） | 标 `deferLoading = true` | **是** |
| `nativeDeferredToolLoading = true` 且 `protocol == NONE`（如 Chat Completions / `deepseek-v4-pro` / 开启 DeepSeek Web Tools / 非 DeepSeek） | 若 `mcpDynamicSchemaLoading = true` → **回退 `get_tool_map` + `mcp_tool_call` 桥**；否则全量原生 | 保持 `deferLoading = false`（全量原生） | **否** |
| `nativeDeferredToolLoading = true` 且 `protocol != NONE` 但 候选延迟工具数为 `0` | — | — | **否**（零额外 token 开销） |
| `nativeDeferredToolLoading = false` | 完全按现有 `mcpDynamicSchemaLoading` 逻辑运行 | 保持 `deferLoading = false` | **否** |

> 关键收益：对于在 `deepseek-flash` 上原本受制于 `mcp_tool_call` 字符串桥接的重型 MCP 工具，一旦满足原生条件即自动升级为原生结构化调用；切回 Chat Completions 或其它模型时无缝保留原有桥接省 token 能力。

### 4.3 目录检索器与 `search_tools` 设计（`app/.../data/ai/tools/DeferredToolCatalog.kt`）
新建纯 Kotlin 文件，无 Android 依赖：
1. **工具目录摘要（注入 `search_tools.description`）**：
   - DeepSeek 明确要求：延迟工具 schema 在加载前对模型不可见，必须在 `search_tools` 的 `description` 或系统提示中让模型知道「有哪些可搜索的能力目录」。
   - **KV Cache 友好格式**：按 `ToolSource.segmentName` 稳定排序（再按工具名字典序），输出紧凑清单，例如：
     ```text
     Search and load deferred tools on demand before calling them.
     Available deferred catalog:
     - [mcp:github] create_issue, list_pull_requests, search_code (+5 more)
     - [plugin:feishu] send_message, read_doc
     - [skills] load_skill
     Call with `query` (keywords) or `names` (exact tool names).
     ```
   - 单段工具过多时截断展示前若干名称并附 `(+N more)`，总目录描述控制上限（如 1,500 chars），避免目录本身膨胀；因为排序完全确定，跨轮次 `search_tools` 描述逐字节稳定，不会破坏前缀缓存。
2. **`search_tools` 参数 Schema**：
   ```json
   {
     "type": "object",
     "properties": {
       "query": {
         "type": "string",
         "description": "Search keywords matching tool name, capability, or source (use \"*\" to list overview)."
       },
       "names": {
         "type": "array",
         "items": { "type": "string" },
         "description": "Optional exact tool names to load directly."
       },
       "limit": {
         "type": "integer",
         "description": "Max number of tools to return (1-10, default 5)."
       }
     }
   }
   ```
3. **打分与匹配策略**：
   - `names` 精准匹配优先（直接命中并置顶）；
   - `query` 分词（支持英文空格/下划线/驼峰拆分与中文子串匹配）：
     - 工具名精确匹配 > 工具名包含 > 来源段名匹配 > 参数名（`InputSchema.Obj.properties.keys`）匹配 > 工具描述匹配；
   - `query == "*"` 或空参数时，按稳定顺序返回前 `limit` 个工具；
   - 结果数硬上限 `10`（默认 `5`），防止单次搜索把几十个重型 schema 全部灌入历史。
4. **工具执行属性与返回格式**：
   - `isDeferredToolSearch = true`
   - `needsApproval = TOOL_NO_APPROVAL`
   - `concurrentSafe = true`
   - `executionTimeoutMillis = 5_000L`
   - 返回单个 `UIMessagePart.Text`：
     - 文本内容简明列出已加载工具名、来源与简要说明（当回退到非延迟协议或人类查看工具气泡时可读）；
     - `metadata = DeferredToolSearchResultMetadata(loadedToolNames = matchedNames).toMetadata()`。

### 4.4 `ChatToolFactory` / `GenerationHandler` / `PromptAssembly` / `SubagentRunner` 衔接
1. **`ChatToolFactory.createToolSegments`**：
   - 保持现有各段装配顺序与命名不变；
   - 拿到 `effectiveProtocol = model.deferredToolProtocol(providerType)`：
     - 当启用原生延迟加载且 `effectiveProtocol != NONE` 时：
       - MCP 段**跳过** `get_tool_map` / `mcp_tool_call` 折叠分支，全部以原生 `Tool` 保留；
       - 按 `settings.deferredToolScopes` 与 `explicitlyAllowedToolNames` 对各段工具标注 `deferLoading = true`；
       - 若存在至少 1 个 `deferLoading = true` 的工具，追加一个 `ToolSegment(ToolSource.DeferredSearch, listOf(createDeferredToolSearchTool(...)))`（`ToolSource` 新增 `DeferredSearch` 常量，段名 `"deferred-search"`）。
2. **`GenerationHandler.kt` & `ToolOutputBudget.kt`**：
   - **元数据保活**：检查 `maybeTruncateToolOutput` 与 `finalizeToolOutput`，确保 `UIMessagePart.Text` 上的 `metadata`（即 `DeferredToolSearchResultMetadata`）在截断与收尾后完整保留（`search_tools` 输出本身也很短，建议直接加入 `ToolOutputBudget` 的免截断名单）。
   - **零执行改动**：由于所有延迟工具都保留在 `toolsInternal` 中，模型加载后发起调用时，`GenerationHandler` 的审批检查（`markPendingApprovals`）、去重、并发车道（`concurrentSafe`）、超时控制（`executeToolWithTimeout`）**100% 自动生效**。
3. **`PromptAssembly.kt` & `PromptInspector`**：
   - `PromptAssembly` 按 `ToolSchemaSize` 的新口径展示：常驻工具数/token、延迟工具池数/token、当前生效的延迟模式（`Responses tool_search` / `Anthropic defer_loading` / `MCP 桥接` / `未启用`）。
4. **`SubagentRunner.kt` & `SubagentTools.kt`**：
   - `resolveSubagentEnvelope` 基于 `ToolSegment` 工具名求交集，应在标记 `deferLoading` 之前或包含 `deferLoading` 工具进行判定；
   - `runToolCatalog` 过滤掉 `DEFERRED_TOOL_SEARCH_TOOL_NAME`（与过滤 `ask_user`、`dispatch_subagent` 同理，不作为可配置业务工具暴露在子代理信封菜单中）。

---

## §5. 设置、备份兼容、UI 展示与 i18n

### 5.1 设置与持久化（零 DB 迁移）
在 `Settings.kt` 新增字段（均带默认值）：
- `val nativeDeferredToolLoading: Boolean = true`（默认开启，仅在命中支持模型与协议时自动生效）
- `val deferredToolScopes: Set<DeferredToolScope> = setOf(DeferredToolScope.MCP_ALL, DeferredToolScope.PLUGINS, DeferredToolScope.SKILLS)`

同步修改点：
1. `PreferencesStore.kt`：新增对应 DataStore key 的读写逻辑。
2. `BackupContentFilter.kt`：将新键加入 `MCP_KEYS` 或 `TOOLS_KEYS`（建议放 `TOOLS_KEYS` 并保证 `BackupContentFilterTest` 覆盖），使分域备份与导出恢复完整。
3. `app/src/main/keepRules/serialization.keep`：为 `DeferredToolScope` / 新增 `PartMetadata` 子类加 keep 规则，避免 R8 混淆剥离 `@SerialName`（遵循 `r8-release-2026-11-27.md` 规范）。

### 5.2 设置页 UI 与消息气泡展示
1. **设置页入口**（参考 `SettingMcpPage.kt:210-233` 的 `CardGroup` 模式）：
   - 在现有「工具/MCP 动态加载」设置区域补充「原生延迟工具加载（DeepSeek）」开关及「延迟加载范围」多选芯片（`FlowRow`），遵循 `AGENTS.md` 规范：
     - `ListItem(supportingContent = ...)` 内若有多块或条件说明行，**必须外包一层 `Column`**，禁止条件行与 `FlowRow` 平级直接挂在槽位上（防静默丢失）；
     - 若涉及任何编辑框，遵循 `PersistedTextFieldState` 规范（本次只有开关与芯片，不涉及文本输入）。
   - **明确的状态说明文案**：
     - 注明当前仅 **DeepSeek `deepseek-flash`** 在 **Responses API** 或 **Anthropic 兼容端点**下生效；
     - 提醒 DeepSeek 内置提供商出厂默认走 Chat Completions，需在提供商设置中开启「使用 Responses API」；
     - 提醒不可与模型设置的 `deepseek_web_search` / `deepseek_read_url` 同时启用（同时开启时会自动回退）。
2. **对话气泡渲染（`ChatMessageTools.kt`）**：
   - 为 `search_tools` 提供专用的紧凑工具步骤展示：
     - 展示搜索关键词（如 `搜索工具: weather`）与已加载的工具标签列表（读 `DeferredToolSearchResultMetadata.loadedToolNames`），避免直接渲染裸 JSON。
3. **国际化**：
   - 所有新增 UI 字符串同步补齐 `values/`（en）、`values-zh-rCN/`（zh）、`values-zh-rTW/`（zh-rTW）三套资源。

---

## §6. 边界场景与风险控制

| 边界场景 | 潜在问题 | 本设计处理方案 |
|---|---|---|
| 同时勾选 `deepseek_web_search` / `deepseek_read_url` | 官方 API 对 `defer_loading` / `tool_search` 与内置联网工具互斥报错 | `Model.deferredToolProtocol` 直接返回 `NONE`；日志记明确原因；工厂自动回退为非延迟/MCP 桥接模式 |
| 选择 `deepseek-v4-pro` 或 Chat Completions | `v4-pro` 忽略延迟指令、Chat Completions 不支持 | `deferredToolProtocol` 返回 `NONE`，不发任何 `defer_loading` 或 `tool_search` 字段 |
| 内置 DeepSeek 默认 `useResponseApi = false` | 用户开启开关后疑惑为什么没走 `tool_search` | 设置页与 `PromptInspector` 明确标示当前生效模式与原因（如「当前走 Chat Completions，已回退」） |
| Responses 思考模式（Thinking） | 缺少 reasoning item 或 `call_id` 错位引发 400 | 解码时优先用 `call_id` 作 `toolCallId`；回放时 `tool_search_call` 复用现有 `PartGroup.Tools` reasoning 回填与补插逻辑 |
| 对话中途断开 MCP / 停用插件 | Anthropic 引用未在顶层声明的工具名会报错；Responses 找不到工具定义 | 回放时按当前 `params.tools` 过滤已加载工具名：Anthropic 仅发射仍存在的 `tool_reference`；Responses 对不存在的工具跳过（全空时合法发射 `tools: []` + 提示文本） |
| 中途切换模型/协议（如 flash → v4-pro / Claude / GPT） | 目标协议不认识 `tool_reference` / `tool_search_call` / `tool_search_output` | 回放时发现 `deferredProtocol` 不匹配，自动降级为普通文本 `tool_result` 或 `function_call` + `function_call_output` |
| 搜索结果为空（无匹配工具） | 空列表序列化异常或缺失配对输出 | Anthropic 返回文本提示未找到匹配工具（无 `tool_reference`）；Responses 返回 `tool_search_output` 携带 `"tools": []` |
| 模型重复搜索或参数校验异常 | 模型传入未知字段、非数字 `limit` 或重复搜同一词 | `search_tools` 对参数做宽松解析并限幅 `1..10`；可按会话内已加载记录自然去重或重复安全返回 |
| 自动压缩 / 上下文截断裁剪 | 旧的 `search_tools` 记录被裁出发送窗口，模型以为工具还加载着 | 由于 `UIMessagePart.Tool` 为原子部件，不会出现孤立的 `tool_search_call` 缺 `tool_search_output`；若整段被裁出窗口，模型可通过 `search_tools` 描述重新检索加载 |
| 多步连续加载与 `PromptCaching` | 工具顺序抖动导致 KV Cache 失效 | 延迟工具列表与 `search_tools` 目录均按固定规则排序，且首轮常驻工具位置不变 |

---

## §7. 分阶段实施路线（P0 → P3）

- **P0：协议层原语与测试（`ai` 模块）**
  - 修改 `Tool.kt`、`Model.kt`、`MessageMetadata.kt`、`ClaudeProvider.kt`、`ResponseAPI.kt`、`ResponseApiStreamDecoder.kt`、`ToolSchemaSize.kt`。
  - 补齐 `ai` 模块 JVM 单元测试。
- **P1：装配与检索核心（`app` 业务层）**
  - 新增 `DeferredToolCatalog.kt`、`ToolSource.DeferredSearch`。
  - 修改 `ChatToolFactory.kt`、`GenerationHandler.kt`（含 `ToolOutputBudget.kt` 元数据保留）、`PromptAssembly.kt`、`SubagentTools.kt`。
  - 补齐 `app` 模块 JVM 单元测试。
- **P2：配置、备份、UI 与国际化**
  - 修改 `Settings.kt`、`PreferencesStore.kt`、`BackupContentFilter.kt`、`serialization.keep`。
  - 更新设置页开关与范围选择 UI、`PromptInspector` 展示、`ChatMessageTools.kt` 渲染，以及 `en` / `zh` / `zh-rTW` 三语言字符串。
- **P3：真机与真实 API 联调验收**
  - 实测 `deepseek-flash` Responses API 与 Anthropic 兼容端点（思考开/关），核验 `usage.cachedTokens` 与端到端调用链路。

---

## §8. 完整验收与测试清单

### 8.1 JVM 单元测试矩阵（`ai` 与 `app` 模块）
1. **能力门控与互斥（`ModelTest`）**
   - `deepseek-flash` + `https://api.deepseek.com/v1` + `useResponseApi = true` → `RESPONSES_TOOL_SEARCH`。
   - `deepseek-flash` + `https://api.deepseek.com/anthropic/v1` (`Claude`) → `ANTHROPIC_DEFER_LOADING`。
   - `deepseek-flash` + `useResponseApi = false`（Chat Completions）→ `NONE`。
   - `deepseek-v4-pro` 或非 `api.deepseek.com` 域名 → `NONE`。
   - 启用 `BuiltInTools.DeepSeekWebSearch` 或 `DeepSeekReadUrl` 时，即使满足上述条件也强制返回 `NONE`。
2. **Anthropic 协议序列化与回放（`ClaudeProviderDeferredToolTest`）**
   - 顶层 `tools[]` 常驻工具在前、延迟工具带 `"defer_loading": true` 在后；无常驻工具时不发 `defer_loading`。
   - `tool_result.content` 正确生成 `{"type": "tool_reference", "tool_name": "..."}`（验证 key 是 `tool_name` 而非 `name`）。
   - 当某延迟工具被中途移除时，`tool_reference` 自动过滤该工具名，不发出未声明的工具引用。
   - 切到非支持模型/端点时，历史搜索结果降级为纯文本 `tool_result`。
3. **Responses 协议编解码与回放（`ResponseApiDeferredToolTest`）**
   - 顶层 `tools[]` 不含 `deferLoading = true` 工具，且正确生成 `{"type": "tool_search", "execution": "client", ...}`（无 `name`/`strict`）。
   - 回归保护：`isOpenAIServerToolCall("tool_search_call")`（`execution = "client"`）**不**再误判为 `ServerTool`；`execution = "server"` 仍保留兼容。
   - 流式与非流式均正确解析 `arguments` 为 **JSON 对象** 的 `tool_search_call`，并保留 `call_id` 与 `DeferredToolSearchCallMetadata`。
   - 多轮回放：`addAssistantItems` 正确输出 `tool_search_call`（`arguments` 为 JsonObject）+ `tool_search_output`（`tools` 内含完整 `function` schema 且 `strict: false`）；空结果时输出 `"tools": []`；思考模式下正确附带/回填 `reasoning` item。
   - 切到非支持模型时，历史记录降级为 `function_call` + `function_call_output`。
4. **目录检索与工具装配（`DeferredToolCatalogTest` & `ChatToolFactoryDeferredTest`）**
   - 精确名 > 前缀/子串 > 来源段 > 参数名 > 描述的排序正确性；中英文关键词与 `"*"` 通配行为；`limit` 截断。
   - 目录 `description` 输出顺序在多次调用间完全确定，无 HashSet 随机序抖动。
   - 回退矩阵：支持原生时跳过 `get_tool_map` / `mcp_tool_call` 桥；不支持原生时完整保留原 MCP 桥接；零延迟工具时不注入 `search_tools`。
   - 子代理信封：`explicitlyAllowedToolNames` 命中的工具提升为非延迟；`runToolCatalog` 剔除 `search_tools`。
5. **预算、元数据与备份（`ToolSchemaSizeTest` & `BackupContentFilterTest`）**
   - `ToolSchemaSize` 正确区分首轮常驻 token 与延迟池 token。
   - `maybeTruncateToolOutput` / `finalizeToolOutput` 保留 `DeferredToolSearchResultMetadata`。
   - 新设置字段在 `Settings` 编解码、旧版缺失字段兼容、导出导入过滤中往返无损。

### 8.2 实机 / 真实 API 联调验收清单
- [ ] **Responses API（`deepseek-flash` + 非思考 & 思考模式）**：首轮仅发常驻工具 + `tool_search`；模型能基于摘要主动调 `tool_search_call`，随后成功调用加载出的 MCP/插件工具并完成多轮对话（思考模式不报 `reasoning_text` 或 `call_id` 400）。
- [ ] **Anthropic 兼容端点（`deepseek-flash` + 非思考 & 思考模式）**：首轮 `tools[]` 带 `defer_loading: true`；`tool_result` 返回 `tool_reference` 后模型在同一回复或下一轮直接调用该延迟工具。
- [ ] **KV Cache 观测**：多轮调用中观察 `usage.cachedTokens`，确认追加加载工具后前缀缓存仍能命中。
- [ ] **互斥与降级验证**：在 `deepseek-flash` 开启 `DeepSeekWebSearch` 时，自动停用延迟加载并正常联网搜索；切到 `deepseek-v4-pro` 或关闭 `useResponseApi` 后无任何报错。
- [ ] **中途变更工具集**：加载某 MCP 工具后在设置中关闭该 MCP 服务器并继续对话，确认不触发未声明工具 400 错误。

---

## §9. 已拍板决议（2026-11-28）

1. **默认开关策略**：`nativeDeferredToolLoading` **默认开启（`true`）**，仅在命中 `deepseek-flash`（Responses / Anthropic）或支持的官方 Anthropic Claude 模型时自动生效，其余场景无损回退。
2. **默认延迟范围**：默认纳入 **`MCP_ALL` + `PLUGINS` + `SKILLS`**。
3. **`Workspace` 工具段归属**：**保持恒定常驻（Never Deferred）**，不纳入可选延迟范围。
4. **官方 Anthropic Claude**：**同步放行**支持 `defer_loading` 的 Claude 4.5+ / 5 系列模型，共用 `ANTHROPIC_DEFER_LOADING` 协议通道，并确保 `cache_control` 仅落在最后一个非延迟工具上。
