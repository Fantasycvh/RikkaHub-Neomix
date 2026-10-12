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
import app.androidrik.rikkahub.data.export.ExportFileEntry
import app.androidrik.rikkahub.data.export.attachmentBasename
import app.androidrik.rikkahub.data.export.matchesContent
import app.androidrik.rikkahub.data.export.rewriteAttachmentUrls
import app.androidrik.rikkahub.data.export.unpackConversationArchive
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.pages.backup.mergeProviders
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val s3Sync: S3Sync,
    private val conversationRepository: ConversationRepository,
    private val filesManager: FilesManager,
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
     * 见 `docs/design/design-conversation-import-export.md` §十。
     *
     * **在 IO 线程执行**（读文件 + 解 zip + 解码 JSON 都是重活；调用方是 Compose 的
     * `rememberCoroutineScope()`，不切线程会卡主线程 → ANR）。
     *
     * @return 解析出的会话（**id/助手均未替换**）与其来源信息；解析失败返回 null。
     */
    suspend fun parseConversationImport(file: File): ConversationImportPreview? =
        withContext(Dispatchers.IO) {
            runCatching {
                // 阶段 2 起容器是 zip；**仍接受阶段 1 的裸 JSON**（向后兼容：用户手上已有的文件）。
                // 靠魔数判别而非扩展名（SAF 返回的 URI 未必带正确扩展名）。
                val raw = file.readBytes()
                val exportData: ExportData
                val attachments: Map<String, ByteArray>
                if (isZip(raw)) {
                    val unpacked = unpackConversationArchive(raw) ?: return@runCatching null
                    exportData = JsonInstant.decodeFromString(ExportData.serializer(), unpacked.json)
                    attachments = unpacked.attachments
                } else {
                    exportData = JsonInstant.decodeFromString(
                        ExportData.serializer(),
                        raw.toString(Charsets.UTF_8),
                    )
                    attachments = emptyMap()
                }
                if (exportData.type != ConversationSerializer.type) return@runCatching null
                val conversation = JsonInstant.decodeFromJsonElement(
                    Conversation.serializer(),
                    exportData.data,
                )
                ConversationImportPreview(
                    conversation = conversation,
                    sourceId = exportData.sourceId,
                    manifest = exportData.files,
                    attachments = attachments,
                )
            }.getOrNull()
        }

    /** zip 魔数（`PK`）。用魔数而非扩展名：SAF 给的 URI 未必带正确扩展名。 */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

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
    ): ConversationImportResult {
        // ① 物化附件：清单 → files/upload/<新 uuid>，并建 `originalUrl → 新 file://` 映射。
        //    **必须走 FilesManager.saveManagedFromBytes**：它同时登记 managed_files，
        //    否则文件管理页会把 UUID 当显示名（设计稿 §六 阶段 2）。
        //    生成新文件名（真拷贝）而非复用原路径：`deleteConversation` 会按 part URL 删盘，
        //    复用会"删原会话连带删掉导入副本的附件"（约束 4）。
        val mapping = LinkedHashMap<String, String>()
        var missingAttachments = 0
        preview.manifest.forEach { entry ->
            val bytes = preview.attachments[entry.entry]
            if (bytes == null || !entry.matchesContent(bytes)) {
                // 条目缺失或内容不符（归档损坏）→ 跳过并计数；该 URL 不改写（仍指向原处）
                missingAttachments++
                Log.w(TAG, "commitConversationImport: attachment unavailable entry=${entry.entry}")
                return@forEach
            }
            val displayName = entry.fileName.ifBlank { attachmentBasename(entry.originalUrl) }
            runCatching {
                val saved = filesManager.saveManagedFromBytes(
                    folder = FileFolders.UPLOAD,
                    bytes = bytes,
                    displayName = displayName,
                    mimeType = entry.mime,
                )
                mapping[entry.originalUrl] = "file://" + filesManager.getFile(saved).absolutePath
            }.onFailure {
                missingAttachments++
                Log.e(TAG, "commitConversationImport: failed to materialize ${entry.entry}", it)
            }
        }

        // ② 改写 part URL（递归，含工具输出嵌套），再换 id —— 顺序无所谓，
        //    但改写必须先于落库（库里不能出现旧路径/伪协议）。
        val rewritten = preview.conversation.rewriteAttachmentUrls(mapping)
        val imported = rewritten.remapForImport(targetAssistantId)
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
            "checkpoints=${imported.compressionSummaries.size} sourceId=${preview.sourceId} " +
            "attachments=${mapping.size} missing=$missingAttachments")
        return ConversationImportResult(
            conversation = imported,
            restoredAttachments = mapping.size,
            missingAttachments = missingAttachments,
        )
    }

    /**
     * 导入 Chatbox 导出（providers + 多个会话）。
     *
     * @param targetAssistantId **用户选择的**目标助手——导入的会话都归属到它。
     *   此前隐式为「当前助手」（内部读 `settings.value.assistantId`），与自家格式导入
     *   「让用户选助手」的做法不一致；见设计稿 §八。
     */
    suspend fun restoreFromChatBox(file: File, targetAssistantId: Uuid): ChatboxRestoreResult {
        var importedConversations = 0
        var skippedExistingConversations = 0
        val result = ChatboxImporter.importStreaming(
            file = file,
            assistantId = targetAssistantId,
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

        // 注意：`allowConversationSystemPrompt` 必须打在**用户选的目标助手**上——
        // 原先用 settings.value.assistantId（当前助手），会出现"导入到 A、却改了 B 的配置"。
        val current = settingsStore.settingsFlowRaw.first()
        settingsStore.update(
            current.copy(
                providers = mergeProviders(current.providers, result.providers),
                assistants = current.assistants.map { assistant ->
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
                providers = mergeProviders(settings.value.providers, importProviders),
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
    /** 附件清单（阶段 1 的裸 JSON 为空列表）。 */
    val manifest: List<ExportFileEntry> = emptyList(),
    /** zip 内解出的附件字节（key = 条目名）。裸 JSON 为空。 */
    val attachments: Map<String, ByteArray> = emptyMap(),
)

/** 导入结果：会话 + 附件恢复统计（供 UI 提示"有 N 个附件缺失"）。 */
data class ConversationImportResult(
    val conversation: Conversation,
    val restoredAttachments: Int,
    val missingAttachments: Int,
)
