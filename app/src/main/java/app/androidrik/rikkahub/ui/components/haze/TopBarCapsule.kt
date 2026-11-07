package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import dev.chrisbanes.haze.HazeState

/**
 * 顶栏按钮的浮动胶囊容器（上游 b40d7a273 ChatTopBarCapsule，两套共存方案的 CAPSULES 档）。
 *
 * 背景效果与底部输入框同款（[chatInputBackgroundEffect]，跟随 enableBlurEffect/inputEffectType），
 * 仅当消息滚到标题栏下方时按 [showBackground] 淡入淡出，按钮本身始终显示；
 * FULL_BAR 档（整条穿底模糊）不经过本组件。
 */
@Composable
fun TopBarCapsule(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    showBackground: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (showBackground) 1f else 0f,
        label = "TopBarCapsuleBackground",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (backgroundAlpha > 0f) {
            Surface(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = backgroundAlpha }
                    .clip(CircleShape)
                    .chatInputBackgroundEffect(
                        hazeState = hazeState,
                        displaySetting = displaySetting,
                        containerColor = containerColor,
                        // GlassStyleScope.shape 只收 RoundedCornerShape；CircleShape 是 50% 圆角，可直传
                        shape = CircleShape,
                    ),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (displaySetting.enableBlurEffect) Color.Transparent else containerColor,
                content = {},
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
