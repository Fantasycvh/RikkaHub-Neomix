package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 续写策略（`docs/peer-apps-benchmark.md` §四.7 D）：裸断流恒自动续写、撞输出上限默认只提示、
 * 两类共用同一上限。策略错配的代价是**烧费**（该停不停）或**用户看不到续写**（该续不续），
 * 故用测试固定优先级与上限边界。
 */
class ContinuationPolicyTest {

    @Test
    fun `bare EOF always auto-continues`() {
        // 网关断流非模型本意：不看设置，直接续写
        assertEquals(
            CONTINUE_INCOMPLETE_HINT,
            autoContinueHintFor(
                finishIncomplete = true,
                finishReason = null,
                autoContinueOnLengthTruncation = false,
                alreadyContinued = 0,
            ),
        )
    }

    @Test
    fun `output limit truncation only auto-continues when user enabled it`() {
        assertNull(
            autoContinueHintFor(
                finishIncomplete = false,
                finishReason = "length",
                autoContinueOnLengthTruncation = false,
                alreadyContinued = 0,
            ),
        )
        assertEquals(
            CONTINUE_TRUNCATED_HINT,
            autoContinueHintFor(
                finishIncomplete = false,
                finishReason = "length",
                autoContinueOnLengthTruncation = true,
                alreadyContinued = 0,
            ),
        )
        // Google 命名同样命中
        assertEquals(
            CONTINUE_TRUNCATED_HINT,
            autoContinueHintFor(
                finishIncomplete = false,
                finishReason = "MAX_TOKENS",
                autoContinueOnLengthTruncation = true,
                alreadyContinued = 1,
            ),
        )
    }

    @Test
    fun `normal stop and content filter never auto-continue`() {
        assertNull(
            autoContinueHintFor(
                finishIncomplete = false,
                finishReason = "stop",
                autoContinueOnLengthTruncation = true,
                alreadyContinued = 0,
            ),
        )
        // 内容拦截：续写会再次被拦，不该自动重试
        assertNull(
            autoContinueHintFor(
                finishIncomplete = false,
                finishReason = "content_filter",
                autoContinueOnLengthTruncation = true,
                alreadyContinued = 0,
            ),
        )
    }

    @Test
    fun `limit caps both kinds of continuation`() {
        assertNull(
            autoContinueHintFor(
                finishIncomplete = true,
                finishReason = null,
                autoContinueOnLengthTruncation = true,
                alreadyContinued = CONTINUE_LIMIT,
            ),
        )
        // 上限前一次仍可续
        assertEquals(
            CONTINUE_INCOMPLETE_HINT,
            autoContinueHintFor(
                finishIncomplete = true,
                finishReason = null,
                autoContinueOnLengthTruncation = true,
                alreadyContinued = CONTINUE_LIMIT - 1,
            ),
        )
    }

    @Test
    fun `bare EOF wins when both conditions hold`() {
        // 模型撞上限后网关又没发终止标记：内容确实被切断，按断流续写
        assertEquals(
            CONTINUE_INCOMPLETE_HINT,
            autoContinueHintFor(
                finishIncomplete = true,
                finishReason = "length",
                autoContinueOnLengthTruncation = false,
                alreadyContinued = 0,
            ),
        )
    }
}
