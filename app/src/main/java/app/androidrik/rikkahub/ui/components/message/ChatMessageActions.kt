package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toQuotedMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.QuoteUp
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalTTSState
import app.androidrik.rikkahub.utils.copyMessageToClipboard
import app.androidrik.rikkahub.utils.extractQuotedContentAsText
import app.androidrik.rikkahub.utils.removeBracketedContent
import app.androidrik.rikkahub.utils.toLocalString
import app.androidrik.rikkahub.utils.toMessageTimeString
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val actionIconColor = MaterialTheme.colorScheme.onSurfaceVariant

        Icon(
            imageVector = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            modifier = Modifier
                .clip(CircleShape)
                .clickable { context.copyMessageToClipboard(message) }
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        Icon(
            imageVector = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            modifier = Modifier
                .clip(CircleShape)
                // scope 选择与（仅 scope B 的）确认由页面层负责——见 RegenerateScopeDialog
                .clickable { onRegenerate() }
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        if (message.role == MessageRole.ASSISTANT) {
            val tts = LocalTTSState.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            Icon(
                imageVector = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isAvailable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                val text = message.toText()
                                var textToSpeak = text
                                if (settings.displaySetting.ttsOnlyReadQuoted) {
                                    textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                                }
                                if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                    textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                                }
                                tts.speak(textToSpeak)
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = if (isAvailable) actionIconColor else actionIconColor.copy(alpha = 0.38f)
            )

            // Translation button
            if (onTranslate != null) {
                Icon(
                    imageVector = HugeIcons.Translate,
                    contentDescription = stringResource(R.string.translate),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = LocalIndication.current,
                            onClick = {
                                showTranslateDialog = true
                            }
                        )
                        .padding(8.dp)
                        .size(16.dp),
                    tint = actionIconColor
                )
            }
        }

        Icon(
            imageVector = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = {
                        onOpenActionSheet()
                    }
                )
                .padding(8.dp)
                .size(16.dp),
            tint = actionIconColor
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )

        if (settings.displaySetting.showDateTimeInMessage) {
            Text(
                text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    /** 多文本段消息（工具轮续跑产生「文本-工具卡-文本」）按段编辑；null 时只提供整体编辑入口。 */
    onEditSegment: ((UIMessage, Int) -> Unit)? = null,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    onQuote: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Select and Copy
            Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.TextSelection,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // WebView Preview (only show if message has text content)
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }

            if (hasTextContent) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onWebViewPreview()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.WebDesign01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.render_with_webview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // #1906 引用：只对可引用（有文本）的消息显示
            val quotable = remember(message) { message.toQuotedMessage() != null }
            if (quotable) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onQuote()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.QuoteUp,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_quote),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Edit。多文本段消息（工具轮续跑形状「文本-工具卡-文本」）按段列出编辑入口——
            // 否则编辑只能落到最后一段（更早的文本永远改不了，且用户会以为编辑的是前面那段）。
            val textSegments = remember(message) {
                message.parts.withIndex()
                    .filter { (it.value as? UIMessagePart.Text)?.text?.isNotBlank() == true }
            }
            if (onEditSegment != null && textSegments.size > 1) {
                textSegments.forEachIndexed { ordinal, (index, part) ->
                    val fullText = (part as UIMessagePart.Text).text.trim()
                    val snippet = if (fullText.length > 24) fullText.take(24) + "…" else fullText
                    Card(
                        onClick = {
                            onDismissRequest()
                            onEditSegment(message, index)
                        },
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = null,
                                modifier = Modifier.padding(4.dp)
                            )
                            Text(
                                text = stringResource(R.string.chat_message_edit_segment, ordinal + 1, snippet),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            } else {
                Card(
                    onClick = {
                        onDismissRequest()
                        onEdit()
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.Edit01,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.edit),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Share
            Card(
                onClick = {
                    onDismissRequest()
                    onShare()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Share04,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.share),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Create a Fork
            Card(
                onClick = {
                    onDismissRequest()
                    onFork()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.GitFork,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_fork),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (onToggleFavorite != null) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onToggleFavorite()
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = HugeIcons.FavouriteCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(
                                if (isFavorite) R.string.chat_message_remove_favorite
                                else R.string.chat_message_add_favorite
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Delete
            Card(
                onClick = {
                    onDismissRequest()
                    onDelete()
                },
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.delete),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}
