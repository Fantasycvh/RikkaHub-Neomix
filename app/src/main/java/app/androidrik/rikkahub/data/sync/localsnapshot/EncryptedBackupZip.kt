package app.androidrik.rikkahub.data.sync.localsnapshot

import app.androidrik.rikkahub.data.sync.BackupManifest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal object EncryptedBackupZip {
    private const val ENTRY = "rikka-backup.zip"
    // The inner ZIP contains the archive's capped uncompressed data plus ZIP metadata.
    const val MAX_ARCHIVE_BYTES = BackupManifest.MAX_TOTAL_BYTES + 128L * 1024 * 1024

    fun isWrapped(file: File): Boolean = ZipFile(file).use { it.fileHeaders.any { entry -> entry.fileName == ENTRY } }

    suspend fun wrap(archive: File, target: File, password: CharArray) {
        require(archive.length() in 1..MAX_ARCHIVE_BYTES)
        val parameters = ZipParameters().apply {
            fileNameInZip = ENTRY
            compressionMethod = CompressionMethod.STORE
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            entrySize = archive.length()
        }
        try {
            ZipOutputStream(target.outputStream().buffered(), password).use { output ->
                output.putNextEntry(parameters)
                archive.inputStream().buffered().use { input -> copy(input, output, parameters.entrySize) }
                output.closeEntry()
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    suspend fun unwrap(wrapper: File, target: File, password: CharArray) {
        try {
            ZipFile(wrapper, password).use { zip ->
                val entry = zip.fileHeaders.singleOrNull()
                require(entry != null && supported(entry)) { "Invalid encrypted backup" }
                zip.getInputStream(entry).use { input ->
                    target.outputStream().buffered().use { output -> copy(input, output, entry.uncompressedSize) }
                }
            }
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    internal suspend fun copy(input: InputStream, output: OutputStream, expected: Long? = null) {
        val buffer = ByteArray(128 * 1024)
        var bytes = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            require(bytes <= (expected ?: MAX_ARCHIVE_BYTES)) { "Invalid backup size" }
            output.write(buffer, 0, count)
        }
        require(bytes > 0 && (expected == null || bytes == expected)) { "Incomplete backup" }
    }

    private fun supported(entry: FileHeader): Boolean = entry.fileName == ENTRY && !entry.isDirectory &&
        entry.isEncrypted && entry.encryptionMethod == EncryptionMethod.AES &&
        entry.aesExtraDataRecord?.aesKeyStrength == AesKeyStrength.KEY_STRENGTH_256 &&
        entry.uncompressedSize in 1..MAX_ARCHIVE_BYTES
}
