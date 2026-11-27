package app.androidrik.rikkahub.data.sync.lan

import android.app.Application
import android.util.AtomicFile
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.export.sha256Of
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.nio.file.Files
import java.util.UUID

private const val FAMILY_CHUNK_BYTES = 8 * 1024 * 1024

@Serializable
internal data class LanFamilyFile(val path: String, val bytes: Long, val modifiedAt: Long, val chunks: List<String>)

@Serializable
internal data class LanFamilyObject(
    val key: String,
    val value: JsonElement? = null,
    val name: String = "",
    val root: String = "",
    val workspaceId: String? = null,
    val files: List<LanFamilyFile> = emptyList(),
)

@Serializable
internal data class LanFamilyRevision(val digest: String, val time: Long, val deletedAt: Long = 0, val descriptor: LanFamilyObject)

@Serializable
private data class LanFamilyPending(
    val recovery: String,
    val revisions: Map<String, LanFamilyRevision>,
    val files: Map<String, String?>,
    val before: Map<String, JsonElement?>,
    val after: Map<String, JsonElement?>,
    val removedAssistants: Set<kotlin.uuid.Uuid> = emptySet(),
    val written: Map<String, LanFamilyFile> = emptyMap(),
)

internal data class LanFamilyCapture(
    val entries: List<LanManifestObject>,
    val files: Map<String, File>,
    val tombstones: List<LanTombstone>,
)

/** Family revisions survive restarts. Missing previously observed entities become deletion records. */
internal class LanFamilyStore(
    private val app: Application,
    private val settings: SettingsStore,
    private val database: AppDatabase,
) {
    private val journal = AtomicFile(File(app.filesDir, "lan-family-revisions.json"))
    private val pending = AtomicFile(File(app.filesDir, "lan-family-pending.json"))
    private val chunks = File(app.cacheDir, "lan-family-chunks")
    private val fileIndex = AtomicFile(File(app.cacheDir, "lan-family-file-index.json"))
    private var fingerprints = mutableMapOf<String, LanFamilyFile>()
    private val skills = SkillManager(app, settings)

    private fun read(): Map<String, LanFamilyRevision> =
        if (!journal.baseFile.exists()) emptyMap() else journal.openRead().bufferedReader().use {
            JsonInstant.decodeFromString(it.readText())
        }

    private fun write(state: Map<String, LanFamilyRevision>) {
        val output = journal.startWrite()
        try {
            output.write(JsonInstant.encodeToString(state).encodeToByteArray())
            journal.finishWrite(output)
        } catch (failure: Throwable) {
            journal.failWrite(output)
            throw failure
        }
    }

    suspend fun capture(directory: File, now: Long): LanFamilyCapture {
        recover()
        check(chunks.isDirectory || chunks.mkdirs())
        fingerprints = runCatching {
            fileIndex.openRead().bufferedReader().use { JsonInstant.decodeFromString<Map<String, LanFamilyFile>>(it.readText()) }
        }.getOrDefault(emptyMap()).toMutableMap()
        val state = read().toMutableMap()
        val objects = linkedMapOf<String, File>()
        val observed = linkedSetOf<String>()
        val entries = arrayListOf<LanManifestObject>()
        val config = settings.settingsFlow.first { !it.init }
        val descriptors = arrayListOf<LanFamilyObject>()
        config.assistants.sortedBy { it.id.toString() }.forEach {
            descriptors += captureConfig("assistant:${it.id}", JsonInstant.encodeToJsonElement(Assistant.serializer(), it), objects)
        }
        config.lorebooks.sortedBy { it.id.toString() }.forEach {
            descriptors += captureConfig("lorebook:${it.id}", JsonInstant.encodeToJsonElement(Lorebook.serializer(), it), objects)
        }
        for (skill in skills.getSkillsDir().listFiles().orEmpty().sortedBy { it.name }) {
            if (!skill.isDirectory || skill.name.startsWith(".") || !File(skill, "SKILL.md").isFile) continue
            val files = captureTree(skill, objects)
            descriptors += LanFamilyObject(lanFamilyKey("skill", skill.name), name = skill.name, files = files)
        }
        for (workspace in database.workspaceDao().getAll().sortedBy { it.root }) {
            if (workspace.isSsh() || !WorkspaceBackupHelper.isValidRoot(workspace.root)) continue
            val root = File(WorkspaceBackupHelper.workspacesDir(app), "${workspace.root}/files")
            for (file in captureTree(root, objects)) {
                descriptors += LanFamilyObject(lanFamilyKey("workspace", "${workspace.root}/${file.path}"),
                    name = workspace.name, root = workspace.root, workspaceId = workspace.id, files = listOf(file))
            }
        }
        for (descriptor in descriptors) {
            currentCoroutineContext().ensureActive()
            val path = File(directory, descriptor.key.replace(':', '-'))
            path.writeText(JsonInstant.encodeToString(descriptor))
            val digest = hashLanFile(path)
            val old = state[descriptor.key]
            val stamp = if (old?.digest == digest && old.deletedAt == 0L) old.time else if (old == null) 0L
                else maxOf(now, old.time + 1, old.deletedAt + 1)
            state[descriptor.key] = LanFamilyRevision(digest, stamp, descriptor = descriptor)
            observed += descriptor.key
            val refs = descriptor.files.flatMap { it.chunks }.distinct()
            entries += LanManifestObject(descriptor.key, digest, stamp, path.length(), refs)
            objects[digest] = path
        }
        for ((key, old) in state.toMap()) {
            if (key !in observed && old.deletedAt == 0L) state[key] = old.copy(deletedAt = maxOf(now, old.time + 1))
        }
        val retained = state.filterValues { it.deletedAt == 0L || it.deletedAt >= now - LAN_TOMBSTONE_RETENTION_MS }
        write(retained)
        val indexOutput = fileIndex.startWrite()
        try {
            indexOutput.write(JsonInstant.encodeToString(fingerprints).encodeToByteArray())
            fileIndex.finishWrite(indexOutput)
        } catch (failure: Throwable) {
            fileIndex.failWrite(indexOutput)
            throw failure
        }
        return LanFamilyCapture(entries, objects, retained.filterValues { it.deletedAt > 0 }.map { LanTombstone(it.key, it.value.deletedAt) })
    }

    private suspend fun captureTree(root: File, objects: MutableMap<String, File>): List<LanFamilyFile> {
        if (!root.exists()) return emptyList()
        require(!Files.isSymbolicLink(root.toPath()))
        return root.walkTopDown().onEnter { directory ->
            require(!Files.isSymbolicLink(directory.toPath())) { "Symbolic directory in sync scope" }
            true
        }.filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList().map { source ->
            captureFile(root, source, objects)
        }
    }

    private suspend fun captureFile(root: File, source: File, objects: MutableMap<String, File>): LanFamilyFile {
        require(!Files.isSymbolicLink(source.toPath()))
        val modifiedAt = source.lastModified()
        val size = source.length()
        require(size <= 1024L * FAMILY_CHUNK_BYTES) { "File exceeds LAN chunk inventory limit" }
        val cached = fingerprints[source.absolutePath]
        if (cached != null && cached.bytes == size && cached.modifiedAt == modifiedAt &&
            cached.chunks.all { File(chunks, it).isFile }
        ) {
            cached.chunks.forEach { objects[it] = File(chunks, it) }
            return cached
        }
        val hashes = arrayListOf<String>()
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(FAMILY_CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                var count = 0
                while (count < buffer.size) {
                    val got = input.read(buffer, count, buffer.size - count)
                    if (got < 0) break
                    count += got
                }
                if (count == 0) break
                val digest = sha256Of(buffer.copyOf(count))
                val chunk = File(chunks, digest)
                if (!chunk.exists()) {
                    val temp = File.createTempFile("chunk-", ".part", chunks)
                    try { temp.writeBytes(buffer.copyOf(count)); check(temp.renameTo(chunk)) } finally { temp.delete() }
                }
                objects[digest] = chunk
                hashes += digest
            }
        }
        check(source.length() == size && source.lastModified() == modifiedAt) { "File changed during sync capture" }
        val result = LanFamilyFile(source.relativeTo(root).invariantSeparatorsPath, size, modifiedAt, hashes)
        fingerprints[source.absolutePath] = result
        return result
    }

    private suspend fun captureConfig(key: String, value: JsonElement, objects: MutableMap<String, File>): LanFamilyObject {
        val assets = linkedMapOf<String, LanFamilyFile>()
        val mappings = linkedMapOf<String, String>()
        for (url in strings(value).filter { it.startsWith("file://") }.distinct()) {
            val source = File(normalizeLocalFileUrl(app.filesDir, url).removePrefix("file://")).canonicalFile
            val allowed = listOf("upload", "images", "avatars", "backgrounds").firstOrNull {
                source.path.startsWith(File(app.filesDir, it).canonicalPath + File.separator)
            }
            require(allowed != null && source.isFile) { "Unavailable assistant asset" }
            val asset = captureFile(File(app.filesDir, allowed).canonicalFile, source, objects)
            val digest = sha256Of(asset.chunks.joinToString("").encodeToByteArray())
            val extension = source.extension.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,12}")) } ?: "bin"
            val path = "$allowed/lan-$digest-${asset.modifiedAt}.$extension"
            assets[path] = asset.copy(path = path)
            mappings[url] = "file:///rikka-family-assets/$path"
        }
        return LanFamilyObject(key, rewrite(value, mappings), files = assets.values.sortedBy { it.path })
    }

    suspend fun apply(
        local: LanSyncInventory,
        plan: LanMergePlan,
        staging: LanObjectStore,
    ): LanSyncReport {
        val entries = plan.pull.filterNot { it.key.startsWith("conversation:") }
        val deletions = plan.deleteLocal.filterNot { it.key.startsWith("conversation:") }
        val marks = plan.tombstones.filterNot { it.key.startsWith("conversation:") }
        val conflicts = plan.conflicts.filterNot { it.loser.key.startsWith("conversation:") }
        if (entries.isEmpty() && deletions.isEmpty() && marks.isEmpty() && conflicts.isEmpty()) return LanSyncReport()
        val state = read().toMutableMap()
        val captured = local.manifest.objects.associateBy { it.key }
        val incoming = entries.associateWith { entry ->
            val source = requireNotNull(staging.completed(entry.sha256) ?: local.files[entry.sha256])
            check(source.length() == entry.size && hashLanFile(source) == entry.sha256)
            JsonInstant.decodeFromString<LanFamilyObject>(source.readText()).also { value ->
                require(value.key == entry.key && value.files.flatMap { it.chunks }.toSet() == entry.attachmentShas.toSet())
                validate(value)
            }
        }
        val targets = incoming.values.flatMap { it.files }
        targets.forEach { file ->
            var bytes = 0L
            for (digest in file.chunks) {
                val chunk = requireNotNull(staging.completed(digest) ?: local.files[digest])
                check(hashLanFile(chunk) == digest)
                bytes += chunk.length()
            }
            require(bytes == file.bytes)
        }
        // Retain complete losing objects before applying any family update, including locally edited files.
        val recovery = File(app.filesDir, "lan-family-conflicts/${UUID.randomUUID()}").apply { check(mkdirs()) }
        for (key in incoming.keys.map { it.key } + deletions.map { it.key }) {
            val expected = captured[key]
            check(state[key]?.digest == expected?.sha256 || expected == null) { "Family changed during transfer" }
            val descriptor = state[key]?.descriptor ?: continue
            File(recovery, key.replace(':', '-') + ".json").writeText(JsonInstant.encodeToString(descriptor))
            descriptor.files.forEach { file ->
                if (descriptor.value != null) {
                    file.chunks.forEach { digest ->
                        val source = requireNotNull(local.files[digest])
                        val saved = File(recovery, digest)
                        if (!saved.exists()) source.copyTo(saved)
                    }
                    return@forEach
                }
                val target = resolve(descriptor, file.path)
                check(matches(target, file)) { "File changed during sync" }
                if (target.isFile) {
                    val saved = File(recovery, key.replace(':', '-') + "/" + file.path)
                    check(saved.parentFile!!.mkdirs() || saved.parentFile!!.isDirectory)
                    target.copyTo(saved)
                }
            }
        }
        for (conflict in conflicts) {
            val entry = conflict.loser
            val source = requireNotNull(staging.completed(entry.sha256) ?: local.files[entry.sha256])
            check(hashLanFile(source) == entry.sha256)
            source.copyTo(File(recovery, "loser-${entry.sha256}.json"), overwrite = true)
            entry.attachmentShas.forEach { digest ->
                val content = requireNotNull(staging.completed(digest) ?: local.files[digest])
                check(hashLanFile(content) == digest)
                val destination = File(recovery, digest)
                if (!destination.exists()) content.copyTo(destination)
            }
        }
        val destinations = incoming.values.flatMap { descriptor -> descriptor.files.map { resolve(descriptor, it.path) } } +
            (entries.map { it.key } + deletions.map { it.key }).flatMap { key ->
                state[key]?.descriptor?.let { descriptor ->
                    if (descriptor.value == null) descriptor.files.map { resolve(descriptor, it.path) } else emptyList()
                }.orEmpty()
            }
        val backups = destinations.distinctBy { it.path }.associate { file ->
            val relative = file.relativeTo(app.filesDir.canonicalFile).invariantSeparatorsPath
            val backup = if (file.exists()) {
                val saved = File(recovery, "rollback-${UUID.randomUUID()}")
                file.copyTo(saved)
                check(saved.setLastModified(file.lastModified()))
                saved.name
            } else null
            relative to backup
        }
        val currentSettings = settings.settingsFlow.first { !it.init }
        val currentConfig = currentSettings.assistants.associate { "assistant:${it.id}" to JsonInstant.encodeToJsonElement(Assistant.serializer(), it) } +
            currentSettings.lorebooks.associate { "lorebook:${it.id}" to JsonInstant.encodeToJsonElement(Lorebook.serializer(), it) }
        val after = incoming.filterValues { it.value != null }.map { (entry, value) -> entry.key to localized(value) }.toMap() +
            deletions.filter { it.key.startsWith("assistant:") || it.key.startsWith("lorebook:") }.associate { it.key to null }
        val undo = LanFamilyPending(recovery.name, state.toMap(), backups, after.keys.associateWith { currentConfig[it] }, after,
            currentSettings.removedAssistantIds, incoming.values.flatMap { descriptor ->
                descriptor.files.map { file -> resolve(descriptor, file.path).relativeTo(app.filesDir.canonicalFile)
                    .invariantSeparatorsPath to file }
            }.toMap())
        val marker = pending.startWrite()
        try {
            marker.write(JsonInstant.encodeToString(undo).encodeToByteArray())
            pending.finishWrite(marker)
        } catch (failure: Throwable) {
            pending.failWrite(marker)
            throw failure
        }
        try {
            for ((entry, descriptor) in incoming) {
                descriptor.files.forEach { file ->
                    val target = resolve(descriptor, file.path)
                    if (descriptor.value == null) {
                        val old = state[entry.key]?.descriptor?.files?.firstOrNull { it.path == file.path }
                        check(if (old == null) !target.exists() else matches(target, old)) { "File changed before application" }
                    }
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    val atomic = AtomicFile(target)
                    val output = atomic.startWrite()
                    try {
                        for (digest in file.chunks) requireNotNull(staging.completed(digest) ?: local.files[digest])
                            .inputStream().use { it.copyTo(output) }
                        atomic.finishWrite(output)
                        check(target.setLastModified(file.modifiedAt))
                    } catch (failure: Throwable) {
                        atomic.failWrite(output)
                        throw failure
                    }
                }
                if (entry.key.startsWith("assistant:") || entry.key.startsWith("lorebook:")) {
                    applyConfig(descriptor, state[entry.key]?.descriptor, delete = false)
                }
                if (entry.key.startsWith("workspace:")) ensureWorkspace(descriptor)
                if (entry.key.startsWith("skill:")) {
                    val oldFiles = state[entry.key]?.descriptor?.files.orEmpty()
                    oldFiles.filter { old -> descriptor.files.none { it.path == old.path } }.forEach {
                        resolve(descriptor, it.path).let { target -> check(!target.exists() || target.delete()) }
                    }
                }
                state[entry.key] = LanFamilyRevision(entry.sha256, entry.updateTime, descriptor = descriptor)
                write(state)
            }
            for (deletion in deletions) {
                val old = state[deletion.key] ?: continue
                when (deletion.key.substringBefore(':')) {
                    "assistant", "lorebook" -> applyConfig(old.descriptor, old.descriptor, delete = true)
                    else -> old.descriptor.files.forEach { file ->
                        val target = resolve(old.descriptor, file.path)
                        check(!target.exists() || target.delete())
                    }
                }
                state[deletion.key] = old.copy(deletedAt = deletion.deletedAt)
                write(state)
            }
            marks.forEach { mark ->
                val old = state[mark.key] ?: LanFamilyRevision("", 0, descriptor = LanFamilyObject(mark.key))
                state[mark.key] = old.copy(deletedAt = maxOf(old.deletedAt, mark.deletedAt))
            }
            write(state)
            pending.delete()
        } catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { recover() }
            throw failure
        }
        return LanSyncReport(entries.count { captured[it.key] == null }, entries.count { captured[it.key] != null },
            deletions.size, plan.conflicts.count { !it.winner.key.startsWith("conversation:") })
    }

    private suspend fun matches(file: File, expected: LanFamilyFile, checkTime: Boolean = true): Boolean {
        if (!file.isFile || file.length() != expected.bytes || checkTime && file.lastModified() != expected.modifiedAt) return false
        val hashes = arrayListOf<String>()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(FAMILY_CHUNK_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size == 0) break
                hashes += sha256Of(buffer.copyOf(size))
            }
        }
        return hashes == expected.chunks
    }

    private suspend fun applyConfig(value: LanFamilyObject, previous: LanFamilyObject?, delete: Boolean) {
        when (value.key.substringBefore(':')) {
            "assistant" -> {
                val received = JsonInstant.decodeFromJsonElement(Assistant.serializer(), localized(value))
                require(value.key == "assistant:${received.id}")
                val before = settings.settingsFlow.first { !it.init }.assistants.firstOrNull { it.id == received.id }
                if (before != null && before != received) {
                    val portable = captureConfig(value.key, JsonInstant.encodeToJsonElement(Assistant.serializer(), before), mutableMapOf())
                    check(portable == previous) { "Assistant changed during sync" }
                }
                settings.update { current ->
                    val live = current.assistants.firstOrNull { it.id == received.id }
                    check(live == before || live == received) {
                        "Assistant changed during sync"
                    }
                    current.copy(
                        assistants = current.assistants.filterNot { it.id == received.id } + if (delete) emptyList() else listOf(received),
                        removedAssistantIds = if (delete) current.removedAssistantIds + received.id else current.removedAssistantIds - received.id,
                    )
                }
            }
            "lorebook" -> {
                val received = JsonInstant.decodeFromJsonElement(Lorebook.serializer(), localized(value))
                require(value.key == "lorebook:${received.id}")
                val before = settings.settingsFlow.first { !it.init }.lorebooks.firstOrNull { it.id == received.id }
                if (before != null && before != received) {
                    val portable = captureConfig(value.key, JsonInstant.encodeToJsonElement(Lorebook.serializer(), before), mutableMapOf())
                    check(portable == previous) { "Lorebook changed during sync" }
                }
                settings.update { current ->
                    val live = current.lorebooks.firstOrNull { it.id == received.id }
                    check(live == before || live == received) {
                        "Lorebook changed during sync"
                    }
                    current.copy(lorebooks = current.lorebooks.filterNot { it.id == received.id } + if (delete) emptyList() else listOf(received))
                }
            }
        }
    }

    private fun localized(descriptor: LanFamilyObject): JsonElement {
        val mapping = descriptor.files.associate { file ->
            "file:///rikka-family-assets/${file.path}" to "file://${resolve(descriptor, file.path).absolutePath}"
        }
        return rewrite(requireNotNull(descriptor.value), mapping)
    }

    private suspend fun recover() {
        if (!pending.baseFile.exists()) return
        val undo = pending.openRead().bufferedReader().use { JsonInstant.decodeFromString<LanFamilyPending>(it.readText()) }
        require(runCatching { UUID.fromString(undo.recovery) }.isSuccess)
        val recovery = File(app.filesDir, "lan-family-conflicts/${undo.recovery}")
        for ((relative, saved) in undo.files) {
            require(relative.split('/').all { it != ".." && it != "." } && !relative.startsWith('/') && '\\' !in relative)
            require(saved == null || saved.startsWith("rollback-") && '/' !in saved && '\\' !in saved)
            val root = app.filesDir.canonicalFile
            val target = File(root, relative)
            require(target.canonicalPath.startsWith(root.path + File.separator) && target.canonicalFile == target.absoluteFile)
            if (target.exists()) {
                val written = undo.written[relative]
                val backup = saved?.let { File(recovery, it) }
                val unchanged = backup?.isFile == true && hashLanFile(target) == hashLanFile(backup)
                // An external edit made after an interrupted apply remains authoritative on this device.
                if (!unchanged && (written == null || !matches(target, written, checkTime = false))) continue
            }
            if (saved == null) check(!target.exists() || target.delete()) else {
                require(saved.startsWith("rollback-") && '/' !in saved && '\\' !in saved)
                val backup = File(recovery, saved)
                val atomic = AtomicFile(target)
                val output = atomic.startWrite()
                try {
                    backup.inputStream().use { it.copyTo(output) }
                    atomic.finishWrite(output)
                    check(target.setLastModified(backup.lastModified()))
                } catch (failure: Throwable) {
                    atomic.failWrite(output)
                    throw failure
                }
            }
        }
        settings.settingsFlow.first { !it.init }
        settings.update { config ->
            var assistants = config.assistants
            var books = config.lorebooks
            val restoredAssistants = hashSetOf<kotlin.uuid.Uuid>()
            for ((key, before) in undo.before) {
                if (key.startsWith("assistant:")) {
                    val id = kotlin.uuid.Uuid.parse(key.substringAfter(':'))
                    val live = assistants.firstOrNull { it.id == id }
                    if (live?.let { JsonInstant.encodeToJsonElement(Assistant.serializer(), it) } == undo.after[key]) {
                        restoredAssistants += id
                        assistants = assistants.filterNot { it.id == id } + listOfNotNull(before?.let {
                            JsonInstant.decodeFromJsonElement(Assistant.serializer(), it)
                        })
                    }
                } else {
                    val id = kotlin.uuid.Uuid.parse(key.substringAfter(':'))
                    val live = books.firstOrNull { it.id == id }
                    if (live?.let { JsonInstant.encodeToJsonElement(Lorebook.serializer(), it) } == undo.after[key]) {
                        books = books.filterNot { it.id == id } + listOfNotNull(before?.let {
                            JsonInstant.decodeFromJsonElement(Lorebook.serializer(), it)
                        })
                    }
                }
            }
            config.copy(assistants = assistants, lorebooks = books,
                removedAssistantIds = (config.removedAssistantIds - restoredAssistants) +
                    undo.removedAssistants.intersect(restoredAssistants))
        }
        write(undo.revisions)
        pending.delete()
    }

    private fun validate(value: LanFamilyObject) {
        val family = value.key.substringBefore(':')
        require(value.files.size <= 10_000 && value.files.map { it.path }.distinct().size == value.files.size)
        when (family) {
            "assistant", "lorebook" -> {
                require(value.value != null)
                val refs = strings(value.value).filter { it.startsWith("file://") }.toSet()
                require(refs == value.files.map { "file:///rikka-family-assets/${it.path}" }.toSet())
                value.files.forEach { asset ->
                    val expected = sha256Of(asset.chunks.joinToString("").encodeToByteArray())
                    require(asset.path.matches(Regex("(upload|images|avatars|backgrounds)/lan-$expected-${asset.modifiedAt}\\.[a-zA-Z0-9]{1,12}")))
                }
            }
            "skill" -> require(value.key == lanFamilyKey(family, value.name) && value.files.any { it.path == "SKILL.md" })
            "workspace" -> require(value.files.size == 1 && WorkspaceBackupHelper.isValidRoot(value.root) &&
                value.workspaceId != null && runCatching { UUID.fromString(value.workspaceId) }.isSuccess &&
                value.key == lanFamilyKey(family, "${value.root}/${value.files.single().path}"))
            else -> error("Unsupported family")
        }
        value.files.forEach { file ->
            require(file.bytes >= 0 && file.modifiedAt >= 0 && file.chunks.size <= 1024 && file.chunks.all(::isLanDigest))
            resolve(value, file.path)
        }
    }

    private fun resolve(value: LanFamilyObject, path: String): File {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.split('/').all { it.isNotBlank() && it != "." && it != ".." })
        val appRoot = app.filesDir.canonicalFile
        val root = when (value.key.substringBefore(':')) {
            "assistant", "lorebook" -> {
                require(path.substringBefore('/') in setOf("upload", "images", "avatars", "backgrounds"))
                appRoot
            }
            "skill" -> {
                requireNotNull(skills.getSkillDir(value.name))
                File(appRoot, "skills/${value.name}")
            }
            "workspace" -> {
                require(WorkspaceBackupHelper.isValidRoot(value.root))
                File(appRoot, "workspaces/${value.root}/files")
            }
            else -> error("Family has no files")
        }
        require(!Files.isSymbolicLink(root.toPath()))
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot == appRoot || canonicalRoot.path.startsWith(appRoot.path + File.separator))
        require(root.absoluteFile == canonicalRoot) { "Symbolic sync root in ${value.key.substringBefore(':')}" }
        val target = File(canonicalRoot, path)
        require(target.canonicalFile.path.startsWith(root.canonicalPath + File.separator) && target.absoluteFile == target.canonicalFile)
        return target
    }

    private suspend fun ensureWorkspace(value: LanFamilyObject) {
        val dao = database.workspaceDao()
        val existing = dao.getByRoot(value.root)
        require(existing == null || !existing.isSsh())
        if (existing == null) {
            val now = System.currentTimeMillis()
            require(dao.getById(requireNotNull(value.workspaceId)) == null) { "Workspace identity already has a different root" }
            dao.upsert(WorkspaceEntity(id = value.workspaceId,
                name = value.name, root = value.root, createdAt = now, updatedAt = now))
        }
    }
}

internal fun lanFamilyKey(family: String, identity: String): String =
    "$family:${UUID.nameUUIDFromBytes(identity.encodeToByteArray())}"

private fun strings(value: JsonElement): List<String> = when (value) {
    is JsonObject -> value.values.flatMap(::strings)
    is JsonArray -> value.flatMap(::strings)
    is JsonPrimitive -> if (value.isString) listOfNotNull(value.contentOrNull) else emptyList()
}

private fun rewrite(value: JsonElement, mapping: Map<String, String>): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { rewrite(it.value, mapping) })
    is JsonArray -> JsonArray(value.map { rewrite(it, mapping) })
    is JsonPrimitive -> mapping[value.contentOrNull]?.let(::JsonPrimitive) ?: value
}
