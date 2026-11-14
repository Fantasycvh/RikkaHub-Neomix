package app.androidrik.rikkahub.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.androidrik.rikkahub.data.model.AppSkin
import app.androidrik.rikkahub.data.model.AppSkinMotion
import app.androidrik.rikkahub.data.model.AppSkinMotionTokens

/** 消费口解析后的动效 token（已按 AppSkinMotion 档位选好、已处理系统关动画）。 */
data class ResolvedMotionTokens(
    val pressMs: Int,
    val quickMs: Int,
    val standardMs: Int,
    val collapseMs: Int,
    val emphasizedMs: Int,
    val sheetInMs: Int,
    val sheetOutMs: Int,
    val slowMs: Int,
    val breathMs: Int,
    val layoutSpringMs: Int,
    val layoutBounce: Float,
    val flingSpringMs: Int,
    val flingBounce: Float,
)

val LocalMotionTokens = staticCompositionLocalOf { expressiveMotionTokens() }

/** EXPRESSIVE 档：fling 弹簧保留一点回弹。 */
fun expressiveMotionTokens(): ResolvedMotionTokens = AppSkinMotionTokens().normalized().toResolved()

/** STANDARD 档：同时长，弹簧无回弹。 */
fun standardMotionTokens(): ResolvedMotionTokens =
    AppSkinMotionTokens().normalized().toResolved().copy(layoutBounce = 0f, flingBounce = 0f)

fun AppSkinMotionTokens.toResolved(): ResolvedMotionTokens = ResolvedMotionTokens(
    pressMs = pressMs, quickMs = quickMs, standardMs = standardMs, collapseMs = collapseMs,
    emphasizedMs = emphasizedMs, sheetInMs = sheetInMs, sheetOutMs = sheetOutMs,
    slowMs = slowMs, breathMs = breathMs,
    layoutSpringMs = layoutSpringMs, layoutBounce = layoutBounce,
    flingSpringMs = flingSpringMs, flingBounce = flingBounce,
)

/** 系统「移除动画」= 所有时长归 0；弹簧 bounce 保留由消费方自行判断，循环动画须另判 reduced 停静止帧。 */
fun ResolvedMotionTokens.zeroed(): ResolvedMotionTokens = copy(
    pressMs = 0, quickMs = 0, standardMs = 0, collapseMs = 0, emphasizedMs = 0,
    sheetInMs = 0, sheetOutMs = 0, slowMs = 0, breathMs = 0,
    layoutSpringMs = 0, flingSpringMs = 0,
)

/** 在取值口统一解析：档位选择 + 系统动画时长缩放（0 = 移除动画）。 */
@Composable
fun rememberResolvedMotionTokens(skin: AppSkin): ResolvedMotionTokens {
    val context = LocalContext.current
    val scale = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    return remember(skin.motion, skin.motionTokens, scale) {
        val base = when (skin.motion) {
            AppSkinMotion.TOKENS -> (skin.motionTokens ?: AppSkinMotionTokens()).normalized().toResolved()
            AppSkinMotion.STANDARD -> standardMotionTokens()
            AppSkinMotion.EXPRESSIVE -> expressiveMotionTokens()
        }
        if (scale == 0f) base.zeroed() else base
    }
}
