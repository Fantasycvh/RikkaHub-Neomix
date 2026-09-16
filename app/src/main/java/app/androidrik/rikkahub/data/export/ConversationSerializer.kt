package app.androidrik.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.decodeFromJsonElement
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.JsonInstantPretty

/**
 * 单会话导出/导入（**自家格式**，非 Markdown/HTML/PNG 三形态——那三种只出不能回填）。
 *
 * 设计见 `docs/design-conversation-import-export.md`：
 * - 导出携带 `sourceId`（导出时的会话 id）作为**重复导入检测的幂等锚点**；
 * - 导入**总是换新 id**（[Conversation.remapForImport]），故同一文件可反复导入、每次得新副本；
 * - 导出文件**不存 `assistantId`**（跨设备必失效），目标助手由用户导入时选择（UI 层）。
 *
 * **本类不做 id 重映射与检查点迁移**（那是 [remapForImport] 的职责，且需要"目标助手"这一
 * 用户输入）——[import] 只负责**解析**出会话；调用方解析后自行决定目标助手并重映射。
 */
object ConversationSerializer : ExportSerializer<Conversation> {
    override val type: String = "conversation"

    override fun export(data: Conversation): ExportData = ExportData(
        version = 2,  // 阶段 2：容器为 zip 且携带附件清单（见 docs/design-conversation-import-export.md §六）
        type = type,
        data = JsonInstant.encodeToJsonElement(Conversation.serializer(), data),
        exportedAt = System.currentTimeMillis(),
        // 幂等锚点：导出时刻的会话 id。它随文件固定不变，导入侧据此提示重复导入
        sourceId = data.id.toString(),
    )

    /**
     * 解析导出文件为 [Conversation]（**未换 id、未换助手**）。
     *
     * 调用方须在使用前做两件事（见设计稿 §三/§四）：
     * 1. 确定**目标助手**（用户选择；文件里的 `assistantId` 已被忽略——见下）；
     * 2. 调 [remapForImport] 换新 id 并迁移压缩检查点。
     */
    override fun import(context: Context, uri: Uri): Result<Conversation> = runCatching {
        parse(readUri(context, uri))
    }

    /** 从 JSON 文本解析（便于单测与复用；不含 IO）。 */
    fun parse(json: String): Conversation {
        val exportData = JsonInstant.decodeFromString(ExportData.serializer(), json)
        require(exportData.type == type) {
            "Not a conversation export (type=${exportData.type})"
        }
        val conversation = JsonInstant.decodeFromJsonElement(Conversation.serializer(), exportData.data)
        // 压缩检查点：`Conversation` 序列化时带着 compresssionSummaries；其 conversationId 仍是旧值，
        // 由 remapForImport 统一改为新会话 id。
        return conversation
    }

    /** 导出为 JSON 文本。 */
    fun toJson(conversation: Conversation, pretty: Boolean = false): String =
        (if (pretty) JsonInstantPretty else JsonInstant).encodeToString(
            ExportData.serializer(),
            export(conversation),
        )
}
