package app.androidrik.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * V5：把本仓旧的「OpenAI ChatGPT 订阅」提供商迁成上游 #2186 的账号类
 * [app.androidrik.ai.provider.ProviderSetting.ChatGPT]，让存量订阅用户在 P4 的账号界面里原地可用
 * （旧 `codexAccount` 设备码链路 P5 才决定去留，**本迁移不删任何旧代码**）。
 *
 * 旧数据形如：
 * ```
 * {"type":"openai","authType":"chatgpt_subscription",
 *  "codexAccount":{"accountId":…,"email":…,"planType":…,"expiresAt":…,
 *                  "accessTokenEncrypted":"v1:…","refreshTokenEncrypted":"v1:…"}}
 * ```
 *
 * 转换规则（`migrateProviderCodexAccountEntry`）：
 * - 只处理 `type == "openai"` 且属于旧订阅的条目，其余 provider 原样保留；
 * - 旧订阅判定：`authType == "chatgpt_subscription"`，或 `authType` **缺省**且带 `codexAccount`；
 * - **显式 `authType == "api_key"` 一律不动**：用户已把该 provider 切回 API Key（旧界面切回时
 *   只改 authType、会遗留 `codexAccount`），旧请求链路本来就不读 `codexAccount`——把它迁走等于
 *   删掉一份仍然可用的 API Key 配置；
 * - 保留 `id`（历史消息的 `modelId` 绑在 provider 内的模型上，订阅锁也按 provider id 判定）、
 *   `enabled`、`name`、`models`、`proxy`、`customHeaders`、`retryOption`、`reasoningOverride`；
 * - `credential` 由 `codexAccount` 逐字段搬：**密文原样搬运，不解密也不重新加密**
 *   （`SecretCipher` 密文带 `v1:` 前缀，账号层读设置时自行解密）；
 * - 两个 token 都为空 ⇒ 视为未登录，不写 `credential`（账号类只有 `credential == null` 才表示
 *   未登录；写成空 token 的 credential 会被账号层当成已登录、发请求时才失败）；
 * - 旧 OpenAI 专有字段（`codexAccount`/`authType`/`apiKey`/`baseUrl`/`quotaQuery`…）丢弃，
 *   账号类没有这些字段。
 *
 * 幂等：已是 `type == "chatgpt"` 的条目不会被再次处理；DataStore 侧还有 `data_version` 版本门
 * （4→5）兜底。[SettingsJsonMigrator] 的 V7 备份迁移复用同一份 JSON 变换。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.PROVIDERS]?.let { json ->
            prefs[SettingsStore.PROVIDERS] = migrateProvidersCodexAccountToChatGPT(json)
        }
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

private const val PROVIDER_TYPE_OPENAI = "openai"
private const val PROVIDER_TYPE_CHATGPT = "chatgpt"
private const val AUTH_TYPE_CHATGPT_SUBSCRIPTION = "chatgpt_subscription"
private const val AUTH_TYPE_API_KEY = "api_key"

/**
 * 旧 OpenAI 订阅与账号类 ChatGPT 语义一致的字段（账号类没有 apiKey/baseUrl/quotaQuery 之类，
 * 显式列出白名单可避免把旧字段原样带进新 provider）。
 */
private val ACCOUNT_PROVIDER_FIELDS = listOf(
    "id",
    "enabled",
    "name",
    "models",
    "proxy",
    "customHeaders",
    "retryOption",
    "reasoningOverride",
)

/**
 * providers JSON 数组里的旧订阅 OpenAI → ChatGPT 账号提供商。
 * 解析失败时原样返回（宁可保留旧字段，也不要把用户配置改坏）。
 */
internal fun migrateProvidersCodexAccountToChatGPT(providersJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(providersJson).jsonArray
        val migrated = JsonArray(root.map(::migrateProviderCodexAccountEntry))
        JsonInstant.encodeToString(JsonArray.serializer(), migrated)
    }.getOrElse { providersJson }
}

/**
 * 单条 provider：旧订阅 OpenAI → ChatGPT，其余原样返回（幂等：`type == "chatgpt"` 直接跳过）。
 */
internal fun migrateProviderCodexAccountEntry(element: JsonElement): JsonElement {
    val provider = element as? JsonObject ?: return element
    if (provider.stringField("type") != PROVIDER_TYPE_OPENAI) return provider
    if (!provider.isLegacyCodexSubscription()) return provider
    return buildChatGptProvider(provider)
}

/** 是否是「仍在订阅态」的旧 OpenAI provider。 */
private fun JsonObject.isLegacyCodexSubscription(): Boolean {
    when (stringField("authType")) {
        AUTH_TYPE_CHATGPT_SUBSCRIPTION -> return true
        // 用户显式切回 API Key：旧请求链路本来就不读 codexAccount，保留这份可用配置
        AUTH_TYPE_API_KEY -> return false
    }
    // authType 缺省（该字段出现之前的数据 / 异构来源）：带订阅账号才转换
    return codexAccount() != null
}

private fun buildChatGptProvider(provider: JsonObject): JsonObject = buildJsonObject {
    put("type", JsonPrimitive(PROVIDER_TYPE_CHATGPT))
    ACCOUNT_PROVIDER_FIELDS.forEach { field ->
        // 缺省/显式 null 的字段不写，解码时走 ProviderSetting.ChatGPT 的默认值
        provider[field]?.takeIf { it !is JsonNull }?.let { put(field, it) }
    }
    buildOAuthCredential(provider.codexAccount())?.let { put("credential", it) }
}

private fun JsonObject.codexAccount(): JsonObject? = this["codexAccount"] as? JsonObject

/**
 * `CodexSubscriptionAccount` → `OAuthCredential` 的 JSON。
 *
 * **密文原样搬运**：旧的 `accessTokenEncrypted`/`refreshTokenEncrypted` 已经是
 * `SecretCipher`（`v1:` 前缀）密文，账号层读设置时会解密，这里既不剥前缀也不重新加密。
 */
private fun buildOAuthCredential(account: JsonObject?): JsonObject? {
    val accessToken = account?.stringField("accessTokenEncrypted").orEmpty()
    val refreshToken = account?.stringField("refreshTokenEncrypted").orEmpty()
    // 没有任何令牌 = 从未完成登录：保持「未登录」语义
    if (accessToken.isBlank() && refreshToken.isBlank()) return null
    return buildJsonObject {
        put("accessToken", JsonPrimitive(accessToken))
        if (refreshToken.isNotBlank()) put("refreshToken", JsonPrimitive(refreshToken))
        put("expiresAt", JsonPrimitive(account?.longField("expiresAt") ?: 0L))
        account?.stringField("accountId")?.takeIf { it.isNotBlank() }
            ?.let { put("accountId", JsonPrimitive(it)) }
        // email/plan 是展示字段（保持明文），空白视为未知
        account?.stringField("email")?.takeIf { it.isNotBlank() }
            ?.let { put("email", JsonPrimitive(it)) }
        account?.stringField("planType")?.takeIf { it.isNotBlank() }
            ?.let { put("plan", JsonPrimitive(it)) }
    }
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longField(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
