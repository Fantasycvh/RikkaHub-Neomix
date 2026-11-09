package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import app.androidrik.rikkahub.ui.components.richtext.SelectableContent
import app.androidrik.rikkahub.ui.components.richtext.SharedSelectableContent
import me.rerere.bettertext.text.BetterText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import app.androidrik.rikkahub.R

@Composable
fun ChatMessageCopySheet(
    message: UIMessage,
    onDismissRequest: () -> Unit
) {
    val copyMessage = rememberMessageCopy(message)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }

                Text(
                    text = stringResource(R.string.select_and_copy),
                    style = MaterialTheme.typography.headlineSmall,
                )

                TextButton(
                    onClick = {
                        copyMessage()
                        onDismissRequest()
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_all))
                }
            }

            // Content
            val textParts =
                message.parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotBlank() }

            if (textParts.isEmpty()) {
                // No text content available
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_text_content_to_copy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val source = remember(message) { message.toText() }
                val chunks = remember(source) { plainTextChunks(source) }
                val group = remember(message.id) { Any() }
                SharedSelectableContent(documentText = { if (it == group) source else null },
                    modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().testTag("message_copy_list")) {
                        items(chunks, key = { "${message.id}:${it.start}" }) { chunk ->
                            SelectableContent(group = group, separatorBefore = "") {
                                BetterText(source.substring(chunk.start, chunk.end),
                                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
                            }
                        }
                    }
                }
            }
        }
    }
}
