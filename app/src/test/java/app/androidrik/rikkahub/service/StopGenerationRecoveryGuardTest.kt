package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ① 的**接线守卫**：取消安全落点必须三处齐备，缺一处就等于没修。
 *
 * 为什么是源码扫描：这条链跨两个文件三步，而每一步单独看都「看起来正常」——
 * 1. `GenerationHandler` 回填时同步写快照（`onMessagesCommitted`）；
 * 2. `ChatService.handleMessageComplete` 把它接到 session 上，并在每块送达时清空
 *    （不清空会用旧快照覆盖之后收到的正文）；
 * 3. `ChatService.stopGeneration` 在取消之后、落库之前把快照补进内存态
 *    （不补的话 `persistStoppedGeneration` 落库的仍是缺最后一个工具结果的旧内容）。
 *
 * 行为层能证伪的只有第 1 步（`GenerationToolLoopTest` 的假 provider 用例）与第 3 步的
 * 单行变换；第 2 步的接线（谁调谁）JVM 单测够不到，故用源码钉住。
 */
class StopGenerationRecoveryGuardTest {

    private fun chatServiceSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
    ).readText().replace("\r\n", "\n")

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    /** 截出 `suspend fun <name>(` 到下一个同级 `suspend fun` 之间的源码。 */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("suspend fun $name(")
        check(start >= 0) { "找不到 suspend fun $name" }
        val next = source.indexOf("\n    suspend fun ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    @Test
    fun `stop generation restores the pending commit before persisting`() {
        val body = functionBody(chatServiceSource(), "stopGeneration")

        assertTrue(
            "停止路径必须消费取消安全落点（否则工具已算完的结果仍然丢）：$body",
            "lastCommittedMessages" in body,
        )
        assertTrue(
            "补回必须走「只增不改」的 withRestoredToolOutputs（整份替换会盖掉较新的正文）：$body",
            "withRestoredToolOutputs(" in body,
        )
        // 顺序：先补快照，再收尾 pending 工具、再落库
        val restoreAt = body.indexOf("lastCommittedMessages")
        val finishAt = body.indexOf("finishInterruptedPendingTools(")
        assertTrue(
            "必须先补快照再收尾待审批工具（否则收尾改的是缺结果的那份内存态）：$body",
            restoreAt in 0..<finishAt,
        )
    }

    @Test
    fun `generation wires the commit callback and resets it per turn`() {
        val source = chatServiceSource()

        assertTrue(
            "handleMessageComplete 必须把 onMessagesCommitted 接到会话上",
            "onMessagesCommitted = {" in source && "lastCommittedMessages.value = committed" in source,
        )
        // 起新一轮时清空（上一轮的快照与这一轮无关）
        val resetAt = source.indexOf("session.lastCommittedMessages.value = null")
        val generateAt = source.indexOf("generationHandler.generateText(")
        assertTrue("新一轮开始前必须清空上一轮遗留快照", resetAt in 0..<generateAt)
    }

    @Test
    fun `session exposes a nullable commit slot`() {
        val source = locate(
            "src/main/java/app/androidrik/rikkahub/service/ConversationSession.kt",
            "app/src/main/java/app/androidrik/rikkahub/service/ConversationSession.kt",
        ).readText()

        assertTrue(
            "取消安全落点挂在会话上（与护栏同款：随 session 生命周期）",
            "val lastCommittedMessages = MutableStateFlow<List<UIMessage>?>(null)" in source,
        )
    }
}
