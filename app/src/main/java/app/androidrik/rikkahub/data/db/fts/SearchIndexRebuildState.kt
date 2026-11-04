package app.androidrik.rikkahub.data.db.fts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 恢复后搜索索引重建的对外状态（进程级，UI 用于显示进度/失败重试）。 */
sealed interface SearchIndexRebuildState {
    data object Idle : SearchIndexRebuildState
    data class Running(val done: Int, val total: Int) : SearchIndexRebuildState
    data class Succeeded(val conversations: Int) : SearchIndexRebuildState
    data class Failed(val message: String) : SearchIndexRebuildState
}

/**
 * 进程级单例：`RikkaHubApp` 在换库后的启动期重建 FTS 时写入，根 UI 的横幅与备份页状态行读取。
 *
 * 只做状态展示，不承担重试语义：失败时磁盘标记 `fts_rebuild_pending` 仍保留，
 * 下次冷启动/手动重试会再跑一遍（`RikkaHubApp.rebuildSearchIndexIfPending` 幂等）。
 */
object SearchIndexRebuildSignal {
    private val _state = MutableStateFlow<SearchIndexRebuildState>(SearchIndexRebuildState.Idle)
    val state: StateFlow<SearchIndexRebuildState> = _state.asStateFlow()

    internal fun started(total: Int) {
        _state.value = SearchIndexRebuildState.Running(0, total)
    }

    internal fun progress(done: Int, total: Int) {
        _state.value = SearchIndexRebuildState.Running(done, total)
    }

    internal fun succeeded(conversations: Int) {
        _state.value = SearchIndexRebuildState.Succeeded(conversations)
    }

    internal fun failed(message: String) {
        _state.value = SearchIndexRebuildState.Failed(message)
    }

    /** 手动重试前先回到初始态，避免旧失败横幅残留。 */
    fun reset() {
        _state.value = SearchIndexRebuildState.Idle
    }
}
