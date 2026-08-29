package app.androidrik.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.tikz.PdfToBitmap
import app.androidrik.rikkahub.tikz.TectonicNative
import app.androidrik.rikkahub.tikz.TikzRenderer
import app.androidrik.rikkahub.tikz.TikzTelemetry
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.exportImage

/**
 * TikZ 渲染三级回退链：
 * 1. TikzRenderer（纯 Kotlin 子集 → SVG → AndroidSVG 矢量，最快）
 * 2. TectonicNative（真 TeX 引擎 → PDF → PdfRenderer 位图，完整性最高）
 * 3. 原始代码展示
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
                // 失败分布埋点：成功也记（不含代码内容），提供计算失败率的分母。
                // 注意 darkMode 切换会重渲染一次，计数会有少量重复，可接受。
                TikzTelemetry.recordSuccess(context, it.length)
                Log.i("TikzJax", "svg ok, len=${it.length}: $it")
                it
            },
            onFailure = { e ->
                TikzTelemetry.record(context, code, e)
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
    // Native TeX 回退状态：子集渲染失败时异步触发真编译（不阻塞组合）
    // 矢量路径优先：XDV → dvisvgm → SVG（与第 1 级同为矢量）；
    // dvisvgm 失败时降级 PDF → PdfRenderer 位图
    var nativeSvg by remember(code) { mutableStateOf<String?>(null) }
    var nativeBitmap by remember(code) { mutableStateOf<ImageBitmap?>(null) }
    var nativeFailed by remember(code) { mutableStateOf(false) }
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt().coerceAtLeast(1)
    }

    LaunchedEffect(code, svg == null) {
        if (svg == null && !nativeFailed && TectonicNative.available) {
            // 隔离进程执行（tectonic 与 dvisvgm 各占一个 isolatedProcess，根治互污染）
            val (svgText, bitmap) = withContext(Dispatchers.IO) {
                val xdv = TectonicNative.compileIsolated(context, code, "xdv")
                val svgText = xdv?.let { TectonicNative.xdvToSvgIsolated(context, it) }
                if (svgText != null) {
                    svgText to null
                } else {
                    val pdf = TectonicNative.compileIsolated(context, code)
                    null to pdf?.let { PdfToBitmap.renderFirstPage(it, widthPx) }
                }
            }
            nativeSvg = svgText
            nativeBitmap = bitmap
            if (svgText == null && bitmap == null) nativeFailed = true
        }
    }

    // AndroidSVG 对象（渲染用 renderToCanvas 而非 renderToPicture+drawPicture：
    // 硬件加速 Canvas 不支持 drawPicture，FAQ 明确 —— 直接渲染到 Canvas 才可靠）
    fun parseSvgText(text: String?, tag: String): SVG? {
        if (text == null) return null
        val parsed = runCatching { SVG.getFromString(text) }
        parsed.onFailure { Log.w("TikzJax", "$tag getFromString FAIL: ${it.message}", it) }
        val obj = parsed.getOrNull() ?: return null
        // 渲染前数值校验：documentWidth/Height 必须有限、为正、且不超合理上限。
        // 真机曾出现 TikZ→SVG height=4.58e11（12 万公里画布），aspectRatio 趋 0 →
        // Compose 高度 = width/ratio 溢出 Int.MAX → `Can't represent height of 2147483647` 崩溃。
        // 超限直接拒收（降级显示，不进入布局）。
        val w = obj.documentWidth
        val h = obj.documentHeight
        return if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f || w > MAX_SVG_DIMENSION || h > MAX_SVG_DIMENSION) {
            Log.w("TikzJax", "$tag svg dimension abnormal, reject: w=$w h=$h")
            null
        } else {
            Log.i("TikzJax", "$tag getFromString -> OK (w=$w h=$h)")
            obj
        }
    }

    val svgObj = remember(svg) { parseSvgText(svg, "tier1") }
    val nativeSvgObj = remember(nativeSvg) { parseSvgText(nativeSvg, "native") }
    val displaySvgObj = svgObj ?: nativeSvgObj
    val displaySvgText: String? = svg ?: nativeSvg
    // native SVG 黑色笔画在暗色主题不可见：渲染时衬白底（第 1 级已做 darkMode 颜色替换）
    val whiteBackground = svg == null && nativeSvg != null
    val aspectRatio = remember(displaySvgObj) {
        val w = displaySvgObj?.documentWidth ?: 1f
        val h = displaySvgObj?.documentHeight ?: 1f
        if (w > 0f && h > 0f) w / h else 1f
    }

    if (displaySvgObj != null && displaySvgText != null) {
        Column(modifier = modifier) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                if (whiteBackground) drawRect(color = Color.White)
                drawIntoCanvas { canvas ->
                    val c = canvas.nativeCanvas
                    val w = displaySvgObj.documentWidth
                    val scale = if (w > 0f) size.width / w else 1f
                    c.save()
                    c.scale(scale, scale)
                    displaySvgObj.renderToCanvas(c)
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
                                val s = SVG.getFromString(displaySvgText) ?: error("parse failed")
                                val exportScale = 2f
                                val w = (s.documentWidth * exportScale).toInt().coerceAtLeast(1)
                                val h = (s.documentHeight * exportScale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                if (whiteBackground) bitmap.eraseColor(android.graphics.Color.WHITE)
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
    } else if (nativeBitmap != null) {
        // 回退链第 2 级：Tectonic 编译 PDF → PdfRenderer 位图
        Image(
            bitmap = nativeBitmap!!,
            contentDescription = null,
            modifier = modifier
                .fillMaxWidth()
                .padding(4.dp),
        )
    } else {
        // 回退链第 3 级：渲染失败降级显示原始 TikZ 代码
        Text(
            text = code,
            fontSize = 12.sp,
            modifier = modifier.padding(8.dp),
        )
    }
}
