package app.androidrik.rikkahub.data.export

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "ConversationArchive"

/** zip 内主 JSON 的条目名。 */
const val CONVERSATION_JSON_ENTRY: String = "conversation.json"

/** 附件合计体积软上限：超过则提示确认（**不硬拒**——见设计稿 §六 阶段 2 约束 7）。 */
const val ATTACHMENTS_SOFT_LIMIT_BYTES: Long = 100L * 1024 * 1024

/** 单个附件体积上限：超过则跳过（防止一次导出产出一个巨大的 zip，SAF 分享体验崩坏）。 */
const val ATTACHMENT_MAX_BYTES: Long = 50L * 1024 * 1024

/**
 * 附件的**预算结果**（只 stat 不读内容 —— 软上限确认要在打包前做，避免白读一遍大文件）。
 *
 * ⚠️ [planAttachments] 与 [packConversationArchive] **都在 IO 线程执行**：它们做文件 stat/读取
 * 与 SHA-256（大附件下是重活）。调用方多在 Compose 的 `rememberCoroutineScope()`（Main）里，
 * 若在这两个函数内部不切线程就会把主线程卡住 → **ANR**（本实现首次真机验证时即因此 ANR）。
 *
 * @param totalBytes 可打包附件的合计字节（已扣除缺失与超限项）
 * @param missingUrls 引用存在但读不到的 URL
 * @param oversizedUrls 超过 [ATTACHMENT_MAX_BYTES] 而会被跳过的 URL
 * @param count 实际会进 zip 的附件数（去重前；去重发生在打包时）
 */
data class AttachmentPlan(
    val totalBytes: Long,
    val missingUrls: List<String>,
    val oversizedUrls: List<String>,
    val count: Int,
) {
    /** 合计体积是否超过软上限（→ 调用方提示确认，**不硬拒**）。 */
    val exceedsSoftLimit: Boolean get() = totalBytes > ATTACHMENTS_SOFT_LIMIT_BYTES
}

/**
 * 预算附件（**只 stat，不读内容**）。
 *
 * 用于在打包前决定是否提示软上限：读一遍大文件才发现超限是浪费。
 */
suspend fun planAttachments(context: Context, conversation: Conversation): AttachmentPlan =
    withContext(Dispatchers.IO) {
    var total = 0L
    var count = 0
    val missing = mutableListOf<String>()
    val oversized = mutableListOf<String>()
    conversation.collectAttachmentUrls().forEach { url ->
        val file = resolveAttachmentFile(context, url)
        if (file == null) {
            missing += url
        } else if (file.length() > ATTACHMENT_MAX_BYTES) {
            oversized += url
        } else {
            total += file.length()
            count++
        }
    }
    AttachmentPlan(
        totalBytes = total,
        missingUrls = missing,
        oversizedUrls = oversized,
        count = count,
    )
}

/** 打包结果：zip 字节 + 清单 + 缺失/超限的跳过统计。 */
data class ArchivePackResult(
    val bytes: ByteArray,
    val manifest: List<ExportFileEntry>,
    /** 引用存在但读不到（已被删、或旧包名且同名文件也不在）的 URL。 */
    val missingUrls: List<String>,
    /** 体积超过 [ATTACHMENT_MAX_BYTES] 而跳过的 URL。 */
    val oversizedUrls: List<String>,
    /** zip 内附件合计字节（用于软上限提示）。 */
    val attachmentsBytes: Long,
)

/** 解压结果：清单里的附件字节（key = zip 条目名）+ 主 JSON 文本。 */
data class ArchiveUnpackResult(
    val json: String,
    val attachments: Map<String, ByteArray>,
    /** 清单里有、但 zip 里没有（或损坏）的条目名。 */
    val missingEntries: List<String>,
)

/**
 * 把会话打包成 zip（`conversation.json` + `attachments/<sha256 前 16>.<ext>`）。
 *
 * **始终 zip**（阶段 2 决策：本功能未发布，无裸 JSON 的历史包袱）。
 *
 * 附件读取用 [normalizeLocalFileUrl] 归一化 URL——存量数据的 part URL 大量写成**旧包名**
 * `file:///data/user/0/me.rerere.rikkahub/files/upload/...`（磁盘上不存在），只能靠取
 * basename 重解析找到真实文件；不归一化会把大量附件误判为"缺失"（设计稿 §六 阶段 2 约束 5）。
 *
 * 去重范围 = **单次导出内**（同 sha256 只存一份条目，但清单里每个 URL 各有一条记录）——
 * **不做跨会话全局去重**，那会重新引入"删一个会话删掉共用文件"的危险（约束 8）。
 *
 * 缺失/超限的附件**跳过并计数**，导出照常成功（约束 6）。
 */
suspend fun packConversationArchive(
    context: Context,
    conversation: Conversation,
    exportData: ExportData,
): ArchivePackResult = withContext(Dispatchers.IO) {
    val urls = conversation.collectAttachmentUrls()
    val manifest = mutableListOf<ExportFileEntry>()
    val missing = mutableListOf<String>()
    val oversized = mutableListOf<String>()
    // 内容哈希 → zip 条目名，实现单次导出内去重
    val entryBySha = LinkedHashMap<String, ByteArray>()
    var attachmentBytes = 0L

    urls.forEach { url ->
        val bytes = readAttachmentBytes(context, url)
        if (bytes == null) {
            missing += url
            return@forEach
        }
        if (bytes.size.toLong() > ATTACHMENT_MAX_BYTES) {
            oversized += url
            return@forEach
        }
        val sha = sha256Of(bytes)
        val fileName = attachmentBasename(url)
        val mime = guessMimeFromFileName(fileName)
        val ext = guessExtension(url, fileName, mime)
        val entry = attachmentEntryName(sha, ext)
        if (entryBySha.putIfAbsent(entry, bytes) == null) {
            attachmentBytes += bytes.size
        }
        manifest += ExportFileEntry(
            originalUrl = url,
            entry = entry,
            fileName = fileName,
            mime = mime,
            size = bytes.size.toLong(),
            sha256 = sha,
        )
    }

    val json = JsonInstant.encodeToString(
        ExportData.serializer(),
        exportData.copy(version = 2, files = manifest),
    )

    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry(CONVERSATION_JSON_ENTRY))
        zip.write(json.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        entryBySha.forEach { (entry, bytes) ->
            zip.putNextEntry(ZipEntry(entry))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    Log.i(
        TAG,
        "packConversationArchive: urls=${urls.size} entries=${entryBySha.size} " +
            "missing=${missing.size} oversized=${oversized.size} bytes=${out.size()}",
    )
    ArchivePackResult(
        bytes = out.toByteArray(),
        manifest = manifest,
        missingUrls = missing,
        oversizedUrls = oversized,
        attachmentsBytes = attachmentBytes,
    )
}

/**
 * 解压 zip，取出主 JSON 与附件字节。
 *
 * **zip-slip 防护**：只接受 [CONVERSATION_JSON_ENTRY] 与 [ATTACHMENTS_PREFIX] 下的条目，
 * 且条目名不得含 `..`——我们从不落盘到用户指定的路径（附件走 [FilesManager] 生成 UUID 名），
 * 但仍按最小暴露面处理（对齐 `BackupArchiveEngine` 的判定手法）。
 *
 * @return null = 不是有效 zip / 缺主 JSON（调用方据此报"无效文件"）。
 */
fun unpackConversationArchive(bytes: ByteArray): ArchiveUnpackResult? {
    var json: String? = null
    val attachments = LinkedHashMap<String, ByteArray>()
    try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == CONVERSATION_JSON_ENTRY ->
                        json = zip.readBytes().toString(Charsets.UTF_8)

                    name.startsWith(ATTACHMENTS_PREFIX) && isSafeEntryName(name) ->
                        attachments[name] = zip.readBytes()

                    else -> Unit  // 未知条目：忽略（前向兼容，不报错）
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (t: Throwable) {
        // Throwable：损坏的 zip 可能抛 Error 系（与项目内其它解析路径一致的兜底口径）
        Log.w(TAG, "unpackConversationArchive: failed to read zip", t)
        return null
    }
    return json?.let { ArchiveUnpackResult(json = it, attachments = attachments, missingEntries = emptyList()) }
}

/** 拒绝 `..`、绝对路径、空段（zip-slip 的基本形态）。 */
private fun isSafeEntryName(name: String): Boolean {
    if (name.startsWith('/')) return false
    val relative = name.removePrefix(ATTACHMENTS_PREFIX)
    if (relative.isBlank()) return false
    return relative.split('/').none { it.isBlank() || it == ".." || it == "." }
}

/**
 * 按归一化后的 URL 定位附件文件；找不到返回 null（= 缺失，跳过并计数）。
 *
 * 用 [normalizeLocalFileUrl] 归一化：把旧包名/绝对路径重写到**当前包** `files/upload/<name>`，
 * 从而既兼容跨包迁移的存量数据，也仍然读不到时明确判为缺失（设计稿 §六 阶段 2 约束 5）。
 */
private fun resolveAttachmentFile(context: Context, url: String): File? {
    val normalized = runCatching { context.normalizeLocalFileUrl(url) }.getOrNull() ?: return null
    val path = normalized.removePrefix("file://").takeIf { it.isNotBlank() } ?: return null
    return File(path).takeIf { it.isFile }
}

/** 读取附件字节（读失败按缺失处理）。 */
private fun readAttachmentBytes(context: Context, url: String): ByteArray? {
    val file = resolveAttachmentFile(context, url) ?: return null
    return runCatching { file.readBytes() }.getOrNull()
}

/** 由文件名推测 MIME（内置常见映射；未知回退 `application/octet-stream`）。 */
private fun guessMimeFromFileName(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return EXTENSION_MIME[ext] ?: "application/octet-stream"
}

private val EXTENSION_MIME: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "svg" to "image/svg+xml",
    "mp4" to "video/mp4",
    "webm" to "video/webm",
    "mov" to "video/quicktime",
    "mp3" to "audio/mpeg",
    "m4a" to "audio/mp4",
    "wav" to "audio/wav",
    "ogg" to "audio/ogg",
    "pdf" to "application/pdf",
    "json" to "application/json",
    "zip" to "application/zip",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "html" to "text/html",
    "csv" to "text/csv",
)
