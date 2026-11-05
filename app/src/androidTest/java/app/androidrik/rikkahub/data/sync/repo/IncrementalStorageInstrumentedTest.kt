package app.androidrik.rikkahub.data.sync.repo

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.datastore.WebDavConfig
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.s3.S3Client
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.data.sync.webdav.WebDavClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/** Synthetic data only. Run with the isolated runner and a separate application id (see design log). */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class IncrementalStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "incremental-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val device = UUID.randomUUID().toString()
    @After fun cleanup() { root.deleteRecursively() }

    private suspend fun roundTrip(store: BackupObjectStore) {
        val settings = File(root, "settings.json").apply { writeText("{}") }
        val file = File(root, "source.txt").apply { writeText("first version") }
        val inputs = listOf(SnapshotInput("settings.json", settings), SnapshotInput("upload/test.txt", file))
        val engine = IncrementalBackupEngine(File(root, "cache"))
        suspend fun plan() = engine.plan(store, inputs, listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, device, 58, "test")
        val first = plan().use { engine.commit(it) }
        plan().use { assertEquals(0L, it.uploadBytes); engine.commit(it) }
        file.writeText("second version")
        val latest = plan().use { assertTrue(it.uploadBytes > 0); engine.commit(it) }
        assertEquals(3, engine.list(store).size)
        for ((snapshot, expected) in listOf(first to "first version", latest to "second version")) {
            ZipFile(engine.assemble(store, snapshot, 58)).use { zip ->
                assertEquals(expected, zip.getInputStream(zip.getEntry("upload/test.txt")).reader().readText())
            }
        }
        engine.deleteSnapshot(store, first)
        assertEquals(2, engine.list(store).size)
        assertNotNull(store.stat(latest.files.last().key))
    }

    private fun nativeStore(): Pair<FixtureDocumentsProvider, SafBackupObjectStore> {
        val provider = FixtureDocumentsProvider(File(root, "documents").apply { mkdirs() })
        val authority = "app.androidrik.rikkahub.incremental.fixture"
        provider.attachInfo(context, ProviderInfo().apply {
            this.authority = authority
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
            applicationInfo = context.applicationInfo
        })
        val resolver = ContentResolver.wrap(provider)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        return provider to SafBackupObjectStore(AndroidBackupDocumentAccess(resolver, tree), tree.toString())
    }

    @Test fun nativeSafGarbageCollectionReclaimsUnreferencedContentAndReleasesLease() = runBlocking<Unit> {
        val (_, store) = nativeStore()
        val engine = IncrementalBackupEngine(File(root, "cache"))
        val settings = File(root, "settings.json").apply { writeText("{}") }
        val file = File(root, "source.txt").apply { writeText("first version") }
        suspend fun plan() = engine.plan(
            store, listOf(SnapshotInput("settings.json", settings), SnapshotInput("upload/test.txt", file)),
            listOf(BackupItem.FILES), SnapshotDatabaseState.NOT_SELECTED, device, 58, "test",
        )
        val first = plan().use { engine.commit(it) }
        file.writeText("second version")
        val second = plan().use { engine.commit(it) }
        engine.deleteSnapshot(store, second)

        // fixture 的 mtime 来自真实文件：零宽限期即可回收不可达对象/pack。
        val result = engine.garbageCollect(store, device, grace = java.time.Duration.ZERO)
        assertEquals(1, result.deletedObjects + result.deletedPacks)
        assertNull(store.stat("v3/gc.json"))
        assertTrue(first.objects.all { store.stat(it.key) != null })
        // 剩下的快照仍能完整还原（pack 下载/校验走真实 DocumentsProvider）。
        ZipFile(engine.assemble(store, first, 58)).use { zip ->
            assertEquals("first version", zip.getInputStream(zip.getEntry("upload/test.txt")).reader().readText())
        }
    }

    @Test fun safNativeDocumentsContractRoundTripWithReorderedColumnsAndUnknownSize() = runBlocking<Unit> {
        val (_, store) = nativeStore()
        roundTrip(store)
    }

    @Test fun safProviderFailureIsNotMissingAndAutoRenameCannotCommit() = runBlocking<Unit> {
        val (provider, store) = nativeStore()
        provider.offline = true
        assertTrue(runCatching { store.stat("repo.json") }.isFailure)
        provider.offline = false
        provider.rename = true
        val file = File(root, "blob").apply { writeText("synthetic") }
        assertTrue(runCatching { store.put("repo.json", file) }.isFailure)
        assertTrue(store.listChildren("").isEmpty())
    }

    @Test fun webDavNativeHttpRoundTrip() = runBlocking<Unit> {
        FixtureBackupServer().use { server ->
            HttpClient(OkHttp).use { http ->
                val client = WebDavClient(WebDavConfig(server.url, "fixture", "fixture", "dav/测试 backup"), http)
                roundTrip(WebDavBackupObjectStore(client, "fixture"))
                assertTrue(server.methods.contains("MKCOL"))
            }
        }
    }

    @Test fun s3NativeHttpRoundTrip() = runBlocking<Unit> {
        FixtureBackupServer().use { server ->
            HttpClient(OkHttp).use { http ->
                val client = S3Client(S3Config(server.url, "fixture", "fixture-secret", "bucket"), http)
                roundTrip(S3BackupObjectStore(client, "fixture"))
                assertTrue(server.signedRequests > 0)
            }
        }
    }

    @Test fun forbiddenWebDavDirectoryQueryDoesNotTriggerMkcol() = runBlocking<Unit> {
        FixtureBackupServer().use { server ->
            server.forbidden = true
            HttpClient(OkHttp).use { http ->
                val client = WebDavClient(WebDavConfig(server.url, "fixture", "fixture", "dav/denied"), http)
                assertTrue(client.mkdirs("v3/objects").isFailure)
                assertFalse(server.methods.contains("MKCOL"))
            }
        }
    }

    @Test fun remoteDownloadBudgetsApplyBeforeDiskWritesEvenWithoutContentLength() = runBlocking<Unit> {
        FixtureBackupServer().use { server ->
            HttpClient(OkHttp).use { http ->
                val stores = listOf(
                    S3BackupObjectStore(S3Client(S3Config(server.url, "fixture", "fixture-secret", "bucket"), http), "fixture"),
                    WebDavBackupObjectStore(WebDavClient(WebDavConfig(server.url, "fixture", "fixture", "dav"), http), "fixture"),
                )
                val source = File(root, "large").apply { writeBytes(ByteArray(65536) { 42 }) }
                stores.forEachIndexed { index, store ->
                    store.put("payload", source)
                    for (unknown in listOf(false, true)) {
                        server.unknownLength = unknown
                        val target = File(root, "budget-$index-$unknown")
                        assertTrue(runCatching { store.get("payload", target, 20) }.isFailure)
                        assertTrue(!target.exists() || target.length() <= 20)
                    }
                    server.unknownLength = false
                }
            }
        }
    }
}

/** Real Android DocumentsProvider routing and file descriptors, backed by an isolated test directory. */
private class FixtureDocumentsProvider(private val root: File) : DocumentsProvider() {
    var offline = false
    var rename = false
    override fun onCreate() = true
    private fun file(id: String): File {
        require(id == "root" || id.startsWith("root/"))
        val file = if (id == "root") root else File(root, id.removePrefix("root/"))
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        return file
    }
    private fun id(file: File) = if (file == root) "root" else "root/" + file.relativeTo(root).invariantSeparatorsPath
    // Deliberately ignore requested column order: callers must resolve names from the returned cursor.
    private fun cursor(files: List<File>): Cursor = MatrixCursor(arrayOf(Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_FLAGS)).apply {
        files.forEach { file -> addRow(arrayOf<Any?>(file.lastModified(),
            if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            null, file.name, id(file), Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_DIR_SUPPORTS_CREATE)) }
    }
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(listOf(file(documentId)))
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        if (offline) throw java.io.FileNotFoundException("fixture volume removed")
        return cursor(requireNotNull(file(parentDocumentId).listFiles()).toList())
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).toPath().startsWith(file(parentDocumentId).toPath())
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val created = File(file(parentDocumentId), if (rename) "$displayName(1)" else displayName)
        check(if (mimeType == Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile())
        return id(created)
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
}
