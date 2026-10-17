package app.androidrik.workspace

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [prepareProotLibDir] 的行为约定：可写库目录的创建、幂等与缺源处理。
 *
 * 这些用例不依赖 Android：只操作临时目录里的普通文件（与 `ProotShellRunner` 运行时做的事一致）。
 */
class ProotLibDirTest {
    private fun nativeDir(vararg libs: Pair<String, String>): File {
        val dir = Files.createTempDirectory("proot-native").toFile()
        libs.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    private fun fullNativeDir(): File = nativeDir(
        "libproot_exec.so" to "proot",
        "libproot_loader.so" to "loader",
        "libtalloc2.so" to "talloc-bytes",
        "libandroid-shmem.so" to "shmem-bytes",
    )

    private fun libDir(): File = File(Files.createTempDirectory("proot-libs-root").toFile(), "proot-libs")

    @Test
    fun firstCallCreatesWritableDirWithRealLibraryNames() {
        val libDir = libDir()
        assertFalse("前置条件：目录尚不存在", libDir.exists())

        val prepared = prepareProotLibDir(fullNativeDir(), libDir)

        assertEquals(libDir, prepared)
        assertTrue(libDir.isDirectory)
        assertEquals("talloc-bytes", File(libDir, "libtalloc.so.2").readText())
        assertEquals("shmem-bytes", File(libDir, "libandroid-shmem.so").readText())
        // 打包名不能出现在运行目录：链接器只认 DT_NEEDED 里的 libtalloc.so.2
        assertFalse(File(libDir, "libtalloc2.so").exists())
    }

    @Test
    fun repeatedCallIsIdempotentAndRepairsTruncatedCopy() {
        val native = fullNativeDir()
        val libDir = libDir()
        prepareProotLibDir(native, libDir)
        val talloc = File(libDir, "libtalloc.so.2")
        val lastModified = talloc.lastModified()

        // 第二次调用：目录已存在且内容完整 → 返回同一目录，内容不变
        assertEquals(libDir, prepareProotLibDir(native, libDir))
        assertEquals("talloc-bytes", talloc.readText())
        assertEquals("shmem-bytes", File(libDir, "libandroid-shmem.so").readText())
        assertEquals("完整的目标文件不应被重复复制", lastModified, talloc.lastModified())

        // 上次复制被中断（大小不符）→ 本次调用修复它
        talloc.writeText("bad")
        assertEquals(libDir, prepareProotLibDir(native, libDir))
        assertEquals("talloc-bytes", talloc.readText())
        // 临时文件不应残留
        assertFalse(File(libDir, "libtalloc.so.2.tmp").exists())
    }

    @Test
    fun missingAnySourceReturnsNullAndCreatesNothing() {
        listOf("libtalloc2.so", "libandroid-shmem.so").forEach { missing ->
            val native = fullNativeDir().apply { File(this, missing).delete() }
            val libDir = libDir()

            assertNull("缺 $missing 时应返回 null", prepareProotLibDir(native, libDir))
            // 不做半套：目录都不应被创建
            assertFalse("缺 $missing 时不应创建库目录", libDir.exists())
        }
    }
}
