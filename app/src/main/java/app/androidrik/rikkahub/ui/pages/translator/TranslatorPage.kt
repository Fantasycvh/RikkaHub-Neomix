package app.androidrik.rikkahub.ui.pages.translator

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.ModelType
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import app.androidrik.rikkahub.ui.components.ai.ModelSelector
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.utils.getText
import app.androidrik.rikkahub.utils.toLocalDateTime
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.LanguageCircle
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.util.Locale

@Composable
fun TranslatorPage(vm: TranslatorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // 处理错误
    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(error.message ?: "错误", type = ToastType.Error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.translator_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ModelSelector(
                        modelId = settings.translateModeId,
                        onSelect = {
                            vm.updateSettings(settings.copy(translateModeId = it.id))
                        },
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onlyIcon = true,
                    )
                }
            )
        },
        bottomBar = {
            BottomBar(
                translating = translating,
                onTranslate = {
                    vm.translate()
                },
                onCancelTranslation = {
                    vm.cancelTranslation()
                },
                onLanguageSelected = {
                    vm.updateTargetLanguage(it)
                },
                targetLanguage = targetLanguage
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 输入区域
            Column {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { vm.updateInputText(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.translator_page_input_placeholder)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent
                    ),
                    maxLines = 10,
                    textStyle = MaterialTheme.typography.headlineSmall,
                )

                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            clipboard.getClipEntry()?.clipData?.getText()?.let {
                                vm.updateInputText(it)
                            }
                        }
                    }
                ) {
                    Icon(HugeIcons.Clipboard, null)
                    Text("粘贴文本", modifier = Modifier.padding(start = 4.dp))
                }
            }

            // 翻译进度条
            Crossfade(translating) { isTranslating ->
                if (isTranslating) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .padding(8.dp)
                            .fillMaxWidth()
                    )
                } else {
                    HorizontalDivider()
                }
            }

            // 翻译结果
            SelectionContainer {
                Text(
                    text = translatedText.ifEmpty {
                        stringResource(R.string.translator_page_result_placeholder)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }

            AnimatedVisibility(translatedText.isNotBlank()) {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(
                                    ClipData.newPlainText(
                                        null, translatedText
                                    )
                                )
                            )
                        }
                    }
                ) {
                    Icon(HugeIcons.Clipboard, null)
                    Text("复制翻译结果", modifier = Modifier.padding(start = 4.dp))
                }
            }

            // 翻译历史：成功的翻译自动留档，点一条恢复、左滑删除、可一键清空
            HistorySection(
                history = history,
                onRestore = { entry ->
                    vm.restoreHistory(entry)
                    // 恢复的内容在页面顶部（输入框 + 结果区），滚回去才看得见
                    scope.launch { scrollState.animateScrollTo(0) }
                },
                onDelete = { vm.deleteHistory(it) },
                onClear = { vm.clearHistory() },
            )
        }
    }
}

@Composable
private fun HistorySection(
    history: List<TranslationHistoryEntity>,
    onRestore: (TranslationHistoryEntity) -> Unit,
    onDelete: (TranslationHistoryEntity) -> Unit,
    onClear: () -> Unit,
) {
    // 没有历史时不占版面：翻译页的主体是输入与结果
    if (history.isEmpty()) return

    var showClearDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.translator_page_history),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showClearDialog = true }) {
                Text(stringResource(R.string.translator_page_clear_history))
            }
        }

        history.forEach { entry ->
            // key(entry.id)：删除后复用节点时要重置 swipe 状态，否则新卡片会停在「已滑出」位置
            key(entry.id) {
                TranslationHistoryItem(
                    entry = entry,
                    onClick = { onRestore(entry) },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.translator_page_clear_history_title)) },
            text = { Text(stringResource(R.string.translator_page_clear_history_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClear()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.translator_page_clear_history))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TranslationHistoryItem(
    entry: TranslationHistoryEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        initialValue = SwipeToDismissBoxValue.Settled,
    )

    // 用 settledValue 而不是 currentValue：后者在动画途中就会变，会在手指还没松开时提前触发删除
    LaunchedEffect(dismissState.settledValue) {
        if (dismissState.settledValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = entry.sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.translatedText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = translationLanguageName(Locale.forLanguageTag(entry.targetLanguage)) +
                        " · " + Instant.ofEpochMilli(entry.createAt).toLocalDateTime(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val Locales by lazy {
    listOf(
        Locale.SIMPLIFIED_CHINESE,
        Locale.ENGLISH,
        Locale.TRADITIONAL_CHINESE,
        Locale.JAPANESE,
        Locale.KOREAN,
        Locale.FRENCH,
        Locale.GERMAN,
        Locale.ITALIAN,
        Locale("es", "ES")
    )
}

/** 目标语言/历史记录共用的显示名（历史只存 BCP 47 标签，显示时再本地化） */
@Composable
private fun translationLanguageName(locale: Locale): String {
    return when (locale) {
        Locale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
        Locale.ENGLISH -> stringResource(R.string.language_english)
        Locale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
        Locale.JAPANESE -> stringResource(R.string.language_japanese)
        Locale.KOREAN -> stringResource(R.string.language_korean)
        Locale.FRENCH -> stringResource(R.string.language_french)
        Locale.GERMAN -> stringResource(R.string.language_german)
        Locale.ITALIAN -> stringResource(R.string.language_italian)
        Locale("es", "ES") -> stringResource(R.string.language_spanish)
        else -> locale.getDisplayLanguage(LocalLocale.current.platformLocale)
    }
}

@Composable
private fun LanguageSelector(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = translationLanguageName(targetLanguage),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                    .fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent
                )
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                Locales.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(translationLanguageName(language)) },
                        onClick = {
                            onLanguageSelected(language)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    targetLanguage: Locale,
    onLanguageSelected: (Locale) -> Unit,
    translating: Boolean,
    onTranslate: () -> Unit,
    onCancelTranslation: () -> Unit
) {
    BottomAppBar(
        actions = {
            // 目标语言选择
            LanguageSelector(
                targetLanguage = targetLanguage,
                onLanguageSelected = { onLanguageSelected(it) }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (translating) {
                        onCancelTranslation()
                    } else {
                        onTranslate()
                    }
                },
                containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
            ) {
                if (!translating) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Icon(
                            HugeIcons.LanguageCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.translator_page_translate),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                } else {
                    Text(stringResource(R.string.translator_page_cancel))
                }
            }
        }
    )
}
