package app.androidrik.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 只读挂载快照（design-readonly-mount-snapshots，移植自 Yuihub 3.0.0 同名测试）：
 *
 * - 快照写入/新建不改原件（shell 写的是私有副本）
 * - 内容/时间戳变化 ⇒ 新一代；旧代保留（终端/后台进程可能仍在用）
 * - symlink：内部符号链接物化为独立副本；逃逸/悬空/环 fail-closed；快照被污染后换代
 * - 预算：1GB/64 代（测试收紧），超限报错且不删既有代
 * - WorkspaceManager 接线：shell 执行面拿到快照，文件工具（resolveRootfsPath）仍读原件
 *
 * symlink 用例需要 POSIX 权限：Windows 宿主无开发者模式时 `assumeSymlinks` 跳过。
 */
class ReadOnlyMountSnapshotsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun assumeSymlinks() {
        val base = temporaryFolder.newFolder()
        val target = File(base, "t").apply { writeText("x") }
        val link = File(base, "l")
        val ok = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
        Assume.assumeTrue("需要 symlink 权限（Windows 无开发者模式时跳过）", ok)
    }

    private fun source() = temporaryFolder.newFolder().also {
        File(it, "original.txt").writeText("original")
    }

    private fun mount(source: File, target: String = "/upload", readOnly: Boolean = true) =
        WorkspaceBindMount(source, target, readOnly)

    @Test
    fun snapshotWritesAndNewFilesDoNotChangeOriginals() {
        val source = source()
        val snapshot = ReadOnlyMountSnapshots()
            .prepare(listOf(mount(source)), temporaryFolder.newFolder())
            .single()

        File(snapshot.source, "original.txt").writeText("shell edit")
        File(snapshot.source, "new.txt").writeText("shell file")

        assertEquals("original", File(source, "original.txt").readText())
        assertFalse(File(source, "new.txt").exists())
        assertFalse(Files.isSameFile(File(source, "original.txt").toPath(), File(snapshot.source, "original.txt").toPath()))
        assertFalse(snapshot.readOnly)
    }

    @Test
    fun sourceChangesCreateNewGenerationWithoutRefreshingOldSnapshot() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        fun prepare() = snapshots.prepare(listOf(mount(source)), directory).single().source
        val first = prepare()
        assertEquals(first, prepare())
        val original = File(source, "original.txt")
        val mtime = Files.getLastModifiedTime(original.toPath())
        original.writeText("replaced") // 同字节数同 mtime 也必须使快照失效（指纹含内容哈希）
        Files.setLastModifiedTime(original.toPath(), mtime)
        File(source, "new.txt").writeText("new upload")
        val second = prepare()

        assertNotEquals(first, second)
        assertEquals("original", File(first, "original.txt").readText())
        assertFalse(File(first, "new.txt").exists())
        assertEquals("replaced", File(second, "original.txt").readText())
        assertEquals("new upload", File(second, "new.txt").readText())
        assertTrue(original.delete())
        assertFalse(File(prepare(), "original.txt").exists())
    }

    @Test
    fun timestampChangesAreVisibleInNewSnapshot() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        val changedTime = FileTime.fromMillis(File(source, "original.txt").lastModified() + 5000)
        Files.setLastModifiedTime(File(source, "original.txt").toPath(), changedTime)
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source

        assertNotEquals(first, second)
        assertEquals(changedTime, Files.getLastModifiedTime(File(second, "original.txt").toPath()))
    }

    @Test
    fun internalSymlinksAreMaterializedAsIndependentFilesAndDirectories() {
        assumeSymlinks()
        val source = source()
        val nested = File(source, "nested").apply { mkdirs() }
        File(nested, "data.txt").writeText("nested original")
        Files.createSymbolicLink(File(source, "alias.txt").toPath(), File("original.txt").toPath())
        Files.createSymbolicLink(File(source, "alias-dir").toPath(), nested.toPath())
        val snapshot = ReadOnlyMountSnapshots()
            .prepare(listOf(mount(source)), temporaryFolder.newFolder())
            .single().source

        assertFalse(Files.isSymbolicLink(File(snapshot, "alias.txt").toPath()))
        assertFalse(Files.isSymbolicLink(File(snapshot, "alias-dir").toPath()))
        File(snapshot, "alias.txt").writeText("edit")
        File(snapshot, "alias-dir/data.txt").writeText("edit")
        assertEquals("original", File(source, "original.txt").readText())
        assertEquals("nested original", File(nested, "data.txt").readText())
        assertEquals("original", File(snapshot, "original.txt").readText())
    }

    @Test
    fun escapingSymlinkFailsWithoutPublishingSnapshot() {
        assumeSymlinks()
        val source = source()
        val outside = temporaryFolder.newFile().also { it.writeText("outside") }
        Files.createSymbolicLink(File(source, "escape").toPath(), outside.toPath())
        val directory = temporaryFolder.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots().prepare(listOf(mount(source)), directory)
        }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals("outside", outside.readText())
    }

    @Test
    fun danglingAndCyclicSymlinksFailClosed() {
        assumeSymlinks()
        val source = source()
        val link = File(source, "link").toPath()
        Files.createSymbolicLink(link, File("missing").toPath())
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertThrows(java.io.IOException::class.java) { snapshots.prepare(listOf(mount(source)), directory) }
        Files.delete(link)
        Files.createSymbolicLink(link, source.toPath())
        assertThrows(IllegalArgumentException::class.java) { snapshots.prepare(listOf(mount(source)), directory) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun poisonedPublishedSnapshotIsNeverUsedAsSyncDestination() {
        assumeSymlinks()
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        val copied = File(first, "original.txt").toPath()
        Files.delete(copied)
        Files.createSymbolicLink(copied, File(source, "original.txt").toPath())
        Files.createSymbolicLink(File(first, "nested").toPath(), source.toPath())
        File(source, "nested").mkdirs()
        File(source, "nested/new.txt").writeText("source addition")
        File(source, "original.txt").writeText("source update")
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source

        assertNotEquals(first, second)
        assertTrue(Files.isSymbolicLink(copied))
        assertEquals("source update", File(source, "original.txt").readText())
        assertFalse(File(source, "new.txt").exists())
        assertEquals("source addition", File(second, "nested/new.txt").readText())
        assertFalse(Files.isSymbolicLink(File(second, "nested").toPath()))
    }

    @Test
    fun snapshotStorageCannotBeInsideSource() {
        val source = source()
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots().prepare(listOf(mount(source)), File(source, "snapshots"))
        }
    }

    @Test
    fun missingReadOnlySourceIsSkippedButDanglingSourceSymlinkIsRejected() {
        assumeSymlinks()
        val missing = File(temporaryFolder.root, "missing")
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertTrue(snapshots.prepare(listOf(mount(missing)), directory).isEmpty())
        val link = File(temporaryFolder.root, "dangling").toPath()
        Files.createSymbolicLink(link, missing.toPath())
        assertThrows(java.io.IOException::class.java) { snapshots.prepare(listOf(mount(link.toFile())), directory) }
    }

    @Test
    fun pollutedSnapshotIsReplacedEvenWhenOriginalIsUnchanged() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        File(first, "original.txt").writeText("polluted")
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(first, second)
        assertEquals("original", File(second, "original.txt").readText())
        assertEquals("polluted", File(first, "original.txt").readText())
        assertEquals(2, directory.listFiles().orEmpty().size)
    }

    @Test
    fun snapshotBudgetRejectsNewGenerationsWithoutDeletingExistingOnes() {
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots(maxStorageBytes = 20, maxGenerations = 2)
        // 本仓恒复用：靠内容变化制造新代（指纹不同才开新一代）
        fun bump() {
            val f = File(source, "original.txt")
            Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(System.currentTimeMillis() + (1..1000).random()))
        }
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        bump()
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        bump()
        val error = assertThrows(IllegalArgumentException::class.java) {
            snapshots.prepare(listOf(mount(source)), directory)
        }
        assertTrue(error.message!!.contains("storage budget exhausted"))
        assertEquals("original", File(first, "original.txt").readText())
        assertEquals("original", File(second, "original.txt").readText())
        assertEquals(2, directory.listFiles().orEmpty().size)
        assertThrows(IllegalArgumentException::class.java) {
            ReadOnlyMountSnapshots(maxStorageBytes = 1).prepare(listOf(mount(source)), temporaryFolder.newFolder())
        }
    }

    @Test
    fun replacingGenerationRootWithSymlinkCannotBindOriginalOnNextCall() {
        assumeSymlinks()
        val source = source()
        val directory = temporaryFolder.newFolder()
        val snapshots = ReadOnlyMountSnapshots()
        val first = snapshots.prepare(listOf(mount(source)), directory).single().source
        Files.delete(File(first, "original.txt").toPath())
        Files.delete(first.toPath())
        Files.createSymbolicLink(first.toPath(), source.toPath())
        val second = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(first, second)
        assertFalse(Files.isSymbolicLink(second.toPath()))
        File(second, "original.txt").writeText("edit")
        assertEquals("original", File(source, "original.txt").readText())
    }

    @Test
    fun missingSourceAppearingLaterIsSnapshotted() {
        val source = File(temporaryFolder.root, "later")
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        assertTrue(snapshots.prepare(listOf(mount(source)), directory).isEmpty())
        source.mkdirs()
        File(source, "new.txt").writeText("later upload")
        val snapshot = snapshots.prepare(listOf(mount(source)), directory).single().source
        assertNotEquals(source, snapshot)
        assertEquals("later upload", File(snapshot, "new.txt").readText())
    }

    @Test
    fun concurrentPreparationPublishesCompleteGenerations() {
        val source = source()
        val snapshots = ReadOnlyMountSnapshots()
        val directory = temporaryFolder.newFolder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = executor.invokeAll((1..8).map {
                Callable { snapshots.prepare(listOf(mount(source)), directory).single().source }
            })
            val generations = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, generations.distinct().size)
            generations.forEach { assertEquals("original", File(it, "original.txt").readText()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun nonReadOnlyMountsPassThroughUntouched() {
        val source = source()
        val result = ReadOnlyMountSnapshots()
            .prepare(listOf(mount(source, "/skills", readOnly = false)), temporaryFolder.newFolder())
            .single()
        assertEquals(source, result.source)
        assertFalse(result.readOnly)
    }

    // ---- WorkspaceManager 接线 ----

    @Test
    fun managerExecutionUsesSnapshotsButFileToolsStillReadOriginals() {
        val source = source()
        val writable = temporaryFolder.newFolder()
        var captured: WorkspaceShellContext? = null
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(source), mount(writable, "/editable", readOnly = false)),
            shellRunner = object : WorkspaceShellRunner {
                override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
                    captured = context
                    // 模拟 shell 写 /upload：写的是快照
                    File(context.bindMounts.single { it.target == "/upload" }.source, "original.txt")
                        .writeText("shell edit")
                    return WorkspaceCommandResult(0, "", "")
                }
            },
            readOnlySnapshotStorage = temporaryFolder.newFolder(),
        )
        manager.ensureWorkspace("test-workspace")
        manager.executeCommand("test-workspace", "edit")
        val context = checkNotNull(captured)
        val snapshotSource = context.bindMounts.single { it.target == "/upload" }.source
        assertNotEquals(source, snapshotSource)
        assertEquals("shell edit", File(snapshotSource, "original.txt").readText())
        // 可写挂载原样透传
        assertEquals(writable, context.bindMounts.single { it.target == "/editable" }.source)
        // 文件工具解析仍指向原件（快照只进 shell 执行面）
        assertEquals(source, manager.resolveRootfsPath("test-workspace", "/upload/original.txt").rootDir)
        assertEquals("original", manager.resolveReadableRootfsFile("test-workspace", "/upload/original.txt").readText())
        // 原件未被 shell 写污染
        assertEquals("original", File(source, "original.txt").readText())
    }

    @Test
    fun managerWithoutSnapshotStoragePassesReadOnlyMountThroughAndRunnerFailsClosed() {
        val source = source()
        val manager = WorkspaceManager(
            baseDir = temporaryFolder.newFolder(),
            bindMounts = listOf(mount(source)),
            shellRunner = ProotShellRunner(nativeLibraryDir = temporaryFolder.newFolder()),
            readOnlySnapshotStorage = null,
        )
        val root = "test-workspace"
        manager.ensureWorkspace(root)
        // 伪造最小 rootfs 与 proot 文件，让 start() 的预检通过、到达只读守卫
        val linuxDir = manager.linuxDir(root)
        val binDir = File(linuxDir, "bin")
        binDir.mkdirs()
        File(binDir, "sh").writeText("#!/bin/sh")
        manager.tempDir(root).mkdirs()
        val native = temporaryFolder.newFolder()
        File(native, "libproot_exec.so").writeText("fake")
        File(native, "libproot_loader.so").writeText("fake")
        val runner = ProotShellRunner(nativeLibraryDir = native)
        val context = WorkspaceShellContext(
            root = root,
            command = "edit",
            cwd = "",
            filesDir = manager.filesDir(root),
            linuxDir = linuxDir,
            tempDir = manager.tempDir(root),
            workingDir = manager.filesDir(root),
            timeoutMillis = 1000,
            stdin = null,
            bindMounts = listOf(mount(source)),
        )
        // WorkspaceManager 直通（storage=null）⇒ runner 的 require(!readOnly) 当场失败，
        // 不会走到 rootfs patch / 进程创建
        assertThrows(IllegalArgumentException::class.java) { runner.start(context) }
        assertEquals("original", File(source, "original.txt").readText())
    }
}
