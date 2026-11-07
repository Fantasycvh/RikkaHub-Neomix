package app.androidrik.rikkahub.ui.theme

import app.androidrik.rikkahub.data.datastore.BackgroundEffectType
import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.data.model.ThemeRoleOverrides
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.LiquidGlassReadabilityMode
import kotlinx.serialization.Serializable

/**
 * 主题快照（对照 BiliPai「主题 = 设置快照」语义）：导出 = 色板 + 可选外观子集。
 *
 * 兼容：导入端先按本类型解码，失败回退裸 [CustomTheme]（旧版导出/手写 JSON）。
 * 明确排除项：chatCustomFontPath/Name（本机路径跨设备悬空）、userAvatar（多态+隐私）、
 * customAppName/launcherAliasId（设备级应用身份）、背景图（per-assistant）。
 */
@Serializable
data class ThemeSnapshot(
    val formatVersion: Int = 1,
    // 必填（无默认）：旧版裸 CustomTheme JSON 缺 theme 键 → 解码失败 → 导入端回退按裸主题
    // 解码。若给默认值，旧 JSON 会「成功」解码成默认主题而静默丢数据。
    val theme: CustomTheme,
    val appearance: ThemeAppearanceSnapshot? = null,
)

/**
 * 外观子集：液态玻璃组 + 字体族 + 气泡/字号 + 角色覆写。字段带与 `DisplaySetting`
 * 一致的默认值，旧快照缺字段可解码；应用时**逐字段合并**（只动本快照携带的域）。
 */
@Serializable
data class ThemeAppearanceSnapshot(
    val enableBlurEffect: Boolean = false,
    val inputEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val enableGlassTheme: Boolean = false,
    val listGlassEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val enableTopBarBlur: Boolean = false,
    val topBarEffectType: BackgroundEffectType = BackgroundEffectType.BLUR,
    val liquidGlassReadabilityMode: LiquidGlassReadabilityMode = LiquidGlassReadabilityMode.AUTO,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val fontSizeRatio: Float = 1.0f,
    val themeRoleOverrides: ThemeRoleOverrides = ThemeRoleOverrides(),
)

/** 从当前显示设置取外观子集（导出侧）。 */
fun DisplaySetting.toAppearanceSnapshot(): ThemeAppearanceSnapshot = ThemeAppearanceSnapshot(
    enableBlurEffect = enableBlurEffect,
    inputEffectType = inputEffectType,
    enableGlassTheme = enableGlassTheme,
    listGlassEffectType = listGlassEffectType,
    enableTopBarBlur = enableTopBarBlur,
    topBarEffectType = topBarEffectType,
    liquidGlassReadabilityMode = liquidGlassReadabilityMode,
    chatFontFamily = chatFontFamily,
    showAssistantBubble = showAssistantBubble,
    bubbleOpacity = bubbleOpacity,
    fontSizeRatio = fontSizeRatio,
    themeRoleOverrides = themeRoleOverrides,
)

/** 应用外观快照：逐字段合并，只覆盖快照携带的域，其余显示设置不动（纯函数）。 */
fun DisplaySetting.withAppearanceSnapshot(a: ThemeAppearanceSnapshot): DisplaySetting = copy(
    enableBlurEffect = a.enableBlurEffect,
    inputEffectType = a.inputEffectType,
    enableGlassTheme = a.enableGlassTheme,
    listGlassEffectType = a.listGlassEffectType,
    enableTopBarBlur = a.enableTopBarBlur,
    topBarEffectType = a.topBarEffectType,
    liquidGlassReadabilityMode = a.liquidGlassReadabilityMode,
    chatFontFamily = a.chatFontFamily,
    showAssistantBubble = a.showAssistantBubble,
    bubbleOpacity = a.bubbleOpacity,
    fontSizeRatio = a.fontSizeRatio,
    themeRoleOverrides = a.themeRoleOverrides,
)
