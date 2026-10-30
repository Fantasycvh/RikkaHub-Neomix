package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation

/**
 * 会话选择器里的一「组」：同一助手下的一批会话。
 *
 * [assistant] 为 null 表示这些会话的助手已被删除（脏数据兜底，仍可选中/清空，不能静默吞掉）。
 */
internal data class ConversationGroup(
    val assistant: Assistant?,
    val conversations: List<Conversation>,
)

/**
 * 保存任务时「目标解析失败」的两种原因。
 *
 * 保存按钮的可用性已经按目标就绪与否门控过，能走到这里只可能是**竞态**：选择器打开时助手/会话
 * 还在，点保存前被删掉了；或者建会话的 DB 写入失败。出现时不能静默什么都不发生——
 * 用户会以为按钮坏了，所以回落到创建页顶部的错误行。
 */
enum class TargetFailure {
    CONVERSATION_MISSING,
    ASSISTANT_MISSING,
}

/**
 * 按助手给会话分组（选择器展示顺序 = 设置里助手顺序，未归属的排最后）。
 *
 * 纯函数，单测覆盖：会话→助手是会话自身的 `assistantId`，助手表可能落后（用户删了助手但
 * 会话行还在），所以「未知助手」必须是独立分组而不是丢行。
 */
internal fun groupConversationsByAssistant(
    conversations: List<Conversation>,
    assistants: List<Assistant>,
): List<ConversationGroup> {
    val known = assistants.mapTo(HashSet()) { it.id }
    val groups = assistants.mapNotNull { assistant ->
        conversations
            .filter { it.assistantId == assistant.id }
            .takeIf { it.isNotEmpty() }
            ?.let { ConversationGroup(assistant, it) }
    }
    val orphans = conversations.filter { it.assistantId !in known }
    return if (orphans.isEmpty()) groups else groups + ConversationGroup(null, orphans)
}

/**
 * 任务目标的用户可读描述（列表行与详情页共用，纯展示）。
 *
 * 三种形态各自说明「这轮会跑在哪儿」：已有会话给会话标题；专属会话给会话标题 + 形态后缀；
 * 每轮新建没有固定会话，给助手名（助手被删时显式写「助手已删除」而不是空串）。
 */
@Composable
internal fun targetDescription(
    task: ScheduledTaskEntity,
    conversationTitles: Map<String, String>,
    assistantNames: Map<String, String>,
): String {
    val assistantName = task.assistantId?.let { assistantNames[it] }
    return when (targetModeOrDefault(task.targetMode)) {
        TargetMode.EXISTING ->
            conversationTitles[task.conversationId]
                ?: stringResource(R.string.scheduled_task_conversation_missing)

        TargetMode.DEDICATED ->
            (conversationTitles[task.conversationId]
                ?: stringResource(R.string.scheduled_task_conversation_missing)) +
                " · " + stringResource(R.string.scheduled_task_target_dedicated)

        TargetMode.NEW_EACH_RUN ->
            stringResource(R.string.scheduled_task_target_new_each_run) + " · " +
                (assistantName ?: stringResource(R.string.scheduled_task_assistant_missing))
    }
}
