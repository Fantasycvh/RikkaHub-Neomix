package app.androidrik.rikkahub.data.sync

import android.content.Context
import app.androidrik.rikkahub.data.datastore.RecoverySettings
import app.androidrik.rikkahub.data.datastore.recoverySettingsFile
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val RESCUE_REPORT_ENTRY = "rescue-report.txt"

internal data class RecoveryArchive(val file: File, val omissions: Int = 0)

internal class RecoveryBackupExporter(private val context: Context) {
    // Call on IO while normal startup is paused or has failed.
    suspend fun prepare(rescue: Boolean): RecoveryArchive {
        if (rescue) return prepareRescue()
        check(!PendingRestoreStore.hasPending(context.filesDir) && !PendingRestoreStore.hasApplied(context.filesDir)) {
            "A database restore is pending or unconfirmed"
        }
        val writer = BackupArchiveWriter(context, JsonInstant)
        return RecoveryArchive(writer.prepare(BackupItem.entries, BackupScope()) {
            RecoverySettings(context).readForBackup()
        })
    }

    private suspend fun prepareRescue(): RecoveryArchive {
        val coroutine = currentCoroutineContext()
        val settings = recoverySettingsFile(context)
        val database = context.getDatabasePath(AppDatabase.NAME)
        val workspaceDir = WorkspaceBackupHelper.workspacesDir(context)
        val archive = File.createTempFile("backup_rescue-", ".zip", context.cacheDir)
        val omitted = mutableListOf<String>()
        var captured = 0
        val buffer = ByteArray(64 * 1024)
        try {
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                fun addSource(file: File, name: String, recursive: Boolean) {
                    coroutine.ensureActive()
                    if (Files.isSymbolicLink(file.toPath()) || '\\' in file.name) {
                        omitted += name
                        return
                    }
                    if (file.isDirectory) {
                        if (!recursive) return
                        val children = file.listFiles()
                        if (children == null) omitted += name
                        else children.forEach { addSource(it, "$name/${it.name}", recursive = true) }
                        return
                    }
                    if (!file.isFile) {
                        omitted += name
                        return
                    }
                    val input = try {
                        file.inputStream()
                    } catch (_: IOException) {
                        omitted += name
                        return
                    }
                    input.use {
                        zip.putNextEntry(ZipEntry(name))
                        while (true) {
                            coroutine.ensureActive()
                            val count = try {
                                it.read(buffer)
                            } catch (_: IOException) {
                                // The entry may contain a prefix. The report marks it as incomplete.
                                omitted += name
                                break
                            }
                            if (count < 0) {
                                captured++
                                break
                            }
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }

                listOf(settings).plus(settings.parentFile?.listFiles { _, name ->
                    name.startsWith("${settings.name}.corrupt-")
                }.orEmpty()).forEach { file ->
                    if (file.exists()) addSource(file, "files/datastore/${file.name}", recursive = false)
                }
                listOf("", "-wal", "-journal", "-shm").forEach { suffix ->
                    val source = File(database.path + suffix)
                    if (source.exists()) addSource(source, "databases/${source.name}", recursive = false)
                }
                listOf("restore_pending", "restore_applied", "migration_snapshots", "downgrade_pending").forEach { name ->
                    val directory = File(context.filesDir, name)
                    if (directory.exists()) addSource(directory, "files/$name", recursive = true)
                }
                appFilesPackingPlan().forEach { packing ->
                    val directory = File(context.filesDir, packing.folder)
                    if (directory.exists()) {
                        val files = directory.takeUnless { Files.isSymbolicLink(it.toPath()) }?.listFiles()
                        if (files == null) omitted += "files/${packing.folder}"
                        else files.forEach { file ->
                            addSource(file, "files/${packing.entryPrefix}${file.name}", packing.recursive)
                        }
                    }
                }
                if (Files.isSymbolicLink(workspaceDir.toPath())) {
                    omitted += "files/${workspaceDir.name}"
                } else if (workspaceDir.exists()) {
                    val workspaces = workspaceDir.listFiles()
                    if (workspaces == null) omitted += "files/${workspaceDir.name}"
                    workspaces?.filter { WorkspaceBackupHelper.isValidRoot(it.name) }?.forEach { workspace ->
                        val files = File(workspace, "files")
                        val entry = "files/${workspaceDir.name}/${workspace.name}/files"
                        if (Files.isSymbolicLink(workspace.toPath())) omitted += entry
                        else if (files.exists()) addSource(files, entry, recursive = true)
                    }
                }
                check(captured > 0) { "No readable recovery files were found" }
                zip.putNextEntry(ZipEntry(RESCUE_REPORT_ENTRY))
                val report = buildString {
                    appendLine("RikkaHub rescue archive. The raw files are intended for manual recovery.")
                    appendLine("Entries missing or incomplete due to read errors: ${omitted.size}")
                    omitted.forEach { appendLine(it) }
                }
                zip.write(report.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return RecoveryArchive(archive, omitted.size)
        } catch (failure: Throwable) {
            archive.delete()
            throw failure
        }
    }
}
