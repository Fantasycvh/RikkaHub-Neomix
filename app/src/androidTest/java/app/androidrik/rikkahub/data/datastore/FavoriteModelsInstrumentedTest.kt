package app.androidrik.rikkahub.data.datastore

import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.utils.JsonInstant
import io.pebbletemplates.pebble.PebbleEngine
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/** Real settings read/write paths, using only a temporary cache profile. */
@RunWith(AndroidJUnit4::class)
class FavoriteModelsInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var ownsKoin = false
    private val first = Model(modelId = "first", id = Uuid.parse("a40c316e-6506-40bd-ae33-33e218697be9"))
    private val second = Model(modelId = "second")
    private val providers: List<ProviderSetting> = listOf(ProviderSetting.OpenAI(models = listOf(first, second)))
    private val expected get() = listOf(second.id, first.id)
    private val dirty get() = listOf(second.id, first.id, second.id, Uuid.random(), first.id)

    @Before
    fun startDependencies() {
        if (GlobalContext.getOrNull() == null) {
            startKoin { modules(module { single { PebbleEngine.Builder().build() } }) }
            ownsKoin = true
        }
    }

    @After
    fun stopDependencies() {
        if (ownsKoin) stopKoin()
    }

    private suspend fun withProfile(test: suspend (File, SettingsStore) -> Unit) {
        val directory = File(context.cacheDir, "favorite-regression-${Uuid.random()}").apply { mkdirs() }
        val scope = AppScope()
        val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val seedFile = File(directory, "seed.preferences_pb")
            val seed = PreferenceDataStoreFactory.create(scope = seedScope, produceFile = { seedFile })
            seed.edit {
                it[SettingsStore.VERSION] = 5
                it[SettingsStore.PROVIDERS] = JsonInstant.encodeToString(providers)
                it[SettingsStore.FAVORITE_MODELS] = JsonInstant.encodeToString(dirty)
            }
            val settingsFile = File(directory, "settings.preferences_pb")
            seedFile.copyTo(settingsFile)
            val isolated = object : ContextWrapper(context) {
                override fun getFilesDir(): File = directory
            }
            val store = SettingsStore(isolated, scope, settingsFile)
            withTimeout(10_000) { store.settingsFlow.first { !it.init } }
            test(directory, store)
        } finally {
            scope.cancel()
            seedScope.cancel()
            check(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyDuplicateFavoritesLoadWithoutRepeatedKeys() = runBlocking {
        withProfile { _, store ->
            // The previous rendering path produced exactly the issue's duplicate favorite key.
            val oldKeys = dirty.filter { id -> providers.any { p -> p.models.any { it.id == id } } }
                .map { "favorite:$it" }
            assertTrue(oldKeys.count { it == "favorite:${first.id}" } > 1)
            assertEquals(expected, store.settingsFlow.value.favoriteModels)
            assertEquals(expected, store.settingsFlowRaw.first().favoriteModels)
        }
    }

    @Test
    fun duplicateUpdatesAreCleanInMemoryOnDiskAndAfterReload() = runBlocking {
        withProfile { directory, store ->
            // Covers native repeated additions, web list replacement, and restored snapshots.
            repeat(3) { store.update { it.copy(favoriteModels = dirty) } }
            assertEquals(expected, store.settingsFlow.value.favoriteModels)
            val disk = File(directory, "settings.preferences_pb")
                .copyTo(File(directory, "readback.preferences_pb"))
            val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val reloadScope = AppScope()
            try {
                val raw = PreferenceDataStoreFactory.create(scope = readScope, produceFile = { disk })
                val persisted = JsonInstant.decodeFromString<List<Uuid>>(raw.data.first()[SettingsStore.FAVORITE_MODELS]!!)
                assertEquals(expected, persisted)
                val reloadFile = disk.copyTo(File(directory, "reloaded.preferences_pb"))
                val reloaded = SettingsStore(context, reloadScope, reloadFile)
                val loaded = withTimeout(10_000) { reloaded.settingsFlow.first { !it.init } }
                assertEquals(expected, loaded.favoriteModels)
            } finally {
                readScope.cancel()
                reloadScope.cancel()
            }
        }
    }
}
