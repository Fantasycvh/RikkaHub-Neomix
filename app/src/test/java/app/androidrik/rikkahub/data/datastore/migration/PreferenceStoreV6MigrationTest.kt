package app.androidrik.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.androidrik.rikkahub.data.datastore.*
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PreferenceStoreV6MigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun oldPreferences() = mutablePreferencesOf(
        SettingsStore.VERSION to 5,
        SettingsStore.SELECT_ASSISTANT to LEGACY_EXAMPLE_ASSISTANT_ID.toString(),
        SettingsStore.ASSISTANTS to JsonInstant.encodeToString(DEFAULT_ASSISTANTS + Assistant(
            id = LEGACY_EXAMPLE_ASSISTANT_ID, systemPrompt = LEGACY_EXAMPLE_PROMPTS.first(),
        )),
        SettingsStore.LAUNCH_COUNT to 42,
    )

    @Test
    fun `upgrade writes settings only after reference migration succeeds`() = runBlocking {
        val before = oldPreferences()
        var moved = false
        val migration = PreferenceStoreV6Migration {
            assertEquals(5, before[SettingsStore.VERSION])
            moved = true
        }
        val after = migration.migrate(before)
        assertTrue(moved)
        assertEquals(6, after[SettingsStore.VERSION])
        assertEquals(DEFAULT_ASSISTANTS, JsonInstant.decodeFromString<List<Assistant>>(after[SettingsStore.ASSISTANTS]!!))
        assertEquals(DEFAULT_ASSISTANT_ID.toString(), after[SettingsStore.SELECT_ASSISTANT])
        assertEquals(true, after[SettingsStore.LEGACY_DEFAULT_ASSISTANT_MERGED])
        assertEquals(42, after[SettingsStore.LAUNCH_COUNT])
        assertFalse(migration.shouldMigrate(after))
    }

    @Test
    fun `database failure does not advance preferences and retry is safe`() = runBlocking {
        val before = oldPreferences()
        val migration = PreferenceStoreV6Migration { error("DB unavailable") }
        assertTrue(runCatching { migration.migrate(before) }.isFailure)
        assertEquals(5, before[SettingsStore.VERSION])
        assertTrue(migration.shouldMigrate(before))
        assertEquals(6, PreferenceStoreV6Migration {}.migrate(before)[SettingsStore.VERSION])
    }

    @Test
    fun `malformed or unknown configuration is preserved rather than discarded`() = runBlocking {
        for (raw in listOf("broken", oldPreferences()[SettingsStore.ASSISTANTS]!!.replace("\"name\":\"\"", "\"futureOption\":true,\"name\":\"\""))) {
            val before = oldPreferences().apply { this[SettingsStore.ASSISTANTS] = raw }
            val after = PreferenceStoreV6Migration { error("must preserve unknown config") }.migrate(before)
            assertEquals(raw, after[SettingsStore.ASSISTANTS])
            assertEquals(before[SettingsStore.SELECT_ASSISTANT], after[SettingsStore.SELECT_ASSISTANT])
        }
    }

    @Test
    fun `real datastore persists upgrade and does not repeat it after reopening`() = runBlocking {
        val file = temporaryFolder.root.resolve("settings.preferences_pb")
        suspend fun openStore(
            migrations: List<DataMigration<Preferences>>,
            action: suspend (DataStore<Preferences>) -> Unit,
        ) {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + job), migrations = migrations, produceFile = { file },
            )
            try {
                action(store)
            } finally {
                job.cancelAndJoin()
            }
        }
        openStore(emptyList()) { it.updateData { oldPreferences() } }
        var calls = 0
        openStore(listOf(PreferenceStoreV6Migration { calls++ })) {
            assertEquals(6, it.data.first()[SettingsStore.VERSION])
            assertEquals(true, it.data.first()[SettingsStore.LEGACY_DEFAULT_ASSISTANT_MERGED])
        }
        openStore(listOf(PreferenceStoreV6Migration { error("already migrated") })) {
            assertEquals(DEFAULT_ASSISTANT_ID.toString(), it.data.first()[SettingsStore.SELECT_ASSISTANT])
            assertEquals(DEFAULT_ASSISTANTS, JsonInstant.decodeFromString<List<Assistant>>(it.data.first()[SettingsStore.ASSISTANTS]!!))
        }
        assertEquals(1, calls)
    }
}
