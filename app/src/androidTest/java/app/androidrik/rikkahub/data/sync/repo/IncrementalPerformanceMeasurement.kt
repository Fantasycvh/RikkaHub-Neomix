package app.androidrik.rikkahub.data.sync.repo

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.StartupState
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.sync.BackupItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipFile
import java.util.zip.DeflaterOutputStream

/** 一次性性能测量（真实 debug 库）；跑完只留 logcat，不改用户数据。 */
@RunWith(AndroidJUnit4::class)
class IncrementalPerformanceMeasurement {
    private val TAG = "PerfMeasure"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        val result = block()
        Log.i(TAG, "PHASE $label ${SystemClock.elapsedRealtime() - start} ms")
        return result
    }

    private class LocalDirStore(private val root: File) : BackupObjectStore {
        override val destinationId: String get() = root.absolutePath
        private fun fileOf(key: String) = File(root, key)
        override suspend fun listChildren(prefix: String): List<BackupStoredObject> = withContext(Dispatchers.IO) {
            val dir = if (prefix.isEmpty()) root else fileOf(prefix)
            (dir.listFiles() ?: emptyArray()).map {
                BackupStoredObject(
                    (if (prefix.isEmpty()) "" else "$prefix/") + it.name,
                    if (it.isDirectory) null else it.length(),
                    it.isDirectory,
                )
            }
        }

        override suspend fun stat(key: String): BackupStoredObject? = withContext(Dispatchers.IO) {
            val file = fileOf(key)
            if (!file.exists()) null else BackupStoredObject(key, if (file.isDirectory) null else file.length(), file.isDirectory)
        }

        override suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
            val file = fileOf(key)
            file.inputStream().use { input -> target.outputStream().use { out ->
                var copied = 0L
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= maxBytes) { "too large" }
                    out.write(buffer, 0, read)
                }
                onBytes(copied)
            } }
        }

        override suspend fun put(key: String, source: File, onBytes: (Long) -> Unit) = withContext(Dispatchers.IO) {
            val target = fileOf(key)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            onBytes(target.length())
        }

        override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
            fileOf(key).delete(); Unit
        }

        override suspend fun ensureDir(prefix: String) = withContext(Dispatchers.IO) {
            fileOf(prefix).mkdirs(); Unit
        }
    }

    @Test
    fun measure() {
        runBlocking {
            val app = context.applicationContext as RikkaHubApp
            withTimeout(90_000) { app.startupState.first { it is StartupState.Ready } }
            val koin = GlobalContext.get()
            val database = koin.get<AppDatabase>()
            val settingsStore = koin.get<SettingsStore>()
            val json = koin.get<Json>()
            val conversationRepository = koin.get<ConversationRepository>()

            val root = File(context.cacheDir, "perf-measure").apply { deleteRecursively(); mkdirs() }
            try {
                val snapshot = File(root, "db-snapshot.db")
                timed("vacuum-into") {
                    database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(snapshot.absolutePath))
                }
                val objects = timed("capture") { DatabaseObjectCapture.capture(snapshot, File(root, "staging").apply { mkdirs() }) }
                val payloadBytes = objects.sumOf { it.file.length() }
                Log.i(TAG, "DATA objects=${objects.size} payloadBytes=$payloadBytes dbBytes=${snapshot.length()}")

                timed("sha256-all") { objects.forEach { fileSha256(it.file) } }
                listOf(1, 6).forEach { level ->
                    var encodedBytes = 0L
                    timed("deflate-level-$level") {
                        objects.forEach { input ->
                            val encoded = File(root, "tmp-encoded")
                            encoded.outputStream().buffered().use { out ->
                                val deflater = Deflater(level, true)
                                try { DeflaterOutputStream(out, deflater).use { input.file.inputStream().copyTo(it) } }
                                finally { deflater.end() }
                            }
                            encodedBytes += encoded.length()
                        }
                    }
                    Log.i(TAG, "DATA deflate-level-$level encodedBytes=$encodedBytes")
                }

                val settingsFile = File(root, "settings.json").apply { writeText(json.encodeToString(settingsStore.settingsFlow.value)) }
                val engine = IncrementalBackupEngine(File(root, "engine-cache"))
                val store = LocalDirStore(File(root, "repo").apply { mkdirs() })
                val device = UUID.randomUUID().toString()
                val plan = timed("plan-new-store") {
                    engine.plan(
                        store, listOf(SnapshotInput("settings.json", settingsFile)),
                        listOf(BackupItem.FILES, BackupItem.DATABASE), SnapshotDatabaseState.PRESENT,
                        device, AppDatabase.SCHEMA_VERSION, "1", objects,
                    )
                }
                Log.i(TAG, "DATA plan uploads=${plan.uploads.size} uploadBytes=${plan.uploads.values.sumOf { it.length() }} reused=${plan.reusedBytes}")
                val committed = timed("commit-new-store") { engine.commit(plan) }
                Log.i(TAG, "DATA commit objects=${committed.objects.size}")

                timed("vacuum-into-2") {
                    database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(snapshot.absolutePath))
                }
                val staging2 = File(root, "staging2").apply { mkdirs() }
                val objects2 = timed("capture-warm") { DatabaseObjectCapture.capture(snapshot, staging2) }
                val plan2 = timed("plan-warm") {
                    engine.plan(
                        store, listOf(SnapshotInput("settings.json", settingsFile)),
                        listOf(BackupItem.FILES, BackupItem.DATABASE), SnapshotDatabaseState.PRESENT,
                        device, AppDatabase.SCHEMA_VERSION, "1", objects2,
                    )
                }
                Log.i(TAG, "DATA plan-warm uploads=${plan2.uploads.size} uploadBytes=${plan2.uploads.values.sumOf { it.length() }} reused=${plan2.reusedBytes}")
                plan2.close()

                val snap = engine.list(store).first { it.snapshotId == committed.snapshotId }
                val zip = timed("assemble-total") {
                    engine.assemble(store, snap, AppDatabase.SCHEMA_VERSION) { decoded, dir ->
                        val base = File(dir, "base.db")
                        timed("merge-base-vacuum") {
                            database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(base.absolutePath))
                        }
                        timed("merge") {
                            DatabaseObjectMerge.merge(
                                base, decoded.map { it.objectKey to it.raw },
                                currentDbVersion = AppDatabase.SCHEMA_VERSION,
                                snapshotDbVersion = snap.dbVersion,
                            )
                        }
                    }
                }
                Log.i(TAG, "DATA zipBytes=${zip.length()}")

                // 恢复暂存会立刻把 DB 解压回来：验证 STORED 条目可读，并量一次解压耗时。
                val unzipped = File(root, "unzipped").apply { mkdirs() }
                timed("unzip-zip") {
                    ZipFile(zip).use { zipFile ->
                        zipFile.entries().asSequence().forEach { entry ->
                            if (entry.isDirectory) return@forEach
                            val target = File(unzipped, entry.name)
                            target.parentFile?.mkdirs()
                            zipFile.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
                val unzippedDb = File(unzipped, "rikka_hub.db")
                val restoredConversations = timed("verify-zip-db") {
                    SQLiteDatabase.openDatabase(unzippedDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                            check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "unzipped db integrity_check failed" }
                        }
                        db.rawQuery("SELECT COUNT(*) FROM conversationentity", null).use { cursor ->
                            if (cursor.moveToFirst()) cursor.getInt(0) else -1
                        }
                    }
                }
                Log.i(TAG, "DATA zipDbBytes=${unzippedDb.length()} restoredConversations=$restoredConversations")

                var lastLog = SystemClock.elapsedRealtime()
                timed("fts-rebuild") {
                    conversationRepository.rebuildAllIndexes { done, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLog > 2000) { lastLog = now; Log.i(TAG, "DATA fts-progress $done/$total") }
                    }
                }
                val ftsRows = database.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM message_fts").use { if (it.moveToFirst()) it.getInt(0) else -1 }
                Log.i(TAG, "DATA ftsRows=$ftsRows")
            } finally {
                root.deleteRecursively()
                Log.i(TAG, "END")
            }
        }
    }
}
