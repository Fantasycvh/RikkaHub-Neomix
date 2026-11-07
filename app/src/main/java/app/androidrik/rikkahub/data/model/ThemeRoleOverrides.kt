package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 角色覆写（对照 BiliPai ThemeRoleOverrides）：用户直接指定若干 M3 角色的颜色，
 * 作为**全局层**叠加在任意色源（动态取色/预设/自定义主题）之上。
 *
 * 槽位可空 = 该角色跟随生成（BiliPai 用固定默认值预填，这里用 null=跟随更轻，
 * 编辑器初值从当前色板反推）。[ThemeModeRoleOverrides.userBubbleHex] 是本 fork 特有
 * 槽位（橘瓣「用户气泡」灵感）：聊天用户气泡不归 accent 族管，经 `LocalUserBubbleColor`
 * 单独下发。批次 11 从 ui/theme 迁入——DisplaySettings 的 @Serializable 字段类型
 * 必须住在 data 层（消 data→ui 反向依赖）；渲染应用（withRoleOverrides 等）仍留 ui/theme。
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
