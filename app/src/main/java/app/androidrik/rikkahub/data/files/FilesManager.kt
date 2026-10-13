package app.androidrik.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.utils.exportImage
import app.androidrik.rikkahub.utils.exportImageFile
import app.androidrik.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "FilesManager"

        /** 模型输出 base64 图片字符数上限（约 20MB base64）：超大 decode 曾 OOM */
        private const val MAX_BASE64_IMAGE_CHARS = 20_000_000

        /** 位图最长边采样上限：超 4096px 时降采样，避免全尺寸解码 OOM */
        private const val MAX_BITMAP_DIMENSION = 4096
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    /**
     * 原子写入留存区（`files/exports`）：**先写临时文件、再 rename**。
     *
     * 为什么不能用 [saveManagedFromBytes]（它直接 `writeBytes` 到最终名）：导出会复用同名
     * （如 JSON 的 `"$safeTitle.json"`），若"先删旧再写新"，写入中途失败会**毁掉前一份**。
     * 本方法写临时文件再原子 rename，故中途失败只会留下可被清理的临时文件，最终名始终完好
     * （见 `docs/design/design-export-retention.md` §4.2）。
     *
     * 目标名仍是 UUID（`managed_files.relative_path` 有 UNIQUE 索引，不能同名覆盖），
     * 人类可读名存 `displayName`。
     *
     * @return 落库后的 `ManagedFileEntity`（含 id，供后续分享/删除）。
     */
    suspend fun saveExportAtomically(
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
        folder: String = FileFolders.EXPORTS,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder).apply { if (!exists()) mkdirs() }
        val finalFile = File(dir, FileUtils.buildUuidFileName(displayName, mimeType))
        val tempFile = File(dir, "$EXPORT_TEMP_PREFIX${finalFile.name}")
        try {
            tempFile.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                // fsync：确保数据落盘后再 rename，避免断电/崩溃留下 0 字节的最终文件
                (output as? FileOutputStream)?.fd?.sync()
            }
            // 同目录内 rename 是原子的（同一文件系统）；失败则抛错，最终名未被触碰
            check(tempFile.renameTo(finalFile)) {
                "Failed to finalize export file: ${finalFile.absolutePath}"
            }
        } finally {
            // 无论成败都清掉临时文件（成功时它已被 rename 移走，exists() 为 false）
            if (tempFile.exists()) tempFile.delete()
        }
        createManagedFileEntity(
            folder = folder,
            file = finalFile,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    /**
     * 保存一张**头像**图片（助手头像 / 用户头像），落 [FileFolders.AVATARS]。
     *
     * 与 [createChatFilesByContents] 的唯一区别就是目录——头像不能进 `upload/`，
     * 否则「清理聊天文件」的整目录删除会连它一起删掉（上游 issue #2127）。
     * 单独一个方法而不是给原方法加 `folder` 参数：两条路径的**生命周期语义不同**
     * （头像=持久配置，聊天文件=可清历史），调用点也不该随手切换。
     *
     * @return 落盘后的 `file://` Uri；失败返回 null（调用方保持原头像不变）。
     */
    fun createAvatarImage(uri: Uri): Uri? {
        val dir = context.filesDir.resolve(FileFolders.AVATARS)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return runCatching {
            val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "avatar"
            val sourceMime = getFileMimeType(uri)
            val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("Failed to open input stream for $uri")
            inputStream.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            trackManagedFile(
                folder = FileFolders.AVATARS,
                file = file,
                displayName = sourceName,
                mimeType = sourceMime ?: guessMimeType(file, sourceName),
            )
            file.toUri()
        }.onFailure {
            Log.e(TAG, "createAvatarImage: Failed to save avatar from $uri", it)
            Logging.log(
                TAG,
                "createAvatarImage: Failed to save avatar from $uri ${it.message} | ${it.stackTraceToString()}",
            )
        }.getOrNull()
    }

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackManagedFile(
                    folder = FileFolders.UPLOAD,
                    file = file,
                    displayName = sourceName,
                    mimeType = guessedMime
                )
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(parts = convertBase64PartsRecursively(message.parts))
        }

    /**
     * 递归转换 parts 中的 base64 图片为本地文件，含工具结果嵌套 parts（D3）：
     * 旧实现只转 message 顶层 parts，UIMessagePart.Tool.output 里的图片漏转，
     * 后续 require/base64 渲染失败导致消息丢失。
     */
    private suspend fun convertBase64PartsRecursively(parts: List<UIMessagePart>): List<UIMessagePart> =
        parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    if (part.url.startsWith("data:image")) {
                        val base64Data = part.url.substringAfter("base64,")
                        if (base64Data.length > MAX_BASE64_IMAGE_CHARS) {
                            // 超大 base64 跳过转换（保留原 part，避免 decode OOM）
                            part
                        } else {
                            val sourceByteArray = Base64.decode(base64Data.toByteArray())
                            val bitmap = decodeSampledBitmap(sourceByteArray)
                            val byteArray = FileUtils.compressBitmapToPng(bitmap)
                            val urls = createChatFilesByByteArrays(listOf(byteArray))
                            Log.i(
                                TAG,
                                "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                            )
                            part.copy(
                                url = urls.first().toString(),
                            )
                        }
                    } else {
                        part
                    }
                }

                is UIMessagePart.Tool -> part.copy(
                    output = convertBase64PartsRecursively(part.output)
                )

                else -> part
            }
        }

    /** 采样解码位图：先读边界，超 4096px 时 inSampleSize 降采样 + RGB_565，防全尺寸解码 OOM */
    private fun decodeSampledBitmap(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_BITMAP_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_BITMAP_DIMENSION
        ) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("Failed to decode image")
    }

    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            getRelativePathInFilesDir(file)?.let { relativePaths.add(it) }
            if (file.exists()) {
                file.delete()
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve(FileFolders.IMAGES)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                removed += repository.deleteByPath(entity.relativePath)
            }
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk) {
            runCatching { getFile(entity).delete() }
        }
        repository.deleteById(id) > 0
    }

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }

        var allDeletedFromDisk = true
        entries.orEmpty().forEach { entry ->
            if (!runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                allDeletedFromDisk = false
            }
        }

        if (allDeletedFromDisk) {
            repository.deleteByFolder(folder)
            return@withContext true
        }

        repository.listByFolder(folder).first().forEach { entity ->
            if (!getFile(entity).exists()) {
                repository.deleteById(entity.id)
            }
        }
        false
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

object FileFolders {
    const val UPLOAD = "upload"
    const val IMAGES = "images"
    const val SKILLS = "skills"
    const val BUILTIN_SKILLS = "builtin_skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
    const val LOGS = "logs"

    /**
     * 用户配置的头像（助手头像 + 用户头像）。
     *
     * **为什么单独一个目录**（2026-10-13，上游 issue #2127）：此前头像经
     * [FilesManager.createChatFilesByContents] 落进 [UPLOAD]，而「设置 → 存储管理 → 文件管理」
     * 里对 `upload` 的「清理」走 [FilesManager.deleteAll] ⇒ **整目录 `deleteRecursively()`**，
     * 于是用户主动配置、且**正被助手引用**的头像被当成「历史文件」一并删掉，助手头像当场变空。
     * 头像与聊天附件是两类东西（前者是持久配置、后者是可清历史），目录分开后
     * 「清理聊天文件」在语义上就不可能碰到头像。
     *
     * **存量兼容**：老版本的头像文件仍在 `upload/` 下、引用也指向那里，由
     * `AvatarMigration` 在启动时搬进本目录并改写引用（搬不动的留在原处并打日志）。
     */
    const val AVATARS = "avatars"

    /**
     * 导出留存区（AI/用户导出的对话文件副本；经「设置 → 文件管理」可见、可再分享）。
     *
     * 与 `tool_outputs`（会话级临时产物、启动即清）不同：本目录是**用户想留的产物**，
     * 故按 TTL 清理而非启动清空。见 `docs/design/design-export-retention.md`。
     */
    const val EXPORTS = "exports"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
