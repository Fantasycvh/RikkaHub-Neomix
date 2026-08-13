package app.androidrik.rikkahub.ui.components.richtext

import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.View
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.webview.TIKZ_ASSET_URL
import app.androidrik.rikkahub.ui.components.webview.WebView
import app.androidrik.rikkahub.ui.components.webview.WebViewContentCache
import app.androidrik.rikkahub.ui.components.webview.rememberWebViewState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.exportImage

/**
 * 通过 WebView + 原版 tikzjax（主线程 wasm 编译 TikZ→SVG）渲染 AI 输出的 TikZ 图表代码。
 *
 * 触发形式：
 * - ```tikz / ```tikzpicture / ```tikzcd / ```tikztiming 代码围栏
 * - $$...$$ 块公式内嵌的 \begin{tikzpicture} / \begin{tikzcd} / \begin{axis} / \xymatrix / \begin{CD}
 *
 * 原理：原版 kisonecat/tikzjax 是**纯主线程架构**（无 Web Worker）——load() fetch tex.wasm +
 * core.dump.gz，tex() 主线程 WebAssembly.instantiate 直接跑 wasm，process() 把 <script type="text/tikz">
 * 替换为 SVG。与 1.0.2 fork（Comlink worker）不同，主线程无 blob/origin/worker 竞态问题，
 * 在受限 WebView 环境稳定。
 *
 * 加载方式：WebViewAssetLoader（真实 https origin appassets.androidplatform.net）+ loadUrl 加载
 * assets/html/tikz.html 模板，TikZ 代码经 base64url 放 URL fragment，模板 JS 解码后动态注入。
 */
@Composable
fun TikzJax(
    code: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current

    var svgHeight by remember(code) { mutableStateOf(0f) }
    var errorMessage by remember(code) { mutableStateOf<String?>(null) }

    val jsInterface = remember(code) {
        TikzJaxInterface(
            onSvgHeight = { heightPx -> svgHeight = heightPx },
            onError = { msg -> errorMessage = msg },
            onExportImage = { base64Image ->
                runCatching {
                    activity?.let {
                        try {
                            val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                            val bitmap =
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                            context.exportImage(
                                it,
                                bitmap,
                                "tikz_${System.currentTimeMillis()}.png"
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
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
            }
        )
    }

    // base64url 编码 TikZ 代码（URL-safe，无 padding），放 URL fragment
    val fragment = remember(code) {
        Base64.encodeToString(
            code.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
    val url = remember(fragment) { "$TIKZ_ASSET_URL/html/tikz.html#$fragment" }

    val webViewState = rememberWebViewState(
        url = url,
        interfaces = mapOf("AndroidInterface" to jsInterface),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
        }
    )

    Column(
        modifier = modifier
    ) {
        if (errorMessage != null) {
            // 渲染失败时显示错误，仍保留原文可读
            androidx.compose.material3.Text(
                text = errorMessage!!,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp),
            )
        }

        WebView(
            state = webViewState,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .height(if (svgHeight > 0f) svgHeight.dp else 40.dp),
        )

        if (activity != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = {
                        val contentId = WebViewContentCache.store(context.cacheDir, url)
                        navController.navigate(Screen.WebView(contentId = contentId))
                    },
                ) {
                    Icon(
                        HugeIcons.View,
                        contentDescription = "Preview"
                    )
                }
                IconButton(
                    onClick = {
                        webViewState.webView?.evaluateJavascript(
                            "exportSvgToPng();",
                            null
                        )
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
}

private class TikzJaxInterface(
    private val onSvgHeight: (Float) -> Unit,
    private val onError: (String) -> Unit,
    private val onExportImage: (String) -> Unit,
) {
    @JavascriptInterface
    fun onSvgHeight(heightPx: Float) {
        onSvgHeight(heightPx)
    }

    @JavascriptInterface
    fun onError(message: String) {
        onError(message)
    }

    @JavascriptInterface
    fun exportImage(base64Image: String) {
        onExportImage(base64Image)
    }
}
