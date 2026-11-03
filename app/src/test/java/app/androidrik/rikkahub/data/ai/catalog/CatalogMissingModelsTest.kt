package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class CatalogMissingModelsTest {
    private val a = Model(modelId = "a", displayName = "A")
    private val b = Model(modelId = "b", displayName = "Friendly B", enabled = false)
    private val c = Model(modelId = "c")
    private val provider = ProviderSetting.OpenAI(models = listOf(a, b, c))
    private fun state(vararg entries: CatalogEntry) = ProviderCatalogState("scope", CatalogSnapshot(
        CatalogFetchResult.Complete(entries.toList(), CatalogScope(provider.id.toString(), listOf("test"))), 1000, "snapshot-1"),
        CatalogAttempt("r", CatalogRefreshTrigger.MANUAL, CatalogAttemptStatus.COMPLETE, 1000))
    @Test fun `all added models are compared by API id including hidden and manual entries`() {
        val report = catalogMissingReport(provider, state(CatalogEntry("a"), CatalogEntry("c")), now = 1000)!!
        assertEquals(listOf(b), report.models.map { it.model })
        assertFalse(report.historical)
        assertTrue(report.needsAttention)
    }
    @Test fun `hidden remote entries count as present even when not selectable`() {
        assertEquals(CatalogPresenceKind.LISTED, catalogModelPresence(provider, b,
            state(CatalogEntry("b", type = null, selectable = false))).kind)
    }
    @Test fun `aliases do not determine presence and IDs remain case sensitive`() {
        val renamed = b.copy(displayName = "a", modelId = "B")
        val p = provider.copy(models = listOf(renamed))
        assertEquals(CatalogPresenceKind.MISSING, catalogModelPresence(p, renamed, state(CatalogEntry("a"), CatalogEntry("b"))).kind)
    }
    @Test fun `partial only unknown or another provider cannot establish absence`() {
        val partial = ProviderCatalogState("scope", lastAttempt = CatalogAttempt("r", CatalogRefreshTrigger.MANUAL,
            CatalogAttemptStatus.PARTIAL, 0, partial = CatalogFetchResult.Partial(emptyList(), CatalogScope(provider.id.toString(), listOf("test")), IncompleteReason.LIMIT)))
        assertNull(catalogMissingReport(provider, partial))
        assertEquals(CatalogPresenceKind.UNKNOWN, catalogModelPresence(provider, b, partial).kind)
        assertNull(catalogMissingReport(provider.copy(id = kotlin.uuid.Uuid.random()), state()))
    }
    @Test fun `overridden models are independent and never counted as missing`() {
        val overridden = b.copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://independent.test"))
        val p = provider.copy(models = listOf(overridden))
        assertEquals(CatalogPresenceKind.INDEPENDENT_CONFIGURATION, catalogModelPresence(p, overridden, state()).kind)
        assertTrue(catalogMissingReport(p, state())!!.models.isEmpty())
    }
    @Test fun `duplicate API IDs remain separate local model records`() {
        val other = Model(modelId = "b", displayName = "Another alias")
        val p = provider.copy(models = listOf(b, other))
        val report = catalogMissingReport(p, state())!!
        assertEquals(2, report.models.size)
        assertEquals(2, report.models.map { it.incident.incidentId }.toSet().size)
    }
    @Test fun `failed checks preserve dated historical evidence rather than inventing new missing models`() {
        val complete = state(CatalogEntry("a"), CatalogEntry("c"))
        val failed = complete.copy(lastAttempt = CatalogAttempt("failed", CatalogRefreshTrigger.MANUAL, CatalogAttemptStatus.FAILED, 1001))
        assertEquals(catalogMissingReport(provider, complete)!!.models, catalogMissingReport(provider, failed)!!.models)
        assertTrue(catalogMissingReport(provider, failed, now = 1002)!!.historical)
        assertTrue(catalogMissingReport(provider, complete, now = 1000 + 6 * 60 * 60_000L)!!.historical)
    }
    @Test fun `model-level headers and request model overrides are not checked with provider credentials`() {
        val customAuth = b.copy(customHeaders = listOf(CustomHeader("Authorization", "different-account")))
        val routed = b.copy(id = kotlin.uuid.Uuid.random(), customBodies = listOf(CustomBody("model", kotlinx.serialization.json.JsonPrimitive("routed-id"))))
        val p = provider.copy(models = listOf(customAuth, routed))
        assertTrue(catalogMissingReport(p, state())!!.models.isEmpty())
        assertEquals(CatalogPresenceKind.INDEPENDENT_CONFIGURATION, catalogModelPresence(p, customAuth, state()).kind)
        assertEquals(CatalogPresenceKind.INDEPENDENT_CONFIGURATION, catalogModelPresence(p, routed, state()).kind)
    }

}
