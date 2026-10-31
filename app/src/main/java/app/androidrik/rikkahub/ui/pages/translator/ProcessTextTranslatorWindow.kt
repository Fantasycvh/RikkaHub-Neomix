package app.androidrik.rikkahub.ui.pages.translator

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.LanguageCircle
import org.koin.androidx.compose.koinViewModel
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 跨应用划词翻译弹窗（ACTION_PROCESS_TEXT 入口，见 ProcessTextTranslatorActivity）。
 *
 * 与完整翻译页（[TranslatorPage]）共用 [TranslatorVM]，但按「弹窗即用即走」裁剪：
 * 进窗自动翻一次、结果区可再选中复制、模型/目标语言改动即时重翻；翻译历史照常留档。
 * 方案来源与取舍记录：docs/references/other projects/miffan.md（Miffan 同款 PROCESS_TEXT 路线）。
 */
@Composable
fun ProcessTextTranslatorWindow(
    selectedText: String,
    onClose: () -> Unit,
    vm: TranslatorVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 自动开翻的竞态双防护（借自 Miffan，但 init 语义相反）：
    // 本仓 Settings.dummy() 是 init=TRUE，磁盘读出后变 false——所以等 !settings.init
    // 才是「真正从磁盘读出」，等 settings.init 会拿 dummy 空 providers 发车报
    // 「Translation model not found」（真机踩过）。
    // translationStarted 防旋转/重建后重复触发；输入写入的幂等守卫在 VM
    // （initializeInputText），不用 rememberSaveable——进程死亡重建后它会误报
    // 「已初始化」而 VM 是新的
    var translationStarted by rememberSaveable(selectedText) { mutableStateOf(false) }

    LaunchedEffect(selectedText, settings.init) {
        vm.initializeInputText(selectedText)
        if (!settings.init && !translationStarted) {
            translationStarted = true
            vm.translate()
        }
    }

    // 错误不弹 toast（弹窗里看得见），显示在结果区
    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            errorMessage = error.message ?: error::class.java.simpleName
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.LanguageCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.translator_page_title),
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    PopupModelButton(
                        settings = settings,
                        onModelSelected = { model ->
                            errorMessage = null
                            // settingsStore 写入是异步的，直接带上改完的值重翻
                            vm.updateSettings(settings.copy(translateModeId = model.id))
                            if (translatedText.isNotBlank() || translating) {
                                vm.translate(settings.copy(translateModeId = model.id))
                            }
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = vm::updateInputText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 200.dp),
                    placeholder = { Text(stringResource(R.string.translator_page_input_placeholder)) },
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                )

                if (translating) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    HorizontalDivider()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 72.dp, max = 280.dp),
                ) {
                    when {
                        errorMessage != null -> {
                            Text(
                                text = errorMessage.orEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        translatedText.isBlank() -> {
                            Text(
                                text = stringResource(R.string.translator_page_result_placeholder),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            SelectionContainer {
                                Text(
                                    text = translatedText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TargetLanguageButton(
                        targetLanguage = targetLanguage,
                        onLanguageSelected = { language ->
                            errorMessage = null
                            vm.updateTargetLanguage(language)
                            vm.translate()
                        },
                    )

                    Spacer(Modifier.weight(1f))

                    if (translatedText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(null, translatedText)
                                        )
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = stringResource(R.string.copy),
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            if (translating) {
                                vm.cancelTranslation()
                            } else {
                                errorMessage = null
                                vm.translate()
                            }
                        },
                    ) {
                        Text(
                            if (translating) {
                                stringResource(R.string.translator_page_cancel)
                            } else {
                                stringResource(R.string.translator_page_translate)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 弹窗专用的轻量模型选择器：不用共享的 [app.androidrik.rikkahub.ui.components.ai.ModelSelector]——
 * 它打开的模型列表里 ModelItem 长按跳供应商设置页，强依赖 LocalNavController，
 * 弹窗不在导航栈里会直接崩（真机踩过）。
 */
@Composable
private fun PopupModelButton(
    settings: Settings,
    onModelSelected: (Model) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }

    val modelOptions = remember(settings.providers) {
        settings.providers
            .filter(ProviderSetting::enabled)
            .flatMap { provider ->
                provider.models
                    .filter { it.enabled && it.type == ModelType.CHAT }
                    .map { model -> provider to model }
            }
    }
    val currentModel = remember(modelOptions, settings.translateModeId) {
        modelOptions.find { it.second.id == settings.translateModeId }?.second
    }

    TextButton(onClick = { pickerVisible = true }) {
        currentModel?.let {
            AutoAIIcon(
                name = it.modelId,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(20.dp),
            )
        }
        Text(
            text = currentModel?.displayName
                ?: stringResource(R.string.model_list_select_model),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    if (pickerVisible) {
        ModelPickerDialog(
            modelOptions = modelOptions,
            selectedModelId = currentModel?.id,
            onDismiss = { pickerVisible = false },
            onModelSelected = { model ->
                pickerVisible = false
                onModelSelected(model)
            },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    modelOptions: List<Pair<ProviderSetting, Model>>,
    selectedModelId: Uuid?,
    onDismiss: () -> Unit,
    onModelSelected: (Model) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredOptions = remember(modelOptions, searchQuery) {
        if (searchQuery.isBlank()) {
            modelOptions
        } else {
            modelOptions.filter { (provider, model) ->
                provider.name.contains(searchQuery, ignoreCase = true) ||
                    model.displayName.contains(searchQuery, ignoreCase = true) ||
                    model.modelId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_list_select_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.model_list_search_placeholder)) },
                    singleLine = true,
                )
                if (filteredOptions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_list_no_providers),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = filteredOptions,
                            key = { (provider, model) -> "${provider.id}:${model.id}" },
                        ) { (provider, model) ->
                            ListItem(
                                headlineContent = {
                                    Text(model.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    Text(
                                        text = "${provider.name} · ${model.modelId}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    AutoAIIcon(name = model.modelId, modifier = Modifier.size(32.dp))
                                },
                                trailingContent = {
                                    RadioButton(
                                        selected = model.id == selectedModelId,
                                        onClick = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onModelSelected(model)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun TargetLanguageButton(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = translationLanguageName(targetLanguage),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Locales.forEach { language ->
                DropdownMenuItem(
                    text = { Text(translationLanguageName(language)) },
                    onClick = {
                        expanded = false
                        onLanguageSelected(language)
                    },
                )
            }
        }
    }
}
