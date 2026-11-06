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
        // 只回查新建的这一个文档（一次查询），不再把父目录整层再列一遍：
        // 目录列举是 provider 往返的主要成本，而 create 只需要确认 provider 没有改名/改类型。
        val query = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return requireNotNull(resolver.query(query, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)) {
            "Cannot read created backup entry"
        }.use { cursor ->
            require(cursor.moveToFirst()) { "Created backup entry disappeared: $id" }
            fun text(column: String): String = cursor.getString(cursor.getColumnIndexOrThrow(column))
            fun optionalLong(column: String): Long? {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
            SafDocument(
                id = id,
                name = text(Document.COLUMN_DISPLAY_NAME),
                directory = text(Document.COLUMN_MIME_TYPE) == Document.MIME_TYPE_DIR,
                bytes = optionalLong(Document.COLUMN_SIZE),
                lastModified = optionalLong(Document.COLUMN_LAST_MODIFIED),
            )
        }
    }
    override fun read(id: String) = requireNotNull(resolver.openInputStream(uri(id))) { "Cannot read backup file" }
    override fun write(id: String) = requireNotNull(resolver.openOutputStream(uri(id), "wt")) { "Cannot write backup file" }
    override fun delete(id: String) { check(DocumentsContract.deleteDocument(resolver, uri(id))) { "Cannot delete snapshot file" } }
}

internal class SafBackupObjectStore(private val access: BackupDocumentAccess, override val destinationId: String) : BackupObjectStore {
    /** debug 计数装饰器（若该 store 建立在计数 access 上），让服务层记录阶段日志时不受其它操作干扰。 */
    internal val countingAccess: CountingBackupDocumentAccess? get() = access as? CountingBackupDocumentAccess
    /**
     * 一次操作内的目录列举缓存：`dirId -> 子文档`。
     *
     * SAF 每个 `children()` 都是一次 provider IPC（ExternalStorageProvider 还要走 FUSE），
     * 而按路径解析、逐对象存在性检查、提交前复核都会反复访问同一批目录；对象上千时这部分
     * 往返会把一次备份拖到分钟级。store 实例只在一次操作内存活（`IncrementalBackupService`
     * 每次操作新建），因此缓存不需要（也不应该）跨操作失效；写入/删除时同步修补。
     */
    private val cacheLock = Any()
    private val dirCache = HashMap<String, List<SafDocument>>()

    private fun children(id: String): List<SafDocument> =
        synchronized(cacheLock) { dirCache[id] } ?: fetchChildren(id).also {
            synchronized(cacheLock) { dirCache[id] = it }
        }

    /** 不读缓存的目录列举（发布前复核用，保证能看到其它设备的并发删除）。 */
    private fun freshChildren(id: String): List<SafDocument> = validate(access.children(id))

    private fun fetchChildren(id: String): List<SafDocument> = validate(access.children(id))

    private fun validate(list: List<SafDocument>): List<SafDocument> = list.also {
        require(it.map { doc -> doc.name }.distinct().size == it.size) { "Ambiguous duplicate names in backup folder" }
    }

    private fun remember(parentId: String, doc: SafDocument) {
        synchronized(cacheLock) {
            val current = dirCache[parentId] ?: return
            dirCache[parentId] = current.filterNot { it.name == doc.name } + doc
        }
    }

    private fun forget(parentId: String, docId: String) {
        synchronized(cacheLock) {
            val current = dirCache[parentId] ?: return
            dirCache[parentId] = current.filterNot { it.id == docId }
        }
    }

    private fun resolve(key: String): SafDocument? = resolvePath(key) { children(it) }

    private fun resolveFresh(key: String): SafDocument? = resolvePath(key) { freshChildren(it) }

    private inline fun resolvePath(key: String, childrenOf: (String) -> List<SafDocument>): SafDocument? {
        if (key.isEmpty()) return SafDocument(access.rootId, "", true, null)
        validateKey(key)
        var current = SafDocument(access.rootId, "", true, null)
        key.split('/').forEach { part ->
            require(current.directory) { "Backup path is not a directory" }
            current = childrenOf(current.id).singleOrNull { it.name == part } ?: return null
        }
        return current
    }

    private fun stored(key: String, doc: SafDocument) = BackupStoredObject(
        key, doc.bytes, doc.directory, doc.lastModified?.let(java.time.Instant::ofEpochMilli),
    )

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
            stored((if (prefix.isEmpty()) "" else "$prefix/") + it.name, it)
        }
    }

    /** 发布前复核：不看缓存里的叶子列表，重新向 provider 列举，能看到其它设备的并发删除。 */
    override suspend fun missingKeysFresh(keys: Collection<String>): List<String> {
        if (keys.isEmpty()) return emptyList()
        val byParent = keys.groupBy { it.substringBeforeLast('/', "") }
        val missing = java.util.Collections.synchronizedList(mutableListOf<String>())
        coroutineScope {
            byParent.entries.map { (parent, groupKeys) ->
                async(Dispatchers.IO) {
                    // 父目录用本次操作内的缓存解析（plan 已列过 v3/objects 及各分片目录）；
                    // 只有叶子列表回源，避免逐键重新逐层解析路径。目录是内容寻址结构、不会被并发改名；
                    // 万一父目录已消失则退化为 fresh 解析，仍会判定为缺失。
                    val dir = resolve(parent) ?: resolveFresh(parent)
                    val names = if (dir == null || !dir.directory) {
                        emptySet()
                    } else {
                        freshChildren(dir.id).asSequence().filter { !it.directory }.map { it.name }.toSet()
                    }
                    val base = if (parent.isEmpty()) "" else "$parent/"
                    groupKeys.forEach { key -> if (key.removePrefix(base) !in names) missing += key }
                }
            }.awaitAll()
        }
        return missing.toList()
    }
    override suspend fun stat(key: String) = resolve(key)?.let { stored(key, it) }

    override suspend fun statFresh(key: String) = resolveFresh(key)?.let { stored(key, it) }
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
            // provider 若把文件改名（同名冲突/异常卷），这里立刻失败：绝不把内容写进「不是目标键」的文档。
            require(created.name == name && !created.directory) { "Provider changed the backup filename" }
            access.write(created.id).use { output -> source.inputStream().use { input ->
                copyBounded(input, output, source.length()) { coroutine.ensureActive(); onBytes(it) }
            } }
            remember(parent.id, created)
        } catch (failure: Throwable) {
            // Only this newly-created document is ours to remove; never delete an existing object.
            synchronized(cacheLock) { dirCache.remove(parent.id) }
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
        // 覆盖写后大小变了；缓存里的旧尺寸只用于读预算，丢掉让下一次 fresh 列举纠正。
        synchronized(cacheLock) { dirCache.remove(parent.id) }
    }

    override suspend fun ensureDir(prefix: String) {
        validateKey(prefix)
        var parent = SafDocument(access.rootId, "", true, null)
        prefix.split('/').forEach { name ->
            require(parent.directory) { "Backup path is not a directory" }
            val existing = children(parent.id).singleOrNull { it.name == name }
            val doc = existing ?: access.create(parent.id, name, true).also { remember(parent.id, it) }
            require(doc.name == name && doc.directory) { "Backup directory name/type conflict" }
            parent = doc
        }
    }
    override suspend fun delete(key: String) {
        val doc = resolve(key) ?: return
        require(!doc.directory) { "Backup path is a directory" }
        val parentId = if (key.contains('/')) resolve(key.substringBeforeLast('/'))?.id else access.rootId
        access.delete(doc.id)
        if (parentId != null) forget(parentId, doc.id)
    }

    private companion object {
        /** 逐层列举时同一层的并发查询数；provider 侧本就是 IPC，串行跑会把延迟叠加成分钟级。 */
        const val LIST_PARALLEL = 8
    }
}

/**
 * SAF/DocumentsProvider 调用的计数快照（仅 debug 诊断用）。
 *
 * 「逐对象写入慢」只能靠数往返次数定位：同一操作里 `children`/`create`/打开流的次数、
 * 读写字节数、以及各阶段耗时。这里只做无锁累加，运营时零分支外开销可忽略。
 */
internal data class SafAccessStats(
    val children: Long,
    val create: Long,
    val openRead: Long,
    val openWrite: Long,
    val delete: Long,
    val readBytes: Long,
    val writeBytes: Long,
) {
    fun summary(): String =
        "children=$children create=$create openRead=$openRead openWrite=$openWrite delete=$delete " +
            "readBytes=$readBytes writeBytes=$writeBytes"
}

/** 给 [BackupDocumentAccess] 加计数的装饰器；只包在 debug 构建的本地仓库访问上。 */
internal class CountingBackupDocumentAccess(private val delegate: BackupDocumentAccess) : BackupDocumentAccess {
    private val childrenCount = java.util.concurrent.atomic.AtomicLong()
    private val createCount = java.util.concurrent.atomic.AtomicLong()
    private val openReadCount = java.util.concurrent.atomic.AtomicLong()
    private val openWriteCount = java.util.concurrent.atomic.AtomicLong()
    private val deleteCount = java.util.concurrent.atomic.AtomicLong()
    private val readBytes = java.util.concurrent.atomic.AtomicLong()
    private val writeBytes = java.util.concurrent.atomic.AtomicLong()

    override val rootId get() = delegate.rootId

    fun stats(): SafAccessStats = SafAccessStats(
        children = childrenCount.get(),
        create = createCount.get(),
        openRead = openReadCount.get(),
        openWrite = openWriteCount.get(),
        delete = deleteCount.get(),
        readBytes = readBytes.get(),
        writeBytes = writeBytes.get(),
    )

    /** commit 复用 plan 创建的 store；重置一次计数，让该阶段日志只包含 commit 自身开销。 */
    fun reset() {
        childrenCount.set(0)
        createCount.set(0)
        openReadCount.set(0)
        openWriteCount.set(0)
        deleteCount.set(0)
        readBytes.set(0)
        writeBytes.set(0)
    }

    override fun children(parent: String): List<SafDocument> =
        delegate.children(parent).also { childrenCount.incrementAndGet() }

    override fun create(parent: String, name: String, directory: Boolean): SafDocument =
        delegate.create(parent, name, directory).also { createCount.incrementAndGet() }

    override fun read(id: String): InputStream =
        CountingInputStream(delegate.read(id), readBytes).also { openReadCount.incrementAndGet() }

    override fun write(id: String): OutputStream =
        CountingOutputStream(delegate.write(id), writeBytes).also { openWriteCount.incrementAndGet() }

    override fun delete(id: String) {
        deleteCount.incrementAndGet()
        delegate.delete(id)
    }
}

private class CountingInputStream(private val delegate: InputStream, private val counter: java.util.concurrent.atomic.AtomicLong) :
    java.io.FilterInputStream(delegate) {
    override fun read(): Int = super.read().also { if (it >= 0) counter.incrementAndGet() }
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) counter.addAndGet(it.toLong()) }
}

private class CountingOutputStream(private val delegate: OutputStream, private val counter: java.util.concurrent.atomic.AtomicLong) :
    java.io.FilterOutputStream(delegate) {
    override fun write(b: Int) {
        delegate.write(b)
        counter.incrementAndGet()
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        counter.addAndGet(len.toLong())
    }
}
