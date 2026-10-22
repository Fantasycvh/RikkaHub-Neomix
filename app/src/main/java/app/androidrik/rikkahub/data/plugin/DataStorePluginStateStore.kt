package app.androidrik.rikkahub.data.plugin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.androidrik.plugin.manager.PluginState
import app.androidrik.plugin.manager.PluginStateStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * 插件状态的 DataStore 实现(单 key JSON,整份读写)。
 *
 * 独立文件 `plugin_states.preferences_pb`,不进主设置 DataStore:
 * 插件状态是附加数据,坏掉不应影响用户核心设置(与设计文档 §2.4 的
 * "单 key JSON"一致;PreferencesStore 负责主设置,这里只放插件开关与授权)。
 */
class DataStorePluginStateStore(context: Context) : PluginStateStore {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { appContext.preferencesDataStoreFile("plugin_states") },
    )

    override suspend fun read(): Map<String, PluginState> {
        val raw = dataStore.data.first()[PLUGIN_STATES_KEY] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PluginState>>(raw)
        }.getOrDefault(emptyMap())
    }

    override suspend fun write(states: Map<String, PluginState>) {
        val raw = json.encodeToString(states)
        dataStore.edit { preferences ->
            preferences[PLUGIN_STATES_KEY] = raw
        }
    }

    private companion object {
        val PLUGIN_STATES_KEY = stringPreferencesKey("plugin_states")
    }
}
