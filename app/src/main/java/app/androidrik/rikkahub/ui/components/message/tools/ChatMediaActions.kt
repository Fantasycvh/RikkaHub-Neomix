package app.androidrik.rikkahub.ui.components.message.tools

import androidx.compose.runtime.compositionLocalOf

/** Available only in the interactive chat composer. The callback imports a private attachment copy. */
val LocalChatMediaReference = compositionLocalOf<((String, String?) -> Unit)?> { null }
