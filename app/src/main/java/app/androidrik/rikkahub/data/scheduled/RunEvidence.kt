package app.androidrik.rikkahub.data.scheduled

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.*
import app.androidrik.rikkahub.data.ai.isMemoryTool
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.*

data class RunEvidence(val memorySync: String, val summary: String, val receipts: String)

fun messagesOfScheduledRun(messages: List<UIMessage>, runId: String): List<UIMessage> {
    val start = messages.indexOfLast { m -> m.annotations.any { it is UIMessageAnnotation.ScheduledTrigger && it.runId == runId } }
    return if (start < 0) emptyList() else messages.drop(start + 1).takeWhile { it.semanticRole != MessageRole.USER }
}

private fun JsonObject.value(key: String) = (this[key] as? JsonPrimitive)?.content

private fun json(text: String): JsonObject? = runCatching { JsonInstant.parseToJsonElement(text).jsonObject }.getOrNull()

/** Only real mutation receipts count. A proposal, tool absence, or text saying "saved" is not proof. */
fun memorySyncStatus(messages: List<UIMessage>, available: Boolean, headless: Boolean = false, finished: Boolean = false): String {
    if (headless) return "unsupported"
    val calls = messages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().filter { isMemoryTool(it.toolName) }
        .filter { json(it.input)?.value("action") in setOf("create", "edit", "delete", "append", "replace", "complete", "drop", "no_candidate") }
    if (!available && calls.isEmpty()) return "disabled"
    if (calls.any { it.isPending }) return if (finished) "interrupted" else "pending_approval"
    if (calls.any { it.approvalState is ToolApprovalState.Denied }) return "denied"
    if (calls.any { !it.isExecuted }) return if (finished) "failed" else "writing"
    val outputs = calls.map { tool -> tool.output.filterIsInstance<UIMessagePart.Text>().mapNotNull { json(it.text) } }
    if (outputs.flatten().any { it.value("proposal") == "true" && it.value("applied") == "false" }) return "proposed"
    if (outputs.flatten().any { it.value("applied") == "false" || it.value("stored") == "false" || it.value("success") == "false" }) return "failed"
    if (outputs.any { parts -> parts.isEmpty() || parts.none { it.value("memory_sync") in setOf("saved", "no_candidate") } }) return "failed"
    if (outputs.flatten().any { it.value("memory_sync") == "saved" }) return "saved"
    if (outputs.flatten().any { it.value("memory_sync") == "no_candidate" }) return "no_candidate"
    return "unassessed"
}


fun captureRunEvidence(runId: String, messages: List<UIMessage>, available: Boolean, deliverables: String? = null, finished: Boolean = true): RunEvidence {
    val scoped = messagesOfScheduledRun(messages, runId)
    val calls = scoped.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>().filter { isMemoryTool(it.toolName) }
    val receipts = buildJsonArray {
        calls.takeLast(64).forEach { call -> add(buildJsonObject {
            put("tool_call_id", call.toolCallId)
            call.memoryContext?.let { put("library", it) }
            put("action", json(call.input)?.value("action").orEmpty())
            put("pending", call.isPending)
            put("denied", call.approvalState is ToolApprovalState.Denied)
            // Persist identities/revisions rather than duplicating potentially private memory bodies.
            put("receipts", buildJsonArray { call.output.filterIsInstance<UIMessagePart.Text>().mapNotNull { json(it.text) }.forEach { value ->
                add(buildJsonObject { listOf("id", "path", "revision", "memory_sync", "success", "applied", "proposal", "stored").forEach { key -> value[key]?.let { put(key, it) } } })
            } })
        }) }
    }.toString()
    val final = scoped.lastOrNull { it.role == MessageRole.ASSISTANT && it.parts.any { p -> p is UIMessagePart.Text && p.text.isNotBlank() } }
        ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty()
    val summary = buildJsonObject {
        put("run_id", runId); put("kind", "bounded_run_snapshot")
        put("final_text", final.take(12_000)); put("truncated", final.length > 12_000)
        put("declared_deliverables", deliverables?.take(8_000).orEmpty())
        put("deliverables_verified", false)
        put("tool_calls", scoped.flatMap { it.parts }.count { it is UIMessagePart.Tool })
    }.toString()
    return RunEvidence(memorySyncStatus(scoped, available, finished = finished), summary, receipts)
}

fun safeToRemoveRunSource(memorySync: String, summary: String?): Boolean = summary != null &&
    memorySync in setOf("saved", "no_candidate", "disabled", "unsupported")
