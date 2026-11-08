package app.androidrik.rikkahub.data.sync

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.AttachmentLimits
import app.androidrik.ai.util.encodeBase64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupAndAttachmentInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun snapshotChecksDetectForeignKeyViolationsThatIntegrityCheckMisses() {
        val dir = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "backup-validation-test-").toFile()
        try {
            val file = File(dir, "snapshot.db")
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
                db.execSQL("CREATE TABLE child(id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))")
                db.execSQL("INSERT INTO parent VALUES(1)")
                db.execSQL("INSERT INTO child VALUES(1,1)")
            }
            validateBackupDatabase(file)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("INSERT INTO child VALUES(2,999)")
                db.rawQuery("PRAGMA integrity_check", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("ok", c.getString(0)) }
            }
            assertThrows(IllegalStateException::class.java) { validateBackupDatabase(file) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptSnapshotFailsClosed() {
        val file = File.createTempFile("corrupt-backup-", ".db", context.cacheDir)
        try {
            file.writeBytes(ByteArray(4096) { 42 })
            assertThrows(Exception::class.java) { validateBackupDatabase(file) }
        } finally { file.delete() }
    }

    @Test fun oversizedPhotoIsSampledAndOriginalRemainsUnchanged() {
        val file = File.createTempFile("send-image-test-", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(6000, 2000, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            val original = file.readBytes()
            val encoded = UIMessagePart.Image(file.toUri().toString()).encodeBase64(false).getOrThrow()
            val bytes = Base64.decode(encoded.base64, Base64.NO_WRAP)
            assertTrue(bytes.size <= AttachmentLimits.MAX_IMAGE_BYTES)
            assertEquals("image/jpeg", encoded.mimeType)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertTrue(bounds.outWidth <= AttachmentLimits.IMAGE_MAX_DIMENSION)
            assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= AttachmentLimits.IMAGE_MAX_PIXELS)
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete() }
    }
}
