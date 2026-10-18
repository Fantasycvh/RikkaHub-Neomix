package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「轮」边界的**接线守卫**：取消一轮生成时，这一轮起的后台任务必须被一起收掉。
 *
 * 这条链跨三个文件、四步，每一步单独看都「看起来正常」，而漏一处的后果是**静默的**
 * （进程继续在设备上跑到 30 分钟看门狗，模型拿不到结果、用户也不知道）：
 *
 * 1. `WorkspaceTaskRegistry.start(context, owner)` 在**启动那一刻**记下归属
 *    （不能靠工具结果里的 taskId：进程已启动、结果还没回填就被取消的窗口里没有 id）；
 * 2. `ChatToolFactory` → `createRunBackgroundTool` 把本轮令牌一路传到仓库；
 * 3. `ConversationSession.setJob` 在**注册时**捕获令牌、并且只在**取消**时回调——
 *    取消回调是唯一同时覆盖「点停止 / 插队 / 重新生成 / 继续生成 / 顶掉前任 / 清理会话」
 *    且发生在「确实停了之后」的挂点；
 * 4. `ChatService` 的 `onTurnCancelled` 落到 `killBackgroundTasksByOwner`。
 *
 * 行为层能证伪的是 3 的「取消才回调 + 令牌在注册时捕获」（`ConversationTurnCancellationTest`）
 * 与 1 的归属过滤（`WorkspaceBackgroundTasksTest`）；2 与 4 的接线用源码钉住。
 */
class TurnBackgroundTaskCancelGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun source(relative: String): String = locate(relative, "app/$relative")
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `the turn token reaches the background launcher`() {
        val tools = source("src/main/java/app/androidrik/rikkahub/data/ai/tools/WorkspaceTools.kt")
        assertTrue(
            "workspace_run_background 必须把轮次归属传给仓库，否则任务没有 owner、取消时收不掉",
            "startBackgroundCommand(workspaceId, command, cwd, owner = turnOwner)" in tools,
        )

        val factory = source("src/main/java/app/androidrik/rikkahub/data/ai/tools/ChatToolFactory.kt")
        assertTrue(
            "装配点必须把 turnOwner 透传到工作区段",
            "createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, turnOwner)" in factory,
        )

        val service = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")
        assertTrue(
            "真正装配请求工具的地方必须给 turnOwner = 本轮令牌",
            "turnOwner = session.backgroundTaskOwner," in service,
        )
        assertTrue(
            "onTurnCancelled 必须接到按 owner 收尾的实现上",
            "onTurnCancelled = { owner -> killBackgroundTasksOfCancelledTurn(owner) }" in service,
        )
        assertTrue(
            "收尾必须真的调用仓库的 killBackgroundTasksByOwner",
            "killBackgroundTasksByOwner(owner)" in service,
        )
    }

    /**
     * 「轮」的边界只允许有一个入口。
     *
     * 若某个调用点又自己写回 `session.toolLoopGuard.reset()` 那四行，它会**编译通过、
     * 行为看起来也对**，但后台任务归属不会换新 ⇒ 那一轮的取消会把上一轮的任务一起杀掉。
     * 这类漏项只能在源码层拦住。
     */
    @Test
    fun `turn boundaries only go through beginNewTurn`() {
        val service = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")

        assertEquals(
            "ChatService 里不应再有直接的护栏 reset——轮的边界统一走 session.beginNewTurn()",
            0,
            service.countOccurrences("session.toolLoopGuard.reset()"),
        )
        assertEquals(
            "beginNewTurn 的调用点应恰好三处：队列派发（新一轮用户消息）、重新生成、继续生成",
            3,
            service.countOccurrences("session.beginNewTurn()"),
        )
        // 三处都必须在 appScope.launch 之前同步调用：协程体可能晚于 session.setJob(job) 才开始跑，
        // 那时令牌还没换新，setJob 捕获到的就是上一轮的令牌。
        for (marker in listOf("private fun sendQueuedMessage", "fun regenerateAtMessage", "fun continueMessage")) {
            val start = service.indexOf(marker)
            assertTrue("找不到 $marker", start >= 0)
            val body = service.substring(start, minOf(service.length, start + 1_500))
            val turnAt = body.indexOf("session.beginNewTurn()")
            val launchAt = body.indexOf("appScope.launch")
            assertTrue("$marker 里没调用 beginNewTurn：$body", turnAt >= 0)
            assertTrue(
                "$marker 必须在 appScope.launch 之前换令牌（否则 setJob 捕获到上一轮的令牌）",
                turnAt < launchAt,
            )
        }
    }

    @Test
    fun `the session reports cancellation with the token captured at registration`() {
        val session = source("src/main/java/app/androidrik/rikkahub/service/ConversationSession.kt")
        val setJobAt = session.indexOf("fun setJob(")
        assertTrue("找不到 setJob", setJobAt >= 0)
        val body = session.substring(setJobAt, minOf(session.length, setJobAt + 3_000))

        // 令牌必须在 invokeOnCompletion 注册**之前**取出
        val captureAt = body.indexOf("val turnOwner = backgroundTaskOwner")
        val registerAt = body.indexOf("job?.invokeOnCompletion")
        assertTrue("setJob 没在注册回调前捕获令牌", captureAt >= 0)
        assertTrue("令牌捕获必须在注册回调之前（注册之后现读会拿到下一轮的）", captureAt < registerAt)
        assertTrue(
            "只有被取消才回调（正常结束要保留后台任务）",
            "if (cause is CancellationException)" in body && "onTurnCancelled(turnOwner)" in body,
        )
    }

    /**
     * 停在审批上的那一轮**没有活跃 Job**（等用户决定时生成协程已正常结束），
     * 取消回调不会触发。这条分支是它唯一的收尾路径——写成源码断言，因为它在 JVM 上
     * 够不到（`stopGeneration` 依赖整个 ChatService 装配）。
     */
    @Test
    fun `stop generation also covers the turn that stopped at a tool approval`() {
        val service = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")
        val stopAt = service.indexOf("suspend fun stopGeneration(")
        assertTrue("找不到 stopGeneration", stopAt >= 0)
        val body = service.substring(stopAt, minOf(service.length, stopAt + 3_000))

        // 判定必须在收尾之前（finishInterruptedPendingTools 会把 pending 抹掉）
        val detectAt = body.indexOf("val awaitingApproval = hasPendingToolApproval(")
        val finishAt = body.indexOf("finishInterruptedPendingTools(")
        assertTrue("没判定「停在审批上」", detectAt >= 0)
        assertTrue("判定必须在收尾之前（收尾后 pending 已消失）", detectAt < finishAt)
        assertTrue(
            "停在审批上的一轮也要收掉本轮起的后台任务：$body",
            "if (jobs.isEmpty() && awaitingApproval)" in body,
        )
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }
}
