package app.androidrik.rikkahub.ui.components.ai

import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.androidrik.rikkahub.ui.hooks.ChatInputState

@Composable
internal fun SegmentedTextEditor(state: ChatInputState, onDone: () -> Unit, onCancel: () -> Unit) {
    val session = remember(state) { state.segmentedDraft ?: SegmentedEditSession(state.textContent, state.editingMessage).also { state.segmentedDraft = it } }
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var conflict by remember { mutableStateOf(false) }
    fun move(action: () -> Unit) { focus.clearFocus(force = true); action() }
    BasicAlertDialog(onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row {
                    TextButton(onClick = onCancel) { Text("取消分段编辑") }
                    TextButton(onClick = {
                        focus.clearFocus(force = true)
                        if (session.owner == state.editingMessage && session.applyTo(state.textContent)) { state.segmentedDraft = null; onDone() }
                        else conflict = true
                    }, modifier = Modifier.testTag("segmented_apply")) { Text("应用并返回") }
                }
                Text("第 ${session.index + 1} / ${session.pages.size} 段 · 修改应用后合并到全文")
                if (conflict) Text("原文已在其他位置变化，请取消后重新打开。", color = MaterialTheme.colorScheme.error)
                Row {
                    TextButton(enabled = session.index > 0, onClick = { move { session.index-- } }) { Text("上一段") }
                    TextButton(enabled = session.index < session.pages.lastIndex, onClick = { move { session.index++ } }) { Text("下一段") }
                    TextButton(onClick = { move { session.insertPage() } }) { Text("插入空白段") }
                }
                TextField(state = session.pages[session.index], modifier = Modifier.weight(1f).fillMaxWidth()
                    .testTag("segmented_input").focusRequester(requester))
                LaunchedEffect(session.index) { requester.requestFocus() }
            }
        }
    }
}
