package app.androidrik.rikkahub.utils

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderContextLengthTest {
    private val model = Model(modelId = "shared-model", contextLength = 128_000)

    @Test
    fun `model override wins over provider builtin and global defaults`() {
        assertEquals(
            64_000,
            model.copy(customContextLength = 64_000).effectiveContextLength(
                globalOverride = 32_000,
                providerOverride = 256_000,
            ),
        )
        assertEquals(256_000, model.effectiveContextLength(globalOverride = 32_000, providerOverride = 256_000))
        assertEquals(128_000, model.effectiveContextLength(globalOverride = 32_000))
    }

    @Test
    fun `provider default applies to newly added models without changing model overrides`() {
        val custom = model.copy(customContextLength = 64_000)
        val provider = ProviderSetting.OpenAI(models = listOf(custom), contextLengthOverride = 256_000)
        val added = Model(modelId = "new-model", contextLength = 128_000)
        val updated = provider.addModel(added)
        val settings = Settings(providers = listOf(updated))

        assertEquals(64_000, custom.effectiveContextLength(settings))
        assertEquals(256_000, added.effectiveContextLength(settings))
        assertEquals(custom, updated.models.first { it.id == custom.id })
    }

    @Test
    fun `provider lookup uses model identity when providers share model names`() {
        val otherModel = model.copy(id = kotlin.uuid.Uuid.random())
        val settings = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(models = listOf(model), contextLengthOverride = 256_000),
                ProviderSetting.Google(models = listOf(otherModel), contextLengthOverride = 1_000_000),
            ),
        )
        assertEquals(256_000, model.effectiveContextLength(settings))
        assertEquals(1_000_000, otherModel.effectiveContextLength(settings))
    }

    @Test
    fun `independent provider override supplies its own context default`() {
        val overridden = model.copy(providerOverwrite = ProviderSetting.OpenAI(contextLengthOverride = 512_000))
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(overridden), contextLengthOverride = 256_000)),
        )
        assertEquals(512_000, overridden.effectiveContextLength(settings))
        assertEquals(64_000, overridden.copy(customContextLength = 64_000).effectiveContextLength(settings))
    }

    @Test
    fun `clearing provider override restores builtin and global defaults`() {
        val unknown = Model(modelId = "unknown")
        val provider = ProviderSetting.OpenAI(models = listOf(model, unknown), contextLengthOverride = 256_000)
        val settings = Settings(
            providers = listOf(provider.copyProvider(contextLengthOverride = null)),
            contextLengthOverride = 32_000,
        )
        assertEquals(128_000, model.effectiveContextLength(settings))
        assertEquals(32_000, unknown.effectiveContextLength(settings))
    }

    @Test
    fun `all provider types preserve override when copied and serialized`() {
        providerTypes().forEach { provider ->
            val configured = provider.copyProvider(contextLengthOverride = 256_000)
                .copyProvider(name = "Renamed")
            assertEquals(256_000, configured.contextLengthOverride)
            val restored = JsonInstant.decodeFromString<ProviderSetting>(JsonInstant.encodeToString(configured))
            assertEquals(256_000, restored.contextLengthOverride)
            assertEquals("Renamed", restored.name)
        }
    }

    @Test
    fun `legacy provider data without context override keeps previous defaults`() {
        providerTypes().forEach { provider ->
            val encoded = JsonInstant.encodeToString(provider.copyProvider(contextLengthOverride = 256_000))
            val legacy = JsonObject(JsonInstant.parseToJsonElement(encoded).jsonObject - "contextLengthOverride")
            val restored = JsonInstant.decodeFromString<ProviderSetting>(legacy.toString())
            assertNull(restored.contextLengthOverride)
        }
    }

    private fun providerTypes(): List<ProviderSetting> = listOf(
        ProviderSetting.OpenAI(),
        ProviderSetting.Google(),
        ProviderSetting.Claude(),
        ProviderSetting.ChatGPT(),
        ProviderSetting.Grok(),
    )
}
