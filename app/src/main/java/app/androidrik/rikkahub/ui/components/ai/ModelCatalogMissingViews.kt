package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.catalog.*
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.TriangleAlert
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Persistent inline evidence remains available even after its aggregate notice is acknowledged. */
@Composable
fun CatalogModelMarker(provider: ProviderSetting, model: Model, iconOnly: Boolean = false, modifier: Modifier = Modifier) {
    val state = rememberCatalogState(provider.id)
    val presence = catalogModelPresence(provider, model, state)
    var showDetails by remember(provider.id) { mutableStateOf(false) }
    if (presence.kind == CatalogPresenceKind.MISSING) {
        if (iconOnly) {
            Icon(Lucide.TriangleAlert, stringResource(R.string.catalog_model_missing), modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
        } else {
            TextButton(onClick = { showDetails = true }, contentPadding = PaddingValues(0.dp), modifier = modifier) {
                Icon(Lucide.TriangleAlert, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.catalog_model_missing), style = MaterialTheme.typography.labelSmall)
            }
        }
    } else if (!iconOnly && presence.kind == CatalogPresenceKind.INDEPENDENT_CONFIGURATION) {
        Text(stringResource(R.string.catalog_model_independent), modifier, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showDetails) CatalogMissingSheet(provider) { showDetails = false }
}

@Composable
fun CatalogMissingSummary(provider: ProviderSetting, showProviderName: Boolean = false, dense: Boolean = false) {
    val state = rememberCatalogState(provider.id)
    val report = catalogMissingReport(provider, state)
    var showDetails by remember(provider.id) { mutableStateOf(false) }
    if (report != null && report.models.isNotEmpty()) {
        if (report.needsAttention) {
            val label = (if (showProviderName) provider.name + ": " else "") +
                stringResource(R.string.catalog_missing_count, report.models.size)
            // dense：批量折叠面板里按提供商逐条列出，行高与间距都要压到最小（默认 TextButton 的 40dp
            // 最小高度 + 居中内边距会让少数几条就吃掉大半块面板）
            if (dense) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { showDetails = true }
                    .heightIn(min = 28.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.TriangleAlert, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else TextButton(onClick = { showDetails = true }, contentPadding = PaddingValues(0.dp)) {
                Icon(Lucide.TriangleAlert, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        } else if (!showProviderName) IconButton(onClick = { showDetails = true }) {
            Icon(Lucide.TriangleAlert, stringResource(R.string.catalog_missing_details), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDetails) CatalogMissingSheet(provider) { showDetails = false }
}

@Composable
private fun CatalogMissingSheet(provider: ProviderSetting, onDismiss: () -> Unit) {
    val repository = koinInject<ProviderModelCatalogRepository>()
    val state = rememberCatalogState(provider.id)
    val report = catalogMissingReport(provider, state)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        CatalogMissingContent(provider.name, provider is ProviderSetting.Account, state, report,
            onRefresh = { scope.launch { repository.refresh(provider.id) } },
            onAcknowledge = {
                report?.let { shown -> scope.launch {
                    repository.acknowledgeMissing(provider.id, shown.scopeFingerprint, shown.models.map { it.incident.incidentId }.toSet())
                } }
            })
    }
}

/** Stateless content for device verification; refresh and acknowledgement callbacks are supplied by the shared owner. */
@Composable
internal fun CatalogMissingContent(
    providerName: String,
    account: Boolean,
    state: ProviderCatalogState?,
    report: CatalogMissingReport?,
    onRefresh: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(providerName, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.catalog_missing_details), style = MaterialTheme.typography.titleMedium)
        CatalogStatusContent(state)
        Text(stringResource(if (account) R.string.catalog_scope_account else R.string.catalog_scope_key), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.catalog_missing_explanation), style = MaterialTheme.typography.bodySmall)
        if (report == null) Text(stringResource(R.string.catalog_missing_unknown))
        else {
            Text(stringResource(R.string.catalog_checked_at, catalogTime(report.checkedAt)))
            if (report.historical) Text(stringResource(R.string.catalog_missing_historical), style = MaterialTheme.typography.bodySmall)
            if (report.models.isEmpty()) Text(stringResource(R.string.catalog_missing_none))
            report.models.forEach { entry ->
                SelectionContainer {
                    Column {
                        Text(entry.model.displayName.ifBlank { entry.model.modelId }, style = MaterialTheme.typography.titleSmall)
                        Text(entry.model.modelId, style = MaterialTheme.typography.bodySmall)
                        if (entry.incident.acknowledged) Text(stringResource(R.string.catalog_missing_acknowledged), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRefresh, enabled = !state.isRefreshing) { Text(stringResource(R.string.catalog_refresh)) }
            TextButton(onClick = onAcknowledge, enabled = report?.needsAttention == true) { Text(stringResource(R.string.catalog_missing_acknowledge)) }
        }
    }
}
