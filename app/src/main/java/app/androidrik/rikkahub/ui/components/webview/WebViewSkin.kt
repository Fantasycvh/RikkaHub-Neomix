package app.androidrik.rikkahub.ui.components.webview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import app.androidrik.rikkahub.ui.theme.skinContentColor
import app.androidrik.rikkahub.utils.toCssHex
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** Explicit, content-side opt-in. It carries no extra WebView permissions or JavaScript bridges. */
const val APP_SKIN_HTML_META = "<meta name=\"rikka-app-skin\" content=\"v1\">"

data class WebSkinStyle(
    val enabled: Boolean,
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val dark: Boolean,
    val fontFamily: String,
    val fontSizePx: Float,
) {
    fun script(): String {
        val css = if (!enabled) "" else """
            :root { --rikka-background: ${background.toCssHex()}; --rikka-foreground: ${foreground.toCssHex()};
              --rikka-accent: ${accent.toCssHex()}; --rikka-font-family: $fontFamily;
              --rikka-font-size: ${fontSizePx}px; color-scheme: ${if (dark) "dark" else "light"}; }
            :where(html) { font-size: var(--rikka-font-size); }
            :where(body) { background: var(--rikka-background); color: var(--rikka-foreground);
              font-family: var(--rikka-font-family); }
            :where(a) { color: var(--rikka-accent); }
        """.trimIndent()
        // JSON encoding prevents CSS/string values from becoming executable JavaScript.
        return """(() => {
            const id = 'rikka-app-skin-v1'; let style = document.getElementById(id);
            const css = ${JsonPrimitive(css)};
            if (!css) { if (style) style.remove(); }
            else { if (!style) { style = document.createElement('style'); style.id = id;
              (document.head || document.documentElement).appendChild(style); } style.textContent = css; }
            window.dispatchEvent(new CustomEvent('rikka-skin-change', {detail: {enabled: $enabled}}));
        })();""".trimIndent()
    }
}

@Composable
fun rememberWebSkinStyle(): WebSkinStyle {
    val skin = LocalAppSkin.current.skin
    val web = skin.embedded.web
    val palette = MaterialTheme.colorScheme
    val customBackground = web.backgroundHex.toComposeColorOrNull()
    return WebSkinStyle(web.enabled, customBackground ?: palette.background,
        web.foregroundHex.toComposeColorOrNull() ?: customBackground?.skinContentColor() ?: palette.onBackground,
        web.accentHex.toComposeColorOrNull() ?: palette.primary, LocalDarkMode.current,
        when (skin.typography.family) {
            ChatFontFamily.SERIF -> "serif"
            ChatFontFamily.MONOSPACE -> "monospace"
            else -> "sans-serif" // Device-local font paths are never disclosed to HTML.
        }, 16f * skin.typography.sizeRatio * LocalDensity.current.fontScale)
}

/** A navigation to any different URL loses the opt-in, including links from an opted-in document. */
internal fun WebContent.allowsSkinAt(url: String?): Boolean {
    val data = this as? WebContent.Data ?: return false
    if (!data.appOwned || data.mimeType?.substringBefore(';')?.lowercase() != "text/html") return false
    val target = skinDocumentUrl(url) ?: return false
    return if (data.baseUrl.isNullOrBlank()) target == "about:blank" || target.startsWith("data:text/html")
        else target == skinDocumentUrl(data.baseUrl)
}

/** Browsers canonicalize an empty HTTP path to '/'; other paths and queries remain distinct. */
private fun skinDocumentUrl(value: String?): String? {
    val url = value?.substringBefore('#') ?: return null
    val parsed = runCatching { URI(url) }.getOrNull() ?: return null
    return if ((parsed.scheme == "https" || parsed.scheme == "http") && parsed.rawPath.isNullOrEmpty()) {
        val query = url.indexOf('?')
        if (query < 0) "$url/" else url.substring(0, query) + "/" + url.substring(query)
    } else url
}

internal fun WebViewState.applySkin(view: android.webkit.WebView) {
    val script = skinScript ?: return
    // loadDataWithBaseURL may report about:blank through getUrl() while the actual document uses baseUrl.
    // The page callbacks track that document URL. Check it again inside JS against stale callbacks/navigation.
    if (!isLoading && content.allowsSkinAt(currentUrl) && script != appliedSkinScript) {
        val expected = JsonPrimitive(currentUrl?.substringBefore('#').orEmpty())
        view.evaluateJavascript("if (document.URL.split('#')[0] === $expected) { $script }", null)
        appliedSkinScript = script
    }
}
