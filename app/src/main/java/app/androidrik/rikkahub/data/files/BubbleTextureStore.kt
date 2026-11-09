package app.androidrik.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import app.androidrik.rikkahub.data.model.isBubbleTextureId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max

/** Content-addressed, bounded PNG assets. No absolute paths are stored in appearance settings. Call on IO. */
class BubbleTextureStore(private val context: Context) {
    private val directory get() = File(context.filesDir, FileFolders.BUBBLE_TEXTURES)

    fun importImage(uri: Uri): String = context.contentResolver.openInputStream(uri)?.use {
        importImage(readLimited(it, MAX_INPUT_BYTES))
    } ?: error("无法读取纹理图片")

    fun importImage(bytes: ByteArray): String {
        require(bytes.size <= MAX_INPUT_BYTES) { "纹理图片过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "请选择 PNG、JPEG 或 WebP 图片" }
        require(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "纹理图片尺寸无效" }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > MAX_SIDE) inSampleSize *= 2
        }
        var bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "图片解码失败" }
        try {
            val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
            val matrix = Matrix().apply {
                if (exif?.isFlipped == true) postScale(-1f, 1f)
                exif?.rotationDegrees?.takeIf { it != 0 }?.let { postRotate(it.toFloat()) }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            val png = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "图片转换失败" }
                out.toByteArray()
            }
            val id = textureDigest(png)
            install(id, png)
            return id
        } finally { bitmap.recycle() }
    }

    fun file(id: String): File? = id.takeIf(::isBubbleTextureId)?.let { File(directory, "$it.png") }
    fun bytes(id: String): ByteArray = requireNotNull(file(id)) { "纹理 ID 无效" }.inputStream().use { readLimited(it, MAX_ASSET_BYTES) }

    fun validate(id: String, bytes: ByteArray) {
        require(isBubbleTextureId(id) && bytes.size <= MAX_ASSET_BYTES && textureDigest(bytes) == id) { "纹理资源校验失败" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE) { "纹理资源尺寸或格式无效" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "纹理资源损坏" }
        bitmap.recycle()
    }

    fun install(id: String, bytes: ByteArray) {
        validate(id, bytes)
        synchronized(lock) {
            val target = requireNotNull(file(id))
            directory.mkdirs()
            if (target.isFile && target.length() <= MAX_ASSET_BYTES && textureDigest(target.readBytes()) == id) return
            val temporary = File.createTempFile("texture-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes) }
                java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } finally { temporary.delete() }
        }
    }

    fun load(id: String): Bitmap? {
        synchronized(lock) { return loadLocked(id) }
    }

    fun cached(id: String): Bitmap? = file(id)?.let { synchronized(cache) { cache.get(it.absolutePath) } }

    private fun loadLocked(id: String): Bitmap? {
        val target = file(id)?.takeIf(File::isFile) ?: return null
        val key = target.absolutePath
        synchronized(cache) { cache.get(key)?.let { return it } }
        val bitmap = runCatching {
            val bytes = bytes(id)
            require(textureDigest(bytes) == id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..MAX_SIDE && bounds.outHeight in 1..MAX_SIDE)
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }.getOrNull() ?: return null
        synchronized(cache) { cache.put(key, bitmap) }
        return bitmap
    }

    companion object {
        const val MAX_SIDE = 1024
        const val MAX_INPUT_BYTES = 16 * 1024 * 1024
        const val MAX_ASSET_BYTES = 5 * 1024 * 1024
        private val lock = Any()
        private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    }
}

fun textureDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

fun readLimited(input: InputStream, limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(out.size().toLong() + n <= limit) { "文件超过允许大小" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
