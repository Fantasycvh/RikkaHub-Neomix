package app.androidrik.rikkahub.ui.components.ai

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import com.dokar.sonner.ToasterState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.common.android.Logging
import app.androidrik.common.android.appTempFolder
import app.androidrik.rikkahub.ui.components.crop.ImageCropDialog
import app.androidrik.rikkahub.ui.context.LocalToaster
import java.io.File

/**
 * 裁剪产物的**编码格式与文件名的唯一真相源**（吸收上游 `7c1629d0` / #1892）。
 *
 * **为什么把两者放在一起、并由格式派生文件名**：历史实现把文件名写死 `crop_output_*.jpg`
 * 而编码用 PNG ⇒ 产物「扩展名说 JPEG、内容说 PNG」。真机实证 `files/upload/` 下 5 个 `.jpg`
 * 全部以 PNG magic（`89 50 4E 47`）开头。请求侧不受影响（[app.androidrik.ai.util.encodeBase64]
 * 嗅探文件头、再统一压成 JPEG），但错标会误导任何按扩展名判断的消费方，且让「改了格式忘了
 * 改名字」这类改动无声发生——故名字从格式**派生**，两者不可能再分叉。
 *
 * **为何是 JPEG 而非 PNG（#1892）**：裁剪结果允许到很大尺寸，PNG 编码这个量级的位图既慢又占
 * 内存，正是「裁剪确认卡顿」的成因。JPEG 也是主流裁剪器的默认值，此前的 PNG 才是在覆盖默认。
 *
 * **已知取舍**：JPEG 无 alpha 通道，透明像素会合成成黑色。影响仅限**本地/头像显示**；
 * 发给模型的请求路径无变化——`FileEncoder.compressAndEncode` 本就无条件压 JPEG q85。
 */
internal object CropOutput {
    val FORMAT: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

    /** 与 UCrop 时代保持一致的压缩质量（旧 `UCropActivity.DEFAULT_COMPRESS_QUALITY`）。 */
    const val QUALITY: Int = 90

    /** 由 [FORMAT] 派生，不写死——见类型注释里那个「名字与内容不符」的历史 bug。 */
    val EXTENSION: String = extensionOf(FORMAT)

    fun fileName(timestamp: Long): String = "crop_output_$timestamp.$EXTENSION"

    /**
     * 编码格式 → 文件扩展名。未映射的格式返回 `"bin"`——对应单测会断言配置的格式**必须**
     * 是可映射的，避免将来换成新格式（如 WEBP_*）时静默产出 `.bin`。
     */
    internal fun extensionOf(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.JPEG -> "jpg"
        Bitmap.CompressFormat.PNG -> "png"
        else -> "bin"
    }
}

/**
 * 返回一个打开裁剪对话框的函数。裁好的图片先写成临时的 JPEG 交给 [onCroppedImageReady]，
 * 回调结束后删除；不管裁剪完成还是取消，最后都会调用 [onCleanup]。
 *
 * 与上游 `2a0a831e6` 的区别：不再启动 ucrop 的 Activity，改用本仓 Compose 实现
 * [ImageCropDialog]（解码走 `ImageDecoder`/`BitmapFactory`，HEIF 与 EXIF 方向都原生支持，
 * 因此调用方不必再先把 HEIF 转成 JPEG）。
 *
 * @param aspectRatio 固定的宽高比（宽 / 高），为空时可以自由裁剪
 * @param maxResultSize 裁剪结果的长边上限（像素），透传给 [ImageCropDialog]
 */
@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Float? = null,
    maxResultSize: Int = 4096,
): (Uri) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    // 写文件期间对话框还开着，防止连点确认存出两张
    var saving by remember { mutableStateOf(false) }

    fun finish() {
        source = null
        onCleanup?.invoke()
    }

    source?.let { uri ->
        ImageCropDialog(
            image = uri,
            aspectRatio = aspectRatio,
            maxResultSize = maxResultSize,
            onDismiss = ::finish,
            onConfirm = { bitmap ->
                if (saving) return@ImageCropDialog
                saving = true
                scope.launch {
                    persistCroppedImage(context, toaster, bitmap, onCroppedImageReady)
                    saving = false
                    finish()
                }
            },
        )
    }

    return { source = it }
}

/**
 * 裁剪结果落地的共用实现：临时 JPEG → [onReady] → 删除临时文件。
 *
 * 单张 [useCropLauncher] 与批量 [useMultiCropLauncher] 共用这一份，「用哪种编码、放在哪个临时
 * 目录、失败怎么提示」只有一处真相，避免两条路径日后各改各的而漂移（历史上就有「扩展名说
 * JPEG、内容说 PNG」的 bug，见 [CropOutput]）。[onReady] 返回后临时文件立即删除，需要持久化
 * 的调用方必须在回调内当场拷走。
 */
private suspend fun persistCroppedImage(
    context: Context,
    toaster: ToasterState,
    bitmap: Bitmap,
    onReady: (Uri) -> Unit,
) {
    val outputFile = File(
        context.appTempFolder,
        CropOutput.fileName(System.currentTimeMillis()),
    )
    runCatching {
        withContext(Dispatchers.IO) {
            outputFile.outputStream().use {
                bitmap.compress(CropOutput.FORMAT, CropOutput.QUALITY, it)
            }
        }
        onReady(outputFile.toUri())
    }.onFailure { error ->
        Logging.log("CropLauncher", "crop failed: ${error.message} | ${error.stackTraceToString()}")
        toaster.show(
            "Failed to crop image: ${error.message ?: "unknown error"}",
            type = ToastType.Error
        )
    }
    outputFile.delete()
}

/**
 * 返回一个「批量裁剪」函数：把传入的 URI 列表按顺序逐张交给 [ImageCropDialog]。
 *
 * 语义：
 * - 每张确认 → 写成临时 JPEG 交给 [onImageReady] → 删临时文件 → 自动打开下一张；
 * - 全部确认完 → 调 [onDone]，参数为空列表；
 * - 用户中途取消（关闭按钮 / 返回）→ 停止队列，[onDone] 收到「当前这张 + 还没轮到的」URI
 *   列表，由调用方决定是否原样加入（多图场景不丢用户已经挑好的内容，单图场景通常丢弃）。
 *
 * **为什么不能靠在回调里链式排队**：[useCropLauncher] 的 onConfirm 顺序是「回调 → 删临时
 * 文件 → finish()」，finish 会把唯一的 source 槽清空。如果直接在 [onImageReady] 里调用
 * `launchImageCrop(nextUri)`，这次赋值会被紧随其后的 finish 覆盖，下一张图永远不显示（更糟
 * 的是队列与 UI 状态分叉）。所以这里不递归调用自己，而是把待裁剪列表作为**独立队列状态**
 * 常驻在本 composable 里：每张结束后只推进队列，由下一次重组去渲染新的 source；取消也只需
 * 清空队列并把剩余项一次性交回，不存在「回调里再进回调」的重入问题。
 *
 * @param onImageReady 每张裁剪成功后的回调；临时文件在回调返回后即删除
 * @param onDone 队列结束（全部完成或中途取消）时调用，参数是未裁剪的剩余 URI（全部完成时为空）
 * @param aspectRatio 固定的宽高比（宽 / 高），为空时可以自由裁剪
 * @param maxResultSize 裁剪结果的长边上限（像素），透传给 [ImageCropDialog]
 */
@Composable
internal fun useMultiCropLauncher(
    onImageReady: (Uri) -> Unit,
    onDone: (List<Uri>) -> Unit,
    aspectRatio: Float? = null,
    maxResultSize: Int = 4096,
): (List<Uri>) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // 一批裁剪会跨多次重组、多张图执行，必须始终调用调用方**最新**的回调；直接闭包捕获会是
    // 启动那一刻的旧 lambda（sheet 状态一变回调就重建），故用 rememberUpdatedState 兜底。
    val currentOnImageReady by rememberUpdatedState(onImageReady)
    val currentOnDone by rememberUpdatedState(onDone)
    // 待裁剪队列（不含当前正在显示的那张）。用 SnapshotStateList 而不是普通 List/ArrayDeque：
    // 它既能在多次重组之间常驻，又能在协程里改完后驱动下一帧渲染新的 source。
    val queue = remember { mutableStateListOf<Uri>() }
    var source by remember { mutableStateOf<Uri?>(null) }
    // 写文件期间对话框还开着，防止连点确认；批量场景下也用来忽略「保存中」的取消请求
    var saving by remember { mutableStateOf(false) }

    // 结束整批：先清空队列、关掉对话框，再通知调用方（调用方可能会立刻 dismiss 整个 sheet）
    fun finish(remaining: List<Uri>) {
        queue.clear()
        source = null
        currentOnDone(remaining)
    }

    // 推进到下一张；队列空了说明整批完成。只在协程里调用，不在 composition 里改状态。
    fun advance() {
        source = if (queue.isEmpty()) null else queue.removeAt(0)
        if (source == null) finish(emptyList())
    }

    // 取消当前这张：当前这张与队列里还没轮到的都交回调用方，是否加入由调用方按来源决定。
    // 保存中收到取消直接忽略——此时这张已经按裁剪结果回调过，若再退回原图会导致同一张重复加入。
    fun cancelCurrent() {
        if (saving) return
        val remaining = buildList {
            source?.let { add(it) }
            addAll(queue)
        }
        finish(remaining)
    }

    source?.let { uri ->
        ImageCropDialog(
            image = uri,
            aspectRatio = aspectRatio,
            maxResultSize = maxResultSize,
            onDismiss = ::cancelCurrent,
            onConfirm = { bitmap ->
                if (saving) return@ImageCropDialog
                saving = true
                scope.launch {
                    persistCroppedImage(context, toaster, bitmap) { currentOnImageReady(it) }
                    saving = false
                    advance()
                }
            },
        )
    }

    return { uris ->
        if (!saving) {
            // 新批次覆盖旧队列：避免上一批还没走完的残留串到这一批里
            queue.clear()
            queue.addAll(uris.drop(1))
            source = uris.firstOrNull()
            if (source == null) finish(emptyList())
        }
    }
}
