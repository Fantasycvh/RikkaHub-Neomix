package app.androidrik.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.util.ToolSchemaSize
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.mcp.McpNameSanitizer
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findProvider
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
    private val workspaceRepository: WorkspaceRepository,
) {
    /**
     * 装配本次生成的完整工具集。
     *
     * 顺序保持迁移前不变（记忆工具在前，随后搜索/本地/对话/工作区/技能/MCP）——
     * Claude 的 `promptCaching` 会把缓存断点打在**最后一个**工具上，顺序变动会移动断点位置。
     *
     * 按来源分段收集（而非一段 `addAll` 到底）：末尾要按来源打印 schema 体积，
     * 那是排查「谁吃掉了上下文」的关键——实测 MCP 服务器可轻易吃掉 3 万 token
     * （54 个逆向工具的 schema），而记忆注入只有数千，不按来源拆就看不出元凶。
     */
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val segments = mutableListOf<ToolSegment>()

        // 记忆工具（原在 GenerationHandler 循环内叠加，现为装配点的一部分）
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            segments += ToolSegment(
                "memory",
                buildMemoryTools(
                    json = json,
                    onCreation = { content, importance ->
                        memoryRepository.addMemory(memoryAssistantId, content, importance)
                    },
                    onUpdate = { id, content, importance ->
                        memoryRepository.updateContent(id, content, importance)
                    },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                    // 分档审批：create 默认放行（可选开关）、edit/delete 默认拦截——见 memoryToolNeedsApproval
                    needsApproval = { action -> memoryToolNeedsApproval(assistant, action) },
                ),
            )
        }

        if (shouldUseExternalWebSearch(assistant, model)) {
            segments += ToolSegment("search", createSearchTools(settings).toList())
        }

        segments += ToolSegment("local", localTools.getTools(assistant.localTools))

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

        // 服务器名/工具名允许含中文等非 ASCII 字符，这里净化为 provider 可接受的
        // function name（^[a-zA-Z0-9_-]{1,64}$），显示层仍用原名。
        // 不同原名可能净化后相同，用 usedNames 保证发给模型的名字唯一。
        // **按服务器分别成段**：MCP 往往是最重的一块，按服务器拆分才能看出是哪一个。
        val usedToolNames = mutableSetOf<String>()
        val mcpToolsByServer = linkedMapOf<String, MutableList<Tool>>()
        mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
            mcpToolsByServer.getOrPut("mcp:$serverName") { mutableListOf() } += Tool(
                name = McpNameSanitizer.buildUniqueFunctionName(
                    serverName = serverName,
                    toolName = tool.name,
                    used = usedToolNames,
                ),
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = {
                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                },
            )
        }
        mcpToolsByServer.forEach { (source, tools) -> segments += ToolSegment(source, tools) }

        logSchemaBreakdown(segments, settings, model)
        return segments.flatMap { it.tools }
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

    /** 一段同来源的工具（仅用于日志归因，不影响装配顺序）。 */
    private data class ToolSegment(val source: String, val tools: List<Tool>)

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
