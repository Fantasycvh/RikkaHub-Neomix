package app.androidrik.rikkahub.ui.components.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onVisibilityChanged

/**
 * 装饰性无限动画的视口 + 页面 + 窗口 + 减弱动效组合门控（借鉴 BiliPai `DecorativeAnimationGate`，
 * 按本仓重写）。
 *
 * 相比仅看前后台/焦点的 [rememberVisualWorkActive]，补齐「视口可见性」轴：
 * 滚出 `LazyColumn` 视口、落在未激活 `HorizontalPager` 页或折叠容器内的组件，
 * [inViewport] 立即置 `false`，消费方据此从源头停止启动 `rememberInfiniteTransition` 或自驱循环。
 */
internal fun shouldRunDecorativeAnimation(
    inViewport: Boolean,
    pageActive: Boolean,
    resumed: Boolean,
    focused: Boolean,
    reduceMotion: Boolean,
    respectSystemReduceMotion: Boolean = true,
): Boolean = inViewport &&
    shouldRunVisualWork(pageActive = pageActive, resumed = resumed, focused = focused) &&
    (!respectSystemReduceMotion || !reduceMotion)

@Stable
class DecorativeAnimationGate {
    private var inWindow by mutableStateOf(false)
    private var hasUnclippedArea by mutableStateOf(false)
    val inViewport: Boolean get() = inWindow && hasUnclippedArea
    var allowed by mutableStateOf(false)
        internal set

    /** 仅当组件在视口内且页面活跃、宿主 RESUMED、窗口聚焦且未被减弱动效屏蔽时为 `true`。 */
    val active: Boolean get() = inViewport && allowed

    /** 必须始终挂在目标组件节点上（即使当前 [active] 为 `false`），以便重新进入视口时唤醒。 */
    val modifier: Modifier = Modifier
        // 保留原生监测的 unplaced/reset/detach 通知，避免 Lazy 缓存节点继续运行动画。
        .onVisibilityChanged(minFractionVisible = 0f) { visible -> inWindow = visible }
        // 原生监测默认只与窗口求交；这里再计入祖先的滚动/折叠裁剪。
        .onGloballyPositioned { coordinates ->
            hasUnclippedArea = coordinates.isAttached &&
                !coordinates.boundsInWindow(clipBounds = true).isEmpty
        }
}

@Composable
fun rememberDecorativeAnimationGate(
    pageActive: Boolean = LocalPageActive.current,
    respectSystemReduceMotion: Boolean = true,
): DecorativeAnimationGate {
    val visualWorkActive = rememberVisualWorkActive(pageActive = pageActive)
    val reduceMotion = rememberSystemReduceMotion()
    val allowed = visualWorkActive && (!respectSystemReduceMotion || !reduceMotion)
    val gate = remember { DecorativeAnimationGate() }
    SideEffect {
        gate.allowed = allowed
    }
    return gate
}
