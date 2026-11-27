package app.androidrik.rikkahub.ui.components.ai

// 批次 7 拆出：CompletionPopup + ChatInputState.applyCompletion 扩展 + QuickMessageButton
// 自 ChatInput.kt 拆出（同包，private→internal；CompletionPopup 的 item key（label:insertText）原样保留）

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.QuickMessage
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionContext
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionEffect
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionGroup
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionItem
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionList
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.completion.SlashCommandCall
import app.androidrik.rikkahub.ui.components.ai.completion.completionContext
import app.androidrik.rikkahub.ui.components.ai.completion.isSkillQueryCharacter
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Zap

@Stable
internal class CompletionPopupState {
    var list by mutableStateOf<ChatCompletionList?>(null)
    var request by mutableStateOf<ChatCompletionContext?>(null)

    fun dismiss() {
        list = null
        request = null
    }

    fun matchesCurrent(textState: TextFieldState): Boolean {
        val currentRequest = request ?: return false
        return currentRequest == completionContext(textState.text, textState.selection)
    }
}

internal fun mergeCompletionLists(lists: List<ChatCompletionList>): ChatCompletionList? {
    val primary = lists.firstOrNull() ?: return null
    val merged = lists
        .filter { it.replacementRange == primary.replacementRange }
        .flatMap { it.items }
        .distinctBy { it.key }
        .sortedWith(
            compareBy<ChatCompletionItem> { it.group?.ordinal ?: Int.MAX_VALUE }
                .thenByDescending { it.sortScore }
                .thenBy { it.label.length }
                .thenBy { it.label.lowercase() }
        )
    if (merged.isEmpty()) return null
    val capped = if (merged.any { it.group != null }) merged else merged.take(8)
    return primary.copy(items = capped)
}

@Composable
internal fun rememberCompletionPopupState(
    textState: TextFieldState,
    providers: List<ChatCompletionProvider>,
    active: Boolean,
): CompletionPopupState {
    val popupState = remember(textState) { CompletionPopupState() }
    LaunchedEffect(textState, providers, active) {
        if (!active || providers.isEmpty()) {
            popupState.dismiss()
            return@LaunchedEffect
        }
        snapshotFlow {
            completionContext(textState.text, textState.selection)
        }.collectLatest { context ->
            popupState.dismiss()
            if (context == null) return@collectLatest
            val lists = withContext(Dispatchers.Default) {
                providers.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            popupState.request = context
            popupState.list = mergeCompletionLists(lists)
        }
    }
    return popupState
}

@Composable
internal fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    val hasGroups = remember(completionList.items) {
        completionList.items.any { it.group != null }
    }
    val grouped = remember(completionList.items, hasGroups) {
        if (!hasGroups) emptyList()
        else completionList.items.groupBy { it.group }
            .entries
            .sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = appSkinRoundedShape(18f),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            if (!hasGroups) {
                items(
                    items = completionList.items,
                    key = { item -> item.key },
                ) { item ->
                    CompletionPopupItemRow(item = item, onClick = { onItemClick(item) })
                }
            } else {
                grouped.forEach { (group, groupItems) ->
                    if (group != null) {
                        item(key = "group:${group.name}") {
                            Text(
                                text = when (group) {
                                    ChatCompletionGroup.COMMAND -> "命令"
                                    ChatCompletionGroup.SKILL -> "技能"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    items(
                        items = groupItems,
                        key = { item -> item.key },
                    ) { item ->
                        CompletionPopupItemRow(item = item, onClick = { onItemClick(item) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletionPopupItemRow(
    item: ChatCompletionItem,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.badge?.let { badge ->
                        Surface(
                            shape = appSkinRoundedShape(8f),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                item.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal fun TextFieldState.applyTextCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    val replacement = when (val effect = item.effect) {
        is ChatCompletionEffect.InsertText -> effect.text
        else -> item.insertText
    }
    edit {
        replace(start, end, replacement)
        selection = TextRange(start + replacement.length)
    }
}

internal fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
    onExecuteSlashCommand: (SlashCommandCall) -> Unit = {},
) {
    val text = textContent.text
    val textLength = text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    var end = replacementRange.max.coerceIn(start, textLength)
    when (val effect = item.effect) {
        is ChatCompletionEffect.AttachSkill -> {
            if (isEditing()) return
            while (end < textLength && text[end].isSkillQueryCharacter()) end++
            addPendingSkill(effect.skillName)
            textContent.edit {
                replace(start, end, "")
                selection = TextRange(start)
            }
        }

        is ChatCompletionEffect.ExecuteCommand -> {
            if (isEditing()) return
            textContent.edit {
                replace(start, end, "")
                selection = TextRange(start)
            }
            onExecuteSlashCommand(effect.call)
        }

        is ChatCompletionEffect.InsertText -> {
            textContent.edit {
                replace(start, end, effect.text)
                selection = TextRange(start + effect.text.length)
            }
        }
    }
}

@Composable
internal fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            val hapticFeedback = LocalHapticFeedback.current
            quickMessages.forEach { quickMessage ->
                // 短按追加到输入框末尾，长按替换输入框内容
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = {
                                state.appendText(quickMessage.content)
                                expanded = false
                            },
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                state.setMessageText(quickMessage.content)
                                expanded = false
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
