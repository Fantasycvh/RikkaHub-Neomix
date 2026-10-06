package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import app.androidrik.rikkahub.data.datastore.Settings

// compositionLocalOf：设置变化时只重组读取了 LocalSettings 的组合，
// 而非像 staticCompositionLocalOf 那样使整棵 CompositionLocal 提供点以下的界面树全部重组
val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
