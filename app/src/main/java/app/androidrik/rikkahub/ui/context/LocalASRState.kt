package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import app.androidrik.rikkahub.ui.hooks.CustomAsrState

val LocalASRState = compositionLocalOf<CustomAsrState> { error("Not provided yet") }

