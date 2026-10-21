package app.androidrik.rikkahub.service

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-8 回归：拼装预览 / 上下文容量面板的重活必须离开主线程。
 *
 * 缺陷形态：`inspectPromptAssembly` 会在调用方线程上跑完整 prompt 组装 + 逐段真 BPE 分词，
 * 两个调用方分别位于 `viewModelScope`（Main）和 `produceState`（组合上下文 = Main），
 * 长会话打开面板/预览会卡死主线程直到 ANR。
 *
 * 修复点在 [ChatService.inspectPromptAssembly] **入口**：快照（settings/conversation）留在
 * 调用方上下文读（`getConversationFlow` 的 session 创建有 Main 线程约定），重活（组装 +
 * 分词 + 记忆读取 + 工具装配）经 [withPromptInspectionDispatcher]（默认
 * [Dispatchers.Default]）执行，所以所有调用方、以及未来新增调用都自动覆盖。
 * 这里测的是那个 helper 的**真实行为**：
 * 1. 换线程（不在调用线程上跑）；
 * 2. 返回后回到调用方上下文（调用方可以直接继续写 UI 状态）；
 * 3. 取消能从调用方传播进去、并以 `CancellationException` 形式传出来（不被 catch 吞掉）；
 * 4. 业务异常原样抛出。
 *
 * 为什么不写「扫描 ChatService 源码里有没有 withContext」的镜像测试：那只能钉住写法，
 * 钉不住「真的换线程」，而且在 `withContext` 被包进别的辅助函数、或 block 被内联优化时
 * 会误报。入口与 helper 的绑定由 `inspectPromptAssembly` 的 KDoc 约束（`ChatService.kt`
 * 「线程」小节），helper 本身的行为由本测试钉死。
 */
class PromptInspectionDispatchTest {

    @Test
    fun `heavy block runs on the provided dispatcher, not on the caller thread`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "prompt-inspection-test")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            val executionThread = withPromptInspectionDispatcher(dispatcher) {
                Thread.currentThread().name
            }
            assertNotEquals(
                "重活不能在调用线程（主线程）上执行",
                callerThread,
                executionThread,
            )
            assertTrue(
                "重活应跑在注入的 dispatcher 上，实际线程=$executionThread",
                executionThread.startsWith("prompt-inspection-test"),
            )
            assertEquals(
                "await 返回后必须回到调用方上下文，调用方才能直接写 UI 状态",
                callerThread,
                Thread.currentThread().name,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `default dispatcher is not the caller thread`() = runBlocking {
        val callerThread = Thread.currentThread().name
        val executionThread = withPromptInspectionDispatcher {
            Thread.currentThread().name
        }
        assertNotEquals(callerThread, executionThread)
    }

    @Test
    fun `cancellation of the caller job stops the background work`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val started = CompletableDeferred<Unit>()
            val job = launch {
                withPromptInspectionDispatcher(dispatcher) {
                    started.complete(Unit)
                    // 模拟长文 BPE 分词：取消必须能打断它，否则面板关闭后仍在烧 CPU
                    delay(60_000)
                }
            }
            started.await()
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue("后台重活应随调用方取消", job.isCancelled)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancellation exception is rethrown, not swallowed by the helper`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val outcome = runCatching {
                withTimeout(50) {
                    withPromptInspectionDispatcher(dispatcher) {
                        delay(10_000)
                    }
                }
            }
            assertTrue(
                "取消必须以 TimeoutCancellationException 形式传出，实际=${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull() is TimeoutCancellationException,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `business exceptions from the block propagate unchanged`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val outcome = runCatching {
                withPromptInspectionDispatcher(dispatcher) {
                    error("组装失败")
                }
            }
            assertTrue(
                "业务异常应原样抛出（调用方决定如何展示），实际=${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull() is IllegalStateException,
            )
        } finally {
            executor.shutdownNow()
        }
    }
}
