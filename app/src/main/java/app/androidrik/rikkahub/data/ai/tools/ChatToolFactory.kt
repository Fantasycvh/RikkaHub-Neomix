package app.androidrik.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.mcp.McpNameSanitizer
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.datastore.Settings
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
     */
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> = buildList {
        // 记忆工具（原在 GenerationHandler 循环内叠加，现为装配点的一部分）
        if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            addAll(
                buildMemoryTools(
                    json = json,
                    onCreation = { content -> memoryRepository.addMemory(memoryAssistantId, content) },
                    onUpdate = { id, content -> memoryRepository.updateContent(id, content) },
                    onDelete = { id -> memoryRepository.deleteMemory(id) },
                    // 分档审批：create 默认放行（可选开关）、edit/delete 默认拦截——见 memoryToolNeedsApproval
                    needsApproval = { action -> memoryToolNeedsApproval(assistant, action) },
                )
            )
        }

        if (shouldUseExternalWebSearch(assistant, model)) {
            addAll(createSearchTools(settings))
        }

        addAll(localTools.getTools(assistant.localTools))

        if (assistant.enableRecentChatsReference) {
            addAll(createConversationTools(conversationRepository, assistant.id))
        }

        addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd))

        // 动态添加 Skill（上游 #1031）：AI 可从 GitHub 安装 / 把对话经验保存为技能。始终注册（不依赖已启用技能）。
        addAll(createSkillInstallTools(SkillInstaller(skillManager)))

        if (assistant.enabledSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    enabledSkills = assistant.enabledSkills,
                    allSkills = skillManager.listSkills(),
                )
            )
        }

        // 服务器名/工具名允许含中文等非 ASCII 字符，这里净化为 provider 可接受的
        // function name（^[a-zA-Z0-9_-]{1,64}$），显示层仍用原名。
        // 不同原名可能净化后相同，用 usedNames 保证发给模型的名字唯一。
        val usedToolNames = mutableSetOf<String>()
        mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
            add(
                Tool(
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
            )
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
