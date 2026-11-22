package app.androidrik.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.ConversationTitleRow
import app.androidrik.rikkahub.data.files.FileConversationScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 文件管理页的会话关联状态（design-file-conversation-link.md Phase A）。
 *
 * ready=false 表示扫描尚未完成（UI 不显示会话行）；refs 键为
 * `managed_files.relative_path`，值为引用该文件的会话 id 集（含所有分支）。
 */
data class FileConversationRefs(
    val ready: Boolean,
    val refs: Map<String, Set<String>>,
    val titles: Map<String, ConversationTitleRow>,
)

class SettingFilesVM(
    private val scanner: FileConversationScanner,
    private val conversationDAO: ConversationDAO,
) : ViewModel() {

    private val _refs = MutableStateFlow(FileConversationRefs(false, emptyMap(), emptyMap()))
    val refs: StateFlow<FileConversationRefs> = _refs.asStateFlow()

    private var scanJob: Job? = null

    fun ensureScanned() {
        if (scanJob?.isActive == true || _refs.value.ready) return
        scanJob = viewModelScope.launch {
            runCatching { scanner.scan() }
                .onSuccess { map ->
                    val ids = map.values.flatten().distinct()
                    val titles = if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        conversationDAO.getTitleRowsByIds(ids).associateBy { it.id }
                    }
                    _refs.value = FileConversationRefs(ready = true, refs = map, titles = titles)
                }
                .onFailure {
                    // 扫描失败不阻塞页面：只是不显示会话行（与未关联同呈现）
                    _refs.value = FileConversationRefs(ready = true, refs = emptyMap(), titles = emptyMap())
                }
        }
    }
}
