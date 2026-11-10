package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

data class SkinSelection(val id: String?, val overrides: AppSkinOverrides) {
    fun applyTo(display: DisplaySetting) = display.copy(appSkinId = id, appSkinOverrides = overrides)
    companion object { fun from(display: DisplaySetting) = SkinSelection(display.appSkinId, display.appSkinOverrides) }
}
data class SkinTrial(val token: String, val before: SkinSelection, val target: SkinSelection, val skin: AppSkin, val name: String,
    val startedNanos: Long = System.nanoTime())
data class SkinUndo(val before: SkinSelection, val after: SkinSelection, val afterSkin: AppSkin)

private fun resolveTrialSelection(selection: SkinSelection): AppSkin {
    check(selection.id == null || AppSkinRegistry.shared.skins.value.any { it.id == selection.id }) { "皮肤方案已卸载或停用" }
    return resolveAppSkin(selection.applyTo(DisplaySetting())).skin
}

/** Process-local preview and one-step undo. Only the skin fields participate in compare-and-set writes. */
class SkinTrialController {
    private val trialState = MutableStateFlow<SkinTrial?>(null)
    val trial = trialState.asStateFlow()
    private val undoState = MutableStateFlow<SkinUndo?>(null)
    val undo = undoState.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private val mutex = Mutex()
    private var cancelAfterWrite: String? = null
    private val hosts = mutableSetOf<String>()
    @Synchronized fun attachHost(): AutoCloseable {
        val id = UUID.randomUUID().toString()
        hosts.add(id)
        return AutoCloseable { synchronized(this) {
            if (hosts.remove(id) && hosts.isEmpty()) cancel()
        } }
    }
    @Synchronized fun start(display: DisplaySetting, target: SkinSelection, skin: AppSkin, name: String) {
        if (working.value) return
        trialState.value = SkinTrial(UUID.randomUUID().toString(), SkinSelection.from(display), target, skin.normalized(), name)
    }
    @Synchronized fun cancel(token: String? = trialState.value?.token) {
        if (token != null && trialState.value?.token == token) {
            if (working.value) cancelAfterWrite = token else trialState.value = null
        }
    }
    @Synchronized private fun finishWrite() {
        working.value = false
        if (cancelAfterWrite != null && trialState.value?.token == cancelAfterWrite) trialState.value = null
        cancelAfterWrite = null
    }
    suspend fun apply(update: suspend ((DisplaySetting) -> DisplaySetting) -> Unit,
        resolve: (SkinSelection) -> AppSkin = ::resolveTrialSelection,
        token: String? = trialState.value?.token) = mutex.withLock { withContext(NonCancellable) {
        val session = synchronized(this) { working.value = true; trialState.value }
        try {
            check(session?.token == token) { "试穿已被替换，请确认当前方案" }
            if (session != null) {
                update { current ->
                    check(SkinSelection.from(current) == session.before) { "皮肤设置已变化，请重新试穿" }
                    check(resolve(session.target) == session.skin) { "皮肤方案已更新或卸载，请重新试穿" }
                    session.target.applyTo(current)
                }
                undoState.value = SkinUndo(session.before, session.target, session.skin)
                trialState.value = null
            }
        } finally { finishWrite() }
    } }
    suspend fun undo(update: suspend ((DisplaySetting) -> DisplaySetting) -> Unit,
        resolve: (SkinSelection) -> AppSkin = ::resolveTrialSelection,
        expected: SkinUndo? = undoState.value) = mutex.withLock { withContext(NonCancellable) {
        synchronized(this) { working.value = true }
        try {
            check(undoState.value == expected) { "撤销记录已变化，请重试" }
            undoState.value?.let { action -> update { current ->
                check(SkinSelection.from(current) == action.after) { "皮肤设置已变化，不能覆盖后续编辑" }
                check(resolve(action.after) == action.afterSkin) { "方案内容已更新，请使用版本历史" }
                action.before.applyTo(current)
            }; undoState.value = null }
        } finally { finishWrite() }
    } }
    fun dismissUndo() { if (!working.value) undoState.value = null }
    companion object { val shared = SkinTrialController() }
}
