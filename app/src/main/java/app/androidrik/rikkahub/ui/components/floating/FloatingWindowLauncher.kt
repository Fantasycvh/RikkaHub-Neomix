package app.androidrik.rikkahub.ui.components.floating

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 单个应用内小窗；仅用户主动打开，不在进程重启后自行恢复。 */
object FloatingWindowLauncher {
    data class Request(val conversationId: String, val sequence: Long)

    var request by mutableStateOf<Request?>(null)
        private set

    fun openInApp(conversationId: String) {
        request = Request(conversationId, (request?.sequence ?: 0L) + 1L)
    }

    fun closeInApp() {
        request = null
    }
}
