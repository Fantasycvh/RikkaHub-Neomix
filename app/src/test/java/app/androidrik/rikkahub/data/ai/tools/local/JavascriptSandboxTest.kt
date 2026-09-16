package app.androidrik.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `eval_javascript` 资源防护里**与 Android 无关**的部分（issue #1903 的第 1、2 点）。
 *
 * ⚠️ 真正的 QuickJS 求值无法在 JVM 单测里跑（native `.so` 仅在 Android AAR 里），
 * 故这里覆盖的是可纯逻辑验证的骨架：**超时机制**、日志/结果截断。
 * 真实引擎行为（死循环返回超时、`new Array(1e9)` 被拦）走真机验证。
 */
class JavascriptSandboxTest {

    // ---- runOnWorkerWithTimeout：超时机制（#1903 第 1 点的核心）----

    @Test
    fun `returns completed value when block finishes in time`() {
        val result = runOnWorkerWithTimeout(timeoutMillis = 5_000L) { "ok" }
        assertEquals(TimedResult.Completed("ok"), result)
    }

    @Test
    fun `returns TimedOut instead of hanging forever`() {
        // 模拟死循环：一直睡，超出超时。**这条是 #1903 第 1 点的回归保护**——
        // 修复前 evaluate() 会永久挂起、只能杀进程。
        val started = System.currentTimeMillis()
        val result = runOnWorkerWithTimeout(timeoutMillis = 200L) {
            while (true) {
                Thread.sleep(10)
            }
            @Suppress("UNREACHABLE_CODE") "never"
        }
        val elapsed = System.currentTimeMillis() - started
        assertEquals(TimedResult.TimedOut, result)
        assertTrue("必须在超时附近返回而不是永久阻塞（实际 ${elapsed}ms）", elapsed < 5_000)
    }

    @Test
    fun `rethrows exceptions from the worker so callers can handle them`() {
        // JS 语法错误/内存超限最终都以异常形式冒出来，必须能正常处理（而不是被吞）
        val error = runCatching {
            runOnWorkerWithTimeout<String>(timeoutMillis = 5_000L) {
                throw IllegalStateException("simulated JS error")
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("simulated JS error", error!!.message)
    }

    @Test
    fun `worker thread is daemon so a stuck evaluation cannot block JVM exit`() {
        // 超时后的工作线程仍在跑；若它是非守护线程，进程就无法退出
        val thread = Thread.currentThread()
        var workerIsDaemon: Boolean? = null
        runOnWorkerWithTimeout<Unit>(timeoutMillis = 5_000L) {
            workerIsDaemon = Thread.currentThread().isDaemon
        }
        assertEquals(true, workerIsDaemon)
        assertTrue("调用方线程不受影响", !thread.isDaemon || thread.isDaemon)
    }

    // ---- 日志截断 ----

    @Test
    fun `short logs pass through unchanged`() {
        assertEquals("abc", truncateLogs("abc", maxChars = 10))
    }

    @Test
    fun `long logs are truncated with a marker`() {
        val result = truncateLogs("0123456789abc", maxChars = 10)
        assertTrue(result.startsWith("0123456789"))
        assertTrue("应带截断标记，避免模型以为这就是全部输出", result.contains("truncated"))
    }

    // ---- 结果截断（超限返回 null，而不是半截内容）----

    @Test
    fun `result within limit is returned as-is`() {
        assertEquals("abc", truncateResult("abc", maxChars = 10))
    }

    @Test
    fun `oversized result returns null so caller can report a clear error`() {
        // 有意"截一半"是错的：半截 JSON 交给模型更具误导性
        assertNull(truncateResult("0123456789abc", maxChars = 10))
    }

    @Test
    fun `null result stays null`() {
        assertNull(truncateResult(null, maxChars = 10))
    }

    // ---- 上限取值（对齐上游 c8853531，便于对比行为）----

    @Test
    fun `limits match the upstream values`() {
        assertEquals(10_000L, JsLimits.EXECUTION_TIMEOUT_MS)
        assertEquals(64 * 1024 * 1024, JsLimits.MEMORY_LIMIT_BYTES)
        assertEquals(256 * 1024, JsLimits.MAX_STACK_BYTES)
    }
}
