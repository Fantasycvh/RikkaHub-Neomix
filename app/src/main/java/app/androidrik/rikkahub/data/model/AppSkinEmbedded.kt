package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Native-hosted renderers share this group; document/program-owned colors remain authoritative. */
@Serializable
data class AppSkinEmbedded(
    val web: AppSkinWeb = AppSkinWeb(),
    val terminal: AppSkinTerminal = AppSkinTerminal(),
    val canvas: AppSkinCanvas = AppSkinCanvas(),
) {
    fun normalized() = copy(web = web.normalized(), terminal = terminal.normalized(), canvas = canvas.normalized())
}

@Serializable
data class AppSkinWeb(
    /** Only explicitly opted-in, app-owned HTML receives CSS variables. Remote pages never do. */
    val enabled: Boolean = true,
    val backgroundHex: String? = null,
    val foregroundHex: String? = null,
    val accentHex: String? = null,
) {
    fun normalized() = copy(backgroundHex = backgroundHex.validColor(), foregroundHex = foregroundHex.validColor(),
        accentHex = accentHex.validColor())
}

@Serializable
data class AppSkinTerminal(
    /** Legacy black/white is retained unless the user or skin opts into the app palette. */
    val followAppColors: Boolean = false,
    val foregroundHex: String? = null,
    val backgroundHex: String? = null,
    val cursorHex: String? = null,
    val fontSizeSp: Float = 12f,
) {
    fun normalized() = copy(foregroundHex = foregroundHex.validColor(), backgroundHex = backgroundHex.validColor(),
        cursorHex = cursorHex.validColor(), fontSizeSp = if (fontSizeSp.isFinite()) fontSizeSp.coerceIn(8f, 24f) else 12f)
}

@Serializable
data class AppSkinCanvas(
    val workspaceHex: String? = null,
    val selectionHex: String? = null,
    /** Initial document background, captured once when a new sketch is opened, including its export. */
    val newPaperHex: String? = null,
) {
    fun normalized() = copy(workspaceHex = workspaceHex.validColor(), selectionHex = selectionHex.validColor(),
        newPaperHex = newPaperHex.validColor())
}

fun isSkinEmbeddedColor(value: String) = value.isBlank() || COLOR.matches(value)
private val COLOR = Regex("#[0-9a-fA-F]{6}")
private fun String?.validColor(): String? = this?.takeIf(COLOR::matches)
