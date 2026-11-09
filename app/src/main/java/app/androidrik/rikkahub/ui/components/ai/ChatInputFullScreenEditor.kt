package app.androidrik.rikkahub.ui.components.ai

// 批次 7 拆出：FullScreenEditor 自 ChatInput.kt 拆出（同包，private→internal；
// 与主输入共享 state.textContent，纯搬）

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.components.ui.AppTextField as TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.hooks.ChatInputState

@Composable
internal fun FullScreenEditor(
    state: ChatInputState,
    scrollState: ScrollState = rememberScrollState(),
    onDone: () -> Unit,
) {
    var segmented by remember(state) { mutableStateOf(state.segmentedDraft != null) }
    val offerSegmented by remember(state) { derivedStateOf { state.textContent.text.length >= 24_000 } }
    if (segmented) {
        SegmentedTextEditor(state, onDone = onDone, onCancel = { state.segmentedDraft = null; segmented = false })
        return
    }
    val focusRequester = remember { FocusRequester() }
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = appSkinRoundedShape(16f).copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        if (offerSegmented) TextButton(onClick = { segmented = true },
                            modifier = Modifier.testTag("chat_segmented_open")) { Text("分段编辑") }
                        TextButton(
                            modifier = Modifier.testTag("chat_fullscreen_done"),
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = state.textContent,
                        scrollState = scrollState,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize()
                            .testTag("chat_fullscreen_input")
                            .focusRequester(focusRequester),
                        shape = appSkinRoundedShape(32f),
                        placeholder = {
                            Text(stringResource(R.string.chat_input_placeholder))
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }
                }
            }
        }
    }
}
