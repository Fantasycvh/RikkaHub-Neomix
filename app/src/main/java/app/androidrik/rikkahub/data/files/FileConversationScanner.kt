package app.androidrik.rikkahub.data.files

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件 → 会话 反向关联扫描（design-file-conversation-link.md Phase A/B）。
 *
 * 消息 JSON 里附件以 `file:///…/files/upload/<uuid>.<ext>` 绝对 URI 存储
 * （写入口统一经 FilesManager），与会话之间无外键；本扫描器一次全表读
 * `message_node(conversation_id, messages)`，正则提取 URI 后归一为
 * `managed_files.relative_path`，产出 Map<relativePath, conversationIds>。
 *
 * 提取用子串正则而非 kotlinx 反序列化（同 `MessageNodeDAO.hasFileReference`
 * 的 JSON 子串匹配惯例）：全量反序列化成本高一个量级，且 Tool 分片内嵌内容
 * （workspace read_image 等）也能被平铺提取到。Phase B 起扫描降级为回填引擎/
 * 兜底，日常展示读 `message_file_ref` 关联表（写路径在 ConversationRepository 收口）。
 */
class FileConversationScanner(
    private val context: Context,
    private val database: AppDatabase,
    private val messageNodeDAO: MessageNodeDAO,
) {
    /**
     * 全量扫描。单 SELECT 原子读 + 单事务批量处理（套 rebuildAllIndexes 的
     * WAL 提交教训），调用方在后台 IO 触发；结果仅供展示层按
     * `managed_files.relative_path` JOIN 过滤，外部路径/归一失败的条目自然落不进。
     */
    suspend fun scan(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val filesDir = runCatching { context.filesDir.canonicalFile }.getOrElse { context.filesDir }
        val result = HashMap<String, MutableSet<String>>()
        database.withTransaction {
            messageNodeDAO.getAllRefRows().forEach { row ->
                extractRelativePathsFromJson(row.messages, filesDir).forEach { relativePath ->
                    result.getOrPut(relativePath) { HashSet() }.add(row.conversationId)
                }
            }
        }
        Log.i(
            TAG,
            "scan: ${result.size} paths, ${result.values.sumOf { it.size }} refs " +
                "in ${System.currentTimeMillis() - startMs}ms",
        )
        result
    }

    companion object {
        private const val TAG = "FileConvScanner"

        /**
         * JSON 字符串内的 file:// URI。kotlinx 序列化后 URI 里的 `"` 与 `\`
         * 都会被转义（`\"` / `\\`），故匹配到这两个字符即视为 URI 终止；
         * 空白字符同理（UUID 文件名不含空白，防御历史数据）。
         */
        private val FILE_URL_REGEX = Regex("""file://[^"\\\s]*""")

        /** 从消息 JSON（message_node.messages 原文）提取引用的 relativePath 集合。 */
        fun extractRelativePathsFromJson(messagesJson: String, filesDir: File): Set<String> {
            if (!messagesJson.contains("file://")) return emptySet()
            val paths = HashSet<String>()
            for (match in FILE_URL_REGEX.findAll(messagesJson)) {
                relativePathOf(match.value, filesDir)?.let(paths::add)
            }
            return paths
        }

        /** 从内存 parts（含 Tool 分片内嵌内容，同 Base64ImageSanitizer 的递归口径）提取 file:// URL。 */
        fun collectFileUrls(parts: List<UIMessagePart>): List<String> = parts.flatMap { part ->
            when (part) {
                is UIMessagePart.Image -> listOf(part.url)
                is UIMessagePart.Video -> listOf(part.url)
                is UIMessagePart.Audio -> listOf(part.url)
                is UIMessagePart.Document -> listOf(part.url)
                is UIMessagePart.Tool -> collectFileUrls(part.output)
                else -> emptyList()
            }
        }

        /** 写路径收口用：从内存 parts 提取引用的 relativePath 集合（仅 file:// 项）。 */
        fun extractRelativePathsFromParts(parts: List<UIMessagePart>, filesDir: File): Set<String> =
            collectFileUrls(parts)
                .filterTo(HashSet()) { it.startsWith("file://") }
                .mapNotNull { relativePathOf(it, filesDir) }
                .toSet()

        /**
         * `file:///data/…/files/upload/x.png` → `upload/x.png`。
         * URI 路径段是 percent-encoded 的，先解码再归一（自带 %XX 解码而非
         * android.net.Uri：本伴生对象要在 JVM 单测里跑）；归一失败（filesDir 之外）返回 null。
         */
        fun relativePathOf(fileUrl: String, filesDir: File): String? {
            val path = decodePercentEncoded(fileUrl.removePrefix("file://"))
            if (path.isEmpty() || path.contains("..")) return null
            val file = File(File.separatorChar + path.trimStart(File.separatorChar, '/'))
            return FileUtils.getRelativePathInFilesDir(filesDir, file)
        }

        /** %XX 解码（UTF-8）；非法序列原样保留。URI 里 `+` 是字面量（路径段），不转空格。 */
        internal fun decodePercentEncoded(input: String): String {
            if ('%' !in input) return input
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '%' && i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        bytes.write(hex)
                        i += 3
                        continue
                    }
                }
                bytes.write(c.code)
                i++
            }
            return bytes.toString("UTF-8")
        }
    }
}
