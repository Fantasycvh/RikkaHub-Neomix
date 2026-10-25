package app.androidrik.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.storage.ClearResult
import app.androidrik.rikkahub.data.storage.StorageCategory
import app.androidrik.rikkahub.data.storage.StorageCategoryId
import app.androidrik.rikkahub.data.storage.StorageDetail
import app.androidrik.rikkahub.data.storage.StorageUsageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUiState(
    val categories: List<StorageCategory> = emptyList(),
    val loading: Boolean = true,
    val clearingId: StorageCategoryId? = null,
    val error: String? = null,
    val expanded: Set<StorageCategoryId> = emptySet(),
    val details: Map<StorageCategoryId, List<StorageDetail>> = emptyMap(),
    val detailsLoading: StorageCategoryId? = null,
    /** 非空 = 备份/恢复正在进行，清理需禁用。清理自身不算阻塞。 */
    val blockingOperation: DataOperation? = null,
) {
    val totalBytes: Long get() = categories.sumOf { it.sizeBytes }
    val busy: Boolean get() = loading || clearingId != null
    val blocked: Boolean get() = blockingOperation != null
}

sealed interface StorageEvent {
    data class Cleared(val result: ClearResult) : StorageEvent
    data object Busy : StorageEvent
}

class StorageVM(
    private val manager: StorageUsageManager,
) : ViewModel() {

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<StorageEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<StorageEvent> = _events.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            DataOperationLock.active.collect { operation ->
                _state.update {
                    it.copy(
                        blockingOperation = operation?.takeIf { op -> op != DataOperation.STORAGE_CLEANUP },
                    )
                }
            }
        }
    }

    fun refresh() {
        if (_state.value.clearingId != null) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val categories = manager.scan()
                val details = loadDetailsFor(_state.value.expanded)
                _state.update {
                    it.copy(
                        categories = categories,
                        details = it.details + details,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(loading = false, error = failure.message ?: failure.javaClass.simpleName)
                }
            }
        }
    }

    fun toggleExpand(id: StorageCategoryId) {
        val category = _state.value.categories.firstOrNull { it.id == id } ?: return
        if (!category.canExpand) return

        if (id in _state.value.expanded) {
            _state.update { it.copy(expanded = it.expanded - id) }
            return
        }

        _state.update { it.copy(expanded = it.expanded + id, detailsLoading = id) }
        viewModelScope.launch {
            val items = try {
                manager.details(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            _state.update {
                it.copy(
                    details = it.details + (id to items),
                    detailsLoading = if (it.detailsLoading == id) null else it.detailsLoading,
                )
            }
        }
    }

    fun clear(id: StorageCategoryId) {
        val snapshot = _state.value
        if (snapshot.busy) return
        if (snapshot.blocked) {
            viewModelScope.launch { _events.emit(StorageEvent.Busy) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(clearingId = id, error = null) }
            val result = try {
                DataOperationLock.runImmediate(DataOperation.STORAGE_CLEANUP) { manager.clear(id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        clearingId = null,
                        error = failure.message ?: failure.javaClass.simpleName,
                    )
                }
                return@launch
            }

            if (result == null) {
                _state.update { it.copy(clearingId = null) }
                _events.emit(StorageEvent.Busy)
                return@launch
            }

            _state.update { it.copy(clearingId = null) }
            try {
                val categories = manager.scan()
                val details = loadDetailsFor(_state.value.expanded)
                _state.update { it.copy(categories = categories, details = it.details + details) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 清理已成功；重扫失败下次刷新会补上，不覆盖清理结果
            }
            _events.emit(StorageEvent.Cleared(result))
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun loadDetailsFor(
        ids: Set<StorageCategoryId>,
    ): Map<StorageCategoryId, List<StorageDetail>> =
        ids.filter { it.canExpand }.associateWith { manager.details(it) }
}
