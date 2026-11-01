package app.androidrik.ai.provider

import app.androidrik.ai.util.mergeCustomHeaders
import app.androidrik.ai.util.json
import app.androidrik.common.http.await
import app.androidrik.common.http.evaluateJsonExpr
import app.androidrik.common.http.getByKey
import app.androidrik.common.http.jsonObjectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

/**
 * 执行 API Key 类提供商的自定义额度查询（GET），没有开启时返回 null。
 *
 * 两种模式（见 [QuotaQuery]）：
 * - **单接口模式**（[QuotaQuery.sources] 为空）：请求 [QuotaQuery.url]，按
 *   [QuotaQuery.resultPath] 取值；对齐上游 #2186。
 * - **多接口模式**（本仓扩展，`sources` 非空）：并行请求各 [QuotaSource]，把各自
 *   [QuotaSource.resultPath] 的取值以 [QuotaSource.name] 为变量名合并成 JSON 对象，
 *   再用 [QuotaQuery.expression] 求值（`evaluateJsonExpr`）。旧 `BalanceOption` 的多接口
 *   能力原样保留。
 *
 * URL / 请求头模板支持 `{{baseUrl}}` / `{{apiKey}}`；提供商级自定义请求头先落、
 * 查询配置的头后写（同名以查询配置为准），与对话请求的 header 合并语义一致。
 *
 * 调用方负责传入**已按 [ProviderSetting.proxy] 配置过**的 client。
 */
suspend fun OkHttpClient.runQuotaQuery(
    setting: ProviderSetting.ApiKey,
    nextApiKey: () -> String,
): ProviderQuota.Balance? = withContext(Dispatchers.IO) {
    val query = setting.quotaQuery.takeIf { it.enabled } ?: return@withContext null
    // 填了多个 key 时只取一个（和对话请求一样轮转），懒求值避免不需要 key 时也消耗轮转位
    val apiKey by lazy(nextApiKey)
    fun render(template: String): String = renderQuotaTemplate(template) { name ->
        when (name) {
            QuotaQuery.VARIABLE_BASE_URL -> setting.baseUrl
            QuotaQuery.VARIABLE_API_KEY -> apiKey
            else -> null
        }
    }

    if (query.sources.isEmpty()) {
        val request = Request.Builder()
            .url(render(query.url.trim()))
            .quotaHeaders(setting, query, ::render)
            .get()
            .build()
        val body = execute(request)
        val bodyJson = json.parseToJsonElement(body).jsonObjectOrNull
            ?: error("Quota API response is not a JSON object: ${body.take(200)}")
        return@withContext ProviderQuota.Balance(formatQuotaValue(bodyJson.getByKey(query.resultPath)))
    }

    val merged = coroutineScope {
        query.sources.map { source ->
            async {
                val request = Request.Builder()
                    .url(sourceUrl(setting.baseUrl, source.apiPath, ::render))
                    .quotaHeaders(setting, query, ::render)
                    .get()
                    .build()
                val body = execute(request)
                val bodyJson = json.parseToJsonElement(body).jsonObjectOrNull
                    ?: error("Quota response for source '${source.name}' is not a JSON object: ${body.take(200)}")
                source.name to bodyJson.getByKey(source.resultPath)
            }
        }.awaitAll().let { pairs ->
            buildJsonObject {
                pairs.forEach { (name, rawValue) ->
                    val num = rawValue.toFloatOrNull()
                    put(name, if (num != null) JsonPrimitive(num) else JsonPrimitive(rawValue))
                }
            }
        }
    }
    ProviderQuota.Balance(formatQuotaValue(evaluateJsonExpr(query.expression, merged)))
}


/**
 * 请求头：先落提供商级自定义头（原样），再写查询配置的头（值走模板渲染，同名覆盖）。
 * 与上游一致——提供商级头不参与模板渲染，避免历史自定义头里的字面量 `{{...}}` 被误替换。
 */
private fun Request.Builder.quotaHeaders(
    setting: ProviderSetting.ApiKey,
    query: QuotaQuery,
    render: (String) -> String,
): Request.Builder {
    val merged = setting.mergeCustomHeaders()
    for (index in 0 until merged.size) {
        header(merged.name(index), merged.value(index))
    }
    query.headers
        .filter { it.name.isNotBlank() }
        .forEach { header(it.name.trim(), render(it.value)) }
    return this
}

/** 多接口模式下单个来源的完整 URL：绝对地址直接用，否则拼在 baseUrl 后面 */
private fun sourceUrl(baseUrl: String, apiPath: String, render: (String) -> String): String {
    val rendered = render(apiPath.trim())
    return if (rendered.startsWith("http://") || rendered.startsWith("https://")) {
        rendered
    } else {
        baseUrl.trimEnd('/') + "/" + rendered.trimStart('/')
    }
}

/** 数字结果统一两位小数，非数字原样返回（与旧余额展示一致） */
private fun formatQuotaValue(value: String): String =
    value.toFloatOrNull()?.let { "%.2f".format(Locale.ROOT, it) } ?: value

private suspend fun OkHttpClient.execute(request: Request): String {
    val response = newCall(request).await()
    val body = response.body?.string() ?: ""
    if (!response.isSuccessful) {
        error("Failed to get quota: ${response.code} ${body.take(500)}")
    }
    return body
}
