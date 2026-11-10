package app.androidrik.rikkahub.data.files

import android.content.Context
import android.graphics.Typeface
import android.util.AtomicFile
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.theme.AppSkinPreset
import app.androidrik.rikkahub.ui.theme.AppSkinRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.util.zip.*

@Serializable
data class SkinPackageManifest(
    val formatVersion: Int = 1, val id: String, val name: String, val version: Int,
    val skin: AppSkin, val font: String? = null,
)

/** Hash-only ZIP paths and bounded decompression, before any persistent mutation. */
data class SkinPackageArchive(val manifest: SkinPackageManifest, val assets: Map<String, ByteArray>) {
    fun encode(): ByteArray {
        validate()
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                put("manifest.json", packageJson.encodeToString(SkinPackageManifest.serializer(), manifest).toByteArray())
                assets.forEach { (id, data) -> put("assets/$id", data) }
            }; out.toByteArray()
        }
    }
    fun validate() {
        require(manifest.formatVersion == 1 && manifest.id.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "皮肤包 ID 或格式版本无效" }
        require(manifest.version > 0 && manifest.name.isNotBlank() && manifest.name.length <= 128)
        require(manifest.skin.typography.customFontPath.isEmpty()) { "皮肤包不能包含本机路径" }
        require(manifest.skin.panels().all { (it.background?.stack?.layers?.size ?: 0) <= BubbleStack.MAX_LAYERS })
        require(manifest.skin.resources == manifest.skin.resources.normalized())
        val required = manifest.skin.textureIds() + listOfNotNull(manifest.font)
        require(required == assets.keys && required.all(::isBubbleTextureId)) { "皮肤包资源缺失或包含未引用资源" }
        require(assets.size <= 32 && assets.values.sumOf { it.size.toLong() } <= MAX_BYTES)
        require(assets.all { textureDigest(it.value) == it.key && it.value.size <= SkinFontStore.MAX_BYTES }) { "皮肤资源哈希或大小无效" }
        require((manifest.font != null) == (manifest.skin.typography.family == ChatFontFamily.CUSTOM)) { "自定义字体必须附带资源" }
        require(packageJson.encodeToString(SkinPackageManifest.serializer(), manifest).toByteArray().size <= AppearanceArchive.MAX_MANIFEST)
    }
    companion object {
        const val MAX_BYTES = 48 * 1024 * 1024
        fun decode(bytes: ByteArray): SkinPackageArchive {
            require(bytes.size <= MAX_BYTES + 512 * 1024) { "皮肤包过大" }
            var manifest: SkinPackageManifest? = null
            val assets = linkedMapOf<String, ByteArray>()
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && names.add(entry.name)) { "皮肤包存在重复条目" }
                if (entry.name == "manifest.json") {
                    manifest = packageJson.decodeFromString<SkinPackageManifest>(readLimited(zip, AppearanceArchive.MAX_MANIFEST).toString(Charsets.UTF_8))
                } else {
                    require(entry.name.startsWith("assets/") && assets.size < 32)
                    val id = entry.name.removePrefix("assets/")
                    require(isBubbleTextureId(id)) { "皮肤包资源路径无效" }
                    val data = readLimited(zip, SkinFontStore.MAX_BYTES)
                    total += data.size; require(total <= MAX_BYTES)
                    assets[id] = data
                }
            } }
            return SkinPackageArchive(requireNotNull(manifest), assets).also { it.validate() }
        }
    }
}

private val packageJson = Json { encodeDefaults = true }

/** Atomic catalog commit. Resources are shared immutable hashes and retained on uninstall for existing overrides. */
class SkinPackageStore(context: Context, private val registry: AppSkinRegistry = AppSkinRegistry.shared) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, FileFolders.SKIN_PACKAGES)
    private val index get() = AtomicFile(File(directory, "index.json"))
    fun list(): List<SkinPackageManifest> = synchronized(lock) { readCatalog() }
    fun reload() = synchronized(lock) { publish(readCatalog()) }
    private fun readCatalog(): List<SkinPackageManifest> {
        if (!File(directory, "index.json").exists() && !File(directory, "index.json.bak").exists()) return emptyList()
        return index.openRead().use { packageJson.decodeFromString<List<SkinPackageManifest>>(readLimited(it, 8 * 1024 * 1024).toString(Charsets.UTF_8)) }.also { items ->
            require(items.size <= 32 && items.map { it.id }.distinct().size == items.size)
            require(items.all { it.formatVersion == 1 && it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) &&
                it.version > 0 && it.name.length in 1..128 && it.skin.typography.customFontPath.isEmpty() &&
                (it.font == null || isBubbleTextureId(it.font)) })
        }
    }
    private fun fontFile(id: String) = File(context.filesDir, "${FileFolders.FONTS}/skin_pack_$id.ttf")
    private fun publish(items: List<SkinPackageManifest>) {
        registry.replaceInstalled(items.map { item ->
            val skin = item.skin.normalized()
            AppSkinPreset("package:${item.id}", "${item.name} · v${item.version}", if (item.font == null) skin else skin.copy(
                typography = skin.typography.copy(customFontPath = "${FileFolders.FONTS}/${fontFile(item.font).name}")))
        })
    }
    private fun save(items: List<SkinPackageManifest>) {
        directory.mkdirs()
        val stream = index.startWrite()
        try { stream.write(packageJson.encodeToString(items).toByteArray()); index.finishWrite(stream) }
        catch (failure: Throwable) { index.failWrite(stream); throw failure }
        publish(items)
    }
    fun install(bytes: ByteArray): SkinPackageManifest = synchronized(lock) {
        val archive = SkinPackageArchive.decode(bytes)
        val item = archive.manifest
        val items = readCatalog()
        val previous = items.firstOrNull { it.id == item.id }
        require(previous == null || item.version > previous.version) { "升级版本必须高于已安装版本" }
        require(previous != null || items.size < 32) { "最多安装 32 套皮肤" }
        val textures = BubbleTextureStore(context)
        // Validate every asset before installing any. Catalog remains unchanged if validation/storage fails.
        item.skin.textureIds().forEach { textures.validate(it, archive.assets.getValue(it)) }
        item.font?.let { id ->
            val temp = File.createTempFile("skin-font-", ".tmp", context.cacheDir)
            try { temp.writeBytes(archive.assets.getValue(id)); Typeface.createFromFile(temp) } finally { temp.delete() }
        }
        item.skin.textureIds().forEach { textures.install(it, archive.assets.getValue(it)) }
        item.font?.let { id ->
            val target = fontFile(id); target.parentFile!!.mkdirs()
            val atomic = AtomicFile(target); val stream = atomic.startWrite()
            try { stream.write(archive.assets.getValue(id)); atomic.finishWrite(stream) }
            catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        }
        save(items.filterNot { it.id == item.id } + item)
        item
    }
    fun uninstall(id: String) = synchronized(lock) { save(readCatalog().filterNot { it.id == id }) }
    fun exportInstalled(id: String): ByteArray = synchronized(lock) {
        val item = readCatalog().first { it.id == id }
        SkinPackageArchive(item, item.skin.textureIds().associateWith { BubbleTextureStore(context).bytes(it) } +
            listOfNotNull(item.font).associateWith { fontFile(it).inputStream().use { input -> readLimited(input, SkinFontStore.MAX_BYTES) } }).encode()
    }
    fun exportSkin(id: String, name: String, version: Int, skin: AppSkin): ByteArray {
        val textures = skin.textureIds().associateWith { BubbleTextureStore(context).bytes(it) }
        val fontBytes = if (skin.typography.family == ChatFontFamily.CUSTOM) {
            val root = File(context.filesDir, FileFolders.FONTS).canonicalFile
            val file = File(context.filesDir, skin.typography.customFontPath).canonicalFile
            require(file.parentFile == root && file.isFile) { "字体必须位于字体存储目录" }
            file.inputStream().use { readLimited(it, SkinFontStore.MAX_BYTES) }
        } else null
        val font = fontBytes?.let(::textureDigest)
        val portable = skin.normalized().copy(typography = skin.typography.normalized().copy(customFontPath = ""))
        return SkinPackageArchive(SkinPackageManifest(id = id, name = name, version = version, skin = portable, font = font),
            textures + if (fontBytes != null) mapOf(requireNotNull(font) to fontBytes) else emptyMap()).encode()
    }
    companion object { private val lock = Any() }
}
