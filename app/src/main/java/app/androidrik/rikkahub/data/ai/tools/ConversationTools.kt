package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.fts.MessageSearchSort
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    createReadConversationTool(conversationRepo),
    Tool(
        name = "recent_chats",
        description = """
            List the user's recent conversations with you to understand their preferences and ongoing topics.
            Returns conversation titles and the date of last activity, ordered by pinned first then most recently updated.
            Use this when you need quick context about what the user has been discussing lately.
            Only titles and dates are returned; use `conversation_search` to look up the actual content.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    Tool(
        name = "conversation_search",
        description = """
            Full-text search across the user's past conversations to recall specific information they mentioned before.
            Use focused keywords. Run multiple searches with different keywords if needed.
            Each result includes the conversation title, a snippet with matched keywords wrapped in [brackets], and the date.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Keywords to search for in past conversation messages")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of results to return (default: 15, max: 50)"
                        )
                    })
                },
                required = listOf("query")
            )
        },
        execute = {
            val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
                ?: error("query is required")
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
            val results = conversationRepo
                .searchMessages(query, MessageSearchSort.RELEVANCE)
                .take(limit)
            val payload = buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("conversation_id", result.conversationId)
                        // message_id 是 `read_conversation` 的寻址锚点：检索层只给片段，
                        // 完整上下文由第三层按此键取回（分层加载）
                        put("message_id", result.messageId)
                        put("title", result.title.ifBlank { "Untitled" })
                        put("snippet", result.snippet)
                        put("date", result.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    )
)

/**
 * 第三层（分层加载的「详情层」）：按锚点取回命中点周围的完整消息。
 *
 * 工具族漏斗：`recent_chats`（标题索引）→ `conversation_search`（片段+寻址键）→
 * 本工具（完整上下文）。前两层给的是便宜信号，本层按需取回——把「历史上下文」的
 * 成本从一次性全量改为分层按需（与 peer⑤③ schema 三档的元工具模式同构，见
 * handoff §三 同名条目；本工具是该模式的**零纠缠练手场**）。
 */
fun createReadConversationTool(
    conversationRepo: ConversationRepository,
): Tool = Tool(
    name = "read_conversation",
    description = """
        Read the full messages around a specific point in one of the user's past conversations.
        Use it when a `conversation_search` snippet is not enough and you need the surrounding dialogue
        (e.g. the question that led to an answer, or details cut off by the snippet).
        Pass conversation_id and message_id exactly as returned by `conversation_search`.
        Returns the anchor message plus the requested number of messages before and after it, oldest first.
        Without message_id it reads from the beginning of the conversation's current branch.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation id from `recent_chats` or `conversation_search`")
                })
                put("message_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional message id from `conversation_search`; anchors the read window")
                })
                put("context", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Messages to include before and after the anchor (default: 6, max: 20)"
                    )
                })
            },
            required = listOf("conversation_id")
        )
    },
    execute = {
        val conversationId = it.jsonObject["conversation_id"]?.jsonPrimitive?.contentOrNull
            ?: error("conversation_id is required")
        val anchorMessageId = it.jsonObject["message_id"]?.jsonPrimitive?.contentOrNull
        val context = (it.jsonObject["context"]?.jsonPrimitive?.intOrNull ?: 6).coerceIn(1, 20)

        val conversation = conversationRepo.getConversationById(
            runCatching { Uuid.parse(conversationId) }.getOrElse {
                error("conversation_id is not a valid UUID: $conversationId")
            }
        ) ?: error("Conversation not found: $conversationId")

        listOf(
            UIMessagePart.Text(
                buildConversationReadPayload(
                    conversation = conversation,
                    anchorMessageId = anchorMessageId,
                    context = context,
                )
            )
        )
    },
)

private const val MAX_TOTAL_CHARS = 24_000
private const val MAX_MESSAGE_CHARS = 8_000

/**
 * 取回锚点 ±[context] 条完整消息（锚点含在内），纯函数可单测。
 *
 * 预算纪律：单条消息截断（[MAX_MESSAGE_CHARS]，工具输出可达 32KB）+ 总量截断
 * （[MAX_TOTAL_CHARS]）——防模型连续拉取把上下文撑爆；截断都带显式标记。
 * 寻址失败（message_id 不在当前分支）不报错：回退到会话开头并在 payload 里注明，
 * 让模型自行决定换锚点。
 */
internal fun buildConversationReadPayload(
    conversation: Conversation,
    anchorMessageId: String?,
    context: Int,
): String {
    val messages = conversation.currentMessages
    if (messages.isEmpty()) {
        return buildJsonObject {
            put("conversation_id", conversation.id.toString())
            put("title", conversation.title.ifBlank { "Untitled" })
            put("messages", buildJsonArray { })
            put("note", "Conversation is empty")
        }.toString()
    }

    val anchorIndex = anchorMessageId
        ?.let { anchor -> messages.indexOfFirst { it.id.toString() == anchor } }
        ?.takeIf { it >= 0 }
    val anchored = anchorIndex != null
    val from = ((anchorIndex ?: 0) - context).coerceAtLeast(0)
    val to = ((anchorIndex ?: 0) + context).coerceAtMost(messages.size - 1)

    var truncated = false
    var used = 0
    val items = buildJsonArray {
        for (index in from..to) {
            val message = messages[index]
            val rendered = renderMessage(message).let { text ->
                if (text.length > MAX_MESSAGE_CHARS) {
                    truncated = true
                    text.take(MAX_MESSAGE_CHARS) + "\n…(message truncated)"
                } else {
                    text
                }
            }
            if (used + rendered.length > MAX_TOTAL_CHARS && index != (anchorIndex ?: 0)) {
                truncated = true
                break
            }
            used += rendered.length
            add(buildJsonObject {
                put("position", index)
                put("role", message.role.name.lowercase())
                put("date", message.createdAt.date.toString())
                put("content", rendered)
                if (anchorIndex == index) put("anchor", true)
            })
        }
    }

    return buildJsonObject {
        put("conversation_id", conversation.id.toString())
        put("title", conversation.title.ifBlank { "Untitled" })
        put("anchored", anchored)
        if (!anchored && anchorMessageId != null) {
            put("note", "Anchor message_id not found in current branch; reading from the beginning")
        }
        if (truncated) put("note", "Output truncated; narrow the context or search with more specific keywords")
        put("messages", items)
    }.toString()
}

private fun renderMessage(message: UIMessage): String = buildString {
    message.parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> {
                if (isNotBlank()) appendLine()
                append(part.text)
            }
            is UIMessagePart.Tool -> {
                if (isNotBlank()) appendLine()
                append("[tool call: ${part.toolName}]")
            }
            is UIMessagePart.Image -> {
                if (isNotBlank()) appendLine()
                append("[image]")
            }
            is UIMessagePart.Document -> {
                if (isNotBlank()) appendLine()
                append("[document: ${part.fileName}]")
            }
            else -> {}
        }
    }
}.ifBlank { "[empty message]" }
