package app.androidrik.workspace

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFileMutationTest {
    @Test fun `rename file and directory without replacing occupied target`() {
        val root = Files.createTempDirectory("rename-files").toFile()
        try {
            val fs = WorkspaceFileSystem()
            root.resolve("old.txt").writeText("original")
            fs.rename(root, "old.txt", "new.txt")
            assertEquals("original", root.resolve("new.txt").readText())
            root.resolve("occupied.txt").writeText("occupied")
            assertThrows(Exception::class.java) { fs.rename(root, "new.txt", "occupied.txt") }
            assertEquals("original", root.resolve("new.txt").readText())
            assertEquals("occupied", root.resolve("occupied.txt").readText())
            root.resolve("folder").mkdir()
            root.resolve("folder/data").writeText("data")
            fs.rename(root, "folder", "renamed")
            assertEquals("data", root.resolve("renamed/data").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun `unsafe paths root mutations and invalid names are rejected`() {
        val root = Files.createTempDirectory("rename-invalid").toFile()
        try {
            val fs = WorkspaceFileSystem()
            root.resolve("file").writeText("safe")
            for (name in listOf("", ".", "..", "../outside", "a/b", "a\\b", "a\u0000b")) {
                assertThrows(IllegalArgumentException::class.java) { fs.rename(root, "file", name) }
            }
            for (path in listOf("", ".", "./", "../file", "/file")) {
                assertThrows(IllegalArgumentException::class.java) { fs.rename(root, path, "new") }
            }
            assertEquals("safe", root.resolve("file").readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun `imports retain existing file and choose a distinct name`() {
        val root = Files.createTempDirectory("batch-import").toFile()
        try {
            val fs = WorkspaceFileSystem()
            root.resolve("same.txt").writeText("old")
            val entry = fs.importBytes(root, "same.txt", ByteArrayInputStream("new".toByteArray()))
            assertEquals("old", root.resolve("same.txt").readText())
            assertEquals("new", root.resolve(entry.path).readText())
            assertNotEquals("same.txt", entry.path)
        } finally { root.deleteRecursively() }
    }

    @Test fun `failed import removes incomplete destination`() {
        val root = Files.createTempDirectory("batch-import-failure").toFile()
        try {
            val input = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("broken") }
            assertThrows(java.io.IOException::class.java) { WorkspaceFileSystem().importBytes(root, "bad", input) }
            assertFalse(root.resolve("bad").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun `imports may land in an in-root directory alias but never escape`() {
        val root = Files.createTempDirectory("import-alias").toFile()
        try {
            val fs = WorkspaceFileSystem()
            root.resolve("usr/bin").mkdirs()
            Files.createSymbolicLink(root.resolve("bin").toPath(), java.nio.file.Path.of("usr/bin"))
            val entry = fs.importBytes(root, "bin/tool.txt", ByteArrayInputStream("payload".toByteArray()))
            assertEquals("payload", root.resolve("usr/bin/tool.txt").readText())
            // 入口路径是用户给的别名路径（列表/分享也按它展示）；真实落盘在 usr/bin 下
            assertEquals("usr/bin/tool.txt", entry.path)
            // 指向 root 外的别名必须在任何一段被拒绝
            val outside = Files.createTempDirectory("outside")
            Files.createSymbolicLink(root.resolve("evil").toPath(), outside)
            assertThrows(IllegalArgumentException::class.java) {
                fs.importBytes(root, "evil/x.txt", ByteArrayInputStream("x".toByteArray()))
            }
            assertEquals(0, outside.toFile().list()!!.size)
        } finally { root.deleteRecursively() }
    }

    @Test fun `recursive delete never follows descendant symlinks to outside content`() {
        val root = Files.createTempDirectory("delete-symlink").toFile()
        val outside = Files.createTempDirectory("delete-victim").toFile()
        try {
            outside.resolve("keep.txt").writeText("must survive")
            val dir = root.resolve("dir")
            dir.mkdir()
            Files.createSymbolicLink(dir.resolve("link").toPath(), outside.toPath())
            assertTrue(WorkspaceFileSystem().delete(root, "dir", recursive = true))
            assertFalse(dir.exists())
            // 链接本身已断开，但它指向的外部目录必须原样保留
            assertTrue(outside.resolve("keep.txt").exists())
            assertEquals("must survive", outside.resolve("keep.txt").readText())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test
    fun `recursive delete refuses alias paths instead of touching the real target`() {
        val root = Files.createTempDirectory("delete-alias").toFile()
        try {
            val real = root.resolve("usr/data").apply { mkdirs() }
            real.resolve("keep.txt").writeText("kept")
            root.resolve("alias").let { Files.createSymbolicLink(it.toPath(), real.toPath()) }
            // delete 走逐段拒绝链接的 mutationPath：别名路径直接拒绝（用户应先在终端里处理
            // 或删除真实路径），绝不会踩着别名把真实目录删掉
            assertThrows(IllegalArgumentException::class.java) {
                WorkspaceFileSystem().delete(root, "alias", recursive = true)
            }
            assertTrue(real.resolve("keep.txt").exists())
        } finally { root.deleteRecursively() }
    }
}
