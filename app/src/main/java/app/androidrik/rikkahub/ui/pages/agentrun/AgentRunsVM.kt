package app.androidrik.rikkahub.ui.pages.agentrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.tools.runToolCatalog
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.service.SubagentRunner
import kotlin.uuid.Uuid

/**
 * 运行列表 + 派发（账本的 UI 面，设计 §9.3/§9.4）。
 *
 * 工具目录（信封候选）按所选 profile **实时计算**：`createTools` 是 suspend（MCP 动态段），
 * 且结果随 profile 的绑定（工作区/技能/搜索）变化——信封必须反映「这次真的会装配什么」。
 */
class AgentRunsVM(
    private val settingsStore: SettingsStore,
    private val runner: SubagentRunner,
    agentRunDAO: AgentRunDAO,
    private val chatToolFactory: ChatToolFactory,
) : ViewModel() {

    val runs = agentRunDAO.observeRuns()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val assistants = settingsStore.settingsFlow
        .map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun hasActiveRun(): Boolean = runner.hasActiveRun()

    /**
     * 所选 profile 的工具目录（信封候选 = run 里**实际可用**的全部工具）。
     *
     * 与 run 侧同口径：①memory 工具不在目录里（run 禁用记忆，见 §9.4）；
     * ②`dispatch_subagent` 也在目录外——run 恒定不装配它（禁嵌套，见 `runToolCatalog`），
     * 列在候选里等于承诺一件不会发生的事。
     */
    suspend fun toolCatalog(profile: Assistant): List<String> {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(profile.chatModelId ?: settings.chatModelId)
            ?: return emptyList()
        return runToolCatalog(
            chatToolFactory.createToolSegments(
                settings,
                profile.copy(enableMemory = false),
                model,
                workspaceCwd = null,
            )
        )
    }

    /** 派发；串行约束由 Runner 保证，返回 null = 已有活跃 run。 */
    fun dispatch(profile: Assistant, goal: String, allowedTools: Set<String>): Uuid? =
        runner.dispatchRun(profile, goal, allowedTools)
}

class AgentRunDetailVM(
    settingsStore: SettingsStore,
    private val runner: SubagentRunner,
    private val agentRunDAO: AgentRunDAO,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    val assistants = settingsStore.settingsFlow
        .map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun observeRun(id: String) = agentRunDAO.observeRun(id)

    /**
     * 父会话是否**还在**（详情页据此决定给不给「回到对话」入口）。
     *
     * 必须判存在：父指针是无外键的纯列（见 `AgentRunEntity.parentConversationId`），
     * 会话被删后 run 仍留在账本里，此时那个 id 是悬空的——直接跳过去会落到一个空会话页。
     */
    suspend fun parentConversationExists(id: String): Boolean =
        runCatching { conversationRepository.existsConversationById(Uuid.parse(id)) }.getOrDefault(false)

    fun cancel(id: Uuid) = runner.cancelRun(id)

    fun delete(id: Uuid) {
        viewModelScope.launch { runner.deleteRun(id) }
    }
}
