package app.androidrik.rikkahub.data.export

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.serialization.Serializable

/**
 * zip 内一个附件条目的清单记录（与 `conversation.json` 里的 part URL 一一对应）。
 *
 * **为什么不把 part 的 URL 直接改写成 `attachment://…` 伪协议**：导入中途失败时伪协议会
 * 残留在库里成为非法值（`file://`/`data:` 都不是），后续所有读文件的地方都得再兜底一次。
 * 用清单关联则 JSON 始终是被导出的原样，改写只发生在导入的最后一步（见设计稿 §六 阶段 2）。
 *
 * @param originalUrl 导出时该 part 的 URL（**可能是旧包名的 `/files/upload/...` 路径**——
 *   存量数据大量如此，只能靠 basename 归一化找到真实文件）→ 导入侧据此回填。
 * @param entry zip 内条目名，形如 `attachments/<sha256 前 16 位>.<ext>`（内容哈希命名 → 单次导出内天然去重）。
 * @param fileName `Document.fileName` 或 URL basename（导入时作为 `display_name`）。
 * @param mime 推测的 MIME（导入时作为 `managed_files.mime_type`）。
 * @param size 字节数（导入侧可据此校验；也供软上限提示展示）。
 * @param sha256 完整 sha256（**去重键 + 完整性校验**：导入时校验解出的字节，防归档损坏）。
 */
@Serializable
data class ExportFileEntry(
    val originalUrl: String,
    val entry: String,
    val fileName: String,
    val mime: String,
    val size: Long,
    val sha256: String,
)

/** 附件条目在 zip 内的目录前缀（与 `WorkspaceBackupHelper.ZIP_PREFIX` 同款的"前缀分区"手法）。 */
const val ATTACHMENTS_PREFIX: String = "attachments/"

/** 导入时若某条目的 sha256 与清单不符，视为该项不可用（跳过并计数，不中断整个导入）。 */
fun ExportFileEntry.matchesContent(bytes: ByteArray): Boolean =
    sha256Of(bytes) == sha256

/**
 * 计算 sha256（小写 hex）。纯函数、不依赖 Android，便于单测。
 *
 * 与 `Conversation` 内部指纹用的 sha256 不同：**这里要完整的 64 位 hex**（作为内容去重键
 * 与完整性校验），而指纹只需 32 位前缀够用。
 */
fun sha256Of(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

/** 由内容哈希与扩展名生成 zip 条目名（内容相同 → 条目名相同 → 天然去重）。 */
fun attachmentEntryName(sha256: String, extension: String): String {
    val ext = extension.lowercase().takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
    return "$ATTACHMENTS_PREFIX${sha256.take(16)}.${ext ?: "bin"}"
}

/**
 * 从 part 的 URL / Document 文件名推测扩展名（无扩展名时回退 MIME 映射，再回退 `bin`）。
 *
 * 纯函数（内置常见 MIME 映射，不依赖 `MimeTypeMap`），保证单测可复现。
 */
fun guessExtension(url: String, fileName: String?, mime: String?): String {
    // 1) 文件名自带的扩展名（最可信：用户上传时就带着）。
    //    统一小写：来源大小写不可控（`SHOT.PNG`/`Report.PDF` 都常见），
    //    且扩展名会进 zip 条目名与后续匹配，规范化才稳定。
    val fromName = nameOrNull(fileName)?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() && it != nameOrNull(fileName) }
        ?.lowercase()
    if (fromName != null) return fromName

    // 2) URL 的扩展名（注意去掉查询串；同样小写化）
    val fromUrl = url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit) }
        ?.lowercase()
    if (fromUrl != null) return fromUrl

    // 3) MIME 映射
    mime?.lowercase()?.substringBefore(';')?.trim()?.let { m ->
        COMMON_MIME_EXTENSIONS[m]?.let { return it }
    }
    return "bin"
}

private fun nameOrNull(fileName: String?): String? =
    fileName?.takeIf { it.isNotBlank() && it != "blob" }

/** 常见 MIME → 扩展名（覆盖附件实际会出现的类型；未知回退 bin）。 */
private val COMMON_MIME_EXTENSIONS: Map<String, String> = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
    "image/bmp" to "bmp",
    "image/heic" to "heic",
    "image/svg+xml" to "svg",
    "video/mp4" to "mp4",
    "video/webm" to "webm",
    "video/quicktime" to "mov",
    "audio/mpeg" to "mp3",
    "audio/mp3" to "mp3",
    "audio/mp4" to "m4a",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/ogg" to "ogg",
    "application/pdf" to "pdf",
    "application/json" to "json",
    "application/zip" to "zip",
    "text/plain" to "txt",
    "text/markdown" to "md",
    "text/html" to "html",
    "text/csv" to "csv",
)

// ---- URL 收集与改写（纯逻辑，`collectAllParts` 同款递归：工具输出里也能嵌附件） ----

/** 递归深度上限（与 `Conversation.collectAllParts` 一致：恶意深嵌套曾触发 StackOverflowError）。 */
private const val MAX_PARTS_DEPTH = 16

/** 一个 part 引用的本地文件 URL（仅 `file://`；`data:`/`http` 不属附件，原样保留）。 */
private fun UIMessagePart.localFileUrlOrNull(): String? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }
    else -> null
}

/**
 * 收集会话中所有 part（含工具输出嵌套）引用的本地文件 URL。
 *
 * 返回的是**导出时 part 里实际写的 URL**（可能带旧包名）——它是清单的关联键，
 * 故不能先归一化再收集（否则导入侧回填时匹配不上原 URL）。
 */
fun Conversation.collectAttachmentUrls(): List<String> {
    val urls = LinkedHashSet<String>()
    messageNodes.forEach { node ->
        node.messages.forEach { message ->
            collectUrls(message.parts, urls, 0)
        }
    }
    return urls.toList()
}

private fun collectUrls(parts: List<UIMessagePart>, out: MutableSet<String>, depth: Int) {
    if (depth >= MAX_PARTS_DEPTH) return
    parts.forEach { part ->
        part.localFileUrlOrNull()?.let { out.add(it) }
        if (part is UIMessagePart.Tool) collectUrls(part.output, out, depth + 1)
    }
}

/**
 * 把所有 part（含工具输出嵌套）的本地文件 URL 按 [mapping] 改写。
 *
 * 映射里没有的 URL **原样保留**——缺失附件就是这种情形：用户仍能看到原来的引用（虽然可能读不到），
 * 比静默清空更可解释（对齐设计稿 §六 阶段 2 约束 6）。
 */
fun Conversation.rewriteAttachmentUrls(mapping: Map<String, String>): Conversation {
    if (mapping.isEmpty()) return this
    return copy(messageNodes = messageNodes.map { node -> node.rewriteUrls(mapping) })
}

private fun MessageNode.rewriteUrls(mapping: Map<String, String>): MessageNode =
    copy(messages = messages.map { it.copy(parts = it.parts.rewriteParts(mapping, 0)) })

private fun List<UIMessagePart>.rewriteParts(
    mapping: Map<String, String>,
    depth: Int,
): List<UIMessagePart> {
    if (depth >= MAX_PARTS_DEPTH) return this
    return map { part ->
        when (part) {
            is UIMessagePart.Image -> part.copy(url = mapping[part.url] ?: part.url)
            is UIMessagePart.Document -> part.copy(url = mapping[part.url] ?: part.url)
            is UIMessagePart.Video -> part.copy(url = mapping[part.url] ?: part.url)
            is UIMessagePart.Audio -> part.copy(url = mapping[part.url] ?: part.url)
            is UIMessagePart.Tool -> part.copy(output = part.output.rewriteParts(mapping, depth + 1))
            else -> part
        }
    }
}

/** 取 URL 的 basename（作为 `display_name` 回退；查询串/片段先剥掉）。 */
fun attachmentBasename(url: String): String = url
    .substringBefore('?')
    .substringBefore('#')
    .substringAfterLast('/')
    .takeIf { it.isNotBlank() }
    ?: "attachment"
