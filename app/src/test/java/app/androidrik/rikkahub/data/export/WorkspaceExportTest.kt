package app.androidrik.rikkahub.data.export

import app.androidrik.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 工作区批量导出（设计见 `docs/design-workspace-multiselect-export.md`）。
 *
 * 重点钉住四组契约（错了都会静默出错）：
 * ① **条目名拒绝逃逸**（`..` / 绝对路径）而不是"尽力修正"——写方向的 zip-slip；
 * ② **空目录要保留**（用户决议），且目录条目必须以 `/` 结尾（否则某些解压器建成普通文件）；
 * ③ 选中项里混着非法/已消失的条目时**只跳过它**，不让整批判失败；
 * ④ zip 是**流式**写出来的，且内容与磁盘一致（用临时目录真压一遍再解开核对）。
 */
class WorkspaceExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 条目名 ----

    @Test
    fun `entry names reject escapes and absolute paths`() {
        assertNull(WorkspaceExportNames.zipEntryName("../secret.txt"))
        assertNull(WorkspaceExportNames.zipEntryName("a/../../b.txt"))
        assertNull(WorkspaceExportNames.zipEntryName("/etc/passwd"))
        assertNull(WorkspaceExportNames.zipEntryName("   "))
    }

    @Test
    fun `entry names normalize separators and redundant segments`() {
        assertEquals("a/b.txt", WorkspaceExportNames.zipEntryName("a\\b.txt"))
        assertEquals("a/b.txt", WorkspaceExportNames.zipEntryName("./a//b.txt"))
        assertEquals("b.txt", WorkspaceExportNames.zipEntryName("b.txt/"))
    }

    @Test
    fun `directory entries end with a slash`() {
        assertEquals("empty/", WorkspaceExportNames.zipDirectoryName("empty"))
        assertNull(WorkspaceExportNames.zipDirectoryName("../escape"))
    }

    @Test
    fun `relativeTo refuses paths outside the base directory`() {
        assertEquals("a.txt", WorkspaceExportNames.relativeTo("sub", "sub/a.txt"))
        assertEquals("a.txt", WorkspaceExportNames.relativeTo("", "a.txt"))
        assertNull("same path is not a child", WorkspaceExportNames.relativeTo("sub", "sub"))
        assertNull(WorkspaceExportNames.relativeTo("sub", "other/a.txt"))
        assertNull("sibling with shared prefix must not match", WorkspaceExportNames.relativeTo("sub", "subx/a.txt"))
    }

    @Test
    fun `default archive name follows the current directory`() {
        assertEquals("proj-123.zip", WorkspaceExportNames.defaultArchiveName("docs/proj", 123))
        assertEquals("workspace-123.zip", WorkspaceExportNames.defaultArchiveName("", 123))
    }

    // ---- 计划 ----

    @Test
    fun `plan keeps empty directories and recurses into folders`() {
        val root = temp.newFolder("files")
        java.io.File(root, "proj").apply { mkdirs() }
        java.io.File(root, "proj/empty").apply { mkdirs() }
        java.io.File(root, "proj/a.txt").writeText("A")
        java.io.File(root, "proj/sub").apply { mkdirs() }
        java.io.File(root, "proj/sub/b.txt").writeText("BB")

        val (plan, skipped) = collectWorkspaceExportPlan(
            root = root,
            selection = listOf(entry("proj", isDirectory = true)),
            basePath = "",
        )

        assertTrue(skipped.isEmpty())
        assertEquals(
            listOf("proj/", "proj/a.txt", "proj/empty/", "proj/sub/", "proj/sub/b.txt"),
            plan.entries.map { it.entryName },
        )
        assertEquals(2, plan.fileCount)
        assertEquals(3, plan.directoryCount)
        assertEquals(3L, plan.totalBytes)
    }

    @Test
    fun `selection from a subdirectory is named relative to it`() {
        val root = temp.newFolder("files2")
        java.io.File(root, "docs").apply { mkdirs() }
        java.io.File(root, "docs/report.txt").writeText("R")

        val (plan, _) = collectWorkspaceExportPlan(
            root = root,
            selection = listOf(entry("docs/report.txt")),
            basePath = "docs",
        )

        assertEquals(listOf("report.txt"), plan.entries.map { it.entryName })
    }

    @Test
    fun `entries outside the base directory and missing files are skipped not failed`() {
        val root = temp.newFolder("files3")
        java.io.File(root, "a.txt").writeText("A")

        val (plan, skipped) = collectWorkspaceExportPlan(
            root = root,
            selection = listOf(
                entry("a.txt"),
                entry("other/elsewhere.txt"), // 不在当前目录下
                entry("gone.txt"), // 选中后被删
            ),
            basePath = "",
        )

        assertEquals(listOf("a.txt"), plan.entries.map { it.entryName })
        // 跳过清单按**路径排序**（实现里 selection.sortedBy { it.path }）：同一批导出的产物
        // 要可复现，否则两次导出的条目顺序会随 UI 选中顺序变化
        assertEquals(listOf("gone.txt", "other/elsewhere.txt"), skipped)
    }

    // ---- zip 写出 ----

    @Test
    fun `zip output matches disk content including empty dirs`() {
        val root = temp.newFolder("files4")
        java.io.File(root, "p").apply { mkdirs() }
        java.io.File(root, "p/hello.txt").writeText("hello")
        java.io.File(root, "p/empty").apply { mkdirs() }

        val (plan, _) = collectWorkspaceExportPlan(
            root = root,
            selection = listOf(entry("p", isDirectory = true)),
            basePath = "",
        )
        val bytes = ByteArrayOutputStream()
        val written = writeWorkspaceZip(bytes, plan)

        assertEquals(1, written)

        val names = mutableListOf<String>()
        val contents = mutableMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names += e.name
                if (!e.isDirectory) contents[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        assertEquals(listOf("p/", "p/empty/", "p/hello.txt"), names)
        assertEquals("hello", contents["p/hello.txt"])
    }

    private fun entry(path: String, isDirectory: Boolean = false) = WorkspaceFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        sizeBytes = 0,
        updatedAt = 0,
    )
}
