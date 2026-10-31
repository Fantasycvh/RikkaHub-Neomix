package app.androidrik.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.app.NotificationCompat
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.sendNotification
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent.getKoin
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 管理任务（Yuihub 形态，`docs/design/design-proactive-task-management.md` §三）：
 * 单工具 **action 分档**（与 memory_tool 同款惯用法），让助手在对话里管理**本助手名下**的
 * 定时任务。
 *
 * 三条红线（§三.1，[taskManageVerdict] 纯函数判定、单测钉死）：
 * 1. 助手只能看到/操作 `assistantId` = 自己的任务；
 * 2. 目标模式只允许 DEDICATED / NEW_EACH_RUN（会话默认隐藏），**EXISTING 不开放**；
 * 3. AI **不可删** user 建的任务——不是审批，是直接拒绝（审批是给人犹豫用的，不是给越权用的）。
 *
 * 双层开关：能力层 [Assistant.taskManageEnabled] 关 ⇒ 工具不装配（ChatToolFactory）；
 * 动作层 create/delete/modify 各自的审批开关默认全开（[taskManageNeedsApproval]）。
 * 每次 AI 动作成功后发一条轻量通知（§三.2「AI 在排我的任务」必须随时可见）。
 */
internal fun taskManageNeedsApproval(assistant: Assistant, action: String): Boolean = when (action) {
    "create" -> assistant.taskCreateApproval
    "delete" -> assistant.taskDeleteApproval
    "enable", "disable" -> assistant.taskModifyApproval
    // 只读不拦（与 memory_tool 的 read/search 同口径）
    "list" -> false
    // 未知 action 宁可多拦
    else -> assistant.taskModifyApproval
}

/**
 * 越权判定（纯函数）：返回 null = 允许；否则返回拒绝原因串（原样回给模型）。
 * [action] 只需处理「作用于既有任务」的四个（list/create 在调用侧直接分流）。
 */
internal fun taskManageVerdict(
    action: String,
    task: ScheduledTaskEntity?,
    assistantId: String,
): String? = when {
    task == null -> "not_found"
    task.assistantId != assistantId -> "foreign_assistant"
    action == "delete" && task.createdBy != ScheduledTaskEntity.CREATED_BY_AI -> "user_task_delete_denied"
    else -> null
}

/** 装配 `task_manage` 工具；[sourceConversationId] 记 AI 建任务的发起会话（审计溯源）。 */
fun buildTaskManageTool(
    assistant: Assistant,
    sourceConversationId: String?,
): Tool {
    val assistantId = assistant.id.toString()
    return Tool(
        name = "task_manage",
        description = """
            Manage the scheduled tasks (cron-like AI tasks) that belong to YOU (the current assistant).
            Actions: "list" (your tasks with status), "create" (schedule a recurring task for yourself),
            "enable"/"disable", "delete" (only tasks you created).
            Created tasks run headless in a hidden conversation; results arrive as notifications.
            Use "list" before "create" to avoid duplicates.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("list"); add("create"); add("enable"); add("disable"); add("delete")
                        })
                        put("description", "Which management action to perform")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "Task id (required for enable/disable/delete)")
                    })
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Short task name (create)")
                    })
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "The instruction injected at each run (create)")
                    })
                    put("schedule_type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add("DAILY"); add("WEEKLY"); add("MONTHLY"); add("INTERVAL"); add("ONCE")
                        })
                        put("description", "Schedule shape (create)")
                    })
                    put("hour", buildJsonObject {
                        put("type", "integer"); put("description", "0-23 (create, except INTERVAL)")
                    })
                    put("minute", buildJsonObject {
                        put("type", "integer"); put("description", "0-59 (create, except INTERVAL)")
                    })
                    put("day_of_week", buildJsonObject {
                        put("type", "integer"); put("description", "1=Monday..7=Sunday (WEEKLY)")
                    })
                    put("day_of_month", buildJsonObject {
                        put("type", "integer"); put("description", "1-31, clamped to month end (MONTHLY)")
                    })
                    put("interval_minutes", buildJsonObject {
                        put("type", "integer"); put("description", ">=15 (INTERVAL)")
                    })
                    put("run_at_date", buildJsonObject {
                        put("type", "string"); put("description", "ISO date yyyy-MM-dd (ONCE)")
                    })
                    put("miss_policy", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("RUN_LATE"); add("SKIP") })
                        put("description", "What to do when a slot is missed (default RUN_LATE)")
                    })
                    put("target_mode", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("NEW_EACH_RUN"); add("DEDICATED") })
                        put("description", "NEW_EACH_RUN: fresh hidden conversation per run (default). DEDICATED: one persistent hidden conversation.")
                    })
                    put("deliverables", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Workspace-relative file paths that must exist (non-empty) before a run may end; empty = no gate")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            val action = runCatching { input.jsonObject["action"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            taskManageNeedsApproval(assistant, action)
        },
        execute = { input ->
            val params = input.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val koin = getKoin()
            val repo = koin.get<ScheduledTaskRepository>()
            val context = koin.get<Context>()
            when (action) {
                "list" -> {
                    val tasks = repo.allTasks().filter { it.assistantId == assistantId }
                    val payload = buildJsonArray {
                        tasks.forEach { task ->
                            add(buildJsonObject {
                                put("id", task.id)
                                put("title", task.title)
                                put("schedule_type", task.scheduleType)
                                put("enabled", task.enabled)
                                put("next_run_at", task.nextRunAt)
                                put("last_status", task.lastStatus)
                                put("created_by", task.createdBy)
                            })
                        }
                    }
                    listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
                }

                "create" -> {
                    val invalid = validateCreateParams(params)
                    if (invalid != null) {
                        listOf(UIMessagePart.Text(invalid))
                    } else {
                        val task = createTask(repo, context, params, assistant, sourceConversationId)
                        notifyAction(context, "created", task.title)
                        val receipt = buildJsonObject {
                            put("ok", true)
                            put("id", task.id)
                            put("title", task.title)
                            put("target_mode", task.targetMode)
                            put("next_run_at", task.nextRunAt)
                        }
                        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(receipt)))
                    }
                }

                "enable", "disable" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                    val task = repo.byId(id)
                    val verdict = taskManageVerdict(action, task, assistantId)
                    if (verdict != null) {
                        listOf(UIMessagePart.Text("""{"ok":false,"error":"$verdict"}"""))
                    } else {
                        repo.setEnabled(id, enabled = action == "enable")
                        notifyAction(context, action, task!!.title)
                        listOf(UIMessagePart.Text("""{"ok":true,"id":"$id","enabled":${action == "enable"}}"""))
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                    val task = repo.byId(id)
                    val verdict = taskManageVerdict(action, task, assistantId)
                    if (verdict != null) {
                        listOf(UIMessagePart.Text("""{"ok":false,"error":"$verdict"}"""))
                    } else {
                        repo.delete(id)
                        notifyAction(context, "deleted", task!!.title)
                        listOf(UIMessagePart.Text("""{"ok":true,"id":"$id"}"""))
                    }
                }

                else -> listOf(
                    UIMessagePart.Text("""{"ok":false,"error":"unknown_action","action":"$action"}"""),
                )
            }
        },
    )
}

private fun kotlinx.serialization.json.JsonElement.contentOrNullSafe(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()

/** create 参数校验（镜像表单同一套判据）；null = 通过，否则返回错误回执 JSON。 */
private fun validateCreateParams(params: kotlinx.serialization.json.JsonObject): String? {
    val title = params["title"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    val prompt = params["prompt"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    val type = params["schedule_type"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
    val hour = params["hour"]?.jsonPrimitive?.intOrNull
    val minute = params["minute"]?.jsonPrimitive?.intOrNull
    if (title.isBlank() || prompt.isBlank()) {
        return """{"ok":false,"error":"title_and_prompt_required"}"""
    }
    val scheduleType = runCatching { ScheduleType.valueOf(type) }.getOrNull()
        ?: return """{"ok":false,"error":"bad_schedule_type"}"""
    if (scheduleType != ScheduleType.INTERVAL) {
        if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) {
            return """{"ok":false,"error":"bad_time"}"""
        }
    }
    if (scheduleType == ScheduleType.INTERVAL &&
        (params["interval_minutes"]?.jsonPrimitive?.longOrNull ?: 0L) < 15
    ) {
        return """{"ok":false,"error":"interval_too_short"}"""
    }
    return null
}

private suspend fun createTask(
    repo: ScheduledTaskRepository,
    context: Context,
    params: kotlinx.serialization.json.JsonObject,
    assistant: Assistant,
    sourceConversationId: String?,
): ScheduledTaskEntity {
    val title = params["title"]!!.jsonPrimitive.content.trim()
    val prompt = params["prompt"]!!.jsonPrimitive.content.trim()
    val scheduleType = ScheduleType.valueOf(params["schedule_type"]!!.jsonPrimitive.content)
    val hour = params["hour"]?.jsonPrimitive?.intOrNull ?: 8
    val minute = params["minute"]?.jsonPrimitive?.intOrNull ?: 0
    val payload = SchedulePayload(
        hour = hour,
        minute = minute,
        dayOfWeek = params["day_of_week"]?.jsonPrimitive?.intOrNull ?: 1,
        dayOfMonth = params["day_of_month"]?.jsonPrimitive?.intOrNull ?: 1,
        intervalMinutes = params["interval_minutes"]?.jsonPrimitive?.longOrNull ?: 240L,
        runAt = params["run_at_date"]?.jsonPrimitive?.contentOrNullSafe()
            ?.let { raw ->
                runCatching {
                    LocalDate.parse(raw).atTime(hour, minute)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            ?: 0L,
        extraMinutesOfDay = emptyList(),
    )
    val missPolicy = params["miss_policy"]?.jsonPrimitive?.contentOrNullSafe()
        ?.let { runCatching { MissPolicy.valueOf(it) }.getOrNull() } ?: MissPolicy.RUN_LATE
    // 目标模式白名单：EXISTING 不开放（AI 无权把定时注入指到任意既有会话）。
    // DEDICATED 需要现在就建一条隐藏专属会话——走 ChatService（Koin 调用时解析，
    // 与 WorkspaceTools 同款逃生舱，避免构造环）。
    val targetMode = params["target_mode"]?.jsonPrimitive?.contentOrNullSafe()
        ?.let { raw ->
            runCatching { TargetMode.valueOf(raw) }.getOrNull()
        }
        // 白名单之外（含 EXISTING / 脏值）一律回落 NEW_EACH_RUN
        ?.takeIf { it == TargetMode.DEDICATED || it == TargetMode.NEW_EACH_RUN }
        ?: TargetMode.NEW_EACH_RUN
    val deliverables = params["deliverables"]?.jsonArray
        ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
        ?.mapNotNull(DeliverableCheckerAdapter::normalize)
        ?.takeIf { it.isNotEmpty() }

    val conversationId = when (targetMode) {
        TargetMode.DEDICATED -> {
            val chatService = getKoin().get<app.androidrik.rikkahub.service.ChatService>()
            chatService.createTaskConversation(
                assistantId = assistant.id,
                title = title,
            )?.toString() ?: ""
        }
        else -> ""
    }

    return repo.create(
        conversationId = conversationId,
        targetMode = targetMode,
        assistantId = assistant.id.toString(),
        title = title,
        prompt = prompt,
        scheduleType = scheduleType,
        payload = payload,
        missPolicy = missPolicy,
        deliverables = deliverables?.let { app.androidrik.rikkahub.utils.JsonInstant.encodeToString(it) },
        createdBy = ScheduledTaskEntity.CREATED_BY_AI,
        sourceConversationId = sourceConversationId,
    )
}

/** [app.androidrik.rikkahub.data.ai.DeliverableChecker] 的静态引用桥（避免顶层 import 撞名）。 */
private object DeliverableCheckerAdapter {
    fun normalize(raw: String): String? =
        app.androidrik.rikkahub.data.ai.DeliverableChecker.normalizeRelative(raw)
}

/** 每次 AI 动作成功后的轻量通知（§三.2）：「AI 在排我的任务」必须随时可见。 */
private fun notifyAction(context: Context, action: String, taskTitle: String) {
    runCatching {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = ("task-manage-$action-$taskTitle").hashCode() and 0x3FFF or 0x4000,
        ) {
            title = context.getString(R.string.task_manage_notif_title)
            content = context.getString(R.string.task_manage_notif_content, action, taskTitle)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
        }
    }
}
