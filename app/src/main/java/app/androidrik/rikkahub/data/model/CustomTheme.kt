package app.androidrik.rikkahub.data.model

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.androidrik.material3.toColorScheme
import palettes.TonalPalette
import kotlin.uuid.Uuid

/**
 * 自定义主题的调色风格（BiliPai 参照的收窄版：不移植 flex_seed_scheme 的 22 风格，
 * 直接暴露 vendored material-color-utilities 的 [Variant] 9 值）。
 * 枚举常量名与 MCU `Variant` 一一对应，映射用 [toMcuVariant] 按名转换。
 * JSON 存小写短名（@SerialName），自有序列化枚举不依赖 MCU 源码。
 */
@Serializable
enum class ThemePaletteStyle {
    @SerialName("tonal_spot") TONAL_SPOT,
    @SerialName("monochrome") MONOCHROME,
    @SerialName("neutral") NEUTRAL,
    @SerialName("vibrant") VIBRANT,
    @SerialName("expressive") EXPRESSIVE,
    @SerialName("fidelity") FIDELITY,
    @SerialName("content") CONTENT,
    @SerialName("rainbow") RAINBOW,
    @SerialName("fruit_salad") FRUIT_SALAD,
}

/** 对比度合法档位（M3 标准）；读到的越界值按最近档归一（见 [CustomTheme.normalizeContrastLevel]）。 */
val THEME_CONTRAST_LEVELS = doubleArrayOf(0.0, 0.5, 1.0)

@Serializable
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = 0xFF6750A4,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
    /** null = TONAL_SPOT（旧行为；旧 JSON 零迁移，ignoreUnknownKeys 兼容）。 */
    val paletteStyle: ThemePaletteStyle? = null,
    /** null = 0.0；合法域见 [THEME_CONTRAST_LEVELS]，读取时归一到最近档。 */
    val contrastLevel: Double? = null,
) {
    fun generateColorScheme(dark: Boolean): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = DynamicScheme.DEFAULT_SPEC_VERSION
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = normalizeContrastLevel(contrastLevel)
        val variant = (paletteStyle ?: ThemePaletteStyle.TONAL_SPOT).toMcuVariant()
        val colorSpec = ColorSpecs.get(specVersion)

        val primaryPalette = colorSpec.getPrimaryPalette(
            variant, sourceHct, dark, platform, contrastLevel,
        )
        val secondaryPalette = if (secondaryColorArgb != null) {
            TonalPalette.fromInt(secondaryColorArgb.toInt())
        } else {
            colorSpec.getSecondaryPalette(
                variant, sourceHct, dark, platform, contrastLevel,
            )
        }
        val tertiaryPalette = if (tertiaryColorArgb != null) {
            TonalPalette.fromInt(tertiaryColorArgb.toInt())
        } else {
            colorSpec.getTertiaryPalette(
                variant, sourceHct, dark, platform, contrastLevel,
            )
        }

        val scheme = DynamicScheme(
            sourceHct,
            variant,
            dark,
            contrastLevel,
            platform,
            specVersion,
            primaryPalette,
            secondaryPalette,
            tertiaryPalette,
            colorSpec.getNeutralPalette(
                variant, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getNeutralVariantPalette(
                variant, sourceHct, dark, platform, contrastLevel,
            ),
            colorSpec.getErrorPalette(
                variant, sourceHct, dark, platform, contrastLevel,
            ),
        )
        return scheme.toColorScheme()
    }

    companion object {
        /** 越界夹取 + 按最近合法档归一（外部 JSON 可能带任意 double）。 */
        fun normalizeContrastLevel(raw: Double?): Double {
            val v = (raw ?: 0.0).coerceIn(-1.0, 1.0)
            return THEME_CONTRAST_LEVELS.minBy { kotlin.math.abs(it - v) }
        }
    }
}

/** 按名映射到 vendored MCU Variant（枚举常量刻意同名；防御性回退 TONAL_SPOT）。 */
fun ThemePaletteStyle.toMcuVariant(): Variant = runCatching { Variant.valueOf(name) }
    .getOrDefault(Variant.TONAL_SPOT)
