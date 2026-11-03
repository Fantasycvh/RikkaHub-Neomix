package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OcrTransformerTest {
    @Test
    fun `unconfigured OCR fails visibly before cache or provider access`() {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                OcrTransformer.performOcr(UIMessagePart.Image("file:///image.png"), Settings(providers = emptyList()))
            }
        }
        assertTrue(error.message.orEmpty().contains("Select an OCR model"))
    }

    @Test
    fun `deleted OCR selection is not replaced by an arbitrary available model`() {
        val available = Model(modelId = "available")
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(available))),
            ocrModelId = Uuid.random(),
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking { OcrTransformer.performOcr(UIMessagePart.Image("file:///image.png"), settings) }
        }
    }

    @Test
    fun `hidden OCR model remains usable with its provider override`() {
        val override = ProviderSetting.OpenAI(name = "override")
        val selected = Model(modelId = "vision", enabled = false, providerOverwrite = override)
        val settings = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(selected))),
            ocrModelId = selected.id,
        )
        val (model, provider) = resolveOcrConfiguration(settings)
        assertEquals(selected, model)
        assertEquals(override.id, provider.id)
    }
}
