package app.androidrik.rikkahub.ui.pages.agentrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
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
     * 所选 profile 的工具目录（信封候选 = 实际会装配的全部工具）。
     * 与 run 侧同口径：memory 工具不在目录里（Phase 1 run 禁用记忆，见 §9.4）。
     */
    suspend fun toolCatalog(profile: Assistant): List<String> {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(profile.chatModelId ?: settings.chatModelId)
            ?: return emptyList()
        return chatToolFactory
            .createTools(settings, profile.copy(enableMemory = false), model, workspaceCwd = null)
            .map { it.name }
            .distinct()
            .sorted()
    }

    /** 派发；串行约束由 Runner 保证，返回 null = 已有活跃 run。 */
    fun dispatch(profile: Assistant, goal: String, allowedTools: Set<String>): Uuid? =
        runner.dispatchRun(profile, goal, allowedTools)
}

class AgentRunDetailVM(
    settingsStore: SettingsStore,
    private val runner: SubagentRunner,
    private val agentRunDAO: AgentRunDAO,
) : ViewModel() {

    val assistants = settingsStore.settingsFlow
        .map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun observeRun(id: String) = agentRunDAO.observeRun(id)

    fun cancel(id: Uuid) = runner.cancelRun(id)

    fun delete(id: Uuid) {
        viewModelScope.launch { runner.deleteRun(id) }
    }
}
