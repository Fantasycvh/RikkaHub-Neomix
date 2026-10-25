package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LocalWorkspaceBackendTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val root = "test-workspace"

    private fun createManager(): Pair<WorkspaceManager, File> {
        val skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        val manager = WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            shellRunner = HostShellRunner(),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        )
        return manager to skillsDir
    }

    @Test
    fun layoutWritableRootsMatchToolApprovalBoundary() {
        val (manager, _) = createManager()
        val layout = manager.layout

        assertEquals("/workspace", layout.workspaceDir)
        assertEquals(listOf("/workspace", "/tmp"), layout.writableRoots)
        assertTrue(layout.isWritable("/workspace"))
        assertTrue(layout.isWritable("/workspace/src/a.kt"))
        assertTrue(layout.isWritable("/tmp/a.txt"))
        assertFalse(layout.isWritable("/skills/demo/SKILL.md"))
        assertFalse(layout.isWritable("/upload/a.txt"))
        assertFalse(layout.isWritable("/workspace-other/a.txt"))
        assertFalse(layout.isWritable("/"))
    }

    @Test
    fun layoutConvertsBetweenAreaAndAbsolutePaths() {
        val layout = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = emptyList())

        assertEquals("/workspace", layout.absolutePath(WorkspaceStorageArea.FILES, ""))
        assertEquals("/workspace/src/a.kt", layout.absolutePath(WorkspaceStorageArea.FILES, "src/a.kt"))
        assertEquals("/", layout.absolutePath(WorkspaceStorageArea.LINUX, ""))
        assertEquals("/etc/hostname", layout.absolutePath(WorkspaceStorageArea.LINUX, "etc/hostname"))
        assertEquals("", layout.relativeToWorkspace("/workspace"))
        assertEquals("src", layout.relativeToWorkspace("/workspace/src"))
        assertEquals("src", layout.relativeToWorkspace("src"))
        assertEquals("/workspace-other/a.txt", layout.relativeToWorkspace("/workspace-other/a.txt"))
    }

    @Test
    fun fileWrittenByAreaPathIsReadableByAbsolutePath() {
        val (manager, skillsDir) = createManager()
        val backend = manager.backend(root)

        backend.writeText("notes/a.txt", "hello", overwrite = true)
        File(skillsDir, "demo.md").writeText("skill")

        assertEquals(5L, backend.absoluteFileSize("/workspace/notes/a.txt"))

        val workspaceFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/workspace/notes/a.txt", workspaceFile)
        assertEquals("hello", workspaceFile.toString(Charsets.UTF_8.name()))

        val skillFile = ByteArrayOutputStream()
        backend.exportAbsoluteFile("/skills/demo.md", skillFile)
        assertEquals("skill", skillFile.toString(Charsets.UTF_8.name()))

        assertEquals(
            File(manager.filesDir(root), "notes/a.txt").canonicalFile,
            backend.localFile(WorkspaceStorageArea.FILES, "notes/a.txt")?.canonicalFile,
        )
        assertEquals(
            File(skillsDir, "demo.md").canonicalFile,
            backend.localAbsoluteFile("/skills/demo.md")?.canonicalFile,
        )
    }

    @Test
    fun writeAbsoluteTextUsesShellAndCreatesParentDirectories() {
        Assume.assumeTrue("需要 POSIX shell（/bin/sh），Windows 宿主跳过", File("/bin/sh").exists())
        val (manager, _) = createManager()
        val backend = manager.backend(root)

        val entry = backend.writeAbsoluteText(
            path = "/workspace/deep/nested.txt",
            text = "hello from shell",
            overwrite = true,
        )

        assertEquals("/workspace/deep/nested.txt", entry.path)
        assertEquals("nested.txt", entry.name)
        assertFalse(entry.isDirectory)
        assertEquals("hello from shell", File(manager.filesDir(root), "deep/nested.txt").readText())
    }

    @Test
    fun executeCommandRunsInWorkspaceFilesDirectory() {
        Assume.assumeTrue("需要 POSIX shell（/bin/sh），Windows 宿主跳过", File("/bin/sh").exists())
        val (manager, _) = createManager()
        val backend = manager.backend(root)

        val result = backend.executeCommand(
            command = "printf hello > from-backend.txt && cat from-backend.txt",
            cwd = "",
            timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            stdin = null,
        )

        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("hello", File(manager.filesDir(root), "from-backend.txt").readText())
    }
}
