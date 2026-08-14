package app.androidrik.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.tikz.TikzRenderer
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.exportImage

/**
 * 纯 Java 渲染 AI 输出的 TikZ 图表：TikzRenderer → SVG 字符串 → AndroidSVG → Picture → Compose Canvas 矢量绘制。
 *
 * 与旧版（WebView + tikzjax wasm）不同：无 WebView/wasm/JS，渲染结果直接可矢量缩放；
 * SVG 字符串同时可作为导出（HTML 内嵌 / 位图）的中间产物。
 */
@Composable
fun TikzJax(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val darkMode = LocalDarkMode.current

    val svg = remember(code, darkMode) { TikzRenderer.render(code, darkMode).getOrNull() }
    val picture = remember(svg) {
        runCatching { SVG.getFromString(svg)?.renderToPicture() }.getOrNull()
    }
    val aspectRatio = remember(svg) {
        runCatching {
            val s = SVG.getFromString(svg)
            val w = s?.documentWidth ?: 1f
            val h = s?.documentHeight ?: 1f
            if (w > 0f && h > 0f) w / h else 1f
        }.getOrDefault(1f)
    }

    if (picture != null && svg != null) {
        Column(modifier = modifier) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                drawIntoCanvas { canvas ->
                    val scale = if (picture.width > 0) size.width / picture.width else 1f
                    canvas.save()
                    canvas.scale(scale, scale)
                    canvas.nativeCanvas.drawPicture(picture)
                    canvas.restore()
                }
            }

            if (activity != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = {
                            runCatching {
                                val s = SVG.getFromString(svg) ?: error("parse failed")
                                val exportScale = 2f
                                val w = (s.documentWidth * exportScale).toInt().coerceAtLeast(1)
                                val h = (s.documentHeight * exportScale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                val bitmapCanvas = android.graphics.Canvas(bitmap)
                                bitmapCanvas.scale(exportScale, exportScale)
                                s.renderToCanvas(bitmapCanvas)
                                context.exportImage(
                                    activity,
                                    bitmap,
                                    "tikz_${System.currentTimeMillis()}.png"
                                )
                                toaster.show(
                                    context.getString(R.string.mermaid_export_success),
                                    type = ToastType.Success
                                )
                            }.onFailure {
                                it.printStackTrace()
                                toaster.show(
                                    context.getString(R.string.mermaid_export_failed),
                                    type = ToastType.Error
                                )
                            }
                        },
                    ) {
                        Icon(
                            HugeIcons.Download01,
                            contentDescription = stringResource(R.string.mermaid_export)
                        )
                    }
                }
            }
        }
    } else {
        // 渲染失败：降级显示原始 TikZ 代码
        Text(
            text = code,
            fontSize = 12.sp,
            modifier = modifier.padding(8.dp),
        )
    }
}
