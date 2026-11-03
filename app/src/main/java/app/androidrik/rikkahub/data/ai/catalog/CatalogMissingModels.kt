package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.Serializable

@Serializable
data class CatalogMissingIncident(val modelId: String, val incidentId: String, val acknowledged: Boolean = false)

enum class CatalogPresenceKind { LISTED, MISSING, UNKNOWN, INDEPENDENT_CONFIGURATION }

data class CatalogModelPresence(val kind: CatalogPresenceKind, val checkedAt: Long? = null)

data class CatalogMissingModel(val model: Model, val incident: CatalogMissingIncident)
data class CatalogMissingReport(val models: List<CatalogMissingModel>, val scopeFingerprint: String, val checkedAt: Long, val historical: Boolean) {
    val needsAttention: Boolean get() = models.any { !it.incident.acknowledged }
}

/** Model-level headers can change gateway tenancy/auth; a body override can change the actual API model ID. */
private fun Model.usesIndependentCatalogScope(): Boolean = providerOverwrite != null ||
    customHeaders.any { it.name.isNotBlank() } || customBodies.any { it.key.trim() == "model" }

fun catalogModelPresence(provider: ProviderSetting, model: Model, state: ProviderCatalogState?): CatalogModelPresence {
    if (model.usesIndependentCatalogScope()) return CatalogModelPresence(CatalogPresenceKind.INDEPENDENT_CONFIGURATION)
    val snapshot = state?.lastComplete?.takeIf { it.catalog.scope.providerId == provider.id.toString() }
        ?: return CatalogModelPresence(CatalogPresenceKind.UNKNOWN)
    val present = snapshot.catalog.entries.any { it.modelId == model.modelId }
    return CatalogModelPresence(if (present) CatalogPresenceKind.LISTED else CatalogPresenceKind.MISSING, snapshot.checkedAt)
}

/** Derive against all raw IDs, including hidden/non-chat entries. Local model UUIDs distinguish duplicate API IDs. */
internal fun reconcileMissingIncidents(provider: ProviderSetting, state: ProviderCatalogState): Map<String, CatalogMissingIncident> {
    val snapshot = state.lastComplete?.takeIf { it.catalog.scope.providerId == provider.id.toString() } ?: return emptyMap()
    val rawIds = snapshot.catalog.entries.map { it.modelId }.toSet()
    return provider.models.filter { !it.usesIndependentCatalogScope() && it.modelId !in rawIds }.associate { model ->
        val id = model.id.toString()
        val previous = state.missingIncidents[id]?.takeIf { it.modelId == model.modelId }
        id to (previous ?: CatalogMissingIncident(model.modelId,
            "${snapshot.snapshotId.ifEmpty { snapshot.checkedAt.toString() }}:$id:${model.modelId}"))
    }
}

fun catalogMissingReport(provider: ProviderSetting, state: ProviderCatalogState?, now: Long = System.currentTimeMillis()): CatalogMissingReport? {
    val snapshot = state?.lastComplete?.takeIf { it.catalog.scope.providerId == provider.id.toString() } ?: return null
    val incidents = reconcileMissingIncidents(provider, state)
    return CatalogMissingReport(provider.models.mapNotNull { model -> incidents[model.id.toString()]?.let { CatalogMissingModel(model, it) } },
        state.scopeFingerprint, snapshot.checkedAt,
        state.lastAttempt?.status != CatalogAttemptStatus.COMPLETE || now - snapshot.checkedAt >= 6 * 60 * 60_000L)
}
