package app.androidrik.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * 在**独立线程**上执行 [block] 并等待至多 [timeoutMillis]。
 *
 * **为什么需要独立线程**：fork 用的 QuickJS 包装库（`wang.harlon.quickjs`）**没有中断/超时接口**
 * ——`evaluate()` 是同步阻塞调用，模型若生成 `while(true){}`，调用会永久挂起、只能杀进程。
 * 唯一可行的做法是把求值放到可丢弃的线程上，主流程按超时返回（见 issue #1903 第 1 点）。
 *
 * **⚠️ 超时后绝不去打断/销毁该线程**：执行中的 native 代码若被外部 destroy 会 use-after-free 崩溃。
 * 因此超时只是"不再等它"，工作线程若将来自己跑完会正常释放自己的资源
 * （本文件的调用方把 context 的创建/销毁都放在同一线程内，故销毁永远发生在求值结束之后）。
 * 死循环属罕见输入，泄漏一个线程到进程结束是可接受的代价。
 */
internal sealed interface TimedResult<out T> {
    data class Completed<T>(val value: T) : TimedResult<T>

    /**
     * 超时。**注意**：工作线程仍在运行，其资源由它自己释放；调用方不得触碰其上下文。
     */
    data object TimedOut : TimedResult<Nothing>
}

/**
 * @param workerName 线程名（便于在崩溃/线程转储里辨认）。
 * @throws Throwable [block] 抛出的异常原样重抛（在本线程，故可正常处理）。
 */
internal fun <T> runOnWorkerWithTimeout(
    timeoutMillis: Long,
    workerName: String = "quickjs-eval",
    block: () -> T,
): TimedResult<T> {
    val holder = AtomicReference<Any?>()
    val thread = Thread({
        holder.set(
            try {
                block()
            } catch (t: Throwable) {
                t
            }
        )
    }, workerName).apply { isDaemon = true }

    thread.start()
    thread.join(timeoutMillis)
    if (thread.isAlive) return TimedResult.TimedOut

    val outcome = holder.get()
    if (outcome is Throwable) throw outcome
    @Suppress("UNCHECKED_CAST")
    return TimedResult.Completed(outcome as T)
}

/**
 * 截断日志到 [maxChars]（防止模型打印海量日志把工具结果撑爆）。
 *
 * 纯函数，便于单测。
 */
internal fun truncateLogs(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars) + "\n[Logs truncated]"

/**
 * 截断工具结果到 [maxChars]。超限返回 null 表示"结果过大、拒绝返回"。
 *
 * 与日志不同：结果超限时不应"截一半"（半截 JSON 交给模型反而更具误导性），
 * 故返回 null 由调用方给出明确错误。
 */
internal fun truncateResult(text: String?, maxChars: Int): String? = when {
    text == null -> null
    text.length <= maxChars -> text
    else -> null
}

/**
 * 求值参数的**防护上限**（对齐上游 `c8853531` 的取值，便于将来对比行为）。
 * 本文件独立于 Android，故这些常量可在纯 JVM 单测中使用。
 */
internal object JsLimits {
    /** 执行超时（死循环在此之后返回错误，而不是永久挂起）。 */
    const val EXECUTION_TIMEOUT_MS: Long = 10_000L

    /** native 堆上限：超大分配转为可捕获的 JS 异常，不再 OOM 整个进程。 */
    const val MEMORY_LIMIT_BYTES: Int = 64 * 1024 * 1024

    /** 栈上限：深递归不再有 native 栈溢出风险。 */
    const val MAX_STACK_BYTES: Int = 256 * 1024

    /** 日志总量上限。 */
    const val MAX_LOG_CHARS: Int = 64 * 1024

    /** 单次结果字符上限。 */
    const val MAX_RESULT_CHARS: Int = 1024 * 1024
}
