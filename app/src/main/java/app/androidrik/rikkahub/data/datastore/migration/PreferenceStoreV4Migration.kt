package app.androidrik.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * V4：把本仓旧的 `balanceOption`（余额配置，含单接口/多接口两种模式）迁移成上游 #2186 的
 * `quotaQuery`，随后 `ProviderSetting` 里删除 `BalanceOption` 类型。
 *
 * 映射规则（能力无损）：
 * - `sources` 非空（多接口模式）：`sources` / `expression` 原样搬入，`enabled` 跟随旧开关
 * - 否则（单接口模式）：`apiPath` → `url = "{{baseUrl}}" + apiPath`，`resultPath` 原样
 * - `enabled` / `resultPath` 的缺省值沿用旧数据默认（`false` / `data.total_usage`）
 *
 * 旧字段一律删除（`ignoreUnknownKeys` 能兼容，但保留会让导入导出/分享串继续带死字段）。
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val providersJson = prefs[SettingsStore.PROVIDERS]
        if (providersJson != null) {
            prefs[SettingsStore.PROVIDERS] = migrateProvidersBalanceToQuota(providersJson)
        }
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/**
 * providers JSON 数组里的 `balanceOption` → `quotaQuery`。解析失败时原样返回（宁可保留旧字段，
 * 也不要把用户配置改坏；`ProviderSetting` 反序列化忽略未知字段，旧数据仍能读）。
 */
internal fun migrateProvidersBalanceToQuota(providersJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(providersJson).jsonArray
        val migrated = buildJsonArray {
            root.forEach { element ->
                add(migrateProviderEntry(element))
            }
        }
        JsonInstant.encodeToString(JsonArray.serializer(), migrated)
    }.getOrElse { providersJson }
}

private fun migrateProviderEntry(element: JsonElement): JsonElement {
    val provider = element as? JsonObject ?: return element
    if ("balanceOption" !in provider) return provider

    val balance = (provider["balanceOption"] as? JsonObject)?.let { it }
    val rebuilt = buildJsonObject {
        provider.forEach { (key, value) ->
            if (key != "balanceOption") put(key, value)
        }
        if (balance != null) {
            put("quotaQuery", buildQuotaQuery(balance))
        }
    }
    return rebuilt
}

private fun buildQuotaQuery(balance: JsonObject): JsonObject = buildJsonObject {
    val enabled = balance["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
    put("enabled", JsonPrimitive(enabled))
    val resultPath = balance["resultPath"]?.jsonPrimitive?.content ?: "data.total_usage"
    val sources = balance["sources"] as? JsonArray
    if (sources != null && sources.isNotEmpty()) {
        put("resultPath", JsonPrimitive(resultPath))
        putJsonArray("sources") {
            sources.forEach { source ->
                val obj = source.jsonObject
                add(
                    buildJsonObject {
                        put("name", obj["name"] ?: JsonPrimitive(""))
                        put("apiPath", obj["apiPath"] ?: JsonPrimitive(""))
                        put("resultPath", obj["resultPath"] ?: JsonPrimitive(""))
                    }
                )
            }
        }
        put("expression", balance["expression"] ?: JsonPrimitive(""))
    } else {
        val apiPath = balance["apiPath"]?.jsonPrimitive?.content ?: "/credits"
        // 旧逻辑允许 apiPath 直接写完整 URL（如 "https://relay.example.com/v1/usage"），迁移时不能拼 baseUrl
        val url = if (apiPath.startsWith("http://") || apiPath.startsWith("https://")) {
            apiPath
        } else {
            "{{baseUrl}}".plus(apiPath)
        }
        put("url", JsonPrimitive(url))
        put("resultPath", JsonPrimitive(resultPath))
    }
    // headers 不写：缺省值就是上游的 Authorization: Bearer {{apiKey}}（旧余额链路同款鉴权）
}
