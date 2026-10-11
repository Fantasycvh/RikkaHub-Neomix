package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `readRootfsLineWindow` 的 IO 侧单测（真实临时文件，不用 shell）。
 *
 * 为什么这条要单独测：窗口读是**唯一**能读「超过单次读取上限」的文件路径（其余走整文件读），
 * 而它的实现里有三处独立判据——跳过起始行、读满 limit、多探一行判断"还有没有"，任一处差一
 * 都会让模型读到错位的行却看不出问题。
 */
class RootfsLineWindowTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private lateinit var manager: WorkspaceManager

    private fun createManager(): WorkspaceManager =
        WorkspaceManager(baseDir = tempFolder.newFolder("workspaces"))
            .also { it.ensureWorkspace(root) }

    private fun write(relative: String, text: String): String {
        val file = File(manager.filesDir(root), relative)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        return "/workspace/$relative"
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line$it" }

    @Test
    fun `a window starts at the requested 1-based line`() {
        manager = createManager()
        val path = write("log.txt", lines(10))

        val window = manager.readRootfsLineWindow(root, path, startLine = 3, limit = 2)

        assertEquals(listOf("line3", "line4"), window.lines)
        assertTrue("后面还有行时必须报 true（模型据此决定要不要继续翻）", window.moreLines)
    }

    @Test
    fun `the last window reports no more lines`() {
        manager = createManager()
        val path = write("log.txt", lines(10))

        val window = manager.readRootfsLineWindow(root, path, startLine = 9, limit = 5)

        assertEquals(listOf("line9", "line10"), window.lines)
        assertFalse(window.moreLines)
    }

    @Test
    fun `an offset past the end yields an empty window`() {
        manager = createManager()
        val path = write("log.txt", lines(3))

        val window = manager.readRootfsLineWindow(root, path, startLine = 99, limit = 5)

        assertTrue(window.lines.isEmpty())
        assertFalse(window.moreLines)
    }

    @Test
    fun `crlf endings are normalized to plain lines`() {
        manager = createManager()
        val path = write("win.txt", "a\r\nb\r\nc\r\n")

        val window = manager.readRootfsLineWindow(root, path, startLine = 2, limit = 2)

        assertEquals("行尾不该带上 \\r（否则每行末尾多一个不可见字符）", listOf("b", "c"), window.lines)
    }

    @Test
    fun `a missing file fails loudly`() {
        manager = createManager()

        assertThrows(Exception::class.java) {
            manager.readRootfsLineWindow(root, "/workspace/nope.txt", startLine = 1, limit = 10)
        }
    }

    @Test
    fun `a window larger than the whole file returns everything`() {
        manager = createManager()
        val path = write("small.txt", lines(2))

        val window = manager.readRootfsLineWindow(root, path, startLine = 1, limit = 20_000)

        assertEquals(listOf("line1", "line2"), window.lines)
        assertFalse(window.moreLines)
    }
}
