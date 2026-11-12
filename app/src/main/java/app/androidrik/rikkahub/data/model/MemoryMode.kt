package app.androidrik.rikkahub.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = MemoryModeSerializer::class)
enum class MemoryMode(val key: String) {
    ENTRIES("entries"),
    FILES("files"),
}

object MemoryModeSerializer : KSerializer<MemoryMode> {
    override val descriptor = PrimitiveSerialDescriptor("MemoryMode", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MemoryMode) = encoder.encodeString(value.key)

    override fun deserialize(decoder: Decoder): MemoryMode {
        val key = decoder.decodeString()
        return MemoryMode.entries.firstOrNull { it.key == key } ?: MemoryMode.ENTRIES
    }
}
