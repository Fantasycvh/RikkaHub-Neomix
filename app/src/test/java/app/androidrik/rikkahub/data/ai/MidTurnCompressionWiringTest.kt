package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中途自动压缩的**接线守卫**（上游 `bcc649abc`；本仓落点）。
 *
 * 语义单测覆盖不了「挂在哪」：同一段判定挂早一步（工具批中间/护栏出口之前）就会发出
 * 「有调用没结果」的窗口或白烧一次压缩请求。以下不变量在源码层钉死：
 *
 * 1. 暂停点必须在一批工具**全部回填之后**，且排在所有「提前结束」判定（护栏停止、待审批、
 *    endTurn、最后一步）**之后**——优雅停止优先于预防性压缩；
 * 2. 压缩成功才裁尾部空回复、刷新窗口；失败只记日志、接着原回复写；
 * 3. 重新生成（`messageRange != null`）一律不启用中途压缩，也不自己同步会话状态
 *    （数据源由 `compressToCheckpoint` 在无挂起点里写回，避免 coalescer 迟到快照）；
 * 4. 请求面必须裁掉「新开的空回复」并给检查点补续跑提示，否则模型会把摘要当用户消息回应。
 */
class MidTurnCompressionWiringTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.exists() } ?: File("app/$relative")
        return file.readText().replace("\r\n", "\n")
    }

    private fun handler() = source("src/main/java/app/androidrik/rikkahub/data/ai/GenerationHandler.kt")
    private fun chatService() = source("src/main/java/app/androidrik/rikkahub/service/ChatService.kt")

    @Test
    fun `compression hook sits after every terminal exit`() {
        val handler = handler()
        val hook = handler.indexOf("// ---- 中途自动压缩")
        val endTurnExit = handler.indexOf("if (endTurnAfterTools) break")
        val approvalExit = handler.indexOf("if (messages.last().getTools().any { it.isPending })")
        assertTrue("暂停点必须存在", hook >= 0)
        assertTrue("endTurn 出口必须存在", endTurnExit >= 0)
        assertTrue("暂停点必须在「endTurn 结束」之后（收尾结论优先）", hook > endTurnExit)
        assertTrue("暂停点必须在「待审批结束」之后", hook > approvalExit)
        // 压缩动作本身：成功 = 新开空回复 + 刷新窗口；失败 = 只记日志
        val block = handler.substring(hook, hook + 3_200)
        assertTrue(
            "成功必须新开一条 assistant 回复（检查点之后的新段落）",
            "messages + UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())" in block,
        )
        assertTrue("成功必须刷新压缩窗口", "refreshCompressionWindow?.invoke()" in block)
        assertTrue("失败路径不得中断本轮", "continuing current reply" in block)
    }

    @Test
    fun `regenerate never enables mid-turn compression`() {
        val chatService = chatService()
        assertTrue(
            "重生成（messageRange 非空）必须传 null",
            "if (messageRange != null) null else compressMidTurn" in chatService,
        )
        // 压缩的数据源必须是生成器快照：会话内存态经 coalesceMessageUpdates 节流会落后
        assertTrue("必须把 live 消息交给压缩", "midTurnMessages = request.messages" in chatService)
        assertTrue(
            "数据源写回必须在 compressToCheckpoint 内（同一段无挂起点）",
            "latest.mergeGenerationMessages(midTurnMessages.map { it.finishReasoning() })" in chatService,
        )
    }

    @Test
    fun `request face trims the fresh reply and adds the continuation hint`() {
        val handler = handler()
        assertTrue("必须裁掉空回复", "withoutTrailingEmptyAssistantForRequest()" in handler)
        assertTrue(
            "检查点续跑提示必须作用于请求面",
            "compressionWindow?.withContinuationHintFor(requestBasis)" in handler,
        )
        assertTrue("装配必须用裁剪后的请求面", "messages = requestBasis" in handler)
    }

    @Test
    fun `mid-turn compress instruction is appended after the user template`() {
        val chatService = chatService()
        val chunk = chatService.indexOf("suspend fun compressChunk")
        assertTrue("压缩请求构造必须存在", chunk >= 0)
        val region = chatService.substring(chunk, chunk + 2_000)
        val template = region.indexOf("settings.compressPrompt.applyPlaceholders(")
        val instruction = region.indexOf("append(MID_TURN_COMPRESS_INSTRUCTION)")
        assertTrue("用户模板必须先展开", template >= 0)
        assertTrue("中途指令必须追加在模板之后", instruction > template)
    }
}
