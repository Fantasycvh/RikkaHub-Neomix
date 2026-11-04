package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.BackupManifestSection
import app.androidrik.rikkahub.data.sync.BackupPhase
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.BackupSectionStats
import app.androidrik.rikkahub.data.sync.classifyBackupEntry
import app.androidrik.rikkahub.data.sync.addFileToZip
import kotlinx.coroutines.currentCoroutineContext
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
            val checked = HashSet<String>()
            var captured = 0L
            var totalRaw = 0L
            var reused = 0L
            val coroutine = currentCoroutineContext()

            /** 一个对象（文件或 DB 对象）共同的去重/上传路径。 */
            suspend fun captureObject(raw: File, size: Long, logicalKey: String, databaseObject: Boolean) {
                totalRaw = Math.addExact(totalRaw, size)
                require(totalRaw <= BackupManifest.MAX_TOTAL_BYTES) { "Backup grew beyond the size limit" }
                val sha = fileSha256(raw)
                val ref: SnapshotObjectRef = if (databaseObject) {
                    SnapshotDbObject(logicalKey, sha, size).also { dbEntries += it }
                } else {
                    SnapshotFileEntry(logicalKey, sha, size).also { entries += it }
                }
                if (checked.add(ref.key)) {
                    val existing = store.stat(ref.key)
                    if (existing != null && trusted[ref.sha256]?.size == ref.size) {
                        require(!existing.directory) { "Object is a directory" }
                        reused += size
                    } else if (existing != null) {
                        // Uncommitted objects can be partial writes left by a failed attempt.
                        verifyObject(store, ref, dir)
                        reused += size
                    } else {
                        val encoded = File(dir, "object-${uploads.size}-${checked.size}")
                        SnapshotObjectCodec.encode(raw, encoded) { coroutine.ensureActive() }
                        uploads[ref.key] = encoded
                    }
                } else reused += size
            }

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
                captureObject(raw, size, source.path, databaseObject = false)
                raw.delete()
            }
            databaseObjects.forEachIndexed { index, source ->
                coroutine.ensureActive()
                validateDbObjectKey(source.objectKey)
                val raw = File(dir, "db-raw-$index")
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
                    source.file.lastModified() == originalTime) { "Database object changed while being captured; retry backup: ${source.objectKey}" }
                require(size > 0) { "Database object is empty: ${source.objectKey}" }
                captureObject(raw, size, source.objectKey, databaseObject = true)
                raw.delete()
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
        var uploaded = 0L
        val coroutine = currentCoroutineContext()
        val references = plan.snapshot.objects.associateBy { it.key }
        plan.uploads.forEach { (key, file) ->
            coroutine.ensureActive()
            store.ensureDir(key.substringBeforeLast('/'))
            store.put(key, file) { n ->
                coroutine.ensureActive()
                uploaded += n
                progress(BackupProgress(BackupPhase.UPLOADING, uploaded, plan.uploadBytes))
            }
            val entry = requireNotNull(references[key]) { "Uploaded object is not referenced by the snapshot" }
            verifyObject(store, entry, plan.directory)
        }
        // Recheck reused references immediately before publication; missing objects never produce a snapshot.
        plan.snapshot.objects.distinctBy { it.key }.forEach {
            require(store.stat(it.key)?.directory == false) { "Backup object disappeared; scan again" }
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

    private suspend fun verifyObject(store: BackupObjectStore, entry: SnapshotObjectRef, dir: File) {
        val encoded = File(dir, "verify-encoded")
        val raw = File(dir, "verify-raw")
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
                else -> require(snapshot.dbVersion == maxDbVersion) {
                    "Logical database snapshots can only be restored into the same database schema version"
                }
            }
            // Objects + decoded file + output ZIP + restore DB + safety backup, conservatively budgeted.
            val needed = Math.addExact(Math.multiplyExact(snapshot.logicalBytes, 5), RESERVE_BYTES)
            check(cache.usableSpace >= needed) { "Not enough internal space for snapshot restore (need $needed bytes)" }
            val zip = File(dir, "backup_snapshot.zip")
            val coroutine = currentCoroutineContext()
            var processed = 0L
            val decodedObjects = ArrayList<DecodedDbObject>()
            var mergedDatabase: File? = null
            try {
                if (snapshot.formatVersion == SNAPSHOT_FORMAT_V2 && snapshot.databaseState == SnapshotDatabaseState.PRESENT) {
                    val merge = requireNotNull(mergeDatabase) { "This build cannot restore logical database snapshots" }
                    snapshot.dbObjects.forEachIndexed { index, entry ->
                        coroutine.ensureActive()
                        val encoded = File(dir, "db-encoded-$index")
                        val raw = File(dir, "db-payload-$index")
                        store.get(entry.key, encoded, encodedLimit(entry)) { n ->
                            coroutine.ensureActive()
                            progress(BackupProgress(BackupPhase.DOWNLOADING, processed + n, null))
                        }
                        SnapshotObjectCodec.decode(encoded, raw, entry) { coroutine.ensureActive() }
                        decodedObjects += DecodedDbObject(entry.objectKey, raw)
                        processed += entry.size
                        progress(BackupProgress(BackupPhase.EXTRACTING, processed, snapshot.logicalBytes))
                        encoded.delete()
                    }
                    mergedDatabase = requireNotNull(merge(decodedObjects, dir)) { "Database merge produced no database" }.also {
                        require(it.isFile && it.length() > 0) { "Database merge produced no database" }
                    }
                }
                ZipOutputStream(zip.outputStream().buffered()).use { out ->
                    snapshot.files.forEach { entry ->
                        coroutine.ensureActive()
                        val encoded = File(dir, "encoded")
                        val raw = File(dir, "decoded")
                        store.get(entry.key, encoded, encodedLimit(entry)) { n -> coroutine.ensureActive(); progress(BackupProgress(BackupPhase.DOWNLOADING, processed + n, null)) }
                        SnapshotObjectCodec.decode(encoded, raw, entry) { coroutine.ensureActive() }
                        addFileToZip(out, raw, entry.path, { coroutine.ensureActive() })
                        processed += entry.size
                        progress(BackupProgress(BackupPhase.EXTRACTING, processed, snapshot.logicalBytes))
                        encoded.delete(); raw.delete()
                    }
                    val database = mergedDatabase
                    if (database != null) addFileToZip(out, database, "rikka_hub.db", { coroutine.ensureActive() })
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
                decodedObjects.forEach { it.raw.delete() }
                mergedDatabase?.delete()
            }
        } catch (failure: Throwable) { dir.deleteRecursively(); throw failure }
    }

    suspend fun deleteSnapshot(store: BackupObjectStore, snapshot: BackupSnapshotManifest) {
        require(inspectRepository(store)?.repoId == snapshot.repoId)
        validateSnapshot(snapshot)
        store.delete(snapshot.completionKey)
        store.delete(snapshot.manifestKey)
        // Shared objects are deliberately retained. Safe GC is a later phase.
    }

    private fun encodedLimit(entry: SnapshotObjectRef) = entry.size + entry.size / 100 + 1024 * 1024
}
