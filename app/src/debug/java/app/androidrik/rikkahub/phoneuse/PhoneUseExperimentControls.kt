package app.androidrik.rikkahub.phoneuse

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    val context = LocalContext.current
    val blocked = PHONE_USE_BLOCKED_APPS + LocalSettings.current.displaySetting.phoneUseBlockedPackages
    Column {
        PhoneUseLiveFrame(target, blocked, Modifier.fillMaxWidth().height(160.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { PhoneUseDisplayOverlay.show(context) }) {
                Text("Screen window")
            }
            TextButton(onClick = {
                target.close()
                onStopped()
            }) { Text("Stop virtual display") }
        }
    }
}

/** 黑名单应用是否在虚拟屏上；观察端每 500ms 轮询一次。 */
@Composable
internal fun rememberBlockedOnScreen(target: PhoneUseVirtualDisplay, blocked: Set<String>): Boolean {
    var blockedOnScreen by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target, blocked) {
        while (true) {
            blockedOnScreen = runCatching { target.controller.visiblePackages().any { it in blocked } }
                .getOrDefault(false)
            delay(500)
        }
    }
    return blockedOnScreen
}

/** 面板预览与观察窗共用的实时帧；观察端配对 add/remove，黑名单应用上屏时只显示占位。 */
@Composable
internal fun PhoneUseLiveFrame(target: PhoneUseVirtualDisplay, blocked: Set<String>, modifier: Modifier) {
    val frame by target.previewFrames.collectAsState()
    val blockedOnScreen = rememberBlockedOnScreen(target, blocked)

    DisposableEffect(target) {
        target.setPreviewActive(true)
        onDispose { target.setPreviewActive(false) }
    }

    if (blockedOnScreen) {
        Text(
            "Preview hidden: a blocked app is on the Phone Use display.",
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
    } else {
        frame?.let {
            Image(it, "Phone Use display preview", modifier)
        }
    }
}
