package app.androidrik.rikkahub.service

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.tools.SUBAGENT_DISPATCH_TOOL_NAME
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.androidrik.rikkahub.utils.JsonInstant

/** Only a started subagent receives a background receipt; unstarted tools are still cancelled. */
internal fun retainedSubagentToolResult(tool: UIMessagePart.Tool, runIds: List<String>): UIMessagePart.Tool? {
    if (tool.toolName != SUBAGENT_DISPATCH_TOOL_NAME || runIds.isEmpty()) return null
    val result = buildJsonObject {
        put("status", "background")
        putJsonArray("run_ids") { runIds.forEach { add(it) } }
        put("note", "The user retained this task and sent a new message. The original task continues; its result will arrive later. Do not redispatch it or replace the newer user's request with its goal.")
    }
    return tool.copy(output = listOf(UIMessagePart.Text(result.toString())))
}

internal fun retainedWorkspaceToolResult(
    tool: UIMessagePart.Tool,
    tasks: List<app.androidrik.workspace.WorkspaceTaskSnapshot>,
): UIMessagePart.Tool? {
    if (tool.toolName != "workspace_run_background") return null
    val command = runCatching {
        (JsonInstant.parseToJsonElement(tool.input) as? JsonObject)?.get("command") as? JsonPrimitive
    }.getOrNull()?.content ?: return null
    val task = tasks.lastOrNull { it.command == command && it.noticeEventId != null } ?: return null
    val result = buildJsonObject {
        put("taskId", task.id)
        put("completionEventId", task.noticeEventId)
        put("status", task.status.name)
        put("note", "Task retained in the background. Completion will be delivered later; do not launch it again.")
    }
    return tool.copy(output = listOf(UIMessagePart.Text(result.toString())))
}
