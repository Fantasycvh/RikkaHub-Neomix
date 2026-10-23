package app.androidrik.rikkahub.ui.pages.setting

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.ui.pages.setting.components.toMediaGenerationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
