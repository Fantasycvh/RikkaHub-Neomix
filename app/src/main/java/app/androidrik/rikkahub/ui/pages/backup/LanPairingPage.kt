package app.androidrik.rikkahub.ui.pages.backup

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.OutlinedTextField
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
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.sync.lan.LanPairingException
import app.androidrik.rikkahub.data.sync.lan.LanPairingFailure
import app.androidrik.rikkahub.data.sync.lan.LanPairingService
import app.androidrik.rikkahub.data.sync.lan.LanPeer
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.components.ui.permission.LocalNetworkPermissionCard
import app.androidrik.rikkahub.ui.components.ui.permission.rememberLocalNetworkPermissionState
import app.androidrik.rikkahub.ui.context.LocalNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun LanPairingPage(service: LanPairingService = koinInject()) {
    val peers by service.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    val invite by service.invitation.collectAsStateWithLifecycle()
    val permission = rememberLocalNetworkPermissionState()
    val allowed = permission?.allPermissionsGranted != false
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<LanPairingFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<LanPeer?>(null) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure = (error as? LanPairingException)?.reason ?: LanPairingFailure.FAILED
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(service) { action { fingerprint = service.fingerprint() } }
    LaunchedEffect(allowed) { if (!allowed) service.stop() }
    DisposableEffect(lifecycle, service) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) service.stop() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            service.stop()
        }
    }

    BackupDetailScaffold(stringResource(R.string.lan_pair_title)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (permission != null && !allowed) LocalNetworkPermissionCard(permission)
            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.lan_pair_device_fingerprint)) },
                    supportingContent = { fingerprint?.let { SelectionContainer { Text(it) } } },
                )
                item(
                    onClick = if (allowed && !busy && invite == null) ({ action { service.start() } }) else null,
                    headlineContent = { Text(stringResource(R.string.lan_pair_invite)) },
                    supportingContent = {
                        invite?.let { active ->
                            Column {
                                Text(stringResource(R.string.lan_pair_invite_help))
                                LanPairingQr(active.code)
                                SelectionContainer { Text(active.code) }
                                TextButton(onClick = {
                                    val clip = ClipData.newPlainText(context.getString(R.string.lan_pair_code), active.code)
                                    if (Build.VERSION.SDK_INT >= 33) {
                                        clip.description.extras = PersistableBundle().apply {
                                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                        }
                                    }
                                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                                }) { Text(stringResource(R.string.lan_pair_copy)) }
                                TextButton(onClick = service::stop) { Text(stringResource(R.string.common_close)) }
                            }
                        }
                    },
                )
                item(
                    headlineContent = {
                        TextButton(onClick = { nav.navigate(Screen.LanPairJoin) }, enabled = allowed && !busy) {
                            Text(stringResource(R.string.lan_pair_join))
                        }
                    },
                )
                failure?.let { problem -> item(headlineContent = { Text(stringResource(problem.message())) }) }
            }
            if (peers.isNotEmpty()) {
                CardGroup(title = { Text(stringResource(R.string.lan_pair_devices)) }) {
                    peers.forEach { peer ->
                        item(
                            headlineContent = { Text(peer.name) },
                            supportingContent = { SelectionContainer { Text(peer.certSha256) } },
                            trailingContent = {
                                TextButton(onClick = { forget = peer }, enabled = !busy) {
                                    Text(stringResource(R.string.lan_pair_forget))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    forget?.let { peer ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.lan_pair_forget),
            confirmText = stringResource(R.string.lan_pair_forget),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                action { service.forget(peer.certSha256) }
                forget = null
            },
            onDismiss = { forget = null },
        ) { Text(stringResource(R.string.lan_pair_forget_confirm, peer.name)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanPairingJoinPage(service: LanPairingService = koinInject()) {
    val nav = LocalNavController.current
    val permission = rememberLocalNetworkPermissionState()
    val allowed = permission?.allPermissionsGranted != false
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val code = remember { TextFieldState() }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<LanPairingFailure?>(null) }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failure = (error as? LanPairingException)?.reason ?: LanPairingFailure.FAILED
            } finally {
                busy = false
            }
        }
    }

    val readQr = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            action {
                val value = readLanPairingQr(resolver, it) ?: throw LanPairingException(LanPairingFailure.INVALID_CODE)
                if (value != code.text.toString()) code.setTextAndPlaceCursorAtEnd(value)
            }
        }
    }
    BackupDetailScaffold(stringResource(R.string.lan_pair_join)) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (permission != null && !allowed) LocalNetworkPermissionCard(permission)
            OutlinedTextField(
                state = code,
                inputTransformation = InputTransformation.maxLength(128),
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.lan_pair_code)) },
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 3),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                enabled = !busy,
            )
            TextButton(enabled = !busy && allowed, onClick = { readQr.launch(arrayOf("image/*")) }) {
                Text(stringResource(R.string.lan_pair_qr))
            }
            failure?.let { Text(stringResource(it.message())) }
            TextButton(enabled = code.text.isNotBlank() && !busy && allowed, onClick = {
                action {
                    service.join(code.text.toString())
                    nav.popBackStack()
                }
            }) { Text(stringResource(R.string.lan_pair_join)) }
        }
    }
}

private fun LanPairingFailure.message(): Int = when (this) {
    LanPairingFailure.INVALID_CODE -> R.string.lan_pair_invalid_code
    LanPairingFailure.NO_ADDRESS -> R.string.lan_pair_no_address
    LanPairingFailure.NOT_AUTHORIZED -> R.string.permission_local_network
    LanPairingFailure.SELF -> R.string.lan_pair_self
    LanPairingFailure.EXPIRED -> R.string.lan_pair_expired
    LanPairingFailure.REJECTED -> R.string.lan_pair_rejected
    LanPairingFailure.LOCKED -> R.string.lan_pair_locked
    LanPairingFailure.PROTOCOL -> R.string.lan_pair_protocol
    LanPairingFailure.FAILED -> R.string.lan_pair_failed
}
