package app.androidrik.rikkahub.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        // Copy on IO with cancellation checks between chunks. Never register a partial file.
        val temp = createAtomicTempFile(target)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to open input stream for $uri")
            input.use { source ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!temp.renameTo(target)) throw IOException("Failed to save file from $uri")
        } finally {
            temp.delete()
        }
        try {
            currentCoroutineContext().ensureActive()
            createManagedFileEntity(
                folder = folder,
                file = target,
                displayName = resolvedName,
                mimeType = resolvedMime,
            )
        } catch (t: Throwable) {
            // 落库失败不能留孤儿文件
            target.delete()
            throw t
        }
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        // 空 ByteArray 是合法内容（例如空文本文件），这里只把「写失败」当失败
        if (!writeBytesAtomically(target, bytes)) {
            throw IOException("Failed to save file ($displayName) under $folder")
        }
        try {
            createManagedFileEntity(
                folder = folder,
                file = target,
                displayName = displayName,
                mimeType = mimeType,
            )
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
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
        // 空字符串是合法内容（空文本附件），只把「写失败」当失败
        if (!writeFileAtomically(target, text)) {
            throw IOException("Failed to save text file ($displayName) under $folder")
        }
        try {
            createManagedFileEntity(
                folder = folder,
                file = target,
                displayName = displayName,
                mimeType = mimeType,
            )
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
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
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: error("Failed to open input stream for $uri")
            // 原子写入：拷贝失败不留 0 字节/半截头像；失败时返回 null，调用方保持原头像
            if (!inputStream.use { writeStreamAtomically(file, it) }) {
                error("Failed to save avatar from $uri")
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
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                // 原子写入：流为 null / 拷贝失败都不登记（旧实现会留下 0 字节孤儿文件）
                if (!inputStream.use { writeStreamAtomically(file, it) }) {
                    error("Failed to save file from $uri")
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

    /**
     * 把内存里的字节数组存成聊天附件（落 `upload/` 并登记 managed_files），返回 `file://` Uri。
     *
     * [displayName]/[mimeType] 决定文件扩展名与登记信息；画板导出的 JPEG 草图必须走这里传对类型，
     * 不能一律写成 image.png。每次都用新的 UUID 文件名，不会覆盖已有附件。
     */
    fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        displayName: String = "image.png",
        mimeType: String = "image/png",
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            // 本方法所有调用点都是图片/媒体字节（画板草图、工作区读图、base64 转换）：
            // 0 字节图片只会让下游渲染继续失败，显式报错比产出一个「成功」的空附件更可诊断。
            if (byteArray.isEmpty()) {
                throw IOException("Refusing to save empty attachment ($displayName)")
            }
            val fileName = buildUuidFileName(displayName = displayName, mimeType = mimeType)
            val file = dir.resolve(fileName)
            if (!writeBytesAtomically(file, byteArray)) {
                Log.e(TAG, "createChatFilesByByteArrays: Failed to save $displayName (${byteArray.size} bytes)")
                Logging.log(TAG, "createChatFilesByByteArrays: outcome=failed mime=$mimeType bytes=${byteArray.size}")
                throw IOException("Failed to save attachment ($displayName)")
            }
            val newUri = file.toUri()
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = displayName,
                mimeType = mimeType
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            // P1-6：只要消息里还留着 `data:` 图片，ConversationRepository 的
            // `require(... none { it.hasBase64Part() })` 就会让**整轮回复**写不进库
            // （用户看到回复，重启后消失）。超限/解码失败一律换可见占位文本，
            // 文本部分照常落库；Tool.output 里嵌套的图片由 sanitizeBase64ImageParts 递归处理。
            val parts = sanitizeBase64ImageParts(
                parts = message.parts,
                convert = { base64Payload ->
                    val sourceByteArray = Base64.decode(base64Payload.toByteArray())
                    val bitmap = decodeSampledBitmap(sourceByteArray)
                    try {
                        val byteArray = FileUtils.compressBitmapToPng(bitmap)
                        if (byteArray.isEmpty()) error("Compressed image is empty")
                        createChatFilesByByteArrays(listOf(byteArray)).firstOrNull()?.toString()
                    } finally {
                        // 显式回收：转换链在 IO 线程高频执行，不依赖 GC 回收 native 像素内存
                        bitmap.recycle()
                    }
                },
                onOmit = { reason ->
                    Log.w(TAG, "convertBase64ImagePartToLocalFile: omitted(${reason.name})")
                    Logging.log(TAG, "convertBase64ImagePartToLocalFile: outcome=omitted reason=${reason.name}")
                },
            )
            message.copy(parts = parts)
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
        // 跳过原子写入的中间文件（未登记、进程被杀才可能残留），否则统计虚高
        val visibleFiles = files.filterNot { it.name.startsWith(ATOMIC_TEMP_PREFIX) }
        val count = visibleFiles.size
        val size = visibleFiles.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        // 空文本是合法附件（照常保存）；写失败才显式报错，避免半成品被登记
        if (!writeFileAtomically(file, text)) {
            throw IOException("Failed to save pasted text file")
        }
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
        // data URI 只取 payload；非 data URI 按裸 base64 处理（保持原有语义）
        val data = if (base64Data.startsWith("data:")) {
            val markerIndex = base64Data.indexOf(";base64,")
            if (markerIndex < 0) {
                throw IOException("Invalid base64 image data URI")
            }
            base64Data.substring(markerIndex + ";base64,".length)
        } else {
            base64Data
        }

        val byteArray = try {
            Base64.decode(data.toByteArray())
        } catch (e: Exception) {
            throw IOException("Invalid base64 image data", e)
        }
        // 图片必须非空：0 字节文件只会让下游缩略图/预览继续失败，显式失败更可诊断
        if (byteArray.isEmpty()) {
            throw IOException("Invalid base64 image data: empty payload")
        }
        val file = File(filePath)
        val parent = file.parentFile
        if (parent == null) {
            file.writeBytes(byteArray)
        } else if (!writeBytesAtomically(file, byteArray)) {
            throw IOException("Failed to save image file: $filePath")
        }
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

            image.startsWith("content:") -> {
                // SAF/相册来源：先落到自己的 cache 再导出（导出接口只吃 File）
                val file = copyImageToCache(image) ?: error("Invalid image format")
                activityContext.exportImageFile(activity, file)
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

    /**
     * 把消息里的一张图片落到可分享的临时文件，并返回它的 FileProvider URI（失败返回 null）。
     *
     * 为什么要**先拷贝进 cacheDir**再分享，而不是把原 URI 直接塞进 ACTION_SEND：
     * - `content://`（SAF / 相册）的读授权是**临时**的，接收方不一定拿得到；进程重启后
     *   原授权还可能失效（本仓上传路径一律把字节拷进 `filesDir/upload`，但历史数据/
     *   分享进来的数据仍可能是 content://）；
     * - `data:image/...;base64` 与 `http(s)` 本来就不是文件；
     * - `filesDir/images/`（生图落盘目录）不在 FileProvider 暴露范围内
     *   （见 res/xml/file_paths.xml，只暴露 upload/tool_outputs/logs/exports 与 cache），
     *   统一走 cache 才不会抛 IllegalArgumentException。
     */
    suspend fun createImageShareUri(image: String): Uri? = withContext(Dispatchers.IO) {
        val file = copyImageToCache(image) ?: return@withContext null
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure {
            Log.e(TAG, "createImageShareUri: FileProvider failed for $file", it)
        }.getOrNull()
    }

    /**
     * 把图片从任意来源（content: / file: / 绝对路径 / data: / http）拷贝进
     * `cacheDir/images_share`，供「分享」「导出」这类只吃文件的场景使用。
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun copyImageToCache(image: String): File? {
        return runCatching {
            val dir = context.cacheDir.resolve("images_share").apply { mkdirs() }
            val target = File(dir, "share_${System.currentTimeMillis()}.png")
            when {
                image.startsWith("data:image") -> {
                    val data = image.substringAfter("base64,")
                    target.writeBytes(Base64.decode(data.toByteArray()))
                }

                image.startsWith("content:") -> {
                    val input = context.contentResolver.openInputStream(image.toUri())
                        ?: error("Cannot open stream for $image")
                    input.use { source -> target.outputStream().use { source.copyTo(it) } }
                }

                image.startsWith("file:") -> {
                    val source = image.toUri().toFile()
                    if (source.absolutePath != target.absolutePath) source.copyTo(target, overwrite = true)
                }

                image.startsWith("/") -> {
                    val source = File(image)
                    if (source.absolutePath != target.absolutePath) source.copyTo(target, overwrite = true)
                }

                image.startsWith("http") -> {
                    val connection = URL(image).openConnection() as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.inputStream.use { source -> target.outputStream().use { source.copyTo(it) } }
                    connection.disconnect()
                }

                else -> error("Unsupported image source")
            }
            target
        }.onFailure {
            // 只记前 64 个字符：data: 图片的 URL 可能极长
            Log.e(TAG, "copyImageToCache failed for ${image.take(64)}", it)
        }.getOrNull()
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile && !it.name.startsWith(ATOMIC_TEMP_PREFIX) }
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
            val file = getFile(entity)
            if (file.exists() && !file.delete()) throw IOException("无法删除文件：${entity.displayName}")
        }
        repository.deleteById(id) > 0
    }

    suspend fun rename(id: Long, displayName: String): Boolean = withContext(Dispatchers.IO) {
        repository.rename(id, displayName)
    }

    suspend fun importMany(folder: String, uris: List<Uri>): List<ManagedFileOperationResult> =
        withContext(Dispatchers.IO) {
            runManagedFileOperations(uris.distinct(), { it.toString() }, { uri ->
                getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
            }) { uri -> saveManagedFromUri(folder, uri) }
        }

    suspend fun deleteMany(files: List<ManagedFileEntity>): List<ManagedFileOperationResult> =
        withContext(Dispatchers.IO) {
            runManagedFileOperations(files.distinctBy { it.id }, { it.id.toString() }, { it.displayName }) {
                check(delete(it.id)) { "文件已不存在" }
            }
        }

    suspend fun renameMany(files: List<ManagedFileEntity>, names: Map<String, String>): List<ManagedFileOperationResult> =
        withContext(Dispatchers.IO) {
            runManagedFileOperations(files.distinctBy { it.id }, { it.id.toString() }, { it.displayName }) { file ->
                val name = requireNotNull(names[file.id.toString()]) { "未提供新名称" }
                check(rename(file.id, name)) { "文件已不存在" }
            }
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

/** A batch is best-effort per item, but cancellation always stops and propagates. */
data class ManagedFileOperationResult(val key: String, val name: String, val error: String? = null)

internal suspend fun <T> runManagedFileOperations(
    items: List<T>,
    key: (T) -> String,
    name: (T) -> String,
    operation: suspend (T) -> Unit,
): List<ManagedFileOperationResult> = items.map { item ->
    currentCoroutineContext().ensureActive()
    var label = key(item)
    try {
        label = name(item)
        operation(item)
        ManagedFileOperationResult(key(item), label)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ManagedFileOperationResult(key(item), label, failure.message ?: failure.javaClass.simpleName)
    }
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
     * [FilesManager.createChatFilesByContents] 落进 [UPLOAD]，而「设置 → 文件管理」
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
     * 媒体创作（图片/视频生成）的文件区：`media_creation/<sessionId>/…`。
     *
     * 与 [UPLOAD] 分开的理由同 avatars：产物由生成记录本身引用，不该被「清理聊天文件」连带删除；
     * 目录按会话分子目录，故备份按递归目录打包（见 `BACKED_UP_FILE_FOLDERS`）。
     */
    const val MEDIA_CREATION = "media_creation"

    /**
     * 导出留存区（AI/用户导出的对话文件副本；经「设置 → 文件管理」可见、可再分享）。
     *
     * 与 `tool_outputs`（会话级临时产物、启动即清）不同：本目录是**用户想留的产物**，
     * 故按 TTL 清理而非启动清空。见 `docs/design/design-export-retention.md`。
     */
    const val EXPORTS = "exports"

    /**
     * 恢复前自动安全备份（design-backup-manifest-and-safety §3.4）：恢复含数据库的备份前，
     * 先把当前 settings+数据库快照打包到这里，滚动保留 3 份——恢复失败的后悔药。
     * 派生数据（由 [app.androidrik.rikkahub.data.sync.BackupArchiveEngine] 生成），
     * 不进备份（见 `EXCLUDED_FILE_FOLDERS`）。
     */
    const val SAFETY_BACKUP = "safety_backup"
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
