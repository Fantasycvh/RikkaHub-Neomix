package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Modality
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

internal fun ProviderSetting.OpenAI.responsesUrl(): String = apiEndpoint(baseUrl, responsesPath)

internal fun ProviderSetting.OpenAI.modelsUrl(): String = "$baseUrl/models"

internal fun ProviderSetting.OpenAI.chatCompletionsUrl(): String =
    apiEndpoint(baseUrl, chatCompletionsPath)

/**
 * 是否为 Codex 端点：API Key 模式下手动把 baseUrl 配成官方 Codex 也认
 * （便于高级用户直接填 `https://chatgpt.com/backend-api/codex`），据此套 Codex 的能力集。
 */
internal fun ProviderSetting.OpenAI.looksLikeCodexEndpoint(): Boolean =
    baseUrl.toHttpUrlOrNull()?.let { url ->
        url.host == "chatgpt.com" && url.encodedPath.startsWith("/backend-api/codex")
    } == true

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
