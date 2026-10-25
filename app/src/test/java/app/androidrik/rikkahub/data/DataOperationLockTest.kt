package app.androidrik.rikkahub.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 备份/恢复与存储清理互斥（`docs/design/design-storage-management.md` §5.2 红线 5）。
 *
 * 真机上要触发「备份进行中」需要可用的 WebDAV/S3，故用进程级闸门的单测钉死语义。
 */
class DataOperationLockTest {

    @Test
    fun `storage cleanup is rejected while backup holds the gate`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            DataOperationLock.runWaiting(DataOperation.BACKUP) {
                started.complete(Unit)
                release.await()
            }
        }

        withTimeout(5_000) { started.await() }
        try {
            assertEquals(DataOperation.BACKUP, DataOperationLock.active.value)
            assertNull(
                DataOperationLock.runImmediate(DataOperation.STORAGE_CLEANUP) { "cleaned" }
            )
        } finally {
            release.complete(Unit)
            holder.join()
        }

        assertNull(DataOperationLock.active.value)
        assertEquals(
            "cleaned",
            DataOperationLock.runImmediate(DataOperation.STORAGE_CLEANUP) { "cleaned" },
        )
    }

    @Test
    fun `active reports the running operation and clears afterwards`() = runBlocking {
        assertEquals(
            DataOperation.STORAGE_CLEANUP,
            DataOperationLock.runImmediate(DataOperation.STORAGE_CLEANUP) {
                DataOperationLock.active.value
            },
        )
        assertNull(DataOperationLock.active.value)
        assertEquals(2, DataOperationLock.runWaiting(DataOperation.BACKUP) { 2 })
    }
}
