package app.androidrik.rikkahub.ui.components.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import app.androidrik.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(visibility) {
        if (visibility) {
            window?.show()
        } else {
            window?.hide()
        }
    }

    DisposableEffect(context) {
        // 无 SYSTEM_ALERT_WINDOW 权限时 FloatingX.show() 会抛 WindowManager SecurityException
        // → TTS 朗读即崩溃。静默降级：未授权不显示悬浮窗（TTS 朗读本身不受影响）。
        if (Settings.canDrawOverlays(context)) {
            window = FloatingX.install {
                setTag(tag)
                setContext(context)
                setGravity(FxGravity.LEFT_OR_BOTTOM)
                setOffsetXY(20f, -20f)
                setEnableAnimation(true)
                setLayoutView(ComposeView(context).apply {
                    setContent {
                        RikkahubTheme {
                            content()
                        }
                    }
                })
            }
            if (visibility) window?.show() else window?.hide()
        }
        onDispose {
            window?.cancel()
        }
    }
}
