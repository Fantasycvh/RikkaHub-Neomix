package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.recoverySettingsFile
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RecoveryBackupInstrumentedTest {
    @Test
    fun fullArchiveUsesPersistedSettingsAndReplaysCopiedWal() = withProfile { profile ->
        val settingsFile = recoverySettingsFile(profile)
        val job = Job()
        try {
            PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { settingsFile }.edit {
                it[SettingsStore.VERSION] = 6
                it[SettingsStore.DEVELOPER_MODE] = true
            }
        } finally {
            job.cancelAndJoin()
        }
        val originalSettings = settingsFile.readBytes()
        val main = profile.getDatabasePath(AppDatabase.NAME)
        val source = File(main.parentFile, "fixture.db")
        SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
            assertTrue(db.enableWriteAheadLogging())
            db.execSQL("CREATE TABLE MemoryEntity(id INTEGER PRIMARY KEY, value TEXT)")
            db.version = AppDatabase.SCHEMA_VERSION
            db.execSQL("INSERT INTO MemoryEntity VALUES(1, 'committed in WAL')")
            source.copyTo(main)
            assertTrue(File(source.path + "-wal").length() > 0)
            File(source.path + "-wal").copyTo(File(main.path + "-wal"))
        }
        val mainHash = sha256Of(main)
        val walHash = sha256Of(File(main.path + "-wal"))

        val archive = RecoveryBackupExporter(profile).prepare(rescue = false)
        ZipFile(archive.file).use { zip ->
            val settingsBytes = zip.bytes("settings.json")
            assertTrue(JsonInstant.decodeFromString<Settings>(settingsBytes.decodeToString()).developerMode)
            val manifest = JsonInstant.decodeFromString<BackupManifest>(zip.bytes(BackupManifest.ZIP_ENTRY).decodeToString())
            assertEquals(sha256Hex(settingsBytes), manifest.settingsSha256)
            val snapshot = File(profile.cacheDir, "exported.db").apply { writeBytes(zip.bytes("rikka_hub.db")) }
            assertEquals(sha256Of(snapshot), manifest.databaseSha256)
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM MemoryEntity WHERE id = 1", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("committed in WAL", it.getString(0))
                }
            }
        }
        assertArrayEquals(originalSettings, settingsFile.readBytes())
        assertEquals(mainHash, sha256Of(main))
        assertEquals(walHash, sha256Of(File(main.path + "-wal")))
    }

    @Test
    fun corruptSourceCanStillBeExportedForManualRecovery() = withProfile { profile ->
        val main = profile.getDatabasePath(AppDatabase.NAME).apply { writeText("invalid database") }
        val wal = File(main.path + "-wal").apply { writeText("original wal") }
        val settings = recoverySettingsFile(profile).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0x80.toByte()))
        }
        val attachment = File(profile.filesDir, "${FileFolders.UPLOAD}/attachment.txt").apply {
            parentFile!!.mkdirs()
            writeText("user attachment")
        }
        val exporter = RecoveryBackupExporter(profile)
        val failure = runCatching { exporter.prepare(rescue = false) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, profile.cacheDir.listFiles()!!.size)
        assertArrayEquals(byteArrayOf(0x80.toByte()), settings.readBytes())

        val archive = exporter.prepare(rescue = true)
        assertEquals(0, archive.omissions)
        ZipFile(archive.file).use { zip ->
            assertArrayEquals(main.readBytes(), zip.bytes("databases/${main.name}"))
            assertArrayEquals(wal.readBytes(), zip.bytes("databases/${wal.name}"))
            assertArrayEquals(settings.readBytes(), zip.bytes("files/datastore/${settings.name}"))
            assertArrayEquals(attachment.readBytes(), zip.bytes("files/${FileFolders.UPLOAD}/attachment.txt"))
            assertNotNull(zip.getEntry("rescue-report.txt"))
            assertNull(zip.getEntry(BackupManifest.ZIP_ENTRY))
        }
    }

    private fun withProfile(block: suspend (Context) -> Unit) = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "recovery-fixture-").toFile()
        try {
            block(object : ContextWrapper(context) {
                private val files = File(directory, "files").apply { mkdir() }
                private val cache = File(directory, "cache").apply { mkdir() }
                override fun getDatabasePath(name: String) = File(directory, name)
                override fun getFilesDir() = files
                override fun getCacheDir() = cache
            })
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    private fun ZipFile.bytes(name: String): ByteArray = getInputStream(getEntry(name)).use { it.readBytes() }
}
