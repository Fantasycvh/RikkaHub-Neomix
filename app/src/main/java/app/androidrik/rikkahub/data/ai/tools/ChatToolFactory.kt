package app.androidrik.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import app.androidrik.rikkahub.ui.components.message.tools.getStringContent
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.ui.UIMessagePart
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.util.ToolSchemaSize
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.mcp.McpNameSanitizer
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.image.resolveImageGenerationModel
import app.androidrik.rikkahub.data.files.SkillInstaller
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.WorkspaceShellStatus

private const val TAG = "ChatToolFactory"

/**
 * 是否注册外挂（客户端）搜索工具。
 *
 * 模型自带内置搜索（`BuiltInTools.Search`，如 Gemini / GPT 原生搜索）时不再叠加，
 * 否则会同时下发两套搜索能力——既浪费 schema 预算，也可能让两套搜索冲突
 * （上游 `aac6e963` / #1666「统一内置搜索与外挂搜索行为」）。
 */
internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

/**
 * 记忆工具是否需要用户审批（按 action 分档，两个开关相互独立）。
 *
 * - `create`：由 [Assistant.memoryCreateApproval] 决定（**默认关** = 直接记录）
 * - `edit` / `delete`：由 [Assistant.memoryModifyApproval] 决定（默认开）
 * - 缺失/未知 action：按 edit/delete 同口径（**宁可多拦，不可漏放**——不能被当成 create 蒙混过关）
 *
 * 抽成纯函数以便单测直接覆盖生产判定（此前测试各自复刻 lambda，等于没测到生产逻辑）。
 */
internal fun memoryToolNeedsApproval(assistant: Assistant, action: String): Boolean = when (action) {
    "create" -> assistant.memoryCreateApproval
    "edit", "delete" -> assistant.memoryModifyApproval
    // **读/检索都不拦**：只读操作没有可被否决的副作用（与 workspace_task_status 免审批同款）。
    // 必须显式列出——落进 else 兜底会让渐进披露的每次 memory_read、以及每次按需检索，
    // 都变成一次审批轰炸（检索是「查找」不是「改动」，与 read 同类）。
    "read", "search" -> false
    else -> assistant.memoryModifyApproval
}

/**
 * 单次生成所需工具集的**唯一装配点**（上游 `097cdb90` 的 fork 适配）。
 *
 * 迁移自 `ChatService` 的内联 `buildList` 与 `GenerationHandler` 内叠加的记忆工具——
 * 原先装配分散在两处（记忆工具在生成循环内层），集中后新增装配方（MCP host、
 * Agent/工作流入口等）只需改这里。见 `docs/upstream-analysis.md`「注意点」第 9 条。
 *
 * 与上游的差异：MCP 工具名沿用 fork 的 [McpNameSanitizer] **净化去重**，而非上游的
 * `InvalidMcpServerNamesException` 抛错——前者允许中文等非 ASCII 服务器名（有用户可见收益）。
 */
class ChatToolFactory(
    private val json: Json,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val ragRepository: app.androidrik.rikkahub.data.rag.RagRepository,
    private val workspaceRepository: WorkspaceRepository,
    /** 会话内生图工具（#2123）的写路径；见 [app.androidrik.rikkahub.data.image.ImageGenerationRunner] */
    private val imageGenerationRunner: ImageGenerationRunner,
    /**
     * 对话内子代理派发器（[SubagentDispatcher] 的**延迟解析**）。
     *
     * 为什么是 `() -> SubagentDispatcher?` 而不是直接注入：`SubagentRunner` 依赖本工厂
     * （它要用装配点装配子代理的工具），本工厂又要给派发工具一个 dispatcher——
     * 直接互相注入就是构造环（Koin 会报 `NoDefinitionFound` 或栈溢出）。工具侧拿到的只是
     * 这个 lambda，环断在"调用时才解析"这一步。
     *
     * 默认 `{ null }`：没有派发器时子代理段**不装配**（宁可没有工具，也不能出现一个
     * 一调用就崩的工具）。
     */
    private val subagentDispatcher: () -> SubagentDispatcher? = { null },
) {
    /**
     * 装配本次生成的完整工具集（[createToolSegments] 的扁平视图）。
     *
     * 顺序保持迁移前不变（记忆工具在前，随后搜索/本地/对话/工作区/技能/子代理/MCP）——
     * Claude 的 `promptCaching` 会把缓存断点打在**最后一个**工具上，顺序变动会移动断点位置。
     */
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        /**
         * 本次生成所属的会话 id（可选）。目前只有子代理派发工具用它——账本记下来才能在
         * 运行详情页给「回到对话」的反向入口。**只有本装配点知道这件事**，所以只能从这里透传。
         */
        conversationId: String? = null,
        /** 会话级「不使用记忆」（隐身对话）：为真时不装配记忆工具，见 [createToolSegments] */
        memoryDisabled: Boolean = false,
    ): List<Tool> = createToolSegments(settings, assistant, model, workspaceCwd, conversationId, memoryDisabled = memoryDisabled)
        .flatMap { it.tools }

    /**
     * 按来源**分段**装配工具集。
     *
     * 按来源分段收集（而非一段 `addAll` 到底）：末尾要按来源打印 schema 体积，
     * 那是排查「谁吃掉了上下文」的关键——实测 MCP 服务器可轻易吃掉 3 万 token
     * （54 个逆向工具的 schema），而记忆注入只有数千，不按来源拆就看不出元凶。
     *
     * **段名是给子代理信封用的**（`resolveSubagentEnvelope`）：预设的"能力开关"落到
     * `search` / `workspace` 两个段名上，其余来源（MCP / 技能安装）只有被**显式点名**
     * 才进得了子代理的工具集——所以段名不是纯粹的日志分类，改动它会影响信封语义。
     */
    internal suspend fun createToolSegments(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        conversationId: String? = null,
        /**
         * 调用方**显式点名**的工具名（子代理信封）。折叠档中被点名的工具会**提升回原生档**：
         * 折叠把工具从 `segments` 里摘掉，而子代理的信封是**按名求交集**的
         * （`resolveSubagentEnvelope`），不提升就会出现「预设里点了名、run 里却静默没有」——
         * 宁可多花那一个工具的 schema，也不能让显式配置静默失效。
         */
        explicitlyAllowedToolNames: Set<String> = emptySet(),
        /**
         * 会话级「不使用记忆」（隐身对话；见 `docs/design/design-memory-discipline.md` §4.4）。
         * 为真时**不装配记忆工具**——它和「不注入记忆」是同一件事的两半：只关注入的话，
         * 模型仍可 `memory_read` 把记忆逐条读回来，开关等于装饰。
         */
        memoryDisabled: Boolean = false,
    ): List<ToolSegment> {
        val segments = mutableListOf<ToolSegment>()

        // 记忆工具（原在 GenerationHandler 循环内叠加，现为装配点的一部分）
        // **会话级「不使用记忆」时不装配**（隐身对话，见 docs/design/design-memory-discipline.md §4.4）：
        // 开关要能拦住「这次对话碰我的记忆库」，否则模型仍可 memory_read 逐条读回来、开关等于装饰。
        if (assistant.enableMemory && !memoryDisabled) {
            val memoryAssistantId = assistant.memoryId()
            segments += ToolSegment(
                "memory",
                buildMemoryTools(
                    json = json,
                    onCreation = { content, importance, description ->
                        memoryRepository.addMemory(memoryAssistantId, content, importance, description)
                    },
                    onUpdate = { id, content, importance, description ->
                        memoryRepository.updateContent(id, content, importance, description)
                    },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                    // 读取顺带记一次遥测（渐进披露的清单层靠它反馈「这条到底有没有用」）
                    onRead = { id -> memoryRepository.readMemory(id) },
                    // 按需检索：把注入预算之外、清单层也没列到的记录变成可取的（**不记遥测**，
                    // 口径理由见 MemoryRepository.searchMemories 的 kdoc）
                    onSearch = { query, limit ->
                        memoryRepository.searchMemories(memoryAssistantId, query, limit)
                    },
                    // 分档审批：create 默认放行（可选开关）、edit/delete 默认拦截、read/search 不拦
                    needsApproval = { action -> memoryToolNeedsApproval(assistant, action) },
                ),
            )
        }

        if (shouldUseExternalWebSearch(assistant, model)) {
            segments += ToolSegment("search", createSearchTools(settings).toList())
        }

        segments += ToolSegment("local", localTools.getTools(assistant.localTools))

        // 会话内生图（上游 #2123）。**门控 = 配置里能解析出生图模型**：没配就一个 token 都不付
        // （工具 schema 每次请求都要下发），也避免出现"一调用就报错"的工具——沿用本文件
        // `subagentDispatcher` 那条定式「宁可没有工具，也不能出现一个一调用就崩的工具」。
        // 用设置里的生图模型（不是当前 chat 模型），故 chat 模型不支持生图也能用。
        if (settings.resolveImageGenerationModel() != null) {
            segments += ToolSegment(
                "image",
                createImageTools(
                    assistant = assistant,
                    generate = { prompt, num, size ->
                        imageGenerationRunner.generate(settings, prompt, num, size)
                    },
                ),
            )
        }

        if (assistant.enableRecentChatsReference) {
            segments += ToolSegment("conversation", createConversationTools(conversationRepository, assistant.id))
        }

        segments += ToolSegment(
            "workspace",
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd),
        )

        // 动态添加 Skill（上游 #1031）：AI 可从 GitHub 安装 / 把对话经验保存为技能。始终注册（不依赖已启用技能）。
        segments += ToolSegment("skill-install", createSkillInstallTools(SkillInstaller(skillManager)))

        if (assistant.enabledSkills.isNotEmpty()) {
            segments += ToolSegment(
                "skills",
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                ),
            )
        }

        // 知识库检索（RAG-as-tool）：助手绑定了至少一个知识库才装配；单工具跨库检索。
        if (assistant.enabledKnowledgeBaseIds.isNotEmpty()) {
            val knowledgeBases = ragRepository.getKnowledgeBases(assistant.enabledKnowledgeBaseIds)
            if (knowledgeBases.isNotEmpty()) {
                segments += ToolSegment(
                    "knowledge",
                    listOf(createKnowledgeBaseTool(ragRepository, knowledgeBases)),
                )
            }
        }

        // 子代理（对话内派发，设计见 docs/design/design-subagent-context.md §十）。
        // 三重门槛：全局开关开 / 至少一个可用预设 / 派发器就绪（延迟解析，见构造参数）。
        // 预设的 `modelId` 留空语义是「跟随当前对话」，而"当前"只有装配点知道 ⇒ 在这里解析。
        val subagentPresets = settings.subagentPresets.filter { it.enabled && it.name.isNotBlank() }
        if (settings.enableSubagentDispatch && subagentPresets.isNotEmpty()) {
            subagentDispatcher()?.let { dispatcher ->
                segments += ToolSegment(
                    "subagent",
                    createSubagentTools(
                        presets = subagentPresets.map { it.copy(modelId = it.modelId ?: model.id) },
                        dispatcher = dispatcher,
                        parentConversationId = conversationId,
                    ),
                )
            }
        }

        // 服务器名/工具名允许含中文等非 ASCII 字符，这里净化为 provider 可接受的
        // function name（^[a-zA-Z0-9_-]{1,64}$），显示层仍用原名。
        // 不同原名可能净化后相同，用 usedNames 保证发给模型的名字唯一。
        // **按服务器分别成段**：MCP 往往是最重的一块，按服务器拆分才能看出是哪一个。
        //
        // **动态分层加载**（peer⑤③ 缩水版）：单工具 schema 超过阈值的重型工具不再进
        // provider schema，由 `get_tool_map` + `mcp_tool_call` 两个元工具替代——
        // provider schema 恒定（缓存断点不动），token 成本从 33K 级降到 O(小工具数)。
        // 开关关 / 无折叠档时零行为变化（全部原生）。
        // MCP 工具的外层上限给得比默认宽：MCP 服务端可能是 LLM 驱动的重型服务（检索/分析），
        // 60s 会误杀正常工作。它防的是「服务端不响应时无限悬挂」。
        val mcpTimeoutMillis = 180_000L
        val usedToolNames = mutableSetOf<String>()
        val providerTypeForMcp = model.findProvider(settings.providers, checkOverwrite = false)
        val mcpEntries = mcpManager.getAllAvailableTools().map { (serverId, serverName, tool) ->
            val functionName = McpNameSanitizer.buildUniqueFunctionName(
                serverName = serverName,
                toolName = tool.name,
                used = usedToolNames,
            )
            val nativeTool = Tool(
                name = functionName,
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                executionTimeoutMillis = mcpTimeoutMillis,
                execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
            )
            McpToolEntry(
                serverId = serverId.toString(),
                serverName = serverName,
                functionName = functionName,
                originalToolName = tool.name,
                description = tool.description ?: "",
                paramsHint = buildParamsHint(
                    (tool.inputSchema as? app.androidrik.ai.core.InputSchema.Obj)?.properties
                ),
                needsApproval = tool.needsApproval,
                schemaTokens = ToolSchemaSize.estimate(nativeTool, providerTypeForMcp, json),
                nativeTool = nativeTool,
            )
        }
        val dynamicLoading = settings.mcpDynamicSchemaLoading
        val split = splitMcpToolsBySchemaSize(mcpEntries)
        // 显式点名的折叠工具提升回原生：信封按名求交集，摘掉即静默失效（见参数注释）
        val (promoted, collapsed) = promoteExplicitlyAllowed(split.collapsed, explicitlyAllowedToolNames)

        // 原生档：按服务器成段（与既有行为一致）
        (split.native + promoted)
            .groupBy { it.serverName }
            .forEach { (serverName, entries) ->
                segments += ToolSegment("mcp:$serverName", entries.map { it.nativeTool })
            }

        // 折叠档：get_tool_map（有什么、参数长什么样）+ mcp_tool_call（桥，JSON 串传参）
        if (dynamicLoading && collapsed.isNotEmpty()) {
            segments += ToolSegment(
                "mcp-dynamic",
                listOf(
                    Tool(
                        name = "get_tool_map",
                        description = """
                            List the heavy MCP tools whose full schemas are not loaded into this request.
                            Each entry gives the tool name, server, description and a compact params hint.
                            Call `mcp_tool_call` with a name from this map to execute one.
                        """.trimIndent(),
                        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
                        needsApproval = { false },
                        execute = {
                            listOf(
                                UIMessagePart.Text(buildToolMapPayload(collapsed))
                            )
                        },
                    ),
                    Tool(
                        name = "mcp_tool_call",
                        description = """
                            Execute an MCP tool by name with JSON-encoded arguments.
                            Use `get_tool_map` first to learn the available tools and their params hint.
                            `arguments` must be a JSON object string matching the tool's params hint
                            (e.g. "{\"query\": "rikkahub"}").
                        """.trimIndent(),
                        parameters = {
                            InputSchema.Obj(
                                properties = buildJsonObject {
                                    put("name", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Tool name from `get_tool_map`")
                                    })
                                    put("arguments", buildJsonObject {
                                        put("type", "string")
                                        put("description", "JSON object string with the tool's arguments")
                                    })
                                },
                                required = listOf("name", "arguments"),
                            )
                        },
                        executionTimeoutMillis = mcpTimeoutMillis,
                        // 审批跟随被调用的底层工具（折叠不能变成审批绕行）
                        needsApproval = { args ->
                            resolveCollapsedEntry(collapsed, args.getStringContent("name").orEmpty())
                                ?.needsApproval == true
                        },
                        execute = { args ->
                            val name = args.getStringContent("name")
                                ?: error("name is required")
                            val entry = resolveCollapsedEntry(collapsed, name)
                                ?: error(
                                    "Unknown MCP tool: $name. Available: " +
                                        collapsed.joinToString(", ") { it.functionName }
                                )
                            val argumentsText = args.getStringContent("arguments")
                                ?: error("arguments is required")
                            val arguments = runCatching {
                                Json.parseToJsonElement(argumentsText).jsonObject
                            }.getOrElse {
                                error("arguments must be a valid JSON object string: ${it.message}")
                            }
                            mcpManager.callTool(
                                Uuid.parse(entry.serverId),
                                // 桥按净化名寻址，执行回 MCP 协议原名
                                entry.originalToolName,
                                arguments,
                            )
                        },
                    ),
                ),
            )
        }

        logSchemaBreakdown(segments, settings, model)
        return segments
    }

    /**
     * 打印各来源工具的 schema 体积（每回合一次）。
     *
     * 用 `Log.i` 而非 `Logging.log()`：后者进内存环形缓冲（上限 100 条），会被高频日志冲掉；
     * 这类诊断信息走 logcat 即可，也不需要落盘开关配合。
     *
     * 估算按**当前 provider 的实际形状**（OpenAI/Claude/Google 三种包装与变换不同），
     * 故数字会随 provider 变化——这是有意的，它反映真实下发的字节数。
     */
    private fun logSchemaBreakdown(segments: List<ToolSegment>, settings: Settings, model: Model) {
        runCatching {
            val providerType = model.findProvider(settings.providers, checkOverwrite = false)
            val total = segments.sumOf { it.tools.size }
            if (total == 0) {
                Log.i(TAG, "tool schema: no tools registered")
                return
            }
            val perSource = segments
                .filter { it.tools.isNotEmpty() }
                .joinToString(separator = " | ") { seg ->
                    val tok = ToolSchemaSize.estimate(seg.tools, providerType, json)
                    "${seg.source} ${seg.tools.size}/${tok}t"
                }
            val totalTokens = ToolSchemaSize.estimate(
                segments.flatMap { it.tools }, providerType, json,
            )
            Log.i(
                TAG,
                "tool schema total=$total tools ≈${totalTokens} tokens " +
                    "(shape=${providerType?.javaClass?.simpleName ?: "openai-default"}) → $perSource",
            )
        }.onFailure {
            Log.w(TAG, "logSchemaBreakdown failed", it)
        }
    }


    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}

/**
 * 一段同来源的工具。
 *
 * **顶层而非嵌套**：子代理的信封解析（`resolveSubagentEnvelope` / `runToolCatalog`）需要按
 * 段名判断"这个工具来自哪个开关控制的来源"，那是纯函数、放在工具包顶层；嵌套在
 * [ChatToolFactory] 里的话，每个签名都得写成 `ChatToolFactory.ToolSegment`，且纯函数测试
 * 要连带拖上工厂（含 Android 依赖）。段名因此是**对外契约**，见 [createToolSegments]。
 */
internal data class ToolSegment(val source: String, val tools: List<Tool>)
