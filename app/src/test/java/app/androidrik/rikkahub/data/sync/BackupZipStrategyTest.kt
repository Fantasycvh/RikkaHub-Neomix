package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 大文件加固 P0 的压缩策略（设计稿 §九）。
 *
 * 背景：对不可压缩数据（git pack/图片/视频）跑 DEFLATE 纯属浪费——实测比率 1.000x
 * 仍以 24 MB/s 烧单核，2.8GB ≈ 数分钟。判定为不可压缩的条目用 `ZipEntry.STORED`。
 */
class BackupZipStrategyTest {

    @Test
    fun `random bytes are detected as incompressible`() {
        // 伪随机（确定性）：无法压缩的典型形态
        val sample = ByteArray(BackupZipStrategy.SAMPLE_SIZE)
        var seed = 42L
        for (i in sample.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            sample[i] = (seed ushr 33).toByte()
        }
        assertTrue(BackupZipStrategy.shouldStoreUncompressed(sample))
    }

    @Test
    fun `repetitive text is detected as compressible`() {
        val sample = ("The quick brown fox jumps over the lazy dog. ").toByteArray()
            .let { line -> ByteArray(BackupZipStrategy.SAMPLE_SIZE) { line[it % line.size] } }
        assertFalse(BackupZipStrategy.shouldStoreUncompressed(sample))
    }

    @Test
    fun `tiny samples are never stored`() {
        // 小文件 STORED 预读不划算：一律走 DEFLATE（快、且省一次读）
        assertFalse(BackupZipStrategy.shouldStoreUncompressed(ByteArray(100)))
        assertFalse(BackupZipStrategy.shouldStoreUncompressed(ByteArray(0)))
    }

    @Test
    fun `stored entry round-trips through zip with correct content and method`() {
        // 用与 Engine 相同的方式构建 STORED 条目（Java API 要求预置 size/crc），
        // 验证恢复侧 ZipInputStream 能正常读回（备份格式向后兼容的契约）
        val content = ByteArray(BackupZipStrategy.SAMPLE_SIZE) { ((it * 31 + 7) % 251).toByte() }
        val crc = java.util.zip.CRC32().apply { update(content) }

        val bytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                val entry = ZipEntry("workspaces/clone/files/pack.bin").apply {
                    method = ZipEntry.STORED
                    size = content.size.toLong()
                    compressedSize = content.size.toLong()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
            out.toByteArray()
        }

        ByteArrayInputStream(bytes).use { input ->
            ZipInputStream(input).use { zip ->
                val entry = zip.nextEntry
                assertTrue(entry != null)
                assertEquals(ZipEntry.STORED.toLong(), entry!!.method.toLong())
                val restored = zip.readBytes()
                assertTrue(content.contentEquals(restored))
            }
        }
    }
}
