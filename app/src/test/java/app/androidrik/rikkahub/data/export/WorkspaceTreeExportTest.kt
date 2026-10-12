package app.androidrik.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * **SAF 目录树导出的写入规则**（设计 `docs/design/design-workspace-multiselect-export.md` §6.1）。
 *
 * 这条路径此前是"一行都测不了"的：逻辑直接写在仓库里、全程依赖 `DocumentFile`，
 * 而 JVM 单测没有 DocumentsProvider（Robolectric 也造不出真 provider）。
 * 抽出 [ExportTreeWriter] 后，用**内存假树**就能把整条路径跑一遍——而这里恰好是最需要测的：
 * 「跳过 vs 失败」的分档、同名不覆盖、目录缓存、"同名但不是目录"、部分成功。
 */
class WorkspaceTreeExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- 内存假树 ----

    /** 假节点：要么是目录（children），要么是文件（bytes）。 */
    private class Node(val name: String, val isDir: Boolean) {
        val children = linkedMapOf<String, Node>()
        var bytes: ByteArray = ByteArray(0)
    }

    /**
     * 内存实现：能造"同名不是目录"、"createFile 失败"、"开流失败"、"写到一半抛异常"这些
     * SAF 上真会出现、却极难在真机复现的情形。
     */
    private class FakeTreeWriter(
        override val root: Node = Node("", isDir = true),
        private val failCreateFile: (String) -> Boolean = { false },
        private val failOpenStream: (String) -> Boolean = { false },
        private val failWrite: (String) -> Boolean = { false },
    ) : ExportTreeWriter<Node> {
        /** 每个目录被 find/create 了几次（验证目录缓存）。 */
        val ensureDirCalls = mutableListOf<String>()

        override fun findChild(parent: Node, name: String): Node? {
            ensureDirCalls += "${parent.name}/$name"
            return parent.children[name]
        }

        override fun isDirectory(handle: Node): Boolean = handle.isDir

        override fun isFile(handle: Node): Boolean = !handle.isDir

        override fun createDirectory(parent: Node, name: String): Node? =
            Node(name, isDir = true).also { parent.children[name] = it }

        override fun createFile(parent: Node, name: String): Node? {
            if (failCreateFile(name)) return null
            return Node(name, isDir = false).also { parent.children[name] = it }
        }

        override fun openOutputStream(handle: Node): OutputStream? {
            if (failOpenStream(handle.name)) return null
            if (failWrite(handle.name)) return object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("disk full (simulated)")
                }
            }
            return object : ByteArrayOutputStream() {
                override fun close() {
                    handle.bytes = toByteArray()
                    super.close()
                }
            }
        }
    }

    private fun planOf(vararg names: String): WorkspaceExportPlan = WorkspaceExportPlan(
        entries = names.map { name ->
            val isDir = name.endsWith("/")
            PlannedExportEntry(
                entryName = name,
                isDirectory = isDir,
                absolutePath = "",
                sizeBytes = 0,
            )
        }
    )

    /** 造一个真实的源文件，让 writer 有东西可读。 */
    private fun planWithRealFiles(vararg names: String): WorkspaceExportPlan {
        val dir = temp.newFolder("src")
        return WorkspaceExportPlan(
            entries = names.map { name ->
                val file = File(dir, name.replace('/', '_'))
                file.writeText("content-of-$name")
                PlannedExportEntry(
                    entryName = name,
                    isDirectory = false,
                    absolutePath = file.absolutePath,
                    sizeBytes = file.length(),
                )
            }
        )
    }

    private fun FakeTreeWriter.fileContent(vararg path: String): String? {
        var node = root
        path.forEach { seg ->
            node = node.children[seg] ?: return null
        }
        return if (node.isDir) null else node.bytes.decodeToString()
    }

    // ---- 基本写入 ----

    @Test
    fun `flat files land in the root with their content`() {
        val writer = FakeTreeWriter()
        val result = exportPlanToTree(planWithRealFiles("a.txt", "b.txt"), writer)

        assertEquals(2, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failed)
        assertEquals("content-of-a.txt", writer.fileContent("a.txt"))
        assertEquals("content-of-b.txt", writer.fileContent("b.txt"))
    }

    @Test
    fun `nested files create their directory chain`() {
        val writer = FakeTreeWriter()
        val result = exportPlanToTree(planWithRealFiles("sub/deep/a.txt"), writer)

        assertEquals(1, result.succeeded)
        assertEquals("content-of-sub/deep/a.txt", writer.fileContent("sub", "deep", "a.txt"))
    }

    @Test
    fun `a directory is created once even for many children`() {
        val writer = FakeTreeWriter()
        val result = exportPlanToTree(
            planWithRealFiles("sub/a.txt", "sub/b.txt", "sub/c.txt"),
            writer,
        )

        assertEquals(3, result.succeeded)
        // 目录缓存：`sub` 只在第一次出现时被查一次
        assertEquals(1, writer.ensureDirCalls.count { it == "${writer.root.name}/sub" })
    }

    @Test
    fun `empty directories are created`() {
        val writer = FakeTreeWriter()
        val result = exportPlanToTree(planOf("empty/"), writer)

        assertNotNull(writer.root.children["empty"])
        assertTrue(writer.root.children["empty"]!!.isDir)
        assertEquals(0, result.succeeded)
        assertEquals(0, result.failed)
    }

    // ---- 跳过 vs 失败 ----

    @Test
    fun `existing file with the same name is skipped and NOT overwritten`() {
        val writer = FakeTreeWriter()
        writer.root.children["a.txt"] = Node("a.txt", isDir = false).apply {
            bytes = "user's own file".toByteArray()
        }

        val result = exportPlanToTree(planWithRealFiles("a.txt"), writer)

        assertEquals(0, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(listOf("a.txt"), result.skippedNames)
        // 关键：用户既有内容必须原样保留
        assertEquals("user's own file", writer.fileContent("a.txt"))
    }

    @Test
    fun `existing directory is reused instead of failing`() {
        val writer = FakeTreeWriter()
        writer.root.children["sub"] = Node("sub", isDir = true)

        val result = exportPlanToTree(planWithRealFiles("sub/a.txt"), writer)

        assertEquals(1, result.succeeded)
        assertEquals("content-of-sub/a.txt", writer.fileContent("sub", "a.txt"))
    }

    @Test
    fun `a file blocking a needed directory fails that entry and the run continues`() {
        val writer = FakeTreeWriter()
        // 目标里已有一个同名**文件**占着 `sub` 这个位置 ⇒ 目录建不出来
        writer.root.children["sub"] = Node("sub", isDir = false)

        val result = exportPlanToTree(planWithRealFiles("sub/a.txt", "ok.txt"), writer)

        assertEquals(listOf("sub/a.txt"), result.failedNames)
        assertEquals(1, result.failed)
        // 其余条目照常成功（部分成功语义）
        assertEquals(1, result.succeeded)
        assertEquals("content-of-ok.txt", writer.fileContent("ok.txt"))
    }

    @Test
    fun `createFile returning null counts as a failure`() {
        val writer = FakeTreeWriter(failCreateFile = { it == "bad.txt" })
        val result = exportPlanToTree(planWithRealFiles("bad.txt", "good.txt"), writer)

        assertEquals(listOf("bad.txt"), result.failedNames)
        assertEquals(1, result.succeeded)
        assertNull("nothing must be created for the failed entry", writer.fileContent("bad.txt"))
    }

    @Test
    fun `openOutputStream returning null counts as a failure`() {
        val writer = FakeTreeWriter(failOpenStream = { it == "bad.txt" })
        val result = exportPlanToTree(planWithRealFiles("bad.txt"), writer)

        assertEquals(listOf("bad.txt"), result.failedNames)
        assertEquals(0, result.succeeded)
    }

    @Test
    fun `a failing write mid-copy counts as a failure and keeps the rest going`() {
        val writer = FakeTreeWriter(failWrite = { it == "bad.txt" })
        val result = exportPlanToTree(planWithRealFiles("bad.txt", "good.txt"), writer)

        assertEquals(listOf("bad.txt"), result.failedNames)
        assertEquals(1, result.succeeded)
        assertEquals("content-of-good.txt", writer.fileContent("good.txt"))
    }

    @Test
    fun `failed empty directory counts as a failure`() {
        val writer = FakeTreeWriter()
        writer.root.children["blocked"] = Node("blocked", isDir = false)

        val result = exportPlanToTree(planOf("blocked/"), writer)

        assertEquals(listOf("blocked/"), result.failedNames)
    }

    // ---- 汇总与进度 ----

    @Test
    fun `three buckets are reported separately`() {
        val writer = FakeTreeWriter(failCreateFile = { it == "fail.txt" })
        writer.root.children["skip.txt"] = Node("skip.txt", isDir = false)

        val result = exportPlanToTree(planWithRealFiles("skip.txt", "fail.txt", "ok.txt"), writer)

        assertEquals(1, result.succeeded)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failed)
        assertEquals(3, result.total)
        assertEquals(listOf("skip.txt"), result.skippedNames)
        assertEquals(listOf("fail.txt"), result.failedNames)
    }

    @Test
    fun `progress counts only files and reaches the total`() {
        val writer = FakeTreeWriter()
        val seen = mutableListOf<Pair<Int, Int>>()

        exportPlanToTree(
            planWithRealFiles("a.txt", "sub/b.txt", "sub/c.txt"),
            writer,
            onProgress = { done, total -> seen += done to total },
        )

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
        assertFalse("progress must not fire for directories", seen.any { it.first > it.second })
    }
}
