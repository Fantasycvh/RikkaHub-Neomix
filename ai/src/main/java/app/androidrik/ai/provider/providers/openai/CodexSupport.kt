package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.OPENAI_CODEX_BASE_URL
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.util.apiEndpoint
import app.androidrik.ai.util.json
import app.androidrik.common.http.jsonArrayOrNull
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Codex 客户端版本。官方 `GET /models?client_version=` 会按它过滤模型，太旧拿不到新模型
 * （与上游 #2186 的 `CODEX_CLIENT_VERSION` 对齐）。
 */
internal const val CODEX_CLIENT_VERSION = "0.163.0"

/** Codex CLI 的客户端标识，官方后端用于识别来源（上游 #2186 同值）。 */
internal const val CODEX_ORIGINATOR = "codex_cli_rs"

/** `prompt_cache_key` 上限（服务端约束，超长会被拒）。 */
private const val CODEX_PROMPT_CACHE_KEY_MAX = 64

internal val ProviderSetting.OpenAI.isChatGPTSubscription: Boolean
    get() = authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION

/**
 * Responses 接口 URL：订阅模式固定官方 Codex 后端（**不覆写** [ProviderSetting.OpenAI.baseUrl]，
 * 切回 API Key 时用户原配置不变）；API Key 模式沿用 baseUrl + responsesPath。
 */
internal fun ProviderSetting.OpenAI.responsesUrl(): String =
    if (isChatGPTSubscription) "$OPENAI_CODEX_BASE_URL/responses"
    else apiEndpoint(baseUrl, responsesPath)

internal fun ProviderSetting.OpenAI.modelsUrl(): String =
    if (isChatGPTSubscription) "$OPENAI_CODEX_BASE_URL/models?client_version=$CODEX_CLIENT_VERSION"
    else "$baseUrl/models"

internal fun ProviderSetting.OpenAI.chatCompletionsUrl(): String =
    if (isChatGPTSubscription) {
        error("ChatGPT 订阅模式只支持 Response API（/responses），不支持 /chat/completions")
    } else {
        apiEndpoint(baseUrl, chatCompletionsPath)
    }

/**
 * 是否为 Codex 端点：订阅模式恒真；API Key 模式下手动把 baseUrl 配成官方 Codex 也认
 * （便于高级用户直接填 `https://chatgpt.com/backend-api/codex`）。
 */
internal fun ProviderSetting.OpenAI.looksLikeCodexEndpoint(): Boolean =
    isChatGPTSubscription || baseUrl.toHttpUrlOrNull()?.let { url ->
        url.host == "chatgpt.com" && url.encodedPath.startsWith("/backend-api/codex")
    } == true

/**
 * 稳定 `prompt_cache_key`：优先用显式 `session-id` 自定义头（会话级接入见设计的 P2），
 * 否则退化为「provider + model」稳定键——同一模型的多轮请求仍能命中服务端路由缓存。
 */
internal fun ProviderSetting.OpenAI.codexPromptCacheKey(modelId: String): String {
    val explicit = customHeaders
        .firstOrNull { it.name.equals("session-id", ignoreCase = true) }
        ?.value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return (explicit ?: "codex-${id}-$modelId").take(CODEX_PROMPT_CACHE_KEY_MAX)
}

/**
 * 解析 `GET /models` 的 Codex 响应：`{"models":[{slug, display_name, visibility, ...}]}`。
 *
 * - 只保留 `visibility == "list"` 的模型（Codex 选择器可见集），该字段缺失/为空则退回全量；
 * - `supported_reasoning_levels` 非空视为推理模型（据此发 `reasoning.effort` 与加密推理）；
 * - `context_window` 供上下文占用显示。
 */
internal fun parseCodexModels(body: String): List<Model> {
    val root = runCatching { json.parseToJsonElement(body).jsonObjectOrNull }.getOrNull() ?: return emptyList()
    val all = root["models"]?.jsonArrayOrNull?.mapNotNull { it.jsonObjectOrNull } ?: return emptyList()
    val visible = all
        .filter { it["visibility"]?.jsonPrimitiveOrNull?.contentOrNull == "list" }
        .ifEmpty { all }
    return visible.mapNotNull { model ->
        val slug = model["slug"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val inputModalities = model["input_modalities"]?.jsonArrayOrNull
            ?.mapNotNull { modality ->
                when (modality.jsonPrimitiveOrNull?.contentOrNull) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Modality.TEXT, Modality.IMAGE)
        val reasoning = model["supported_reasoning_levels"]?.jsonArrayOrNull?.isNotEmpty() == true
        Model(
            modelId = slug,
            displayName = model["display_name"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: slug,
            inputModalities = inputModalities,
            outputModalities = listOf(Modality.TEXT),
            abilities = if (reasoning) {
                listOf(ModelAbility.TOOL, ModelAbility.REASONING)
            } else {
                listOf(ModelAbility.TOOL)
            },
            contextLength = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull,
        )
    }
}
