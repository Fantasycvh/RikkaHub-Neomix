package app.androidrik.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import app.androidrik.rikkahub.data.datastore.mergeLegacyDefaultAssistant
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

internal class PreferenceStoreV6Migration(
    private val moveReferences: suspend () -> Unit,
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsStore.VERSION] ?: 0) < 6

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        // Unknown fields may contain user configuration: preserve such records rather than guessing.
        val strictJson = Json(JsonInstant) {
            ignoreUnknownKeys = false
            coerceInputValues = false
        }
        val before = runCatching {
            Settings(
                assistants = strictJson.decodeFromString(prefs[SettingsStore.ASSISTANTS] ?: "[]"),
                assistantId = prefs[SettingsStore.SELECT_ASSISTANT]?.let(Uuid::parse) ?: DEFAULT_ASSISTANT_ID,
                removedAssistantIds = strictJson.decodeFromString<List<String>>(
                    prefs[SettingsStore.REMOVED_ASSISTANTS] ?: "[]",
                ).map(Uuid::parse).toSet(),
            )
        }.getOrNull()
        if (before != null) {
            // DB errors must propagate: retaining the old preferences lets DataStore retry safely.
            val after = before.mergeLegacyDefaultAssistant(moveReferences)
            if (after != before) {
                prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(after.assistants)
                prefs[SettingsStore.SELECT_ASSISTANT] = after.assistantId.toString()
                prefs[SettingsStore.REMOVED_ASSISTANTS] = JsonInstant.encodeToString(after.removedAssistantIds.map { it.toString() })
                prefs[SettingsStore.LEGACY_DEFAULT_ASSISTANT_MERGED] = after.legacyDefaultAssistantMerged
            }
        }
        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}
