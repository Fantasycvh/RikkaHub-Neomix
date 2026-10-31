package app.androidrik.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
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
                        onCroppedImageReady(outputFile.toUri())
                    }.onFailure { error ->
                        Logging.log("CropLauncher", "crop failed: ${error.message} | ${error.stackTraceToString()}")
                        toaster.show(
                            "Failed to crop image: ${error.message ?: "unknown error"}",
                            type = ToastType.Error
                        )
                    }
                    outputFile.delete()
                    saving = false
                    finish()
                }
            },
        )
    }

    return { source = it }
}
