package app.androidrik.rikkahub.ui.components.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * 应用内 WebView 使用的虚拟域名，用于给 loadDataWithBaseURL 提供一个稳定的 origin。
 */
const val WEB_VIEW_BASE_URL = "https://rikkahub.local"

/**
 * 虚拟域名下的静态资源前缀，会被映射到 app 的 assets 目录。
 *
 * 例如 `https://rikkahub.local/assets/html/mermaid.min.js` 对应 `assets/html/mermaid.min.js`，
 * 这样 mermaid/katex 之类的库可以本地加载，而不用从 CDN 下载（首次加载可能需要几十秒）。
 */
const val WEB_VIEW_ASSET_URL = "$WEB_VIEW_BASE_URL/assets"

/**
 * TikZ 图表渲染使用的真实 https origin。
 *
 * rikkahub.local 是未注册的虚拟域名——对纯 JS 库（mermaid）够用，但 tikzjax 依赖
 * Web Worker + blob URL + WebAssembly：Worker 内 importScripts 加载 blob 时 origin 解析为
 * null（"blob:null/..."），WebView 拦截 "Not allowed to load local resource" → 渲染空白。
 *
 * WebViewAssetLoader 注册真正的 https://appassets.androidplatform.net，提供 secure context，
 * blob URL 继承真实 origin，Worker + wasm 全部可用。这正是 androidx.webkit 官方为
 * "WebView 内托管本地 assets 且需要完整 Web 能力" 提供的标准方案。
 */
const val TIKZ_ASSET_URL = "https://appassets.androidplatform.net/assets"

private const val ASSET_HOST = "rikkahub.local"
private const val ASSET_PATH_PREFIX = "/assets/"
private const val APPASSETS_HOST = "appassets.androidplatform.net"

internal object WebViewLocalAssets {
    /**
     * WebViewAssetLoader：把 https://appassets.androidplatform.net/assets/... 映射到 assets。
     * 提供真实 https origin，供需要 Worker/wasm/blob 的场景（tikzjax）使用。
     * applicationContext 懒加载，跨 WebView 复用安全。
     */
    private var assetLoader: WebViewAssetLoader? = null

    private fun loader(context: Context): WebViewAssetLoader {
        assetLoader?.let { cached -> return cached }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context.applicationContext))
            .build()
        assetLoader = loader
        return loader
    }

    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        // 1) appassets.androidplatform.net 真实 origin（TikZ/worker/wasm，用 loadUrl）
        if (uri.host.equals(APPASSETS_HOST, ignoreCase = true)) {
            return loader(context).shouldInterceptRequest(uri)
        }
        // 2) rikkahub.local 虚拟域（旧路径，纯 JS 库如 mermaid，用 loadDataWithBaseURL）
        if (uri.host.equals(ASSET_HOST, ignoreCase = true)) {
            val path = uri.path ?: return null
            if (!path.startsWith(ASSET_PATH_PREFIX)) return null
            val assetPath = path.removePrefix(ASSET_PATH_PREFIX)
            if (assetPath.isEmpty() || assetPath.contains("..")) return null
            val mimeType = mimeTypeOf(assetPath)
            return runCatching {
                WebResourceResponse(
                    mimeType,
                    if (mimeType.startsWith("text/") || mimeType.endsWith("json") || mimeType.endsWith("xml")) "UTF-8" else null,
                    context.assets.open(assetPath)
                )
            }.getOrNull()
        }
        return null
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
}
