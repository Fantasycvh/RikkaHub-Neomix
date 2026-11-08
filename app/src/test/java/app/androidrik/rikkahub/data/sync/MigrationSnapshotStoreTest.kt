package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 升级前快照（借鉴 NMoi `65e13bf`）：版本落后才快照、失败不落半成品、滚动只留最新一份。
 */
class MigrationSnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeVacuumInto(source: String, target: String) {
        File(target).writeText("snapshot-of-" + File(source).name)
    }

    @Test
    fun `skips when database file is missing`() {
        val filesDir = tmp.newFolder("files")
        assertNull(
            MigrationSnapshotStore.snapshotBeforeUpgrade(
                filesDir, File(filesDir, "nope.db"), 57, { 0 }, ::fakeVacuumInto,
            )
        )
    }

    @Test
    fun `skips fresh database and up-to-date database`() {
        val filesDir = tmp.newFolder("files")
        val db = tmp.newFile("app.db").apply { writeText("db") }
        // 版本 0 = 全新建库，无迁移发生
        assertNull(
            MigrationSnapshotStore.snapshotBeforeUpgrade(filesDir, db, 57, { 0 }, ::fakeVacuumInto)
        )
        // 版本已达标
        assertNull(
            MigrationSnapshotStore.snapshotBeforeUpgrade(filesDir, db, 57, { 57 }, ::fakeVacuumInto)
        )
    }

    @Test
    fun `snapshots when version is behind target`() {
        val filesDir = tmp.newFolder("files")
        val db = tmp.newFile("app.db").apply { writeText("db") }
        val snapshot = MigrationSnapshotStore.snapshotBeforeUpgrade(
            filesDir, db, 57, { 56 }, ::fakeVacuumInto,
        )
        assertNotNull(snapshot)
        assertEquals("pre-migration-v56.bak", snapshot!!.name)
        assertTrue(snapshot.length() > 0)
    }

    @Test
    fun `reuses existing snapshot for the same version`() {
        val filesDir = tmp.newFolder("files")
        val db = tmp.newFile("app.db").apply { writeText("db") }
        val first = MigrationSnapshotStore.snapshotBeforeUpgrade(
            filesDir, db, 57, { 56 }, ::fakeVacuumInto,
        )!!
        val second = MigrationSnapshotStore.snapshotBeforeUpgrade(
            filesDir, db, 57, { 56 }, ::fakeVacuumInto,
        )
        assertEquals(first, second)
    }

    @Test
    fun `prunes stale snapshots keeping newest`() {
        val dir = tmp.newFolder("migration_snapshots")
        File(dir, "pre-migration-v54.bak").writeText("old")
        File(dir, "pre-migration-v55.bak").writeText("new")
        File(dir, "pre-migration-v56.bak.tmp-123").writeText("orphan")
        MigrationSnapshotStore.prune(dir, keep = 1)
        // prune 只管快照滚动；tmp 残留由 snapshotBeforeUpgrade 开头清理，不在本契约内
        val remaining = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("pre-migration-v55.bak", "pre-migration-v56.bak.tmp-123"), remaining)
    }

    @Test
    fun `failed vacuum leaves no tmp behind and rethrows`() {
        val filesDir = tmp.newFolder("files")
        val db = tmp.newFile("app.db").apply { writeText("db") }
        try {
            MigrationSnapshotStore.snapshotBeforeUpgrade(filesDir, db, 57, { 56 }, { _, _ ->
                error("disk full")
            })
            org.junit.Assert.fail("expected exception")
        } catch (expected: IllegalStateException) {
            // 快照失败必须不落半成品（调用方 fail-open 继续，但残留 tmp 会一直躺在目录里）
            val dir = File(filesDir, "migration_snapshots")
            assertTrue(dir.listFiles()!!.none { it.name.contains(".tmp-") })
        }
    }
}
