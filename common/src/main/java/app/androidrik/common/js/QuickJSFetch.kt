package app.androidrik.common.js

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val json = Json { ignoreUnknownKeys = true }

/** fetch 响应体最大字节数：恶意/超大响应全量读入曾 OOM */
private const val MAX_FETCH_RESPONSE_BYTES = 5 * 1024 * 1024

@Serializable
private data class HttpResponseDto(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val body: String,
)

// fetch() returns a Response object synchronously (not a Promise)
// because this QuickJS wrapper doesn't support microtask scheduling.
private const val FETCH_POLYFILL = """
globalThis.fetch = function(url, options) {
    options = options || {};
    var method = (options.method || 'GET').toUpperCase();
    var headers = options.headers ? JSON.stringify(options.headers) : null;
    var body = options.body;
    if (typeof body === 'object' && body !== null) {
        body = JSON.stringify(body);
    } else if (typeof body !== 'string') {
        body = null;
    }

    var raw = __httpRequest(url, method, headers, body);
    var data = JSON.parse(raw);
    return {
        status: data.status,
        ok: data.ok,
        statusText: data.statusText,
        url: url,
        _body: data.body,
        text: function() { return this._body; },
        json: function() { return JSON.parse(this._body); }
    };
};
"""

fun QuickJSContext.injectFetch(httpClient: OkHttpClient) {
    globalObject.setProperty("__httpRequest", JSCallFunction { args ->
        val url = args[0] as? String ?: error("url is required")
        val method = (args[1] as? String ?: "GET").uppercase()
        val headersJson = args[2] as? String
        val body = args[3] as? String

        val requestBuilder = Request.Builder().url(url)

        val parsedHeaders = if (!headersJson.isNullOrBlank() && headersJson != "null") {
            json.parseToJsonElement(headersJson).jsonObject
        } else null

        parsedHeaders?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val contentType = try {
            parsedHeaders?.get("Content-Type")?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }

        val mediaType = (contentType ?: "application/json").toMediaType()
        when (method) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            else -> {
                val reqBody = body?.toRequestBody(mediaType)
                    ?: if (method in setOf("POST", "PUT", "PATCH")) {
                        "".toRequestBody(mediaType)
                    } else {
                        null
                    }
                requestBuilder.method(method, reqBody)
            }
        }

        val response = httpClient.newCall(requestBuilder.build()).execute()
        val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
        val responseBody = response.body.byteStream().use { input ->
            // 限量读取：防恶意/超大响应 OOM（body.string() 会全量读入）
            val buffer = ByteArray(MAX_FETCH_RESPONSE_BYTES + 1)
            val read = input.read(buffer)
            if (read < 0) {
                ""
            } else {
                val kept = minOf(read, MAX_FETCH_RESPONSE_BYTES)
                String(buffer, 0, kept, charset) + if (read > kept) "\n[TRUNCATED]" else ""
            }
        }
        val code = response.code
        val message = response.message
        response.close()

        json.encodeToString(
            HttpResponseDto(
                status = code,
                ok = code in 200..299,
                statusText = message,
                body = responseBody,
            )
        )
    })

    evaluate(FETCH_POLYFILL)
}
