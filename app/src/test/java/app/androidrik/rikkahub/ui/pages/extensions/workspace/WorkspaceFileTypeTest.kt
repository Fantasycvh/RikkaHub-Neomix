package app.androidrik.rikkahub.ui.pages.extensions.workspace

import app.androidrik.document.DocumentFormat
import app.androidrik.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工作区文件分类与"能被 `workspace_read_file` 抽文本的文档格式"必须**互不重叠**。
 *
 * 为什么值得钉：分类决定点击文件时的行为。文档格式一旦被归到 TEXT，点开就会用应用内的文本编辑器
 * 打开一个 zip/二进制（docx/pptx/epub）——看到的是一屏乱码，而不是"交给系统应用打开"。
 * 这条也正是本仓另一类老问题的形状：两处各维护一份扩展名清单，改一处忘一处。
 */
class WorkspaceFileTypeTest {

    private fun entry(name: String) = WorkspaceFileEntry(
        path = "/workspace/$name",
        name = name,
        isDirectory = false,
        sizeBytes = 1,
        updatedAt = 0,
    )

    @Test
    fun `document formats are not opened in the in-app text editor`() {
        DocumentFormat.entries.forEach { format ->
            format.extensions.forEach { extension ->
                val type = entry("report.$extension").detectFileType()
                assertEquals(
                    "$extension 必须交给系统应用打开（应用内文本编辑器会把二进制显示成乱码）",
                    WorkspaceFileType.OTHER,
                    type,
                )
            }
        }
    }

    @Test
    fun `images and text files keep their in-app preview`() {
        assertEquals(WorkspaceFileType.IMAGE, entry("photo.png").detectFileType())
        assertEquals(WorkspaceFileType.TEXT, entry("main.kt").detectFileType())
        assertEquals(WorkspaceFileType.OTHER, entry("archive.bin").detectFileType())
    }
}
