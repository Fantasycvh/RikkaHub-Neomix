package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import android.util.Log
import java.io.File
import org.koin.java.KoinJavaComponent.getKoin
import app.androidrik.rikkahub.data.export.AttachmentPlan
import app.androidrik.rikkahub.data.export.ConversationSerializer
import app.androidrik.rikkahub.data.export.packConversationArchive
import app.androidrik.rikkahub.data.export.planAttachments
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Conversation

private const val TAG = "ChatExport"

/**
 * 导出为**可导入的自家归档**（zip：`conversation.json` + `attachments/`）。
 *
 * 与 Markdown/HTML/PNG 不同：那三种只出不能回填。**始终 zip**（阶段 2 决策——本功能未发布，
 * 无裸 JSON 的历史包袱），见 `docs/design/design-conversation-import-export.md` §六 阶段 2。
 *
 * 落点 = **留存区 `files/exports`**（原子写入），随后由调用方分享或另存。
 * 文件名清洗非法字符（用字符列表而非正则，避免转义歧义）。
 *
 * 缺失附件**跳过并计数**（导出照常成功）——挡死导出会让人连文字部分都导不出。
 */
internal suspend fun exportToConversationArchive(
    context: Context,
    conversation: Conversation,
): ExportedFile {
    val packed = packConversationArchive(context, conversation, ConversationSerializer.export(conversation))
    if (packed.missingUrls.isNotEmpty() || packed.oversizedUrls.isNotEmpty()) {
        Log.i(
            TAG,
            "exportConversationArchive: skipped ${packed.missingUrls.size} missing, " +
                "${packed.oversizedUrls.size} oversized attachment(s)",
        )
    }
    return writeExportBytes(
        displayName = "${sanitizeExportFileName(conversation.title)}.zip",
        bytes = packed.bytes,
        mimeType = "application/zip",
    )
}

/** 供 UI 在打包前决定是否提示软上限（只 stat，不读内容；内部切 IO 线程）。 */
internal suspend fun conversationAttachmentPlan(
    context: Context,
    conversation: Conversation,
): AttachmentPlan = planAttachments(context, conversation)

/** 文件系统非法字符（导出文件名清洗用）。 */
private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/** 清洗导出文件名（去非法字符、限长；空标题回退 `conversation`）。 */
internal fun sanitizeExportFileName(title: String): String = title
    .ifBlank { "conversation" }
    .map { ch -> if (ch in ILLEGAL_FILENAME_CHARS || ch.code < 0x20) '_' else ch }
    .joinToString("")
    .trim()
    .take(60)
    .ifBlank { "conversation" }

/** 一次导出在留存区的落点。 */
internal data class ExportedFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
)

/**
 * 把导出内容**原子地写进留存区**（`files/exports`）。
 *
 * **为什么不是 `cache/temp`**：那里由 [app.androidrik.rikkahub.RikkaHubApp.deleteTempFiles] 在每次冷启动整目录清空，
 * 留存物活不过一次重启——而「先导出再继续」的语义是"留一份"，必须跨重启。
 *
 * **为什么不用 `saveManagedFromBytes`**：后者直接写最终名；导出会复用同名（JSON 按标题命名），
 * "先删旧再写新"在中途失败时会毁掉前一份。[FilesManager.saveExportAtomically] 改为
 * "写临时文件 + rename"，故最终名始终完好。见 `docs/design/design-export-retention.md` §4.2。
 */
internal suspend fun writeExportBytes(
    displayName: String,
    bytes: ByteArray,
    mimeType: String,
): ExportedFile {
    val filesManager = getKoin().get<FilesManager>()
    val entity = filesManager.saveExportAtomically(
        bytes = bytes,
        displayName = displayName,
        mimeType = mimeType,
    )
    return ExportedFile(
        file = filesManager.getFile(entity),
        displayName = displayName,
        mimeType = mimeType,
    )
}
