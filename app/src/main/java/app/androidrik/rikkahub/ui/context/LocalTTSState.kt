package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import app.androidrik.rikkahub.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
