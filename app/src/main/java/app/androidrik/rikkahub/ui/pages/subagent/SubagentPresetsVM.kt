package app.androidrik.rikkahub.ui.pages.subagent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.tools.ToolSegment
import app.androidrik.rikkahub.data.ai.tools.resolveSubagentEnvelope
import app.androidrik.rikkahub.data.ai.tools.runToolCatalog
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.clamped
import app.androidrik.rikkahub.data.model.toRunProfile
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

/**
 * 子代理预设管理（「按名称调度的模型 + 提示词」组合）。
 *
 * **工具预览与 run 侧同源**：生效工具不是页面上推算的，而是真的调一次装配点
 * （[ChatToolFactory.createToolSegments]）+ 信封解析（[resolveSubagentEnvelope]）——
 * 与 `SubagentRunner` 执行时用的是同一份代码。所以页面上写着"生效 3 个工具"，
 * run 里就真的是那 3 个（不会出现"界面说能搜、跑起来没工具"的静默错位）。
 */
class SubagentPresetsVM(
    private val settingsStore: SettingsStore,
    private val chatToolFactory: ChatToolFactory,
    workspaceRepository: WorkspaceRepository,
) : ViewModel() {

    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val workspaces = workspaceRepository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setDispatchEnabled(enabled: Boolean) = update { it.copy(enableSubagentDispatch = enabled) }

    /** 新增或覆盖（按 id 定位）；写入前钳制越界值并去掉名字两侧空白。 */
    fun upsertPreset(preset: SubagentPreset) = update { settings ->
        val cleaned = preset.clamped()
        val list = settings.subagentPresets
        val index = list.indexOfFirst { it.id == cleaned.id }
        settings.copy(
            subagentPresets = if (index >= 0) {
                list.toMutableList().apply { set(index, cleaned) }
            } else {
                list + cleaned
            }
        )
    }

    fun deletePreset(id: Uuid) = update { settings ->
        settings.copy(subagentPresets = settings.subagentPresets.filterNot { it.id == id })
    }

    fun setPresetEnabled(id: Uuid, enabled: Boolean) = update { settings ->
        settings.copy(
            subagentPresets = settings.subagentPresets.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
        )
    }

    /** 该预设**实际生效**的工具（信封解析结果）。 */
    suspend fun effectiveTools(preset: SubagentPreset): List<String> {
        val segments = assemble(preset) ?: return emptyList()
        return resolveSubagentEnvelope(segments, preset.allowedTools).sorted()
    }

    /** 装配出的工具全集（「指定工具」模式的候选；已剔掉子代理工具本身）。 */
    suspend fun toolCandidates(preset: SubagentPreset): List<String> {
        val segments = assemble(preset) ?: return emptyList()
        return runToolCatalog(segments)
    }

    private suspend fun assemble(preset: SubagentPreset): List<ToolSegment>? {
        val current = settingsStore.settingsFlow.value
        val profile = preset.toRunProfile()
        // 预设没指定模型时"跟随当前对话"，管理页没有"当前对话"⇒ 用全局默认模型当口径，
        // 只是为了让候选工具列表可用（工具集与模型无关，只有 provider 形态会影响聚合）。
        val model = current.findModelById(preset.modelId ?: current.chatModelId) ?: return null
        return chatToolFactory.createToolSegments(current, profile, model, workspaceCwd = null)
    }

    private fun update(fn: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(fn) }
    }
}
