package app.androidrik.rikkahub.data.ai.tools.local

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart

/**
 * JS 求值工具（QuickJS）。
 *
 * **资源防护**（issue #1903 / #1904，取值对齐上游 `c8853531` 便于将来对比行为）：
 * 三处防护缺一不可，因为模型生成的代码是**不可信输入**：
 *
 * 1. **执行超时**：`while(true){}` 会让 `evaluate()` 永久挂起、只能杀进程。
 *    该包装库**无中断接口** ⇒ 放到独立线程 + `join(10s)`（见 [runOnWorkerWithTimeout]）。
 * 2. **内存/栈上限**：`new Array(1e9)` 会撑爆 native 堆导致进程 OOM；深递归有 native 栈溢出风险。
 *    `setMemoryLimit` / `setMaxStackSize` 把它们转成**可捕获的 JS 异常**。
 * 3. **context 释放**：每次 eval 不 `destroy()` 会泄漏 native 内存，长会话累积 OOM。
 *
 * **⚠️ 超时路径绝不 destroy context**：执行中的 native 代码被外部销毁会 use-after-free 崩溃。
 * 故 context 的创建与销毁都在**工作线程内部**（`finally`）完成，超时方只是不再等待。
 */
internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Execution is sandboxed: it times out after ${JsLimits.EXECUTION_TIMEOUT_MS / 1000}s with a
        ${JsLimits.MEMORY_LIMIT_BYTES / 1024 / 1024}MB memory cap, so endless loops fail
        instead of hanging the app.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
            ?: error("code is required")
        listOf(UIMessagePart.Text(evaluateJavascriptSafely(code)))
    }
)

/**
 * 在工作线程上求值并施加全部防护；返回工具结果 JSON。
 *
 * **不抛异常**：超时/内存超限/JS 异常都转成 `{"error": ...}` —— 工具失败应让模型看到原因并自行
 * 调整，而不是让整个生成回合崩掉（与上游行为一致）。
 */
private fun evaluateJavascriptSafely(code: String): String {
    val outcome = runOnWorkerWithTimeout(JsLimits.EXECUTION_TIMEOUT_MS, "quickjs-eval") {
        // context 的创建与销毁都在工作线程内：超时后由该线程自己善后（见文件头注释）
        val context = QuickJSContext.create()
        try {
            applyResourceLimits(context)
            evaluateIn(context, code)
        } finally {
            context.destroy()
        }
    }
    return when (outcome) {
        is TimedResult.TimedOut ->
            errorPayload("JavaScript execution timed out after ${JsLimits.EXECUTION_TIMEOUT_MS}ms")

        is TimedResult.Completed -> outcome.value
    }
}

/**
 * 施加内存与栈上限。
 *
 * `setMemoryLimit` 使超大分配变成**可捕获的 JS 异常**（而非 native OOM 崩进程）；
 * `setMaxStackSize` 避免深递归打爆 native 栈。
 * 各自包 `runCatching`：包装库版本间对二者支持不一致，**防护缺失不应让工具直接不可用**。
 */
private fun applyResourceLimits(context: QuickJSContext) {
    runCatching { context.setMemoryLimit(JsLimits.MEMORY_LIMIT_BYTES) }
    runCatching { context.setMaxStackSize(JsLimits.MAX_STACK_BYTES) }
}

/** 真正执行并在工作线程内完成结果序列化（序列化也可能触发 JS getter / toJSON）。 */
private fun evaluateIn(context: QuickJSContext, code: String): String {
    val logs = arrayListOf<String>()
    context.setConsole(object : QuickJSContext.Console {
        override fun log(info: String?) {
            logs.add("[LOG] $info")
        }

        override fun info(info: String?) {
            logs.add("[INFO] $info")
        }

        override fun warn(info: String?) {
            logs.add("[WARN] $info")
        }

        override fun error(info: String?) {
            logs.add("[ERROR] $info")
        }
    })

    return try {
        val result = context.evaluate(code)
        val resultText = when (result) {
            null -> null
            is QuickJSObject -> result.stringify()
            else -> result.toString()
        }
        // 结果超限时不返回"半截内容"——半截 JSON 交给模型更具误导性，改为明确错误
        val oversized = resultText != null && truncateResult(resultText, JsLimits.MAX_RESULT_CHARS) == null
        buildJsonObject {
            if (logs.isNotEmpty()) {
                put("logs", JsonPrimitive(truncateLogs(logs.joinToString("\n"), JsLimits.MAX_LOG_CHARS)))
            }
            if (oversized) {
                put("error", "JavaScript result exceeds output limit (${JsLimits.MAX_RESULT_CHARS} chars)")
            } else {
                put("result", resultText?.let(::JsonPrimitive) ?: JsonNull)
            }
        }.toString()
    } catch (t: Throwable) {
        // 内存超限 / 语法错误 / 运行时异常都走这里（内存超限由 setMemoryLimit 转成 JS 异常）
        errorPayload((t.message ?: "JavaScript execution failed").take(JsLimits.MAX_LOG_CHARS))
    }
}

private fun errorPayload(message: String): String =
    buildJsonObject { put("error", message) }.toString()
