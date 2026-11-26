package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RecoveryBackupImporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createZip(vararg entries: Pair<String, String>): File {
        val file = tempFolder.newFile("archive_${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `accepts standard backup archives containing settings or database`() {
        val settingsOnly = createZip("settings.json" to "{}")
        val dbOnly = createZip("rikka_hub.db" to "sqlite-placeholder")
        assertEquals(RecoveryArchiveCheck.VALID, checkRecoveryBackupZip(settingsOnly))
        assertEquals(RecoveryArchiveCheck.VALID, checkRecoveryBackupZip(dbOnly))
    }

    @Test
    fun `rejects rescue archives for manual recovery only`() {
        val rescue = createZip(
            RESCUE_REPORT_ENTRY to "rescue report",
            "settings.json" to "{}",
        )
        assertEquals(RecoveryArchiveCheck.RESCUE_ARCHIVE, checkRecoveryBackupZip(rescue))
    }

    @Test
    fun `rejects arbitrary zip files without backup payload`() {
        val randomZip = createZip("notes/readme.txt" to "hello")
        assertEquals(RecoveryArchiveCheck.INVALID_ARCHIVE, checkRecoveryBackupZip(randomZip))
    }
}
