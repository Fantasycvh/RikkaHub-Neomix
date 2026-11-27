package app.androidrik.rikkahub.ui.components.motion

import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/** 栈顶资格只影响循环动效；仍在转场中的页面必须保留绘制和玻璃材质。 */
@Composable
internal fun <T : Any> rememberPageVisualWorkNavEntryDecorator(
    topContentKey: State<Any?>,
): NavEntryDecorator<T> = remember(topContentKey) {
    NavEntryDecorator(onPop = {}) { entry ->
        val topKey = topContentKey.value
        val isTop = topKey == null || topKey == entry.contentKey
        val transition = LocalNavAnimatedContentScope.current.transition
        val visible = isTop ||
            transition.currentState == EnterExitState.Visible ||
            transition.targetState == EnterExitState.Visible ||
            transition.isRunning || transition.isSeeking
        PageVisualWorkHost(active = isTop, visible = visible) { entry.Content() }
    }
}

@Composable
internal fun PageVisualWorkHost(
    active: Boolean,
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPageActive provides active,
        LocalPageVisible provides visible,
    ) {
        // 保留组合、测量和编辑状态；完全隐藏时才截断整棵子树绘制。
        Box(
            modifier = Modifier.drawWithContent { if (visible) drawContent() },
            propagateMinConstraints = true,
        ) { content() }
    }
}
