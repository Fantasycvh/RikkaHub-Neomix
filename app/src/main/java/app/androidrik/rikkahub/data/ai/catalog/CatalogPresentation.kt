package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

fun catalogRefreshTargetIds(providers: List<ProviderSetting>): Set<Uuid> =
    providers.filter { it.enabled }.map { it.id }.toSet()

val ProviderCatalogState?.isRefreshing: Boolean
    get() = this?.lastAttempt?.status in listOf(CatalogAttemptStatus.QUEUED, CatalogAttemptStatus.RUNNING)

/** Prefer the last complete catalog; partial candidates are usable only when none exists. */
fun catalogCandidateModels(state: ProviderCatalogState?): List<Model> =
    (state?.lastComplete?.catalog?.entries ?: state?.lastAttempt?.partial?.entries).orEmpty()
        .filter { it.selectable && it.type != null }.sortedBy { it.modelId }.map { it.toModel() }
