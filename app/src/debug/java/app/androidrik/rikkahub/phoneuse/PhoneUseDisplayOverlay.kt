package app.androidrik.rikkahub.phoneuse

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_BLOCKED_APPS
import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import app.androidrik.rikkahub.ui.hooks.rememberUserSettingsState
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost

/**
 * 虚拟屏观察窗：FloatingX 系统浮层（TYPE_APPLICATION_OVERLAY），可拖拽，实时帧直出。
 * 虚拟屏启动后自动弹出，关闭（X 或屏关）后自动收起；面板里有「Screen window」重新打开。
 */
internal object PhoneUseDisplayOverlay {
    private const val TAG = "phone_use_screen"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var control: FxControl? = null

    /** 幂等：已显示时再次调用只把它带回前台。 */
    fun show(context: Context) {
        mainHandler.post {
            if (control != null) {
                runCatching { control?.show() }
                return@post
            }
            val app = context.applicationContext
            control = runCatching {
                FloatingX.install(TAG) {
                    compose { PhoneUseScreenWindowContent() }
                    anchor(FxGravity.TOP_END)
                    systemHost(app) { permission(FxPermissionStrategy.skip()) }
                }.also { it.show() }
            }.getOrNull()
        }
    }

    fun hide() {
        mainHandler.post {
            runCatching { control?.cancel() }
            control = null
        }
    }
}

@Composable
private fun PhoneUseScreenWindowContent() {
    val display by PhoneUseVirtualDisplays.current.collectAsState()
    LaunchedEffect(display) { if (display == null) PhoneUseDisplayOverlay.hide() }
    RikkahubTheme {
        Surface(
            shape = appSkinRoundedShape(12f),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.width(180.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Phone Use screen",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { PhoneUseDisplayOverlay.hide() }) {
                        Icon(Lucide.X, contentDescription = "Hide screen window")
                    }
                }
                val target = display
                if (target != null) {
                    val blocked = PHONE_USE_BLOCKED_APPS +
                        rememberUserSettingsState().value.displaySetting.phoneUseBlockedPackages
                    PhoneUseLiveFrame(
                        target,
                        blocked,
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp).aspectRatio(720f / 1280f),
                    )
                }
            }
        }
    }
}
