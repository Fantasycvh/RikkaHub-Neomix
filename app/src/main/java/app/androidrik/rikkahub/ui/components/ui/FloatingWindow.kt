package app.androidrik.rikkahub.ui.components.ui

import android.app.Application
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.app.appHost
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.FxState
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.layout.FxGravity
import app.androidrik.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val currentContent by rememberUpdatedState(content)
    var window: FxControl? by remember { mutableStateOf(null) }

    LaunchedEffect(window, visibility) {
        window?.setVisible(visibility)
    }

    DisposableEffect(context, tag) {
        // 保留本仓的权限降级：未授权不建窗口，TTS 朗读本身不受影响。
        val control = if (Settings.canDrawOverlays(context)) {
            FloatingX.install(tag) {
                appHost(context.applicationContext as Application)
                anchor(FxGravity.BOTTOM_START, dx = 20f, dy = 20f)
                animation(FxAnimations.fade())
                compose {
                    RikkahubTheme {
                        currentContent()
                    }
                }
            }.also {
                window = it
                it.setVisible(visibility)
            }
        } else null
        onDispose {
            control?.cancel()
        }
    }
}

// 同 tag 再次 install 会 cancel 旧 control；已取消的窗口不可再操作。
private fun FxControl.setVisible(visible: Boolean) {
    if (state == FxState.CANCELLED) return
    if (visible) show() else hide()
}
