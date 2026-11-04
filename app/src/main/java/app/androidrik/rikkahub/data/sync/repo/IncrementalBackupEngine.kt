package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.BackupPhase
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.data.sync.classifyBackupEntry
import app.androidrik.rikkahub.data.sync.addFileToZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipOutputStream

internal data class SnapshotInput(val path: String, val file: File)

/** 已由平台层从一致性 DB 快照中导出的逻辑对象载荷（JSONL，见 [DbObjectPayloadWriter]）。 */
internal data class SnapshotDbObjectInput(val objectKey: String, val file: File)

/** 恢复端下载并解码后的对象载荷；由平台层合并进当前库副本。 */
internal data class DecodedDbObject(val objectKey: String, val raw: File)

/** 按保留策略清理旧快照的结果（只删清单/完成标记，对象由 [IncrementalBackupEngine.garbageCollect] 回收）。 */
internal data class SnapshotPruneResult(val deletedSnapshots: Int, val keptSnapshots: Int)

/** 未引用对象回收结果。 */
internal data class ObjectGcResult(val deletedObjects: Int, val freedBytes: Long, val keptObjects: Int)

internal class BackupSnapshotPlan(
    val store: BackupObjectStore,
    val snapshot: BackupSnapshotManifest,
    val directory: File,
    val uploads: Map<String, File>,
    val reusedBytes: Long,
    val expiresAt: Instant = Instant.now().plusSeconds(1800),
) : Closeable {
    val uploadBytes get() = uploads.values.sumOf(File::length)
    override fun close() { directory.deleteRecursively() }
}

/** Protocol engine; Android file capture, locking and the transport live outside this class. */
internal class IncrementalBackupEngine(
    private val cache: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val progress: (BackupProgress) -> Unit = {},
) {
    private fun taskDir(): File {
        check(cache.isDirectory || cache.mkdirs()) { "Cannot create backup staging directory" }
        return File(cache, UUID.randomUUID().toString()).also { check(it.mkdir()) }
    }

    suspend fun inspectRepository(store: BackupObjectStore): BackupRepositoryInfo? {
        val metadata = store.stat("repo.json") ?: return null
        require(!metadata.directory) { "Invalid repository marker" }
        val dir = taskDir()
        return try {
            val file = File(dir, "repo.json")
            store.get("repo.json", file, 4096)
            json.decodeFromString<BackupRepositoryInfo>(file.readText()).also {
                require(it.version == REPO_VERSION && validUuid(it.repoId)) { "Unsupported or damaged repository" }
            }
        } finally { dir.deleteRecursively() }
    }

    private suspend fun repository(store: BackupObjectStore): BackupRepositoryInfo {
        inspectRepository(store)?.let { return it }
        require(store.allowNonEmptyInitialization || store.listChildren("").isEmpty()) {
            "Select an empty folder or an existing backup repository"
        }
        // Planning may create local staging files, but never changes the destination.
        return BackupRepositoryInfo(repoId = UUID.randomUUID().toString(), createdAt = Instant.now().toString())
    }

    suspend fun list(store: BackupObjectStore): List<BackupSnapshotManifest> {
        val repo = inspectRepository(store) ?: return emptyList()
        val dir = taskDir()
        return try {
            store.listChildren("v3/snapshots").filter { !it.directory && it.key.endsWith(".complete.json") }.mapNotNull {
                currentCoroutineContext().ensureActive()
                val id = it.key.substringAfterLast('/').removeSuffix(".complete.json")
                if (!validUuid(id)) null else try {
                    readSnapshot(store, id, repo.repoId, dir)
                } catch (_: IllegalArgumentException) { null }
                catch (_: java.util.zip.ZipException) { null }
                catch (_: java.io.EOFException) { null }
            }.sortedByDescending { it.createdAt }
        } finally { dir.deleteRecursively() }
    }

    private suspend fun readSnapshot(store: BackupObjectStore, id: String, repoId: String, dir: File): BackupSnapshotManifest {
        val markerFile = File(dir, "complete.json")
        store.get("v3/snapshots/$id.complete.json", markerFile, 4096)
        val marker = json.decodeFromString<SnapshotCompletion>(markerFile.readText())
        require(marker.version == REPO_VERSION && marker.snapshotId == id && marker.repoId == repoId &&
            validHash(marker.manifestSha256) && marker.manifestBytes in 1..METADATA_LIMIT) { "Invalid completion marker" }
        val manifest = File(dir, "manifest.json.gz")
        store.get("v3/snapshots/$id.json.gz", manifest, marker.manifestBytes)
        require(manifest.length() == marker.manifestBytes && fileSha256(manifest) == marker.manifestSha256) { "Snapshot checksum mismatch" }
        val raw = File(dir, "manifest.json")
        GZIPInputStream(manifest.inputStream()).use { input -> raw.outputStream().use { copyBounded(input, it, METADATA_LIMIT) } }
        return json.decodeFromString<BackupSnapshotManifest>(raw.readText()).also {
            validateSnapshot(it)
            require(it.snapshotId == id && it.repoId == repoId) { "Snapshot identity mismatch" }
        }
    }

    /**
     * 输入在返回前已物化为任务目录中的不可变文件；不做「大小/时间戳相同」之类的哈希捷径。
     *
     * @param databaseObjects P2：从一致性 DB 快照导出的逻辑对象。传了对象就不再接受 `rikka_hub.db`
     *   文件输入（快照格式 v2）。`null`/空表示沿用 v1 整库文件（仅测试与旧调用方）。
     */
    suspend fun plan(
        store: BackupObjectStore,
        inputs: List<SnapshotInput>,
        items: List<BackupItem>,
        databaseState: SnapshotDatabaseState,
        deviceId: String,
        dbVersion: Int,
        appVersionCode: String,
        databaseObjects: List<SnapshotDbObjectInput> = emptyList(),
    ): BackupSnapshotPlan {
        val dir = taskDir()
        try {
            val repo = repository(store)
            val trusted = list(store).firstOrNull { it.deviceId == deviceId }?.objects?.associateBy { it.sha256 }.orEmpty()
            val usesLogicalDatabase = databaseObjects.isNotEmpty()
            require(!usesLogicalDatabase || inputs.none { it.path == "rikka_hub.db" }) {
                "Database must be captured once, either as a file or as logical objects"
            }
            require(!usesLogicalDatabase || databaseState == SnapshotDatabaseState.PRESENT) {
                "Logical database objects require a present database"
            }
            require(inputs.size <= ENTRY_LIMIT && databaseObjects.size <= ENTRY_LIMIT) { "Too many files" }
            val plannedRaw = inputs.sumOf { it.file.length() } + databaseObjects.sumOf { it.file.length() }
            require(plannedRaw <= BackupManifest.MAX_TOTAL_BYTES) { "Backup exceeds restore size limit" }
            check(cache.usableSpace >= Math.addExact(Math.multiplyExact(plannedRaw, 2), RESERVE_BYTES)) { "Not enough cache space to capture backup" }
            val entries = ArrayList<SnapshotFileEntry>()
            val dbEntries = ArrayList<SnapshotDbObject>()
            val uploads = linkedMapOf<String, File>()
            var captured = 0L
            var totalRaw = 0L
            var reused = 0L
            val coroutine = currentCoroutineContext()
            // 1) 先原样收集待处理对象：普通文件保持「复制到任务目录 + 复核大小/mtime」的既有语义，
            //    DB 逻辑对象是本次捕获刚写出的不可变暂存文件，直接原地哈希/压缩，省掉 55MB 级拷贝。
            data class WorkItem(
                val raw: File,
                val size: Long,
                val logicalKey: String,
                val databaseObject: Boolean,
            )
            val work = ArrayList<WorkItem>(inputs.size + databaseObjects.size)
            inputs.forEachIndexed { index, source ->
                coroutine.ensureActive()
                val raw = File(dir, "raw-$index")
                val originalSize = source.file.length()
                val originalTime = source.file.lastModified()
                val size = source.file.inputStream().use { input -> raw.outputStream().use { out ->
                    copyBounded(input, out, BackupManifest.MAX_ENTRY_BYTES) { count ->
                        coroutine.ensureActive()
                        captured += count
                        progress(BackupProgress(BackupPhase.SCANNING, captured, plannedRaw))
                    }
                } }
                require(source.file.isFile && originalSize == size && source.file.length() == originalSize &&
                    source.file.lastModified() == originalTime) { "Source changed while being captured; retry backup: ${source.path}" }
                work += WorkItem(raw, size, source.path, databaseObject = false)
            }
            databaseObjects.forEach { source ->
                coroutine.ensureActive()
                validateDbObjectKey(source.objectKey)
                require(source.file.isFile) { "Database object disappeared: ${source.objectKey}" }
                val size = source.file.length()
                require(size > 0) { "Database object is empty: ${source.objectKey}" }
                work += WorkItem(source.file, size, source.objectKey, databaseObject = true)
            }

            // 2) 哈希、去重判定与压缩都可以按对象并行：每个对象独立、输出确定；远程 stat 是网络往返，
            //    串行 290 次 HEAD 在真机上就是十几秒。
            val hashes = parallelMap(work) { fileSha256(it.raw) }
            // 内容寻址 key 决定去重与上传目标（同内容对象共用一份）；逻辑 key 只用于清单/恢复映射。
            val refs: List<SnapshotObjectRef> = work.mapIndexed { index, item ->
                if (item.databaseObject) SnapshotDbObject(item.logicalKey, hashes[index], item.size)
                else SnapshotFileEntry(item.logicalKey, hashes[index], item.size)
            }
            val firstIndexByKey = LinkedHashMap<String, Int>()
            work.forEachIndexed { index, _ -> firstIndexByKey.putIfAbsent(refs[index].key, index) }
            val actionByKey = HashMap<String, ObjectAction>()
            parallelMap(firstIndexByKey.keys.toList()) { key ->
                val index = firstIndexByKey.getValue(key)
                val item = work[index]
                val ref = refs[index]
                val existing = store.stat(key)
                val action = when {
                    existing != null && trusted[ref.sha256]?.size == item.size -> ObjectAction.Reuse
                    existing != null -> {
                        // Uncommitted objects can be partial writes left by a failed attempt.
                        require(!existing.directory) { "Object is a directory" }
                        verifyObject(store, ref, dir)
                        ObjectAction.Reuse
                    }
                    else -> {
                        val encoded = File(dir, "object-$index-${ref.sha256.take(12)}")
                        SnapshotObjectCodec.encode(item.raw, encoded) { coroutine.ensureActive() }
                        ObjectAction.Upload(encoded)
                    }
                }
                synchronized(actionByKey) { actionByKey[key] = action }
            }
            // 3) 组装清单（保持输入顺序）并统计复用/上传。
            work.forEachIndexed { index, item ->
                val ref = refs[index]
                val key = ref.key
                totalRaw = Math.addExact(totalRaw, item.size)
                require(totalRaw <= BackupManifest.MAX_TOTAL_BYTES) { "Backup grew beyond the size limit" }
                when (ref) {
                    is SnapshotDbObject -> dbEntries += ref
                    is SnapshotFileEntry -> entries += ref
                }
                if (firstIndexByKey.getValue(key) == index) {
                    when (val action = actionByKey.getValue(key)) {
                        ObjectAction.Reuse -> reused += item.size
                        is ObjectAction.Upload -> uploads[key] = action.file
                    }
                } else {
                    reused += item.size
                }
            }
            val formatVersion = if (databaseObjects.isEmpty() && inputs.none { it.path == "rikka_hub.db" }) {
                SNAPSHOT_FORMAT_VERSION
            } else if (usesLogicalDatabase) SNAPSHOT_FORMAT_VERSION else SNAPSHOT_FORMAT_V1
            val snapshot = BackupSnapshotManifest(
                formatVersion = formatVersion,
                snapshotId = UUID.randomUUID().toString(), repoId = repo.repoId, createdAt = Instant.now().toString(),
                deviceId = deviceId, appVersionCode = appVersionCode, dbVersion = dbVersion,
                items = items.distinct(), databaseState = databaseState, files = entries, dbObjects = dbEntries,
            )
            validateSnapshot(snapshot)
            return BackupSnapshotPlan(store, snapshot, dir, uploads, reused)
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    suspend fun commit(plan: BackupSnapshotPlan): BackupSnapshotManifest {
        check(plan.directory.isDirectory && Instant.now().isBefore(plan.expiresAt)) { "Backup plan expired; scan again" }
        val store = plan.store
        val repository = inspectRepository(store)
        if (repository == null) {
            require(store.allowNonEmptyInitialization || store.listChildren("").isEmpty()) { "Backup folder is no longer empty" }
            val info = BackupRepositoryInfo(repoId = plan.snapshot.repoId, createdAt = plan.snapshot.createdAt)
            val infoFile = File(plan.directory, "repo-init.json").apply { writeText(json.encodeToString(info)) }
            store.put("repo.json", infoFile)
            require(inspectRepository(store) == info) { "Repository initialization was not confirmed" }
        } else require(repository.repoId == plan.snapshot.repoId) { "Backup destination changed" }
        val coroutine = currentCoroutineContext()
        val references = plan.snapshot.objects.associateBy { it.key }
        val uploaded = java.util.concurrent.atomic.AtomicLong()
        // 目录先顺序建好（并发创建同一父目录在 SAF 上会互相干扰），对象本身再并行上传。
        plan.uploads.keys.map { it.substringBeforeLast('/') }.distinct()
            .forEach { store.ensureDir(it) }
        parallelMap(plan.uploads.entries.toList()) { (key, file) ->
            val entry = requireNotNull(references[key]) { "Uploaded object is not referenced by the snapshot" }
            store.put(key, file) { n ->
                coroutine.ensureActive()
                progress(BackupProgress(BackupPhase.UPLOADING, uploaded.addAndGet(n), plan.uploadBytes))
            }
            // 首备时把每个对象整份下载回来校验，等于再传一遍（实测 7.7s 里约 6s 是这次回读）。
            // 改为校验**本地编码文件**可解码且 SHA 匹配 + 发布前复检远端存在性/类型；
            // 传输层（TLS/S3/SAF）保证写入成功即字节完整，恢复端仍逐对象校验 SHA-256（设计稿 R8），
            // 最终一致性不变。失败重试时仍走 verifyObject 的完整回读路径。
            verifyLocalEncodedObject(entry, file, plan.directory)
        }
        // Recheck reused references immediately before publication; missing objects never produce a snapshot.
        parallelMap(plan.snapshot.objects.distinctBy { it.key }) { entry ->
            require(store.stat(entry.key)?.directory == false) { "Backup object disappeared; scan again" }
        }
        progress(BackupProgress(BackupPhase.COMMITTING, 0, null))
        val manifest = File(plan.directory, "manifest.json.gz")
        val text = json.encodeToString(plan.snapshot).toByteArray(Charsets.UTF_8)
        require(text.size <= METADATA_LIMIT)
        GZIPOutputStream(manifest.outputStream()).use { it.write(text) }
        store.ensureDir("v3/snapshots")
        store.put(plan.snapshot.manifestKey, manifest)
        val readback = File(plan.directory, "manifest-check")
        store.get(plan.snapshot.manifestKey, readback, METADATA_LIMIT)
        require(fileSha256(readback) == fileSha256(manifest)) { "Manifest write failed verification" }
        val completion = SnapshotCompletion(snapshotId = plan.snapshot.snapshotId, repoId = plan.snapshot.repoId,
            manifestSha256 = fileSha256(manifest), manifestBytes = manifest.length())
        val marker = File(plan.directory, "complete.json").apply { writeText(json.encodeToString(completion)) }
        try {
            store.put(plan.snapshot.completionKey, marker)
        } catch (failure: Exception) {
            coroutine.ensureActive()
            // A lost response may follow a successful write. Confirm using the same snapshot id.
            try { readSnapshot(store, plan.snapshot.snapshotId, plan.snapshot.repoId, plan.directory) }
            catch (_: Exception) { throw failure }
        }
        return readSnapshot(store, plan.snapshot.snapshotId, plan.snapshot.repoId, plan.directory)
    }

    /** 校验本地编码文件（解码 + SHA），不做远端回读；上传侧完整回读仍保留在 [verifyObject]。 */
    private fun verifyLocalEncodedObject(entry: SnapshotObjectRef, encoded: File, dir: File) {
        val raw = File(dir, "verify-local-${entry.sha256.take(16)}-${System.nanoTime()}")
        try {
            SnapshotObjectCodec.decode(encoded, raw, entry)
        } finally {
            raw.delete()
        }
    }

    private suspend fun verifyObject(store: BackupObjectStore, entry: SnapshotObjectRef, dir: File) {
        val encoded = File(dir, "verify-encoded-${entry.sha256.take(16)}-${System.nanoTime()}")
        val raw = File(dir, "verify-raw-${entry.sha256.take(16)}-${System.nanoTime()}")
        try {
            store.get(entry.key, encoded, encodedLimit(entry))
            val coroutine = currentCoroutineContext()
            SnapshotObjectCodec.decode(encoded, raw, entry) { coroutine.ensureActive() }
        } finally { encoded.delete(); raw.delete() }
    }

    /**
     * 把快照重新组装成既有 v2 ZIP（settings + 文件 + `rikka_hub.db` + manifest），
     * 恢复流程继续复用 [BackupArchiveEngine.restoreFromBackupFile]，P2 只改变「数据库从哪来」。
     *
     * @param mergeDatabase v2 快照必备：把解码后的逻辑对象合并进**当前库副本**并返回可恢复的 DB 文件。
     *   P2 首版限定同 schema；合并实现见 `DatabaseObjectMerge`（平台层，JVM 测试可注入 fake）。
     */
    suspend fun assemble(
        store: BackupObjectStore,
        selected: BackupSnapshotManifest,
        maxDbVersion: Int,
        mergeDatabase: (suspend (objects: List<DecodedDbObject>, dir: File) -> File?)? = null,
    ): File {
        val dir = taskDir()
        try {
            val repo = requireNotNull(inspectRepository(store)) { "Missing repository" }
            val snapshot = readSnapshot(store, selected.snapshotId, repo.repoId, dir)
            require(snapshot == selected) { "Snapshot changed; refresh the list" }
            when (snapshot.formatVersion) {
                SNAPSHOT_FORMAT_V1 -> require(snapshot.dbVersion <= maxDbVersion) { "Database version is newer than this app" }
                // v2 跨 schema：旧快照按当前 schema 合并（列交集 + 新增列默认值 + 已删表忽略），
                // 由 DatabaseObjectMerge 负责语义兜底；**更新版本**的快照仍然拒绝。
                else -> require(snapshot.dbVersion <= maxDbVersion) {
                    "Snapshot database is from a newer app version (${snapshot.dbVersion} > $maxDbVersion); update the app first"
                }
            }
            // Objects + decoded file + output ZIP + restore DB + safety backup, conservatively budgeted.
            val needed = Math.addExact(Math.multiplyExact(snapshot.logicalBytes, 5), RESERVE_BYTES)
            check(cache.usableSpace >= needed) { "Not enough internal space for snapshot restore (need $needed bytes)" }
            val zip = File(dir, "backup_snapshot.zip")
            val coroutine = currentCoroutineContext()
            var processed = 0L
            val decodedFiles = java.util.concurrent.ConcurrentLinkedQueue<File>()
            // DB 对象与文件对象是连续两段下载：共用同一个计数，避免 DOWNLOAD 阶段进度回跳。
            val downloaded = java.util.concurrent.atomic.AtomicLong()
            var mergedDatabase: File? = null
            try {
                if (snapshot.formatVersion == SNAPSHOT_FORMAT_V2 && snapshot.databaseState == SnapshotDatabaseState.PRESENT) {
                    val merge = requireNotNull(mergeDatabase) { "This build cannot restore logical database snapshots" }
                    // 对象相互独立：并行下载 + 解码；本地仓库的逐文件 SAF 查询和远程仓库的往返都在这里省掉。
                    val decodedObjects = parallelMap(snapshot.dbObjects.withIndex().toList()) { (index, entry) ->
                        coroutine.ensureActive()
                        val encoded = File(dir, "db-encoded-$index")
                        val raw = File(dir, "db-payload-$index")
                        decodedFiles += raw
                        store.get(entry.key, encoded, encodedLimit(entry)) { n ->
                            coroutine.ensureActive()
                            progress(BackupProgress(BackupPhase.DOWNLOADING, downloaded.addAndGet(n), snapshot.logicalBytes))
                        }
                        SnapshotObjectCodec.decode(encoded, raw, entry) { coroutine.ensureActive() }
                        encoded.delete()
                        DecodedDbObject(entry.objectKey, raw)
                    }
                    mergedDatabase = requireNotNull(merge(decodedObjects, dir)) { "Database merge produced no database" }.also {
                        require(it.isFile && it.length() > 0) { "Database merge produced no database" }
                    }
                }
                // 文件同样先并行下载+解码，再单线程写 ZIP（ZipOutputStream 不是并发安全的）。
                val decodedFilesList = parallelMap(snapshot.files.withIndex().toList()) { (index, entry) ->
                    coroutine.ensureActive()
                    val encoded = File(dir, "file-encoded-$index")
                    val raw = File(dir, "file-raw-$index")
                    decodedFiles += raw
                    store.get(entry.key, encoded, encodedLimit(entry)) { n ->
                        coroutine.ensureActive()
                        progress(BackupProgress(BackupPhase.DOWNLOADING, downloaded.addAndGet(n), snapshot.logicalBytes))
                    }
                    SnapshotObjectCodec.decode(encoded, raw, entry) { coroutine.ensureActive() }
                    encoded.delete()
                    entry to raw
                }
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    decodedFilesList.forEach { (entry, raw) ->
                        coroutine.ensureActive()
                        addFileToZip(out, raw, entry.path, { coroutine.ensureActive() })
                        processed += entry.size
                        progress(BackupProgress(BackupPhase.EXTRACTING, processed, snapshot.logicalBytes))
                    }
                    val database = mergedDatabase
                    // 换库恢复会把 DB 立即解压回来：这里 STORED 省掉一次 119MB 级 DEFLATE（以及恢复端
                    // 对应的 inflate），代价只是临时 ZIP 大 ~30MB（缓存预算 5x 逻辑字节已覆盖）。
                    if (database != null) addFileToZip(out, database, "rikka_hub.db", { coroutine.ensureActive() }, forceStored = true)
                    val settings = snapshot.files.single { it.path == "settings.json" }
                    val fileDatabase = snapshot.files.singleOrNull { it.path == "rikka_hub.db" }
                    val sections = snapshot.files.groupBy { requireNotNull(classifyBackupEntry(it.path)) }.mapValues { (_, files) ->
                        BackupSectionStats(files.size, files.sumOf { it.size })
                    }.toMutableMap()
                    if (database != null) sections[BackupManifestSection.DATABASE] = BackupSectionStats(1, database.length())
                    val manifest = BackupManifest(createdAt = snapshot.createdAt, items = snapshot.items,
                        sections = sections, settingsSha256 = settings.sha256, settingsBytes = settings.size,
                        databaseSha256 = fileDatabase?.sha256 ?: database?.let { fileSha256(it) },
                        databaseBytes = fileDatabase?.size ?: database?.length())
                    out.putNextEntry(java.util.zip.ZipEntry(BackupManifest.ZIP_ENTRY))
                    out.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8)); out.closeEntry()
                }
                return zip
            } finally {
                decodedFiles.forEach { it.delete() }
                mergedDatabase?.delete()
            }
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    suspend fun snapshotExists(store: BackupObjectStore, snapshotId: String): Boolean =
        store.stat("v3/snapshots/$snapshotId.complete.json")?.directory == false

    /**
     * 严格列举：任何一份已提交快照的标记/清单读不出来都直接失败。
     *
     * GC 不能用宽容的 [list]（坏清单会被静默跳过），否则会把坏快照仍引用的对象当垃圾删掉。
     * 删除权限（保留策略/GC）必须先过这一关，实现「失败即停止」。
     */
    suspend fun snapshotsStrict(store: BackupObjectStore): List<BackupSnapshotManifest> {
        val repo = requireNotNull(inspectRepository(store)) { "Missing repository" }
        val dir = taskDir()
        try {
            val markers = store.listChildren("v3/snapshots").filter { !it.directory && it.key.endsWith(".complete.json") }
            return markers.map { marker ->
                currentCoroutineContext().ensureActive()
                val id = marker.key.substringAfterLast('/').removeSuffix(".complete.json")
                require(validUuid(id)) { "Invalid snapshot marker: ${marker.key}" }
                readSnapshot(store, id, repo.repoId, dir)
            }.sortedByDescending { it.createdAt }
        } finally { dir.deleteRecursively() }
    }

    /**
     * 保留策略：只删**本设备**最旧的快照清单（按 createdAt 降序保留 [keep] 份；[keep] <= 0 表示不限）。
     *
     * 不删对象、不动其他设备的快照——共享对象的可达性由 [garbageCollect] 统一判断。
     */
    suspend fun pruneSnapshots(store: BackupObjectStore, deviceId: String, keep: Int): SnapshotPruneResult {
        val snapshots = snapshotsStrict(store)
        val owned = snapshots.filter { it.deviceId == deviceId }
        if (keep <= 0 || owned.size <= keep) return SnapshotPruneResult(0, owned.size)
        val stale = owned.drop(keep)
        stale.forEach { snapshot ->
            currentCoroutineContext().ensureActive()
            deleteSnapshot(store, snapshot)
        }
        return SnapshotPruneResult(stale.size, owned.size - stale.size)
    }

    /**
     * 未引用对象 GC：先从所有已提交快照（严格列举）求可达 sha256 集合，再逐个删除
     * `v3/objects/` 下不在可达集里的对象；任何列举/删除失败即停止（不静默跳过）。
     *
     * **只允许单设备仓库**：多设备时「另一台设备刚上传对象、快照还没提交」的窗口无法用
     * 仓库内信息排除，删除可能破坏它正在进行的备份。跨设备回收需要协调协议，明确不做。
     */
    suspend fun garbageCollect(store: BackupObjectStore): ObjectGcResult {
        val snapshots = snapshotsStrict(store)
        val devices = snapshots.map { it.deviceId }.distinct()
        require(devices.size <= 1) {
            "仓库包含 ${devices.size} 台设备的快照，暂不支持回收未引用对象（需跨设备协调）；可先手动删除旧快照"
        }
        val referenced = snapshots.flatMap { it.objects }.map { it.sha256 }.toHashSet()
        var deleted = 0
        var freed = 0L
        var kept = 0
        store.listAll("v3/objects").forEach { objectRef ->
            currentCoroutineContext().ensureActive()
            require(!objectRef.directory) { "Unexpected directory in object bucket: ${objectRef.key}" }
            val sha = objectRef.key.substringAfterLast('/')
            require(validHash(sha) && objectRef.key == "v3/objects/${sha.take(2)}/$sha") {
                "Unexpected object layout: ${objectRef.key}"
            }
            if (sha in referenced) {
                kept++
            } else {
                store.delete(objectRef.key)
                deleted++
                freed += objectRef.bytes ?: 0L
            }
        }
        return ObjectGcResult(deleted, freed, kept)
    }

    suspend fun deleteSnapshot(store: BackupObjectStore, snapshot: BackupSnapshotManifest) {
        require(inspectRepository(store)?.repoId == snapshot.repoId)
        validateSnapshot(snapshot)
        store.delete(snapshot.completionKey)
        store.delete(snapshot.manifestKey)
        // Shared objects are deliberately retained. Safe GC is a later phase.
    }

    private fun encodedLimit(entry: SnapshotObjectRef) = entry.size + entry.size / 100 + 1024 * 1024

    private sealed interface ObjectAction {
        data object Reuse : ObjectAction
        data class Upload(val file: File) : ObjectAction
    }

    /** 对象级并行（哈希/stat/压缩/校验独立）；并发度固定保守值，避免把低端机 IO 打满。 */
    private suspend fun <T, R> parallelMap(items: List<T>, block: suspend (T) -> R): List<R> =
        if (items.size <= 1) items.map { block(it) }
        else coroutineScope {
            val permits = Semaphore(minOf(PARALLEL_OBJECTS, items.size))
            items.map { item -> async(Dispatchers.IO) { permits.withPermit { block(item) } } }.awaitAll()
        }
}
