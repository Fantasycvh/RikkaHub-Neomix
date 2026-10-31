package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾闸门的**接线守卫**（design-deliverable-gate.md §六）。
 *
 * 闸门最大的风险不是「没生效」，而是**挂错了地方**——聊天场景被拦截意味着用户
 * 「发完消息却结束不了」，直接推翻本仓「只提醒不拦截」的哲学。以下不变量在源码层钉死：
 *
 * 1. 闸门只允许挂在 GenerationHandler 的「空工具分支」唯一自然出口——护栏停止、
 *    上下文硬限等**环境掐断**的出口不得触发（打回只会雪上加霜）；
 * 2. 普通聊天路径（ChatService 的用户入口）不得挂闸：闸门只能从队列项
 *    （`QueuedMessage.loopGate`）随定时任务的注入进入，用户发起的入队不传；
 * 3. 重生成/继续生成等用户发起的轮次边界必须清闸（`beginNewTurn`）。
 */
class LoopGateWiringGuardTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun handler() = source("src/main/java/app/androidrik/rikkahub/data/ai/GenerationHandler.kt")
    private fun chatService() = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")
    private fun queue() = source("src/main/java/app/androidrik/rikkahub/service/MessageQueue.kt")
    private fun session() = source("src/main/java/app/androidrik/rikkahub/service/ConversationSession.kt")

    @Test
    fun `gate sits only at the natural no-tool-call exit`() {
        val handler = handler()
        val gateIndex = handler.indexOf("if (loopGate != null) {")
        val naturalExit = handler.indexOf("// no tool calls")
        assertTrue("闸门判定块必须存在", gateIndex >= 0)
        assertTrue("自然出口注释必须存在（空工具分支锚点）", naturalExit >= 0)
        assertTrue(
            "闸门必须紧跟自然出口（空工具分支），不得挪到护栏/审批出口",
            gateIndex in naturalExit until naturalExit + 400,
        )
        // 判定块的收尾必须是 break（Pass 时照旧收束）或 continue（Retry 打回）
        val block = handler.substring(gateIndex, gateIndex + 900)
        assertTrue("Retry 必须打回本轮（continue）", "continue" in block)
    }

    @Test
    fun `chat entry never mounts a gate`() {
        val chatService = chatService()
        // 用户发起的插队发送不得传闸门（默认 null 参数；出现显式传值即挂错）
        assertTrue(
            "sendInterrupting（插队发送）不得携带闸门",
            "loopGate" !in chatService.substringAfter("fun sendInterrupting(")
                .substringBefore("fun sendMessage("),
        )
        // 闸门只能随队列项进入会话
        assertTrue(
            "会话闸门必须来自队列项（queued.loopGate）",
            "session.turnLoopGate = queued.loopGate" in chatService,
        )
    }

    @Test
    fun `gate retry resets the tool loop guard`() {
        val handler = handler()
        val gateIndex = handler.indexOf("if (loopGate != null) {")
        val block = handler.substring(gateIndex, gateIndex + 700)
        assertTrue(
            "闸门打回必须重置 LoopGuard（打回后的复查是新一轮指令的合理重试，"
                + "不重置会被护栏误判成工具循环提前掐轮）",
            "loopGuard.reset()" in block,
        )
    }

    @Test
    fun `queue carries the gate per item`() {
        val queue = queue()
        assertTrue(
            "QueuedMessage 必须带 loopGate 字段（null 默认 = 用户入队不挂闸）",
            "val loopGate: LoopGateConfig? = null" in queue,
        )
    }

    @Test
    fun `new turn resets the gate`() {
        val session = session()
        val begin = session.indexOf("fun beginNewTurn()")
        val reset = session.indexOf("turnLoopGate = null")
        assertTrue("beginNewTurn 必须清闸（用户发起的下一轮不得继承上一轮闸门）", reset >= 0)
        assertTrue(
            "清闸必须在 beginNewTurn 内",
            reset in begin until begin + 600,
        )
    }

    @Test
    fun `scheduled runner mounts gate via sendMessage param only`() {
        val runner = source("src/main/java/app/androidrik/rikkahub/service/ScheduledTaskRunner.kt")
        assertTrue(
            "定时任务必须经 sendMessage(loopGate=…) 挂闸",
            "loopGate = loopGate" in runner,
        )
    }
}
