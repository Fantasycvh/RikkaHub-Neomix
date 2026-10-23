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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
                onPromptChange = { vm.updateSettings(settings.copy(translatePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(translatePrompt = DEFAULT_TRANSLATION_PROMPT)) },
                reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                onUpdateReasoningLevel = { vm.updateSettings(settings.copy(translateThinkingBudget = it.budgetTokens)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_title),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.titlePrompt,
                onPromptChange = { vm.updateSettings(settings.copy(titlePrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(titlePrompt = DEFAULT_TITLE_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_suggestion),
                promptDescription = stringResource(R.string.setting_model_page_suggestion_prompt_vars),
                promptValue = settings.suggestionPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(suggestionPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(suggestionPrompt = DEFAULT_SUGGESTION_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_ocr),
                promptDescription = stringResource(R.string.setting_model_page_ocr_prompt_vars),
                promptValue = settings.ocrPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(ocrPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(ocrPrompt = DEFAULT_OCR_PROMPT)) },
            )
        }
        item {
            PromptSettingItem(
                title = "做梦（记忆整理）提示词",
                promptDescription = "用于自动和手动记忆整理。运行时仍会追加当前的审批与记忆类型调整说明。",
                promptValue = settings.memoryConsolidationPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(memoryConsolidationPrompt = it)) },
                onResetPrompt = {
                    vm.updateSettings(settings.copy(memoryConsolidationPrompt = MEMORY_CONSOLIDATION_SYSTEM_PROMPT))
                },
            )
        }
        item {
            PromptSettingItem(
                title = stringResource(R.string.setting_model_page_prompt_compress),
                promptDescription = stringResource(R.string.setting_model_page_compress_prompt_vars),
                promptValue = settings.compressPrompt,
                onPromptChange = { vm.updateSettings(settings.copy(compressPrompt = it)) },
                onResetPrompt = { vm.updateSettings(settings.copy(compressPrompt = DEFAULT_COMPRESS_PROMPT)) },
            )
        }
    }
}

@Composable
private fun PromptSettingItem(
    title: String,
    promptDescription: String,
    promptValue: String,
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
                    value = promptValue,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 15,
                )
                TextButton(onClick = onResetPrompt) {
                    Text(stringResource(R.string.setting_model_page_reset_to_default))
                }
            }
        }
    }
}
