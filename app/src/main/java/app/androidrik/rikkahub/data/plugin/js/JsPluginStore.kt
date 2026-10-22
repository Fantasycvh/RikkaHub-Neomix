package app.androidrik.rikkahub.data.plugin.js

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "JsPluginStore"

/**
 * JS 插件定义的持久化(独立 DataStore,单 key JSON 列表)。
 *
 * 与规则插件一致:只存**定义**,启用状态与能力授权仍由 `PluginManager` 统一管理。
 */
class JsPluginStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(JsPluginDefinition.serializer())

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile("js_plugins") },
    )

    suspend fun read(): List<JsPluginDefinition> {
        val raw = dataStore.data.first()[PLUGINS_KEY] ?: return emptyList()
        return runCatching {
            json.decodeFromString(serializer, raw)
                // 跳过坏定义:一条坏插件不应让整个列表消失
                .filter { it.manifest.validate().isEmpty() }
        }.onFailure { Log.w(TAG, "failed to read js plugins", it) }
            .getOrDefault(emptyList())
    }

    suspend fun save(definitions: List<JsPluginDefinition>) {
        val raw = json.encodeToString(serializer, definitions)
        dataStore.edit { preferences ->
            preferences[PLUGINS_KEY] = raw
        }
    }

    private companion object {
        val PLUGINS_KEY = stringPreferencesKey("js_plugins")
    }
}
