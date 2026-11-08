package app.androidrik.rikkahub.ui.components.haze

import androidx.compose.runtime.compositionLocalOf
import dev.chrisbanes.haze.HazeState

/** Only the wallpaper source is provided. Sampling the message list would feed text back into itself. */
val LocalBubbleBackdrop = compositionLocalOf<HazeState?> { null }

enum class BubbleGlassBackend { GLASS, BLUR, STATIC }

fun bubbleGlassBackend(sdkInt: Int, hasActiveBackdrop: Boolean): BubbleGlassBackend = when {
    !hasActiveBackdrop -> BubbleGlassBackend.STATIC
    sdkInt >= 33 -> BubbleGlassBackend.GLASS
    sdkInt >= 31 -> BubbleGlassBackend.BLUR
    else -> BubbleGlassBackend.STATIC
}
