package app.androidrik.rikkahub.data.ai.tools.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `eval_javascript` 的**真实 QuickJS 引擎**行为验证（issue #1903 第 1、2 点）。
 *
 * 必须是 **instrumented 测试**：native `.so` 只打进 Android AAR，JVM 单测跑不了引擎
 * （纯逻辑部分见 `JavascriptSandboxTest`）。这里验证的正是**防护是否真的落到 native 上**——
 * 单测只能证明"我们的超时骨架会返回"，证明不了"引擎真的被拦住"。
 */
@RunWith(AndroidJUnit4::class)
class JavascriptToolInstrumentedTest {

    private val tool = buildJavascriptTool()

    /** 通过工具本体执行（走与线上完全相同的路径）。 */
    private fun run(code: String): String {
        val args = buildJsonObject { put("code", code) }
        val parts = kotlinx.coroutines.runBlocking { tool.execute(args) }
        return parts.filterIsInstance<app.androidrik.ai.ui.UIMessagePart.Text>().single().text
    }

    private fun resultOf(payload: String): String? =
        Json.parseToJsonElement(payload).jsonObject["result"]?.jsonPrimitive?.contentOrNull

    private fun errorOf(payload: String): String? =
        Json.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.contentOrNull

    @Test
    fun normalEvaluationStillWorks() {
        // 回归保护：加了防护后正常表达式必须照常可用
        assertEquals("3", resultOf(run("1 + 2")))
        assertEquals("10", resultOf(run("const x = 5; x * 2")))
    }

    @Test
    fun consoleOutputIsCaptured() {
        val payload = run("console.log('hi'); 42")
        assertEquals("42", resultOf(payload))
        assertTrue("日志应被捕获", payload.contains("hi"))
    }

    @Test
    fun infiniteLoopReturnsTimeoutInsteadOfHanging() {
        // #1903 第 1 点：修复前这里会永久挂起、只能杀进程。
        // 断言"在超时后返回错误"而不是"卡住"——若防护失效，本测试会超时失败。
        val started = System.currentTimeMillis()
        val payload = run("while(true){}")
        val elapsed = System.currentTimeMillis() - started
        assertNotNull("应返回错误而不是挂起", errorOf(payload))
        assertTrue("错误应说明是超时：$payload", errorOf(payload)!!.contains("timed out", ignoreCase = true))
        assertTrue("应在超时附近返回（实际 ${elapsed}ms）", elapsed < JsLimits.EXECUTION_TIMEOUT_MS + 15_000)
    }

    @Test
    fun hugeAllocationIsRejectedInsteadOfCrashingTheProcess() {
        // #1903 第 2 点：修复前 new Array(1e9) 会撑爆 native 堆 → 进程被系统杀死。
        // 现在应转为可捕获的 JS 异常（工具返回 error），**进程存活**。
        val payload = run("new Array(1e9).fill(0).length")
        assertNotNull("超大分配必须被拦下并作为错误返回：$payload", errorOf(payload))
        // 能跑到这里就说明进程没崩（崩了本测试不会产出结果）
    }

    @Test
    fun deepRecursionIsRejectedInsteadOfNativeStackOverflow() {
        // setMaxStackSize 的作用：深递归转成 JS 异常而不是 native 栈溢出
        val payload = run("function f(n){return f(n+1)} f(0)")
        assertNotNull("深递归必须被拦下：$payload", errorOf(payload))
    }

    @Test
    fun syntaxErrorIsReportedNotThrown() {
        // 工具失败应以 error 字段返回，让模型看到原因并自行调整（而不是崩掉整个回合）
        val payload = run("this is not valid js ((")
        assertNotNull(errorOf(payload))
    }

    @Test
    fun oversizedResultIsRejectedWithClearError() {
        // 结果超限不返回"半截内容"，而是明确错误
        val payload = run("'x'.repeat(${JsLimits.MAX_RESULT_CHARS + 100})")
        val error = errorOf(payload)
        assertNotNull("超大结果应报错：${payload.take(200)}", error)
        assertTrue(error!!.contains("output limit", ignoreCase = true))
    }

    @Test
    fun repeatedInvocationsDoNotAccumulateNativeMemory() {
        // #1903 第 3 点（context 释放）：反复调用后 native 内存不应持续增长。
        // 这里用"能连续跑完多轮"作为轻量代理——真正的泄漏观察需 dumpsys meminfo。
        repeat(40) { i ->
            assertEquals("$i", resultOf(run("$i")))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
