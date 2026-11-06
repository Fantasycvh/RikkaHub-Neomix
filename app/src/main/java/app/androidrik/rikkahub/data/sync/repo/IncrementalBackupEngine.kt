package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.BackupPhase
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.data.sync.ProgressThrottle
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
    /** 拆包重构的 pack 数（pack 里部分条目已不可达，重打包并改写快照清单）。 */
    val repackedPacks: Int = 0,
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
        val marker = readCompletion(store, id, repoId, dir)
        val manifest = File(dir, "manifest-$id.json.gz")
        store.get("v3/snapshots/$id.json.gz", manifest, marker.manifestBytes)
        require(manifest.length() == marker.manifestBytes && fileSha256(manifest) == marker.manifestSha256) { "Snapshot checksum mismatch" }
        val raw = File(dir, "manifest-$id.json")
        GZIPInputStream(manifest.inputStream()).use { input -> raw.outputStream().use { copyBounded(input, it, METADATA_LIMIT) } }
        return json.decodeFromString<BackupSnapshotManifest>(raw.readText()).also {
            validateSnapshot(it)
            require(it.snapshotId == id && it.repoId == repoId) { "Snapshot identity mismatch" }
        }
    }

    /** 只读完成标记（拆包改写/修复用它判断当前发布状态）。 */
    private suspend fun readCompletion(store: BackupObjectStore, id: String, repoId: String, dir: File): SnapshotCompletion {
        val markerFile = File(dir, "completion-$id.json")
        store.get("v3/snapshots/$id.complete.json", markerFile, 4096)
        return json.decodeFromString<SnapshotCompletion>(markerFile.readText()).also {
            require(it.version == REPO_VERSION && it.snapshotId == id && it.repoId == repoId &&
                validHash(it.manifestSha256) && it.manifestBytes in 1..METADATA_LIMIT) { "Invalid completion marker" }
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
        // 上次拆包改写若中断，先回滚再提交，避免半写快照被当成损坏。
        val repairDir = taskDir()
        try { repairSnapshotRewrites(store, repository?.repoId ?: plan.snapshot.repoId, repairDir) }
        finally { repairDir.deleteRecursively() }
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
            // 拆包改写若在「覆盖清单」与「覆盖完成标记」之间中断，先按旧清单备份回滚，再严格读取。
            repairSnapshotRewrites(store, repo.repoId, dir)
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
        // 别家正在回收/拆包时不要动快照清单，避免与它的改写/回滚互相踩。
        ensureNoForeignGcLease(store, deviceId)
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
        val gcProgress = GcProgress(progress)
        gcProgress.start(BackupPhase.SCANNING, null)
        val repo = requireNotNull(inspectRepository(store)) { "Missing repository" }
        acquireGcLease(store, deviceId, repo.repoId)
        val dir = taskDir()
        try {
            val snapshots = snapshotsStrict(store) // 内部会先修复上次未完成的拆包改写
            val reachable = reachability(snapshots)
            var keptObjects = 0
            val objectCandidates = ArrayList<BackupStoredObject>()
            store.listAll("v3/objects").forEach { stored ->
                currentCoroutineContext().ensureActive()
                require(!stored.directory) { "Unexpected directory in object bucket: ${stored.key}" }
                val sha = stored.key.substringAfterLast('/')
                require(validHash(sha) && stored.key == objectKeyFor(sha)) { "Unexpected object layout: ${stored.key}" }
                if (stored.key in reachable.objects) keptObjects++ else objectCandidates += stored
            }
            // 拆包：先重打包并改写快照清单（「部分条目仍被引用」的 pack 才能释放空间），再统一删除。
            val repack = repackPacks(store, repo.repoId, snapshots, reachable.packs, grace, dir, gcProgress)
            // 复核前重新严格读一遍快照（读的是改写后的清单），顺便把阶段切回「扫描」。
            gcProgress.start(BackupPhase.SCANNING, null)
            val confirmed = snapshotsStrict(store)
            val confirmedReachable = reachability(confirmed)
            require(confirmed.map { it.snapshotId }.toSet() == snapshots.map { it.snapshotId }.toSet()) {
                "Repository changed while collecting garbage; retry later"
            }
            val expectedPacks = reachable.packs.entries.associate { (sha, entries) -> (repack.replacements[sha] ?: sha) to entries }
            require(confirmedReachable.objects == reachable.objects && confirmedReachable.packs == expectedPacks) {
                "Repository changed while collecting garbage; retry later"
            }
            val packCandidates = listPacks(store).filter { packShaFromKey(it.key) !in confirmedReachable.packs }
            val objectDeletes = objectCandidates.filter { olderThan(it, grace) }
            val packDeletes = packCandidates.filter { olderThan(it, grace) }
            val skippedRecent = objectCandidates.size - objectDeletes.size + packCandidates.size - packDeletes.size
            // 删除阶段按待删字节推进进度；拿不到全部字节数时退化为不确定进度条。
            val deleteEntries = objectDeletes + packDeletes
            val cleanupTotal = if (deleteEntries.isNotEmpty() && deleteEntries.all { it.bytes != null }) {
                deleteEntries.sumOf { it.bytes ?: 0L }
            } else null
            gcProgress.start(BackupPhase.CLEANUP, cleanupTotal)
            // 删除也要按对象并行：SAF 每个 delete 都要重新解析路径，串行时上百个孤儿对象能跑一分多钟。
            parallelMap(objectDeletes) { stored ->
                currentCoroutineContext().ensureActive()
                store.delete(stored.key)
                gcProgress.add(stored.bytes ?: 0L)
            }
            parallelMap(packDeletes) { stored ->
                currentCoroutineContext().ensureActive()
                store.delete(stored.key)
                gcProgress.add(stored.bytes ?: 0L)
            }
            val freed = (
                objectDeletes.sumOf { it.bytes ?: 0L } + packDeletes.sumOf { it.bytes ?: 0L } - repack.uploadedBytes
                ).coerceAtLeast(0)
            return ObjectGcResult(objectDeletes.size, freed, keptObjects, packDeletes.size, skippedRecent, repack.replacements.size)
        } finally {
            withContext(NonCancellable) { releaseGcLease(store, deviceId) }
            dir.deleteRecursively()
        }
    }

    /** 快照可达集：独立对象路径 + 每个 pack 里仍被引用的条目（sha → 原始大小）。 */
    private class Reachability(val objects: Set<String>, val packs: Map<String, Map<String, Long>>)

    private fun reachability(snapshots: List<BackupSnapshotManifest>): Reachability {
        val objects = HashSet<String>()
        val packs = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        snapshots.forEach { snapshot ->
            snapshot.objects.forEach { ref ->
                val packSha = ref.pack
                if (packSha != null) {
                    val entries = packs.getOrPut(packSha) { LinkedHashMap() }
                    val previous = entries.put(ref.sha256, ref.size)
                    require(previous == null || previous == ref.size) { "Inconsistent object metadata: ${ref.sha256}" }
                } else objects += ref.storageKey
            }
        }
        return Reachability(objects, packs)
    }

    private suspend fun listPacks(store: BackupObjectStore): List<BackupStoredObject> =
        store.listChildren(PACK_DIR).map { stored ->
            require(!stored.directory && packShaFromKey(stored.key) != null) { "Unexpected pack layout: ${stored.key}" }
            stored
        }

    private class RepackResult(val replacements: Map<String, String>, val uploadedBytes: Long)

    /** 已本地重打包、尚未上传的新 pack（[newFile] 在 GC 任务目录里，随任务目录清理）。 */
    private class RepackedPack(val oldSha: String, val newSha: String, val newFile: File)

    /**
     * 仓库回收的进度上报：并行拆包任务会从多个协程同时上报，这里把「阶段 + 字节」聚合成单条进度并
     * 串行节流，避免并发写 StateFlow/通知栏与进度回退；阶段切换时清零并立即上报一次。
     */
    private class GcProgress(private val sink: (BackupProgress) -> Unit) {
        private val lock = Any()
        private var phase: BackupPhase? = null
        private var total: Long? = null
        private var processed = 0L
        private var throttle = ProgressThrottle()

        fun start(newPhase: BackupPhase, newTotal: Long?) = synchronized(lock) {
            phase = newPhase
            total = newTotal
            processed = 0L
            throttle = ProgressThrottle()
            sink(BackupProgress(newPhase, 0L, newTotal))
        }

        /** 累计已处理字节；总量未知时只推进计数（进度条不确定）。 */
        fun add(delta: Long) = synchronized(lock) {
            val current = phase ?: return@synchronized
            if (delta <= 0L) return@synchronized
            processed += delta
            val value = BackupProgress(current, processed, total)
            if (throttle.shouldEmit(value)) sink(value)
        }
    }

    /**
     * 拆包：对「仍被快照引用、但 pack 里有条目已不可达」的 pack，把仍被引用的条目重新打成一个
     * 内容寻址 pack，并改写相关快照清单的 pack 指针；旧 pack 随后由删除阶段回收。
     *
     * 多个 pack 的下载/校验/解压/重打包是独立的文件操作，按 [REPACK_PARALLEL] 并行执行
     * （一份快照引用多个被拆 pack 时收益最大）；新 pack 的上传在准备阶段全部成功后再按
     * 新 SHA 去重、串行执行，避免两个任务同时创建同一个内容寻址文档。
     *
     * 进度按 [GcProgress] 上报：REPACKING（下载 + 重打包字节）→ UPLOADING（新 pack 字节）→ COMMITTING。
     *
     * 崩溃安全：改写前把旧清单备份到 `v3/rewrite/<id>.json.gz`，先覆盖清单再覆盖完成标记，
     * 任一步中断都由 [repairSnapshotRewrites] 按标记回滚——读侧永远看到旧清单或新清单的完整状态。
     */
    private suspend fun repackPacks(
        store: BackupObjectStore,
        repoId: String,
        snapshots: List<BackupSnapshotManifest>,
        referencedPacks: Map<String, Map<String, Long>>,
        grace: Duration,
        dir: File,
        gcProgress: GcProgress,
    ): RepackResult {
        if (referencedPacks.isEmpty()) return RepackResult(emptyMap(), 0)
        val storedPacks = listPacks(store).associateBy { requireNotNull(packShaFromKey(it.key)) }
        // 先确认所有被引用的 pack 都在（fail-stop）；只有「已经静默」的 pack 才拆，避免与刚提交的内容互相踩。
        referencedPacks.forEach { (sha, _) -> requireNotNull(storedPacks[sha]) { "Referenced pack is missing: $sha" } }
        val candidates = referencedPacks.mapNotNull { (sha, entries) ->
            storedPacks[sha]?.takeIf { olderThan(it, grace) }?.let { Triple(sha, entries, it) }
        }
        if (candidates.isEmpty()) return RepackResult(emptyMap(), 0)
        // 总工作量口径 = 下载旧 pack + 写出新 pack；拿不到旧 pack 字节数时总量未知（进度条不确定）。
        // 全引用的 pack 不写新 pack，由 [repackOnePack] 在确认无需拆包时一次性计入，保证整体仍能到 100%。
        val repackTotal = if (candidates.all { it.third.bytes != null }) {
            candidates.sumOf { (it.third.bytes ?: 0L) + it.second.values.sum() }
        } else null
        gcProgress.start(BackupPhase.REPACKING, repackTotal)
        val prepared = parallelMap(candidates, parallelism = REPACK_PARALLEL) { (packSha, entries, stored) ->
            repackOnePack(store, stored, packSha, entries, dir, gcProgress)
        }.filterNotNull()
        if (prepared.isEmpty()) return RepackResult(emptyMap(), 0)

        val replacements = LinkedHashMap<String, String>()
        prepared.forEach { replacements[it.oldSha] = it.newSha }
        // 不同旧 pack 可能抽出同一批对象（同一 SHA 在不同设备各自打进不同 pack），新 pack 内容寻址相同只传一次；
        // 内容已存在（上次中断后的重试）直接跳过，避免重复上传。
        val missing = LinkedHashMap<String, RepackedPack>()
        prepared.associateBy { it.newSha }.forEach { (newSha, pack) ->
            val existing = store.stat(packKeyFor(newSha))
            if (existing == null) {
                missing[newSha] = pack
            } else {
                require(!existing.directory && (existing.bytes == null || existing.bytes == pack.newFile.length())) {
                    "Conflicting repacked pack content"
                }
            }
        }
        var uploadedBytes = 0L
        if (missing.isNotEmpty()) {
            gcProgress.start(BackupPhase.UPLOADING, missing.values.sumOf { it.newFile.length() })
            missing.values.forEach { pack ->
                currentCoroutineContext().ensureActive()
                store.ensureDir(PACK_DIR)
                store.put(packKeyFor(pack.newSha), pack.newFile) { gcProgress.add(it) }
                uploadedBytes = Math.addExact(uploadedBytes, pack.newFile.length())
            }
        }
        // 同一份快照可能引用多个被拆的 pack，清单只改写一次。
        gcProgress.start(BackupPhase.COMMITTING, null)
        rewriteSnapshotPacks(store, repoId, snapshots, replacements, dir)
        return RepackResult(replacements, uploadedBytes)
    }

    /**
     * 单个 pack 的拆包准备：下载整包并校验 SHA、确认被引用条目齐全、抽出仍被引用条目并逐条校验、
     * 重新打成确定性 pack。返回 null = 该 pack 无需拆包（全部条目仍被引用）。
     */
    private suspend fun repackOnePack(
        store: BackupObjectStore,
        pack: BackupStoredObject,
        packSha: String,
        entries: Map<String, Long>,
        dir: File,
        gcProgress: GcProgress,
    ): RepackedPack? {
        currentCoroutineContext().ensureActive()
        val packFile = File(dir, "repack-$packSha.zip")
        val kept = entries.keys.sorted()
        val keptBytes = entries.values.sum()
        val extracted = kept.map { sha -> File(dir, "repack-entry-$sha") }
        try {
            store.get(pack.key, packFile, REPACK_DOWNLOAD_LIMIT) { gcProgress.add(it) }
            require(fileSha256(packFile) == packSha) { "Referenced pack checksum mismatch: $packSha" }
            val sizes = SnapshotPacking.entrySizes(packFile)
            entries.forEach { (sha, size) -> require(sizes[sha] == size) { "Packed object missing from pack: $sha" } }
            if (sizes.keys.all { it in entries }) {
                // 全引用：下载完即可确认无需拆包；把「不存在的写工作量」也记入进度，保证整体仍能到 100%。
                gcProgress.add(keptBytes)
                return null
            }
            // 只把仍被引用的条目重新打包；pack 是内容寻址的，未变内容不会重复占空间。
            SnapshotPacking.extract(packFile, kept.map { it to requireNotNull(entries[it]) }, extracted)
            extracted.forEachIndexed { index, file ->
                require(fileSha256(file) == kept[index]) { "Packed object checksum mismatch: ${kept[index]}" }
            }
            val packed = SnapshotPacking.write(dir, kept.mapIndexed { index, sha -> sha to extracted[index] }) {
                gcProgress.add(it)
            }
            val (newKey, newPack) = packed.files.entries.singleOrNull() ?: error("Repacked pack must fit in one file")
            return RepackedPack(packSha, requireNotNull(packShaFromKey(newKey)), newPack)
        } finally {
            packFile.delete()
            extracted.forEach(File::delete)
        }
    }

    /** 把快照清单里指向旧 pack 的引用改成新 pack，两阶段覆盖清单与完成标记。 */
    private suspend fun rewriteSnapshotPacks(
        store: BackupObjectStore,
        repoId: String,
        snapshots: List<BackupSnapshotManifest>,
        replacements: Map<String, String>,
        dir: File,
    ) {
        snapshots.forEach { snapshot ->
            currentCoroutineContext().ensureActive()
            fun updatedPack(pack: String?): String? = pack?.let { replacements[it] ?: it }
            val updated = snapshot.copy(
                files = snapshot.files.map { it.copy(pack = updatedPack(it.pack)) },
                dbObjects = snapshot.dbObjects.map { it.copy(pack = updatedPack(it.pack)) },
            )
            if (updated == snapshot) return@forEach
            validateSnapshot(updated)
            val oldManifest = File(dir, "old-${snapshot.snapshotId}.json.gz")
            store.get(snapshot.manifestKey, oldManifest, METADATA_LIMIT)
            val marker = readCompletion(store, snapshot.snapshotId, repoId, dir)
            require(marker.manifestBytes == oldManifest.length() && marker.manifestSha256 == fileSha256(oldManifest)) {
                "Snapshot changed while repacking; retry later"
            }
            // 两阶段改写：备份旧清单 → 覆盖清单 → 覆盖完成标记 → 回读校验 → 删除备份。
            val journalKey = snapshotRewriteKey(snapshot.snapshotId)
            store.ensureDir(REWRITE_DIR)
            store.putOverwrite(journalKey, oldManifest)
            val text = json.encodeToString(updated).toByteArray(Charsets.UTF_8)
            require(text.size <= METADATA_LIMIT) { "Snapshot manifest exceeds size limit" }
            val newManifest = File(dir, "new-${snapshot.snapshotId}.json.gz")
            GZIPOutputStream(newManifest.outputStream()).use { it.write(text) }
            store.putOverwrite(snapshot.manifestKey, newManifest)
            val completion = SnapshotCompletion(
                snapshotId = snapshot.snapshotId,
                repoId = repoId,
                manifestSha256 = fileSha256(newManifest),
                manifestBytes = newManifest.length(),
            )
            val markerFile = File(dir, "marker-${snapshot.snapshotId}.json").apply { writeText(json.encodeToString(completion)) }
            store.putOverwrite(snapshot.completionKey, markerFile)
            readSnapshot(store, snapshot.snapshotId, repoId, dir)
            store.delete(journalKey)
        }
    }

    /**
     * 修复拆包改写中断：`v3/rewrite/<id>.json.gz` 是改写前的旧清单。
     * - 完成标记匹配旧清单、但当前清单已被覆盖：回滚旧清单（标记无需改），再删备份；
     * - 当前清单与标记一致（改写完成或尚未开始）：删备份；
     * - 两种都不匹配：不断言、直接报错（仓库需要人工检查）。
     */
    private suspend fun repairSnapshotRewrites(store: BackupObjectStore, repoId: String, dir: File) {
        val journals = runCatching { store.listChildren(REWRITE_DIR) }.getOrNull().orEmpty().filter { !it.directory }
        journals.forEach { journal ->
            currentCoroutineContext().ensureActive()
            val id = journal.key.substringAfterLast('/').removeSuffix(".json.gz")
            require(validUuid(id) && journal.key == snapshotRewriteKey(id)) { "Unexpected rewrite journal: ${journal.key}" }
            val marker = runCatching { readCompletion(store, id, repoId, dir) }.getOrNull()
            if (marker == null) { store.delete(journal.key); return@forEach }
            val backup = File(dir, "journal.json.gz")
            store.get(journal.key, backup, METADATA_LIMIT)
            val current = File(dir, "current.json.gz")
            val currentRead = runCatching {
                store.get("v3/snapshots/$id.json.gz", current, METADATA_LIMIT)
                current.length() == marker.manifestBytes && fileSha256(current) == marker.manifestSha256
            }.getOrDefault(false)
            when {
                fileSha256(backup) == marker.manifestSha256 && backup.length() == marker.manifestBytes -> {
                    if (!currentRead) store.putOverwrite("v3/snapshots/$id.json.gz", backup)
                    store.delete(journal.key)
                }
                currentRead -> store.delete(journal.key)
                else -> throw IllegalStateException("Snapshot rewrite journal does not match repository state: ${journal.key}")
            }
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

        /** 拆包时下载旧 pack 的上限：单 pack 原始量上限 + zip 头/压缩最坏开销。 */
        val REPACK_DOWNLOAD_LIMIT: Long = PACK_TARGET_BYTES + 8L * 1024 * 1024

        /**
         * 单次回收内并行拆包的 pack 数。拆包是「下载 + 解压 + 重压缩」，比对象级操作更重，
         * 并发度比 [PARALLEL_OBJECTS] 再保守一档，避免低端机同时开太多压缩流/下载把内存与 IO 打满。
         */
        const val REPACK_PARALLEL = 3
    }

    /** 对象级并行（哈希/stat/压缩/校验独立）；并发度固定保守值，避免把低端机 IO 打满。 */
    private suspend fun <T, R> parallelMap(
        items: List<T>,
        parallelism: Int = PARALLEL_OBJECTS,
        block: suspend (T) -> R,
    ): List<R> =
        if (items.size <= 1) items.map { block(it) }
        else coroutineScope {
            val permits = Semaphore(minOf(parallelism, items.size))
            items.map { item -> async(Dispatchers.IO) { permits.withPermit { block(item) } } }.awaitAll()
        }
}
