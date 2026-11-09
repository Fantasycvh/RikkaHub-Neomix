package app.androidrik.rikkahub.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import java.io.File
import app.androidrik.rikkahub.ui.components.haze.LocalBubbleBackdrop
import dev.chrisbanes.haze.HazeState
import app.androidrik.rikkahub.ui.components.message.BubbleCoordinateRegistry
import app.androidrik.rikkahub.ui.components.message.LocalBubbleCoordinates

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }
val LocalChatTextGradient = compositionLocalOf<app.androidrik.rikkahub.data.model.AppearanceGradient?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    backdropState: HazeState? = null,
    content: @Composable () -> Unit,
) {
    ChatAppearanceProvider(displaySetting) {
        val coordinates = remember { BubbleCoordinateRegistry() }
        val chatFontFamily = rememberChatFontFamily(displaySetting)
        val gradient = rememberChatAppearance(displaySetting).typography.gradient
        CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily, LocalBubbleBackdrop provides backdropState,
            LocalChatTextGradient provides gradient, LocalBubbleCoordinates provides coordinates) {
            content()
        }
    }
}

@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val context = LocalContext.current
    val appearance = rememberChatAppearance(displaySetting)
    val typography = appearance.typography
    val inherited = MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.Default
    return remember(
        typography.family,
        typography.customFontPath,
        inherited,
    ) {
        if (typography.family == ChatFontFamily.DEFAULT) inherited else
            resolveChatFontFamily(context, typography.family, typography.customFontPath)
    }
}

fun DisplaySetting.resolveChatFontFamily(context: Context): FontFamily {
    val resolved = resolveAppSkin(this)
    val chat = resolved.chat.typography
    val font = if (chat.family == ChatFontFamily.DEFAULT) resolved.skin.typography.let { it.family to it.customFontPath }
        else chat.family to chat.customFontPath
    return resolveChatFontFamily(context, font.first, font.second)
}

internal fun resolveChatFontFamily(context: Context, family: ChatFontFamily, path: String): FontFamily = when (family) {
    ChatFontFamily.DEFAULT -> FontFamily.Default
    ChatFontFamily.SERIF -> FontFamily.Serif
    ChatFontFamily.MONOSPACE -> FontFamily.Monospace
    ChatFontFamily.CUSTOM -> loadCustomFontFamily(context, path) ?: FontFamily.Default
}

private fun loadCustomFontFamily(context: Context, relativePath: String): FontFamily? {
    val file = resolveFilesDirFile(context, relativePath) ?: return null
    if (!file.isFile) return null
    return runCatching {
        Typeface.createFromFile(file)
        FontFamily(Font(file))
    }.getOrNull()
}

private fun resolveFilesDirFile(context: Context, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val filesDir = runCatching { context.filesDir.canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(filesDir, relativePath).canonicalFile }.getOrNull() ?: return null
    return file.takeIf { it.path.startsWith("${filesDir.path}${File.separator}") }
}
