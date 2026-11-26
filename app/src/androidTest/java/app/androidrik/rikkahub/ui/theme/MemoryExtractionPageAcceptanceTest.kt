package app.androidrik.rikkahub.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.MemoryExtractionConfig
import app.androidrik.rikkahub.data.datastore.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryExtractionPageAcceptanceTest : SkinPageAcceptanceProfile(1f) {
    @Before
    fun enableExtraction() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        store.update { it.copy(memoryExtraction = MemoryExtractionConfig(enabled = true, reusePromptCache = true)) }
        store.settingsFlow.first { it.memoryExtraction.reusePromptCache }
        Unit
    }

    @Test
    fun modelSettingsWithExtractionEnabled() {
        matrix(false)
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(3)
        compose.onAllNodesWithText("归纳模型")[0].assertIsDisplayed()
        compose.onAllNodesWithText("尝试复用 Prompt Cache")[0].assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.filesDir, "memory-extraction-settings.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
