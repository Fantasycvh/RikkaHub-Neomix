package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/** 分组容器形态：FLAT = 现状逐行卡片；OUTLINED = 整组线框；INSET = 整组底卡 + 内缩分隔线。 */
@Serializable
enum class AppSkinGroupStyle { FLAT, OUTLINED, INSET }

/** 行间分隔线：FULL = 通栏；INSET = 起始侧缩进（RTL 跟随）。 */
@Serializable
enum class AppSkinDividerStyle { NONE, FULL, INSET }

/**
 * CardGroup 这类「一组多行」容器的形态 token。
 * 默认值等于第一批现状（FLAT + 无分隔线），老皮肤 JSON 零迁移。
 * 不接管顶栏/抽屉/气泡（各自有 AppSkinPanel / ChatBubble）。
 */
@Serializable
data class AppSkinGroups(
    val style: AppSkinGroupStyle = AppSkinGroupStyle.FLAT,
    val divider: AppSkinDividerStyle = AppSkinDividerStyle.NONE,
    /** 分隔线起点缩进；INSET 档生效，FULL 档忽略。 */
    val dividerInsetDp: Float = 16f,
    val dividerWidthDp: Float = 1f,
    /** null = 继承 colorScheme.outlineVariant。 */
    val dividerColorHex: String? = null,
    /** 仅 OUTLINED / INSET 生效：整组外描边。 */
    val borderWidthDp: Float = 0f,
    val borderColorHex: String? = null,
    /** 仅 OUTLINED / INSET 生效：组容器底色；null = surfaceBright。 */
    val containerColorHex: String? = null,
    /** 组容器内边距；INSET 档留 0，靠行内 padding 对齐。 */
    val containerPaddingDp: Float = 0f,
) {
    fun normalized() = copy(
        // FLAT 逐行自带圆角与间隙，分隔线会露出线头：契约层直接收掉。
        divider = if (style == AppSkinGroupStyle.FLAT) AppSkinDividerStyle.NONE else divider,
        dividerInsetDp = dividerInsetDp.bound(0f, 72f, 16f),
        dividerWidthDp = dividerWidthDp.bound(0f, 3f, 1f),
        dividerColorHex = dividerColorHex?.takeIf(HEX::matches),
        borderWidthDp = borderWidthDp.bound(0f, 3f, 0f),
        borderColorHex = borderColorHex?.takeIf(HEX::matches),
        containerColorHex = containerColorHex?.takeIf(HEX::matches),
        containerPaddingDp = containerPaddingDp.bound(0f, 16f, 0f),
    )
}

private val HEX = Regex("#[0-9a-fA-F]{6}")
private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
