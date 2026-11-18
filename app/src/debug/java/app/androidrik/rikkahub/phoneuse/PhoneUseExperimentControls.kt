package app.androidrik.rikkahub.phoneuse

import android.graphics.BitmapFactory
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_BLOCKED_APPS
import app.androidrik.rikkahub.ui.context.LocalSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val scope = rememberCoroutineScope()
    val blocked = PHONE_USE_BLOCKED_APPS + LocalSettings.current.displaySetting.phoneUseBlockedPackages
    var frame by remember(target) { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember(target) { mutableStateOf(false) }
    var error by remember(target) { mutableStateOf<String?>(null) }

    Column {
        frame?.let {
            Image(it, "Phone Use display preview", Modifier.fillMaxWidth().height(160.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    frame = null
                    error = null
                    scope.launch {
                        try {
                            val controller = target.controller
                            check(controller.visiblePackages().none { it in blocked }) { "This app is blocked in Phone Use." }
                            val bytes = controller.screenshot(640)
                            frame = withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Preview unavailable."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (frame == null) "Preview" else "Refresh preview") }
            TextButton(onClick = {
                target.close()
                onStopped()
            }) { Text("Stop virtual display") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
