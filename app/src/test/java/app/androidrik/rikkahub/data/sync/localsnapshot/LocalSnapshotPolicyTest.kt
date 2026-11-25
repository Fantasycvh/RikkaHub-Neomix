package app.androidrik.rikkahub.data.sync.localsnapshot

import app.androidrik.rikkahub.data.datastore.LocalSnapshotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class LocalSnapshotPolicyTest {
    private val now = LocalDateTime.of(2026, 11, 25, 10, 0)

    @Test
    fun `accepts only timestamped ZIP filenames with a real date`() {
        assertEquals("local_backup_20261125_100000.zip", LocalSnapshot.fileName(now))
        assertNull(LocalSnapshot.fromDocument("1", "local_backup_20260231_100000.zip", 42))
        assertNull(LocalSnapshot.fromDocument("2", "local_backup_20261125_100000.zip.partial", 42))
        assertNull(LocalSnapshot.fromDocument("3", "personal.zip", 42))
    }

    @Test
    fun `keeps recent weekly and monthly copies and deletes oldest first`() {
        val snapshots = listOf(9L, 0, 1, 31, 2, 7, 3, 30).map(::copyAtAge)
        assertEquals(listOf("31", "9", "3"), LocalSnapshotRetentionPolicy.deletions(snapshots, now, 3).map { it.id })
    }

    @Test
    fun `empty file cannot displace the newest nonempty copy`() {
        val empty = copyAtAge(0).copy(bytes = 0)
        val onlyValid = copyAtAge(1)
        assertEquals(onlyValid, LocalSnapshotRetentionPolicy.newestNonEmpty(listOf(empty, onlyValid)))
        assertEquals(listOf(empty), LocalSnapshotRetentionPolicy.deletions(listOf(empty, onlyValid), now, 1))
    }

    @Test
    fun `retry backs off and skips never advance the last success`() {
        val config = LocalSnapshotConfig(enabled = true, treeUri = "tree", intervalDays = 1)
        val savedAt = 2.days.inWholeMilliseconds
        val dueAt = savedAt + 1.days.inWholeMilliseconds
        val latest = copyAtAge(0)
        var state = LocalSnapshotState(
            lastSuccessAt = savedAt,
            lastArchiveName = latest.name,
            lastArchiveBytes = latest.bytes,
            fingerprint = "hash",
        )
        assertFalse(state.isDue(config, dueAt - 1))
        assertTrue(state.isDue(config, dueAt))
        assertTrue(state.isUnchanged("hash", latest))
        assertFalse(state.isUnchanged("hash", null))
        assertFalse(state.isUnchanged("hash", latest.copy(bytes = 1)))
        assertFalse(state.isUnchanged(null, latest))
        assertFalse(state.isUnchanged("another", latest))
        assertEquals(savedAt, state.copy(status = LocalSnapshotStatus.UNCHANGED).lastSuccessAt)
        for (hours in listOf(1, 2, 4, 8, 16, 16)) {
            state = state.failed(dueAt, LocalSnapshotStatus.PERMISSION_REQUIRED)
            assertEquals(dueAt + hours.hours.inWholeMilliseconds, state.retryAt)
            assertEquals(savedAt, state.lastSuccessAt)
            assertFalse(state.isDue(config, state.retryAt - 1))
            assertTrue(state.isDue(config, state.retryAt))
        }
    }

    private fun copyAtAge(age: Long): LocalSnapshot {
        val time = now.minusDays(age)
        return requireNotNull(LocalSnapshot.fromDocument(age.toString(), LocalSnapshot.fileName(time), 42))
    }
}
