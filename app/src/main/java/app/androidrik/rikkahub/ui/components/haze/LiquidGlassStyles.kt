package app.androidrik.rikkahub.ui.components.haze

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassStyleScope
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import app.androidrik.rikkahub.data.datastore.BackgroundEffectType
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import app.androidrik.rikkahub.ui.theme.LocalDarkMode

/**
 * 玻璃/磨砂表面的通用样式（ChatList 兄弟层与顶栏共用）。
 *
 * 可读性模式（对照 BiliPai LiquidGlassReadabilityMode）：
 * - AUTO：Material3 默认铺底，视觉最「透」；
 * - STABLE_CONTENT：厚铺底（surfaceContainerHigh），玻璃上的文字对比度不随背景图变化。
 */
@Composable
fun liquidGlassBlurStyle(mode: LiquidGlassReadabilityMode): HazeBlurStyle {
    return when (mode) {
        LiquidGlassReadabilityMode.AUTO -> HazeBlurStyle.Material3 {
            blurRadius(16.dp)
        }

        LiquidGlassReadabilityMode.STABLE_CONTENT -> HazeBlurStyle.Material3(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            blurRadius(16.dp)
        }
    }
}

/** 玻璃着色浓度：AUTO 视觉最透，STABLE_CONTENT 加厚着色保文字对比度（ blur 样式同款语义）。 */
private fun liquidGlassTintAlpha(mode: LiquidGlassReadabilityMode): Float = when (mode) {
    LiquidGlassReadabilityMode.AUTO -> 0.3f
    // 稳定内容色：加厚着色，玻璃上文字对比度不随背景图变化
    LiquidGlassReadabilityMode.STABLE_CONTENT -> 0.55f
}

/** 输入框/悬浮层的共用玻璃样式（`GlassStyle.Material3` + 明暗预设 + 光学参数）。 */
@Composable
private fun liquidGlassStyle(
    containerColor: Color,
    tintAlpha: Float,
    darkMode: Boolean,
    cornerShape: RoundedCornerShape?,
): GlassStyle = GlassStyle.Material3(
    containerColor = containerColor,
    tint = containerColor.copy(alpha = tintAlpha),
) {
    appearanceResponse(dark = darkMode)
    // 与背景文字拉开层次，避免玻璃后的内容与玻璃上的文字互相干扰
    optics(
        GlassDefaults.optics.copy(
            blurRadius = OpticalSizeValue.Fixed(16.dp),
            depth = OpticalSizeValue.Fixed(0.5f),
        )
    )
    // GlassStyleScope.shape 只收 RoundedCornerShape；全屏 chrome 无需写 shape
    if (cornerShape != null) shape(cornerShape)
}

/**
 * 输入框容器的背景效果（对照上游 ChatInputHaze）：模糊 / 真玻璃二选一，未开启时原样返回。
 *
 * 开启后容器本身需保持透明，[containerColor] 由效果负责铺底和着色；
 * GLASS 路线依赖 RuntimeShader，<T(33) 时按 SDK 分级退回 blur 路线而非闪退。
 */
@Composable
fun Modifier.chatInputBackgroundEffect(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: Shape,
): Modifier {
    if (!displaySetting.enableBlurEffect) return this
    return when (displaySetting.inputEffectType) {
        BackgroundEffectType.BLUR -> recoverableHazeBlur(
            state = hazeState,
            style = HazeBlurStyle.Material3 {
                blurRadius(12.dp)
            },
        )

        BackgroundEffectType.GLASS -> {
            // 前后台 gate：进后台后玻璃停渲染（recoverableBlurEnabled 同时承载回前台重建策略）
            if (!shouldAllowRuntimeShaderBackedGlassEffect(Build.VERSION.SDK_INT) ||
                !recoverableBlurEnabled(hazeState)
            ) {
                recoverableHazeBlur(
                    state = hazeState,
                    style = HazeBlurStyle.Material3 {
                        blurRadius(12.dp)
                    },
                )
            } else {
                val darkMode = LocalDarkMode.current
                @OptIn(ExperimentalHazeApi::class)
                hazeGlass(
                    input = HazeInput.Sources(hazeState),
                    style = liquidGlassStyle(
                        containerColor = containerColor,
                        tintAlpha = liquidGlassTintAlpha(displaySetting.liquidGlassReadabilityMode),
                        darkMode = darkMode,
                        // GlassStyleScope.shape 只收 RoundedCornerShape；非圆角输入不写 shape
                        cornerShape = shape as? RoundedCornerShape,
                    ),
                )
            }
        }
    }
}

/**
 * 顶栏/聊天列表玻璃层的背景效果：各消费点独立选 [BackgroundEffectType]（BLUR/GLASS）。
 *
 * GLASS 走 hazeGlass（RuntimeShader）：<T(33) 或前后台 gate 关闭时退回 blur 路线而非闪退/空渲染；
 * BLUR 即 [liquidGlassBlurStyle]（可读性模式生效）。调用方自行挂用户开关
 * （enableTopBarBlur / enableGlassTheme）并选择正确的采样 state——悬浮层（顶栏）采
 * hazeState（背景+穿底内容），垫底层（列表玻璃层）只采 glassHazeState（背景）。
 */
@Composable
fun Modifier.liquidGlassChromeEffect(
    hazeState: HazeState,
    effectType: BackgroundEffectType,
    readabilityMode: LiquidGlassReadabilityMode,
): Modifier {
    if (effectType == BackgroundEffectType.GLASS &&
        shouldAllowRuntimeShaderBackedGlassEffect(Build.VERSION.SDK_INT) &&
        recoverableBlurEnabled(hazeState)
    ) {
        val darkMode = LocalDarkMode.current
        @OptIn(ExperimentalHazeApi::class)
        return hazeGlass(
            input = HazeInput.Sources(hazeState),
            style = liquidGlassStyle(
                containerColor = when (readabilityMode) {
                    LiquidGlassReadabilityMode.AUTO -> MaterialTheme.colorScheme.surface
                    LiquidGlassReadabilityMode.STABLE_CONTENT -> MaterialTheme.colorScheme.surfaceContainerHigh
                },
                tintAlpha = liquidGlassTintAlpha(readabilityMode),
                darkMode = darkMode,
                cornerShape = null,
            ),
        )
    }
    return recoverableHazeBlur(
        state = hazeState,
        style = liquidGlassBlurStyle(readabilityMode),
    )
}

// Haze 按系统夜间模式选玻璃的明暗预设，应用内单独设置主题时会选错（深色主题下被提亮发白），
// 所以按应用主题显式写入。深色数值对应库里 internal 的 GlassStyle.regularDark。
private fun GlassStyleScope.appearanceResponse(dark: Boolean) {
    if (dark) {
        specularIntensity(0.38f)
        edgeShadow(Color.Black.copy(alpha = 0.32f))
        ambientResponse(0.08f)
        contrast(0.08f)
        whitePoint(-0.22f)
        chromaMultiplier(1.1f)
    } else {
        specularIntensity(GlassDefaults.specularIntensity)
        edgeShadow(GlassDefaults.edgeShadow)
        ambientResponse(GlassDefaults.ambientResponse)
        contrast(GlassDefaults.contrast)
        whitePoint(GlassDefaults.whitePoint)
        chromaMultiplier(GlassDefaults.chromaMultiplier)
    }
}
