package app.androidrik.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.ConversationTitleRow
import app.androidrik.rikkahub.data.db.dao.MessageFileRefDAO
import app.androidrik.rikkahub.data.db.entity.MessageFileRefEntity
import app.androidrik.rikkahub.data.files.FileConversationScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 文件管理页的会话关联状态（design-file-conversation-link.md Phase B）。
 *
 * 数据源 = `message_file_ref` 关联表（写路径在 ConversationRepository 收口，新附件
 * 保存后即时可见）；扫描器降级为**兜底**：关联表空且从没兜底过时扫一遍并把结果
 * 直接灌库（覆盖回填 Job 未跑/失败、恢复换库漏标记等缺口），observeAll 会再发射。
 * refs 键为 `managed_files.relative_path`，值为引用该文件的会话 id 集（含所有分支）。
 */
data class FileConversationRefs(
    val ready: Boolean,
    val refs: Map<String, Set<String>>,
    val titles: Map<String, ConversationTitleRow>,
)

class SettingFilesVM(
    private val scanner: FileConversationScanner,
    private val messageFileRefDAO: MessageFileRefDAO,
    private val conversationDAO: ConversationDAO,
) : ViewModel() {

    private val _refs = MutableStateFlow(FileConversationRefs(false, emptyMap(), emptyMap()))
    val refs: StateFlow<FileConversationRefs> = _refs.asStateFlow()

    private var fallbackAttempted = false

    init {
        viewModelScope.launch {
            messageFileRefDAO.observeAll().collect { rows ->
                if (rows.isEmpty()) {
                    if (!fallbackAttempted) {
                        fallbackAttempted = true
                        val scanned = runCatching { scanner.scan() }.getOrDefault(emptyMap())
                        if (scanned.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            messageFileRefDAO.insertAll(
                                scanned.flatMap { (path, ids) ->
                                    ids.map { MessageFileRefEntity(conversationId = it, relativePath = path, firstSeenAt = now) }
                                }
                            )
                            return@collect
                        }
                    }
                    // 关联表确实为空（无附件引用/扫描也空）：ready 收口，UI 不显示会话行
                    _refs.value = FileConversationRefs(true, emptyMap(), emptyMap())
                    return@collect
                }
                val byPath = rows.groupBy({ it.relativePath }, { it.conversationId })
                    .mapValues { (_, ids) -> ids.toSet() }
                val ids = rows.map { it.conversationId }.distinct()
                val titles = conversationDAO.getTitleRowsByIds(ids).associateBy { it.id }
                _refs.value = FileConversationRefs(ready = true, refs = byPath, titles = titles)
            }
        }
    }
}
