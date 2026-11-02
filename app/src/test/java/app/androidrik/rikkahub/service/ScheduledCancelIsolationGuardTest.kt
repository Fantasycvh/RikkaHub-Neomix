package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务取消的**隔离**接线守卫（P0.5，design-agent-task-runtime §5.2 第 4 条）。
 *
 * 取消链最容易被「顺手复用现成方法」破坏的点：`ChatService.stopGeneration` 会
 * `messageQueue.pause()`，把目标会话里**用户自己的**待发消息一起冻结。定时任务的停止
 * 入口只能取消「本轮注入的 generation Job」，不能走那条路。
 *
 * 行为层证伪在 [ScheduledRunRegistryTest]（先取消后 attach、重复取消、反注册）与
 * `ChatService` 的队列入参测试；这里钉住跨文件的三段接线，防止后续重构把语义改回去：
 * 1. 定时注入时把 `sched:<runId>` 带到队列项（否则取消入口只有 runId、反查不到这一轮）；
 * 2. 取消只经注册表 `Job.cancel()`，源码里不得出现 `stopGeneration` / `messageQueue`；
 * 3. FGS 通知的停止动作与 `ACTION_STOP` 必须真的接到注册表。
 */
class ScheduledCancelIsolationGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun source(relative: String): String = locate(relative, "app/$relative")
        .readText()
        .replace("\r\n", "\n")
        // 断言只针对**代码**：KDoc 里解释「为什么不用 stopGeneration / messageQueue.pause」
        // 是合规的，不能因为注释提到就误报。先剥掉块注释与行注释。
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    private val runner get() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunner.kt")
    private val registry get() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledRunRegistry.kt")
    private val runService get() = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunService.kt")
    private val chatService get() = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")
    private val session get() = source("src/main/java/app/androidrik/rikkahub/service/ConversationSession.kt")

    @Test
    fun `scheduled injection carries its own turn owner`() {
        assertTrue(
            "注入消息必须带 sched:<runId>，否则取消入口拿不到这一轮的归属",
            "scheduledRunTurnOwner(runId)" in runner,
        )
        assertTrue(
            "ChatService.sendMessage 调用必须把 turnOwner 传下去",
            "turnOwner = turnOwner," in runner,
        )
        assertTrue(
            "排队场景：取消时撤下本轮尚未派发的队列项，否则用户点了停止它仍会烧一轮",
            "removeQueuedMessagesOfTurn(conversationId, turnOwner)" in runner,
        )
        assertTrue(
            "等待循环必须把取消信号并进 combine，才能不烧钱地提前收场",
            "runRegistry.cancelledRunIds" in runner,
        )
        assertTrue("取消终态必须独立记账，不能回落 OK/FAILED", "finishCancelled(task, runId)" in runner)
    }

    @Test
    fun `queue dispatch applies the queued turn owner`() {
        assertTrue(
            "sendQueuedMessage 必须在 beginNewTurn 时用队列项携带的归属",
            "session.beginNewTurn(queued.turnOwner)" in chatService,
        )
        assertTrue(
            "beginNewTurn 只在显式令牌非空白时覆盖，用户路径（null）必须换新随机令牌",
            "forcedTurnOwner?.takeIf { it.isNotBlank() } ?: newTurnToken()" in session,
        )
    }

    @Test
    fun `cancellation goes through the job registry and never pauses the user queue`() {
        // 注册表只持有 Job，不调用 ChatService：取消绝不能顺带 pause 用户消息队列。
        // 注意断言的是**调用形态**（`chatService.` / `messageQueue.` / `stopGeneration(`），
        // KDoc 里解释「为什么不用 stopGeneration」不算违规。
        assertFalse(
            "ScheduledRunRegistry 不得调用 ChatService（禁止走 stopGeneration 语义）",
            "chatService." in registry,
        )
        assertFalse(
            "ScheduledRunRegistry 不得暂停用户队列（KDoc 可以解释，调用不行）",
            "messageQueue.pause(" in registry,
        )
        assertFalse(
            "定时取消路径不得调用 stopGeneration（它会 pause 用户队列）",
            "chatService.stopGeneration(" in runner,
        )
        assertTrue(
            "取消必须落到注册表的 Job.cancel 路径",
            "runRegistry.cancel(" in runService,
        )
        assertTrue(
            "ACTION_STOP 必须由 onStartCommand 处理（通知动作的唯一入口）",
            "intent?.action == ACTION_STOP" in runService,
        )
        assertTrue(
            "FGS 通知必须挂「停止」动作；否则用户没有取消入口",
            "ACTION_STOP" in runService && "PendingIntent.getService" in runService,
        )
    }
}
