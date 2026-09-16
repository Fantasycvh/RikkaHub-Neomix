package app.androidrik.rikkahub.ui.pages.backup

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupProgress
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.WebDavConfig
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.sync.importer.ChatboxImporter
import app.androidrik.rikkahub.data.sync.importer.CherryStudioProviderImporter
import app.androidrik.rikkahub.data.sync.webdav.WebDavBackupItem
import app.androidrik.rikkahub.data.sync.webdav.WebDavSync
import app.androidrik.rikkahub.data.sync.S3BackupItem
import app.androidrik.rikkahub.data.sync.S3Sync
import app.androidrik.rikkahub.data.export.ConversationSerializer
import app.androidrik.rikkahub.data.export.ExportData
import app.androidrik.rikkahub.data.export.remapForImport
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val s3BackupItems = MutableStateFlow<UiState<List<S3BackupItem>>>(UiState.Idle)

    /**
     * 当前备份/恢复的进度（null = 无进行中任务）。UI 收集用于显示百分比。
     *
     * 用 StateFlow 而非在引擎上挂状态：引擎被两个 singleton 各持一份，挂共享状态有并发串扰风险；
     * 这里由 VM 单点接收回调（回调在 IO 线程，StateFlow 本身线程安全）。
     */
    val progress = MutableStateFlow<BackupProgress?>(null)

    private fun reportProgress(p: BackupProgress) {
        progress.value = p
    }

    private fun clearProgress() {
        progress.value = null
    }

    init {
        loadBackupFileItems()
        loadS3BackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webDavSync.listBackupFiles(
                            config = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webDavSync.testConnection(settings.value.webDavConfig)
    }

    suspend fun backup() {
        try {
            webDavSync.backup(settings.value.webDavConfig, ::reportProgress)
            recordBackupTime()
        } finally {
            clearProgress()
        }
    }

    suspend fun restore(item: WebDavBackupItem) {
        try {
            webDavSync.restore(config = settings.value.webDavConfig, item = item, onProgress = ::reportProgress)
        } finally {
            clearProgress()
        }
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webDavSync.deleteBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun exportToFile(): File {
        return try {
            val file = webDavSync.prepareBackupFile(settings.value.webDavConfig, ::reportProgress)
            recordBackupTime()
            file
        } finally {
            clearProgress()
        }
    }

    suspend fun restoreFromLocalFile(file: File) {
        try {
            webDavSync.restoreFromLocalFile(file, settings.value.webDavConfig, ::reportProgress)
        } finally {
            clearProgress()
        }
    }

    /** 查某来源是否导入过（重复导入提示用）。未导入过返回 null。 */
    suspend fun findImportedSource(sourceId: String): ImportSourceRecord? =
        settingsStore.settingsFlowRaw.first().importedSources[sourceId]

    /**
     * 解析一个「对话 JSON」导出文件（**不落库**）。
     *
     * 分两步是为了**重复导入检测**：先解析拿到 `sourceId`，与 `settings.importedSources` 比对，
     * 命中则由 UI 弹确认；用户确认后才调 [commitConversationImport]。
     * 见 `docs/design-conversation-import-export.md` §十。
     *
     * @return 解析出的会话（**id/助手均未替换**）与其来源信息；解析失败返回 null。
     */
    fun parseConversationImport(file: File): ConversationImportPreview? = runCatching {
        val exportData = JsonInstant.decodeFromString(
            ExportData.serializer(),
            file.readText(),
        )
        if (exportData.type != ConversationSerializer.type) return null
        val conversation = JsonInstant.decodeFromJsonElement(
            Conversation.serializer(),
            exportData.data,
        )
        ConversationImportPreview(
            conversation = conversation,
            sourceId = exportData.sourceId,
        )
    }.getOrNull()

    /**
     * 落库一个已解析的导入预览（**换新 id + 目标助手 + 检查点迁移 + 记录来源**）。
     *
     * 顺序关键（设计稿 §三）：**先换 id，再检查存在性/插入**——否则可能因旧 id 命中而被跳过，
     * 让"换 id"白做。此处换 id 后 id 必然是新的，故 `existsConversationById` 恒为 false。
     *
     * @param targetAssistantId 用户选择的目标助手。
     */
    suspend fun commitConversationImport(
        preview: ConversationImportPreview,
        targetAssistantId: Uuid,
    ): Conversation {
        val imported = preview.conversation.remapForImport(targetAssistantId)
        conversationRepository.insertConversation(imported)
        // 压缩检查点：独立表，须显式写入（insertConversation 不处理检查点）
        imported.compressionSummaries.forEach { summary ->
            conversationRepository.addCompressionCheckpoint(summary)
        }
        // 记录来源（**成功之后**才记：取消/失败不该留下记录，否则下次会误报"已导入过"）
        preview.sourceId?.let { sourceId ->
            val current = settingsStore.settingsFlowRaw.first()
            settingsStore.update(
                current.copy(
                    importedSources = current.importedSources +
                        (sourceId to ImportSourceRecord(
                            importedAt = System.currentTimeMillis(),
                            title = imported.title.ifBlank { null },
                        )),
                )
            )
        }
        Log.i(TAG, "commitConversationImport: id=${imported.id} nodes=${imported.messageNodes.size} " +
            "checkpoints=${imported.compressionSummaries.size} sourceId=${preview.sourceId}")
        return imported
    }

    suspend fun restoreFromChatBox(file: File): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = settings.value.assistantId,
            providers = settings.value.providers,
            onConversation = { conversation ->
                if (conversationRepository.existsConversationById(conversation.id)) {
                    skippedExistingConversations++
                } else {
                    conversationRepository.insertConversation(conversation)
                    importedConversations++
                }
            }
        )

        val targetAssistantId = settings.value.assistantId
        settingsStore.update(
            settings.value.copy(
                providers = result.providers + settings.value.providers,
                assistants = settings.value.assistants.map { assistant ->
                    if (result.hasConversationSystemPrompt && assistant.id == targetAssistantId) {
                        assistant.copy(allowConversationSystemPrompt = true)
                    } else {
                        assistant
                    }
                }
            )
        )

        Log.i(
            TAG,
            "restoreFromChatBox: import ${result.providers.size} providers, " +
                "$importedConversations conversations, skip $skippedExistingConversations existing, " +
                "drop ${result.skippedImageParts} images"
        )
        return ChatboxRestoreResult(
            importedProviders = result.providers.size,
            importedConversations = importedConversations,
            skippedExistingConversations = skippedExistingConversations,
            skippedImageParts = result.skippedImageParts,
            skippedEmptyMessages = result.skippedEmptyMessages,
        )
    }

    fun restoreFromCherryStudio(file: File) {
        val importProviders = CherryStudioProviderImporter.importProviders(file)

        if (importProviders.isEmpty()) {
            throw IllegalArgumentException("No importable providers found in Cherry Studio backup")
        }

        Log.i(TAG, "restoreFromCherryStudio: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }

    // S3 Backup methods
    fun loadS3BackupFileItems() {
        viewModelScope.launch {
            runCatching {
                s3BackupItems.emit(UiState.Loading)
                s3BackupItems.emit(
                    value = UiState.Success(
                        data = s3Sync.listBackupFiles(
                            config = settings.value.s3Config
                        )
                    )
                )
            }.onFailure {
                s3BackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testS3() {
        s3Sync.testS3(settings.value.s3Config)
    }

    suspend fun backupToS3() {
        try {
            s3Sync.backupToS3(settings.value.s3Config, ::reportProgress)
            recordBackupTime()
        } finally {
            clearProgress()
        }
    }

    suspend fun restoreFromS3(item: S3BackupItem) {
        try {
            s3Sync.restoreFromS3(config = settings.value.s3Config, item = item, onProgress = ::reportProgress)
        } finally {
            clearProgress()
        }
    }

    suspend fun deleteS3BackupFile(item: S3BackupItem) {
        s3Sync.deleteS3BackupFile(settings.value.s3Config, item)
    }

    private suspend fun recordBackupTime() {
        settingsStore.update { settings ->
            settings.copy(
                backupReminderConfig = settings.backupReminderConfig.copy(
                    lastBackupTime = System.currentTimeMillis()
                )
            )
        }
    }
}

data class ChatboxRestoreResult(
    val importedProviders: Int,
    val importedConversations: Int,
    val skippedExistingConversations: Int,
    val skippedImageParts: Int,
    val skippedEmptyMessages: Int,
)

/**
 * 「对话 JSON」导入的预览（解析结果，尚未落库）。
 *
 * @param conversation 解析出的会话（id/助手仍是文件里的原值）
 * @param sourceId 导出时的会话 id（**重复导入检测的锚点**）；旧版文件为 null → 跳过检测
 */
data class ConversationImportPreview(
    val conversation: Conversation,
    val sourceId: String?,
)
