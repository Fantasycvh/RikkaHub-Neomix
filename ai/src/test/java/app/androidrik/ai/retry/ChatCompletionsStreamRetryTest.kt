package app.androidrik.ai.retry

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.openai.ChatCompletionsAPI
import app.androidrik.ai.provider.providers.openai.StreamingEventSourceTracker
import app.androidrik.ai.provider.providers.openai.resetLearnedReasoningTextValidatorsForTest
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.KeyRoulette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatCompletions 流式「结构 400 重发」的连接管理回归测试（AI-3）。
 *
 * 用**fake EventSource.Factory** 驱动 `ChatCompletionsAPI.streamText` 里真实的 listener 回调，
 * 覆盖纯函数测不到的三类竞态：
 * 1. 异步结构失败重发后，新 EventSource 必须纳入 `awaitClose` 的取消范围（取消时不泄漏连接）；
 * 2. 同步建连失败只允许创建一个替代连接（旧实现会多建一个未纳管连接）；
 * 3. 旧 source 的迟到 `onEvent`/`onClosed` 不得污染或关闭已被替换的新 stream。
 */
class ChatCompletionsStreamRetryTest {

    private companion object {
        const val STRUCTURAL_ERROR =
            "400 Bad Request: The reasoning_content in the thinking mode must be passed back to the API."
        const val OTHER_ERROR = "401 Unauthorized: invalid api key"
        const val CHUNK_OLD =
            """{"id":"old","model":"m","choices":[{"index":0,"delta":{"content":"old"},"finish_reason":null}]}"""
        const val CHUNK_NEW =
            """{"id":"new","model":"m","choices":[{"index":0,"delta":{"content":"new"},"finish_reason":null}]}"""
    }

    @Before
    fun resetLearnedHosts() {
        // 自学习集合是进程级全局状态：用例之间必须隔离，否则后面的用例会「本来就回填」而不再重发。
        resetLearnedReasoningTextValidatorsForTest()
    }

    // ---- 异步结构失败：只重发一次 + 旧 source 隔离 ----

    @Test
    fun `async structural failure resends once and the old source cannot poison the new stream`() = runBlocking {
        val factory = FakeEventSourceFactory()
        val api = api(factory)
        val chunks = CopyOnWriteArrayList<MessageChunk>()
        val job = launch(Dispatchers.Default) {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).collect { chunks += it }
        }

        awaitSources(factory, 1)
        val first = factory.sources[0]
        // 第一次尝试：网关的异步结构校验失败（不带 HTTP 对象，走 Throwable 分支）
        first.listener.onFailure(first, IllegalStateException(STRUCTURAL_ERROR), null)

        awaitSources(factory, 2)
        assertEquals("结构 400 只允许重发一次请求", 2, factory.requests.size)
        assertTrue("被替换的旧连接必须 cancel，否则连接泄漏", first.canceled)

        // 旧 source 迟到的回调：既不能关闭新 stream，也不能把事件送进来，更不能再次触发重试
        first.listener.onClosed(first)
        first.listener.onEvent(first, null, null, CHUNK_OLD)
        first.listener.onFailure(first, IllegalStateException(OTHER_ERROR), null)
        assertTrue("旧 source 的 onClosed 不得关闭已被替换的新 stream", job.isActive)
        assertEquals("旧 source 的迟到 onFailure 不得触发额外连接", 2, factory.requests.size)

        val second = factory.sources[1]
        assertFalse(second.canceled)
        second.listener.onEvent(second, null, null, CHUNK_NEW)
        second.listener.onClosed(second)
        job.join()

        assertEquals("只应收到新 source 的 chunk（旧 source 的必须被丢弃）", 1, chunks.size)
    }

    @Test
    fun `structural failure is retried at most once`() = runBlocking {
        val factory = FakeEventSourceFactory()
        val api = api(factory)
        val driver = launch(Dispatchers.Default) {
            awaitSources(factory, 1)
            val first = factory.sources[0]
            first.listener.onFailure(first, IllegalStateException(STRUCTURAL_ERROR), null)
            awaitSources(factory, 2)
            // 新连接仍然是同一个结构错误：不允许再重发
            val second = factory.sources[1]
            second.listener.onFailure(second, IllegalStateException(STRUCTURAL_ERROR), null)
        }

        val error = runCatching {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
        }.exceptionOrNull()
        driver.join()

        assertEquals("最多重发一次：总共两个请求", 2, factory.requests.size)
        assertNotNull("第二次仍结构失败时必须把错误抛给调用方", error)
        assertTrue(
            "抛出的应是网关的结构错误：$error",
            error.toString().contains("reasoning_content"),
        )
    }

    // ---- 同步建连失败：只建一个替代连接 ----

    @Test
    fun `synchronous creation failure resends exactly one connection`() = runBlocking {
        val factory = FakeEventSourceFactory(failFirstCall = IllegalStateException(STRUCTURAL_ERROR))
        val api = api(factory)
        val driver = launch(Dispatchers.Default) {
            awaitRequests(factory, 2)
            awaitSources(factory, 1)
            val only = factory.sources[0]
            only.listener.onClosed(only)
        }

        val chunks = api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
        driver.join()

        assertEquals("同步失败只允许创建一个替代连接（旧实现会多建一个）", 2, factory.requests.size)
        assertEquals("只应存在一个被接管的事件源", 1, factory.sources.size)
        assertTrue(chunks.isEmpty())
    }

    // ---- 非结构错误不重试 ----

    @Test
    fun `non structural failure does not retry`() = runBlocking {
        val factory = FakeEventSourceFactory()
        val api = api(factory)
        val driver = launch(Dispatchers.Default) {
            awaitSources(factory, 1)
            val first = factory.sources[0]
            first.listener.onFailure(first, IllegalStateException(OTHER_ERROR), null)
        }

        val error = runCatching {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
        }.exceptionOrNull()
        driver.join()

        assertEquals("非结构错误不得重发", 1, factory.requests.size)
        assertNotNull(error)
        assertTrue(error.toString().contains("invalid api key"))
    }

    // ---- 取消竞态：取消时必须取消「当前」（重发后）的 source ----

    @Test
    fun `cancelling the stream cancels the replaced source`() = runBlocking {
        val factory = FakeEventSourceFactory()
        val api = api(factory)
        val job: Job = launch(Dispatchers.Default) {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).collect { }
        }

        awaitSources(factory, 1)
        val first = factory.sources[0]
        first.listener.onFailure(first, IllegalStateException(STRUCTURAL_ERROR), null)
        awaitSources(factory, 2)
        val second = factory.sources[1]

        job.cancelAndJoin()
        awaitCanceled(second)

        assertTrue("取消应作用到重发后的新 source（不是首次创建的那个）", second.canceled)
        assertTrue("被替换的旧 source 也应在切换时被取消", first.canceled)
    }

    // ---- tracker 本体的边界（纯逻辑） ----

    @Test
    fun `tracker rejects activation after close and cancels the late source`() {
        val tracker = StreamingEventSourceTracker()
        val attempt = tracker.newAttempt()
        tracker.close()

        val late = FakeEventSource(Request.Builder().url("https://fake.test/v1").build(), NoopListener)
        assertFalse("关闭后不得再接管新 source", tracker.activate(attempt, late))
        assertTrue("关闭后迟到的新 source 必须被立即取消", late.canceled)
        assertFalse(tracker.isActive(late))
    }

    @Test
    fun `tracker activation replaces and cancels the previous source`() {
        val tracker = StreamingEventSourceTracker()
        val request = Request.Builder().url("https://fake.test/v1").build()
        val first = FakeEventSource(request, NoopListener)
        val second = FakeEventSource(request, NoopListener)

        val firstAttempt = tracker.newAttempt()
        assertTrue(tracker.activate(firstAttempt, first))
        assertTrue(tracker.isActive(first))

        val secondAttempt = tracker.newAttempt()
        assertTrue(tracker.activate(secondAttempt, second))
        assertTrue("新 source 接管后旧 source 必须取消", first.canceled)
        assertTrue(tracker.isActive(second))
        assertFalse(tracker.isActive(first))
    }

    @Test
    fun `synchronous callback of the current attempt claims the source`() {
        // 模拟 OkHttp 在 newEventSource 返回前同步回调：activate 还没执行。
        val tracker = StreamingEventSourceTracker()
        val request = Request.Builder().url("https://fake.test/v1").build()
        val attempt = tracker.newAttempt()
        val early = FakeEventSource(request, NoopListener)

        assertTrue("当前 attempt 的同步回调必须能认领 active", tracker.allowsCallback(attempt, early))
        assertTrue(tracker.isActive(early))
        // 之后正常 activate 同一 source 不应取消它。
        assertTrue(tracker.activate(attempt, early))
        assertTrue(tracker.isActive(early))
        assertFalse(early.canceled)
    }

    @Test
    fun `stale attempt can never reclaim the active source`() {
        val tracker = StreamingEventSourceTracker()
        val request = Request.Builder().url("https://fake.test/v1").build()
        val old = FakeEventSource(request, NoopListener)
        val fresh = FakeEventSource(request, NoopListener)

        val oldAttempt = tracker.newAttempt()
        assertTrue(tracker.allowsCallback(oldAttempt, old)) // 旧 attempt 曾认领过
        val freshAttempt = tracker.newAttempt()
        assertTrue(tracker.allowsCallback(freshAttempt, fresh)) // 新 attempt 抢占并取消旧连接
        assertTrue(old.canceled)
        assertFalse("旧 attempt 的回调不得重新抢占", tracker.allowsCallback(oldAttempt, old))
        assertFalse("旧 attempt 也不得再 activate", tracker.activate(oldAttempt, old))
        assertTrue(tracker.isActive(fresh))
    }

    // ---- 同步工厂回调（newEventSource 返回前回调）：不能丢首事件/永久不结束 ----

    @Test
    fun `callbacks fired before newEventSource returns are not lost`() = runBlocking {
        val factory = FakeEventSourceFactory(
            syncCallbacks = listOf { source, listener ->
                // OkHttp 可能在新连接创建后立刻在调用线程上回调。
                listener.onEvent(source, null, null, CHUNK_NEW)
                listener.onEvent(source, null, null, "[DONE]")
            },
        )
        val api = api(factory)

        val chunks = withTimeout(5_000) {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
        }

        assertEquals("同步回调的首个 chunk 不能丢", 1, factory.requests.size)
        assertTrue("应收到内容 chunk + 显式结束标记", chunks.size >= 2)
    }

    @Test
    fun `synchronous structural failure during creation retries once without hanging`() = runBlocking {
        val factory = FakeEventSourceFactory(
            syncCallbacks = listOf(
                // 第 1 次建连：还没返回就同步失败（结构 400）
                { source, listener ->
                    listener.onFailure(source, IllegalStateException(STRUCTURAL_ERROR), null)
                },
                // 第 2 次建连：同步正常收尾
                { source, listener -> listener.onClosed(source) },
            ),
        )
        val api = api(factory)

        val chunks = withTimeout(5_000) {
            api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
        }

        assertEquals("同步结构失败只重发一次（共两个请求）", 2, factory.requests.size)
        assertEquals(2, factory.sources.size)
        awaitCanceled(factory.sources[0])
        assertTrue("同步失败后返回的旧连接必须被丢弃/取消", factory.sources[0].canceled)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `synchronous non structural failure during creation surfaces the error`() = runBlocking {
        val factory = FakeEventSourceFactory(
            syncCallbacks = listOf { source, listener ->
                listener.onFailure(source, IllegalStateException(OTHER_ERROR), null)
            },
        )
        val api = api(factory)

        val error = withTimeout(5_000) {
            runCatching {
                api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
            }.exceptionOrNull()
        }

        assertEquals("非结构错误不得重发", 1, factory.requests.size)
        assertNotNull(error)
        assertTrue(error.toString().contains("invalid api key"))
    }

    // ---- 建连/同步回调抛错时也必须回收已 claim 的 source（try/finally 覆盖） ----

    @Test
    fun `synchronous parse exception during creation cannot leak the claimed source`() = runBlocking {
        val factory = FakeEventSourceFactory(
            syncCallbacks = listOf { source, listener ->
                // 回调里 JSON 解析异常直接 throw → 同步从 newEventSource 冒出、flow 在
                // awaitClose 之前就失败。try/finally 必须负责 tracker.close + cancel。
                // ⚠️ 不能用 "not-json"：kotlinx-serialization 会把它当裸 literal 解析成
                // JsonPrimitive（jsonObjectOrNull 为 null，静默丢弃），不会抛异常。
                listener.onEvent(source, null, null, "{invalid")
            },
        )
        val api = api(factory)

        val error = withTimeout(5_000) {
            runCatching {
                api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
            }.exceptionOrNull()
        }

        assertNotNull("解析异常必须抛给调用方，不能挂起", error)
        assertEquals("同步异常不应触发重发", 1, factory.requests.size)
        assertEquals(1, factory.sources.size)
        awaitCanceled(factory.sources[0])
        assertTrue("已 claim 的 source 必须在失败路径被取消（try/finally）", factory.sources[0].canceled)
    }

    @Test
    fun `failure without throwable or body closes with IOException instead of success`() = runBlocking {
        val factory = FakeEventSourceFactory()
        val api = api(factory)
        val driver = launch(Dispatchers.Default) {
            awaitSources(factory, 1)
            val source = factory.sources[0]
            source.listener.onFailure(source, null, null)
        }

        val error = withTimeout(5_000) {
            runCatching {
                api.streamText(provider(), listOf(UIMessage.user("hi")), params()).toList()
            }.exceptionOrNull()
        }
        driver.join()

        assertNotNull("onFailure(t=null, response=null) 不得当成正常结束", error)
        assertTrue("没有异常对象时应兜底为 IOException：$error", error is IOException)
        assertEquals(1, factory.requests.size)
    }

    // ---- helpers ----

    private fun api(factory: EventSource.Factory): ChatCompletionsAPI =
        ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()) { factory }

    private fun provider(): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
        name = "retry-test",
        apiKey = "sk-test",
        baseUrl = "https://fake.test/v1",
    )

    private fun params(): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "test-model"),
    )

    private suspend fun awaitSources(factory: FakeEventSourceFactory, count: Int) {
        withTimeout(5_000) {
            while (factory.sources.size < count) delay(5)
        }
    }

    private suspend fun awaitRequests(factory: FakeEventSourceFactory, count: Int) {
        withTimeout(5_000) {
            while (factory.requests.size < count) delay(5)
        }
    }

    private suspend fun awaitCanceled(source: FakeEventSource) {
        withTimeout(5_000) {
            while (!source.canceled) delay(5)
        }
    }

    private class FakeEventSource(
        private val originalRequest: Request,
        val listener: EventSourceListener,
    ) : EventSource {
        @Volatile
        private var canceledFlag = false

        val canceled: Boolean get() = canceledFlag

        override fun request(): Request = originalRequest

        override fun cancel() {
            canceledFlag = true
        }
    }

    private class FakeEventSourceFactory(
        private val failFirstCall: Throwable? = null,
        /**
         * 每次建连（按请求序号）在 `newEventSource` **返回之前**要同步触发的回调。
         * 用来模拟 OkHttp「新连接创建后立即在调用线程回调」的真实时序。
         */
        private val syncCallbacks: List<(EventSource, EventSourceListener) -> Unit> = emptyList(),
    ) : EventSource.Factory {
        val requests = CopyOnWriteArrayList<Request>()
        val sources = CopyOnWriteArrayList<FakeEventSource>()

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
            requests += request
            if (requests.size == 1 && failFirstCall != null) throw failFirstCall
            val source = FakeEventSource(request, listener)
            sources += source
            syncCallbacks.getOrNull(requests.size - 1)?.invoke(source, listener)
            return source
        }
    }

    private object NoopListener : EventSourceListener()
}
