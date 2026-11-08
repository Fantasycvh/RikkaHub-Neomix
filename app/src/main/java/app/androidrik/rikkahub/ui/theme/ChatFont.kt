package app.androidrik.rikkahub.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import java.io.File

val LocalChatFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Composable
fun ChatFontProvider(
    displaySetting: DisplaySetting,
    content: @Composable () -> Unit,
) {
    ChatAppearanceProvider(displaySetting) {
        val chatFontFamily = rememberChatFontFamily(displaySetting)
        CompositionLocalProvider(LocalChatFontFamily provides chatFontFamily) {
            content()
        }
    }
}

@Composable
fun rememberChatFontFamily(displaySetting: DisplaySetting): FontFamily {
    val context = LocalContext.current
    val appearance = rememberChatAppearance(displaySetting)
    val typography = appearance.typography
    return remember(
        typography.family,
        typography.customFontPath,
    ) {
        resolveChatFontFamily(context, typography.family, typography.customFontPath)
    }
}

fun DisplaySetting.resolveChatFontFamily(context: Context): FontFamily =
    resolveChatFontFamily(context, chatFontFamily, chatCustomFontPath)

private fun resolveChatFontFamily(context: Context, family: ChatFontFamily, path: String): FontFamily = when (family) {
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
