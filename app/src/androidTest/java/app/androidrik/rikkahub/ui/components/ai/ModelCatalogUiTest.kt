package app.androidrik.rikkahub.ui.components.ai

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.ai.catalog.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelCatalogUiTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }
    @Test fun failedRefreshRetainsTheVisibleCompleteCatalog() {
        val snapshot = CatalogSnapshot(CatalogFetchResult.Complete(listOf(CatalogEntry("remote")), CatalogScope("p", listOf("test"))), 1234567890000L)
        val state = mutableStateOf(ProviderCatalogState("scope", snapshot,
            CatalogAttempt("r", CatalogRefreshTrigger.MANUAL, CatalogAttemptStatus.COMPLETE, 0)))
        compose.setContent { MaterialTheme { CatalogStatusContent(state.value) } }
        compose.onNodeWithText(context.getString(R.string.catalog_complete, 1)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(lastAttempt = CatalogAttempt("r2", CatalogRefreshTrigger.MANUAL,
            CatalogAttemptStatus.FAILED, 1, error = CatalogError(CatalogErrorKind.NETWORK, "network"))) }
        compose.onNodeWithText(context.getString(R.string.catalog_error_network)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.catalog_cached)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.catalog_last_complete, catalogTime(snapshot.checkedAt), 1)).assertIsDisplayed()
    }
    @Test fun partialResultsCannotLookLikeACompleteCatalog() {
        val state = ProviderCatalogState("scope", lastAttempt = CatalogAttempt("r", CatalogRefreshTrigger.MANUAL,
            CatalogAttemptStatus.PARTIAL, 0, partial = CatalogFetchResult.Partial(listOf(CatalogEntry("remote")),
                CatalogScope("p", listOf("test")), IncompleteReason.SOURCE_FAILED)))
        compose.setContent { MaterialTheme { CatalogStatusContent(state) } }
        compose.onNodeWithText(context.getString(R.string.catalog_partial)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.catalog_partial_candidates)).assertIsDisplayed()
    }
}
