package app.androidrik.rikkahub.data.plugin.js

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import app.androidrik.rikkahub.data.plugin.net.PluginHttpRequest
import app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JS 插件沙箱运行时(对标 BiliPai `.bilipai.js` 的 WebView 沙箱)。
 *
 * 安全取舍(与 BiliPai 不同的地方):
 * - 每次求值**新建 WebView、求值后不保留**(单文件、无模块级状态),超时即 `destroy()` 杀掉跑飞的脚本;
 * - JS 侧**没有** `fetch`/`XMLHttpRequest`/`WebSocket`(`blockNetworkLoads=true`、无 CORS 豁免),
 *   唯一出站通道是宿主注入的 `rikka.http()`,经 [PluginHttpGateway] 的白名单 + SSRF 校验;
 * - 不能加载任何外部资源(图片/脚本/iframe)、不能导航、不能访问 file/content URI;
 * - 宿主只暴露一个 `RikkaHost` 桥,方法只有 `resolve/log/http`。
 */
class JsPluginRuntime(
    private val context: Context,
    private val definition: JsPluginDefinition,
    private val gateway: PluginHttpGateway,
    /** 调用点能力校验:网络能力被撤销后,`rikka.http()` 立即失效(不必重启插件)。 */
    private val networkGranted: () -> Boolean,
    private val keyValueStore: PluginKeyValueStore,
    private val storageGranted: () -> Boolean,
) {
    private val main = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile private var webView: WebView? = null
    private var loaded: CompletableDeferred<Boolean>? = null
    private val evalPending = ConcurrentHashMap<String, (String) -> Unit>()
    private val httpPending = ConcurrentHashMap<String, Boolean>()
    @Volatile private var closed = false
    private val evaluationMutex = Mutex()
    @Volatile private var evaluationScope: CoroutineScope? = null
    @Volatile private var activeEvaluation = false
    private var bridgeCalls = 0

    /** 工具返回任意 JSON，文本钩子仍保持 string/{text}/null 契约。 */
    suspend fun runTool(name: String, arguments: JsonElement): Result<JsonElement> =
        runInvocation("onTool", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
            put("ctx", buildJsonObject { put("pluginId", definition.id) })
        }, tool = true)

    /** 在指定钩子上求值;返回新的文本(null = 不改动),失败返回 failure。 */
    suspend fun runHook(hook: JsHook, payload: JsonObject): Result<String?> =
        runInvocation(hook.jsName, payload, tool = false).mapCatching { value ->
            if (value == JsonNull) null else (value as JsonPrimitive).content
        }

    private suspend fun runInvocation(
        hookName: String,
        payload: JsonObject,
        tool: Boolean,
    ): Result<JsonElement> = evaluationMutex.withLock {
        withContext(main) {
            if (closed) return@withContext Result.failure(IllegalStateException("插件已停用"))
            evaluationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            bridgeCalls = 0
            activeEvaluation = true
            try {
                val view = ensureLoaded()
                withTimeout(definition.manifest.timeoutMs) {
                    Result.success(evaluate(view, hookName, payload, tool))
                }
            } catch (timeout: TimeoutCancellationException) {
                Log.w(TAG, "plugin ${definition.id} hook $hookName timed out")
                Result.failure(IllegalStateException("JS 执行或加载超时，已中止"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Exception) {
                Log.w(TAG, "plugin ${definition.id} hook $hookName failed", t)
                Result.failure(t)
            } finally {
                activeEvaluation = false
                evaluationScope?.cancel()
                evaluationScope = null
                destroy()
            }
        }
    }

    private suspend fun evaluate(view: WebView, hookName: String, payload: JsonObject, tool: Boolean): JsonElement {
        val payloadJson = payload.toString()
        val token = UUID.randomUUID().toString()
        val resultJson = suspendCancellableCoroutine<String> { cont ->
            evalPending[token] = { raw -> cont.resume(raw) }
            cont.invokeOnCancellation { evalPending.remove(token) }
            val script = buildString {
                append("window.__rikkaRun(")
                append(jsString(hookName))
                append(",")
                append(jsString(payloadJson))
                append(",")
                append(jsString(token))
                append(")")
            }
            view.evaluateJavascript(script, null)
        }
        require(resultJson.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "JS 返回值超过 256 KiB" }
        val obj = json.parseToJsonElement(resultJson) as? JsonObject ?: error("bridge 返回非对象")
        check((obj["ok"] as? JsonPrimitive)?.content == "true") {
            (obj["error"] as? JsonPrimitive)?.content ?: "脚本执行失败"
        }
        return if (tool) obj["value"] ?: JsonNull else obj["text"] ?: JsonNull
    }

    fun close() {
        closed = true
        val view = webView
        webView = null
        loaded = null
        Handler(Looper.getMainLooper()).post {
            destroyView(view)
        }
        evaluationScope?.cancel()
        scope.cancel()
    }

    // ---- WebView 生命周期(全部在主线程) ----

    private suspend fun ensureLoaded(): WebView {
        webView?.let { view ->
            if (loaded?.isCompleted == true) return view
        }
        val deferred = CompletableDeferred<Boolean>()
        loaded = deferred
        val view = createWebView()
        webView = view
        view.loadDataWithBaseURL(
            null,
            buildHtml(),
            "text/html",
            "utf-8",
            null,
        )
        withTimeout(LOAD_TIMEOUT_MS) { deferred.await() }
        return view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        // 沙箱:不能读本地文件/资源,不能自己上网,不能开新窗口
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.blockNetworkImage = true
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.loadsImagesAutomatically = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                loaded?.complete(true)
            }

            // 任何导航都拒绝:插件脚本不应能把沙箱带到别的页面
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                url: String?,
            ): Boolean = true
        }
        addJavascriptInterface(Bridge(this), "RikkaHost")
    }

    private fun destroy() {
        val view = webView
        webView = null
        loaded = null
        evalPending.clear()
        httpPending.clear()
        destroyView(view)
    }

    private fun destroyView(view: WebView?) {
        if (view == null) return
        runCatching {
            view.stopLoading()
            view.removeJavascriptInterface("RikkaHost")
            view.destroy()
        }
    }

    @Synchronized
    private fun acceptBridgeCall(): Boolean {
        if (closed || !activeEvaluation || bridgeCalls >= 64) return false
        bridgeCalls++
        return true
    }

    // ---- JS ↔ Kotlin 桥(回调来自 WebView 的 JavaBridge 线程) ----

    private inner class Bridge(private val owner: WebView) {
        @JavascriptInterface
        fun resolve(token: String, resultJson: String) {
            if (webView !== owner) return
            evalPending.remove(token)?.invoke(resultJson)
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[${definition.id}] ${message.take(MAX_LOG_CHARS)}")
        }

        @JavascriptInterface
        fun http(token: String, requestJson: String) {
            if (webView !== owner) return
            if (!acceptBridgeCall()) {
                replyHttp(owner, token, """{"ok":false,"error":"调用已结束或超过 64 次桥调用上限"}""")
                return
            }
            if (!networkGranted()) {
                // 调用点能力校验:用户未批准 NETWORK(或后续撤销)时立即拒绝
                Log.w(TAG, "[${definition.id}] denied rikka.http: NETWORK not granted")
                replyHttp(owner, token, """{"ok":false,"error":"未授权 NETWORK 能力"}""")
                return
            }
            if (definition.manifest.networkHosts.isEmpty()) {
                replyHttp(owner, token, """{"ok":false,"error":"插件未声明 networkHosts"}""")
                return
            }
            httpPending[token] = true
            evaluationScope?.launch {
                val result = runCatching {
                    check(networkGranted()) { "未授权 NETWORK 能力" }
                    val request = json.decodeFromString<PluginHttpRequest>(requestJson)
                    gateway.request(
                        pluginId = definition.id,
                        allowedHosts = definition.manifest.networkHosts.toSet(),
                        request = request,
                    ).getOrThrow()
                }
                result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                val payload = result.fold(
                    onSuccess = { response ->
                        // ok 语义 = HTTP 2xx:非 2xx 也会带回 status/body,便于插件自己判断
                        val httpOk = response.status in 200..299
                        buildJsonObject {
                            put("ok", httpOk)
                            put("status", response.status)
                            put("body", response.body)
                            if (!httpOk) put("error", "HTTP " + response.status)
                            putJsonObject("headers") {
                                response.headers.forEach { (k, v) -> put(k, v) }
                            }
                        }.toString()
                    },
                    onFailure = { error ->
                        buildJsonObject {
                            put("ok", false)
                            put("error", error.message ?: error::class.simpleName ?: "请求失败")
                        }.toString()
                    },
                )
                replyHttp(owner, token, payload)
            }
        }

        @JavascriptInterface
        fun store(token: String, requestJson: String) {
            if (webView !== owner) return
            val view = owner
            val callbackScope = evaluationScope ?: return
            if (!acceptBridgeCall()) {
                replyStore(view, token, """{"ok":false,"error":"超过桥调用上限"}""")
                return
            }
            callbackScope.launch {
                val payload = try {
                    check(storageGranted() && definition.manifest.storage) { "未授权 STORAGE 能力" }
                    require(requestJson.toByteArray(Charsets.UTF_8).size <= 70 * 1024) { "存储请求过大" }
                    val request = json.parseToJsonElement(requestJson) as JsonObject
                    val key = (request["key"] as? JsonPrimitive)?.content.orEmpty()
                    val result: JsonElement = when (request["op"]?.jsonPrimitive?.content) {
                        "get" -> keyValueStore.get(definition.id, key) ?: JsonNull
                        "set" -> {
                            keyValueStore.set(definition.id, key, request["value"] ?: error("缺少 value"))
                            JsonNull
                        }
                        "remove" -> { keyValueStore.remove(definition.id, key); JsonNull }
                        "clear" -> { keyValueStore.clear(definition.id); JsonNull }
                        "keys" -> JsonArray(keyValueStore.keys(definition.id).map(::JsonPrimitive))
                        else -> error("未知 store 操作")
                    }
                    buildJsonObject { put("ok", true); put("value", result) }.toString()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: "存储失败") }.toString()
                }
                replyStore(view, token, payload)
            }
        }
    }

    private fun replyStore(view: WebView, token: String, payload: String) {
        withContextMain {
            if (webView === view && activeEvaluation) view.evaluateJavascript(
                "window.__rikkaStoreResult(${jsString(token)}, ${jsString(payload)})", null,
            )
        }
    }

    private fun replyHttp(view: WebView, token: String, payload: String) {
        httpPending.remove(token)
        withContextMain {
            if (webView === view && activeEvaluation) view.evaluateJavascript(
                "window.__rikkaHttpResult(${jsString(token)}, ${jsString(payload)})",
                null,
            )
        }
    }

    private fun withContextMain(block: () -> Unit) {
        scope.launch {
            withContext(main) { block() }
        }
    }

    // ---- 外壳 HTML ----

    private fun buildHtml(): String = """
        |<!DOCTYPE html>
        |<html><head><meta charset="utf-8"></head>
        |<body>
        |<script>
        |(function () {
        |  'use strict';
        |  var seq = 0;
        |  var httpPending = new Map();
        |  var storePending = new Map();
        |  function storeCall(op, key, value) {
        |    return new Promise(function(resolve, reject) {
        |      var token = 's' + (++seq);
        |      storePending.set(token, {resolve: resolve, reject: reject});
        |      RikkaHost.store(token, JSON.stringify({op:op, key:key, value:value}));
        |    });
        |  }
        |  window.__rikkaStoreResult = function(token, payload) {
        |    var cb = storePending.get(token);
        |    if (!cb) return;
        |    storePending.delete(token);
        |    try {
        |      var result = JSON.parse(payload);
        |      if (result.ok) cb.resolve(result.value);
        |      else cb.reject(new Error(result.error || 'Store failed'));
        |    } catch(e) { cb.reject(e); }
        |  };
        |  window.rikka = {
        |    ctx: {},
        |    store: {
        |      get: function(key) { return storeCall('get', key); },
        |      set: function(key, value) { return storeCall('set', key, value); },
        |      remove: function(key) { return storeCall('remove', key); },
        |      clear: function() { return storeCall('clear'); },
        |      keys: function() { return storeCall('keys'); }
        |    },
        |    log: function (m) { RikkaHost.log(String(m)); },
        |    http: function (request) {
        |      return new Promise(function (resolve) {
        |        var token = 'h' + (++seq);
        |        httpPending.set(token, resolve);
        |        RikkaHost.http(token, JSON.stringify(request || {}));
        |      });
        |    }
        |  };
        |  window.__rikkaHttpResult = function (token, payload) {
        |    var cb = httpPending.get(token);
        |    if (!cb) return;
        |    httpPending.delete(token);
        |    try { cb(JSON.parse(payload)); } catch (e) { cb({ ok: false, error: String(e) }); }
        |  };
        |  window.__rikkaRun = async function (hookName, payloadJson, token) {
        |    try {
        |      var payload = JSON.parse(payloadJson);
        |      window.rikka.ctx = payload.ctx || {};
        |      var hooks = window.__rikkaHooks || {};
        |      var fn = hooks[hookName];
        |      if (typeof fn !== 'function') {
        |        if (hookName === 'onTool') throw new Error('Missing onTool hook');
        |        RikkaHost.resolve(token, JSON.stringify({ ok: true, text: null }));
        |        return;
        |      }
        |      var out = await fn(payload);
        |      if (hookName === 'onTool') {
        |        RikkaHost.resolve(token, JSON.stringify({ok:true, value:out === undefined ? null : out}));
        |        return;
        |      }
        |      var text;
        |      if (typeof out === 'string') text = out;
        |      else if (out && typeof out.text === 'string') text = out.text;
        |      else text = null;
        |      RikkaHost.resolve(token, JSON.stringify({ ok: true, text: text }));
        |    } catch (e) {
        |      RikkaHost.resolve(token, JSON.stringify({ ok: false, error: String((e && e.message) || e) }));
        |    }
        |  };
        |})();
        |</script>
        |<script>
        |(0, eval)(${jsString(definition.source + "\n;window.__rikkaHooks = {onInput: typeof onInput !== 'undefined' ? onInput : null, onOutput: typeof onOutput !== 'undefined' ? onOutput : null, onTool: typeof onTool !== 'undefined' ? onTool : null};").replace("<", "\\u003c")});
        |</script>
        |</body></html>
    """.trimMargin()

    private fun jsString(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "'$escaped'"
    }

    private companion object {
        const val TAG = "JsPluginRuntime"
        const val LOAD_TIMEOUT_MS = 5000L
        const val MAX_LOG_CHARS = 500
    }
}
