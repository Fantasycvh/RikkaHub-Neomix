package app.androidrik.rikkahub.data.sync.lan

import kotlinx.serialization.Serializable

internal const val LAN_FORMAT_VERSION = 1
internal const val LAN_OBJECT_MAX_BYTES = 64L * 1024 * 1024
private const val MANIFEST_MAX_ENTRIES = 10_000
private const val ATTACHMENTS_PER_CONVERSATION = 1_024
private val digestPattern = Regex("[0-9a-f]{64}")
private val logicalKeyPattern = Regex("(?:conversation|assistant):[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

@Serializable
internal data class LanManifest(
    val protocol: Int,
    val device: LanManifestDevice,
    val conversations: List<LanManifestObject> = emptyList(),
    val assistants: List<LanManifestObject> = emptyList(),
    val tombstones: List<LanTombstone> = emptyList(),
) {
    fun validate(dbVersion: Int, expectedDevice: String) {
        require(protocol == LAN_PROTOCOL && device.formatVersion == LAN_FORMAT_VERSION && device.dbVersion == dbVersion) {
            "Incompatible LAN sync version"
        }
        require(isLanDigest(device.id) && device.id == expectedDevice && validLanName(device.name))
        require(conversations.size.toLong() + assistants.size + tombstones.size <= MANIFEST_MAX_ENTRIES)
        val keys = HashSet<String>()
        conversations.forEach { entry -> require(entry.key.startsWith("conversation:") && keys.add(entry.key)); entry.validate() }
        assistants.forEach { entry ->
            require(entry.key.startsWith("assistant:") && keys.add(entry.key) && entry.attachmentShas.isEmpty())
            entry.validate()
        }
        val deleted = HashSet<String>()
        tombstones.forEach { require(logicalKeyPattern.matches(it.key) && it.deletedAt > 0 && deleted.add(it.key)) }
    }
}

@Serializable
internal data class LanManifestDevice(val id: String, val name: String, val dbVersion: Int, val formatVersion: Int)

@Serializable
internal data class LanManifestObject(
    val key: String,
    val sha256: String,
    val updateTime: Long,
    val size: Long,
    val attachmentShas: List<String> = emptyList(),
) {
    fun validate() {
        require(logicalKeyPattern.matches(key) && isLanDigest(sha256) && updateTime >= 0 && size in 1..LAN_OBJECT_MAX_BYTES)
        require(attachmentShas.size <= ATTACHMENTS_PER_CONVERSATION &&
            attachmentShas.all(::isLanDigest) && attachmentShas.toSet().size == attachmentShas.size)
    }
}

@Serializable
internal data class LanTombstone(val key: String, val deletedAt: Long)

internal fun isLanDigest(value: String): Boolean = digestPattern.matches(value)
