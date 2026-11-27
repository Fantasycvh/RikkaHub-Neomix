package app.androidrik.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import app.androidrik.rikkahub.ui.components.motion.rememberSystemReduceMotion
import app.androidrik.rikkahub.ui.components.motion.rememberVisualWorkActive
import kotlin.math.roundToInt

/**
 * 头像形状：非 loading 直接返回 [CircleShape]（绝不启动无限旋转动画）；
 * loading 且门控放行时旋转，离屏 / 被覆盖 / 系统减弱动效时保持静止形态。
 */
@Composable
fun rememberAvatarShape(
    loading: Boolean,
    animated: Boolean? = null,
): Shape {
    if (!loading) return CircleShape
    val shouldAnimate = animated ?: (rememberVisualWorkActive() && !rememberSystemReduceMotion())
    if (!shouldAnimate) return MaterialShapes.Cookie6Sided.toShape(0)
    val infiniteTransition = rememberInfiniteTransition(label = "avatar_loading")
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        ),
        label = "avatar_rotate",
    )
    return MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
}
