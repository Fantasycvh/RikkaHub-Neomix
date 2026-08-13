package app.androidrik.rikkahub.ui.components.richtext

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.webview.TIKZ_ASSET_URL
import app.androidrik.rikkahub.ui.components.webview.WebView
import app.androidrik.rikkahub.ui.components.webview.rememberWebViewState
import app.androidrik.rikkahub.utils.PdfToBitmap

/**
 * 通过 WebView + Tectonic wasm（XeTeX）渲染 AI 输出的 TikZ 图表代码。
 *
 * 触发形式：
 * - ```tikz / ```tikzpicture / ```tikzcd / ```tikztiming 代码围栏
 * - $$...$$ 块公式内嵌的 \begin{tikzpicture} / \begin{tikzcd} / \begin{axis} / \xymatrix / \begin{CD}
 *
 * 原理：Tectonic（XeTeX + xdvipdfmx）编译 TikZ → PDF，Android PdfRenderer 渲染为 Bitmap。
 * 支持中文：XeTeX 原生 UTF-8 + 方括号字体语法 \font"[simhei.ttf]" 注入字体，免 fontspec/xeCJK。
 *
 * 流程：WebView 加载 assets/html/tecton.html#fragment（真实 https origin）→ JS 解 bundle +
 * 注入 simhei.ttf + Tectonic 编译 → onPdf(base64) 回调 → PdfToBitmap 渲染 → 展示 ImageBitmap。
 */
@Composable
fun TikzJax(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var pdfBitmap by remember(code) { mutableStateOf<ImageBitmap?>(null) }
    var errorMessage by remember(code) { mutableStateOf<String?>(null) }
    var isRendering by remember(code) { mutableStateOf(true) }

    // JS 回调（@JavascriptInterface）运行在 JavaBridge 线程，Compose state 必须在主线程更新。
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // 渲染分辨率对齐屏幕物理宽度，避免 FillWidth 拉伸时模糊。
    val screenWidthPx = remember { context.resources.displayMetrics.widthPixels }

    val jsInterface = remember(code) {
        TikzJaxInterface(
            onPdf = { base64Pdf ->
                // 解析 + PdfRenderer 渲染放回调线程（阻塞但非 UI 线程），只把 state 更新切主线程。
                val pdfBytes = runCatching {
                    Base64.decode(base64Pdf, Base64.DEFAULT)
                }.getOrNull()
                val bitmap = pdfBytes?.let { PdfToBitmap.renderFirstPage(it, screenWidthPx) }
                val error = when {
                    pdfBytes == null -> "PDF 解码失败"
                    bitmap == null -> "PDF 渲染失败"
                    else -> null
                }
                mainHandler.post {
                    if (error != null) {
                        errorMessage = error
                    } else {
                        pdfBitmap = bitmap!!.asImageBitmap()
                    }
                    isRendering = false
                }
            },
            onProgress = { msg ->
                // 进度回调，暂不显示（避免闪烁），可后续用于 loading 状态
            },
            onError = { msg ->
                mainHandler.post {
                    errorMessage = msg
                    isRendering = false
                }
            }
        )
    }

    // base64url 编码 TikZ 代码，放 URL fragment
    val fragment = remember(code) {
        Base64.encodeToString(
            code.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
    val url = remember(fragment) { "$TIKZ_ASSET_URL/html/tecton.html#$fragment" }

    val webViewState = rememberWebViewState(
        url = url,
        interfaces = mapOf("AndroidInterface" to jsInterface),
        settings = {
            builtInZoomControls = false
            displayZoomControls = false
        }
    )

    Column(
        modifier = modifier
    ) {
        if (errorMessage != null) {
            Text(
                text = "TikZ 渲染失败: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        val bitmap = pdfBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .padding(vertical = 4.dp),
                contentScale = ContentScale.FillWidth,
            )
        } else if (isRendering) {
            Text(
                text = "渲染中...",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        // WebView 保持极小尺寸以执行 JS（不可见，只做编译）
        WebView(
            state = webViewState,
            modifier = Modifier.height(1.dp),
        )
    }
}

private class TikzJaxInterface(
    private val onPdf: (String) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun onPdf(base64Pdf: String) {
        onPdf(base64Pdf)
    }

    @JavascriptInterface
    fun onProgress(message: String) {
        onProgress(message)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onError(message)
    }
}
