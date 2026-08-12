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
import androidx.compose.material3.MaterialTheme
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
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.exportImage

/**
 * 通过 WebView + 本地 tikzjax（wasm 编译 TikZ→SVG）渲染 AI 输出的 TikZ 图表代码。
 *
 * 触发形式：
 * - ```tikz / ```tikzpicture / ```tikzcd / ```tikztiming 代码围栏
 * - $$...$$ 块公式内嵌的 \begin{tikzpicture} / \begin{tikzcd} / \begin{axis} / \xymatrix / \begin{CD}
 *
 * 原理：tikzjax@1.0.2 的 index.js 是自包含 webpack bundle（wasm + tex_files + CM 字体全 base64 内嵌），
 * 自动扫描 <script type="text/tikz"> 标签并用 MutationObserver 监听动态添加，编译后把 script 替换为 SVG。
 * 渲染结果按 (dataset + textContent) 的 md5 存 localStorage，同内容二次秒开。
 *
 * 深色模式：通过 data-add-to-preamble="\color{white}" 在 LaTeX 源头控制颜色（math-plus 同思路），
 * 浅色不注入 → 缓存 key 含 dataset，深/浅各存一份。
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
    // 跟随应用内"通用设置-颜色模式"（与 Mermaid/HighlightCodeBlock 一致）
    val isDark = LocalDarkMode.current

    var svgHeight by remember(code, isDark) { mutableStateOf(0f) }

    val jsInterface = remember(code, isDark) {
        TikzJaxInterface(
            onSvgHeight = { heightPx -> svgHeight = heightPx },
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

    val html = remember(code, isDark) { buildTikzHtml(code, isDark) }

    val webViewState = rememberWebViewState(
        data = html,
        baseUrl = TIKZ_ASSET_URL,
        mimeType = "text/html",
        encoding = "UTF-8",
        interfaces = mapOf(
            "AndroidInterface" to jsInterface
        ),
        settings = {
            builtInZoomControls = true
            displayZoomControls = false
        }
    )

    Column(
        modifier = modifier
    ) {
        WebView(
            state = webViewState,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .height(svgHeight.dp),
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
                        val contentId = WebViewContentCache.store(context.cacheDir, html)
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
    private val onExportImage: (String) -> Unit,
) {
    @JavascriptInterface
    fun onSvgHeight(heightPx: Float) {
        onSvgHeight(heightPx)
    }

    @JavascriptInterface
    fun exportImage(base64Image: String) {
        onExportImage(base64Image)
    }
}

private fun buildTikzHtml(
    code: String,
    isDark: Boolean,
): String {
    val bodyBackground = if (isDark) "#1F1F1F" else "#FFFFFF"
    val preamble = if (isDark) "\\color{white}" else null

    // tikzjax 读 <script type="text/tikz"> 的 textContent（childNodes[0].nodeValue）。
    // 因此代码不能 escapeHtml（会读到实体字面量），只需转义会提前终止 script 标签的 </script>。
    val scriptSafeCode = code.replace("</script", "<\\/script")

    val preambleAttr = if (preamble != null) {
        """ data-add-to-preamble="${preamble}" """
    } else {
        ""
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <script src="${TIKZ_ASSET_URL}/html/tikzjax.min.js"></script>
            <style>
                body {
                    margin: 0;
                    padding: 0;
                    display: flex;
                    justify-content: center;
                    background-color: ${bodyBackground};
                }
                .tikz {
                    padding: 8px;
                    width: fit-content;
                    min-width: 100%;
                }
                .tikz svg {
                    max-width: 100%;
                    height: auto;
                    display: block;
                }
            </style>
        </head>
        <body>
            <div class="tikz">
            <script type="text/tikz"${preambleAttr}>
            ${scriptSafeCode}
            </script>
            </div>
            <script>
              // 轮询等待 tikzjax 渲染出 .tikz svg，把实际高度回传给 Kotlin 驱动 WebView 尺寸自适应
              (function poll() {
                var svg = document.querySelector('.tikz svg');
                if (svg) {
                    var rect = svg.getBoundingClientRect();
                    if (rect.height > 0) {
                        AndroidInterface.onSvgHeight(rect.height);
                        return;
                    }
                }
                setTimeout(poll, 100);
              })();

              window.exportSvgToPng = function() {
                try {
                    const svgElement = document.querySelector('.tikz svg');
                    if (!svgElement) {
                        AndroidInterface.exportImage('');
                        return;
                    }

                    const canvas = document.createElement('canvas');
                    const ctx = canvas.getContext('2d');

                    const svgRect = svgElement.getBoundingClientRect();
                    const width = svgRect.width;
                    const height = svgRect.height;

                    const scaleFactor = window.devicePixelRatio * 2;
                    canvas.width = width * scaleFactor;
                    canvas.height = height * scaleFactor;

                    const svgXml = new XMLSerializer().serializeToString(svgElement);
                    const svgBase64 = btoa(unescape(encodeURIComponent(svgXml)));

                    const img = new Image();
                    img.onload = function() {
                        ctx.fillStyle = '${bodyBackground}';
                        ctx.fillRect(0, 0, canvas.width, canvas.height);
                        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);

                        const pngBase64 = canvas.toDataURL('image/png').split(',')[1];
                        AndroidInterface.exportImage(pngBase64);
                    };
                    img.onerror = function(e) {
                        AndroidInterface.exportImage('');
                    }
                    img.src = 'data:image/svg+xml;base64,' + svgBase64;
                } catch (e) {
                    AndroidInterface.exportImage('');
                }
              };
            </script>
        </body>
        </html>
    """.trimIndent()
}
