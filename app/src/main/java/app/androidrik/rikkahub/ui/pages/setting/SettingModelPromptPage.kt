package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_SYSTEM_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.ai.ReasoningButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.utils.plus

@Composable
internal fun PromptSettingsPage(settings: Settings, vm: SettingVM, contentPadding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_translation),
                promptDescription = stringResource(R.string.setting_model_page_translate_prompt_vars),
                promptValue = settings.translatePrompt,
                defaultPrompt = DEFAULT_TRANSLATION_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(translatePrompt = text) } },
                onResetPrompt = { vm.updateSettings { s -> s.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT) } },
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_title),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.titlePrompt,
                defaultPrompt = DEFAULT_TITLE_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(titlePrompt = text) } },
                onResetPrompt = { vm.updateSettings { s -> s.copy(titlePrompt = DEFAULT_TITLE_PROMPT) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_suggestion),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.suggestionPrompt,
                defaultPrompt = DEFAULT_SUGGESTION_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(suggestionPrompt = text) } },
                onResetPrompt = { vm.updateSettings { s -> s.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_ocr),
                promptDescription = stringResource(R.string.setting_model_page_ocr_prompt_vars),
                promptValue = settings.ocrPrompt,
                defaultPrompt = DEFAULT_OCR_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(ocrPrompt = text) } },
                onResetPrompt = { vm.updateSettings { s -> s.copy(ocrPrompt = DEFAULT_OCR_PROMPT) } },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_memory_consolidation),
                promptDescription = stringResource(R.string.setting_model_page_prompt_memory_consolidation_desc),
                promptValue = settings.memoryConsolidationPrompt,
                defaultPrompt = MEMORY_CONSOLIDATION_SYSTEM_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(memoryConsolidationPrompt = text) } },
                onResetPrompt = {
                    vm.updateSettings { s -> s.copy(memoryConsolidationPrompt = MEMORY_CONSOLIDATION_SYSTEM_PROMPT) }
                },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_compress),
                promptDescription = stringResource(R.string.setting_model_page_compress_prompt_vars),
                promptValue = settings.compressPrompt,
                defaultPrompt = DEFAULT_COMPRESS_PROMPT,
                onPromptChange = { text -> vm.updateSettings { s -> s.copy(compressPrompt = text) } },
                onResetPrompt = { vm.updateSettings { s -> s.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT) } },
            )
        }
    }
}

@Composable
private fun PromptSettingItem(
    title: String,
    promptDescription: String,
    promptValue: String,
    defaultPrompt: String,
    onPromptChange: (String) -> Unit,
    onResetPrompt: () -> Unit,
    reasoningLevel: ReasoningLevel? = null,
    onUpdateReasoningLevel: ((ReasoningLevel) -> Unit)? = null,
) {
    var showEditor by remember { mutableStateOf(false) }

    CardGroup(title = { Text(title) }) {
        item(
            onClick = { showEditor = true },
            headlineContent = { Text(stringResource(R.string.setting_model_page_prompt)) },
            trailingContent = {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )
        if (reasoningLevel != null && onUpdateReasoningLevel != null) {
            item(
                headlineContent = { Text(stringResource(R.string.assistant_page_thinking_budget)) },
                trailingContent = {
                    ReasoningButton(
                        reasoningLevel = reasoningLevel,
                        onUpdateReasoningLevel = onUpdateReasoningLevel,
                    )
                },
            )
        }
    }

    if (showEditor) {
        ModalBottomSheet(
            onDismissRequest = { showEditor = false },
        ) {
            // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条；范本 1808d418f）。
            // 旧写法 `value = promptValue` 是 settingsFlow 异步投影：每键整份 Settings 落盘后旧值
            // 回灌活跃编辑框（真机 120ms/字符注入会丢字/光标跳）。编辑态只在打开 sheet 时按当前
            // prompt 初始化，切换 prompt（关闭再打开）即换值；持久态回声不回灌。
            val promptState = rememberPersistedTextFieldState(
                externalValue = promptValue,
                onCommit = onPromptChange,
            )
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = promptDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    state = promptState,
                    modifier = Modifier.fillMaxWidth(),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 15),
                )
                TextButton(onClick = {
                    // 重置是用户显式动作：先同步本地权威态（去抖窗口里待提交的旧草稿随之作废），
                    // 再写持久层；否则 250ms 内的旧草稿会在重置之后落盘把它覆盖回去。
                    if (promptState.text.toString() != defaultPrompt) {
                        promptState.setTextAndPlaceCursorAtEnd(defaultPrompt)
                    }
                    onResetPrompt()
                }) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}
