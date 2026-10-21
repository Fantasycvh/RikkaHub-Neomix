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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset

private val json = Json { ignoreUnknownKeys = true }

/** fetch 响应体最大字节数：恶意/超大响应全量读入曾 OOM */
private const val MAX_FETCH_RESPONSE_BYTES = 5 * 1024 * 1024

/** Read across short network reads, probing one extra byte to distinguish exact size from truncation. */
internal fun readFetchResponse(
    input: InputStream,
    charset: Charset,
    maxBytes: Int = MAX_FETCH_RESPONSE_BYTES,
): String {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) {
            val byte = input.read()
            if (byte < 0) break
            output.write(byte)
            remaining--
        } else {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    val truncated = remaining == 0 && input.read() >= 0
    return output.toString(charset.name()) + if (truncated) "\n[TRUNCATED]" else ""
}

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

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val charset = response.body.contentType()?.charset() ?: Charsets.UTF_8
            val responseBody = response.body.byteStream().use { input ->
                readFetchResponse(input, charset)
            }
            json.encodeToString(
                HttpResponseDto(
                    status = response.code,
                    ok = response.code in 200..299,
                    statusText = response.message,
                    body = responseBody,
                )
            )
        }
    })

    evaluate(FETCH_POLYFILL)
}
