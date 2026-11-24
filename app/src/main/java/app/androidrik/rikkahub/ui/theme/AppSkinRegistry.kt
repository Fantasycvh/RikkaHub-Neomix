package app.androidrik.rikkahub.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import app.androidrik.plugin.api.RikkaPlugin
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import app.androidrik.rikkahub.data.datastore.builtinSkinChatLink
import app.androidrik.rikkahub.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSkinPreset(val id: String, val name: String, val skin: AppSkin)

interface PluginAppSkinProvider : RikkaPlugin {
    val appSkins: List<AppSkinPreset> get() = emptyList()
}

/** Owner-scoped contributions are automatically released by the existing plugin lifecycle bridge. */
class AppSkinRegistry {
    private val contributions = linkedMapOf<String, List<AppSkinPreset>>()
    private var installed = emptyList<AppSkinPreset>()
    @Synchronized internal fun replaceInstalled(skins: List<AppSkinPreset>) { installed = skins; publish() }
    private val state = MutableStateFlow(builtInAppSkins)
    val skins = state.asStateFlow()

    @Synchronized fun register(owner: String, skins: List<AppSkinPreset>): AutoCloseable {
        require(owner.isNotBlank() && owner !in setOf("builtin", "package") && ':' !in owner)
        require(skins.all { it.id.isNotBlank() && ':' !in it.id && it.name.isNotBlank() })
        require(skins.map { it.id }.distinct().size == skins.size)
        val owned = skins.map { it.copy(id = "$owner:${it.id}", skin = it.skin.normalized()) }
        contributions[owner] = owned
        publish()
        return AutoCloseable { synchronized(this) {
            if (contributions[owner] === owned) { contributions.remove(owner); publish() }
        } }
    }

    private fun publish() { state.value = builtInAppSkins + installed + contributions.values.flatten() }
    companion object { val shared = AppSkinRegistry() }
}

data class ResolvedAppSkin(val skin: AppSkin = AppSkin(), val chat: ChatAppearance = ChatAppearance())

/** One pure entry for global tokens and the legacy chat domain. Missing plugins retain user overrides. */
fun resolveAppSkin(
    display: DisplaySetting,
    skins: List<AppSkinPreset> = AppSkinRegistry.shared.skins.value,
    chatPresets: List<ChatAppearancePreset> = ChatAppearanceRegistry.shared.presets.value,
): ResolvedAppSkin {
    val base = skins.firstOrNull { it.id == display.appSkinId }?.skin ?: AppSkin()
    // 绑定态下皮肤推荐方案遮蔽 presetId（换肤即时跟随）；解绑态/无推荐回落 presetId ?: details。
    val linkedPresetId = if (display.chatAppearanceDetached) null else builtinSkinChatLink[display.appSkinId]
    val chat = chatPresets.firstOrNull { it.id == (linkedPresetId ?: display.chatAppearancePresetId) }?.appearance
        ?: display.toChatAppearance()
    return ResolvedAppSkin(display.appSkinOverrides.applyTo(base), chat.normalized())
}

/** 绑定/解绑状态下实际生效的聊天方案 id（= resolveAppSkin 的 chat 选择口），供设置页 UI 展示。 */
fun effectiveChatPresetId(display: DisplaySetting): String? =
    (if (display.chatAppearanceDetached) null else builtinSkinChatLink[display.appSkinId])
        ?: display.chatAppearancePresetId

val LocalAppSkin = compositionLocalOf { ResolvedAppSkin() }

@Composable
fun rememberAppSkin(display: DisplaySetting): ResolvedAppSkin {
    val skins by AppSkinRegistry.shared.skins.collectAsState()
    val chats by ChatAppearanceRegistry.shared.presets.collectAsState()
    val trial by SkinTrialController.shared.trial.collectAsState()
    val resolved = resolveAppSkin(display, skins, chats)
    // 试穿预览整份重解析：绑定态下聊天域方案随 target 皮肤联动预览（SkinSelection 只含皮肤字段，
    // 不触碰聊天域持久化字段，确认/撤销的 CAS 语义不受影响）
    val preview = trial?.let { resolveAppSkin(it.target.applyTo(display), skins, chats) }
    return preview ?: resolved
}

val builtInAppSkins = listOf(
    AppSkinPreset("builtin.default", "默认", AppSkin()),
    AppSkinPreset("builtin.compact", "紧凑", AppSkin(
        shapes = AppSkinShapes(cardGroupOuterDp = 12f, cardGroupInnerDp = 2f, buttonDp = 12f, inputDp = 8f),
        spacing = AppSkinSpacing(sectionDp = 10f, formHorizontalDp = 12f, formVerticalDp = 8f, formGapDp = 8f),
        motion = AppSkinMotion.STANDARD)),
    AppSkinPreset("builtin.reading", "舒展阅读", AppSkin(
        typography = AppSkinTypography(sizeRatio = 1.1f),
        shapes = AppSkinShapes(cardGroupOuterDp = 28f, cardGroupInnerDp = 8f, buttonDp = 24f, inputDp = 16f),
        spacing = AppSkinSpacing(pageDp = 12f, sectionDp = 20f, groupItemDp = 4f, formVerticalDp = 16f, formGapDp = 16f))),
    // 第三轮档位：气质档 = 完整 token 组合（palette + typography + shapes + spacing + motion）
    AppSkinPreset("builtin.squircle", "现代旗舰", AppSkin(
        typography = AppSkinTypography(expressive = true),
        shapes = AppSkinShapes(extraSmallDp = 8f, smallDp = 12f, mediumDp = 18f, largeDp = 24f, extraLargeDp = 32f,
            cardGroupOuterDp = 28f, cardGroupInnerDp = 6f, buttonDp = 28f, inputDp = 18f, continuous = true),
        spacing = AppSkinSpacing(pageDp = 12f, sectionDp = 18f),
        motion = AppSkinMotion.EXPRESSIVE)),
    AppSkinPreset("builtin.paper", "纸质感", AppSkin(
        typography = AppSkinTypography(family = ChatFontFamily.SERIF, sizeRatio = 1.05f),
        shapes = AppSkinShapes(extraSmallDp = 4f, smallDp = 6f, mediumDp = 10f, largeDp = 14f, extraLargeDp = 20f,
            cardGroupOuterDp = 18f, cardGroupInnerDp = 4f, buttonDp = 12f, inputDp = 10f),
        spacing = AppSkinSpacing(pageDp = 12f, sectionDp = 20f, groupItemDp = 4f, formVerticalDp = 14f, formGapDp = 16f),
        motion = AppSkinMotion.STANDARD,
        palette = CustomTheme(id = "builtin.paper.palette", name = "纸质感",
            primaryColorArgb = 0xFFA0522D, paletteStyle = ThemePaletteStyle.NEUTRAL))),
    // 第二批档位：分组容器形态 + 排版细项（工程感 = 冷灰线框；iOS 风 = inset 分组底卡）
    AppSkinPreset("builtin.engineering", "工程感", AppSkin(
        typography = AppSkinTypography(sizeRatio = 0.98f, letterSpacingSp = 0.05f, tabularNumerals = true, monoLabels = true),
        shapes = AppSkinShapes(extraSmallDp = 2f, smallDp = 4f, mediumDp = 6f, largeDp = 8f, extraLargeDp = 12f,
            cardGroupOuterDp = 10f, cardGroupInnerDp = 0f, buttonDp = 6f, inputDp = 6f),
        spacing = AppSkinSpacing(pageDp = 10f, sectionDp = 14f, groupItemDp = 0f,
            formHorizontalDp = 14f, formVerticalDp = 10f, formGapDp = 10f),
        motion = AppSkinMotion.STANDARD,
        topBar = AppSkinTopBar(panel = AppSkinPanel(radiusDp = 0f, borderWidthDp = 1f)),
        drawer = AppSkinDrawer(panel = AppSkinPanel(radiusDp = 0f, borderWidthDp = 1f), item = AppSkinPanel(radiusDp = 6f)),
        groups = AppSkinGroups(style = AppSkinGroupStyle.OUTLINED, divider = AppSkinDividerStyle.FULL,
            dividerWidthDp = 1f, borderWidthDp = 1f),
        palette = CustomTheme(id = "builtin.engineering.palette", name = "工程感",
            primaryColorArgb = 0xFF55606B, paletteStyle = ThemePaletteStyle.NEUTRAL))),
    AppSkinPreset("builtin.ios", "iOS 风", AppSkin(
        typography = AppSkinTypography(letterSpacingSp = -0.05f),
        shapes = AppSkinShapes(extraSmallDp = 8f, smallDp = 10f, mediumDp = 12f, largeDp = 16f, extraLargeDp = 22f,
            cardGroupOuterDp = 16f, cardGroupInnerDp = 16f, buttonDp = null, inputDp = 12f, continuous = true),
        spacing = AppSkinSpacing(pageDp = 16f, sectionDp = 24f, groupItemDp = 0f,
            formHorizontalDp = 16f, formVerticalDp = 12f, formGapDp = 12f),
        motion = AppSkinMotion.STANDARD,
        topBar = AppSkinTopBar(panel = AppSkinPanel(radiusDp = 0f)),
        drawer = AppSkinDrawer(panel = AppSkinPanel(radiusDp = 0f), item = AppSkinPanel(radiusDp = 10f)),
        groups = AppSkinGroups(style = AppSkinGroupStyle.INSET, divider = AppSkinDividerStyle.INSET,
            dividerInsetDp = 56f, dividerWidthDp = 0.5f),
        palette = CustomTheme(id = "builtin.ios.palette", name = "iOS 风",
            primaryColorArgb = 0xFF0A84FF, paletteStyle = ThemePaletteStyle.FIDELITY))),
    AppSkinPreset("builtin.premium-dark", "浅蓝", AppSkin(
        shapes = AppSkinShapes(
            extraSmallDp = 8f, smallDp = 12f, mediumDp = 16f, largeDp = 20f, extraLargeDp = 28f,
            cardGroupOuterDp = 22f, cardGroupInnerDp = 4f,
            buttonDp = 16f, inputDp = 16f, continuous = true, iconTile = AppSkinIconTile(),
        ),
        spacing = AppSkinSpacing(
            pageDp = 12f, sectionDp = 20f, groupItemDp = 10f,
            formHorizontalDp = 16f, formVerticalDp = 14f, formGapDp = 10f,
        ),
        motion = AppSkinMotion.STANDARD,
        topBar = AppSkinTopBar(
            mode = TopBarChromeStyle.CAPSULES,
            panel = AppSkinPanel(radiusDp = 24f, borderWidthDp = 0.5f,
                paddingHorizontalDp = 12f, paddingVerticalDp = 8f),
        ),
        drawer = AppSkinDrawer(item = AppSkinPanel(radiusDp = 12f)),
        cards = AppSkinCards(
            reasoning = AppSkinPanel(radiusDp = 16f, borderWidthDp = 0.5f),
            tool = AppSkinPanel(radiusDp = 16f, borderWidthDp = 0.5f),
        ),
        hero = AppSkinHero(
            visible = true,
            lightGradient = AppearanceGradient(listOf("#D6ECFF", "#C4E3F7", "#D9F0F5")),
            darkGradient = AppearanceGradient(listOf("#16344F", "#245979", "#356985")),
        ),
        palette = CustomTheme(
            id = "builtin.premium-dark.palette", name = "浅蓝",
            primaryColorArgb = 0xFF22678E, secondaryColorArgb = 0xFF4B7086, tertiaryColorArgb = 0xFF4E899F,
            paletteStyle = ThemePaletteStyle.TONAL_SPOT, contrastLevel = 0.0,
        ),
    )),
)
