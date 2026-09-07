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
import app.androidrik.rikkahub.tikz.MupdfSvgBridge
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.tikz.PdfToBitmap
import app.androidrik.rikkahub.tikz.TectonicNative
import app.androidrik.rikkahub.tikz.TikzSvgCache
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.exportImage

/**
 * TikZ 渲染三级回退链：
 * 1. TectonicNative（真 TeX 引擎全语法）：XDV→dvisvgm SVG 矢量优先
 * 2. PDF → PdfRenderer 位图降级
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

    // 统一走 native 引擎（真 TeX，全语法支持）：XDV→dvisvgm SVG 矢量优先，
    // 失败降级 PDF→PdfRenderer 位图。原第①级 TikzRenderer 子集渲染已移除
    //（"部分解析画错图"的静默错误风险 > 毫秒级渲染收益；复杂图仍需 3-6s 编译）
    var nativeSvg by remember(code) { mutableStateOf<String?>(null) }
    var nativeBitmap by remember(code) { mutableStateOf<ImageBitmap?>(null) }
    var nativeFailed by remember(code) { mutableStateOf(false) }
    val widthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx().toInt().coerceAtLeast(1)
    }

    // mupdf 不可用 / 失败的降级链：PDF 位图（原主路径原样保留）
    suspend fun fallbackBitmap(
        context: android.content.Context,
        code: String,
        widthPx: Int,
    ): Pair<String?, ImageBitmap?> {
        val pdf = TectonicNative.compileIsolated(context, code)
        return null to pdf?.let { PdfToBitmap.renderFirstPage(it, widthPx) }
    }

    LaunchedEffect(code) {
        if (!nativeFailed && TectonicNative.available) {
            // 缓存命中：历史消息秒开（TeX 确定性输出，sha256(code) 内容寻址）
            val cached: String? = withContext(Dispatchers.IO) { TikzSvgCache.get(context, code) }
            if (cached != null) {
                nativeSvg = cached
                return@LaunchedEffect
            }
            // SVG 来源：mupdf（PDF 内嵌字体→<text>，文字保真）优先，失败降级 PDF 位图。
            // mupdf 链只打包 arm64-v8a，x86_64 模拟器上自动落位图链。
            val (svgText, bitmap) = withContext(Dispatchers.IO) {
                if (MupdfSvgBridge.available) {
                    // mupdf 链：compile pdf（tectonic 必内嵌字体）= 单次编译即得
                    // <text> 保留型 SVG
                    val pdf = TectonicNative.compileIsolated(context, code)
                    val mupdfSvg = pdf?.let { MupdfSvgBridge.pdfToSvg(it) }
                    if (mupdfSvg != null) {
                        TikzSvgCache.put(context, code, mupdfSvg)
                        mupdfSvg to null
                    } else {
                        fallbackBitmap(context, code, widthPx)
                    }
                } else {
                    fallbackBitmap(context, code, widthPx)
                }
            }
            nativeSvg = svgText
            nativeBitmap = bitmap
            if (svgText == null && bitmap == null) nativeFailed = true
        }
    }

    // AndroidSVG 对象（渲染用 renderToCanvas 而非 renderToPicture+drawPicture：
    // 硬件加速 Canvas 不支持 drawPicture，FAQ 明确 —— 直接渲染到 Canvas 才可靠）
    fun parseSvgText(text: String?): SVG? {
        if (text == null) return null
        val parsed = runCatching { SVG.getFromString(text) }
        parsed.onFailure { Log.w("TikzJax", "getFromString FAIL: ${it.message}", it) }
        val obj = parsed.getOrNull() ?: return null
        // 渲染前数值校验（布局层防线，非渲染引擎兼容）：documentWidth/Height 必须
        // 有限、为正、且不超合理上限——畸形 SVG 的 aspectRatio 趋 0 会使 Compose
        // 高度 = width/ratio 溢出 Int.MAX 崩溃，超限直接拒收走降级链
        val w = obj.documentWidth
        val h = obj.documentHeight
        return if (!w.isFinite() || !h.isFinite() || w <= 0f || h <= 0f || w > MAX_SVG_DIMENSION || h > MAX_SVG_DIMENSION) {
            Log.w("TikzJax", "svg dimension abnormal, reject: w=$w h=$h")
            null
        } else {
            Log.i("TikzJax", "getFromString -> OK (w=$w h=$h)")
            obj
        }
    }

    val svgObj = remember(nativeSvg) { parseSvgText(nativeSvg) }
    val aspectRatio = remember(svgObj) {
        val w = svgObj?.documentWidth ?: 1f
        val h = svgObj?.documentHeight ?: 1f
        if (w > 0f && h > 0f) w / h else 1f
    }

    if (svgObj != null && nativeSvg != null) {
        Column(modifier = modifier) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                // native SVG 黑色笔画在暗色主题不可见：衬白底
                drawRect(color = Color.White)
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
                                val s = SVG.getFromString(nativeSvg) ?: error("parse failed")
                                val exportScale = 2f
                                val w = (s.documentWidth * exportScale).toInt().coerceAtLeast(1)
                                val h = (s.documentHeight * exportScale).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bitmap.eraseColor(android.graphics.Color.WHITE)
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
