package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.utils.toLocalString
import java.time.Instant
import java.time.LocalDate

/**
 * `search` 回执的**总字符预算**：命中条数已由 `limit` 兜底，但单条记忆可以很长
 * （真机实测最长 3646 字符），5 条满配就可能吃掉数千 token ⇒ 再按总字符截断。
 * 取值比注入的全文层预算（6000 token ≈ 2 万字符）小得多：检索是**加餐**，不该比正餐还大。
 */
private const val SEARCH_RESULT_CHAR_BUDGET = 6000

/**
 * 检索结果被削时的可执行下一步：`read` / `search` 都是本工具真实存在的 action，
 * 模型读完 hint 能直接发下一个调用（而不是去「narrow the query」这种没有落点的话）。
 */
private const val MEMORY_TRUNCATION_HINT =
    "Call memory_tool with action:\"read\" and one of the returned ids for the full text, " +
        "or action:\"search\" with more specific keywords."

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
     * **检索**记忆库（内容 + 主题词匹配），返回按相关性排序的命中；无命中返回空表。
     *
     * 为什么需要：注入是**预算截断**的，真机 50 条库 / 8000 token 下 **23 条完全不出现**，
     * 而换选取判据并不改变覆盖度（`docs/peer-apps-benchmark.md` §11.3 E）——
     * 把「不可见」变「可取回」只能靠按需检索。实现见 `MemoryRepository.searchMemories`。
     */
    onSearch: suspend (query: String, limit: Int) -> List<AssistantMemory> = { _, _ -> emptyList() },
    /**
     * **只提案模式**（做梦在审批开着时的形态，见 `docs/design/design-memory-consolidation.md` §7.2）：
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
    /** Direct mutations require CAS callbacks; legacy parameters are retained for source compatibility only. */
    versionedOnUpdate: (suspend (String, String, Float?, String?, Long) -> AssistantMemory)? = null,
    versionedOnDelete: (suspend (String, Long) -> Unit)? = null,
    /** Only records actually rendered in the initial full-text page belong here. */
    initialRevisions: Map<String, Long> = emptyMap(),
): List<Tool> {
    val observed = initialRevisions.map { it.key to it.value }.toMutableSet()
    val registeredOperations = mutableSetOf<String>()
    val usedOperationIds = mutableSetOf<String>()
    fun observe(memory: AssistantMemory) {
        observed += memory.id to memory.revision
    }
    fun memoryPayload(memory: AssistantMemory) = buildJsonObject {
        json.encodeToJsonElement(AssistantMemory.serializer(), memory).jsonObject.forEach { (key, value) ->
            put(key, value)
        }
        put("revision", memory.revision)
        observe(memory)
    }
    fun recordProposal(proposal: MemoryConsolidationProposal) = proposalPayload(proposal).also {
        require(proposal.operationId.isNotBlank()) { "operation_id must not be blank" }
        require(proposal.operationId !in usedOperationIds) { "operation_id already registered" }
        require(proposal.dependsOn.distinct().size == proposal.dependsOn.size) { "Duplicate depends_on" }
        require(proposal.dependsOn.all { it in registeredOperations }) {
            "depends_on must reference a successful create/edit operation_id from this run"
        }
        if (proposal.action == "delete") {
            require(proposal.dependsOn.isNotEmpty()) { "Proposal delete requires non-empty depends_on" }
        }
        if (proposal.action == "edit" || proposal.action == "delete") {
            require(requireNotNull(proposal.targetId) to requireNotNull(proposal.expectedRevision) in observed) {
                "expected_revision must match a version actually shown by the page, read or search"
            }
        }
        checkNotNull(proposalSink)(proposal)
        usedOperationIds += proposal.operationId
        if (proposal.action == "create" || proposal.action == "edit") {
            registeredOperations += proposal.operationId
        }
    }
    return listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information about the user across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove),
            `read` (load the full text of one record by `id`), `search` (find records by keyword).
            - No relevant record: `create` + `content` + `importance` + `description`
            - Existing relevant record: `edit` + `id` + `expected_revision` + `content`
            - Outdated/irrelevant record: `delete` + `id` + `expected_revision`
            - A record appears in the memory index but you need its full text: `read` + `id`
            - You need something the index does not list: `search` + `query`
            The memory index lists only the records that fit the budget — older or lower-scored records
            are not shown at all. So when the user asks about a topic you are not certain is recorded
            (a person, project, plan or past decision), `search` for it before saying you do not know.
            The index description is only a hint; read or search instead of guessing from it.

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
            For edit/delete, copy `revision` from the full-text page or read/search into `expected_revision`
            (a positive integer). If no version is visible, read first. A conflict requires re-reading,
            not guessing a newer revision. In suggestion mode, receipts return `operation_id`; delete must
            supply non-empty `depends_on` containing successful create/edit operation ids from this run.
            Memories will automatically appear in the <memories> tag in later conversations.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Today is ${LocalDate.now().toLocalString(true)}.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends.","description":"reply style and activity pattern","importance":0.7}
            {"action":"edit","id":"a3f1c09e","expected_revision":1,"content":"User prefers Chinese replies."}
            {"action":"read","id":"a3f1c09e"}
            {"action":"search","query":"TikZ 渲染"}
            {"action":"delete","id":"7bd20f45","expected_revision":1}
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
                                add("search")
                            }
                        )
                        put("description", "Operation to perform: create, edit, delete, read, or search")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Opaque memory id token (required for edit/delete/read); copy it exactly from the memories list or index")
                    })
                    put("expected_revision", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("description", "Required for edit/delete: revision of the full text you actually saw.")
                    })
                    put("depends_on", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Suggestion delete: successful create/edit operation_ids from this run.")
                    })
                    put("query", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Search keywords (required for search). Use the topic's own words — a name, " +
                                "project or term. Matches on content and description.",
                        )
                    })
                    put("limit", buildJsonObject {
                        put("type", "number")
                        put(
                            "description",
                            "Search only: maximum records to return (1-${MemoryRepository.MAX_SEARCH_LIMIT}, " +
                                "default ${MemoryRepository.DEFAULT_SEARCH_LIMIT}).",
                        )
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
                        recordProposal(MemoryConsolidationProposal(
                            "create", null, requiredContent, importance, description,
                            dependsOn = params["depends_on"]?.jsonArray
                                ?.map { it.jsonPrimitive.content } ?: emptyList(),
                        ))
                    } else {
                        memoryPayload(onCreation(requiredContent, importance, description))
                    }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val requiredContent = content ?: error("content is required")
                    val importance = params["importance"]?.jsonPrimitive?.floatOrNull
                    val description = params["description"]?.jsonPrimitive?.contentOrNull
                    val expected = params["expected_revision"]?.jsonPrimitive?.longOrNull
                    require(expected != null && expected > 0) { "Positive expected_revision is required for edit" }
                    if (proposalSink != null) {
                        recordProposal(MemoryConsolidationProposal(
                            "edit", id, requiredContent, importance, description,
                            expectedRevision = expected,
                            dependsOn = params["depends_on"]?.jsonArray
                                ?.map { it.jsonPrimitive.content } ?: emptyList(),
                        ))
                    } else {
                        val update = checkNotNull(versionedOnUpdate) { "Versioned update callback is required" }
                        val memory = update(id, requiredContent, importance, description, expected)
                        memoryPayload(memory)
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val expected = params["expected_revision"]?.jsonPrimitive?.longOrNull
                    require(expected != null && expected > 0) { "Positive expected_revision is required for delete" }
                    if (proposalSink != null) {
                        recordProposal(MemoryConsolidationProposal(
                            "delete", targetId = id, expectedRevision = expected,
                            dependsOn = params["depends_on"]?.jsonArray
                                ?.map { it.jsonPrimitive.content } ?: emptyList(),
                        ))
                    } else {
                        val delete = checkNotNull(versionedOnDelete) { "Versioned delete callback is required" }
                        delete(id, expected)
                        buildJsonObject {
                            put("success", true)
                            put("id", id)
                            put("revision", expected)
                        }
                    }
                }

                // 渐进披露的读取侧：清单层只给一行主题，模型据此取全文。
                // 回执里带上 description/updated，让模型知道这条记录有多新（用于陈旧性判断）。
                "read" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                    val memory = onRead(id) ?: error("Memory record $id not found")
                    observe(memory)
                    buildJsonObject {
                        put("id", memory.id)
                        put("revision", memory.revision)
                        put("content", memory.content)
                        if (memory.description.isNotBlank()) put("description", memory.description)
                        if (memory.updatedAt > 0L) {
                            put("updated", Instant.ofEpochMilli(memory.updatedAt).toString())
                        }
                    }
                }

                // 按需检索：把注入预算之外、**清单层也没列到**的那些记录变成可取的。
                // 直接回全文而不是回一行主题——检索是模型的**主动**动作，回指针等于多花一个
                // 往返并诱使它照主题词猜；渐进披露要约束的是「自动注入」，不是「主动取回」。
                "search" -> {
                    val query = params["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (query.isEmpty()) error("query is required for search")
                    val limit = (params["limit"]?.jsonPrimitive?.intOrNull
                        ?: MemoryRepository.DEFAULT_SEARCH_LIMIT)
                        .coerceIn(1, MemoryRepository.MAX_SEARCH_LIMIT)
                    val hits = onSearch(query, limit)
                    if (hits.isEmpty()) {
                        buildJsonObject {
                            put("matches", 0)
                            put(
                                "detail",
                                "No record matches that query. Try different keywords before concluding " +
                                    "it is not recorded; only `create` if the user just stated it.",
                            )
                        }
                    } else {
                        // 字符预算：命中条数由 limit 兜底，但单条可以很长 ⇒ 再按总字符截断，
                        // 并**显式告知被略过几条**（让模型收窄查询，而不是以为这就是全部）。
                        val included = mutableListOf<AssistantMemory>()
                        var used = 0
                        hits.forEach { memory ->
                            if (included.isEmpty() || used + memory.content.length <= SEARCH_RESULT_CHAR_BUDGET) {
                                included += memory
                                used += memory.content.length
                            }
                        }
                        buildJsonObject {
                            put("matches", hits.size)
                            put("returned", included.size)
                            if (included.size < hits.size) {
                                put(
                                    "omitted",
                                    "Results were truncated by length; narrow the query or use `read` with an id.",
                                )
                            }
                            // 纯加法：散文照旧，另给一份可机读的损失量（整条未返回 ⇒ items）。
                            markTruncated(
                                OmittedKind.ITEMS,
                                hits.size - included.size,
                                MEMORY_TRUNCATION_HINT,
                            )
                            put(
                                "records",
                                buildJsonArray {
                                    included.forEach { memory ->
                                        observe(memory)
                                        add(
                                            buildJsonObject {
                                                put("id", memory.id)
                                                put("revision", memory.revision)
                                                put("content", memory.content)
                                                if (memory.description.isNotBlank()) {
                                                    put("description", memory.description)
                                                }
                                                if (memory.updatedAt > 0L) {
                                                    put(
                                                        "updated",
                                                        Instant.ofEpochMilli(memory.updatedAt).toString(),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete, read, search]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
}

/**
 * 只提案模式的回执：明确告诉模型「记下了，但没落库」，免得它以为写失败了又重试。
 */
private fun proposalPayload(proposal: MemoryConsolidationProposal) = buildJsonObject {
    put("operation_id", proposal.operationId)
    proposal.expectedRevision?.let { put("expected_revision", it) }
    put("depends_on", buildJsonArray { proposal.dependsOn.forEach { add(it) } })
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
