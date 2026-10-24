package app.androidrik.rikkahub.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.serialization.Serializable
import app.androidrik.rikkahub.ui.hooks.rememberAmoledDarkMode
import app.androidrik.rikkahub.ui.hooks.rememberCurrentColorMode
import app.androidrik.rikkahub.ui.hooks.rememberUserSettingsState

private val ExtendLightColors = lightExtendColors()
private val ExtendDarkColors = darkExtendColors()
val LocalExtendColors = compositionLocalOf { ExtendLightColors }

val LocalDarkMode = compositionLocalOf { false }

private val AMOLED_DARK_BACKGROUND = Color(0xFF000000)

@Serializable
enum class ColorMode {
    SYSTEM,
    LIGHT,
    DARK
}

@Composable
fun RikkahubTheme(
    colorMode: ColorMode = rememberCurrentColorMode(),
    content: @Composable () -> Unit
) {
    val settings by rememberUserSettingsState()

    val darkTheme = when (colorMode) {
        ColorMode.SYSTEM -> isSystemInDarkTheme()
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }
    val amoledDarkMode by rememberAmoledDarkMode()

    val colorScheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val seed = settings.dynamicColorSeedColor
            if (seed == null) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                // 种子色覆盖：壁纸取色换成用户指定种子（复用 CustomTheme 的 material-color-utilities
                // 方案生成路径，TONAL_SPOT + 默认对比度，与系统 monet 观感一致）。palette 生成
                // 有成本，remember 按种子与深浅色缓存。
                remember(seed, darkTheme) {
                    CustomTheme(primaryColorArgb = seed).generateColorScheme(dark = darkTheme)
                }
            }
        }
        else -> {
            val theme = findThemeById(settings.themeId, settings.customThemes)
                ?: findPresetTheme(settings.themeId)
            theme.getColorScheme(dark = darkTheme)
        }
    }
    val colorSchemeConverted = remember(darkTheme, amoledDarkMode, colorScheme) {
        if (darkTheme && amoledDarkMode) {
            colorScheme.copy(
                background = AMOLED_DARK_BACKGROUND,
                surface = AMOLED_DARK_BACKGROUND,
            )
        } else {
            colorScheme
        }
    }
    val extendColors = if (darkTheme) ExtendDarkColors else ExtendLightColors
    // 动态取色（壁纸或种子色）时，gray 阶从当前方案的 surface→onSurface 端点在 HCT 色调空间
    // 插值生成——中性文字/图标带一点取色色调，与主配色不再违和。red/orange/green/blue 是
    // 错误/警告/成功/信息的**语义色**，必须保持色相固定，不跟随取色。
    val extendColorsAdjusted = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        extendColors.copy(
            gray1 = deriveTone(colorScheme.surface, colorScheme.onSurface, 0),
            gray2 = deriveTone(colorScheme.surface, colorScheme.onSurface, 1),
            gray3 = deriveTone(colorScheme.surface, colorScheme.onSurface, 2),
            gray4 = deriveTone(colorScheme.surface, colorScheme.onSurface, 3),
            gray5 = deriveTone(colorScheme.surface, colorScheme.onSurface, 4),
            gray6 = deriveTone(colorScheme.surface, colorScheme.onSurface, 5),
            gray7 = deriveTone(colorScheme.surface, colorScheme.onSurface, 6),
            gray8 = deriveTone(colorScheme.surface, colorScheme.onSurface, 7),
            gray9 = deriveTone(colorScheme.surface, colorScheme.onSurface, 8),
            gray10 = deriveTone(colorScheme.surface, colorScheme.onSurface, 9),
        )
    } else {
        extendColors
    }
    // 更新状态栏图标颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, darkTheme) {
            // 宿主不一定是 Activity：TTS 朗读浮窗（Service 里的 ComposeView）就没有 window 可改。
            // 这里曾经是硬强转 `as Activity`，浮窗一 attach 就 ClassCastException 崩进程（真机实测）。
            // 没有 Activity 时跳过系统栏调整即可。
            val activity = view.context as? Activity
            if (activity == null) {
                onDispose { }
            } else {
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, view)
                val previousLightStatusBars = controller.isAppearanceLightStatusBars
                val previousLightNavigationBars = controller.isAppearanceLightNavigationBars
                controller.apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                onDispose {
                    // 嵌套主题（如终端的深色主题）退出时恢复原有系统栏图标颜色（对齐上游 a7ee362f）
                    controller.isAppearanceLightStatusBars = previousLightStatusBars
                    controller.isAppearanceLightNavigationBars = previousLightNavigationBars
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkMode provides darkTheme,
        LocalExtendColors provides extendColorsAdjusted,
        LocalOverscrollFactory provides null
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorSchemeConverted,
            typography = Typography,
            content = content,
            motionScheme = MotionScheme.expressive()
        )
    }
}

val MaterialTheme.extendColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendColors.current

/**
 * 在 [from]（亮端，如 surface）与 [to]（暗端，如 onSurface）之间取第 [step] 档（0..9）
 * 的中间色：HCT 色调空间线性插值——色调/色度也插值，动态方案的中性色带轻微取色色调，
 * 插值结果随之协调（固定 gray 阶是带一点冷蓝的中性，端点本身决定最终观感）。
 */
private fun deriveTone(from: Color, to: Color, step: Int): Color {
    val hctFrom = hct.Hct.fromInt(from.toArgb())
    val hctTo = hct.Hct.fromInt(to.toArgb())
    val t = step / 9.0
    // 色相走最短弧
    var hueDelta = hctTo.hue - hctFrom.hue
    if (hueDelta > 180) hueDelta -= 360
    if (hueDelta < -180) hueDelta += 360
    val hue = (hctFrom.hue + hueDelta * t + 360) % 360
    val chroma = hctFrom.chroma + (hctTo.chroma - hctFrom.chroma) * t
    val tone = hctFrom.tone + (hctTo.tone - hctFrom.tone) * t
    return Color(hct.Hct.from(hue, chroma, tone).toInt())
}


