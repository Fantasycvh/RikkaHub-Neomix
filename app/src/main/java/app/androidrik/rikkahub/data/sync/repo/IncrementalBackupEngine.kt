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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
internal data class ObjectGcResult(
    val deletedObjects: Int,
    val freedBytes: Long,
    val keptObjects: Int,
    val deletedPacks: Int = 0,
    /** 因「最近修改/时间未知」被保守跳过的候选数（并发写入窗口保护）。 */
    val skippedRecent: Int = 0,
)

/** 仓库级 GC 租约：多设备共用同一目录时避免两台设备同时回收。 */
@Serializable
internal data class GcLease(val deviceId: String, val repoId: String, val startedAt: String)

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
        /** v3 小对象打包；测试可关闭以单独验证对象层语义。 */
        packing: Boolean = true,
    ): BackupSnapshotPlan {
        val dir = taskDir()
        try {
            val repo = repository(store)
            val trusted = list(store).firstOrNull { it.deviceId == deviceId }?.objects?.associateBy { it.sha256 }.orEmpty()
            val usesLogicalDatabase = databaseObjects.isNotEmpty()
            val legacyDatabaseFile = inputs.any { it.path == "rikka_hub.db" }
            require(!usesLogicalDatabase || !legacyDatabaseFile) {
                "Database must be captured once, either as a file or as logical objects"
            }
            require(!usesLogicalDatabase || databaseState == SnapshotDatabaseState.PRESENT) {
                "Logical database objects require a present database"
            }
            require(inputs.size <= ENTRY_LIMIT && databaseObjects.size <= ENTRY_LIMIT) { "Too many files" }
            val plannedRaw = inputs.sumOf { it.file.length() } + databaseObjects.sumOf { it.file.length() }
            require(plannedRaw <= BackupManifest.MAX_TOTAL_BYTES) { "Backup exceeds restore size limit" }
            // v3 需要同时放：原始捕获副本 + pack zip（或独立对象编码），预留 3 倍原始量。
            check(cache.usableSpace >= Math.addExact(Math.multiplyExact(plannedRaw, 3), RESERVE_BYTES)) { "Not enough cache space to capture backup" }
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

            // 2) 哈希按对象并行；每个唯一内容只判定一次处置，避免 290 次串行远端 HEAD。
            val hashes = parallelMap(work) { fileSha256(it.raw) }
            val firstIndexBySha = LinkedHashMap<String, Int>()
            work.forEachIndexed { index, _ -> firstIndexBySha.putIfAbsent(hashes[index], index) }
            val decisions: Map<String, ObjectDecision> = parallelMap(firstIndexBySha.entries.toList()) { (sha, index) ->
                val item = work[index]
                val trustedRef = trusted[sha]
                val decision = when {
                    // 沿用本设备上一份快照的引用（可能已在 pack 里）：pack/对象都是内容寻址不可变的。
                    trustedRef != null && trustedRef.size == item.size && store.stat(trustedRef.key)?.directory == false ->
                        ObjectDecision.Reuse(trustedRef.pack)
                    // 仓库里已有、但不是本设备快照引用的对象（跨设备共享/上次失败留下）：
                    // 不能只信「存在」，先完整回读校验，避免把部分写入的孤儿对象带进新快照。
                    trustedRef == null && store.stat(objectKeyFor(sha))?.directory == false -> {
                        verifyObject(store, sha, item.size, dir)
                        ObjectDecision.Reuse(null)
                    }
                    else -> ObjectDecision.Upload
                }
                sha to decision
            }.toMap()
            // 3) 新内容：小的（含 DB 逻辑对象）进内容寻址 pack，大的单独编码成对象。
            val usePacking = packing && !legacyDatabaseFile
            val packCandidates = ArrayList<Pair<String, File>>()
            firstIndexBySha.forEach { (sha, index) ->
                if (decisions.getValue(sha) !is ObjectDecision.Upload) return@forEach
                val item = work[index]
                if (usePacking && item.size < PACK_ENTRY_LIMIT) {
                    packCandidates += sha to item.raw
                } else {
                    val encoded = File(dir, "object-$index-${sha.take(12)}")
                    SnapshotObjectCodec.encode(item.raw, encoded) { coroutine.ensureActive() }
                    uploads[objectKeyFor(sha)] = encoded
                }
            }
            val packed = SnapshotPacking.write(dir, packCandidates) { coroutine.ensureActive() }
            packed.files.forEach { (key, file) -> uploads[key] = file }
            // 4) 组装清单（保持输入顺序）；pack 指针来自「复用」或本次新 pack。
            work.forEachIndexed { index, item ->
                val sha = hashes[index]
                totalRaw = Math.addExact(totalRaw, item.size)
                require(totalRaw <= BackupManifest.MAX_TOTAL_BYTES) { "Backup grew beyond the size limit" }
                val pack = when (val decision = decisions.getValue(sha)) {
                    is ObjectDecision.Reuse -> decision.pack
                    ObjectDecision.Upload -> packed.shaToPack[sha]
                }
                if (item.databaseObject) dbEntries += SnapshotDbObject(item.logicalKey, sha, item.size, pack)
                else entries += SnapshotFileEntry(item.logicalKey, sha, item.size, pack)
            }
            firstIndexBySha.forEach { (sha, index) ->
                if (decisions.getValue(sha) is ObjectDecision.Reuse) reused += work[index].size
            }
            val formatVersion = if (legacyDatabaseFile) SNAPSHOT_FORMAT_V1 else SNAPSHOT_FORMAT_VERSION
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
        ensureNoForeignGcLease(store, plan.snapshot.deviceId)
        val coroutine = currentCoroutineContext()
        val uploaded = AtomicLong()
        // 目录先顺序建好（并发创建同一父目录在 SAF 上会互相干扰），对象/pack 再并行上传。
        plan.uploads.keys.map { it.substringBeforeLast('/') }.distinct()
            .forEach { store.ensureDir(it) }
        val refsByKey = plan.snapshot.objects.groupBy { it.key }
        parallelMap(plan.uploads.entries.toList()) { (key, file) ->
            val packSha = packShaFromKey(key)
            if (packSha != null) {
                // pack 的本地完整性（整份 SHA + 每个条目 SHA）在上传前校验，不做远端回读。
                SnapshotPacking.verify(file, packSha, requireNotNull(refsByKey[key]) { "Uploaded pack is not referenced" }, plan.directory)
            }
            val entry = if (packSha == null) {
                requireNotNull(refsByKey[key]?.singleOrNull()) { "Uploaded object is not referenced by the snapshot" }
            } else null
            store.put(key, file) { n ->
                coroutine.ensureActive()
                progress(BackupProgress(BackupPhase.UPLOADING, uploaded.addAndGet(n), plan.uploadBytes))
            }
            if (entry != null) verifyLocalEncodedObject(entry, file, plan.directory)
        }
        // Recheck reused references immediately before publication; missing objects/packs never produce a snapshot.
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

    /** 本设备上一份快照没有引用、但仓库里已存在的独立对象：完整回读并校验后再复用。 */
    private suspend fun verifyObject(store: BackupObjectStore, sha256: String, size: Long, dir: File) {
        val encoded = File(dir, "verify-encoded-${sha256.take(16)}-${System.nanoTime()}")
        val raw = File(dir, "verify-raw-${sha256.take(16)}-${System.nanoTime()}")
        try {
            store.get(objectKeyFor(sha256), encoded, size + size / 100 + 1024 * 1024)
            val coroutine = currentCoroutineContext()
            SnapshotObjectCodec.decode(encoded, raw, PlainObjectRef(sha256, size)) { coroutine.ensureActive() }
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
            val downloaded = AtomicLong()
            var mergedDatabase: File? = null
            try {
                if (snapshot.formatVersion != SNAPSHOT_FORMAT_V1 && snapshot.databaseState == SnapshotDatabaseState.PRESENT) {
                    val merge = requireNotNull(mergeDatabase) { "This build cannot restore logical database snapshots" }
                    val raws = downloadObjects(store, snapshot.dbObjects, dir, "db", downloaded, snapshot.logicalBytes)
                    decodedFiles.addAll(raws)
                    val decodedObjects = raws.mapIndexed { index, raw -> DecodedDbObject(snapshot.dbObjects[index].objectKey, raw) }
                    mergedDatabase = requireNotNull(merge(decodedObjects, dir)) { "Database merge produced no database" }.also {
                        require(it.isFile && it.length() > 0) { "Database merge produced no database" }
                    }
                }
                // 文件先并行下载+解包，再单线程写 ZIP（ZipOutputStream 不是并发安全的）。
                val decodedFileRaws = downloadObjects(store, snapshot.files, dir, "file", downloaded, snapshot.logicalBytes)
                decodedFiles.addAll(decodedFileRaws)
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    snapshot.files.forEachIndexed { index, entry ->
                        coroutine.ensureActive()
                        addFileToZip(out, decodedFileRaws[index], entry.path, { coroutine.ensureActive() })
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

    /**
     * 下载一批引用：独立对象并行下载+解码；pack 按 pack 分组，每个 pack 只下载一次，
     * 再按条目 SHA 解出原始载荷。返回与 [refs] 顺序一致的原始文件。
     */
    private suspend fun downloadObjects(
        store: BackupObjectStore,
        refs: List<SnapshotObjectRef>,
        dir: File,
        tag: String,
        downloaded: AtomicLong,
        totalBytes: Long,
    ): List<File> {
        if (refs.isEmpty()) return emptyList()
        val coroutine = currentCoroutineContext()
        val raws = arrayOfNulls<File>(refs.size)
        val packGroups = LinkedHashMap<String, MutableList<Int>>()
        val singles = ArrayList<Int>()
        refs.forEachIndexed { index, ref ->
            val packKey = ref.packKey
            if (packKey == null) singles += index else packGroups.getOrPut(packKey) { ArrayList() } += index
        }
        val batches: List<Pair<String?, List<Int>>> =
            packGroups.map { (packKey, indices) -> packKey to indices.toList() } + singles.map { null to listOf(it) }
        parallelMap(batches) { (packKey, indices) ->
            coroutine.ensureActive()
            if (packKey == null) {
                val index = indices.single()
                val ref = refs[index]
                val encoded = File(dir, "$tag-encoded-$index")
                val raw = File(dir, "$tag-raw-$index").also { raws[index] = it }
                store.get(ref.storageKey, encoded, encodedLimit(ref)) { n ->
                    coroutine.ensureActive()
                    progress(BackupProgress(BackupPhase.DOWNLOADING, downloaded.addAndGet(n), totalBytes))
                }
                SnapshotObjectCodec.decode(encoded, raw, ref) { coroutine.ensureActive() }
                encoded.delete()
            } else {
                val packRefs = indices.map { refs[it] }
                val packSha = requireNotNull(packShaFromKey(packKey)) { "Invalid pack path" }
                val packFile = File(dir, "$tag-pack-$packSha")
                store.get(packKey, packFile, SnapshotPacking.downloadLimit(packRefs)) { n ->
                    coroutine.ensureActive()
                    progress(BackupProgress(BackupPhase.DOWNLOADING, downloaded.addAndGet(n), totalBytes))
                }
                require(fileSha256(packFile) == packSha) { "Backup pack checksum mismatch" }
                val targets = indices.map { index -> File(dir, "$tag-raw-$index").also { raws[index] = it } }
                SnapshotPacking.extractAll(packFile, packRefs, targets)
                packFile.delete()
            }
        }
        return raws.map { requireNotNull(it) { "Backup object was not downloaded" } }
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
     * 未引用对象/pack GC：严格读取**仓库内所有设备**的已提交快照求可达集，再删除
     * `v3/objects/` 与 `v3/packs/` 下不可达的条目；任何列举/读取/删除失败即停止。
     *
     * 多设备共用一个目录时的并发保护（仓库内信息无法完全排除「另一台设备刚上传、清单还没提交」）：
     * 1. `v3/gc.json` 租约：别的设备的回收在 30 分钟内进行时直接拒绝；
     * 2. 删除前重新严格列举快照，集合变化即中止（fail-stop）；
     * 3. 只删「最后修改时间已知且早于 [grace]」的候选——正在上传的对象不会被删，
     *    拿不到 mtime 的 provider 一律保守保留并计入 [ObjectGcResult.skippedRecent]。
     * 即使极端窗口下误删了别的设备刚上传的对象，其提交前的存在性复检会失败（不会产生坏快照）。
     */
    suspend fun garbageCollect(
        store: BackupObjectStore,
        deviceId: String,
        grace: Duration = GC_GRACE,
    ): ObjectGcResult {
        val repo = requireNotNull(inspectRepository(store)) { "Missing repository" }
        acquireGcLease(store, deviceId, repo.repoId)
        try {
            val snapshots = snapshotsStrict(store)
            val referencedObjects = HashSet<String>()
            val referencedPacks = HashSet<String>()
            snapshots.forEach { snapshot ->
                snapshot.objects.forEach { ref ->
                    val packKey = ref.packKey
                    if (packKey != null) referencedPacks += packKey else referencedObjects += ref.storageKey
                }
            }
            var keptObjects = 0
            val objectCandidates = ArrayList<BackupStoredObject>()
            store.listAll("v3/objects").forEach { stored ->
                currentCoroutineContext().ensureActive()
                require(!stored.directory) { "Unexpected directory in object bucket: ${stored.key}" }
                val sha = stored.key.substringAfterLast('/')
                require(validHash(sha) && stored.key == objectKeyFor(sha)) { "Unexpected object layout: ${stored.key}" }
                if (stored.key in referencedObjects) keptObjects++ else objectCandidates += stored
            }
            val packCandidates = ArrayList<BackupStoredObject>()
            store.listChildren(PACK_DIR).forEach { stored ->
                currentCoroutineContext().ensureActive()
                require(!stored.directory && packShaFromKey(stored.key) != null) { "Unexpected pack layout: ${stored.key}" }
                if (stored.key in referencedPacks) Unit else packCandidates += stored
            }
            // 删除前复核：回收期间若有快照新增/删除，说明别的设备正在提交，整次回收中止。
            val confirmed = snapshotsStrict(store).map { it.snapshotId }.toSet()
            require(confirmed == snapshots.map { it.snapshotId }.toSet()) {
                "Repository changed while collecting garbage; retry later"
            }
            val objectDeletes = objectCandidates.filter { olderThan(it, grace) }
            val packDeletes = packCandidates.filter { olderThan(it, grace) }
            val skippedRecent = objectCandidates.size - objectDeletes.size + packCandidates.size - packDeletes.size
            // 删除也要按对象并行：SAF 每个 delete 都要重新解析路径，串行时上百个孤儿对象能跑一分多钟。
            parallelMap(objectDeletes) { stored ->
                currentCoroutineContext().ensureActive()
                store.delete(stored.key)
            }
            parallelMap(packDeletes) { stored ->
                currentCoroutineContext().ensureActive()
                store.delete(stored.key)
            }
            val freed = objectDeletes.sumOf { it.bytes ?: 0L } + packDeletes.sumOf { it.bytes ?: 0L }
            return ObjectGcResult(objectDeletes.size, freed, keptObjects, packDeletes.size, skippedRecent)
        } finally {
            withContext(NonCancellable) { releaseGcLease(store, deviceId) }
        }
    }

    /** 候选删除条件：mtime 已知且早于宽限期；未知时间一律保留（防并发上传窗口）。 */
    private fun olderThan(stored: BackupStoredObject, grace: Duration): Boolean {
        val modified = stored.lastModified ?: return false
        return Duration.between(modified, Instant.now()) >= grace
    }

    private suspend fun readGcLease(store: BackupObjectStore): GcLease? {
        if (store.stat(GC_LEASE_KEY)?.directory != false) return null
        val dir = taskDir()
        return try {
            val file = File(dir, "gc.json")
            store.get(GC_LEASE_KEY, file, 4096)
            runCatching { json.decodeFromString<GcLease>(file.readText()) }.getOrNull()
        } finally { dir.deleteRecursively() }
    }

    private suspend fun acquireGcLease(store: BackupObjectStore, deviceId: String, repoId: String) {
        readGcLease(store)?.let { lease ->
            val started = runCatching { Instant.parse(lease.startedAt) }.getOrNull()
            if (lease.repoId == repoId && lease.deviceId != deviceId && started != null &&
                Duration.between(started, Instant.now()) < GC_LEASE_TTL
            ) {
                throw IllegalStateException("另一台设备正在回收未引用对象，请稍后重试")
            }
        }
        val dir = taskDir()
        try {
            val file = File(dir, "gc.json").apply {
                writeText(json.encodeToString(GcLease(deviceId, repoId, Instant.now().toString())))
            }
            store.ensureDir("v3")
            store.putOverwrite(GC_LEASE_KEY, file)
        } finally { dir.deleteRecursively() }
    }

    private suspend fun releaseGcLease(store: BackupObjectStore, deviceId: String) {
        // 只删自己的租约：回收超过 TTL 后可能有别的设备接手，别把它的租约删掉。
        runCatching {
            val lease = readGcLease(store) ?: return@runCatching
            if (lease.deviceId == deviceId) store.delete(GC_LEASE_KEY)
        }
    }

    /** 提交前检查：别的设备正在回收时，避免与它抢「刚上传、尚未被清单引用」的对象。 */
    private suspend fun ensureNoForeignGcLease(store: BackupObjectStore, deviceId: String) {
        val lease = readGcLease(store) ?: return
        val started = runCatching { Instant.parse(lease.startedAt) }.getOrNull() ?: return
        require(lease.deviceId == deviceId || Duration.between(started, Instant.now()) >= GC_LEASE_TTL) {
            "另一台设备正在回收未引用对象，请稍后重试"
        }
    }

    suspend fun deleteSnapshot(store: BackupObjectStore, snapshot: BackupSnapshotManifest) {
        require(inspectRepository(store)?.repoId == snapshot.repoId)
        validateSnapshot(snapshot)
        store.delete(snapshot.completionKey)
        store.delete(snapshot.manifestKey)
        // Shared objects are deliberately retained. Safe GC is a later phase.
    }

    private fun encodedLimit(entry: SnapshotObjectRef) = entry.size + entry.size / 100 + 1024 * 1024

    /** 一次计划里某个唯一内容的处置：复用已有引用（可能带 pack 指针）或上传新内容。 */
    private sealed interface ObjectDecision {
        data class Reuse(val pack: String?) : ObjectDecision
        data object Upload : ObjectDecision
    }

    /** 仅用于解码校验的最小引用（不写清单）。 */
    private data class PlainObjectRef(override val sha256: String, override val size: Long) : SnapshotObjectRef {
        override val pack: String? get() = null
    }

    private companion object {
        /** 仓库级 GC 租约路径与有效期（多设备共用目录时避免同时回收）。 */
        const val GC_LEASE_KEY = "v3/gc.json"
        val GC_LEASE_TTL: Duration = Duration.ofMinutes(30)

        /** 只回收「已静默（mtime 早于宽限期）」的候选，保护另一台设备正在上传的对象。 */
        val GC_GRACE: Duration = Duration.ofMinutes(10)
    }

    /** 对象级并行（哈希/stat/压缩/校验独立）；并发度固定保守值，避免把低端机 IO 打满。 */
    private suspend fun <T, R> parallelMap(items: List<T>, block: suspend (T) -> R): List<R> =
        if (items.size <= 1) items.map { block(it) }
        else coroutineScope {
            val permits = Semaphore(minOf(PARALLEL_OBJECTS, items.size))
            items.map { item -> async(Dispatchers.IO) { permits.withPermit { block(item) } } }.awaitAll()
        }
}
