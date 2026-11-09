package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.model.isBubbleTextureId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable transport shared by material and theme exports. Assets are hashes, never archive paths. */
data class AppearanceArchive(val manifest: String, val textures: Map<String, ByteArray>) {
    fun encode(): ByteArray = ByteArrayOutputStream().use { output ->
        require(manifest.toByteArray().size <= MAX_MANIFEST)
        require(textures.size <= MAX_ASSETS && textures.all {
            it.value.size <= BubbleTextureStore.MAX_ASSET_BYTES && isBubbleTextureId(it.key) && textureDigest(it.value) == it.key
        })
        require(textures.values.sumOf { it.size.toLong() } <= MAX_TOTAL)
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("manifest.json", manifest.toByteArray(Charsets.UTF_8))
            textures.forEach { (id, bytes) -> put("textures/$id.png", bytes) }
        }
        output.toByteArray()
    }

    companion object {
        const val MAX_TOTAL = 32 * 1024 * 1024
        const val MAX_MANIFEST = 256 * 1024
        const val MAX_ASSETS = 32
        fun decode(bytes: ByteArray): AppearanceArchive {
            require(bytes.size <= MAX_TOTAL + MAX_MANIFEST + 65536) { "材质包过大" }
            var manifest: String? = null
            val textures = linkedMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && names.add(entry.name)) { "材质包包含重复或无效条目" }
                    if (entry.name == "manifest.json") {
                        val data = readLimited(zip, MAX_MANIFEST)
                        manifest = data.toString(Charsets.UTF_8)
                    } else {
                        require(entry.name.startsWith("textures/") && entry.name.endsWith(".png")) { "材质包包含未知条目" }
                        val id = entry.name.removePrefix("textures/").removeSuffix(".png")
                        require(isBubbleTextureId(id) && textures.size < MAX_ASSETS) { "纹理 ID 或数量无效" }
                        val data = readLimited(zip, BubbleTextureStore.MAX_ASSET_BYTES)
                        total += data.size
                        require(total <= MAX_TOTAL && textureDigest(data) == id) { "材质包纹理校验失败" }
                        textures[id] = data
                    }
                }
            }
            return AppearanceArchive(requireNotNull(manifest) { "材质包缺少清单" }, textures)
        }
    }
}
