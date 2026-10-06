package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具级执行超时（见 [executeToolWithTimeout] 与 `Tool.executionTimeoutMillis`）。
 *
 * 关键约束：**超时必须变成「工具执行失败」而不能表现成「用户停止生成」**——
 * `TimeoutCancellationException` 是 `CancellationException` 的子类，而工具执行失败通路
 * （`GenerationHandler` 的 `onFailure`）对 `CancellationException` 一律原样上抛，故包装层
 * 必须把它换成普通异常。这里把三条语义都钉住。
 */
class ToolExecutionTimeoutTest {

    private fun tool(name: String, timeout: Long?): Tool = Tool(
        name = name,
        description = "",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        executionTimeoutMillis = timeout,
        execute = { emptyList() },
    )

    private val args = JsonObject(emptyMap())

    @Test
    fun `hanging tool is converted into a regular tool failure`() {
        val hanging = tool("hang", timeout = 30L)
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                executeToolWithTimeout(hanging, args) {
                    delay(10_000)
                    emptyList()
                }
            }
        }
        // 必须是普通异常（⇒ 走错误输出通路），而不是 CancellationException（⇒ 会被当成用户停止）
        assertTrue(!(thrown is CancellationException))
        assertTrue("错误文案要能被模型读懂并自我纠正", thrown.message!!.contains("timed out"))
        assertTrue(thrown.message!!.contains("hang"))
    }

    @Test
    fun `result within the limit passes through`() {
        val quick = tool("quick", timeout = 5_000L)
        val result = runBlocking {
            executeToolWithTimeout(quick, args) { listOf(UIMessagePart.Text("ok")) }
        }
        assertEquals(1, result.size)
    }

    @Test
    fun `null timeout means the tool manages itself and the outer layer never fires`() {
        // dispatch_subagent 即此形态：阻塞等整轮子代理（可达 7200s），外层不介入
        val selfManaged = tool("dispatch_subagent", timeout = null)
        val result = runBlocking {
            executeToolWithTimeout(selfManaged, args) {
                delay(120)  // 远超任何「短超时」，仍应正常返回
                listOf(UIMessagePart.Text("done"))
            }
        }
        assertEquals(1, result.size)
    }

    @Test
    fun `outer cancellation is not swallowed`() {
        // 用户停止生成：取消必须向上传播（包装层不得把它转成工具错误）
        val slow = tool("slow", timeout = 60_000L)
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                executeToolWithTimeout(slow, args) {
                    throw CancellationException("user stopped")
                }
            }
        }
        assertEquals("user stopped", thrown.message)
    }
}
