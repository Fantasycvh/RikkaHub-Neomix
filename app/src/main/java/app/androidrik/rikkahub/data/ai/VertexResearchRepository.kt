package app.androidrik.rikkahub.data.ai

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.providers.GoogleProvider
import app.androidrik.ai.provider.providers.google.VertexResearchInteraction
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

@Serializable
data class VertexResearchTask(val prompt: String, val interaction: VertexResearchInteraction)

/** The service keeps running remotely. This store retains the interaction ID and downloaded result. */
class VertexResearchRepository(
    context: Context,
    private val providers: ProviderManager,
    private val json: Json,
) {
    private val storage = PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile("vertex_deep_research") },
    )
    private val operations = Mutex()

    fun observe(provider: ProviderSetting.Google) = storage.data.map { prefs ->
        decode(prefs[provider.taskKey()])
    }.distinctUntilChanged()

    suspend fun start(provider: ProviderSetting.Google, prompt: String) {
        operations.withLock {
            val key = provider.taskKey()
            check(decode(storage.data.first()[key]).none { !it.interaction.isTerminal }) {
                "A Deep Research task is already running for this project."
            }
            val task = VertexResearchTask(prompt, google(provider).startDeepResearch(provider, prompt))
            storage.edit { prefs -> prefs[key] = json.encodeToString(listOf(task) + decode(prefs[key])) }
        }
    }

    suspend fun refresh(provider: ProviderSetting.Google, id: String) =
        update(provider, id) { google(provider).getDeepResearch(provider, id) }

    suspend fun cancel(provider: ProviderSetting.Google, id: String) =
        update(provider, id) { google(provider).cancelDeepResearch(provider, id) }

    suspend fun remove(provider: ProviderSetting.Google, id: String) {
        operations.withLock {
            val key = provider.taskKey()
            storage.edit { prefs ->
                prefs[key] = json.encodeToString(decode(prefs[key]).filterNot {
                    it.interaction.id == id && it.interaction.isTerminal
                })
            }
        }
    }

    private suspend fun update(
        provider: ProviderSetting.Google,
        id: String,
        fetch: suspend () -> VertexResearchInteraction,
    ) {
        operations.withLock {
            val key = provider.taskKey()
            val task = decode(storage.data.first()[key]).firstOrNull { it.interaction.id == id }
            if (task == null || task.interaction.isTerminal) return@withLock
            val remote = fetch()
            val previous = task.interaction
            val next = task.copy(interaction = remote.copy(
                // Some status/cancel responses have no steps.
                progress = remote.progress ?: previous.progress,
                report = remote.report ?: previous.report,
                citations = (previous.citations + remote.citations).distinct(),
            ))
            if (task != next) storage.edit { prefs ->
                prefs[key] = json.encodeToString(decode(prefs[key]).map {
                    if (it.interaction.id == id) next else it
                })
            }
        }
    }

    private fun google(provider: ProviderSetting.Google): GoogleProvider =
        providers.getProviderByType(provider) as GoogleProvider

    private fun decode(value: String?): List<VertexResearchTask> =
        value?.let { json.decodeFromString<List<VertexResearchTask>>(it) }.orEmpty()

    private fun ProviderSetting.Google.taskKey(): Preferences.Key<String> {
        val identity = "$id\n${projectId.trim()}\n$location\n${serviceAccountEmail.trim()}"
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return stringPreferencesKey(hash.joinToString("") { "%02x".format(it) })
    }
}
