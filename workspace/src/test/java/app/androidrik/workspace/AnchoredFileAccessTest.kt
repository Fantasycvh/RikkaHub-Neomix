package app.androidrik.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 审计 #4（resolve-then-open TOCTOU）的**接线验证**：
 *
 * 生产实现的 fd 复核依赖 `android.system.Os`（Android 才有），单测 JVM 走 `Os` 的
 * "not mocked" 退化路径——所以这里不测「真 symlink 逃逸能被 Os 拦住」（那需要真机/instrumented
 * 测试），而是测：
 *
 * 1. 复核**返回拒绝**时每条文件路径都 fail-closed（不写、不读、不截断、不留半成品）；
 * 2. 复核默认放行时普通读写行为不变；
 * 3. 无 fd 的路径操作（delete/rename/move）在祖先链含链接时拒绝。
 *
 * 通过替换 [AnchoredFileAccess.verifier] 注入拒绝，覆盖真实实现里 `checkVerified` 之后的所有分支。
 */
class AnchoredFileAccessTest {

    private val denyAll = AnchoredFileAccess.Verifier { _, _, _ -> false }

    @After
    fun restoreVerifier() {
        AnchoredFileAccess.resetVerifier()
    }

    private fun tempRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    @Test
    fun readTextFailsClosedWithoutLeakingContent() {
        val root = tempRoot("anchor-read")
        try {
            root.resolve("secret.txt").writeText("workspace content")
            AnchoredFileAccess.verifier = denyAll
            val error = assertThrows(SecurityException::class.java) {
                WorkspaceFileSystem().readText(root, "secret.txt")
            }
            assertTrue(error.message!!.contains("symlink"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTextFailsClosedWithoutTruncatingTheExistingFile() {
        val root = tempRoot("anchor-write")
        try {
            val target = root.resolve("keep.txt").apply { writeText("must survive") }
            AnchoredFileAccess.verifier = denyAll
            assertThrows(SecurityException::class.java) {
                WorkspaceFileSystem().writeText(root, "keep.txt", "overwritten")
            }
            // 关键：rw 打开不截断 ⇒ 复核失败时原内容完好
            assertEquals("must survive", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importBytesFailsClosedAndRemovesTheIncompleteFile() {
        val root = tempRoot("anchor-import")
        try {
            AnchoredFileAccess.verifier = denyAll
            assertThrows(SecurityException::class.java) {
                WorkspaceFileSystem().importBytes(
                    root,
                    "payload.bin",
                    ByteArrayInputStream("payload".toByteArray()),
                )
            }
            assertFalse(root.resolve("payload.bin").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exportFailsClosedWithoutEmittingBytes() {
        val root = tempRoot("anchor-export")
        try {
            root.resolve("doc.txt").writeText("exported")
            AnchoredFileAccess.verifier = denyAll
            val sink = ByteArrayOutputStream()
            assertThrows(SecurityException::class.java) {
                WorkspaceFileSystem().exportTo(root, "doc.txt", sink)
            }
            assertEquals(0, sink.size())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun defaultVerifierKeepsOrdinaryReadWriteExportWorking() {
        val root = tempRoot("anchor-normal")
        try {
            val fs = WorkspaceFileSystem()
            fs.writeText(root, "notes/a.txt", "hello")
            assertEquals("hello", fs.readText(root, "notes/a.txt"))
            fs.writeText(root, "notes/a.txt", "hello again")
            val sink = ByteArrayOutputStream()
            fs.exportTo(root, "notes/a.txt", sink)
            assertEquals("hello again", sink.toString(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteRefusesPathsWhoseAncestorIsASymlink() {
        val root = tempRoot("anchor-delete")
        val outside = tempRoot("anchor-delete-outside")
        try {
            outside.resolve("keep.txt").writeText("outside")
            val link = root.resolve("alias")
            if (!createSymlink(link, outside)) {
                Assume.assumeTrue("需要 symlink 权限（Windows 无开发者模式时跳过）", false)
                return
            }
            // mutationPath 已经在解析阶段拒绝别名，这里验证「复核」这一层同样拒绝
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceFileSystem().delete(root, "alias/keep.txt", recursive = false)
            }
            assertTrue(outside.resolve("keep.txt").exists())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun verifyAncestorsRejectsSymlinkedComponentsAndAcceptsRealDirectories() {
        val root = tempRoot("anchor-ancestors")
        val outside = tempRoot("anchor-ancestors-outside")
        try {
            root.resolve("real/nested").mkdirs()
            val realTarget = root.resolve("real/nested/file.txt")
            assertTrue(AnchoredFileAccess.verifyAncestors(root, realTarget))

            val link = root.resolve("link")
            if (!createSymlink(link, outside)) {
                Assume.assumeTrue("需要 symlink 权限（Windows 无开发者模式时跳过）", false)
                return
            }
            assertFalse(AnchoredFileAccess.verifyAncestors(root, File(link, "file.txt")))
            // 含 ".." 的路径同样不允许（防止 canonical 折叠后绕过前缀判断）
            assertTrue(AnchoredFileAccess.verifyAncestors(root, root.resolve("real/nested/file.txt")))
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun createSymlink(link: File, target: File): Boolean = try {
        Files.createSymbolicLink(link.toPath(), target.toPath())
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: java.io.IOException) {
        false
    }
}
