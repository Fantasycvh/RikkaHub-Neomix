package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.data.ai.compression.CompressionProgress
import app.androidrik.rikkahub.data.ai.compression.CompressionProgressStage
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 手动压缩后台任务注册表（design-manual-compress-background §7 验收 1）：
 * 单飞 cancel-and-replace、进度状态映射、清理竞态防护（被顶掉者不清继任者状态）。
 */
class ManualCompressionRegistryTest {

    private val registry = ManualCompressionRegistry()
    private val conversation = Uuid.random()

    private fun progress(stage: CompressionProgressStage = CompressionProgressStage.PREPARING) =
        CompressionProgress(stage)

    private fun defaultParams() = ManualCompressionParams(
        conversationId = conversation,
        windowTokens = 128_000,
    )

    @Test
    fun `same params on active job are idempotent`() {
        // §4.1「参数相同则幂等」：同会话同参数且任务在飞 → 不重复起任务
        val job = Job()
        val params = defaultParams()
        registry.replace(conversation, job, params)

        assertTrue(registry.isDuplicateOfActive(conversation, params))
        // 参数变化（同会话）→ 不幂等，正常走 cancel-and-replace
        assertFalse(
            registry.isDuplicateOfActive(
                conversation,
                params.copy(continueInNewWindow = true),
            ),
        )
        // 任务结束后同参数不再幂等（需要重新发起）
        job.complete()
        assertFalse(registry.isDuplicateOfActive(conversation, params))
    }

    @Test
    fun `same conversation replaces and cancels previous job`() {
        val first = Job()
        val second = Job()

        assertFalse(registry.replace(conversation, first, defaultParams()))
        assertTrue(registry.isActive(conversation))

        assertTrue(registry.replace(conversation, second, defaultParams()))
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
        assertTrue(registry.isActive(conversation))
    }

    @Test
    fun `different conversations are isolated`() {
        val other = Uuid.random()
        val a = Job()
        val b = Job()
        registry.replace(conversation, a, defaultParams())
        registry.replace(other, b, defaultParams())

        registry.cancel(conversation)

        assertTrue(a.isCancelled)
        assertFalse(b.isCancelled)
        assertTrue(registry.isActive(other))
    }

    @Test
    fun `completed job unregisters itself and clears progress state`() {
        val job = Job()
        registry.replace(conversation, job, defaultParams())
        registry.updateProgress(conversation, job, progress())
        assertEquals(progress(), registry.states.value[conversation])

        job.complete()

        assertFalse(registry.isActive(conversation))
        assertNull(registry.states.value[conversation])
    }

    @Test
    fun `progress state maps per conversation`() {
        val other = Uuid.random()
        val jobA = Job()
        val jobB = Job()
        registry.replace(conversation, jobA, defaultParams())
        registry.replace(other, jobB, defaultParams())

        registry.updateProgress(conversation, jobA, progress(CompressionProgressStage.CHUNK))
        registry.updateProgress(other, jobB, progress(CompressionProgressStage.VALIDATING))

        assertEquals(
            CompressionProgressStage.CHUNK,
            registry.states.value[conversation]?.stage,
        )
        assertEquals(
            CompressionProgressStage.VALIDATING,
            registry.states.value[other]?.stage,
        )
    }

    @Test
    fun `stale progress from replaced job cannot resurrect state`() {
        // 单飞顶掉旧任务后，旧任务经迟到 onProgress 写入必须被身份校验拦下
        val stale = Job()
        registry.replace(conversation, stale, defaultParams())
        registry.updateProgress(conversation, stale, progress())
        val current = Job()
        registry.replace(conversation, current, defaultParams())
        registry.updateProgress(conversation, current, progress(CompressionProgressStage.CHUNK))

        registry.updateProgress(conversation, stale, progress(CompressionProgressStage.FALLBACK))

        assertEquals(
            CompressionProgressStage.CHUNK,
            registry.states.value[conversation]?.stage,
        )
    }

    @Test
    fun `replaced job completion does not clear successor progress`() {
        // 清理竞态防护（§4.1）：旧任务被顶掉，其 completion 清理不得把继任者
        // 刚写入的阶段状态一并清掉
        val stale = Job()
        registry.replace(conversation, stale, defaultParams())
        val current = Job()
        registry.replace(conversation, current, defaultParams())
        registry.updateProgress(conversation, current, progress(CompressionProgressStage.CHUNK))

        stale.complete()  // 触发旧任务 completion：应只注销自己，不动继任者状态

        assertTrue(registry.isActive(conversation))
        assertEquals(
            CompressionProgressStage.CHUNK,
            registry.states.value[conversation]?.stage,
        )

        current.complete()  // 继任者结束：此时才清状态
        assertNull(registry.states.value[conversation])
    }

    @Test
    fun `cancel clears progress state via completion`() {
        val job = Job()
        registry.replace(conversation, job, defaultParams())
        registry.updateProgress(conversation, job, progress())

        registry.cancel(conversation)

        assertTrue(job.isCancelled)
        assertNull(registry.states.value[conversation])
        assertFalse(registry.isActive(conversation))
    }
}
