package app.androidrik.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation

const val TODO_TOOL_NAME = "todo_write"

/** 单个待办的状态 */
const val TODO_STATUS_PENDING = "pending"
const val TODO_STATUS_IN_PROGRESS = "in_progress"
const val TODO_STATUS_COMPLETED = "completed"
private val TODO_STATUSES = listOf(TODO_STATUS_PENDING, TODO_STATUS_IN_PROGRESS, TODO_STATUS_COMPLETED)

/** 计划条目（已归一化：content 非空、status 合法） */
@Serializable
data class TodoEntry(val content: String, val status: String)

/**
 * 计划工具（Claude Code / Cursor 风格的 TodoWrite；借鉴 Yuihub `ab107549`）。
 *
 * 模型把多步任务拆成显式清单并持续更新，UI 用输入栏上方的常驻计划条渲染进度，
 * 让长任务的过程可见；同时引导模型先规划再动手，减少跑偏。
 *
 * 工具本身无副作用（不落库）：最新一次调用的参数就是当前清单，
 * 随消息历史一起持久化；计划条从会话里取「最后一次调用的参数」派生。
 */
fun buildTodoWriteTool(): Tool = Tool(
    name = TODO_TOOL_NAME,
    description = """
        Create or update the task plan for the current work. Use it to break a multi-step task into
        an explicit checklist and keep it current as you make progress.

        Rules:
        - Call it BEFORE starting a multi-step task (3+ distinct steps), and again every time a step's
          status changes. Skip it for simple single-step requests.
        - Always pass the FULL list (not just the changed items); previously sent items must be repeated.
        - Exactly one item may be "in_progress" at a time; mark an item "completed" only when it is
          truly done (tests passing, file written, etc.).
        - Keep items short, actionable, and starting with a verb (e.g. "Add DB migration").
        - Do not mention this tool or the plan bookkeeping in your final answer to the user; the UI
          already shows the checklist.
    """.trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("todos", buildJsonObject {
                    put("type", "array")
                    put("description", "The complete todo list; replaces any previous list")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Short imperative description of the step")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { TODO_STATUSES.forEach { add(it) } })
                                put("description", "pending | in_progress | completed")
                            })
                        })
                        put("required", buildJsonArray {
                            add("content")
                            add("status")
                        })
                    })
                })
            },
            required = listOf("todos"),
        )
    },
    execute = { args ->
        val todos = args.jsonObject["todos"] as? JsonArray
            ?: error("todos is required and must be an array")
        val normalized = todos.mapIndexed { index, element ->
            val item = element.jsonObject
            val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (content.isBlank()) error("todos[$index].content must not be blank")
            val status = item["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (status !in TODO_STATUSES) {
                error("todos[$index].status must be one of ${TODO_STATUSES.joinToString()}")
            }
            buildJsonObject {
                put("content", content)
                put("status", status)
            }
        }
        val inProgressCount = normalized.count {
            it["status"]?.jsonPrimitive?.contentOrNull == TODO_STATUS_IN_PROGRESS
        }
        // 下一步指引（Yuihub 3.0.0 同款）：弱模型常常只建清单、不再回来更新，
        // 把下一步该做什么直接回给结果 JSON，治「建完清单不复更」
        val inProgressItem = normalized.firstOrNull {
            it["status"]?.jsonPrimitive?.contentOrNull == TODO_STATUS_IN_PROGRESS
        }?.get("content")?.jsonPrimitive?.contentOrNull
        val pendingItem = normalized.firstOrNull {
            it["status"]?.jsonPrimitive?.contentOrNull == TODO_STATUS_PENDING
        }?.get("content")?.jsonPrimitive?.contentOrNull
        val payload = buildJsonObject {
            put("todos", JsonArray(normalized))
            when {
                inProgressCount > 1 -> {
                    // 不报错：模型偶发多个 in_progress 时仍让它继续，但给出提示便于自我纠正
                    put("warning", "More than one item is in_progress ($inProgressCount). Keep exactly one.")
                }

                inProgressCount == 0 && pendingItem != null -> {
                    put("warning", "No item is in_progress. Mark the step you are about to start as in_progress.")
                }
            }
            put(
                "next",
                when {
                    inProgressItem != null ->
                        "Working on \"$inProgressItem\". Call todo_write again the moment it is done: " +
                            "mark it completed and the next step in_progress."

                    pendingItem != null ->
                        "Call todo_write again before starting \"$pendingItem\": mark it in_progress."

                    else -> "All steps are completed."
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

/** 从工具调用的 input 字符串解析计划条目；畸形输入返回 null（不抛，派生路径要容错）。 */
fun parseTodoPlanArgs(input: String): List<TodoEntry>? {
    val todos = runCatching {
        Json.parseToJsonElement(input.ifBlank { "{}" }).jsonObject["todos"]?.jsonArray
    }.getOrNull() ?: return null
    val entries = todos.mapNotNull { element ->
        runCatching {
            val item = element.jsonObject
            val content = item["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val status = item["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (content.isNotBlank() && status in TODO_STATUSES) TodoEntry(content, status) else null
        }.getOrNull()
    }
    return entries.ifEmpty { null }
}

/**
 * 派生当前会话的计划：取当前分支里**最后一次** `todo_write` 调用的参数
 * （借鉴 Yuihub 最终形态——计划不渲染进消息正文，输入栏上方的计划条是唯一展示面）。
 * 无调用或参数畸形时返回空列表（计划条不显示）。
 */
fun latestTodoPlan(conversation: Conversation): List<TodoEntry> =
    latestTodoPlan(conversation.currentMessages)

/** 同上，但直接吃消息列表：`GenerationHandler` 手里只有 messages、拿不到 Conversation。 */
fun latestTodoPlan(messages: List<UIMessage>): List<TodoEntry> {
    var latest: List<TodoEntry>? = null
    messages.forEach { message: UIMessage ->
        message.parts.forEach { part ->
            if (part is UIMessagePart.Tool && part.toolName == TODO_TOOL_NAME) {
                parseTodoPlanArgs(part.input)?.let { latest = it }
            }
        }
    }
    return latest.orEmpty()
}

/**
 * 计划是否**仍在进行**（还有未完成项）。
 *
 * 规则提醒用它而不是"计划非空"：全完成的旧计划不算"有计划" —— 否则同一会话里开新任务时
 * 那条 `plan_missing` 永远会被上一轮做完的清单压住（实测过的坑：拿残留计划当挡箭牌）。
 */
fun List<TodoEntry>.hasUnfinishedItems(): Boolean = any { it.status != TODO_STATUS_COMPLETED }
