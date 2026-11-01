package app.androidrik.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import hct.Hct
import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * 角色覆写（对照 BiliPai ThemeRoleOverrides）：用户直接指定若干 M3 角色的颜色，
 * 作为**全局层**叠加在任意色源（动态取色/预设/自定义主题）之上。
 *
 * 槽位可空 = 该角色跟随生成（BiliPai 用固定默认值预填，这里用 null=跟随更轻，
 * 编辑器初值从当前色板反推）。[userBubbleHex] 是本 fork 特有槽位（橘瓣「用户气泡」
 * 灵感）：聊天用户气泡不归 accent 族管，经 `LocalUserBubbleColor` 单独下发。
 */
@Serializable
data class ThemeRoleOverrides(
    val enabled: Boolean = false,
    val light: ThemeModeRoleOverrides = ThemeModeRoleOverrides(),
    val dark: ThemeModeRoleOverrides = ThemeModeRoleOverrides(),
) {
    fun forMode(darkMode: Boolean): ThemeModeRoleOverrides = if (darkMode) dark else light
}

@Serializable
data class ThemeModeRoleOverrides(
    /** → background + surface（直填） */
    val backgroundHex: String? = null,
    /** 主文字 → onBackground + onSurface（直填） */
    val primaryTextHex: String? = null,
    /** 次文字 → onSurfaceVariant（直填） */
    val secondaryTextHex: String? = null,
    /** 控件强调 → accent 族按新 seed 重新生成（不逐角色硬填，容器/on 色自动配套） */
    val controlAccentHex: String? = null,
    /** fork 特有：用户气泡填充色 → LocalUserBubbleColor（不影响 colorScheme） */
    val userBubbleHex: String? = null,
)

fun ThemeModeRoleOverrides.hasAnyOverride(): Boolean =
    backgroundHex != null || primaryTextHex != null || secondaryTextHex != null ||
        controlAccentHex != null || userBubbleHex != null

/**
 * 应用角色覆写到色板（纯函数，`RikkahubTheme` 在 scheme 解析后、AMOLED 纯黑覆盖前调用）。
 * 直填三类（背景/主文字/次文字）+ accent 族重生成；surfaceContainer 族不覆写（卡片保持
 * M3 中性 tonal——诚实边界：这不是完整角色编辑器）。
 */
fun ColorScheme.withRoleOverrides(o: ThemeModeRoleOverrides, dark: Boolean): ColorScheme {
    var s = this
    o.backgroundHex?.toComposeColorOrNull()?.let { s = s.copy(background = it, surface = it) }
    o.primaryTextHex?.toComposeColorOrNull()?.let { s = s.copy(onBackground = it, onSurface = it) }
    o.secondaryTextHex?.toComposeColorOrNull()?.let { s = s.copy(onSurfaceVariant = it) }
    o.controlAccentHex?.toComposeColorOrNull()?.let { s = s.withAccentFamily(it, dark) }
    return s
}

/**
 * accent 族按新 seed 重生成（BiliPai 核心原则：覆写强调色当「新 seed」走同一条生成管线，
 * 而非逐角色硬填）——摘取新方案 primary/secondary/tertiary 全家族 12 角色替换进基底。
 * accent 方案固定 TONAL_SPOT + 0.0 对比度（与 Theme.kt 种子色覆盖路径同一契约）。
 */
fun ColorScheme.withAccentFamily(seedColor: Color, dark: Boolean): ColorScheme {
    val accent = CustomTheme(
        primaryColorArgb = seedColor.toArgb().toLong() and 0xFFFFFFFFL,
    ).generateColorScheme(dark)
    return copy(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        secondary = accent.secondary,
        onSecondary = accent.onSecondary,
        secondaryContainer = accent.secondaryContainer,
        onSecondaryContainer = accent.onSecondaryContainer,
        tertiary = accent.tertiary,
        onTertiary = accent.onTertiary,
        tertiaryContainer = accent.tertiaryContainer,
        onTertiaryContainer = accent.onTertiaryContainer,
    )
}

/** 解析 `#RRGGBB`（容 6 位无 `#`），非法返回 null（视为未覆写；编辑器侧负责输入校验）。 */
fun String?.toComposeColorOrNull(): Color? {
    if (this == null) return null
    val hex = removePrefix("#")
    if (hex.length != 6) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color((value or 0xFF000000L).toInt())
}

/** 格式化为 `#RRGGBB`（编辑器从当前色板反推初值用）。 */
fun Color.toHexCode(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/**
 * WCAG 2.x 相对亮度对比度（≥ 4.5 为 AA 正文标准；编辑器对比度预警用，BiliPai
 * `themeRoleContrastRatio` 同款语义）。
 */
fun wcagContrastRatio(a: Color, b: Color): Double {
    fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val s = channel.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
    val l1 = maxOf(luminance(a), luminance(b))
    val l2 = minOf(luminance(a), luminance(b))
    return (l1 + 0.05) / (l2 + 0.05)
}

/** WCAG 对比度 AA 正文阈值。 */
const val WCAG_AA_CONTRAST = 4.5

/**
 * 按背景的 HCT tone 自适应内容色（tone ≥ 50 → 深内容色）：用户气泡/覆写背景是任意
 * 拾取色，M3 的 contentColorFor 无法命中，需按明度兜底。
 */
fun adaptiveContentColor(background: Color): Color {
    val tone = Hct.fromInt(background.toArgb()).tone
    return if (tone >= 50.0) Color(0xFF1C1B1F) else Color(0xFFFFFFFF)
}
