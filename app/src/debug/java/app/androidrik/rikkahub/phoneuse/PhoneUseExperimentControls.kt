package app.androidrik.rikkahub.phoneuse

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_BLOCKED_APPS
import app.androidrik.rikkahub.ui.context.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PhoneUseExperimentSetting() {
    if (Build.VERSION.SDK_INT < 35) return
    val context = LocalContext.current
    val display by PhoneUseVirtualDisplays.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Phone Use virtual display (debug)", style = MaterialTheme.typography.labelLarge)
    if (display == null) {
        Text("Start a display, then send a new chat message to use it. Requires Shizuku (ADB or root).")
        TextButton(
            enabled = !starting,
            onClick = {
                starting = true
                error = null
                scope.launch {
                    try {
                        check(isFloatingChatWindowOn(context, settings)) { "Enable the floating chat window first." }
                        startPhoneUseDisplay(context)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not start the Phone Use display."
                    } finally {
                        starting = false
                    }
                }
            },
        ) { Text(if (starting) "Starting…" else "Start virtual display") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } else {
        PhoneUseExperimentPreview(onStopped = {})
    }
}

@Composable
internal fun PhoneUseExperimentPreview(onStopped: () -> Unit) {
    val display by PhoneUseVirtualDisplays.current.collectAsStateWithLifecycle()
    val target = display ?: return
    val blocked = PHONE_USE_BLOCKED_APPS + LocalSettings.current.displaySetting.phoneUseBlockedPackages
    val frame by target.previewFrames.collectAsStateWithLifecycle()
    var blockedOnScreen by remember(target) { mutableStateOf(false) }

    DisposableEffect(target) {
        target.setPreviewActive(true)
        onDispose { target.setPreviewActive(false) }
    }
    LaunchedEffect(target, blocked) {
        while (true) {
            blockedOnScreen = runCatching { target.controller.visiblePackages().any { it in blocked } }
                .getOrDefault(false)
            delay(500)
        }
    }

    Column {
        if (blockedOnScreen) {
            Text(
                "Preview hidden: a blocked app is on the Phone Use display.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            frame?.let {
                Image(it, "Phone Use display preview", Modifier.fillMaxWidth().height(160.dp))
            }
        }
        TextButton(onClick = {
            target.close()
            onStopped()
        }) { Text("Stop virtual display") }
    }
}
