package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.utils.reorderForDragByKey
import org.junit.Assert.*
import org.junit.Test

class CatalogPresentationTest {
    @Test fun `refresh scope includes empty providers and respects the caller allowed set`() {
        val empty = ProviderSetting.OpenAI(models = emptyList())
        val hidden = ProviderSetting.OpenAI(models = listOf(Model(enabled = false)))
        val disabled = ProviderSetting.Google(enabled = false)
        assertEquals(setOf(empty.id, hidden.id), catalogRefreshTargetIds(listOf(empty, hidden, disabled)))
        assertEquals(setOf(empty.id), catalogRefreshTargetIds(listOf(empty)))
    }
    @Test fun `complete candidates survive a failed or partial refresh`() {
        val scope = CatalogScope("provider", listOf("test"))
        val complete = CatalogSnapshot(CatalogFetchResult.Complete(listOf(CatalogEntry("old")), scope), 123)
        val attempt = CatalogAttempt("request", CatalogRefreshTrigger.MANUAL, CatalogAttemptStatus.PARTIAL, 124,
            partial = CatalogFetchResult.Partial(listOf(CatalogEntry("new")), scope, IncompleteReason.SOURCE_FAILED))
        val state = ProviderCatalogState("scope", complete, attempt)
        assertEquals(listOf("old"), catalogCandidateModels(state).map { it.modelId })
        assertEquals(listOf("new"), catalogCandidateModels(state.copy(lastComplete = null)).map { it.modelId })
    }
    @Test fun `nonselectable raw ids are not added as candidates`() {
        val state = ProviderCatalogState("scope", CatalogSnapshot(CatalogFetchResult.Complete(
            listOf(CatalogEntry("A"), CatalogEntry("a", selectable = false), CatalogEntry("other", type = null)),
            CatalogScope("provider", listOf("test"))), 123))
        assertEquals(listOf("A"), catalogCandidateModels(state).map { it.modelId })
    }
    @Test fun `auto refresh is off for both new settings and old serialized settings`() {
        assertFalse(Settings().catalogAutoRefresh)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<Settings>("{}").catalogAutoRefresh)
        assertTrue(json.decodeFromString<Settings>("{\"catalogAutoRefresh\":true}").catalogAutoRefresh)
    }
    @Test fun `status headers never change which model a drag moves`() {
        val first = Model(modelId = "first")
        val second = Model(modelId = "second")
        val models = listOf(first, second)
        assertEquals(listOf(second, first), models.reorderForDragByKey(first.id, second.id) { it.id })
        assertEquals(models, models.reorderForDragByKey("catalog-status", second.id) { it.id })
    }
}
