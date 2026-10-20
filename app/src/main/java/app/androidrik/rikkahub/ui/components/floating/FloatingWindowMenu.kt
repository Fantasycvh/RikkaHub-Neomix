package app.androidrik.rikkahub.ui.components.floating

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.androidrik.rikkahub.service.FloatingWindowService
import app.androidrik.rikkahub.ui.context.LocalToaster
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2

/** 从当前会话打开，绝不猜测 lastConversationId。授权返回后需用户再次主动选择。 */
@Composable
fun FloatingWindowMenu(conversationId: String) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val toaster = LocalToaster.current
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Lucide.Maximize2, contentDescription = "打开小窗")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("应用内小窗（无需权限）") },
                onClick = {
                    expanded = false
                    FloatingWindowService.stop(context)
                    FloatingWindowLauncher.openInApp(conversationId)
                },
            )
            DropdownMenuItem(
                text = { Text("系统悬浮小窗") },
                onClick = {
                    expanded = false
                    if (FloatingWindowService.canDrawOverlays(context)) {
                        FloatingWindowService.start(context, conversationId)
                    } else {
                        toaster.show("请先授予悬浮权限，返回后再次打开小窗")
                        runCatching {
                            context.startActivity(FloatingWindowService.overlayPermissionIntent(context))
                        }.onFailure { toaster.show("无法打开授权页，请在系统设置中授予悬浮权限") }
                    }
                },
            )
        }
    }
}
