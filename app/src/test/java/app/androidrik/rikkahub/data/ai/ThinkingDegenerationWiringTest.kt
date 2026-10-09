package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 思考退化护栏的**接线守卫**（源码扫描）。
 *
 * 起因：本护栏的价值全在「接线」——判据本身有单测覆盖（[ThinkingDegenerationGuardTest]），
 * 但只要有一条链路没接上，功能就静默失效，而且**编译期与普通单测都发现不了**：
 * - 不把 `thinkingGuard` 传进 `generateInternal` ⇒ 调用内断流永远不触发（单次调用可以烧到几百 K）；
 * - 不在步骤循环里消费判定 ⇒ 断流了也不停本轮、还会走「空回复补救/续写」再发一次请求；
 * - 会话上没有实例（或没在轮边界 reset） ⇒ 跨审批续跑时计数被重置，永远攒不到阈值；
 * - `GenerationNotice` 新增分支没被消费 ⇒ 停止时**没有任何提示**（用户只看到「突然停了」）。
 *
 * 与 `RequestMessagePipelineGuardTest` 同款：这里刻意用源码扫描而不是运行时断言。
 */
class ThinkingDegenerationWiringTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `handler wires the guard into the stream and the step loop`() {
        val source = read("app/androidrik/rikkahub/data/ai/GenerationHandler.kt")

        assertTrue(
            "generateInternal 必须收到 degenerationGuard（否则调用内断流永不触发）",
            source.contains("degenerationGuard = thinkingGuard"),
        )
        assertTrue(
            "流消费处必须喂 delta 给护栏",
            source.contains("degenerationGuard?.onDelta("),
        )
        assertTrue(
            "断流必须被内层捕获（不当失败：不回滚、不重试、不 fallback）",
            source.contains("catch (e: ThinkingDegenerationCut)"),
        )
        assertTrue(
            "步骤循环必须消费断流与调用边界判定",
            source.contains("thinkingGuard.takeCut()") && source.contains("thinkingGuard.onCallFinished("),
        )
        assertTrue(
            "停止提示必须发出（否则用户看到的是「突然停了」）",
            source.contains("GenerationNotice.ThinkingDegenerationStopped("),
        )
    }

    @Test
    fun `chat path mounts the guard on the session and resets it at turn boundaries`() {
        val session = read("app/androidrik/rikkahub/service/ConversationSession.kt")
        assertTrue(
            "会话上必须有实例（跨审批续跑累积）",
            session.contains("val thinkingGuard = ThinkingDegenerationGuard()"),
        )

        val chatService = read("app/androidrik/rikkahub/service/ChatService.kt")
        assertTrue(
            "generateText 必须用会话上的实例",
            chatService.contains("thinkingGuard = session.thinkingGuard"),
        )
        val resets = Regex("""session\.thinkingGuard\.reset\(\)""").findAll(chatService).count()
        assertTrue(
            "轮边界（新用户消息 / 重新生成 / 续写）都要 reset，实际 $resets 处",
            resets >= 3,
        )
    }

    @Test
    fun `both consumers handle the new notice`() {
        assertTrue(
            "聊天路径必须消费（用户可见提示）",
            read("app/androidrik/rikkahub/service/ChatService.kt")
                .contains("is GenerationNotice.ThinkingDegenerationStopped ->"),
        )
        assertTrue(
            "无头 run 必须消费（否则 run 被记成「正常完成」的静默失败）",
            read("app/androidrik/rikkahub/service/SubagentRunner.kt")
                .contains("is GenerationNotice.ThinkingDegenerationStopped ->"),
        )
    }

    @Test
    fun `headless runner mounts its own per-run guard`() {
        val runner = read("app/androidrik/rikkahub/service/SubagentRunner.kt")
        assertTrue(
            "run 上必须有实例（与 loopGuard/workspaceBudget/denialGuard 同款）",
            runner.contains("val thinkingGuard = ThinkingDegenerationGuard()"),
        )
        assertTrue(
            "run 的 generateText 必须传入",
            runner.contains("thinkingGuard = thinkingGuard"),
        )
    }
}
