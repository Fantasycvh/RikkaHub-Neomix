package app.androidrik.rikkahub.data.files

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.core.net.toFile
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.ui.components.ai.createChatFileBySketch
import app.androidrik.rikkahub.ui.components.sketch.SketchResult
import app.androidrik.rikkahub.ui.components.sketch.SketchState
import app.androidrik.rikkahub.ui.components.sketch.loadSketchBackground
import app.androidrik.rikkahub.ui.components.sketch.toResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.java.KoinJavaComponent
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 画板 → 本地持久化的真实集成测试：走生产代码 `FilesManager.createChatFileBySketch()`
 * （编码 → 写 `filesDir/upload/` → 登记 managed_files），而不是只断言 Bitmap。
 *
 * 隔离方式：真实 [FilesManager] + **内存 Room** [AppDatabase] + [TemporaryFilesContext] 把
 * `filesDir` 指到 `cacheDir` 下的临时目录；SettingsStore 用 app 启动时 Koin 已建好的单例、
 * 且保存路径不读它。不碰用户 files/DB/settings，`@After` 删除临时目录并关闭数据库。
 *
 * 失败注入用例验证的是**真实保存失败**（返回 null，UI 才会显示 `sketch_save_failed`），
 * 与“底图读不出来”的 `load_background` 是两条不同路径，日志 marker 可区分。
 */
@RunWith(AndroidJUnit4::class)
class SketchPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var repository: FilesRepository
    private lateinit var appScope: AppScope
    private lateinit var filesManager: FilesManager

    @Before
    fun setUp() {
        root = File(context.cacheDir, "sketch-persistence-${UUID.randomUUID()}").apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FilesRepository(database.managedFileDao())
        appScope = AppScope()
        filesManager = FilesManager(
            context = TemporaryFilesContext(context, root),
            repository = repository,
            appScope = appScope,
            settingsStore = KoinJavaComponent.get(SettingsStore::class.java),
        )
    }

    @After
    fun tearDown() {
        runCatching { appScope.cancel() }
        runCatching { database.close() }
        root.deleteRecursively()
    }

    @Test
    fun realSketch_persistsPngIntoUploadAndRegistersManagedFile() = runBlocking {
        val result = sketchPng()
        val saveSuccessBefore = countSaveMarkers(success = true)

        val uri = filesManager.createChatFileBySketch(result)

        assertNotNull("real sketch save must return a file uri", uri)
        val saved = uri!!.toFile()
        val uploadDir = File(root, FileFolders.UPLOAD)
        assertTrue("saved sketch must exist on disk", saved.isFile)
        assertEquals(uploadDir.canonicalPath, saved.parentFile?.canonicalPath)
        assertEquals("png", saved.extension)
        assertTrue("saved sketch must not be empty", saved.length() > 0L)

        // 落盘的是可解码 PNG，且尺寸与画板导出一致（真实编码链路，不是只看 Bitmap）
        val decoded = requireNotNull(BitmapFactory.decodeFile(saved.absolutePath))
        try {
            assertEquals(result.bitmap.width, decoded.width)
            assertEquals(result.bitmap.height, decoded.height)
        } finally {
            decoded.recycle()
        }

        // managed_files 由 appScope 异步登记：必须真的有记录（聊天附件显示名/删除/备份都依赖它）
        val entity = awaitManagedFile("${FileFolders.UPLOAD}/${saved.name}")
        assertEquals(FileFolders.UPLOAD, entity.folder)
        assertEquals("sketch.png", entity.displayName)
        assertEquals("image/png", entity.mimeType)
        assertEquals(saved.length(), entity.sizeBytes)

        // 成功路径留下可导出的应用内 marker（stage/mime/字节数，无图片内容与 URI）
        assertTrue(
            "missing save_chat_file success marker: ${textLogs()}",
            countSaveMarkers(success = true) > saveSuccessBefore,
        )
        assertTrue(
            "success marker must carry mime/bytes/saved fields: ${textLogs()}",
            Logging.getTextLogs().any {
                it.tag == "SketchAttachment" &&
                    it.message.contains("stage=save_chat_file outcome=success") &&
                    it.message.contains("mime=image/png") &&
                    it.message.contains("bytes=") &&
                    it.message.contains("saved=true")
            },
        )

        // 再保存一次不能覆盖已有附件：每次都是新的 UUID 文件名
        val second = requireNotNull(filesManager.createChatFileBySketch(result)?.toFile())
        assertNotEquals(saved.name, second.name)
        assertTrue(saved.isFile)
        assertTrue(second.isFile)
    }

    @Test
    fun sketchOverExistingImage_persistsJpegAtBackgroundSizeAndNeverOverwritesOriginal() = runBlocking {
        val original = File(root, "originals/original.png").apply { parentFile!!.mkdirs() }
        val originalBitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        try {
            originalBitmap.eraseColor(Color.RED)
            FileOutputStream(original).use { originalBitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            originalBitmap.recycle()
        }
        val originalBytes = original.readBytes()

        // 走真实底图读取（画板“在已有图片上继续画”的同一条路径）
        val background = requireNotNull(loadSketchBackground(context, Uri.fromFile(original)))
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(1000f, 1000f))
        state.useBackground(background)
        state.beginText(Offset.Zero, width = 12f)
        state.write("mark")
        val result = state.toResult()
        assertEquals("jpg", result.extension)
        assertEquals(60, result.bitmap.width)
        assertEquals(40, result.bitmap.height)

        val saved = requireNotNull(filesManager.createChatFileBySketch(result)?.toFile())

        assertEquals("jpg", saved.extension)
        assertNotEquals(original.canonicalPath, saved.canonicalPath)
        // 原图只被读取：保存标注后的图不会覆盖/改写原文件
        assertArrayEquals(originalBytes, original.readBytes())
        val savedBytes = saved.readBytes()
        assertTrue(savedBytes.size > 2)
        assertEquals(0xFF.toByte(), savedBytes[0])
        assertEquals(0xD8.toByte(), savedBytes[1])

        val entity = awaitManagedFile("${FileFolders.UPLOAD}/${saved.name}")
        assertEquals("sketch.jpg", entity.displayName)
        assertEquals("image/jpeg", entity.mimeType)
        val decoded = requireNotNull(BitmapFactory.decodeFile(saved.absolutePath))
        try {
            assertEquals(60, decoded.width)
            assertEquals(40, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun saveFailure_returnsNullLogsFailureMarkerAndLeavesNothingBehind() = runBlocking {
        // 故障注入：upload 这个位置被一个普通文件占住，创建目录/目标文件都会失败。
        val blocked = File(root, FileFolders.UPLOAD).apply {
            parentFile!!.mkdirs()
            writeText("not-a-directory")
        }
        val result = sketchPng()
        val failedBefore = countSaveMarkers(success = false)
        val loadFailureBefore = countLoadFailureMarkers()

        val uri = filesManager.createChatFileBySketch(result)

        // 返回 null 正是 UI 显示 sketch_save_failed（“草图保存失败”）的唯一条件
        assertNull("real save failure must be reported to the caller as null", uri)
        assertTrue(blocked.isFile)
        assertEquals("not-a-directory", blocked.readText())
        assertEquals(0, repository.listByFolder(FileFolders.UPLOAD).first().size)
        assertEquals(1, root.listFiles()?.size ?: -1)

        // 真实保存失败必须留下 stage=save_chat_file 的失败 marker（与 load_background 不同）
        assertTrue(
            "missing save_chat_file failure marker: ${textLogs()}",
            countSaveMarkers(success = false) > failedBefore,
        )
        assertEquals(
            "save failure must not be logged as background load failure",
            loadFailureBefore,
            countLoadFailureMarkers(),
        )
    }

    private fun sketchPng(): SketchResult {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(800f, 800f))
        state.beginText(Offset.Zero, width = 12f)
        state.write("hello")
        return state.toResult().also {
            assertTrue("test sketch must contain rendered ink", hasInk(it.bitmap))
        }
    }

    private fun hasInk(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { pixel ->
            Color.alpha(pixel) > 200 && Color.red(pixel) < 40 && Color.green(pixel) < 40 && Color.blue(pixel) < 40
        }
    }

    // trackManagedFile 在 appScope 里异步登记，等它落库（有界等待，不做重试掩盖）
    private suspend fun awaitManagedFile(relativePath: String): ManagedFileEntity = withTimeout(5_000L) {
        var entity = repository.getByPath(relativePath)
        while (entity == null) {
            delay(20L)
            entity = repository.getByPath(relativePath)
        }
        entity!!
    }

    private fun countSaveMarkers(success: Boolean): Int =
        Logging.getTextLogs().count {
            it.tag == "SketchAttachment" &&
                it.message.contains("stage=save_chat_file outcome=${if (success) "success" else "failed"}")
        }

    private fun countLoadFailureMarkers(): Int =
        Logging.getTextLogs().count {
            it.tag == "SketchImage" && it.message.contains("stage=load_background outcome=failed")
        }

    private fun textLogs(): String = Logging.getTextLogs().joinToString(" | ") { "${it.tag}:${it.message}" }

    /** 把 filesDir 指到测试临时目录，其余（contentResolver 等）仍走真实 context。 */
    private class TemporaryFilesContext(base: Context, private val filesDir: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = filesDir
    }
}
