package app.androidrik.rikkahub.data.sync.localsnapshot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EncryptedBackupZipTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun roundTripAndRejectWrongPasswordOrTampering() = runBlocking {
        val original = temp.newFile()
        ZipOutputStream(original.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write("test archive".toByteArray())
            zip.closeEntry()
        }
        val encrypted = temp.newFile()
        val password = "unit-test-password".toCharArray()
        EncryptedBackupZip.wrap(original, encrypted, password)
        assertFalse(EncryptedBackupZip.isWrapped(original))
        assertTrue(EncryptedBackupZip.isWrapped(encrypted))

        val plain = temp.newFile()
        EncryptedBackupZip.unwrap(encrypted, plain, password)
        assertArrayEquals(original.readBytes(), plain.readBytes())
        val wrong = temp.newFile()
        assertTrue(runCatching { EncryptedBackupZip.unwrap(encrypted, wrong, "wrong password".toCharArray()) }.isFailure)
        assertFalse(wrong.exists())

        val bytes = encrypted.readBytes()
        bytes[100] = (bytes[100].toInt() xor 1).toByte()
        encrypted.writeBytes(bytes)
        val damaged = temp.newFile()
        assertTrue(runCatching { EncryptedBackupZip.unwrap(encrypted, damaged, password) }.isFailure)
        assertFalse(damaged.exists())
    }
}
