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

    @Test fun `batch missing notices collapse to providers that still need attention`() {
        val listed = Model(modelId = "listed")
        val missing = Model(modelId = "missing")
        val missingProvider = ProviderSetting.OpenAI(name = "Missing", models = listOf(listed, missing))
        fun snapshot(provider: ProviderSetting, ids: List<String>) = CatalogSnapshot(
            CatalogFetchResult.Complete(ids.map { CatalogEntry(it) }, CatalogScope(provider.id.toString(), listOf("test"))),
            123,
        )
        val missingState = ProviderCatalogState(
            "scope",
            snapshot(missingProvider, listOf("listed")),
            CatalogAttempt("r", CatalogRefreshTrigger.MANUAL, CatalogAttemptStatus.COMPLETE, 0),
        )
        val report = catalogMissingReport(missingProvider, missingState)!!
        val acknowledgedState = missingState.copy(
            missingIncidents = report.models.associate { it.model.id.toString() to it.incident.copy(acknowledged = true) },
        )
        // 目录完整、无缺失的提供商不产生汇总行；停用配置不在刷新目标里，即使有缺失也不计入
        val cleanProvider = ProviderSetting.Google(name = "Clean", models = listOf(listed))
        val disabledProvider = ProviderSetting.Google(name = "Disabled", enabled = false, models = listOf(missing))
        val states = mapOf(
            missingProvider.id to missingState,
            cleanProvider.id to ProviderCatalogState("scope", snapshot(cleanProvider, listOf("listed"))),
            disabledProvider.id to missingState,
        )
        val providers = listOf(missingProvider, cleanProvider, disabledProvider)
        val targets = catalogRefreshTargetIds(providers)

        assertEquals(listOf(missingProvider.id), catalogProvidersNeedingAttention(providers, targets) { states[it] }.map { it.first.id })
        // 折叠汇总的计数：条数=提供商数，模型数=各报告之和
        assertEquals(1, catalogProvidersNeedingAttention(providers, targets) { states[it] }[0].second.models.size)
        // 已确认后该提供商退出汇总（单条提醒的「知道了」语义保持不变）
        assertTrue(catalogProvidersNeedingAttention(providers, targets) { if (it == missingProvider.id) acknowledgedState else states[it] }.isEmpty())
    }
}
