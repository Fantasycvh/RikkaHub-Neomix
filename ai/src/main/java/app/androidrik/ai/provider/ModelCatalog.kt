package app.androidrik.ai.provider

import app.androidrik.ai.util.json
import app.androidrik.common.http.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

/** Remote observations have no local model UUID or user overrides. */
@Serializable
data class CatalogEntry(
    val modelId: String,
    val displayName: String = modelId,
    val type: ModelType? = ModelType.CHAT,
    val selectable: Boolean = true,
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val contextLength: Int? = null,
) {
    fun toModel() = Model(modelId = modelId, displayName = displayName, type = type ?: ModelType.CHAT,
        inputModalities = inputModalities, outputModalities = outputModalities,
        abilities = abilities, contextLength = contextLength)

    companion object {
        fun fromModel(model: Model, selectable: Boolean = true) = CatalogEntry(
            model.modelId, model.displayName, model.type, selectable, model.inputModalities,
            model.outputModalities, model.abilities, model.contextLength,
        )
    }
}

/** Source names are protocol labels, never URLs or credentials. Credential isolation is owned by the app repository. */
@Serializable
data class CatalogScope(val providerId: String, val sources: List<String>)

@Serializable
enum class CatalogErrorKind { NETWORK, TIMEOUT, AUTHENTICATION, RATE_LIMIT, SERVER, PARSE, HTTP, NOT_LOGGED_IN, UNKNOWN }

@Serializable
data class CatalogError(val kind: CatalogErrorKind, val message: String, val httpStatus: Int? = null)

@Serializable
enum class IncompleteReason { MALFORMED_ENTRY, PAGINATION, LIMIT, SOURCE_FAILED }

@Serializable
sealed interface CatalogFetchResult {
    @Serializable
    data class Complete(val entries: List<CatalogEntry>, val scope: CatalogScope) : CatalogFetchResult
    @Serializable
    data class Partial(val entries: List<CatalogEntry>, val scope: CatalogScope,
        val reason: IncompleteReason, val error: CatalogError? = null) : CatalogFetchResult
    @Serializable
    data class Failed(val error: CatalogError) : CatalogFetchResult
    @Serializable
    data class Unsupported(val httpStatus: Int? = null) : CatalogFetchResult
}

fun CatalogFetchResult.candidateModels(): List<Model> = when (this) {
    is CatalogFetchResult.Complete -> entries
    is CatalogFetchResult.Partial -> entries
    is CatalogFetchResult.Failed -> throw IllegalStateException(error.message)
    is CatalogFetchResult.Unsupported -> error("Model catalog endpoint is unavailable or unsupported (HTTP $httpStatus)")
}.filter { it.selectable && it.type != null }.map { it.toModel() }

internal class CatalogFetchException(val error: CatalogError) : IOException(error.message)

internal suspend fun AccountCredentialProvider.catalogCredential(setting: ProviderSetting.Account): OAuthCredential =
    try {
        getCredential(setting, null)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw CatalogFetchException(CatalogError(CatalogErrorKind.AUTHENTICATION, "Unable to authenticate the model catalog; sign in again"))
    }

/** Do not retain server bodies, exception messages, URLs or headers in observable/persisted failures. */
internal fun catalogError(error: Exception): CatalogError = when (error) {
    is CatalogFetchException -> error.error
    is SocketTimeoutException -> CatalogError(CatalogErrorKind.TIMEOUT, "Model catalog request timed out")
    is IOException -> CatalogError(CatalogErrorKind.NETWORK, "Unable to connect to the model catalog")
    is IllegalArgumentException, is IllegalStateException -> CatalogError(CatalogErrorKind.PARSE, "Invalid model catalog response")
    else -> CatalogError(CatalogErrorKind.UNKNOWN, "Unable to fetch the model catalog")
}

internal fun catalogFailure(error: Exception): CatalogFetchResult {
    if (error is CancellationException) throw error
    val detail = catalogError(error)
    return if (detail.httpStatus in listOf(404, 405)) CatalogFetchResult.Unsupported(detail.httpStatus)
    else CatalogFetchResult.Failed(detail)
}

internal suspend fun OkHttpClient.catalogJson(request: Request): JsonObject {
    val networkRequest = request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
    return newCall(networkRequest).await().use { response ->
        if (!response.isSuccessful) {
            val kind = when (response.code) {
                401, 403 -> CatalogErrorKind.AUTHENTICATION
                429 -> CatalogErrorKind.RATE_LIMIT
                in 500..599 -> CatalogErrorKind.SERVER
                else -> CatalogErrorKind.HTTP
            }
            throw CatalogFetchException(CatalogError(kind, "Model catalog request failed (HTTP ${response.code})", response.code))
        }
        json.parseToJsonElement(response.body.string()) as? JsonObject
            ?: throw IllegalArgumentException("Expected catalog object")
    }
}

internal data class CatalogPage(val entries: List<CatalogEntry>, val next: String? = null,
    val incomplete: IncompleteReason? = null)

internal suspend fun collectCatalogPages(
    scope: CatalogScope,
    maxPages: Int = 100,
    maxEntries: Int = 20_000,
    fetch: suspend (String?) -> CatalogPage,
): CatalogFetchResult {
    val entries = linkedMapOf<String, CatalogEntry>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    var receivedPage = false
    var incomplete: IncompleteReason? = null
    try {
        repeat(maxPages) {
            val page = fetch(cursor)
            receivedPage = true
            page.entries.forEach { entry ->
                if (entries.size < maxEntries || entry.modelId in entries) entries.putIfAbsent(entry.modelId, entry)
                else incomplete = IncompleteReason.LIMIT
            }
            incomplete = incomplete ?: page.incomplete
            val next = page.next
            if (next == null) return incomplete?.let { CatalogFetchResult.Partial(entries.values.toList(), scope, it) }
                ?: CatalogFetchResult.Complete(entries.values.toList(), scope)
            if (entries.size >= maxEntries) return CatalogFetchResult.Partial(entries.values.toList(), scope, IncompleteReason.LIMIT)
            if (!cursors.add(next)) return CatalogFetchResult.Partial(entries.values.toList(), scope, IncompleteReason.PAGINATION)
            cursor = next
        }
        return CatalogFetchResult.Partial(entries.values.toList(), scope, IncompleteReason.LIMIT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return if (receivedPage) CatalogFetchResult.Partial(entries.values.toList(), scope, IncompleteReason.SOURCE_FAILED, catalogError(e))
        else catalogFailure(e)
    }
}

internal fun JsonObject.catalogString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

internal fun JsonObject.catalogRows(key: String): JsonArray {
    require(this["error"] == null || this["error"] == JsonNull) { "Catalog contains an error" }
    return this[key] as? JsonArray ?: throw IllegalArgumentException("Missing catalog collection")
}

internal fun JsonObject.catalogPage(key: String, entry: (JsonObject) -> CatalogEntry?): CatalogPage {
    var malformed = false
    val entries = catalogRows(key).mapNotNull { element ->
        val parsed = (element as? JsonObject)?.let(entry)
        if (parsed == null) malformed = true
        parsed
    }
    return CatalogPage(entries, incomplete = if (malformed) IncompleteReason.MALFORMED_ENTRY else null)
}

internal fun parseStandardCatalog(root: JsonObject, cursorPagination: Boolean): CatalogPage {
    val page = root.catalogPage("data") { row -> row.catalogString("id")?.let { id ->
        CatalogEntry(id, row.catalogString("display_name") ?: id)
    } }
    val more = root["has_more"]
    // OpenAI's endpoint is unpaginated. Unknown gateway pagination must not become negative evidence.
    if (!cursorPagination) return if (more != null && (more as? JsonPrimitive)?.booleanOrNull != false ||
        listOf("next", "next_page", "nextPageToken", "next_page_token").any { root[it] != null && root[it] != JsonNull })
        page.copy(incomplete = IncompleteReason.PAGINATION) else page
    if (more == null) return page // compatible unpaginated proxies
    val hasMore = (more as? JsonPrimitive)?.booleanOrNull
        ?: return page.copy(incomplete = IncompleteReason.PAGINATION)
    if (!hasMore) return page
    val next = root.catalogString("last_id") ?: return page.copy(incomplete = IncompleteReason.PAGINATION)
    return page.copy(next = next)
}

internal fun parseGoogleCatalog(root: JsonObject, vertex: Boolean): CatalogPage {
    val key = if (vertex && root["publisherModels"] is JsonArray) "publisherModels" else "models"
    val page = root.catalogPage(key) { row ->
        row.catalogString("name")?.let { name ->
            val id = name.substringAfter("models/")
            if (id.isBlank()) return@let null
            val methods = (row["supportedGenerationMethods"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val type = when {
                "generateContent" in methods -> ModelType.CHAT
                "embedContent" in methods -> ModelType.EMBEDDING
                else -> null
            }
            CatalogEntry(id, row.catalogString("displayName") ?: id, type, type != null)
        }
    }
    val token = root["nextPageToken"]
    if (token == null || token == JsonNull || token == JsonPrimitive("")) return page
    val next = root.catalogString("nextPageToken") ?: return page.copy(incomplete = IncompleteReason.PAGINATION)
    return page.copy(next = next)
}
