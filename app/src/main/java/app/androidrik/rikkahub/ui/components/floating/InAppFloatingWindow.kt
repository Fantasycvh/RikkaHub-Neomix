package app.androidrik.rikkahub.ui.components.floating

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.service.floating.FloatingWindowGeometry
import app.androidrik.rikkahub.service.floating.ScreenSizePx
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.utils.base64Encode

/** 根导航上的非模态小窗。尺寸使用实际可用内容区域，不把系统屏幕像素当成 Activity 大小。 */
@Composable
fun InAppFloatingWindow(modifier: Modifier = Modifier) {
    val request = FloatingWindowLauncher.request ?: return
    val density = LocalDensity.current
    val navigator = LocalNavController.current
    BackHandler { FloatingWindowLauncher.closeInApp() }
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        var preferred by remember {
            mutableStateOf(FloatingWindowGeometry.defaultGeometry(width, height, density.density))
        }
        // IME 只缩小显示区域，不覆盖原几何，收起键盘自动回到原大小/位置。
        val displayed = FloatingWindowGeometry.clamp(
            preferred, width, height,
            FloatingWindowGeometry.minWidthPx(density.density),
            FloatingWindowGeometry.minHeightPx(density.density),
        )
        FloatingWindowPane(
            geometry = displayed,
            screenSize = ScreenSizePx(width, height),
            onGeometryChange = { preferred = it },
            onClose = FloatingWindowLauncher::closeInApp,
            modifier = Modifier
                .offset { IntOffset(displayed.x, displayed.y) }
                .size(with(density) { displayed.width.toDp() }, with(density) { displayed.height.toDp() }),
        ) {
            FloatingChatPane(
                initialConversationId = request.conversationId,
                onInputFocusChanged = {},
                onOpenApp = { id, draft ->
                    FloatingWindowLauncher.closeInApp()
                    // 已经停在该会话页时先弹掉旧页，避免同一个会话在栈里叠两层
                    val current = navigator.current
                    if (current is Screen.Chat && current.id == id) navigator.popBackStack()
                    navigator.navigate(
                        Screen.Chat(
                            id = id,
                            text = draft.takeIf(String::isNotBlank)?.base64Encode(),
                            appendDraft = true,
                        )
                    )
                },
            )
        }
    }
}
