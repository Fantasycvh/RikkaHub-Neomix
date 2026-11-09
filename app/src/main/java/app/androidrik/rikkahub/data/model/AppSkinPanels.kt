package app.androidrik.rikkahub.data.model

import app.androidrik.rikkahub.data.datastore.TopBarChromeStyle
import kotlinx.serialization.Serializable

/** null fields inherit host defaults. A background only paints the panel, never hides its content. */
@Serializable
data class AppSkinPanel(
    val background: ChatBubble? = null,
    val radiusDp: Float? = null,
    val contentColorHex: String? = null,
    val borderColorHex: String? = null,
    val borderWidthDp: Float? = null,
    val paddingHorizontalDp: Float? = null,
    val paddingVerticalDp: Float? = null,
    val gapDp: Float? = null,
) {
    fun normalized() = copy(background = background?.normalized()?.copy(geometry = ChatBubbleGeometry(0f, 0f)),
        radiusDp = radiusDp?.bound(0f, 48f, 16f), contentColorHex = contentColorHex?.takeIf(HEX::matches),
        borderColorHex = borderColorHex?.takeIf(HEX::matches), borderWidthDp = borderWidthDp?.bound(0f, 4f, 0f),
        paddingHorizontalDp = paddingHorizontalDp?.bound(0f, 32f, 12f),
        paddingVerticalDp = paddingVerticalDp?.bound(0f, 24f, 8f), gapDp = gapDp?.bound(0f, 24f, 8f))
}

@Serializable
data class AppSkinTopBar(val mode: TopBarChromeStyle? = null, val panel: AppSkinPanel = AppSkinPanel()) {
    fun normalized() = copy(panel = panel.normalized())
}

@Serializable
data class AppSkinDrawer(val panel: AppSkinPanel = AppSkinPanel(), val item: AppSkinPanel = AppSkinPanel()) {
    fun normalized() = copy(panel = panel.normalized(), item = item.normalized())
}

@Serializable
data class AppSkinCards(val reasoning: AppSkinPanel = AppSkinPanel(), val tool: AppSkinPanel = AppSkinPanel()) {
    fun normalized() = copy(reasoning = reasoning.normalized(), tool = tool.normalized())
}

fun AppSkin.panels(): List<AppSkinPanel> = listOf(topBar.panel, drawer.panel, drawer.item, cards.reasoning, cards.tool)
fun AppSkin.textureIds(): Set<String> = panels().flatMap { it.background?.textureIds().orEmpty() }.toSet()

enum class AppSkinPanelSlot { TOP_BAR, DRAWER, DRAWER_ITEM, REASONING, TOOL }
fun AppSkin.panel(slot: AppSkinPanelSlot): AppSkinPanel = when (slot) {
    AppSkinPanelSlot.TOP_BAR -> topBar.panel
    AppSkinPanelSlot.DRAWER -> drawer.panel
    AppSkinPanelSlot.DRAWER_ITEM -> drawer.item
    AppSkinPanelSlot.REASONING -> cards.reasoning
    AppSkinPanelSlot.TOOL -> cards.tool
}
/** Raw edits stay raw until preview/save, so incomplete color input is never rewritten. */
fun AppSkin.withPanel(slot: AppSkinPanelSlot, value: AppSkinPanel): AppSkin = when (slot) {
    AppSkinPanelSlot.TOP_BAR -> copy(topBar = topBar.copy(panel = value))
    AppSkinPanelSlot.DRAWER -> copy(drawer = drawer.copy(panel = value))
    AppSkinPanelSlot.DRAWER_ITEM -> copy(drawer = drawer.copy(item = value))
    AppSkinPanelSlot.REASONING -> copy(cards = cards.copy(reasoning = value))
    AppSkinPanelSlot.TOOL -> copy(cards = cards.copy(tool = value))
}

private val HEX = Regex("#[0-9a-fA-F]{6}")
private fun Float.bound(min: Float, max: Float, fallback: Float) = if (isFinite()) coerceIn(min, max) else fallback
