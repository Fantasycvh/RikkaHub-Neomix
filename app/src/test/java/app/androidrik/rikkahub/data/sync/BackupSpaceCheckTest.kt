package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 空间预检的纯文件逻辑（[BackupSpaceCheck]）与进度模型（[BackupProgress]/[ProgressThrottle]）。
 *
 * 对应设计见 `docs/design/design-backup-workspace-files.md` §9.1。修复动机：数 GB 备份跑到一半失败
 * 代价过大，需在开始前用「保守估值」拦下。
 */
class BackupSpaceCheckTest {

    private fun tempDir(tag: String): File =
        File("build/tmp/test-space-$tag").absoluteFile.apply {
            deleteRecursively()
            mkdirs()
        }

    // ===== 走盘求和 =====

    @Test
    fun `totalSize sums files recursively`() {
        val dir = tempDir("sum")
        File(dir, "a.txt").writeBytes(ByteArray(100))
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "b.bin").writeBytes(ByteArray(250))
        File(sub, "c.bin").writeBytes(ByteArray(50))

        assertEquals(400L, BackupSpaceCheck.totalSize(dir))
    }

    @Test
    fun `totalSize of missing path is zero`() {
        assertEquals(0L, BackupSpaceCheck.totalSize(File("build/tmp/definitely-missing-xyz")))
    }

    @Test
    fun `estimateBackupBytes sums multiple inputs`() {
        val dir = tempDir("multi")
        val f1 = File(dir, "db").apply { writeBytes(ByteArray(1000)) }
        val f2 = File(dir, "wal").apply { writeBytes(ByteArray(500)) }
        val d = File(dir, "workspace").apply { mkdirs() }
        File(d, "x").writeBytes(ByteArray(300))

        assertEquals(1800L, BackupSpaceCheck.estimateBackupBytes(listOf(f1, f2, d)))
    }

    // ===== zip 中央目录求和 =====

    @Test
    fun `uncompressedSizeOfZip reads central directory without extracting`() {
        val dir = tempDir("zip")
        val payloadA = ByteArray(4096) { (it % 251).toByte() }
        val payloadB = ByteArray(777) { (it % 13).toByte() }
        val zip = File(dir, "t.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.bin")); out.write(payloadA); out.closeEntry()
            out.putNextEntry(ZipEntry("b.bin")); out.write(payloadB); out.closeEntry()
        }

        assertEquals((payloadA.size + payloadB.size).toLong(), BackupSpaceCheck.uncompressedSizeOfZip(zip))
    }

    @Test
    fun `uncompressedSizeOfZip returns null for non-zip`() {
        val dir = tempDir("badzip")
        val f = File(dir, "not.zip").apply { writeText("hello world") }
        assertNull(BackupSpaceCheck.uncompressedSizeOfZip(f))
    }

    // ===== 空间判据 =====

    @Test
    fun `hasEnoughSpace respects headroom`() {
        // 需求 1000，5% 余量 -> 需 ~1050
        assertTrue(BackupSpaceCheck.hasEnoughSpace(available = 1100, needed = 1000))
        assertFalse(BackupSpaceCheck.hasEnoughSpace(available = 1040, needed = 1000))
        // 正好等于需求仍不足（有 5% 余量要求）
        assertFalse(BackupSpaceCheck.hasEnoughSpace(available = 1000, needed = 1000))
    }

    @Test
    fun `hasEnoughSpace trivially true when nothing needed`() {
        assertTrue(BackupSpaceCheck.hasEnoughSpace(available = 0, needed = 0))
    }
}

/** 进度模型与节流器。 */
class BackupProgressTest {

    @Test
    fun `fraction computes and clamps`() {
        assertEquals(0.5f, BackupProgress(BackupPhase.PACKING, 50, 100).fraction)
        assertEquals(1f, BackupProgress(BackupPhase.PACKING, 200, 100).fraction)
        assertEquals(0f, BackupProgress(BackupPhase.PACKING, 0, 100).fraction)
    }

    @Test
    fun `fraction null when total unknown`() {
        assertNull(BackupProgress(BackupPhase.DOWNLOADING, 123, null).fraction)
    }

    @Test
    fun `throttle emits first then drops small steps`() {
        val t = ProgressThrottle(minFractionStep = 0.1f, minIntervalMillis = 1000)
        // 首个必发
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.PACKING, 0, 100), nowMillis = 0))
        // 步进不足且时间不足 -> 丢
        assertFalse(t.shouldEmit(BackupProgress(BackupPhase.PACKING, 1, 100), nowMillis = 10))
        // 步进足够 -> 发
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.PACKING, 20, 100), nowMillis = 20))
    }

    @Test
    fun `throttle always emits completion`() {
        val t = ProgressThrottle(minFractionStep = 0.5f, minIntervalMillis = 10_000)
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.PACKING, 1, 100), nowMillis = 0))
        // 100% 必须发（否则 UI 停在 99%）
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.PACKING, 100, 100), nowMillis = 1))
    }

    @Test
    fun `throttle emits on time even without step`() {
        val t = ProgressThrottle(minFractionStep = 0.9f, minIntervalMillis = 100)
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.UPLOADING, 0, 1000), nowMillis = 0))
        assertTrue(t.shouldEmit(BackupProgress(BackupPhase.UPLOADING, 1, 1000), nowMillis = 150))
    }
}
