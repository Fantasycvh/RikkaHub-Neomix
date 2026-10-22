package app.androidrik.rikkahub.data.plugin.js

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Plugin-private JSON storage. Missing keys return Kotlin null; stored JSON null is [kotlinx.serialization.json.JsonNull].
 *
 * Quotas count UTF-8 bytes, including JSON escaping/quotes for values and the complete JSON
 * object for the plugin total. Invalid arguments/quotas throw [IllegalArgumentException];
 * corrupt files and filesystem failures throw [IOException], never reset existing data.
 * [root] is exclusively owned by this store; plugin IDs never become path components.
 */
class PluginKeyValueStore(private val root: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "plugin_kv"))

    suspend fun get(pluginId: String, key: String): JsonElement? = access {
        validateKey(key)
        read(pluginId)[key]
    }

    suspend fun set(pluginId: String, key: String, value: JsonElement) = access {
        validateKey(key)
        require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) {
            "Value exceeds $MAX_VALUE_BYTES UTF-8 bytes"
        }
        val values = read(pluginId).toMutableMap()
        values[key] = value
        require(values.size <= MAX_KEYS) { "Plugin storage exceeds $MAX_KEYS keys" }
        val bytes = JsonObject(values).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PLUGIN_BYTES) { "Plugin storage exceeds $MAX_PLUGIN_BYTES UTF-8 bytes" }
        write(pluginId, bytes)
    }

    suspend fun remove(pluginId: String, key: String) = access {
        validateKey(key)
        val values = read(pluginId).toMutableMap()
        if (values.containsKey(key)) {
            values.remove(key)
            write(pluginId, JsonObject(values).toString().toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun clear(pluginId: String) = access {
        // Validate first: even clear must not silently overwrite a corrupt file.
        if (read(pluginId).isNotEmpty()) write(pluginId, "{}".toByteArray(Charsets.UTF_8))
    }

    suspend fun keys(pluginId: String): List<String> = access {
        read(pluginId).keys.toList()
    }

    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        // Shared across instances as well: read-modify-write must not lose another instance's update.
        mutex.withLock { block() }
    }

    private fun file(pluginId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(root, "$name.json")
    }

    private fun read(pluginId: String): JsonObject {
        val file = file(pluginId)
        if (!file.exists()) return JsonObject(emptyMap())
        if (file.length() > MAX_PLUGIN_BYTES) throw IOException("Plugin storage file exceeds quota")
        val bytes = file.readBytes()
        if (bytes.size > MAX_PLUGIN_BYTES) throw IOException("Plugin storage file exceeds quota")
        return try {
            val values = Json.parseToJsonElement(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject
                ?: throw IOException("Plugin storage must contain a JSON object")
            require(values.size <= MAX_KEYS) { "Too many stored keys" }
            values.forEach { (key, value) ->
                validateKey(key)
                require(value.toString().toByteArray(Charsets.UTF_8).size <= MAX_VALUE_BYTES) {
                    "Stored value exceeds quota"
                }
            }
            values
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid plugin storage file", e)
        }
    }

    private fun write(pluginId: String, bytes: ByteArray) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create plugin storage directory")
        val target = file(pluginId)
        val temporary = File.createTempFile("${target.name}.", ".tmp", root)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // No delete-before-rename or non-atomic fallback: failure must leave the original intact.
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun validateKey(key: String) {
        require(key.isNotEmpty()) { "Key must not be empty" }
        require(key.toByteArray(Charsets.UTF_8).size <= MAX_KEY_BYTES) {
            "Key exceeds $MAX_KEY_BYTES UTF-8 bytes"
        }
    }

    private companion object {
        val mutex = Mutex()
        const val MAX_KEY_BYTES = 128
        const val MAX_VALUE_BYTES = 64 * 1024
        const val MAX_PLUGIN_BYTES = 256 * 1024
        const val MAX_KEYS = 128
    }
}
