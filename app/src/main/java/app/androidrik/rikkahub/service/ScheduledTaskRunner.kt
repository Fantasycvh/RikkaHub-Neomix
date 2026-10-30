package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.conversationIntent
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.utils.sendNotification
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定时任务的执行层（docs/design/design-scheduled-ai-tasks.md §六）。
 *
 * 只做「注入 + 等结束 + 报结果」，生成本体走 [ChatService] 老管线——transformer、工具、
 * MCP、流式落库全部复用，这里没有任何一条旁路生成通道。
 *
 * 后台入口的两个**冷启动**前提（2026-10-30 真机事故的根因，缺一不可）：
 * 1. [SettingsStore.awaitLoaded]：`settingsFlow` 起手是 `Settings.dummy()`，进程刚被
 *    WorkManager 拉起时直接注入会拿到空 providers / 随机 chatModelId ⇒ 生成侧解析不到模型；
 * 2. [ChatService.ensureConversationLoaded]：不先载入 DB 会话就发消息，会在一个空
 *    `Conversation.ofId` 上跑一轮，随后 `saveConversation` 把目标会话整行覆盖。
 */
class ScheduledTaskRunner(
    private val context: Context,
    private val chatService: ChatService,
    private val conversationRepository: ConversationRepository,
    private val taskRepository: ScheduledTaskRepository,
    private val settingsStore: SettingsStore,
) {
    /**
     * 执行一个**已认领**的任务，返回落 `last_status` 的状态值。
     *
     * 完成判定：`job == null && 队列空`。不能等 `generationDoneFlow`——它的 emit 全部在生成
     * job 体**内**发出（`ChatService.kt:956` 等五处），等信号时 job 还没结束，「emit + job 空闲」
     * 永远凑不齐。而「发送后 job 立即非空或队列非空」（[ChatService.sendMessage] 同步入队 +
     * 派发），所以首个观测必然是活跃态，不会把「还没开始」误判成「已结束」。
     *
     * 已知边界：任务消息排在用户当前轮次之后（FIFO），若前面排着长队，[GENERATION_TIMEOUT]
     * 从注入时刻起算，可能不够等到自己的轮次——此时记 [TaskRunStatus.TIMEOUT] 但**不取消**
     * 生成（消息还在队列里正常走完，只是不再等/不再报状态）。
     */
    suspend fun runClaimed(task: ScheduledTaskEntity): String {
        // 冷启动前提 1：等真实设置就位（超时兜底——读不出来也不能把 worker 挂死；
        // 此时下游会以「未配置模型」的可见错误收尾，状态记 FAILED）。
        // 放在目标解析之前：NEW_EACH_RUN 建会话就要用 settings 里的助手（含预设消息）。
        val settingsLoaded = withTimeoutOrNull(SETTINGS_AWAIT_TIMEOUT) {
            settingsStore.awaitLoaded()
        } != null

        val mode = targetModeOrDefault(task.targetMode)
        val conversationId = when (mode) {
            // 每轮新建：没有存量锚点，本轮现建一条（按选中助手）
            TargetMode.NEW_EACH_RUN -> {
                val assistantId = task.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                val created = assistantId?.let {
                    chatService.createTaskConversation(
                        assistantId = it,
                        title = scheduledRunConversationTitle(task.title, System.currentTimeMillis()),
                    )
                }
                if (created == null) {
                    taskRepository.markAssistantGone(task.id)
                    return TaskRunStatus.ASSISTANT_GONE
                }
                taskRepository.recordLastConversation(task.id, created.toString())
                created
            }
            // 已有会话 / 专属会话：都是存量锚点，会话被删即失效
            TargetMode.EXISTING, TargetMode.DEDICATED -> {
                val parsed = runCatching { Uuid.parse(task.conversationId) }.getOrNull()
                if (parsed == null || !conversationRepository.existsConversationById(parsed)) {
                    taskRepository.markTargetGone(task.id)
                    return TaskRunStatus.TARGET_GONE
                }
                parsed
            }
        }

        // 冷启动前提 2：后台没有 session，必须先按 DB 载入会话（历史/助手/工作区），
        // 否则注入建立在一个空 Conversation 上，落库会覆盖整行会话。
        if (!chatService.ensureConversationLoaded(conversationId)) {
            taskRepository.markTargetGone(task.id)
            return TaskRunStatus.TARGET_GONE
        }

        val assistantCountBefore = assistantMessageCount(conversationId)
        val errorsBefore = chatService.errors.value.count { it.conversationId == conversationId }
        Log.i(
            TAG,
            "runClaimed: inject task=${task.title} conversation=$conversationId " +
                "(settingsLoaded=$settingsLoaded, assistantsSeen=$assistantCountBefore)",
        )
        // 目标语义（purrcat A4.2）：withGoal 且会话 goal 非空 → 头部拼「长期目标」，
        // 多次自唤醒围绕同一目标；goal 为空按普通 prompt 走，不报错（§六.2）。
        // 会话已由 ensureConversationLoaded 载入，直接读内存态的 goal。
        val conversationGoal = chatService.getConversationFlow(conversationId).value.goal
        chatService.sendMessage(
            conversationId,
            listOf(UIMessagePart.Text(buildTaskPrompt(task.prompt, task.withGoal, conversationGoal))),
        )

        // 等待结束期间打心跳：若进程被 ROM 的 cached-app freezer 冻住，日志会断一条明显的
        // 空档、回前台后继续——这是「后台不回复、切前台才续上」的判据（2026-10-30 真机）。
        val waitStartedAt = System.currentTimeMillis()
        val quiesced = coroutineScope {
            val heartbeat = launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL)
                    Log.d(TAG, "runClaimed: waiting ${System.currentTimeMillis() - waitStartedAt}ms")
                }
            }
            val done = withTimeoutOrNull(GENERATION_TIMEOUT.inWholeMilliseconds) {
                combine(
                    chatService.getGenerationJobStateFlow(conversationId),
                    chatService.getMessageQueueFlow(conversationId),
                ) { job, queue -> job == null && queue.messages.isEmpty() }
                    .first { it }
            } != null
            heartbeat.cancel()
            done
        }

        val hasNewError = chatService.errors.value.count { it.conversationId == conversationId } > errorsBefore
        val producedReply = assistantMessageCount(conversationId) > assistantCountBefore
        val status = evaluateRunStatus(
            quiesced = quiesced,
            hasNewError = hasNewError,
            producedReply = producedReply,
        )
        Log.i(
            TAG,
            "runClaimed: task=${task.title} -> $status (quiesced=$quiesced, hasNewError=$hasNewError, " +
                "producedReply=$producedReply)",
        )
        taskRepository.recordStatus(task.id, status)
        notifyResult(task, conversationId, status)
        return status
    }

    private fun assistantMessageCount(conversationId: Uuid): Int =
        chatService.getConversationFlow(conversationId).value.currentMessages
            .count { it.role == MessageRole.ASSISTANT }

    private fun notifyResult(task: ScheduledTaskEntity, conversationId: Uuid, status: String) {
        val (titleRes, contentRes) = when (status) {
            TaskRunStatus.OK ->
                R.string.scheduled_task_notif_done_title to R.string.scheduled_task_notif_done_content

            TaskRunStatus.TIMEOUT ->
                R.string.scheduled_task_notif_timeout_title to R.string.scheduled_task_notif_timeout_content

            else ->
                R.string.scheduled_task_notif_failed_title to R.string.scheduled_task_notif_failed_content
        }
        val contentText = context.getString(contentRes, task.title)
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = task.id.hashCode() + NOTIFICATION_ID_OFFSET,
        ) {
            title = context.getString(titleRes)
            content = contentText
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = PendingIntent.getActivity(
                context,
                task.id.hashCode(),
                conversationIntent(context, task.conversationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskRunner"

        /** 等待生成的上限；超时按失败记但不取消生成（设计稿 §六） */
        val GENERATION_TIMEOUT = 10.minutes

        /** 冷启动等设置落盘的上限（与 `ChatService.SETTINGS_AWAIT_TIMEOUT` 同量级） */
        val SETTINGS_AWAIT_TIMEOUT = 30.seconds

        /** 等待生成期间的诊断心跳间隔（debug 级日志；只用于排查「进程被冻」类问题） */
        val HEARTBEAT_INTERVAL = 30.seconds

        /** 与 ChatNotificationManager 的会话通知 id（+10000）错开 */
        const val NOTIFICATION_ID_OFFSET = 30000
    }
}

/**
 * 「每轮新建」产出的会话标题：任务名 + 触发时刻（同一任务多轮产生的会话在列表里可区分）。
 *
 * 纯函数，单测覆盖（时区与格式不属于业务判断，所以参数化时区）。
 */
internal fun scheduledRunConversationTitle(
    taskTitle: String,
    atMillis: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val stamp = java.time.Instant.ofEpochMilli(atMillis)
        .atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    return if (taskTitle.isBlank()) stamp else "$taskTitle · $stamp"
}

/**
 * 定时任务执行结果的判定（纯函数，单测覆盖「静默收场不能记 OK」）。
 *
 * `OK` 必须同时满足三件事：生成**结束**了、没有新增错误、**确实产出了新的 assistant 消息**。
 * 第三条是 2026-10-30 真机事故的补丁：`ChatService.handleMessageComplete` 曾有一条
 * 「模型解析失败 → 静默 return」的分支，job 秒结、无错误、队列空 ⇒ 旧判定记 OK，用户看到的
 * 是「后台触发了，但没有回复」，而且没有任何可诊断线索。把「产出」当必要证据后，任何
 * 静默收场都会落到 FAILED（并带失败通知），配合 handleMessageComplete 的显式报错完成闭环。
 */
internal fun evaluateRunStatus(
    quiesced: Boolean,
    hasNewError: Boolean,
    producedReply: Boolean,
): String = when {
    !quiesced -> TaskRunStatus.TIMEOUT
    hasNewError -> TaskRunStatus.FAILED
    !producedReply -> TaskRunStatus.FAILED
    else -> TaskRunStatus.OK
}

/**
 * 注入 prompt 的组装（纯函数，单测覆盖）：
 * `withGoal = true` 且会话目标非空 → `## 长期目标\n{goal}\n\n{prompt}`；
 * 开关没开、或会话没写目标（含 NEW_EACH_RUN 每轮新建的空会话）→ 原样返回，不报错。
 */
internal fun buildTaskPrompt(prompt: String, withGoal: Boolean, goal: String?): String {
    if (!withGoal || goal.isNullOrBlank()) return prompt
    return "## 长期目标\n$goal\n\n$prompt"
}
