package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

const val AWAIT_BACKGROUND_TASKS = "await_background_tasks"

fun createAwaitBackgroundTasksTool(pendingTasks: suspend () -> List<String>) = Tool(
    name = AWAIT_BACKGROUND_TASKS,
    description = "Finish this reply and release the conversation while already-started background commands or subagents work. " +
        "First start all independent tasks, do any independent work, and briefly tell the user what is pending. " +
        "Then call this tool instead of polling or sleeping. Task completion will resume you automatically when idle. " +
        "Do not call it without pending tasks, and do not redispatch accepted work.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    endTurnOnSuccess = true,
    execute = {
        val tasks = pendingTasks()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("status", if (tasks.isEmpty()) "ready" else "waiting")
            putJsonArray("tasks") { tasks.forEach { add(it) } }
            put("note", if (tasks.isEmpty()) "No tasks are still pending. Process available results and continue; do not wait or poll."
                else "This turn is finished. Background work continues; completion events will resume processing when idle.")
        }.toString(), metadata = if (tasks.isEmpty()) buildJsonObject { put("runtimeContinueTurn", true) } else null))
    },
)
