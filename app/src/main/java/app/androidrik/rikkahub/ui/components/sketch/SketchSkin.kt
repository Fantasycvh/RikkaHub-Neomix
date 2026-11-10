package app.androidrik.rikkahub.ui.components.sketch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.toComposeColorOrNull
import app.androidrik.rikkahub.ui.theme.skinContentColor

/** Skin chooses a new document's initial paper; recomposition never rewrites an open document. */
@Composable
internal fun rememberSkinSketchState(aspectRatio: Float?): SketchState {
    val initialPaper = LocalAppSkin.current.skin.embedded.canvas.newPaperHex.toComposeColorOrNull() ?: SketchDefaults.PaperColor
    return remember { SketchState(aspectRatio, initialPaper).apply {
        if (initialPaper.skinContentColor() == Color.White) usePreset(Color.White)
    } }
}
