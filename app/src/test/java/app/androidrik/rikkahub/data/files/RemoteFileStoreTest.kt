package app.androidrik.rikkahub.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.data.sync.s3.S3ListResult
import app.androidrik.rikkahub.data.sync.s3.S3Object
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class RemoteFileStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-01T00:00:00Z")
    private var config = S3Config(
        endpoint = "https://s3.example.com",
        accessKeyId = "id",
        secretAccessKey = "secret",
        bucket = "bucket",
    )
    private val storage = FakeStorage()
    private val store = RemoteFileStore(
        scope = CoroutineScope(Dispatchers.Unconfined),
        config = { config },
        storage = { storage },
        now = { now },
    )

    // "hello" 的 SHA-256
    private val helloKey = "rikkahub_uploads/2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824.png"

    private fun file(name: String, content: String = "hello"): File =
        folder.newFile(name).apply { writeText(content) }

    private fun advance(duration: Duration) {
        now += duration.toJavaDuration()
    }

    @Test
    fun `upload stores the file under its content hash and returns a presigned url`() = runBlocking {
        val remote = store.upload(file("Photo.PNG"), expires = 2.hours).getOrThrow()

        assertEquals(helloKey, remote.key)
        assertEquals("signed:$helloKey:2h", remote.url)
        assertEquals(now + 2.hours.toJavaDuration(), remote.expiresAt)
        assertEquals(listOf(helloKey to "image/png"), storage.puts)
    }

    @Test
    fun `same content is uploaded once and reused without asking the bucket again`() = runBlocking {
        store.upload(file("a.png")).getOrThrow()
        val remote = store.upload(file("b.png")).getOrThrow()

        assertEquals(helloKey, remote.key)
        assertEquals(1, storage.puts.size)
        assertEquals(1, storage.heads)
    }

    @Test
    fun `object already in the bucket is not uploaded again`() = runBlocking {
        storage.objects[helloKey] = now - 1.days.toJavaDuration()

        store.upload(file("a.png")).getOrThrow()

        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `object that would be swept before the url expires is uploaded again`() = runBlocking {
        storage.objects[helloKey] = now - 6.days.toJavaDuration() - 1.minutes.toJavaDuration()

        store.upload(file("a.png"), expires = 24.hours).getOrThrow()

        assertEquals(1, storage.puts.size)
        assertEquals(now, storage.objects[helloKey])
    }

    @Test
    fun `object deleted from the bucket is uploaded again after the recheck interval`() = runBlocking {
        val file = file("a.png")
        store.upload(file).getOrThrow()
        storage.objects.clear()

        advance(30.minutes)
        store.upload(file).getOrThrow()
        assertEquals(1, storage.puts.size)

        advance(31.minutes)
        store.upload(file).getOrThrow()
        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `changed file content gets a new key`() = runBlocking {
        val file = file("a.png")
        val first = store.upload(file).getOrThrow()
        file.writeText("hello world")
        val second = store.upload(file).getOrThrow()

        assertNotEquals(first.key, second.key)
        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `switching the s3 config forgets what was uploaded`() = runBlocking {
        val file = file("a.png")
        store.upload(file).getOrThrow()
        storage.objects.clear()
        config = config.copy(bucket = "other")

        store.upload(file).getOrThrow()

        assertEquals(2, storage.puts.size)
    }

    @Test
    fun `upload fails without touching the bucket when s3 is not configured`() = runBlocking {
        config = S3Config()

        assertFalse(store.isConfigured)
        assertTrue(store.upload(file("a.png")).exceptionOrNull() is IllegalStateException)
        assertTrue(store.upload(File(folder.root, "missing.png")).isFailure)
        assertEquals(0, storage.heads)
        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `upload rejects an expiry longer than the retention`() = runBlocking {
        assertTrue(store.upload(file("a.png"), expires = 8.days).exceptionOrNull() is IllegalArgumentException)
        assertTrue(storage.puts.isEmpty())
    }

    @Test
    fun `testConnection reports missing config and bucket errors`() = runBlocking {
        assertTrue(store.testConnection().isSuccess)

        storage.listError = IllegalStateException("403")
        assertEquals("403", store.testConnection().exceptionOrNull()?.message)

        config = S3Config()
        assertTrue(store.testConnection().exceptionOrNull()?.message.orEmpty().contains("not configured"))
    }

    @Test
    fun `sweep deletes only objects past the retention across all pages`() = runBlocking {
        storage.pageSize = 2
        val expired = (1..3).map { "rikkahub_uploads/old$it.png" }
        val fresh = (1..2).map { "rikkahub_uploads/new$it.png" }
        expired.forEach { storage.objects[it] = now - 8.days.toJavaDuration() }
        fresh.forEach { storage.objects[it] = now - 6.days.toJavaDuration() }
        storage.objects["rikkahub_plus_backups/backup_1.zip"] = now - 30.days.toJavaDuration()

        store.sweep()

        assertEquals(expired.toSet(), storage.deleted.toSet())
        assertEquals((fresh + "rikkahub_plus_backups/backup_1.zip").toSet(), storage.objects.keys)
    }

    @Test
    fun `upload sweeps at most once a day`() = runBlocking {
        val file = file("a.png")
        storage.objects["rikkahub_uploads/old.png"] = now - 8.days.toJavaDuration()

        store.upload(file).getOrThrow()
        assertEquals(listOf("rikkahub_uploads/old.png"), storage.deleted)
        assertEquals(1, storage.lists)

        advance(23.hours)
        store.upload(file).getOrThrow()
        assertEquals(1, storage.lists)

        advance(2.hours)
        store.upload(file).getOrThrow()
        assertEquals(2, storage.lists)
    }

    @Test
    fun `sweep never deletes objects outside the upload prefix`() = runBlocking {
        storage.objects["rikkahub_plus_backups/old.zip"] = now - 30.days.toJavaDuration()
        storage.objects["other/old.png"] = now - 30.days.toJavaDuration()
        storage.objects[helloKey] = now - 8.days.toJavaDuration()
        // 模拟不守 prefix 约定、把整桶对象都返回回来的 list 实现（恶意/兼容实现）
        storage.ignorePrefix = true

        store.sweep()

        // 只删上传前缀里过期的对象，备份等其它前缀就算列表返回了也不能动
        assertEquals(listOf(helloKey), storage.deleted)
        assertTrue(storage.objects.containsKey("rikkahub_plus_backups/old.zip"))
        assertTrue(storage.objects.containsKey("other/old.png"))
    }

    @Test
    fun `sweep stops when a truncated page has no continuation token`() = runBlocking {
        storage.pageSize = 1
        storage.omitContinuationToken = true
        storage.objects["rikkahub_uploads/a.png"] = now - 8.days.toJavaDuration()
        storage.objects["rikkahub_uploads/b.png"] = now - 8.days.toJavaDuration()

        store.sweep()

        // 没有 token 就无法翻页：处理完第一页就停，不能死循环
        assertEquals(1, storage.deleted.size)
    }

    @Test(timeout = 5_000L)
    fun `sweep stops when the bucket repeats the same continuation token`() = runBlocking {
        storage.pageSize = 1
        storage.stuckContinuationToken = true
        storage.objects["rikkahub_uploads/old.png"] = now - 8.days.toJavaDuration()

        store.sweep()

        assertEquals(listOf("rikkahub_uploads/old.png"), storage.deleted)
    }

    @Test
    fun `switching config sweeps the new bucket immediately`() = runBlocking {
        // 同一个文件复用两次：TemporaryFolder.newFile 同名会抛 IOException
        val file = file("a.png")
        storage.objects["rikkahub_uploads/old.png"] = now - 8.days.toJavaDuration()
        store.upload(file).getOrThrow()
        assertEquals(1, storage.lists)

        // 换桶之后即使还没到 24h 也应该重新清扫新桶
        config = config.copy(bucket = "other")
        advance(1.hours)
        storage.objects["rikkahub_uploads/old2.png"] = now - 8.days.toJavaDuration()
        store.upload(file).getOrThrow()

        assertEquals(2, storage.lists)
        assertTrue(storage.deleted.contains("rikkahub_uploads/old2.png"))
    }

    @Test
    fun `upload refuses an explicit http endpoint`() = runBlocking {
        config = config.copy(endpoint = "http://s3.example.com")

        assertFalse(store.isConfigured)
        assertTrue(store.upload(file("a.png")).exceptionOrNull()?.message.orEmpty().contains("HTTPS"))
        assertTrue(storage.puts.isEmpty())
        assertEquals(0, storage.heads)
    }

    @Test
    fun `testConnection refuses an explicit http endpoint`() = runBlocking {
        config = config.copy(endpoint = "http://s3.example.com")

        assertTrue(store.testConnection().exceptionOrNull()?.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun `scheme-less endpoint is completed to https`() = runBlocking {
        // public 构造器读配置时会 withHttpsByDefault()，这里用同样的 config lambda 模拟
        val httpsStore = RemoteFileStore(
            scope = CoroutineScope(Dispatchers.Unconfined),
            config = { config.copy(endpoint = "s3.example.com").withHttpsByDefault() },
            storage = { storage },
            now = { now },
        )

        assertTrue(httpsStore.isConfigured)
        httpsStore.upload(file("a.png")).getOrThrow()
        assertEquals(1, storage.puts.size)
    }

    @Test
    fun `upload propagates cancellation instead of wrapping it in a Result`() {
        storage.lastModifiedError = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { store.upload(file("a.png")) }
        }

        assertEquals("cancelled", thrown.message)
    }

    @Test
    fun `testConnection propagates cancellation instead of wrapping it in a Result`() {
        storage.listError = CancellationException("cancelled")

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { store.testConnection() }
        }

        assertEquals("cancelled", thrown.message)
    }

    private inner class FakeStorage : RemoteObjectStorage {
        val objects = sortedMapOf<String, Instant>()
        val puts = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        var heads = 0
        var lists = 0
        var pageSize = 1000
        var listError: Exception? = null
        var lastModifiedError: Exception? = null
        var ignorePrefix = false
        var omitContinuationToken = false
        var stuckContinuationToken = false

        override suspend fun lastModified(key: String): Instant? {
            lastModifiedError?.let { throw it }
            heads++
            return objects[key]
        }

        override suspend fun put(key: String, file: File, contentType: String) {
            puts += key to contentType
            objects[key] = now
        }

        override suspend fun list(prefix: String, continuationToken: String?): S3ListResult {
            listError?.let { throw it }
            if (continuationToken == null) lists++
            val candidates = if (ignorePrefix) {
                objects.entries
            } else {
                objects.entries.filter { it.key.startsWith(prefix) }
            }
            val remaining = candidates
                .filter { continuationToken == null || it.key > continuationToken }
            val page = remaining.take(pageSize)
            if (stuckContinuationToken) {
                // 坏实现：永远说还有下一页，并且反复返回同一个 token
                return S3ListResult(
                    objects = page.map {
                        S3Object(it.key, size = 0, etag = null, lastModified = it.value, storageClass = null)
                    },
                    commonPrefixes = emptyList(),
                    isTruncated = true,
                    nextContinuationToken = "stuck",
                    keyCount = page.size,
                )
            }
            return S3ListResult(
                objects = page.map {
                    S3Object(it.key, size = 0, etag = null, lastModified = it.value, storageClass = null)
                },
                commonPrefixes = emptyList(),
                isTruncated = remaining.size > page.size,
                nextContinuationToken = if (omitContinuationToken) null else page.lastOrNull()?.key,
                keyCount = page.size,
            )
        }

        override suspend fun delete(key: String) {
            deleted += key
            objects.remove(key)
        }

        override fun presign(key: String, expires: Duration): String = "signed:$key:$expires"
    }
}
