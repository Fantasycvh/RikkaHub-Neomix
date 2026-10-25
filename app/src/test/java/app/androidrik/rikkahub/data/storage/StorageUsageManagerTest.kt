package app.androidrik.rikkahub.data.storage

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 缓存管理的统计口径与清理边界（`docs/design/design-storage-management.md` §五、§八）。
 *
 * 清理是破坏性动作，故这里钉死三件事：只动白名单路径、只删内容不删目录、失败不掩盖。
 */
class StorageUsageManagerTest {

    private lateinit var root: File
    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private var logsCleared = 0

    @Before
    fun setUp() {
        root = Files.createTempDirectory("storage-usage").toFile()
        cacheDir = File(root, "cache").apply { mkdirs() }
        filesDir = File(root, "files").apply { mkdirs() }
        logsCleared = 0
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manager(): StorageUsageManager =
        StorageUsageManager(cacheDir, filesDir) { logsCleared++ }

    private fun file(relativePath: String, size: Int = 0): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(size))
        return file
    }

    private suspend fun StorageUsageManager.category(id: StorageCategoryId): StorageCategory =
        scan().first { it.id == id }

    @Test
    fun `scan returns every category in enum order`() = runTest {
        assertEquals(StorageCategoryId.entries, manager().scan().map { it.id })
    }

    @Test
    fun `scan sums nested files and treats missing directories as empty`() = runTest {
        file("cache/temp/a.txt", 10)
        file("cache/temp/nested/b.txt", 5)

        val temp = manager().category(StorageCategoryId.TEMP)
        assertEquals(15L, temp.sizeBytes)
        assertEquals(2, temp.itemCount)
        assertTrue(temp.readable)

        val images = manager().category(StorageCategoryId.IMAGE_CACHE)
        assertEquals(0L, images.sizeBytes)
        assertEquals(0, images.itemCount)
        assertTrue(images.readable)
    }

    @Test
    fun `clear removes contents but keeps the directory and other categories`() = runTest {
        val cache = file("cache/image_cache/a.bin", 10)
        val nested = file("cache/image_cache/nested/b.bin", 20)
        val tempFile = file("cache/temp/keep.bin", 7)
        val avatar = file("files/avatars/me.png", 3)
        val imageCacheDir = File(cacheDir, "image_cache")

        val result = manager().clear(StorageCategoryId.IMAGE_CACHE)

        assertEquals(30L, result.freedBytes)
        assertTrue(result.success)
        assertTrue("目录本身必须保留", imageCacheDir.isDirectory)
        assertEquals(0, imageCacheDir.listFiles()?.size)
        assertFalse(cache.exists())
        assertFalse(nested.exists())
        assertTrue("其他缓存分类不受影响", tempFile.exists())
        assertTrue("用户头像不在清理范围", avatar.exists())
    }

    @Test
    fun `clearing empty category is a successful no-op`() = runTest {
        val result = manager().clear(StorageCategoryId.IMAGE_CACHE)
        assertEquals(0L, result.freedBytes)
        assertTrue(result.success)
    }

    @Test
    fun `backup temp category only matches whitelist`() = runTest {
        file("cache/backup_20260101_000000.zip", 10)
        file("cache/restore_backup_db_snapshot.db", 5)
        file("cache/backup_db_snapshot.db", 2)
        file("cache/backup_notes.txt", 100)
        file("cache/other.zip", 100)
        file("cache/chat_image.png", 100)

        val before = manager().category(StorageCategoryId.BACKUP_TEMP)
        assertEquals(3, before.itemCount)
        assertEquals(17L, before.sizeBytes)

        val result = manager().clear(StorageCategoryId.BACKUP_TEMP)

        assertEquals(17L, result.freedBytes)
        assertTrue(result.success)
        assertFalse(File(cacheDir, "backup_20260101_000000.zip").exists())
        assertFalse(File(cacheDir, "restore_backup_db_snapshot.db").exists())
        assertFalse(File(cacheDir, "backup_db_snapshot.db").exists())
        assertTrue("非白名单文件不能被误删", File(cacheDir, "backup_notes.txt").exists())
        assertTrue(File(cacheDir, "other.zip").exists())
        assertTrue(File(cacheDir, "chat_image.png").exists())
    }

    @Test
    fun `logs are measured from the log folder and cleared through the injected hook`() = runTest {
        file("files/logs/rikkahub-logs.txt", 42)
        file("files/logs/rikkahub-logs.1.txt", 8)

        val manager = manager()
        val logs = manager.category(StorageCategoryId.LOGS)
        assertEquals(50L, logs.sizeBytes)
        assertEquals(2, logs.itemCount)

        manager.clear(StorageCategoryId.LOGS)
        assertEquals(1, logsCleared)
    }

    @Test
    fun `symlinked directories are not followed`() = runTest {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "big.bin").writeBytes(ByteArray(100))
        val link = File(root, "cache/temp/linked")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue("当前平台/权限不支持创建符号链接", created)

        val temp = manager().category(StorageCategoryId.TEMP)
        assertEquals("不应统计目录外的文件", 0L, temp.sizeBytes)
        assertEquals(0, temp.itemCount)
    }

    @Test
    fun `only backup temp and logs can expand`() = runTest {
        val expandable = manager().scan().filter { it.canExpand }.map { it.id }
        assertEquals(
            listOf(StorageCategoryId.LOGS, StorageCategoryId.BACKUP_TEMP),
            expandable,
        )
    }

    @Test
    fun `backup temp details list whitelisted files with sizes`() = runTest {
        file("cache/backup_a.zip", 10)
        file("cache/restore_b", 5)
        file("cache/backup_db_snapshot.db", 2)
        file("cache/keep.txt", 100)

        val details = manager().details(StorageCategoryId.BACKUP_TEMP)

        assertEquals(3, details.size)
        assertEquals(
            setOf("backup_a.zip", "restore_b", "backup_db_snapshot.db"),
            details.map { it.name }.toSet(),
        )
        assertEquals(17L, details.sumOf { it.sizeBytes })
    }

    @Test
    fun `log details list files only`() = runTest {
        file("files/logs/rikkahub-logs.txt", 42)
        file("files/logs/rikkahub-logs.1.txt", 8)
        File(filesDir, "logs/nested").mkdirs()

        val details = manager().details(StorageCategoryId.LOGS)

        assertEquals(2, details.size)
        assertEquals(50L, details.sumOf { it.sizeBytes })
    }

    @Test
    fun `categories without details return empty list`() = runTest {
        file("cache/temp/a.bin", 1)
        assertTrue(manager().details(StorageCategoryId.TEMP).isEmpty())
        assertTrue(manager().details(StorageCategoryId.IMAGE_CACHE).isEmpty())
    }
}
