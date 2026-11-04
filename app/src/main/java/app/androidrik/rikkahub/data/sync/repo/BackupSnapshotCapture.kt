package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.appFilesPackingPlan
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import java.io.File
import java.nio.file.Files

/** Enumerates the same registry as full ZIP backups; failures are never interpreted as deletions. */
internal fun collectSnapshotFiles(filesDir: File, workspaceDir: File, items: List<BackupItem>): List<SnapshotInput> {
    val result = ArrayList<SnapshotInput>()
    fun visit(root: File, file: File, prefix: String, recursive: Boolean, managed: Boolean) {
        require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported in backup: ${file.name}" }
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "Backup file escaped its root" }
        if (managed) {
            if (file.name.startsWith("atomic-") && file.name.endsWith(".tmp")) return
            if (prefix == "${FileFolders.FONTS}/" && file.name == "chat_font_import.tmp") return
            if (prefix == "${FileFolders.MEDIA_CREATION}/" && file.name.endsWith(".part")) return
            if (prefix == "${FileFolders.SKILLS}/" && file.isDirectory &&
                file.name.startsWith('.') && file.name.endsWith(".tmp") &&
                (file.name.contains(".staging.") || file.name.contains(".backup."))) return
        }
        if (file.isDirectory) {
            if (recursive) requireNotNull(file.listFiles()) { "Cannot list backup source: ${file.name}" }.sortedBy { it.name }.forEach {
                visit(root, it, prefix, recursive, managed)
            }
        } else {
            require(file.isFile && file.canRead()) { "Cannot read backup source: ${file.name}" }
            result += SnapshotInput(prefix + file.relativeTo(root).invariantSeparatorsPath, file)
            require(result.size <= ENTRY_LIMIT) { "Too many files" }
        }
    }
    if (BackupItem.FILES in items) appFilesPackingPlan().forEach { packing ->
        val root = File(filesDir, packing.folder)
        if (root.exists()) {
            require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Invalid source folder" }
            requireNotNull(root.listFiles()) { "Cannot list backup folder" }.sortedBy { it.name }.forEach {
                visit(root, it, packing.entryPrefix, packing.recursive, true)
            }
        }
    }
    if (BackupItem.WORKSPACE_FILES in items && workspaceDir.exists()) {
        require(workspaceDir.isDirectory && !Files.isSymbolicLink(workspaceDir.toPath()))
        requireNotNull(workspaceDir.listFiles()) { "Cannot list workspaces" }.sortedBy { it.name }.forEach { workspace ->
            if (workspace.isDirectory && WorkspaceBackupHelper.isValidRoot(workspace.name)) {
                require(!Files.isSymbolicLink(workspace.toPath())) { "Workspace links are not supported" }
                val root = File(workspace, "files")
                if (root.exists()) {
                    require(root.isDirectory && !Files.isSymbolicLink(root.toPath()))
                    requireNotNull(root.listFiles()) { "Cannot list workspace files" }.sortedBy { it.name }.forEach {
                        visit(root, it, "${WorkspaceBackupHelper.ZIP_PREFIX}${workspace.name}/", true, false)
                    }
                }
            }
        }
    }
    return result
}
