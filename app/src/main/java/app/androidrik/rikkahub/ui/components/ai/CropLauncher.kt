package app.androidrik.rikkahub.ui.components.ai

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toFile
import com.dokar.sonner.ToastType
import com.yalantis.ucrop.UCrop
import com.yalantis.ucrop.UCropActivity
import app.androidrik.common.android.Logging
import app.androidrik.common.android.appTempFolder
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
 * **为何是 JPEG 而非 PNG（#1892）**：`withMaxResultSize(4096, 4096)` 允许到 4096²，
 * PNG 编码这个量级的位图既慢又占内存，正是「裁剪确认卡顿」的成因。JPEG 也是 UCrop 自身的
 * 默认值（`UCropActivity.DEFAULT_COMPRESS_FORMAT`），此前的 PNG 才是在覆盖默认。
 *
 * **已知取舍**：JPEG 无 alpha 通道，透明像素会合成成黑色。影响仅限**本地/头像显示**；
 * 发给模型的请求路径无变化——`FileEncoder.compressAndEncode` 本就无条件压 JPEG q85。
 */
internal object CropOutput {
    val FORMAT: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG

    /** 与 UCrop 自身默认值一致（`UCropActivity.DEFAULT_COMPRESS_QUALITY`）。 */
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

@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true
): Pair<ActivityResultLauncher<Intent>, (Uri) -> Unit> {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var cropOutputUri by remember { mutableStateOf<Uri?>(null) }

    val cropActivityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                cropOutputUri?.let { croppedUri ->
                    onCroppedImageReady(croppedUri)
                }
            }

            UCrop.RESULT_ERROR -> {
                val error = result.data?.let { UCrop.getError(it) }
                Logging.log(
                    "CropLauncher",
                    "crop failed: ${error?.message} | ${error?.stackTraceToString()}"
                )
                toaster.show(
                    "Failed to crop image: ${error?.message ?: "unknown error"}",
                    type = ToastType.Error
                )
            }
        }
        cropOutputUri?.toFile()?.delete()
        cropOutputUri = null
        onCleanup?.invoke()
    }

    val launchCrop: (Uri) -> Unit = { sourceUri ->
        val outputFile = File(context.appTempFolder, CropOutput.fileName(System.currentTimeMillis()))
        cropOutputUri = Uri.fromFile(outputFile)

        var crop = UCrop.of(sourceUri, cropOutputUri!!).withOptions(UCrop.Options().apply {
            setFreeStyleCropEnabled(freeStyleCropEnabled)
            setAllowedGestures(
                UCropActivity.SCALE, UCropActivity.ROTATE, UCropActivity.NONE
            )
            setCompressionFormat(CropOutput.FORMAT)
            setCompressionQuality(CropOutput.QUALITY)
        }).withMaxResultSize(4096, 4096)
        aspectRatio?.let { (x, y) ->
            crop = crop.withAspectRatio(x, y)
        }
        val cropIntent = crop.getIntent(context)

        cropActivityLauncher.launch(cropIntent)
    }

    return Pair(cropActivityLauncher, launchCrop)
}
