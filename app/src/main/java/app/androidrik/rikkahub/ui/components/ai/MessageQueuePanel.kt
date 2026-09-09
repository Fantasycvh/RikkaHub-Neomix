package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.service.QueuedMessage
import kotlin.uuid.Uuid

/**
 * 消息发送队列面板：生成期间发送的消息在此排队，等当前轮次（含待审批工具）结束后自动派发。
 * 支持编辑/撤销；出错后队列暂停，可手动继续。
 */
@Composable
internal fun MessageQueuePanel(
    state: MessageQueueState,
    onRemove: (Uuid) -> Unit,
    onBeginEdit: (Uuid) -> QueuedMessage?,
    onFinishEdit: (Uuid, List<UIMessagePart>?) -> Unit,
    onResume: () -> Unit,
) {
    var editing by remember { mutableStateOf<QueuedMessage?>(null) }
    if (state.messages.isNotEmpty()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.paused) {
                            stringResource(R.string.chat_message_queue_paused, state.messages.size)
                        } else {
                            stringResource(R.string.chat_message_queue_pending, state.messages.size)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
                    if (state.paused) {
                        TextButton(onClick = onResume) {
                            Text(stringResource(R.string.chat_message_queue_resume))
                        }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                    itemsIndexed(
                        state.messages,
                        key = { _, message -> message.id }) { index, message ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val imageLabel = stringResource(R.string.chat_message_queue_image)
                            val fileLabel = stringResource(R.string.chat_message_queue_file)
                            val audioLabel = stringResource(R.string.chat_message_queue_audio)
                            val videoLabel = stringResource(R.string.chat_message_queue_video)
                            Text(
                                text = "${index + 1}. " + message.parts.joinToString(" ") {
                                    when (it) {
                                        is UIMessagePart.Text -> it.text
                                        is UIMessagePart.Image -> imageLabel
                                        is UIMessagePart.Document -> fileLabel
                                        is UIMessagePart.Audio -> audioLabel
                                        is UIMessagePart.Video -> videoLabel
                                        else -> ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { editing = onBeginEdit(message.id) },
                            ) {
                                Text(
                                    if (message.isEditing) {
                                        stringResource(R.string.chat_message_queue_editing)
                                    } else {
                                        stringResource(R.string.chat_message_queue_edit)
                                    }
                                )
                            }
                            TextButton(
                                enabled = !message.isEditing,
                                onClick = { onRemove(message.id) },
                            ) {
                                Text(stringResource(R.string.chat_message_queue_remove))
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { message ->
        var draft by remember(message.id) {
            mutableStateOf(message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
        }
        val finishEdit by rememberUpdatedState(onFinishEdit)
        // Leaving the page or dismissing the dialog must release the queue item.
        DisposableEffect(message.id) {
            onDispose { finishEdit(message.id, null) }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_message_queue_edit_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onFinishEdit(message.id, listOf(UIMessagePart.Text(draft)))
                        editing = null
                    },
                ) { Text(stringResource(R.string.chat_message_queue_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
