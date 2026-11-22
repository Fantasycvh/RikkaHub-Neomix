package app.androidrik.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件 → 会话 反向关联扫描（design-file-conversation-link.md Phase A）。
 *
 * 消息 JSON 里附件以 `file:///…/files/upload/<uuid>.<ext>` 绝对 URI 存储
 * （写入口统一经 FilesManager），与会话之间无外键；本扫描器一次全表读
 * `message_node(conversation_id, messages)`，正则提取 URI 后归一为
 * `managed_files.relative_path`，产出 Map<relativePath, conversationIds>。
 *
 * 提取用子串正则而非 kotlinx 反序列化（同 `MessageNodeDAO.hasFileReference`
 * 的 JSON 子串匹配惯例）：全量反序列化成本高一个量级，且 Tool 分片内嵌内容
 * （workspace read_image 等）也能被平铺提取到。
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
                if (row.messages.isEmpty()) return@forEach
                for (match in FILE_URL_REGEX.findAll(row.messages)) {
                    val relativePath = relativePathOf(match.value, filesDir) ?: continue
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

        /**
         * `file:///data/…/files/upload/x.png` → `upload/x.png`。
         * URI 路径段是 percent-encoded 的，先 `Uri.decode` 再归一；
         * 归一失败（filesDir 之外）返回 null。
         */
        fun relativePathOf(fileUrl: String, filesDir: File): String? {
            val path = Uri.decode(fileUrl.removePrefix("file://"))
            if (path.isEmpty() || path.contains("..")) return null
            val file = File(File.separatorChar + path.trimStart(File.separatorChar, '/'))
            return FileUtils.getRelativePathInFilesDir(filesDir, file)
        }
    }
}
