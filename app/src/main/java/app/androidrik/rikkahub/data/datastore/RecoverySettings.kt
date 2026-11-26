package app.androidrik.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import app.androidrik.rikkahub.data.ai.account.ensureCatalogSessions
import app.androidrik.rikkahub.data.datastore.SettingsStore.Companion.decodeSettings
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

internal const val RECOVERY_LARGE_FIELD_MIN_CHARS = 5_000_000

internal fun recoverySettingsFile(context: Context): File = context.preferencesDataStoreFile("settings")

/** Use only while startup is paused, so the main SettingsStore cannot own the live file. */
internal class RecoverySettings(private val context: Context) {
    suspend fun readForBackup(): Settings {
        val source = recoverySettingsFile(context)
        // Decoding builds several copies of the preferences and their embedded JSON.
        val decodeBudget = minOf(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)
        check(source.length() <= decodeBudget) { "Settings file is too large to decode in recovery" }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "recovery-settings-").toFile()
        try {
            val copy = File(directory, source.name)
            if (source.exists()) source.copyTo(copy)
            // V6 also moves database references. Keep the current IDs paired with the offline database.
            val migrations = listOf(
                PreferenceStoreV1Migration(),
                PreferenceStoreV2Migration(),
                PreferenceStoreV3Migration(),
                PreferenceStoreV4Migration(),
                PreferenceStoreV5Migration(),
            )
            return withStore(copy, migrations) { store ->
                store.data.onEach {
                    check(collectLargeStoredFields(it, RECOVERY_LARGE_FIELD_MIN_CHARS).isEmpty()) {
                        "Settings contain oversized fields"
                    }
                }.decodeSettings().first().let { settings ->
                    settings.copy(providers = settings.providers.ensureCatalogSessions(legacy = true))
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    suspend fun largeFields(): List<Pair<String, Int>> = withStore(recoverySettingsFile(context)) {
        collectLargeStoredFields(it.data.first(), RECOVERY_LARGE_FIELD_MIN_CHARS)
    }

    suspend fun resetField(name: String) {
        val key = SettingsStore.RESETTABLE_LARGE_KEYS.first { it.name == name }
        withStore(recoverySettingsFile(context)) { it.edit { preferences -> preferences.remove(key) } }
    }

    suspend fun selectAssistant(id: Uuid) {
        withStore(recoverySettingsFile(context)) {
            it.edit { preferences -> preferences[SettingsStore.SELECT_ASSISTANT] = id.toString() }
        }
    }

    /**
     * 安全模式导入恢复时读取当前设置（供保留设备态字段与排除类别回填）；
     * 若现存设置已损坏或超大解码失败，退回干净默认设置，不阻断恢复覆盖。
     */
    suspend fun readForRestoreOrDefault(): Settings =
        runCatching { readForBackup() }.getOrElse { Settings.dummy().copy(init = false) }

    /**
     * 安全模式导入恢复时把恢复后的设置直接写入 DataStore（无需启动 Koin / Room）。
     * 与 [SettingsStore.update] 共用 [SettingsStore.writeSettingsToPreferences]，保证键集一致。
     */
    suspend fun writeRestored(settings: Settings) {
        require(!settings.init) { "Backup contains uninitialized settings" }
        val corrected = settings.copy(
            webServerJwtEnabled = true,
            providers = settings.providers.ensureCatalogSessions(),
            favoriteModels = normalizeFavoriteModelIds(settings.favoriteModels, settings.providers),
        )
        withStore(recoverySettingsFile(context)) { store ->
            store.edit { preferences ->
                SettingsStore.writeSettingsToPreferences(preferences, settings, corrected)
            }
        }
    }

    private suspend fun <T> withStore(
        file: File,
        migrations: List<DataMigration<Preferences>> = emptyList(),
        block: suspend (DataStore<Preferences>) -> T,
    ): T {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            migrations = migrations,
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return try {
            block(store)
        } finally {
            withContext(NonCancellable) { job.cancelAndJoin() }
        }
    }
}
