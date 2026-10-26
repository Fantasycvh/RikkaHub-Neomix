package app.androidrik.rikkahub.ui.pages.backup.tabs

import android.net.Uri
import app.androidrik.rikkahub.ui.pages.backup.ConversationImportPreview
import app.androidrik.rikkahub.ui.pages.backup.ImportFormat
import app.androidrik.rikkahub.ui.pages.backup.ImportFormatSpec
import app.androidrik.rikkahub.ui.pages.backup.ImportFormatSpecs
import app.androidrik.rikkahub.ui.pages.backup.specOf
import app.androidrik.rikkahub.ui.pages.backup.components.ConversationImportConfirmDialog
import app.androidrik.rikkahub.ui.pages.backup.components.DuplicateImportRecord
import app.androidrik.rikkahub.ui.pages.backup.components.ImportTargetAssistantDialog
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreConfirmDialog
import app.androidrik.rikkahub.ui.pages.backup.components.BackupProgressRow
import app.androidrik.rikkahub.ui.pages.backup.components.BackupRestorePreviewDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.ui.pages.backup.components.BackupItemsSelector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.BackupInspection
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupScope
import app.androidrik.rikkahub.ui.components.ui.StickyHeader
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.backup.BackupVM
import app.androidrik.rikkahub.utils.copyUriToTempFile
import java.io.File
import java.io.FileInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

@Composable
fun ImportExportTab(
    vm: BackupVM,
    onShowRestartDialog: () -> Unit
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // 当前正在进行的导入格式（卡片 spinner 与 SAF 回调分发都用它）。
    // 用类型化的 ImportFormat 取代原先的 `importType: String`（stringly-typed，易拼错且分支散落）
    var pendingFormat by remember { mutableStateOf<ImportFormat?>(null) }
    // 整库恢复：**选文件之前**的覆盖确认（不可逆）
    var pendingLocalImport by remember { mutableStateOf(false) }
    // 「对话归档」导入：解析出的预览（非空 = 待确认）与其重复导入记录
    // 见 docs/design/design-conversation-import-export.md §十
    var conversationImportPreview by remember { mutableStateOf<ConversationImportPreview?>(null) }
    var duplicateImportRecord by remember { mutableStateOf<DuplicateImportRecord?>(null) }
    // Chatbox 导入：已复制到本地的临时文件，等待用户选目标助手
    var pendingChatboxFile by remember { mutableStateOf<File?>(null) }
    // 批次3 恢复预览：已巡检的本地备份（临时文件 + 巡检结果），等待用户在预览卡确认
    var pendingLocalRestore by remember { mutableStateOf<Pair<File, BackupInspection>?>(null) }

    fun toastSuccess() = toaster.show(
        resources.getString(R.string.backup_page_restore_success),
        type = ToastType.Success,
    )

    fun toastFailure(e: Throwable) = toaster.show(
        resources.getString(R.string.backup_page_restore_failed, e.message ?: ""),
        type = ToastType.Error,
    )

    /**
     * 设置里被降级丢弃的条目（原版 3.1.0 的 `chatgpt`/`grok` 提供商、`volcengine` TTS 等）。
     *
     * 这些条目本会让整份 settings 解码失败、恢复直接中止；现在改为逐条丢弃继续导入，
     * 但必须让用户知道"哪些设置没带过来"，否则就是静默丢失。
     */
    fun showSettingsDroppedWarning(warnings: List<String>) {
        if (warnings.isEmpty()) return
        toaster.show(
            resources.getString(
                R.string.backup_page_restore_settings_dropped,
                warnings.joinToString(", "),
            ),
            type = ToastType.Warning,
        )
    }

    /**
     * 导入完成的统一收尾：成功提示 +（按描述符）决定是否提示重启。
     *
     * 之前这段逻辑在四条分支后面各写一遍，且「conversation 提前 return」绕过了它——
     * 现在把"是否要重启"变成描述符的一个字段，语义显式。
     */
    fun finishImport(format: ImportFormat) {
        toastSuccess()
        if (specOf(format).needsRestartDialog) onShowRestartDialog()
    }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val exportFile = vm.exportToFile()
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    exportFile.delete()
                    toaster.show(
                        resources.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toastFailure(e)
                }
                isExporting = false
            }
        }
    }

    /** 分发一次导入（**共享管线**：复制到临时文件 → 按格式处理 → 收尾）。 */
    suspend fun runImport(format: ImportFormat, uri: Uri) {
        val ext = if (format == ImportFormat.CHATBOX) "json" else "zip"
        val tempFile = context.copyUriToTempFile(uri, ext)
        when (format) {
            ImportFormat.LOCAL_BACKUP -> {
                // 批次3 预览（design-backup-manifest-and-safety §3.5）：先巡检（ZipFile 随机
                // 访问，不全量解压）→ 预览卡 → 用户确认后才真正恢复。文件生命周期移交给
                // 预览弹窗（确认后恢复、取消即删）。
                val inspection = try {
                    vm.inspectLocalBackup(tempFile)
                } catch (e: Throwable) {
                    tempFile.delete()
                    throw e
                }
                pendingLocalRestore = tempFile to inspection
            }

            ImportFormat.CHERRY_STUDIO -> {
                try {
                    vm.restoreFromCherryStudio(tempFile)
                } finally {
                    tempFile.delete()
                }
                finishImport(format)
            }

            ImportFormat.CHATBOX -> {
                // 需先选**目标助手**（会话归属）→ 交给对话框，暂不删临时文件
                pendingChatboxFile = tempFile
            }

            ImportFormat.CONVERSATION -> {
                // **先解析、不落库**：需先拿 sourceId 做重复检测、并让用户选目标助手，
                // 确认后才提交（见设计稿 §十）。parse 已把附件读进内存，故可立即删临时文件。
                val preview = runCatching { vm.parseConversationImport(tempFile) }.getOrNull()
                tempFile.delete()
                if (preview == null) {
                    // 「不是有效文件」与「读取失败」要分开报（后者不该被说成格式不对）
                    toaster.show(
                        resources.getString(R.string.backup_page_import_invalid_file),
                        type = ToastType.Error,
                    )
                    return
                }
                duplicateImportRecord = preview.sourceId
                    ?.let { vm.findImportedSource(it) }
                    ?.let { DuplicateImportRecord(title = it.title, importedAt = it.importedAt) }
                conversationImportPreview = preview
                // 尚未落库 → 不提示成功，交由确认弹窗收尾
            }
        }
    }

    /**
     * 目标助手确定后真正执行 Chatbox 导入。
     */
    suspend fun runChatboxImport(file: File, targetAssistantId: Uuid) {
        try {
            val result = vm.restoreFromChatBox(file, targetAssistantId)
            // 导入条数此前被整个丢弃（死返回）——现在如实报出来，用户才知道发生了什么
            toaster.show(
                resources.getString(
                    R.string.backup_page_import_chatbox_done,
                    result.importedConversations.toString(),
                    result.importedProviders.toString(),
                ),
                type = ToastType.Success,
            )
            if (specOf(ImportFormat.CHATBOX).needsRestartDialog) onShowRestartDialog()
        } finally {
            file.delete()
        }
    }

    // 创建文件选择的launcher（**一个** launcher 服务所有格式，按 pendingFormat 分发）
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val format = pendingFormat
        if (uri == null || format == null) return@rememberLauncherForActivityResult
        scope.launch {
            isRestoring = true
            try {
                runImport(format, uri)
            } catch (e: Exception) {
                e.printStackTrace()
                toastFailure(e)
            } finally {
                isRestoring = false
                pendingFormat = null
            }
        }
    }

    fun launchPicker(format: ImportFormat) {
        pendingFormat = format
        openDocumentLauncher.launch(specOf(format).mimes.toTypedArray())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.backup_page_backup_items)) },
                    supportingContent = {
                        BackupItemsSelector(
                            items = settings.backupItems,
                            onItemsChange = { vm.updateSettings(settings.copy(backupItems = it)) },
                        )
                    },
                )
            }
        }

        // 字节级进度：本地导出/导入进行中显示百分比
        if (progress != null) {
            item {
                BackupProgressRow(progress = progress, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            CardGroup {
                item(
                    onClick = if (!isExporting) {
                        {
                            val timestamp = LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            createDocumentLauncher.launch("rikkahub_backup_$timestamp.zip")
                        }
                    } else null,
                    headlineContent = { Text(stringResource(R.string.backup_page_local_backup_export)) },
                    supportingContent = {
                        Text(
                            if (isExporting) {
                                stringResource(R.string.backup_page_exporting)
                            } else {
                                stringResource(R.string.backup_page_export_desc)
                            }
                        )
                    },
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(HugeIcons.File01, null)
                        }
                    },
                )

                // 整库恢复：先弹覆盖确认，确认后才去挑文件
                importItem(
                    spec = specOf(ImportFormat.LOCAL_BACKUP),
                    busy = isRestoring && pendingFormat == ImportFormat.LOCAL_BACKUP,
                    enabled = !isRestoring,
                    onClick = { pendingLocalImport = true },
                )
            }
        }

        item {
            CardGroup {
                importItem(
                    spec = specOf(ImportFormat.CONVERSATION),
                    busy = isRestoring && pendingFormat == ImportFormat.CONVERSATION,
                    enabled = !isRestoring,
                    onClick = { launchPicker(ImportFormat.CONVERSATION) },
                )
            }
        }

        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_import_from_other_app))
            }
        }

        item {
            CardGroup {
                ImportFormatSpecs
                    .filterNot { it.format == ImportFormat.CONVERSATION || it.format == ImportFormat.LOCAL_BACKUP }
                    .forEach { spec ->
                        importItem(
                            spec = spec,
                            busy = isRestoring && pendingFormat == spec.format,
                            enabled = !isRestoring,
                            onClick = { launchPicker(spec.format) },
                        )
                    }
            }
        }
    }

    // Chatbox：目标助手选择（**本轮补上的缺口**——此前隐式导入到当前助手）
    pendingChatboxFile?.let { file ->
        ImportTargetAssistantDialog(
            settings = settings,
            onConfirm = { targetAssistantId ->
                pendingChatboxFile = null
                scope.launch {
                    isRestoring = true
                    try {
                        runChatboxImport(file, targetAssistantId)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toastFailure(e)
                    } finally {
                        isRestoring = false
                    }
                }
            },
            onDismiss = {
                // 取消 → 清掉临时文件（不留垃圾）
                pendingChatboxFile = null
                file.delete()
            },
        )
    }

    // 「对话归档」导入确认：**独立于 pendingLocalImport**（后者只管整库恢复的入口确认）
    conversationImportPreview?.let { preview ->
        ConversationImportConfirmDialog(
            settings = settings,
            conversationTitle = preview.conversation.title,
            duplicateRecord = duplicateImportRecord,
            onConfirm = { targetAssistantId ->
                scope.launch {
                    isRestoring = true
                    runCatching {
                        val result = vm.commitConversationImport(preview, targetAssistantId)
                        // 附件缺失要如实说明（否则用户以为完整导入了）
                        if (result.missingAttachments > 0) {
                            toaster.show(
                                resources.getString(
                                    R.string.backup_page_import_attachment_missing,
                                    result.missingAttachments.toString(),
                                ),
                                type = ToastType.Info,
                            )
                        } else {
                            toastSuccess()
                        }
                    }.onFailure { e -> toastFailure(e) }
                    isRestoring = false
                }
                conversationImportPreview = null
                duplicateImportRecord = null
            },
            onDismiss = {
                conversationImportPreview = null
                duplicateImportRecord = null
            },
        )
    }

    if (pendingLocalImport) {
        RestoreConfirmDialog(
            items = settings.backupItems,
            onConfirm = {
                pendingLocalImport = false
                launchPicker(ImportFormat.LOCAL_BACKUP)
            },
            onDismissRequest = { pendingLocalImport = false },
        )
    }

    // 批次3 恢复预览：覆盖确认与选文件之后，展示这份备份实际包含什么，再确认一次才恢复
    pendingLocalRestore?.let { (file, inspection) ->
        BackupRestorePreviewDialog(
            inspection = inspection,
            onConfirm = {
                pendingLocalRestore = null
                scope.launch {
                    isRestoring = true
                    try {
                        val outcome = vm.restoreFromLocalFile(file)
                        finishImport(ImportFormat.LOCAL_BACKUP)
                        showSettingsDroppedWarning(outcome.settingsWarnings)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        toastFailure(e)
                    } finally {
                        file.delete()
                        isRestoring = false
                    }
                }
            },
            onDismiss = {
                pendingLocalRestore = null
                file.delete()
            },
        )
    }
}

/**
 * 在 [CardGroup] 里挂一张导入卡片：标题/副标题/spinner 全部来自 [ImportFormatSpec]。
 *
 * 抽出来的意义：此前四张卡片各写一遍 stringResource 与
 * `isRestoring && importType == "x"`（字符串字面量比对），新增格式极易漏改。
 *
 * 注意是 **`CardGroupScope` 的扩展**（`CardGroup` 的 content 不是 `@Composable`，
 * 只有 `item {}` 内部的 lambda 是），故不能写成普通 `@Composable`。
 */
private fun CardGroupScope.importItem(
    spec: ImportFormatSpec,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    item(
        onClick = if (enabled) onClick else null,
        headlineContent = { Text(stringResource(spec.titleRes)) },
        supportingContent = {
            Text(
                if (busy) {
                    stringResource(R.string.backup_page_importing)
                } else {
                    stringResource(spec.descRes)
                }
            )
        },
        leadingContent = {
            if (busy) {
                CircularWavyProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(HugeIcons.FileImport, null)
            }
        },
    )
}
