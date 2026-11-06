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

/**
 * 批量缺失提醒只列「仍需要用户注意」的提供商，顺序沿用 [providers]。
 *
 * 抽成纯函数而不是内联在 Composable 里：总览页/聊天选择器会把这些行折叠成一行汇总，
 * 「汇总条数=提供商数、模型数=各报告之和、已确认项不再出现」是折叠态的唯一行为契约，需要上 JVM 单测。
 * 单条提醒的 `needsAttention` 语义与 [CatalogMissingSummary] 一致（已确认的不再打扰）。
 */
fun catalogProvidersNeedingAttention(
    providers: List<ProviderSetting>,
    targets: Set<Uuid>,
    stateOf: (Uuid) -> ProviderCatalogState?,
): List<Pair<ProviderSetting, CatalogMissingReport>> =
    providers.filter { it.id in targets }.mapNotNull { provider ->
        catalogMissingReport(provider, stateOf(provider.id))?.takeIf { it.needsAttention }?.let { provider to it }
    }
