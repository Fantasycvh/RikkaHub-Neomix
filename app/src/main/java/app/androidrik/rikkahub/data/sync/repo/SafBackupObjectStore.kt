package app.androidrik.rikkahub.data.sync.repo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal data class SafDocument(
    val id: String,
    val name: String,
    val directory: Boolean,
    val bytes: Long?,
    val lastModified: Long? = null,
)

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
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)) { "Cannot list backup folder" }.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)
            val modified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (cursor.moveToNext()) add(SafDocument(cursor.getString(id), cursor.getString(name),
                    cursor.getString(mime) == Document.MIME_TYPE_DIR, if (cursor.isNull(size)) null else cursor.getLong(size),
                    if (modified < 0 || cursor.isNull(modified)) null else cursor.getLong(modified)))
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
    /**
     * SAF 只能逐层列目录：默认实现每个对象目录都要用 [resolve] 重走一遍路径（每层多出三次查询），
     * 对象上百时整次 GC 会慢到分钟级。这里按目录 id 逐层列举，并且只在同层兄弟目录之间并行查询，
     * 不做递归并行（递归各自持有许可会互相等待），[LIST_PARALLEL] 个并发已足够吃掉 provider 延迟。
     */
    override suspend fun listAll(prefix: String): List<BackupStoredObject> {
        val root = resolve(prefix) ?: return emptyList()
        require(root.directory) { "Backup path is not a directory" }
        val files = ArrayList<BackupStoredObject>()
        var level = listOf(root to prefix)
        while (level.isNotEmpty()) {
            val next = ArrayList<Pair<SafDocument, String>>()
            listDirectories(level).forEach { entries ->
                entries.forEach { (doc, key) ->
                    if (doc.directory) next += doc to key
                    else files += BackupStoredObject(key, doc.bytes, false, doc.lastModified?.let(java.time.Instant::ofEpochMilli))
                }
            }
            level = next
        }
        return files
    }

    private suspend fun listDirectories(level: List<Pair<SafDocument, String>>): List<List<Pair<SafDocument, String>>> {
        fun entriesOf(parent: SafDocument, key: String): List<Pair<SafDocument, String>> =
            children(parent.id).map { it to (if (key.isEmpty()) it.name else "$key/${it.name}") }
        return if (level.size <= 1) {
            level.map { (doc, key) -> entriesOf(doc, key) }
        } else {
            coroutineScope {
                val permits = Semaphore(minOf(LIST_PARALLEL, level.size))
                level.map { (doc, key) -> async(Dispatchers.IO) { permits.withPermit { entriesOf(doc, key) } } }.awaitAll()
            }
        }
    }

    override suspend fun listChildren(prefix: String): List<BackupStoredObject> {
        val parent = resolve(prefix) ?: return emptyList()
        require(parent.directory)
        return children(parent.id).map {
            BackupStoredObject((if (prefix.isEmpty()) "" else "$prefix/") + it.name, it.bytes, it.directory,
                it.lastModified?.let(java.time.Instant::ofEpochMilli))
        }
    }
    override suspend fun stat(key: String) = resolve(key)?.let {
        BackupStoredObject(key, it.bytes, it.directory, it.lastModified?.let(java.time.Instant::ofEpochMilli))
    }
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
    override suspend fun putOverwrite(key: String, source: File, onBytes: (Long) -> Unit) {
        validateKey(key)
        val parent = requireNotNull(resolve(key.substringBeforeLast('/', ""))) { "Missing backup directory" }
        val name = key.substringAfterLast('/')
        val existing = children(parent.id).singleOrNull { it.name == name }
        if (existing == null) {
            put(key, source, onBytes)
            return
        }
        require(!existing.directory) { "Backup file is a directory" }
        val coroutine = currentCoroutineContext()
        // SAF 的 "wt" 模式即截断重写：租约本来就要换内容，不做内容比对。
        access.write(existing.id).use { output -> source.inputStream().use { input ->
            copyBounded(input, output, source.length()) { coroutine.ensureActive(); onBytes(it) }
        } }
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

    private companion object {
        /** 逐层列举时同一层的并发查询数；provider 侧本就是 IPC，串行跑会把延迟叠加成分钟级。 */
        const val LIST_PARALLEL = 8
    }
}
