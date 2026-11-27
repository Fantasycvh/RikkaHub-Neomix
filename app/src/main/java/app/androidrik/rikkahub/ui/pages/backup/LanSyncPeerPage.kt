package app.androidrik.rikkahub.ui.pages.backup

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.lan.LanPairingException
import app.androidrik.rikkahub.data.sync.lan.LanPairingFailure
import app.androidrik.rikkahub.data.sync.lan.LanPairingService
import app.androidrik.rikkahub.data.sync.lan.LanSyncEndpoint
import app.androidrik.rikkahub.data.sync.lan.LanSyncHttpException
import app.androidrik.rikkahub.data.sync.lan.LanSyncReport
import app.androidrik.rikkahub.data.sync.lan.LanSyncResult
import app.androidrik.rikkahub.data.sync.lan.LanSyncService
import app.androidrik.rikkahub.data.sync.lan.LanSyncStage
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.permission.LocalNetworkPermissionCard
import app.androidrik.rikkahub.ui.components.ui.permission.rememberLocalNetworkPermissionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date
import javax.net.ssl.SSLException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanSyncPeerPage(
    fingerprint: String,
    service: LanSyncService = koinInject(),
    pairing: LanPairingService = koinInject(),
) {
    val peers by pairing.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    val peer = peers.firstOrNull { it.certSha256 == fingerprint }
    val permission = rememberLocalNetworkPermissionState()
    val allowed = permission?.allPermissionsGranted != false
    val endpoint = rememberTextFieldState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<Job?>(null) }
    var stage by remember { mutableStateOf<LanSyncStage?>(null) }
    var hostAddress by remember { mutableStateOf<LanSyncEndpoint?>(null) }
    var result by remember { mutableStateOf<LanSyncResult?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var discovered by remember { mutableStateOf(emptyList<LanSyncEndpoint>()) }
    val busy = running != null

    LaunchedEffect(peer, allowed) {
        val paired = peer ?: return@LaunchedEffect
        if (allowed) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            service.discover(paired).collectLatest { discovered = it }
        }
    }

    LaunchedEffect(peer?.certSha256) {
        if (endpoint.text.isEmpty()) {
            peer?.lastEndpoints?.firstOrNull()?.let { endpoint.setTextAndPlaceCursorAtEnd(it) }
        }
        if (peer == null) running?.cancel()
    }
    LaunchedEffect(allowed) { if (!allowed) running?.cancel() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) running?.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            running?.cancel()
        }
    }

    fun launchSync(block: suspend () -> Unit) {
        if (!allowed || busy) return
        error = null
        result = null
        stage = LanSyncStage.PREPARING
        running = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("LanSync", "LAN operation failed", failure)
                error = failure.syncMessage()
            } finally {
                stage = null
                hostAddress = null
                running = null
            }
        }
    }

    BackupDetailScaffold(stringResource(R.string.lan_sync_open)) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (permission != null && !allowed) LocalNetworkPermissionCard(permission)
            if (peer == null) {
                Text(stringResource(R.string.lan_sync_missing_peer))
                return@Column
            }
            CardGroup {
                item(
                    headlineContent = { Text(peer.name) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.lan_sync_summary))
                            peer.lastSyncAt?.let {
                                Text(stringResource(R.string.lan_sync_last, DateFormat.getDateTimeInstance().format(Date(it))))
                            }
                        }
                    },
                )
                item(
                    headlineContent = {
                        TextButton(enabled = allowed && !busy, onClick = {
                            launchSync {
                                service.receive(peer, onEndpoint = { hostAddress = it }, onStage = { stage = it },
                                    onApplied = { result = LanSyncResult(it, null) })
                            }
                        }) { Text(stringResource(R.string.lan_sync_receive)) }
                    },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.lan_sync_receive_help))
                            hostAddress?.let { address ->
                                SelectionContainer { Text(address.toString()) }
                                TextButton(onClick = {
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("LAN", address.toString()))
                                }) { Text(stringResource(R.string.lan_sync_copy_address)) }
                            }
                        }
                    },
                )
            }
            OutlinedTextField(
                state = endpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.lan_sync_address)) },
                enabled = allowed && !busy,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                inputTransformation = InputTransformation.maxLength(24),
            )
            Row {
                val parsed = LanSyncEndpoint.parse(endpoint.text.toString())
                val candidates = (discovered + listOfNotNull(parsed) + peer.lastEndpoints.mapNotNull(LanSyncEndpoint::parse)).distinct()
                TextButton(enabled = allowed && !busy && candidates.isNotEmpty(), onClick = {
                    launchSync { result = service.sync(peer, candidates) { stage = it } }
                }) { Text(stringResource(R.string.lan_sync_now)) }
                if (busy) TextButton(onClick = { running?.cancel() }) { Text(stringResource(R.string.common_close)) }
            }
            if (discovered.isNotEmpty()) Text(stringResource(R.string.lan_sync_discovered))
            discovered.forEach { address -> Text(address.toString()) }
            stage?.let { Text(stringResource(it.label())) }
            error?.let { Text(stringResource(it)) }
            result?.let { shown ->
                if (shown.pending) Text(stringResource(R.string.lan_sync_resume))
                shown.local?.let { LanSyncReportText(stringResource(R.string.lan_sync_this_device), it) }
                shown.remote?.let { LanSyncReportText(peer.name, it) }
            }
        }
    }
}

@Composable
private fun LanSyncReportText(device: String, report: LanSyncReport) {
    Text(stringResource(R.string.lan_sync_report, device, report.added, report.updated, report.deleted, report.conflicts))
}

private fun LanSyncStage.label() = when (this) {
    LanSyncStage.PREPARING -> R.string.lan_sync_preparing
    LanSyncStage.WAITING -> R.string.lan_sync_waiting
    LanSyncStage.EXCHANGING -> R.string.lan_sync_exchanging
    LanSyncStage.UPLOADING -> R.string.lan_sync_uploading
    LanSyncStage.DOWNLOADING -> R.string.lan_sync_downloading
    LanSyncStage.APPLYING -> R.string.lan_sync_applying
}

private fun Exception.syncMessage() = when {
    this is LanSyncHttpException && status == 426 -> R.string.lan_sync_version_error
    this is LanSyncHttpException && status == 409 -> R.string.lan_sync_changed
    this is LanSyncHttpException && status == 403 || this is SSLException -> R.string.lan_sync_identity_error
    this is LanPairingException && reason == LanPairingFailure.NO_ADDRESS -> R.string.lan_sync_no_address
    else -> R.string.lan_sync_failed
}
