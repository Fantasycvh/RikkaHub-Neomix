package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

class RootfsInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `extract skips OTHER entry data exactly once`() {
        // OTHER 条目 (如 GNU sparse) 带 size>0 数据区, 双重 skip 会让后续 header 错位
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("a.txt", '0', "hello".toByteArray())
            out.writeTarEntry("sparse.bin", 'S', ByteArray(700) { 1 })
            out.writeTarEntry("b.txt", '0', "world".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
        assertFalse(File(target, "sparse.bin").exists())
    }

    @Test
    fun `extract handles directories and zero size entries`() {
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("dir/", '5', ByteArray(0))
            out.writeTarEntry("dir/file.txt", '0', "content".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals(true, File(target, "dir").isDirectory)
        assertEquals("content", File(target, "dir/file.txt").readText())
    }

    @Test
    fun `extract tolerates root directory entry instead of failing the whole install`() {
        // GNU tar 的标准输出（tar -C dir -czf x.tar.gz .）开头就是 "./" 根条目。
        // 旧实现在 readTarHeader 内对「规范化后为空」require(isNotBlank) 抛异常 ⇒ 自制 rootfs
        // 全部安装失败；正确行为是跳过该条目、继续解压（对齐上游 298e2771）。
        val archive = tmp.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { out ->
            out.writeTarEntry("./", '5', ByteArray(0))
            out.writeTarEntry("./etc/", '5', ByteArray(0))
            out.writeTarEntry("./etc/os-release", '0', "PRETTY_NAME=Alpine Linux\n".toByteArray())
            out.write(ByteArray(TAR_BLOCK * 2))
        }

        val target = tmp.newFolder("out")
        createInstaller().extractTar(archive, target) {}

        assertEquals("PRETTY_NAME=Alpine Linux\n", File(target, "etc/os-release").readText())
    }

    private fun createInstaller() = RootfsInstaller(WorkspaceManager(tmp.newFolder()))

    private fun OutputStream.writeTarEntry(name: String, type: Char, data: ByteArray) {
        val header = ByteArray(TAR_BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(header, 0)
        "0000755".toByteArray().copyInto(header, 100)
        data.size.toLong().toOctalField().copyInto(header, 124)
        header[156] = type.code.toByte()
        write(header)
        write(data)
        val padding = (TAR_BLOCK - data.size % TAR_BLOCK) % TAR_BLOCK
        write(ByteArray(padding))
    }

    private fun Long.toOctalField(): ByteArray =
        toString(8).padStart(11, '0').toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAR_BLOCK = 512
    }

    // ---------- F7: parsePax 健壮性 ----------

    @Test
    fun `parsePax parses normal records`() {
        val installer = createInstaller()
        val pax = installer.parsePax("20 path=usr/bin/env\n20 mtime=1700000000\n")
        // pax 记录的 length 含数字+空格+内容+换行：path=usr/bin/env\n 与 mtime=1700000000\n 均为 20
        assertEquals("usr/bin/env", pax["path"])
        assertEquals("1700000000", pax["mtime"])
    }

    @Test
    fun `parsePax breaks on zero length record without infinite loop`() {
        val installer = createInstaller()
        // length=0 的损坏记录：旧实现 index 不前进会死循环
        val pax = installer.parsePax("0 path=x\n")
        assertTrue(pax.isEmpty())
    }

    @Test
    fun `parsePax breaks on negative length record without crash`() {
        val installer = createInstaller()
        val pax = installer.parsePax("-5 path=x\n")
        assertTrue(pax.isEmpty())
    }

    @Test
    fun `parsePax breaks on garbage length without substring crash`() {
        val installer = createInstaller()
        // 非数字前缀：旧实现 substring(space+1, end) 可能越界
        val pax = installer.parsePax("bad path=x\n")
        assertTrue(pax.isEmpty())
    }

    @Test
    fun `parsePax tolerates oversized length by clamping to text end`() {
        val installer = createInstaller()
        val text = "999 path=usr/bin\n"
        val pax = installer.parsePax(text)
        // length 超出剩余文本：coerceAtMost 到末尾，不得越界崩溃
        assertTrue(pax.containsKey("path"))
    }
}
