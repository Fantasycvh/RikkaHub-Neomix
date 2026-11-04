package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupSnapshotCaptureTest {
    @get:Rule val temp = TemporaryFolder()
    private fun put(path: String) = temp.root.resolve(path).also { requireNotNull(it.parentFile).mkdirs(); it.writeText("content") }

    @Test fun managedTempsSkippedButWorkspaceTempFilesPreserved() {
        put("files/upload/atomic-file.random.tmp")
        put("files/upload/real.txt")
        put("files/skills/.example.staging.123.tmp/SKILL.md")
        put("files/media_creation/session/clip.part")
        put("workspaces/my-work/files/important.tmp")
        put("workspaces/my-work/files/important.part")
        val inputs = collectSnapshotFiles(temp.root.resolve("files"), temp.root.resolve("workspaces"),
            listOf(BackupItem.FILES, BackupItem.WORKSPACE_FILES))
        assertEquals(setOf("upload/real.txt", "workspace_files/my-work/important.tmp", "workspace_files/my-work/important.part"), inputs.map { it.path }.toSet())
    }

    @Test fun registryIncludesBackgroundsAndAvatarFramesAndHonorsScope() {
        put("files/backgrounds/bg.png"); put("files/avatar-frames/frame.png")
        put("files/tool_outputs/no.txt"); put("files/backup_index/state.json")
        val files = temp.root.resolve("files"); val workspaces = temp.root.resolve("workspaces")
        assertTrue(collectSnapshotFiles(files, workspaces, emptyList()).isEmpty())
        assertEquals(setOf("backgrounds/bg.png", "avatar-frames/frame.png"),
            collectSnapshotFiles(files, workspaces, listOf(BackupItem.FILES)).map { it.path }.toSet())
    }
}
