package app.androidrik.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import app.androidrik.rikkahub.data.model.AppSkinTerminal
import com.termux.terminal.TerminalColors

data class TerminalSkinColors(val foreground: Int, val background: Int, val cursor: Int) {
    fun applyTo(colors: TerminalColors) = colors.setSessionDefaultColors(foreground, background, cursor)
}

fun Color.skinContentColor(): Color = if (luminance() > .179f) Color.Black else Color.White

fun AppSkinTerminal.resolveColors(palette: ColorScheme): TerminalSkinColors {
    val customBackground = backgroundHex.toComposeColorOrNull()
    val background = customBackground ?: if (followAppColors) palette.surface else Color.Black
    val foreground = foregroundHex.toComposeColorOrNull() ?: customBackground?.skinContentColor()
        ?: if (followAppColors) palette.onSurface else Color.White
    val cursor = cursorHex.toComposeColorOrNull() ?: if (customBackground != null) foreground
        else if (followAppColors) palette.primary else Color.White
    return TerminalSkinColors(foreground.toArgb(), background.toArgb(), cursor.toArgb())
}
