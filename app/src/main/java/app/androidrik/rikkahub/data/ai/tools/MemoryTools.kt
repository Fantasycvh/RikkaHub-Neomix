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
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.toLocalString
import java.time.Instant
import java.time.LocalDate

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String, Float?, String?) -> AssistantMemory,
    onUpdate: suspend (String, String, Float?, String?) -> AssistantMemory,
    onDelete: suspend (String) -> Unit,
    /**
     * 读取一条记忆的全文（**渐进披露**：清单层只给一行主题，模型按需取全文）。
     * 实现里要**顺带记一次读取遥测**（见 `MemoryRepository.readMemory`）；读不到返回 null。
     */
    onRead: suspend (String) -> AssistantMemory? = { null },
    /**
     * **只提案模式**（做梦在审批开着时的形态，见 `docs/design-memory-consolidation.md` §7.2）：
     * 非 null 时 `create` / `edit` / `delete` 都**不动库**，而是把「本该如何改」交给它，
     * 并回执 `applied:false`。
     *
     * 为什么在工具层做，而不是靠 `needsApproval` 拦：审批机制是**交互**的（挂起等用户点），
     * 后台整理没有用户可等；而「直接拒绝」又会丢掉模型原本想改的内容。这里既不挂起也不丢弃。
     * `read` 不受影响（只读）。
     */
    proposalSink: ((MemoryConsolidationProposal) -> Unit)? = null,
    /** 修改/删除记忆需审批（按 action 名判定；create 始终放行——见 Assistant.memoryModifyApproval） */
    needsApproval: (action: String) -> Boolean = { false },
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information about the user across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove),
            `read` (load the full text of one record by `id`).
            - No relevant record: `create` + `content` + `importance` + `description`
            - Existing relevant record: `edit` + `id` + `content` (+ `importance` / `description` when they changed)
            - Outdated/irrelevant record: `delete` + `id`
            - A record appears in the memory index but you need its full text: `read` + `id`
            The memory index lists the records that did not fit the full-text budget; its description is
            only a hint. Before answering a question about a person, project or plan that the index covers,
            read the record instead of guessing from its description.

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
            {"action":"create","content":"User prefers brief replies and is more active on weekends.","description":"reply style and activity pattern","importance":0.7}
            {"action":"edit","id":"a3f1c09e","content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies.","importance":0.95}
            {"action":"read","id":"a3f1c09e"}
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
                                add("read")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, or read")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Opaque memory id token (required for edit/delete/read); copy it exactly from the memories list or index")
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
                    put("description", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional but recommended: one short line (under 150 characters) on what this " +
                                "record is about, naming the people, projects and places most likely to be asked " +
                                "about later. It is what future conversations see when the record does not fit the " +
                                "full-text budget, so make it specific. Keep sensitive details out of it."
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
                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                    if (proposalSink != null) {
                        proposalSink(MemoryConsolidationProposal("create", null, requiredContent, importance, description))
                        proposalPayload()
                    } else {
                        json.encodeToJsonElement(
                            AssistantMemory.serializer(),
                            onCreation(requiredContent, importance, description),
                        )
                    }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val requiredContent = content ?: error("content is required")
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                    if (proposalSink != null) {
                        proposalSink(MemoryConsolidationProposal("edit", id, requiredContent, importance, description))
                        proposalPayload()
                    } else {
                        json.encodeToJsonElement(
                            AssistantMemory.serializer(),
                            onUpdate(id, requiredContent, importance, description),
                        )
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    if (proposalSink != null) {
                        proposalSink(MemoryConsolidationProposal("delete", targetId = id))
                        proposalPayload()
                    } else {
                        onDelete(id)
                        buildJsonObject {
                            put("success", true)
                            put("id", id)
                        }
                    }
                }

                // 渐进披露的读取侧：清单层只给一行主题，模型据此取全文。
                // 回执里带上 description/updated，让模型知道这条记录有多新（用于陈旧性判断）。
                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val memory = onRead(id) ?: error("Memory record $id not found")
                    buildJsonObject {
                        put("id", memory.id)
                        put("content", memory.content)
                        if (memory.description.isNotBlank()) put("description", memory.description)
                        if (memory.updatedAt > 0L) {
                            put("updated", Instant.ofEpochMilli(memory.updatedAt).toString())
                        }
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, read]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

/**
 * 只提案模式的回执：明确告诉模型「记下了，但没落库」，免得它以为写失败了又重试。
 */
private fun proposalPayload() = buildJsonObject {
    put("success", true)
    put("applied", false)
    put("proposal", true)
    put(
        "detail",
        "Recorded as a suggestion for the user to review. Nothing was stored or changed; " +
            "do not repeat this call."
    )
}

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
