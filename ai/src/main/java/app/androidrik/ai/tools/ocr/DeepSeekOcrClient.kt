package app.androidrik.ai.tools.ocr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek OCR API 客户端（2026-11-13 Tool API）。
 *
 * multipart/form-data **恰好两个字段**（`file` + `purpose`），多传任何字段返回 400。
 * 官方特例（配料 01 §4「易踩的两个特例」）：
 * - 识别开始后 HTTP 已是 200；识别失败/超时时**响应 body 是错误对象而不是结果**——
 *   必须检查 body 是否含 `error`，不能只看状态码；
 * - 识别期间服务端持续发送换行保活，整个 body 仍可整体按 JSON 解析（前导换行是空白）。
 *
 * 计费按成功的 `page_count`（¥1.5/千页）；**客户端断开不会取消已开始的识别**。
 * 限流：账号级并发 15。
 *
 * 官方文档: https://api-docs.deepseek.com/zh-cn/guides/ocr/
 */
class DeepSeekOcrClient(
    // 构造注入供测试用假 client（本仓惯例：HTTP 不打桩库，用 interceptor 拦截）
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // 官方总耗时上限 180s（上传+识别+返回）；写超时对齐上传窗口 120s
        .callTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 上传并识别一个文件。仅对 408/429/5xx 重试 1 次（官方「可等待后重试」）；
     * 400/402/413 等直接抛 [DeepSeekOcrException]。
     *
     * @param purpose `text_recognition` 或 `rich_layout_recognition`
     */
    fun recognize(
        baseUrl: String,
        apiKey: String,
        fileBytes: ByteArray,
        fileName: String,
        purpose: String,
        maxAttempts: Int = 2,
    ): DeepSeekOcrResult {
        var lastError: DeepSeekOcrException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return executeOnce(baseUrl, apiKey, fileBytes, fileName, purpose)
            } catch (e: DeepSeekOcrRetryableException) {
                lastError = e
            }
        }
        throw lastError ?: DeepSeekOcrException("DeepSeek OCR upload failed")
    }

    private fun executeOnce(
        baseUrl: String,
        apiKey: String,
        fileBytes: ByteArray,
        fileName: String,
        purpose: String,
    ): DeepSeekOcrResult {
        val response = httpClient.newCall(buildRequest(baseUrl, apiKey, fileBytes, fileName, purpose)).execute()
        response.use { resp ->
            val bodyText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val message = parseErrorMessage(bodyText)
                val e = DeepSeekOcrException(
                    "DeepSeek OCR HTTP ${resp.code}: ${message.ifBlank { bodyText.take(500) }}",
                    statusCode = resp.code,
                )
                if (resp.code == 408 || resp.code == 429 || resp.code in 500..599) {
                    throw DeepSeekOcrRetryableException(e.message.orEmpty(), resp.code, e)
                }
                throw e
            }
            // 200-with-error 特例：识别已开始后失败，body 是错误对象
            parseErrorMessage(bodyText).takeIf { it.isNotBlank() }?.let { message ->
                throw DeepSeekOcrException("DeepSeek OCR failed after upload: $message", statusCode = 200)
            }
            return try {
                json.decodeFromString<DeepSeekOcrResult>(bodyText.trim())
            } catch (e: SerializationException) {
                throw DeepSeekOcrException("DeepSeek OCR response is not valid JSON: ${bodyText.take(500)}", cause = e)
            }
        }
    }

    /** 独立纯函数：单测断言 multipart 恰好两 part（`file`+`purpose`）——多传即 400 是硬约束。 */
    fun buildRequest(baseUrl: String, apiKey: String, fileBytes: ByteArray, fileName: String, purpose: String): Request {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, fileBytes.toRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("purpose", purpose)
            .build()
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/tools/ocr")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    /** 从响应体里提取 `error.message`；非错误体返回空串。 */
    private fun parseErrorMessage(bodyText: String): String = runCatching {
        val obj = json.parseToJsonElement(bodyText.trim()) as? JsonObject ?: return ""
        (obj["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.content }.orEmpty()
    }.getOrDefault("")
}

/** OCR 调用失败。已开始的识别不会被取消，失败往往**已经计费**——错误文案要如实告知。 */
open class DeepSeekOcrException(
    message: String,
    val statusCode: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

private class DeepSeekOcrRetryableException(
    message: String,
    statusCode: Int,
    cause: Throwable? = null,
) : DeepSeekOcrException(message, statusCode, cause)
