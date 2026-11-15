package app.androidrik.rikkahub.data.sync.importer

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DeepSeek 官方导出包（`export_format_version` 3）的格式模型与**宽松解析**。
 *
 * 格式真相源：官方 web 端参考实现 `chat-demo` 的 `src/services/chat/history/format.ts` /
 * `schema.ts`（13 种 fragment、RFC3339 Z 变长小数秒、可空字段显式 null、可选字段可缺省）。
 *
 * 校验容错（设计稿 §2.4，比官方全量替换更宽松——本应用是旁路追加）：
 * - **容器/元数据级**失败（会话 id / 消息树结构 / 时间戳非法）→ 该会话整体拒收（返回 null）；
 * - **单个 fragment** 解析失败 → 仅丢弃该片段（`parseFragment` 返回 null）；
 * - 消息级失败（role/id 非法）→ 仅丢弃该消息。
 *
 * 纯 JVM 实现（不依赖 android.*）：单测可直接构造 JsonElement 走本文件解析。
 */
object DeepSeekFormat {
    /** 当前支持的导出格式版本（manifest.json 的 `export_format_version`）。 */
    const val FORMAT_VERSION = 3

    /** 版本门禁失败：>3 = 导出格式更新（需升级 App）；≠3 = 不识别的格式。 */
    class VersionException(val newer: Boolean) : Exception(
        if (newer) "export format version is newer than supported" else "unsupported export format version"
    )

    /** 容器级失败（manifest 缺失 / 首条目不是 manifest 等），整次导入中止。 */
    class ContainerException(message: String) : Exception(message)

    // ---- manifest ----

    /** 校验 manifest 版本门禁（照抄 chat-demo `checkManifest` 语义）。 */
    fun checkManifest(element: JsonElement) {
        val version = (element as? JsonObject)?.get("export_format_version")?.asDoubleOrNull
            ?: throw ContainerException("manifest.json missing export_format_version")
        if (version > FORMAT_VERSION) throw VersionException(newer = true)
        if (version != FORMAT_VERSION.toDouble()) throw VersionException(newer = false)
    }

    // ---- session ----

    /**
     * 解析 `chat_sessions/{id}.json`。容器/元数据不合法返回 null（会话级失败）。
     *
     * 会话 id 用于拼接条目名，不能含路径分隔符（对齐官方 `sessionId` 校验）。
     */
    fun parseSession(element: JsonElement): DeepSeekSessionFile? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.asStringOrNull?.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && it != "." && it != ".." }
            ?: return null
        val insertedAt = obj["inserted_at"]?.asStringOrNull?.let(::parseTime) ?: return null
        val updatedAt = obj["updated_at"]?.asStringOrNull?.let(::parseTime) ?: return null
        val messagesElement = obj["messages"] as? JsonArray ?: return null
        val messages = messagesElement.mapNotNull { parseMessage(it) }
        return DeepSeekSessionFile(
            id = id,
            title = obj["title"]?.asStringOrNull,
            pinned = obj["pinned"]?.asBooleanOrNull ?: false,
            // current_message_id 是线性化唯一依据；缺失（损坏）时由导入侧兜底取最大 id 节点
            currentMessageId = obj["current_message_id"]?.asLongOrNull,
            insertedAt = insertedAt,
            updatedAt = updatedAt,
            messages = messages,
        )
    }

    /** 单条消息解析：id/parent_id/role/inserted_at 任一非法 → null（该消息丢弃）。 */
    private fun parseMessage(element: JsonElement): DeepSeekMessage? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.asLongOrNull ?: return null
        val role = obj["role"]?.asStringOrNull?.let { role ->
            DeepSeekRole.entries.firstOrNull { it.name == role }
        } ?: return null
        val status = obj["status"]?.asStringOrNull?.let { status ->
            DeepSeekStatus.entries.firstOrNull { it.name == status }
        } ?: return null
        val insertedAt = obj["inserted_at"]?.asStringOrNull?.let(::parseTime) ?: return null
        val fragments = (obj["fragments"] as? JsonArray)?.mapNotNull { parseFragment(it) } ?: emptyList()
        return DeepSeekMessage(
            id = id,
            parentId = obj["parent_id"]?.asLongOrNull,
            role = role,
            status = status,
            insertedAt = insertedAt,
            // 沿分支累计的上下文 token；口径与 TokenUsage 不可对齐 → 只用于「丢弃了多少」的计数
            accumulatedTokenUsage = obj["accumulated_token_usage"]?.asLongOrNull,
            fragments = fragments,
        )
    }

    /** 单个 fragment 解析：任何字段不合法 → null（丢弃该片段，不影响其余）。 */
    private fun parseFragment(element: JsonElement): DeepSeekFragment? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.asStringOrNull ?: return null
        return when (type) {
            "REQUEST" -> DeepSeekFragment.Request(obj["content"]?.asStringOrNull ?: return null)
            "RESPONSE" -> DeepSeekFragment.Response(obj["content"]?.asStringOrNull ?: return null)

            "THINK" -> DeepSeekFragment.Think(
                content = obj["content"]?.asStringOrNull ?: return null,
                elapsedSecs = obj["elapsed_secs"]?.asDoubleOrNull,
            )

            "TEMPLATE_RESPONSE" -> DeepSeekFragment.TemplateResponse(obj["content"]?.asStringOrNull ?: return null)
            "TIP", "CONTENT_RISK" -> DeepSeekFragment.Tip(
                content = obj["content"]?.asStringOrNull ?: "",
                contentRisk = type == "CONTENT_RISK",
            )

            "PARTIAL_FILE_READ" -> DeepSeekFragment.PartialFileRead
            "TOOL_SEARCH" -> DeepSeekFragment.ToolSearch(
                queries = obj["queries"]?.asArrayOrNull?.mapNotNull { it.asStringOrNull } ?: return null,
                results = obj["results"]?.asArrayOrNull?.mapNotNull { parseSearchResult(it) } ?: return null,
            )

            "TOOL_OPEN" -> DeepSeekFragment.ToolOpen(
                url = obj["url"]?.asStringOrNull,
                title = obj["title"]?.asStringOrNull,
            )

            "TOOL_FIND" -> DeepSeekFragment.ToolFind(pattern = obj["pattern"]?.asStringOrNull)
            "READ_LINK" -> DeepSeekFragment.ReadLink(
                results = obj["results"]?.asArrayOrNull?.mapNotNull { entry ->
                    (entry as? JsonObject)?.let {
                        DeepSeekReadLinkResult(
                            signature = it["signature"]?.asStringOrNull ?: return@mapNotNull null,
                            url = it["url"]?.asStringOrNull,
                            title = it["title"]?.asStringOrNull,
                        )
                    }
                } ?: return null,
            )

            "FILE" -> DeepSeekFragment.FileRef(
                files = obj["files"]?.asArrayOrNull?.mapNotNull { parseFileMeta(it) } ?: return null,
            )

            "TOOL_IMAGE_SEARCH" -> DeepSeekFragment.ImageSearch

            // 官方可能新增的 fragment 类型：按「未知类型」丢弃（与官方 default → invalid 一致）
            else -> null
        }
    }

    private fun parseSearchResult(element: JsonElement): DeepSeekSearchResult? {
        val obj = element as? JsonObject ?: return null
        return DeepSeekSearchResult(
            url = obj["url"]?.asStringOrNull ?: return null,
            title = obj["title"]?.asStringOrNull ?: return null,
            snippet = obj["snippet"]?.asStringOrNull ?: "",
        )
    }

    private fun parseFileMeta(element: JsonElement): DeepSeekFileMeta? {
        val obj = element as? JsonObject ?: return null
        return DeepSeekFileMeta(
            id = obj["id"]?.asStringOrNull ?: return null,
            fileName = obj["file_name"]?.asStringOrNull ?: return null,
            exportPath = obj["export_path"]?.asStringOrNull,
            isImage = obj["is_image"]?.asBooleanOrNull ?: false,
        )
    }

    /**
     * RFC3339 UTC（`Z` 结尾、小数秒精度 0-9 位不固定）→ [Instant]。
     * 用官方同款正则收紧（拒绝非 UTC 偏移）；小数秒超 9 位截断（防御性）。
     */
    fun parseTime(text: String): Instant? {
        val match = TIME_REGEX.matchEntire(text) ?: return null
        val fraction = match.groupValues[2].take(9)
        val normalized = if (fraction.isEmpty()) match.groupValues[1] + "Z" else "${match.groupValues[1]}.$fraction" + "Z"
        return runCatching { Instant.parse(normalized) }.getOrNull()
    }

    private val TIME_REGEX = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$""")

    // ---- JsonElement 宽松取值（风格对齐 ChatboxImporter，可空字段显式 null 也走这里） ----

    private val JsonElement.asStringOrNull: String?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private val JsonElement.asBooleanOrNull: Boolean?
        get() = asStringOrNull?.toBooleanStrictOrNull()

    private val JsonElement.asLongOrNull: Long?
        get() = asStringOrNull?.toLongOrNull()

    private val JsonElement.asDoubleOrNull: Double?
        get() = asStringOrNull?.toDoubleOrNull()

    private val JsonElement.asArrayOrNull: JsonArray?
        get() = this as? JsonArray
}

// ---- 格式数据类 ----

data class DeepSeekSessionFile(
    val id: String,
    val title: String?,
    val pinned: Boolean,
    val currentMessageId: Long?,
    val insertedAt: Instant,
    val updatedAt: Instant,
    val messages: List<DeepSeekMessage>,
)

data class DeepSeekMessage(
    val id: Long,
    val parentId: Long?,
    val role: DeepSeekRole,
    val status: DeepSeekStatus,
    val insertedAt: Instant,
    /** 沿分支累计的上下文 token；官方口径，**不导入**（见设计稿 §7.4）。 */
    val accumulatedTokenUsage: Long?,
    val fragments: List<DeepSeekFragment>,
)

enum class DeepSeekRole { USER, ASSISTANT }

enum class DeepSeekStatus {
    WIP,
    FINISHED,
    CONTENT_FILTER,
    FAILED,
    INCOMPLETE,
    CONTEXT_LENGTH_EXCEEDED,
    TIMEOUT,
}

sealed interface DeepSeekFragment {
    data class Request(val content: String) : DeepSeekFragment

    data class Response(val content: String) : DeepSeekFragment

    /** 思考正文；`elapsed_secs`（可空）由导入侧写入 Reasoning 的 metadata。 */
    data class Think(val content: String, val elapsedSecs: Double?) : DeepSeekFragment

    /** 服务端模板（过载/过滤/超长等），官方语义不进上下文 → 导入时丢弃。 */
    data class TemplateResponse(val content: String) : DeepSeekFragment

    /** 兜底提示条（TIP / CONTENT_RISK），官方无展示对应 → 丢弃。 */
    data class Tip(val content: String, val contentRisk: Boolean) : DeepSeekFragment

    data object PartialFileRead : DeepSeekFragment

    data class ToolSearch(val queries: List<String>, val results: List<DeepSeekSearchResult>) : DeepSeekFragment

    data class ToolOpen(val url: String?, val title: String?) : DeepSeekFragment

    data class ToolFind(val pattern: String?) : DeepSeekFragment

    /** signature 是服务端私有签名，物理上无法跨端恢复正文 → 永不落库（设计稿 §一）。 */
    data class ReadLink(val results: List<DeepSeekReadLinkResult>) : DeepSeekFragment

    data class FileRef(val files: List<DeepSeekFileMeta>) : DeepSeekFragment

    data object ImageSearch : DeepSeekFragment
}

data class DeepSeekSearchResult(
    val url: String,
    val title: String,
    val snippet: String,
)

data class DeepSeekReadLinkResult(
    val signature: String,
    val url: String?,
    val title: String?,
)

data class DeepSeekFileMeta(
    val id: String,
    val fileName: String,
    /** zip 内原件路径（`chat_sessions/{session}_files/...`），null = 官方未导出本体。 */
    val exportPath: String?,
    val isImage: Boolean,
)
