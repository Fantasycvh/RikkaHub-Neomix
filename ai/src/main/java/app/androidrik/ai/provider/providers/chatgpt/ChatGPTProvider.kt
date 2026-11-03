package app.androidrik.ai.provider.providers.chatgpt

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.AccountCredentialProvider
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.*
import app.androidrik.ai.util.mergeCustomHeaders
import app.androidrik.ai.util.configureClientWithProxy
import kotlinx.serialization.json.*
import app.androidrik.ai.provider.OPENAI_CODEX_BASE_URL
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.UsageWindow
import app.androidrik.ai.provider.providers.configureAccountAuth
import app.androidrik.ai.provider.providers.openai.CODEX_CAPABILITIES
import app.androidrik.ai.provider.providers.openai.CODEX_CLIENT_VERSION
import app.androidrik.ai.provider.providers.openai.CODEX_ORIGINATOR
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.provider.providers.openai.ResponsesEndpoint
import app.androidrik.ai.provider.providers.openai.parseCodexModels
import app.androidrik.ai.provider.providers.withAccountAuthenticator
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.fetchBody
import app.androidrik.ai.util.json
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.time.Instant

// 参照 openai/codex（codex-rs/model-provider-info、codex-rs/login）
// 与上游 #2186 一致：地址写死，凭据只能通过账号登录获得
internal const val CODEX_BASE_URL = OPENAI_CODEX_BASE_URL

// 订阅用量，参照 codex-rs/backend-client 的 rate_limit_status_url
private const val CODEX_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"

/** 与上游一致：订阅用量接口，取 `rate_limit` 下的两个窗口（通常是 5 小时和一周）。 */
internal fun parseCodexUsage(body: String): ProviderQuota.UsageLimits {
    val rateLimit = json.parseToJsonElement(body).jsonObjectOrNull?.get("rate_limit") as? JsonObject
    val windows = listOf("primary_window", "secondary_window").mapNotNull { key ->
        val window = rateLimit?.get(key) as? JsonObject ?: return@mapNotNull null
        val usedPercent = window["used_percent"]?.jsonPrimitiveOrNull?.doubleOrNull ?: return@mapNotNull null
        val seconds = window["limit_window_seconds"]?.jsonPrimitiveOrNull?.longOrNull
        val resetAt = window["reset_at"]?.jsonPrimitiveOrNull?.longOrNull
        UsageWindow(
            usedPercent = usedPercent,
            windowMinutes = seconds?.takeIf { it > 0 }?.let { ((it + 59) / 60).toInt() },
            resetsAt = resetAt?.takeIf { it > 0 }?.let { Instant.fromEpochSeconds(it) },
        )
    }
    return ProviderQuota.UsageLimits(
        windows = windows,
        limitReached = rateLimit?.get("limit_reached")?.jsonPrimitiveOrNull?.booleanOrNull == true,
    )
}

/**
 * ChatGPT 账号登录后走 Codex 后端。协议是 Responses API，但和 api.openai.com 有几处不同：
 *
 * - 只支持流式；[generateText] 用流式结果拼出完整消息
 * - 请求体的差异见 [CODEX_CAPABILITIES]（system 以 developer 消息放 input 开头、不吃采样参数、
 *   必须请求并回传加密推理内容）
 * - 鉴权除了 Bearer token，还要带 `ChatGPT-Account-ID` 和 `originator`
 *
 * 与上游 #2186 的偏离：`CODEX_CAPABILITIES` / `CODEX_CLIENT_VERSION` / `CODEX_ORIGINATOR` /
 * `parseCodexModels` 复用本仓 `providers/openai/CodexSupport.kt` 里已有的定义（旧的 Codex 设备码
 * 订阅链路仍在用它们），不再在 chatgpt 包里重复一份。
 */
class ChatGPTProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials)
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun fetchModelCatalog(providerSetting: ProviderSetting.ChatGPT): CatalogFetchResult {
        val scope = CatalogScope(providerSetting.id.toString(), listOf("chatgpt"))
        return collectCatalogPages(scope) {
            // Obtain/refresh once, then pin this catalog request to that account credential.
            val credential = credentials.catalogCredential(providerSetting)
            val request = Request.Builder().url("$CODEX_BASE_URL/models?client_version=$CODEX_CLIENT_VERSION")
                .headers(providerSetting.mergeCustomHeaders()).header("Authorization", "Bearer ${credential.accessToken}")
                .apply { credential.accountId?.let { header("ChatGPT-Account-ID", it) } }
                .header("originator", CODEX_ORIGINATOR).get().build()
            parseCodexCatalog(client.configureClientWithProxy(providerSetting.proxy).catalogJson(request))
        }
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.ChatGPT): ProviderQuota {
        val request = Request.Builder()
            .url(CODEX_USAGE_URL)
            .apply { configureAuth(providerSetting) }
            .get()
            .build()
        return parseCodexUsage(client.fetchBody(request))
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = responseAPI.streamText(
        endpoint = ResponsesEndpoint(
            url = "$CODEX_BASE_URL/responses",
            capabilities = CODEX_CAPABILITIES,
            configureRequest = {
                configureAuth(providerSetting, params.customHeaders)
                params.sessionId?.let { header("session-id", it) }
            },
            reasoningOverride = providerSetting.reasoningOverride,
            proxy = providerSetting.proxy,
            // Codex 后端不发 Content-Type: text/event-stream，需要补 Accept 头与补头拦截器
            forceEventStream = true,
        ),
        messages = messages,
        params = params,
    )

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var result = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id)
        )
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) finish = chunk
            result = handler.handle(result, chunk)
        }
        val message = result.last()
        return TextGenerationResult(
            id = finish?.responseId.orEmpty(),
            model = finish?.model ?: params.model.modelId,
            message = message,
            finishReason = finish?.finishReason,
            usage = message.usage,
        )
    }

    private suspend fun Request.Builder.configureAuth(
        setting: ProviderSetting.ChatGPT,
        customHeaders: List<CustomHeader> = emptyList(),
    ) = configureAccountAuth(credentials, setting, customHeaders) { credential ->
        credential.accountId?.let { header("ChatGPT-Account-ID", it) }
        header("originator", CODEX_ORIGINATOR)
    }
}

internal fun parseCodexCatalog(root: JsonObject): CatalogPage {
    val candidates = parseCodexModels(root.toString()).associateBy { it.modelId }
    val page = root.catalogPage("models") { row ->
        row.catalogString("slug")?.let { id ->
            candidates[id]?.let { CatalogEntry.fromModel(it) }
                ?: CatalogEntry(id, row.catalogString("display_name") ?: id, selectable = false)
        }
    }
    return if ((root["has_more"] as? JsonPrimitive)?.booleanOrNull == true ||
        listOf("next", "next_page", "nextPageToken").any { root[it] != null && root[it] != JsonNull })
        page.copy(incomplete = IncompleteReason.PAGINATION) else page
}
