package app.androidrik.rikkahub.ui.components.message

import android.content.ClipData
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import app.androidrik.ai.ui.UIMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Complete data-backed copy, independent of which lazy items happen to be mounted. */
@Composable
internal fun rememberMessageCopy(message: UIMessage): () -> Unit {
    val current by rememberUpdatedState(message)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, context, scope) {
        {
            val text = current.toText()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text))) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { Toast.makeText(context, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show() }
            }
            Unit
        }
    }
}
