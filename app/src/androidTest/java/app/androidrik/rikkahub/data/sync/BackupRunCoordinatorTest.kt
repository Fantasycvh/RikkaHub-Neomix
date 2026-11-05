package app.androidrik.rikkahub.data.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.AppScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 备份长任务执行器：验证「离开页面（调用方取消）后任务继续跑完」这一核心语义。
 *
 * 这是本功能的关键回归点：页面 ViewModel 被销毁会取消 viewModelScope，而备份任务必须
 * 挂在应用级作用域上继续；同时通知栏取消/进度与单任务约束也要生效。
 */
@RunWith(AndroidJUnit4::class)
class BackupRunCoordinatorTest {

    private val app: Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun newCoordinator() = BackupRunCoordinator(app, AppScope())

    @Test
    fun taskContinuesAfterCallerIsCancelled() = runBlocking {
        val coordinator = newCoordinator()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val caller = launch(Dispatchers.Default) {
            coordinator.await(BackupRunKind.FULL_BACKUP) { _ ->
                started.complete(Unit)
                gate.await()
                finished.complete(Unit)
            }
        }
        withTimeout(5_000) { started.await() }
        assertNotNull("任务应处于进行中", coordinator.state.value)

        // 模拟「离开备份页」：页面协程被取消
        caller.cancelAndJoin()
        assertNotNull("调用方取消不应取消任务", coordinator.state.value)

        // 任务继续跑完
        gate.complete(Unit)
        withTimeout(5_000) { finished.await() }
        awaitIdle(coordinator)
        assertNull("完成后应回到空闲", coordinator.state.value)
    }

    @Test
    fun rejectsSecondTaskWhileRunning() = runBlocking {
        val coordinator = newCoordinator()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        val first = launch(Dispatchers.Default) {
            coordinator.await(BackupRunKind.FULL_BACKUP) { _ ->
                started.complete(Unit)
                gate.await()
            }
        }
        withTimeout(5_000) { started.await() }

        try {
            coordinator.await(BackupRunKind.SNAPSHOT_GC) { }
            fail("同一时刻只允许一个长任务")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("已有") == true)
        }

        gate.complete(Unit)
        first.join()
        awaitIdle(coordinator)
    }

    @Test
    fun progressIsExposedAndCleared() = runBlocking {
        val coordinator = newCoordinator()
        val reported = CompletableDeferred<Float>()

        val caller = launch(Dispatchers.Default) {
            coordinator.await(BackupRunKind.EXPORT_BACKUP) { report ->
                report(BackupProgress(BackupPhase.UPLOADING, processedBytes = 50, totalBytes = 100))
                val fraction = withTimeout(5_000) {
                    // 等待 coordinator 把进度写进 state
                    var value: Float? = null
                    while (value == null) {
                        value = coordinator.state.value?.progress?.fraction
                        if (value == null) delay(10)
                    }
                    value
                }
                reported.complete(fraction)
            }
        }
        withTimeout(5_000) { caller.join() }
        assertEquals(0.5f, withTimeout(5_000) { reported.await() }, 0.001f)
        awaitIdle(coordinator)
        assertNull(coordinator.state.value)
    }

    @Test
    fun cancelStopsRunningTask() = runBlocking {
        val coordinator = newCoordinator()
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var cancelled = false

        val caller = launch(Dispatchers.Default) {
            try {
                coordinator.await(BackupRunKind.SNAPSHOT_COMMIT) { _ ->
                    started.complete(Unit)
                    never.await()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw kotlinx.coroutines.CancellationException("task cancelled")
            }
        }
        withTimeout(5_000) { started.await() }
        coordinator.cancel()
        withTimeout(5_000) { caller.join() }
        assertTrue("通知栏取消应取消任务协程", cancelled)
        awaitIdle(coordinator)
    }

    /** 轮询等待任务收尾（finally 里清 state），避免依赖内部实现时序。 */
    private suspend fun awaitIdle(coordinator: BackupRunCoordinator) {
        withTimeout(5_000) {
            while (coordinator.state.value != null) delay(20)
        }
    }
}
