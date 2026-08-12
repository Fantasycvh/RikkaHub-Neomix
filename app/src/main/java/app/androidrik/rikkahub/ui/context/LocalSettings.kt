package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import app.androidrik.rikkahub.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
