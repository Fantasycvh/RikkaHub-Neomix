package app.androidrik.rikkahub.ui.pages.setting

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.common.http.CustomHeader
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.media.effectiveMediaProvider
import app.androidrik.rikkahub.data.media.mediaModelCatalog
import app.androidrik.rikkahub.ui.pages.setting.components.toMediaGenerationProvider
import app.androidrik.rikkahub.ui.pages.setting.components.withImportedImageModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProviderMediaImportTest {
    @Test
    fun `import copies connection but creates independent identity without chat models`() {
        val source = ProviderSetting.OpenAI(
            name = "My gateway",
            baseUrl = "https://example.com/v1",
            apiKey = "legacy-key",
            models = listOf(Model(modelId = "chat-model")),
        )
        val imported = source.toMediaGenerationProvider()
        assertEquals(source.name, imported.name)
        assertEquals(source.baseUrl, imported.baseUrl)
        assertEquals("legacy-key", imported.apiKey)
        assertNotEquals(source.id, imported.id)
        assertNotEquals(imported.id, source.toMediaGenerationProvider().id)
        assertTrue(imported.models.isEmpty())
        assertEquals(1, source.models.size)
    }

    @Test
    fun `import uses only first enabled nonempty key`() {
        val source = ProviderSetting.OpenAI(
            apiKey = "legacy",
            apiKeys = listOf(
                ApiKeyEntry("disabled", enabled = false),
                ApiKeyEntry(""),
                ApiKeyEntry("first"),
                ApiKeyEntry("second"),
            ),
        )
        assertEquals("first", source.toMediaGenerationProvider().apiKey)
    }

    @Test
    fun `legacy multiple keys are reduced to one and empty key is allowed`() {
        assertEquals("first", ProviderSetting.OpenAI(apiKey = "  first,second\nthird").toMediaGenerationProvider().apiKey)
        assertEquals("", ProviderSetting.OpenAI(apiKey = "").toMediaGenerationProvider().apiKey)
    }

    @Test
    fun `import copies enabled image models and repeat import preserves existing edits`() {
        val configured = Model(
            modelId = "image-model",
            displayName = "Image Model",
            type = ModelType.IMAGE,
            customHeaders = listOf(CustomHeader("X-Image-Mode", "example")),
        )
        val source = ProviderSetting.OpenAI(
            models = listOf(
                configured,
                configured.copy(enabled = false, modelId = "disabled"),
                configured.copy(modelId = "elsewhere", providerOverwrite = ProviderSetting.OpenAI()),
                configured.copy(modelId = ""),
                Model(modelId = "text-model"),
            ),
        )
        val imported = source.toMediaGenerationProvider()
        val image = imported.models.single()
        assertEquals(MediaKind.IMAGE, image.kind)
        assertEquals(configured.modelId, image.modelId)
        assertEquals(configured.displayName, image.displayName)
        assertEquals(configured.customHeaders, image.customHeaders)
        assertNotEquals(configured.id, image.id)

        val edited = image.copy(displayName = "Locally customized")
        val saved = imported.copyProvider(models = listOf(edited))
        val updatedSource = source.copy(models = source.models + configured.copy(modelId = "new-image"))
        val refreshed = saved.withImportedImageModels(updatedSource)
        assertEquals(saved.id, refreshed.id)
        assertEquals(edited, refreshed.models.first())
        assertEquals(listOf("image-model", "new-image"), refreshed.models.map { it.modelId })
        assertEquals(refreshed, refreshed.withImportedImageModels(updatedSource))
    }

    @Test
    fun `Google import recognizes image output and follows the linked provider`() {
        val source = ProviderSetting.Google(
            apiKey = "initial-key",
            models = listOf(
                Model(modelId = "image-output", outputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
                Model(modelId = "text-output"),
            ),
        )
        val imported = source.toMediaGenerationProvider() as MediaGenerationProviderSetting.Google
        assertEquals(source.id, imported.credentialRef)
        assertEquals("image-output", imported.models.single().modelId)

        val updated = source.copy(
            apiKey = "updated-key", projectId = "new-project", vertexAI = true, useServiceAccount = true,
        )
        val before = Settings(providers = listOf(source), mediaGenerationProviders = listOf(imported))
        val after = before.copy(providers = listOf(updated))
        val effective = after.effectiveMediaProvider(imported)
            as MediaGenerationProviderSetting.Google
        assertEquals("updated-key", effective.apiKey)
        assertEquals("new-project", effective.projectId)
        assertTrue(effective.vertexAI)
        assertEquals(imported.models, effective.models)
        assertNotEquals(
            before.mediaModelCatalog().first().authorizationFingerprint(),
            after.mediaModelCatalog().first().authorizationFingerprint(),
        )
        assertNotEquals(
            after.mediaModelCatalog().first().authorizationFingerprint(),
            after.copy(providers = listOf(updated.copy(projectId = "another-project")))
                .mediaModelCatalog().first().authorizationFingerprint(),
        )
        assertNull(Settings(providers = emptyList()).effectiveMediaProvider(imported))
    }
}
