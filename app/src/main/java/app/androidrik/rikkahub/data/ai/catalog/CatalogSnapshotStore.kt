package app.androidrik.rikkahub.data.ai.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.SecureRandom
import kotlin.uuid.Uuid

internal interface CatalogSnapshotStore {
    val salt: ByteArray
    fun ids(): Set<Uuid>
    fun load(id: Uuid): ProviderCatalogState?
    fun save(id: Uuid, state: ProviderCatalogState): Boolean
    fun remove(id: Uuid)
}

@Serializable
private data class CatalogCacheFile(val version: Int = 1, val state: ProviderCatalogState)

/** Private noBackupFilesDir storage; no Settings or credentials; local model UUIDs are used only for missing-incident acknowledgements. */
internal class FileCatalogSnapshotStore(private val directory: File) : CatalogSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }
    override val salt: ByteArray = runCatching {
        File(directory, "scope-salt").readBytes().also { require(it.size == 32) }
    }.getOrNull() ?: SecureRandom().generateSeed(32).also {
        runCatching { atomicWrite(File(directory, "scope-salt"), it) }
    } // cache failure must not prevent app startup; a corrupt salt is replaced

    override fun ids(): Set<Uuid> = directory.listFiles().orEmpty().mapNotNull { file ->
        if (file.extension != "json") null else runCatching { Uuid.parse(file.nameWithoutExtension) }.getOrNull()
    }.toSet()

    override fun load(id: Uuid): ProviderCatalogState? = runCatching {
        val file = File(directory, "$id.json")
        require(file.length() in 1..32_000_000)
        json.decodeFromString<CatalogCacheFile>(file.readText()).also { require(it.version == 1) }.state
    }.getOrNull()

    override fun save(id: Uuid, state: ProviderCatalogState): Boolean = runCatching {
        atomicWrite(File(directory, "$id.json"), json.encodeToString(CatalogCacheFile(state = state)).toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)

    override fun remove(id: Uuid) { File(directory, "$id.json").delete() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, target.name + ".tmp")
        try {
            temporary.outputStream().use { stream -> stream.write(bytes); stream.flush() }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }
}
