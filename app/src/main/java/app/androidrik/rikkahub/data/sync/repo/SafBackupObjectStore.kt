package app.androidrik.rikkahub.data.sync.repo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class SafDocument(val id: String, val name: String, val directory: Boolean, val bytes: Long?)

/** Small provider boundary, also exercised by JVM tests with hostile/failed providers. */
internal interface BackupDocumentAccess {
    val rootId: String
    fun children(parent: String): List<SafDocument>
    fun create(parent: String, name: String, directory: Boolean): SafDocument
    fun read(id: String): InputStream
    fun write(id: String): OutputStream
    fun delete(id: String)
}

internal class AndroidBackupDocumentAccess(private val resolver: ContentResolver, private val tree: Uri) : BackupDocumentAccess {
    override val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private fun uri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    override fun children(parent: String): List<SafDocument> {
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        return requireNotNull(resolver.query(query, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE), null, null, null)) { "Cannot list backup folder" }.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            buildList {
                while (cursor.moveToNext()) add(SafDocument(cursor.getString(id), cursor.getString(name),
                    cursor.getString(mime) == Document.MIME_TYPE_DIR, if (cursor.isNull(size)) null else cursor.getLong(size)))
            }
        }
    }
    override fun create(parent: String, name: String, directory: Boolean): SafDocument {
        val created = requireNotNull(DocumentsContract.createDocument(resolver, uri(parent),
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)) { "Cannot create backup entry" }
        val id = DocumentsContract.getDocumentId(created)
        return children(parent).single { it.id == id }
    }
    override fun read(id: String) = requireNotNull(resolver.openInputStream(uri(id))) { "Cannot read backup file" }
    override fun write(id: String) = requireNotNull(resolver.openOutputStream(uri(id), "wt")) { "Cannot write backup file" }
    override fun delete(id: String) { check(DocumentsContract.deleteDocument(resolver, uri(id))) { "Cannot delete snapshot file" } }
}

internal class SafBackupObjectStore(private val access: BackupDocumentAccess, override val destinationId: String) : BackupObjectStore {
    private fun children(id: String): List<SafDocument> = access.children(id).also { list ->
        require(list.map { it.name }.distinct().size == list.size) { "Ambiguous duplicate names in backup folder" }
    }
    private fun resolve(key: String): SafDocument? {
        if (key.isEmpty()) return SafDocument(access.rootId, "", true, null)
        validateKey(key)
        var current = SafDocument(access.rootId, "", true, null)
        key.split('/').forEach { part ->
            require(current.directory) { "Backup path is not a directory" }
            current = children(current.id).singleOrNull { it.name == part } ?: return null
        }
        return current
    }
    override suspend fun listChildren(prefix: String): List<BackupStoredObject> {
        val parent = resolve(prefix) ?: return emptyList()
        require(parent.directory)
        return children(parent.id).map { BackupStoredObject((if (prefix.isEmpty()) "" else "$prefix/") + it.name, it.bytes, it.directory) }
    }
    override suspend fun stat(key: String) = resolve(key)?.let { BackupStoredObject(key, it.bytes, it.directory) }
    override suspend fun get(key: String, target: File, maxBytes: Long, onBytes: (Long) -> Unit) {
        val doc = requireNotNull(resolve(key)) { "Missing backup file: $key" }
        require(!doc.directory && (doc.bytes == null || doc.bytes <= maxBytes)) { "Invalid backup file size" }
        val coroutine = currentCoroutineContext()
        access.read(doc.id).use { input -> target.outputStream().use { output ->
            copyBounded(input, output, maxBytes) { coroutine.ensureActive(); onBytes(it) }
        } }
    }
    override suspend fun put(key: String, source: File, onBytes: (Long) -> Unit) {
        validateKey(key)
        val parent = requireNotNull(resolve(key.substringBeforeLast('/', ""))) { "Missing backup directory" }
        val name = key.substringAfterLast('/')
        // Content-addressed files and UUID manifests are immutable. Never truncate an existing document.
        val existing = children(parent.id).singleOrNull { it.name == name }
        if (existing != null) {
            require(!existing.directory) { "Backup file is a directory" }
            val checkFile = File.createTempFile("repo-compare", ".tmp", source.parentFile)
            try {
                get(key, checkFile, source.length())
                require(fileSha256(checkFile) == fileSha256(source)) { "Existing backup object differs; repository repair is required" }
            } finally { checkFile.delete() }
            return
        }
        val created = access.create(parent.id, name, false)
        val coroutine = currentCoroutineContext()
        try {
            require(created.name == name && !created.directory) { "Provider changed the backup filename" }
            require(children(parent.id).single { it.name == name }.id == created.id) { "Backup filename collision" }
            access.write(created.id).use { output -> source.inputStream().use { input ->
                copyBounded(input, output, source.length()) { coroutine.ensureActive(); onBytes(it) }
            } }
        } catch (failure: Throwable) {
            // Only this newly-created document is ours to remove; never delete an existing object.
            try { access.delete(created.id) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    override suspend fun ensureDir(prefix: String) {
        validateKey(prefix)
        var parent = access.rootId
        prefix.split('/').forEach { name ->
            val doc = children(parent).singleOrNull { it.name == name } ?: access.create(parent, name, true)
            require(doc.name == name && doc.directory) { "Backup directory name/type conflict" }
            require(children(parent).single { it.name == name }.id == doc.id) { "Ambiguous backup directory" }
            parent = doc.id
        }
    }
    override suspend fun delete(key: String) { resolve(key)?.let { require(!it.directory); access.delete(it.id) } }
}
