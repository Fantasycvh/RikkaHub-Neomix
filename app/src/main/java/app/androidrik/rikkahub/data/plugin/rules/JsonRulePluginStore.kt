package app.androidrik.rikkahub.data.plugin.rules

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private const val TAG = "JsonRulePluginStore"

/**
 * JSON 规则插件定义的持久化(独立 DataStore,单 key JSON 列表)。
 *
 * 只存**定义**;启用状态与能力授权由 `PluginManager` 的状态存储负责
 * (物化后的 [JsonRulePlugin] 与内置插件走同一套状态)。
 */
class JsonRulePluginStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile("plugin_rules") },
    )

    suspend fun read(): List<JsonRuleDefinition> {
        val raw = dataStore.data.first()[RULES_KEY] ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<JsonRuleDefinition>>(raw)
                // 跳过非法条目:一条坏定义不应让整个规则列表消失
                .filter { it.validate().isEmpty() }
        }.onFailure { Log.w(TAG, "failed to read rule plugins", it) }
            .getOrDefault(emptyList())
    }

    suspend fun save(definitions: List<JsonRuleDefinition>) {
        val raw = json.encodeToString(definitions)
        dataStore.edit { preferences ->
            preferences[RULES_KEY] = raw
        }
    }

    private companion object {
        val RULES_KEY = stringPreferencesKey("rule_plugins")
    }
}
