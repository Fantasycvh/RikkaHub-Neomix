package app.androidrik.rikkahub.data.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1-7 回归：附件落盘的原子性/失败语义（[writeBytesAtomically] / [writeStreamAtomically]）。
 *
 * 真实 SAF（`content://`）在 JVM 单测里起不来，故把「唯一临时文件 + rename + 失败清理」提纯成
 * 顶层 helper 直接测文件系统行为：覆盖**输入流为 null（SAF 打不开）/ 拷贝中途异常 /
 * temp 唯一性 / 空内容 / 成功**，断言「失败不留半成品、不误报成功」。
 *
 * 失败场景刻意用**与平台无关**的方式构造：
 * - 目标父路径是普通文件（`file/child`）⇒ 创建临时文件必然 IOException；
 * - 目标位置被**非空**目录占住 ⇒ rename 必然失败（空目录在 Windows 上可能被 rename 替换）。
 * 不依赖「无写权限」这类跨平台/跨用户不稳定因素。
 */
class AttachmentAtomicWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `stream write success creates target and leaves no temp residue`() {
        val target = File(tempFolder.root, "upload/photo.png")
        val payload = ByteArray(64) { it.toByte() }

        assertTrue(writeStreamAtomically(target, ByteArrayInputStream(payload)))

        assertArrayEquals(payload, target.readBytes())
        assertTrue("临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `null input stream returns false and leaves neither target nor temp file`() {
        // SAF 打不开流（openInputStream 返回 null）是 P1-7 的根因：旧实现继续登记 0 字节实体
        val target = File(tempFolder.root, "upload/null-stream.png")

        val result = runCatching { writeStreamAtomically(target, null) }

        assertEquals(false, result.getOrNull())
        assertFalse("失败时不能创建目标文件", target.exists())
        assertTrue("失败时临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `stream read failure returns false and leaves neither target nor temp file`() {
        val target = File(tempFolder.root, "upload/broken.png")
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("disk read failed")
        }

        val result = runCatching { writeStreamAtomically(target, failing) }

        // 契约：不抛异常（否则 UI 协程崩溃），如实返回 false
        assertEquals(false, result.getOrNull())
        assertFalse("失败时不能创建目标文件", target.exists())
        assertTrue("失败时临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `partially copied stream is cleaned up without publishing a truncated file`() {
        val target = File(tempFolder.root, "upload/truncated.png")
        val partialThenFail = object : InputStream() {
            private var served = 0
            override fun read(): Int {
                if (served < 3) return 0x41 + served++                  // 先成功写 3 字节
                throw IOException("connection reset mid-copy")          // 再中途失败
            }
        }

        val result = runCatching { writeStreamAtomically(target, partialThenFail) }

        assertEquals(false, result.getOrNull())
        assertFalse("半截内容不能以目标名出现（否则会被当成完整附件）", target.exists())
        assertTrue("临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `rename failure returns false and keeps the previous target intact`() {
        // 目标名被**非空**目录占住 ⇒ rename 必然失败（空目录在 Windows 上可能被替换成功，故不用）
        val target = File(tempFolder.root, "upload/occupied")
        target.mkdirs()
        File(target, "inner.txt").writeText("in the way")

        val result = runCatching {
            writeStreamAtomically(target, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }

        assertEquals(false, result.getOrNull())
        assertTrue("目标位置保持原样（仍是目录）", target.isDirectory)
        assertEquals("in the way", File(target, "inner.txt").readText())
        assertTrue("失败时临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `byte write success is atomic and supports empty content`() {
        // 合法空附件（例如空文本文件）也要能落盘——「0 字节」本身不是失败，
        // 失败只由 null 输入流 / 拷贝异常 / rename 失败界定（P1-7 review 要求）。
        val target = File(tempFolder.root, "upload/empty.txt")

        assertTrue(writeBytesAtomically(target, ByteArray(0)))

        assertTrue(target.isFile)
        assertEquals(0L, target.length())
        assertTrue("临时文件必须清理", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `byte write failure returns false instead of throwing`() {
        // 与平台无关的必失败：目标父路径是**普通文件**，无法在其中创建临时文件
        // （旧测试用空目录占目标名，Windows 上 rename 可能把空目录替换成功 ⇒ 误报 true）
        val notADirectory = File(tempFolder.root, "occupied-parent").apply { writeText("i am a file") }
        val target = File(notADirectory, "child.bin")

        assertEquals(false, writeBytesAtomically(target, byteArrayOf(7)))
        assertFalse("失败时不能创建目标文件", target.exists())
    }

    @Test
    fun `creates missing parent directories`() {
        val target = File(tempFolder.root, "deep/nested/dir/file.bin")
        assertTrue(writeBytesAtomically(target, byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), target.readBytes())
    }

    @Test
    fun `temp file names are unique per write so concurrent writers cannot share one`() {
        val target = File(tempFolder.root, "upload/race.bin")
        target.parentFile.mkdirs()

        val first = createAtomicTempFile(target)
        val second = createAtomicTempFile(target)
        try {
            assertFalse("两次写入的临时文件名必须唯一: $first vs $second", first == second)
            assertTrue(first.isFile)
            assertTrue(second.isFile)
            assertEquals(target.parentFile, first.parentFile)
            assertEquals(target.parentFile, second.parentFile)
            assertTrue(first.name.startsWith("atomic-${target.name}."))
            assertTrue(first.name.endsWith(".tmp"))
            assertFalse("创建临时文件不能提前产出目标文件", target.exists())
        } finally {
            first.delete()
            second.delete()
        }
        assertTrue("测试自身不能留临时文件", leftoverTempFiles(target).isEmpty())
    }

    @Test
    fun `concurrent writes to the same target keep one complete version`() {
        val target = File(tempFolder.root, "upload/concurrent.bin")
        val firstPayload = ByteArray(32 * 1024) { 1 }
        val secondPayload = ByteArray(32 * 1024) { 2 }
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)

        val threads = listOf(firstPayload, secondPayload).map { bytes ->
            Thread {
                start.await()
                if (writeBytesAtomically(target, bytes)) successes.incrementAndGet()
            }
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(10_000) }
        assertFalse("并发写线程未结束", threads.any { it.isAlive })

        // 至少一次成功；无论谁最后 rename，目标都必须是**某一次完整写入**，不能是拼接/截断
        assertTrue("至少应有一次写入成功", successes.get() >= 1)
        val written = target.readBytes()
        assertTrue(
            "目标内容必须是完整的某一次写入",
            written.contentEquals(firstPayload) || written.contentEquals(secondPayload),
        )
        assertTrue("并发写不能留下临时文件", leftoverTempFiles(target).isEmpty())
    }

    /**
     * 固定名（`.name.tmp`）时代的残留检测：现在唯一临时名统一是
     * `atomic-<目标名>.<随机>.tmp`，测试用它判断「失败后有没有清干净」。
     */
    private fun leftoverTempFiles(target: File): List<File> {
        val parent = target.parentFile ?: return emptyList()
        if (!parent.isDirectory) return emptyList()
        val prefix = "atomic-${target.name}."
        return parent.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp") }
            .orEmpty()
    }
}
