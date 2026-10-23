package app.androidrik.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.common.js.injectFetch
import app.androidrik.search.SearchService.Companion.httpClient
import app.androidrik.search.SearchService.Companion.json

object CustomJsSearchService : SearchService<SearchServiceOptions.CustomJsOptions> {
    override val name: String = "Custom JS"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.custom_js_desc))
    }

    override fun parameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", SEARCH_QUERY_DESCRIPTION)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.CustomJsOptions): InputSchema? {
        if (options.scrapeScript.isBlank()) return null
        return InputSchema.Obj(
            properties = buildJsonObject {
                put("urls", buildJsonObject {
                    put("type", "array")
                    put("description", "urls to scrape")
                    put("items", buildJsonObject {
                        put("type", "string")
                    })
                })
            },
            required = listOf("urls")
        )
    }

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val script = serviceOptions.searchScript.ifBlank { error("Search script is empty") }

            val resultJson = executeScript(
                userScript = script,
                invocation = "search(${quoteJsString(query)}, ${commonOptions.resultSize})"
            )

            json.decodeFromString<SearchResult>(resultJson)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.CustomJsOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val script = serviceOptions.scrapeScript.ifBlank { error("Scrape script is empty") }
            val urlsJson = params["urls"]?.toString() ?: error("urls is required")

            val resultJson = executeScript(
                userScript = script,
                invocation = "scrape($urlsJson)"
            )

            json.decodeFromString<ScrapedResult>(resultJson)
        }
    }

    /**
     * 执行用户配置的脚本。
     *
     * **资源防护**（同 app 模块的 `eval_javascript`，见 issue #1903）：脚本是用户手写的不可信输入，
     * 一次 `new Array(1e9)` 会撑爆 native 堆把**整个 app** 拖崩，深递归也有 native 栈溢出风险。
     * 故施加内存与栈上限，把它们转成可捕获的 JS 异常（调用方已包 `runCatching`）。
     *
     * 本模块不依赖 app，故常量在此就近定义（取值与 app 侧 [JsLimits] 一致）。
     */
    private fun executeScript(userScript: String, invocation: String): String {
        val context = QuickJSContext.create()
        try {
            runCatching { context.setMemoryLimit(CUSTOM_JS_MEMORY_LIMIT_BYTES) }
            runCatching { context.setMaxStackSize(CUSTOM_JS_MAX_STACK_BYTES) }
            context.injectFetch(httpClient)
            context.evaluate(userScript)

            val result = context.evaluate("JSON.stringify($invocation)")
            return result as? String ?: error("Function returned null or undefined")
        } finally {
            context.destroy()
        }
    }

    /** native 堆上限：超大分配转成可捕获的 JS 异常，而不是进程 OOM。 */
    private const val CUSTOM_JS_MEMORY_LIMIT_BYTES: Int = 64 * 1024 * 1024

    /** 栈上限：避免深递归打爆 native 栈。 */
    private const val CUSTOM_JS_MAX_STACK_BYTES: Int = 256 * 1024

    private fun quoteJsString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

}
