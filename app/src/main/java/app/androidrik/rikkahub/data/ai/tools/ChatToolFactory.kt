package app.androidrik.rikkahub.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.ui.UIMessagePart
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.TOOL_NO_APPROVAL
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
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.data.repository.MemoryFileRepository
import app.androidrik.rikkahub.data.model.MemoryReadSource
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.data.plugin.PluginToolProvider
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.workspace.ExecutionScope
import app.androidrik.workspace.WorkspaceShellStatus
import app.androidrik.rikkahub.utils.getStringContent

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
    "edit", "delete", "complete", "drop" -> assistant.memoryModifyApproval
    // **读/检索都不拦**：只读操作没有可被否决的副作用（与 workspace_task_status 免审批同款）。
    // 必须显式列出——落进 else 兜底会让渐进披露的每次 memory_read、以及每次按需检索，
    // 都变成一次审批轰炸（检索是「查找」不是「改动」，与 read 同类）。
    "read", "search" -> false
    "no_candidate" -> false
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
    /** 应用私有目录（view_image 工具的路径白名单根，见 [createViewImageTool]） */
    private val filesDirPath: String,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val ragRepository: app.androidrik.rikkahub.data.rag.RagRepository,
    private val workspaceRepository: WorkspaceRepository,
    /** 插件注册表:插件工具段的装配来源(能力门槛 `TOOL_REGISTER` 在 [createPluginToolSegments] 校验) */
    private val pluginManager: PluginManager,
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
    private val memoryFiles: MemoryFileRepository? = null,
) {
    /**
     * 装配本次生成的完整工具集（[createToolSegments] 的扁平视图）。
     *
     * 顺序保持迁移前不变（记忆工具在前，随后搜索/本地/对话/工作区/技能/子代理/MCP）——
     * Claude 的 `promptCaching` 会把缓存断点打在**最后一个**工具上，顺序变动会移动断点位置。
     */
    internal suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
        /**
         * 本次生成所属的会话 id（可选）。目前只有子代理派发工具用它——账本记下来才能在
         * 运行详情页给「回到对话」的反向入口。**只有本装配点知道这件事**，所以只能从这里透传。
         */
        conversationId: String? = null,
        /**
         * 本轮/本 run 的归属 scope（P3，后台任务与子代理归属）。
         *
         * **为什么从装配点透传**：scope 是「这一轮」的身份，只有 `ChatService` 知道它什么时候
         * 换新（新一轮用户消息 / 重新生成 / 继续生成）。工具层拿不到轮次概念，只能被喂。
         */
        turnOwner: ExecutionScope? = null,
        workspaceNoticeTarget: app.androidrik.workspace.WorkspaceTaskNoticeTarget? = null,
        /** 会话级「不使用记忆」（隐身对话）：为真时不装配记忆工具，见 [createToolSegments] */
        memoryDisabled: Boolean = false,
        /** 插件工具与既有段重名时的用户可见报错通道；null = 只记日志（预览/子代理路径） */
        onPluginToolsRejected: PluginToolsRejectedListener? = null,
        awaitBackgroundTasks: (suspend () -> List<String>)? = null,
        /**
         * Phone Use 来源门控（design-phone-use.md §2.3）：只有 App 交互回合为 true。
         * 定时任务、子代理、Web 远程会话传 false，装配期就不提供 `phone_use`。
         */
        allowPhoneUse: Boolean = true,
    ): List<Tool> = createToolSegments(
        settings = settings,
        assistant = assistant,
        model = model,
        workspaceCwd = workspaceCwd,
        conversationId = conversationId,
        turnOwner = turnOwner,
        workspaceNoticeTarget = workspaceNoticeTarget,
        memoryDisabled = memoryDisabled,
        onPluginToolsRejected = onPluginToolsRejected,
        awaitBackgroundTasks = awaitBackgroundTasks,
        allowPhoneUse = allowPhoneUse,
    )
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
         * 本轮/本 run 的归属 scope（P3）：写进 `workspace_run_background` 起的任务与子代理
         * 派发上下文，供取消时按 scope 精确/级联收掉。为 null 的装配方（预览）起的任务没有归属。
         */
        turnOwner: ExecutionScope? = null,
        workspaceNoticeTarget: app.androidrik.workspace.WorkspaceTaskNoticeTarget? = null,
        /**
         * 会话级「不使用记忆」（隐身对话；见 `docs/design/design-memory-discipline.md` §4.4）。
         * 为真时**不装配记忆工具**——它和「不注入记忆」是同一件事的两半：只关注入的话，
         * 模型仍可 `memory_read` 把记忆逐条读回来，开关等于装饰。
         */
        memoryDisabled: Boolean = false,
        /** 插件工具跨段重名的报错通道（P2a 拍板 1）；见 [createPluginToolSegments] */
        onPluginToolsRejected: PluginToolsRejectedListener? = null,
        awaitBackgroundTasks: (suspend () -> List<String>)? = null,
        /** Phone Use 来源门控，见 [createTools] */
        allowPhoneUse: Boolean = true,
    ): List<ToolSegment> {
        val segments = mutableListOf<ToolSegment>()
        if (settings.workspaceTaskNoticeEnabled && workspaceNoticeTarget?.allowWake == true && awaitBackgroundTasks != null) {
            segments += ToolSegment(ToolSource.Local, listOf(createAwaitBackgroundTasksTool(awaitBackgroundTasks)))
        }

        // 记忆工具（原在 GenerationHandler 循环内叠加，现为装配点的一部分）
        // **会话级「不使用记忆」时不装配**（隐身对话，见 docs/design/design-memory-discipline.md §4.4）：
        // 开关要能拦住「这次对话碰我的记忆库」，否则模型仍可 memory_read 逐条读回来、开关等于装饰。
        if (assistant.enableMemory && !memoryDisabled && assistant.memoryMode == MemoryMode.FILES) {
            memoryFiles?.let {
                segments += ToolSegment(ToolSource.Memory, listOf(buildFileMemoryTool(it, assistant.memoryId(), assistant)))
            }
        }
        if (assistant.enableMemory && !memoryDisabled && assistant.memoryMode == MemoryMode.ENTRIES) {
            val memoryAssistantId = assistant.memoryId()
            segments += ToolSegment(
                ToolSource.Memory,
                buildMemoryTools(
                    json = json,
                    onCreation = { content, importance, description ->
                        memoryRepository.addMemory(memoryAssistantId, content, importance, description,
                            actor = "CHAT", reason = "memory_tool create")
                    },
                    onUpdate = { _, _, _, _ -> error("Versioned update callback is required") },
                    onDelete = { error("Versioned delete callback is required") },
                    versionedOnUpdate = { id, content, importance, description, revision ->
                        memoryRepository.updateContentInStore(memoryAssistantId, id, content, importance, description,
                            expectedRevision = revision, actor = "CHAT", reason = "memory_tool edit")
                    },
                    typedOnCreation = { content, importance, description, kind, dueAt ->
                        memoryRepository.addMemory(memoryAssistantId, content, importance, description,
                            actor = "CHAT", reason = "memory_tool create", kind = kind, dueAt = dueAt)
                    },
                    typedVersionedOnUpdate = { id, content, importance, description, revision, kind, dueAt ->
                        memoryRepository.updateContentInStore(memoryAssistantId, id, content, importance, description,
                            expectedRevision = revision, actor = "CHAT", reason = "memory_tool edit",
                            kind = kind, dueAt = dueAt)
                    },
                    versionedOnComplete = { id, revision ->
                        memoryRepository.setTaskStatusInStore(memoryAssistantId, id, MemoryTaskStatus.DONE,
                            expectedRevision = revision, actor = "CHAT", reason = "memory_tool complete")
                    },
                    versionedOnDrop = { id, revision ->
                        memoryRepository.setTaskStatusInStore(memoryAssistantId, id, MemoryTaskStatus.DROPPED,
                            expectedRevision = revision, actor = "CHAT", reason = "memory_tool drop")
                    },
                    versionedOnDelete = { id, revision ->
                        check(memoryRepository.deleteMemoryInStore(memoryAssistantId, id,
                            expectedRevision = revision, actor = "CHAT", reason = "memory_tool delete")) {
                            "Memory record $id not found in store"
                        }
                    },
                    // 读取顺带记一次遥测（渐进披露的清单层靠它反馈「这条到底有没有用」）
                    onRead = { id ->
                        memoryRepository.readMemoryInStore(memoryAssistantId, id, source = MemoryReadSource.CHAT)
                    },
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

        // 上下文图片淘汰的取回工具（借鉴 NMoi D248）：只在淘汰开启时装配——关闭时窗口里
        // 没有占位路径，schema 白吃 token。与 ContextImageEvictionTransformer 配对。
        if (settings.contextImageLimit > 0) {
            segments += ToolSegment(ToolSource.Local, listOf(createViewImageTool(filesDirPath)))
        }

        if (shouldUseExternalWebSearch(assistant, model)) {
            segments += ToolSegment(ToolSource.Search, createSearchTools(settings).toList())
        }

        segments += ToolSegment(
            ToolSource.Local,
            localTools.getTools(
                options = assistant.localTools,
                phoneUseApproval = assistant.phoneUseApproval,
                allowPhoneUse = allowPhoneUse,
            ),
        )

        // 会话内生图（上游 #2123）。**门控 = 配置里能解析出生图模型**：没配就一个 token 都不付
        // （工具 schema 每次请求都要下发），也避免出现"一调用就报错"的工具——沿用本文件
        // `subagentDispatcher` 那条定式「宁可没有工具，也不能出现一个一调用就崩的工具」。
        // 用设置里的生图模型（不是当前 chat 模型），故 chat 模型不支持生图也能用。
        if (settings.resolveImageGenerationModel() != null) {
            segments += ToolSegment(
                ToolSource.Image,
                createImageTools(
                    assistant = assistant,
                    generate = { prompt, num, size ->
                        imageGenerationRunner.generate(settings, prompt, num, size)
                    },
                ),
            )
        }

        // AI 管理任务（Yuihub 形态，design-proactive-task-management.md §三）：能力层开关——
        // 关（默认）= 工具不装配，模型看不到也碰不到定时任务。动作层审批在工具内按 action 分档。
        if (assistant.taskManageEnabled) {
            segments += ToolSegment(
                ToolSource.TaskManage,
                listOf(buildTaskManageTool(assistant, conversationId)),
            )
        }

        if (assistant.enableRecentChatsReference) {
            segments += ToolSegment(ToolSource.Conversation, createConversationTools(conversationRepository, assistant.id))
        }

        segments += ToolSegment(
            ToolSource.Workspace,
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, turnOwner, workspaceNoticeTarget),
        )

        // 动态添加 Skill（上游 #1031）：AI 可从 GitHub 安装 / 把对话经验保存为技能。始终注册（不依赖已启用技能）。
        segments += ToolSegment(ToolSource.SkillInstall, createSkillInstallTools(SkillInstaller(skillManager)))

        if (assistant.enabledSkills.isNotEmpty()) {
            segments += ToolSegment(
                ToolSource.Skills,
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
                    ToolSource.Knowledge,
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
                    ToolSource.Subagent,
                    createSubagentTools(
                        presets = subagentPresets.map { it.copy(modelId = it.modelId ?: model.id) },
                        enableBackgroundSubagent = settings.enableBackgroundSubagent,
                        autoResume = settings.workspaceTaskNoticeEnabled && workspaceNoticeTarget?.allowWake == true && awaitBackgroundTasks != null,
                        dispatcher = dispatcher,
                        parentConversationId = conversationId,
                        dispatchContext = {
                            SubagentDispatchContext(
                                parentToolEnvelope = segments.filter { it.source != ToolSource.Subagent }
                                    .flatMap { it.tools }.map { it.name }.toSet(),
                                scopeToken = turnOwner?.wire,
                                parentWorkspaceId = assistant.workspaceId,
                                noticeTarget = workspaceNoticeTarget?.let {
                                    app.androidrik.rikkahub.data.task.AgentNoticeTarget(it.conversationId, it.assistantId,
                                        it.workspaceId, it.anchorMessageId, it.rootScope, it.allowWake)
                                },
                            )
                        },
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
                segments += ToolSegment(ToolSource.McpServer(serverName), entries.map { it.nativeTool })
            }

        // 折叠档：get_tool_map（有什么、参数长什么样）+ mcp_tool_call（桥，JSON 串传参）
        if (dynamicLoading && collapsed.isNotEmpty()) {
            segments += ToolSegment(
                ToolSource.McpDynamic,
                listOf(
                    Tool(
                        name = "get_tool_map",
                        description = """
                            List the heavy MCP tools whose full schemas are not loaded into this request.
                            Each entry gives the tool name, server, description and a compact params hint.
                            Call `mcp_tool_call` with a name from this map to execute one.
                        """.trimIndent(),
                        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
                        needsApproval = TOOL_NO_APPROVAL,
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

        // 插件工具段:模型侧看到的就是普通工具,来源记为 plugin:<id>(schema 诊断按段统计)。
        // 重名闸门(P2a 拍板 1):与内置/MCP/其他插件段同名 → 该插件段整体拒绝装配并报错。
        segments += createPluginToolSegments(
            existingSegments = segments.toList(),
            onPluginToolsRejected = onPluginToolsRejected,
        )

        logSchemaBreakdown(segments, settings, model)
        return segments
    }

    /**
     * 插件工具段。
     *
     * 门槛:插件已启用 + 持有 [PluginCapability.TOOL_REGISTER] + 实现 [PluginToolProvider]。
     * 能力在**装配点**校验,撤销能力后下一次装配即消失(不必等插件重启)。
     *
     * **跨段重名闸门**（P2a 拍板 1）：插件工具与内置/MCP 段或其他插件段同名（含插件段内
     * 自身重名）时，**该插件段整体拒绝装配**——模型可见名一律不变，不做命名空间前缀改名
     * （隐性改名会牵动历史按名寻址）。判定是纯函数 [acceptPluginToolSegments]（可单测），
     * 这里只负责收集插件段并落日志/回调；[onPluginToolsRejected] 由调用方（ChatService）
     * 转成用户可见错误，预览/子代理路径传 null = 只记日志。
     */
    private fun createPluginToolSegments(
        existingSegments: List<ToolSegment>,
        onPluginToolsRejected: PluginToolsRejectedListener?,
    ): List<ToolSegment> {
        val pluginSegments = pluginManager.plugins.value
            .filter { it.enabled && it.hasCapability(PluginCapability.TOOL_REGISTER) }
            .mapNotNull { info ->
                val provider = info.plugin as? PluginToolProvider ?: return@mapNotNull null
                val tools = provider.tools()
                if (tools.isEmpty()) null else ToolSegment(ToolSource.Plugin(info.plugin.id), tools)
            }
        val (accepted, rejected) = acceptPluginToolSegments(existingSegments, pluginSegments)
        rejected.forEach { (pluginId, conflicting) ->
            Log.w(TAG, "plugin tools rejected (name conflict): plugin=$pluginId tools=$conflicting")
            onPluginToolsRejected?.invoke(pluginId, conflicting)
        }
        return accepted
    }

    /**
     * 打印各来源工具的 schema 体积与审批/超时画像（每回合一次；P2a 补来源/审批/超时维度）。
     *
     * 目标是让 logcat 能回答三个问题：这个工具**从哪来**（[ToolSource.segmentName]）、
     * **要不要审批**（[approvalSummary]，按引用识别默认闭包）、**超时多少**
     * （[timeoutSummary]，含自管超时计数）。
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
                    val appr = approvalDiagnostic(approvalBaseline(seg.source), seg.tools)
                    "${seg.source.segmentName} ${seg.tools.size}/${tok}t" +
                        " appr=$appr to=${timeoutSummary(seg.tools)}"
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


    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        turnOwner: ExecutionScope? = null,
        workspaceNoticeTarget: app.androidrik.workspace.WorkspaceTaskNoticeTarget? = null,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd, turnOwner, workspaceNoticeTarget)
    }
}

/**
 * 一段同来源的工具。
 *
 * **顶层而非嵌套**：子代理的信封解析（`resolveSubagentEnvelope` / `runToolCatalog`）需要按
 * 来源判断"这个工具来自哪个开关控制的来源"，那是纯函数、放在工具包顶层；嵌套在
 * [ChatToolFactory] 里的话，每个签名都得写成 `ChatToolFactory.ToolSegment`，且纯函数测试
 * 要连带拖上工厂（含 Android 依赖）。来源的**段名是对外契约**，见 [ToolSource.segmentName]
 * 与 [createToolSegments]。
 */
internal data class ToolSegment(val source: ToolSource, val tools: List<Tool>)

/** 插件工具段因跨段重名被拒绝装配的通知（P2a 拍板 1）；第二参为冲突工具名。 */
internal typealias PluginToolsRejectedListener = (pluginId: String, conflictingNames: Set<String>) -> Unit

/** 一次拒绝的判定结果：[pluginId] 与它名下**冲突**的工具名（不含未冲突的）。 */
internal data class RejectedPluginTools(val pluginId: String, val conflictingNames: Set<String>)

/**
 * 插件段重名闸门的**纯判定**（P2a 拍板 1，单测直接覆盖）。
 *
 * 规则：插件工具名与 [existingSegments]（内置 + MCP）任一工具同名、与先前已接受的插件段同名、
 * 或**本段内部**重名 ⇒ 整段拒绝；否则整段接受并把工具名并入已见集合（后续插件段以它为对照）。
 * 「整段」而非「逐个剔除」：部分接受会让同一插件的工具集处于半生效状态，行为最难推理。
 */
internal fun acceptPluginToolSegments(
    existingSegments: List<ToolSegment>,
    pluginSegments: List<ToolSegment>,
): Pair<List<ToolSegment>, List<RejectedPluginTools>> {
    val seen = existingSegments.flatMapTo(mutableSetOf()) { seg -> seg.tools.map { it.name } }
    val accepted = mutableListOf<ToolSegment>()
    val rejected = mutableListOf<RejectedPluginTools>()
    for (segment in pluginSegments) {
        val pluginId = (segment.source as? ToolSource.Plugin)?.pluginId ?: continue
        val names = segment.tools.map { it.name }
        val conflicting = names.filterTo(sortedSetOf()) { it in seen } +
            names.groupBy { it }.filterValues { it.size > 1 }.keys
        if (conflicting.isNotEmpty()) {
            rejected += RejectedPluginTools(pluginId, conflicting)
        } else {
            seen += names
            accepted += segment
        }
    }
    return accepted to rejected
}

/**
 * 超时画像摘要：全部自管（`executionTimeoutMillis == null`）报 `self`；否则报**最大**外层
 * 上限（秒），后缀 `+Nself` 标注段内自管个数——自管超时清单此前只存在于注释约定
 * （设计稿 §16.2 ③），这里让它进机器可读输出。
 */
internal fun timeoutSummary(tools: List<Tool>): String {
    val selfManaged = tools.count { it.executionTimeoutMillis == null }
    val maxMillis = tools.mapNotNull { it.executionTimeoutMillis }.maxOrNull()
    return when {
        maxMillis == null -> "self"
        selfManaged == 0 -> "${maxMillis / 1000}s"
        else -> "${maxMillis / 1000}s+${selfManaged}self"
    }
}
