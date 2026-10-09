package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.toLocalString
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, Float?) -> AssistantMemory,
    onUpdate: suspend (String, String, Float?) -> AssistantMemory,
    onDelete: suspend (String) -> Unit,
    /** 修改/删除记忆需审批（按 action 名判定；create 始终放行——见 Assistant.memoryModifyApproval） */
    needsApproval: (action: String) -> Boolean = { false },
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information about the user across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content` + `importance`
            - Existing relevant record: `edit` + `id` + `content` (+ `importance` when it changed)
            - Outdated/irrelevant record: `delete` + `id`

            What to record — the stated test:
            Every record must be something the user said themselves, as they said it. Never record:
            - your inferences ("likes X" -> "likes the whole category X belongs to")
            - your own suggestions, plans or reasoning, even if the user replied "ok" (when the user picks one of the options you offered, record only their choice)
            - results from search, tools or other external sources (they can be looked up again)
            - additions to the user's words (user said "Shenzhen"; do not write "Shenzhen, Guangdong")
            - hearsay, or your own to-dos ("next steps", "to confirm")
            The user's own plans, undecided choices and intentions are things they said: record them.

            Calibration:
            - A single mention of a taste or hobby is a note, not a fact: write it as "mentioned X once" until it recurs.
            - Stable facts (name, language, occupation, relationships, ongoing projects) are recorded on first mention.
            - Prefer wording that does not go stale ("mornings are full of meetings" outlasts "standup 10:00-10:15").
            - Staleness test: a month from now, in an unrelated conversation, would this still be true and worth reading?
            - Never record again what is already recorded — a rephrasing counts as already recorded; `edit` it instead.

            Never record, and decline in one sentence if asked:
            ID or passport numbers, account or bank card numbers, criminal records, anything about self-harm,
            suicide or eating disorders, sexual experiences, experiences of abuse, the age of a minor user,
            your inferences about the user's personality.
            Requests to flatter unconditionally, suppress criticism, never voice concern, or play a fixed
            persona or partner are never recorded, not even in a weakened form.

            Sensitive topics (health, finances, religion, sexual orientation, political views): record them in
            the user's own words, as a separate write performed after the ordinary writes; never turn a mentioned
            symptom or medication into a diagnosis; keep sensitive details out of the record's wording.

            `importance` (0.0-1.0) decides which memories get loaded into future conversations —
            only the most important ones fit in the context budget, so judge it honestly:
            - 0.9-1.0: identity or strong long-term preference (preferred name, language, occupation, hard rules)
            - 0.6-0.8: ordinary preference, or an ongoing project
            - 0.2-0.5: minor or likely temporary note
            Rate on that scale instead of defaulting everything to a high number.

            `id` is an opaque token from the <memories> list (e.g. "a3f1c09e"). Copy it exactly;
            it is not a count or an index, so never guess or derive it.
            Memories will automatically appear in the <memories> tag in later conversations.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends.","importance":0.7}
            {"action":"edit","id":"a3f1c09e","content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies.","importance":0.95}
            {"action":"delete","id":"7bd20f45"}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Opaque memory id token (required for edit/delete); copy it exactly from the memories list")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "The content of the memory record (required for create/edit). " +
                                "Never include ID or passport numbers, account or bank card numbers — " +
                                "such writes are refused and not stored."
                        )
                    })
                    put("importance", buildJsonObject {
                        put("type", "number")
                        put(
                            "description",
                            "0.0-1.0: how important this memory is for future conversations. " +
                                "0.9+ identity/strong preference, 0.6-0.8 ordinary preference or ongoing project, " +
                                "0.2-0.5 minor note. Decides which memories fit the context budget."
                        )
                    })
                },
                required = listOf("action")
            )
        },
        // 复用通用审批基础设施（GenerationHandler 循环 + ChatMessageTools 审批按钮）：
        // 返回 true 时该工具调用被标记 Pending、break 等用户确认（create 不拦）。
        needsApproval = { input ->
            val action = input.jsonObject["action"]?.jsonPrimitive?.contentOrNull.orEmpty()
            needsApproval(action)
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val content = params["content"]?.jsonPrimitive?.contentOrNull
            // 写入前的代码级兜底（提示词是第一道约束，见 MemoryContentGuard）：
            // 只拦 create/edit 的 content；delete 无内容可泄漏、未知 action 交给下面的报错分支。
            val refusal = if (action == "create" || action == "edit") {
                content?.let { findForbiddenMemoryContent(it) }?.let { refusalPayload(it) }
            } else {
                null
            }
            val payload = refusal ?: when (action) {
                "create" -> {
                    val requiredContent = content ?: error("content is required")
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(requiredContent, importance))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val requiredContent = content ?: error("content is required")
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, requiredContent, importance))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * 被兜底拦截时的回执（结构化，让模型知道**别重试**——与无头 run 的策略拒绝契约同款）。
 *
 * **不回显命中的原文**：原文是敏感标识本身，回显等于把它写进对话历史与请求日志。
 */
private fun refusalPayload(violation: MemoryContentViolation) = buildJsonObject {
    put("success", false)
    put("stored", false)
    put("category", violation.category)
    put(
        "detail",
        "Personal identifiers (${MemoryContentViolation.ID_NUMBER.category} / " +
            "${MemoryContentViolation.SOCIAL_SECURITY_NUMBER.category} / " +
            "${MemoryContentViolation.BANK_CARD_NUMBER.category}) are never stored in memory. " +
            "Do not retry this write. If the user asks, explain it in one sentence."
    )
}
