package app.androidrik.rikkahub.data.sync.s3

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * S3 翻页纯函数的单测：用假 fetch 返回多页，不需要 HTTP / Android 环境。
 */
class S3PaginationTest {

    private fun s3Object(key: String) = S3Object(
        key = key,
        size = 1L,
        etag = null,
        lastModified = null,
        storageClass = null,
    )

    private fun page(
        keys: List<String>,
        truncated: Boolean,
        token: String?,
    ) = S3ListResult(
        objects = keys.map { s3Object(it) },
        commonPrefixes = emptyList(),
        isTruncated = truncated,
        nextContinuationToken = token,
        keyCount = keys.size,
    )

    fun keysOf(pages: List<S3ListResult>): List<String> = pages.flatMap { p -> p.objects.map { it.key } }

    @Test
    fun `three pages are collected in server order`() = runBlocking {
        val pages = listOf(
            page(listOf("backup_1.zip", "backup_2.zip"), truncated = true, token = "t1"),
            page(listOf("backup_3.zip"), truncated = true, token = "t2"),
            page(listOf("backup_4.zip"), truncated = false, token = null),
        )
        val requestedTokens = mutableListOf<String?>()

        val collected = collectS3Pages { token ->
            requestedTokens += token
            pages[requestedTokens.size - 1]
        }

        assertEquals(listOf(null, "t1", "t2"), requestedTokens)
        assertEquals(3, collected.size)
        assertEquals(
            listOf("backup_1.zip", "backup_2.zip", "backup_3.zip", "backup_4.zip"),
            keysOf(collected),
        )
        assertFalse(collected.last().isTruncated)
    }

    @Test
    fun `repeated continuation token stops instead of looping forever`() = runBlocking {
        var calls = 0

        val collected = collectS3Pages { _ ->
            calls++
            page(listOf("k$calls"), truncated = true, token = "same-token")
        }

        // 第一页 token=null → next="same-token"；第二页 next 与本次请求 token 相同 ⇒ 停
        assertEquals(2, calls)
        assertEquals(2, collected.size)
        assertEquals(listOf("k1", "k2"), keysOf(collected))
    }

    @Test
    fun `max pages caps an endlessly truncated server`() = runBlocking {
        var calls = 0

        val collected = collectS3Pages(maxPages = 3) { _ ->
            calls++
            page(listOf("k$calls"), truncated = true, token = "t$calls")
        }

        assertEquals(3, calls)
        assertEquals(3, collected.size)
        assertEquals(listOf("k1", "k2", "k3"), keysOf(collected))
    }

    @Test
    fun `single page without truncation is fetched once`() = runBlocking {
        var calls = 0

        val collected = collectS3Pages { _ ->
            calls++
            page(listOf("only.zip"), truncated = false, token = null)
        }

        assertEquals(1, calls)
        assertEquals(1, collected.size)
        assertEquals(listOf("only.zip"), keysOf(collected))
    }

    @Test
    fun `truncated page with blank token stops instead of refetching the first page`() = runBlocking {
        var calls = 0

        val collected = collectS3Pages { _ ->
            calls++
            page(listOf("k"), truncated = true, token = "")
        }

        assertEquals(1, calls)
        assertEquals(1, collected.size)
    }
}
