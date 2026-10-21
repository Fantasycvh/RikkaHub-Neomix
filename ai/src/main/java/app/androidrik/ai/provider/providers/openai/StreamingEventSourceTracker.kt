package app.androidrik.ai.provider.providers.openai

import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/**
 * `streamText` 的 callbackFlow 里「可替换事件源」的连接管理（AI-3）。
 *
 * 背景：网关返回「思考模式必须回传思维链」的结构校验 400 时，本 API 会带着 reasoning 回填
 * **重发同一个请求**，也就是把 callbackFlow 里的 EventSource 换掉。旧实现有两个问题：
 * 1. 重发创建的新 EventSource 没有纳入 `awaitClose` 的取消范围（取消 collect 后连接仍在跑）；
 * 2. 同步建连失败的重发路径会多建一个连接（重试 helper 建一个、外层再建一个）。
 *
 * 另外 OkHttp 的回调来自任意线程，且 `newEventSource` **在返回前就可能回调**（内层
 * `call.enqueue()` 之后立刻执行）：结构失败后旧 source 也可能在新 source 已接管后**迟到**
 * `onEvent` / `onClosed` / `onFailure`。因此不能等 `newEventSource` 返回后再标记 active ——
 * 那时首个事件已经丢了，甚至 onFailure/onClosed 丢失会让 flow 永久不结束。
 *
 * 这里用 **attempt token（generation）** 解决：
 * - 每次建连先 `newAttempt()` 拿到单调递增的 token，并把 token 绑在那个 source 的
 *   [GuardedEventSourceListener] 上；
 * - 回调到达时先 `allowsCallback(token, source)`：**当前** attempt 的首个回调可以「认领」
 *   active（覆盖同步回调），旧 attempt 的回调永远被拒绝（旧 source 不能重新抢占）；
 * - `activate(token, source)` 在 `newEventSource` 返回后正常登记，并取消上一个 source；
 * - `close()` 之后不再接受新 source：迟到的 source 会被立即 `cancel()`。
 */
internal class StreamingEventSourceTracker {
    private val lock = Any()
    private var latestAttempt = 0L
    private var active: EventSource? = null
    private var closed = false

    /** 开始一次建连尝试，返回本次 attempt token（单调递增的 generation）。 */
    fun newAttempt(): Long = synchronized(lock) { ++latestAttempt }

    /**
     * 登记本次 attempt 建出的事件源：取消上一个（任意时刻最多一个存活连接）。
     *
     * @return `false` = 已关闭或该 attempt 已被更新的一次取代（此时新 source 已被**立即取消**）。
     */
    fun activate(attempt: Long, source: EventSource): Boolean = synchronized(lock) {
        when {
            closed -> {
                source.cancel()
                false
            }

            attempt != latestAttempt -> {
                // 建连期间又发生了更新的一次 attempt（例如同步 onFailure 触发了重发）：
                // 这个迟到建出来的连接直接丢弃。
                source.cancel()
                false
            }

            else -> {
                val previous = active
                active = source
                if (previous != null && previous !== source) previous.cancel()
                true
            }
        }
    }

    /**
     * 回调到达时（**必须先于 delegate 调用**）的判定。
     *
     * 允许「当前 attempt」的首个回调认领 active，从而覆盖 OkHttp 在 `newEventSource` 返回前
     * 就同步回调的情况；旧 attempt 的 source 因为 token 已过期，永远无法重新抢占。
     */
    fun allowsCallback(attempt: Long, source: EventSource): Boolean = synchronized(lock) {
        when {
            closed -> false
            attempt != latestAttempt -> false
            else -> {
                val previous = active
                if (previous !== source) {
                    active = source
                    if (previous != null) previous.cancel()
                }
                true
            }
        }
    }

    /** 该 source 是否仍是当前 source（listener 内部的二次防御）。 */
    fun isActive(source: EventSource): Boolean = synchronized(lock) { !closed && active === source }

    /** 关闭流：取消当前 source 并拒绝后续注册。幂等。 */
    fun close() {
        val current = synchronized(lock) {
            closed = true
            active.also { active = null }
        }
        current?.cancel()
    }
}

/**
 * 把某个 attempt 的事件源回调先过 [StreamingEventSourceTracker] 再交给共享 listener。
 *
 * 每个 source 一个实例，token 绑死在连接上：同步回调（`newEventSource` 返回前）也能被正确
 * 认领；旧连接迟到的回调则因 token 过期被直接丢弃。
 */
internal class GuardedEventSourceListener(
    private val tracker: StreamingEventSourceTracker,
    private val attempt: Long,
    private val delegate: EventSourceListener,
) : EventSourceListener() {
    override fun onOpen(eventSource: EventSource, response: Response) {
        if (tracker.allowsCallback(attempt, eventSource)) delegate.onOpen(eventSource, response)
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        if (tracker.allowsCallback(attempt, eventSource)) delegate.onEvent(eventSource, id, type, data)
    }

    override fun onClosed(eventSource: EventSource) {
        if (tracker.allowsCallback(attempt, eventSource)) delegate.onClosed(eventSource)
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        if (tracker.allowsCallback(attempt, eventSource)) delegate.onFailure(eventSource, t, response)
    }
}
