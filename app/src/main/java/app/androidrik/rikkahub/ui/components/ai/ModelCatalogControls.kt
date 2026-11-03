package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.catalog.*
import app.androidrik.rikkahub.data.datastore.SettingsStore
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.ListChecks
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import kotlin.uuid.Uuid

@Composable
fun rememberCatalogState(providerId: Uuid): ProviderCatalogState? {
    val repository = koinInject<ProviderModelCatalogRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val states by repository.states.collectAsStateWithLifecycle()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    return remember(providerId, states, settings) { repository.currentState(providerId) }
}

/** Scope uses full allowed configuration IDs, never search results or models currently visible in a list. */
@Composable
fun CatalogAutoRefreshEffect(providers: List<ProviderSetting>) {
    val repository = koinInject<ProviderModelCatalogRepository>()
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val ids = catalogRefreshTargetIds(providers)
    LaunchedEffect(settings.catalogAutoRefresh, ids) {
        if (settings.catalogAutoRefresh) supervisorScope {
            ids.forEach { id -> launch { repository.refresh(id, CatalogRefreshTrigger.AUTOMATIC) } }
        }
    }
}

internal fun catalogTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

@Composable
internal fun catalogErrorText(error: CatalogError?): String = stringResource(when (error?.kind) {
    CatalogErrorKind.AUTHENTICATION -> R.string.catalog_error_auth
    CatalogErrorKind.NOT_LOGGED_IN -> R.string.catalog_error_login
    CatalogErrorKind.TIMEOUT -> R.string.catalog_error_timeout
    CatalogErrorKind.NETWORK -> R.string.catalog_error_network
    CatalogErrorKind.RATE_LIMIT -> R.string.catalog_error_rate_limit
    CatalogErrorKind.SERVER -> R.string.catalog_error_server
    CatalogErrorKind.PARSE -> R.string.catalog_error_parse
    else -> R.string.catalog_error_unknown
}) + (error?.httpStatus?.let { " (HTTP $it)" } ?: "")

@Composable
internal fun CatalogStatusContent(state: ProviderCatalogState?, compact: Boolean = false) {
    val attempt = state?.lastAttempt
    val status = when (attempt?.status) {
        CatalogAttemptStatus.QUEUED -> stringResource(R.string.catalog_queued)
        CatalogAttemptStatus.RUNNING -> stringResource(R.string.catalog_running)
        CatalogAttemptStatus.COMPLETE -> stringResource(R.string.catalog_complete, state.lastComplete?.catalog?.entries?.size ?: 0)
        CatalogAttemptStatus.PARTIAL -> stringResource(R.string.catalog_partial)
        CatalogAttemptStatus.FAILED -> catalogErrorText(attempt.error)
        CatalogAttemptStatus.UNSUPPORTED -> stringResource(R.string.catalog_unsupported) + (attempt.httpStatus?.let { " (HTTP $it)" } ?: "")
        CatalogAttemptStatus.INTERRUPTED -> stringResource(R.string.catalog_interrupted)
        CatalogAttemptStatus.SKIPPED -> stringResource(R.string.catalog_skipped)
        else -> stringResource(R.string.catalog_idle)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        state?.lastComplete?.let { snapshot ->
            Text(stringResource(R.string.catalog_last_complete, catalogTime(snapshot.checkedAt), snapshot.catalog.entries.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact && attempt?.status != CatalogAttemptStatus.COMPLETE) {
                Text(stringResource(R.string.catalog_cached), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (!compact && state?.lastComplete == null && attempt?.partial != null) {
            Text(stringResource(R.string.catalog_partial_candidates), style = MaterialTheme.typography.labelSmall)
        }
        if (state?.cacheWriteFailed == true) Text(stringResource(R.string.catalog_save_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun CatalogProviderStatus(provider: ProviderSetting, modifier: Modifier = Modifier, compact: Boolean = false) {
    val repository = koinInject<ProviderModelCatalogRepository>()
    val scope = rememberCoroutineScope()
    val state = rememberCatalogState(provider.id)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            CatalogStatusContent(state, compact)
            CatalogMissingSummary(provider)
            if (!compact) Text(stringResource(if (provider is ProviderSetting.Account) R.string.catalog_scope_account else R.string.catalog_scope_key), style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = { scope.launch { repository.refresh(provider.id) } }, enabled = !state.isRefreshing) {
            if (state.isRefreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Lucide.RefreshCw, stringResource(R.string.catalog_refresh))
        }
    }
}

@Composable
fun CatalogBatchControls(providers: List<ProviderSetting>, showAutoSwitch: Boolean = false, modifier: Modifier = Modifier) {
    val repository = koinInject<ProviderModelCatalogRepository>()
    val store = koinInject<SettingsStore>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val states by repository.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val targets = catalogRefreshTargetIds(providers)
    var showDetails by remember { mutableStateOf(false) }
    val attempts = remember(states, settings, targets) { targets.map { repository.currentState(it)?.lastAttempt?.status } }
    val ready = attempts.count { it == CatalogAttemptStatus.COMPLETE }
    val partial = attempts.count { it == CatalogAttemptStatus.PARTIAL }
    val running = attempts.count { it in listOf(CatalogAttemptStatus.QUEUED, CatalogAttemptStatus.RUNNING) }
    val problems = attempts.count { it in listOf(CatalogAttemptStatus.FAILED, CatalogAttemptStatus.UNSUPPORTED, CatalogAttemptStatus.INTERRUPTED) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { scope.launch { repository.refreshAll(targets) } }, enabled = targets.isNotEmpty()) {
                Icon(Lucide.RefreshCw, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.catalog_refresh_all))
            }
            IconButton(onClick = { showDetails = true }, enabled = targets.isNotEmpty()) {
                Icon(Lucide.ListChecks, stringResource(R.string.catalog_details))
            }
        }
        Text(stringResource(R.string.catalog_summary, ready, partial, problems, running, targets.size),
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
        providers.filter { it.id in targets }.forEach { CatalogMissingSummary(it, showProviderName = true) }
        if (showAutoSwitch) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.catalog_auto_refresh), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.catalog_auto_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.catalogAutoRefresh, onCheckedChange = { enabled -> scope.launch { store.update { it.copy(catalogAutoRefresh = enabled) } } })
        }
    }
    if (showDetails) ModalBottomSheet(onDismissRequest = { showDetails = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.catalog_details), style = MaterialTheme.typography.titleLarge)
            providers.filter { it.id in targets }.forEach { provider ->
                Column {
                    Text(provider.name, style = MaterialTheme.typography.titleSmall)
                    CatalogProviderStatus(provider)
                }
            }
        }
    }
}
