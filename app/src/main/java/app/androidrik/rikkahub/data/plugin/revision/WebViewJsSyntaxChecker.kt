package app.androidrik.rikkahub.data.plugin.revision

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

private const val COMPILE_TIMEOUT_MS = 3_000L
private const val LOAD_TIMEOUT_MS = 3_000L

/**
 * 用 WebView 做 **compile-no-execute** 语法检查（设计稿 §四.2）。
 *
 * 只做 `new Function(<源码>)`：编译失败即语法错误，**从不执行**插件代码——比完整的
 * [app.androidrik.rikkahub.data.plugin.js.JsPluginRuntime] 求值轻得多，也没有任何插件副作用。
 *
 * WebView 生命周期全部在主线程，设置镜像 JsPluginRuntime 的沙箱（同款禁项，无 JS 桥），
 * 先载空白页等 `onPageFinished` 再求值（同款加载约定），用完即毁。
 * 等待必须用**挂起**而非 latch：主线程阻塞会让 `evaluateJavascript` 的回调饿死
 * （真机走查实证：latch.await 在 Main 上 3s 必超时）。
 */
class WebViewJsSyntaxChecker(private val context: Context) : JsSyntaxChecker {
    private val seq = AtomicInteger()

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun compile(source: String): String? = withContext(Dispatchers.Main) {
        val view = WebView(context)
        try {
            view.settings.let { s ->
                s.javaScriptEnabled = true
                s.allowFileAccess = false
                s.allowContentAccess = false
                s.blockNetworkLoads = true
                s.blockNetworkImage = true
                s.domStorageEnabled = false
                s.databaseEnabled = false
                s.loadsImagesAutomatically = false
                s.javaScriptCanOpenWindowsAutomatically = false
                s.setSupportMultipleWindows(false)
                s.cacheMode = WebSettings.LOAD_NO_CACHE
                s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            // 先载空白页等 onPageFinished（同 JsPluginRuntime 加载约定）；任何导航都拒绝：
            // 预检不加载任何远端内容
            val pageLoaded = CompletableDeferred<Unit>()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded.complete(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            }
            view.loadDataWithBaseURL(null, "<html><body></body></html>", "text/html", "utf-8", null)
            withTimeoutOrNull(LOAD_TIMEOUT_MS) { pageLoaded.await() }
                ?: return@withContext "预检页面加载超时"

            // 编译不执行；实例级唯一函数名防预检间串扰
            val fn = "__rikka_precheck_${seq.incrementAndGet()}"
            val script = """
                (function(){ try {
                    new Function(${JsonPrimitive(source)});
                    window.$fn = 'OK'; return 'OK';
                } catch (e) { return 'ERR:' + (e && e.message || e); } })()
            """.trimIndent()
            val result = withTimeoutOrNull(COMPILE_TIMEOUT_MS) {
                suspendCancellableCoroutine<String?> { cont ->
                    view.evaluateJavascript(script) { value ->
                        cont.resume(value?.trim()?.removeSurrounding("\""))
                    }
                }
            } ?: return@withContext "语法检查超时"
            when {
                result == "OK" -> null
                result == null -> "语法检查失败"
                else -> result.removePrefix("ERR:")
            }
        } catch (e: Exception) {
            "语法检查失败: ${e.message}"
        } finally {
            view.destroy()
        }
    }
}
