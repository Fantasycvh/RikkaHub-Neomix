package app.androidrik.rikkahub.ui.pages.extensions

// 批次 6 拆出：自 PromptPage.kt 机械搬移（函数体零改动，private → internal）。
// 存放 Preset / Lorebook 两侧共用的高亮定位、关键词编辑器与展示文案辅助。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.SelectiveLogic
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01

/**
 * Tier 4：从拼装预览跳进条目编辑时，滚动到目标条目并**短暂高亮**。
 *
 * 为什么需要高亮而不只是滚动：详情列表可能几十条，滚到某一行后用户仍需自己辨认是哪条；
 * 高亮直接给出「就是从预览点过来的这条」。高亮在片刻后自动褪去（不改变任何持久状态）。
 *
 * 只在**首次**进入时定位（`rememberSaveable` 记录已消费的 id）：否则用户手动往下翻时
 * 任何重组都会把列表拉回目标条目。
 *
 * @param entries 展示顺序的条目列表（与 UI 用同一份，避免按 id 再查一次导致下标错位）
 * @return 需要高亮的条目 id（`null` = 不高亮）
 */
@Composable
internal fun <T : PromptInjection> rememberHighlightedEntry(
    highlightEntryId: String?,
    entries: List<T>,
    onScrollTo: suspend (Int) -> Unit,
): String? {
    var pendingId by rememberSaveable(highlightEntryId) { mutableStateOf(highlightEntryId) }
    var activeId by remember(highlightEntryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingId, entries) {
        val target = pendingId ?: return@LaunchedEffect
        if (entries.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst { it.id.toString() == target }
        if (index < 0) {
            pendingId = null
            return@LaunchedEffect
        }
        onScrollTo(index)
        activeId = target
        pendingId = null
        delay(HIGHLIGHT_DURATION_MS)
        activeId = null
    }
    return activeId
}

/** 高亮持续时间：够看清是哪一条，又不至于一直抢视觉焦点。 */
internal const val HIGHLIGHT_DURATION_MS = 1600L

@Composable
internal fun getPositionLabel(position: InjectionPosition): String = when (position) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_before_system)
    InjectionPosition.AFTER_SYSTEM_PROMPT -> stringResource(R.string.prompt_page_position_after_system)
    InjectionPosition.TOP_OF_CHAT -> stringResource(R.string.prompt_page_position_top_of_chat)
    InjectionPosition.BOTTOM_OF_CHAT -> stringResource(R.string.prompt_page_position_bottom_of_chat)
    InjectionPosition.AT_DEPTH -> stringResource(R.string.prompt_page_position_at_depth)
    InjectionPosition.END_OF_CHAT -> stringResource(R.string.prompt_page_position_end_of_chat)
}

@Composable
internal fun getRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.prompt_page_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.prompt_page_role_assistant)
    MessageRole.SYSTEM -> stringResource(R.string.prompt_page_role_system)
    else -> role.name
}

internal fun InjectionPosition.usesStandaloneMessage(): Boolean = when (this) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT,
    InjectionPosition.AFTER_SYSTEM_PROMPT -> false

    InjectionPosition.TOP_OF_CHAT,
    InjectionPosition.BOTTOM_OF_CHAT,
    InjectionPosition.AT_DEPTH,
    InjectionPosition.END_OF_CHAT -> true
}

/** 次要键逻辑的展示文案（对齐 ST `world_info_logic` 的四种语义）。 */
@Composable
internal fun SelectiveLogic.labelRes(): Int = when (this) {
    SelectiveLogic.AND_ANY -> R.string.prompt_page_secondary_logic_and_any
    SelectiveLogic.NOT_ALL -> R.string.prompt_page_secondary_logic_not_all
    SelectiveLogic.NOT_ANY -> R.string.prompt_page_secondary_logic_not_any
    SelectiveLogic.AND_ALL -> R.string.prompt_page_secondary_logic_and_all
}

/**
 * 关键词列表编辑器（主键与次要键共用，避免两处 UI 漂移）。
 *
 * @param title 区块标题（用于区分「关键词」与「次要关键词」）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeywordEditor(
    title: String,
    keywords: List<String>,
    onKeywordsChange: (List<String>) -> Unit,
) {
    var newKeyword by remember { mutableStateOf("") }

    Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keywords.forEach { keyword ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(keyword) },
                trailingIcon = {
                    IconButton(
                        onClick = { onKeywordsChange(keywords - keyword) },
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                    }
                }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = newKeyword,
            onValueChange = { newKeyword = it },
            label = { Text(stringResource(R.string.prompt_page_new_keyword)) },
            modifier = Modifier.weight(1f),
            singleLine = true
        )
        IconButton(
            onClick = {
                if (newKeyword.isNotBlank()) {
                    onKeywordsChange(keywords + newKeyword.trim())
                    newKeyword = ""
                }
            }
        ) {
            Icon(HugeIcons.Add01, stringResource(R.string.prompt_page_add))
        }
    }
}
