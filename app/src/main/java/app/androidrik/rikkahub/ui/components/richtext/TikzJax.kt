package app.androidrik.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import android.util.Log
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
private const val MAX_SVG_DIMENSION = 20_000f

@Composable
fun TikzJax(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val darkMode = LocalDarkMode.current

    val svg = remember(code, darkMode) {
        TikzRenderer.render(code, darkMode).fold(
            onSuccess = {
                Log.i("TikzJax", "svg ok, len=${it.length}: $it")
                it
            },
            onFailure = { e ->
                Log.w("TikzJax", "render failed: ${e.message}", e)
                // 完整 code 每 100 字符一行打印：logcat 单行超宽会截断，分段保证完整可见
                Log.w("TikzJax", "code len=${code.length}")
                code.chunked(100).forEachIndexed { idx, chunk ->
                    Log.w("TikzJax", "code[$idx]: $chunk")
                }
                null
            },
        )
    }
    // AndroidSVG 对象（渲染用 renderToCanvas 而非 renderToPicture+drawPicture：
    // 硬件加速 Canvas 不支持 drawPicture，FAQ 明确 —— 直接渲染到 Canvas 才可靠）
    val svgObj = remember(svg) {
        if (svg == null) {
            Log.w("TikzJax", "getFromString skipped: svg is null (render failed upstream)")
            null
        } else {
            val parsed = runCatching { SVG.getFromString(svg) }
            parsed.onFailure { Log.w("TikzJax", "getFromString FAIL: ${it.message}", it) }
            val obj = parsed.getOrNull()
            // 渲染前数值校验：documentWidth/Height 必须有限、为正、且不超合理上限。
            // 真机曾出现 TikZ→SVG height=4.58e11（12 万公里画布），aspectRatio 趋 0 →
            // Compose 高度 = width/ratio 溢出 Int.MAX → `Can't represent height of 2147483647` 崩溃。
            // 超限直接拒收（降级显示，不进入布局）。
            if (obj != null) {
                val w = obj.documentWidth
                val h = obj.documentHeight
                if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f || w > MAX_SVG_DIMENSION || h > MAX_SVG_DIMENSION) {
                    Log.w("TikzJax", "svg dimension abnormal, reject: w=$w h=$h")
                    null
                } else {
                    Log.i("TikzJax", "getFromString -> OK (w=$w h=$h)")
                    obj
                }
            } else {
                Log.w("TikzJax", "getFromString -> FAIL")
                null
            }
        }
    }
    val aspectRatio = remember(svgObj) {
        val w = svgObj?.documentWidth ?: 1f
        val h = svgObj?.documentHeight ?: 1f
        if (w > 0f && h > 0f) w / h else 1f
    }

    if (svgObj != null && svg != null) {
        Column(modifier = modifier) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    val w = svgObj.documentWidth
                    val scale = if (w > 0f) size.width / w else 1f
                    c.save()
                    c.scale(scale, scale)
                    svgObj.renderToCanvas(c)
                    c.restore()
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
