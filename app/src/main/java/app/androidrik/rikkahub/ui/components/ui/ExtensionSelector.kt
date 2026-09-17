package app.androidrik.rikkahub.ui.components.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.sortedForDisplay
import kotlin.uuid.Uuid
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.ai.ExtensionEmptyState
import app.androidrik.rikkahub.ui.components.ai.ExtensionTab
import app.androidrik.rikkahub.ui.components.ai.ModeInjectionsContent
import app.androidrik.rikkahub.ui.components.ai.QuickMessagesContent
import app.androidrik.rikkahub.ui.components.ai.SkillsContent
import org.koin.compose.koinInject

@Composable
private fun PickerBreadcrumb(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text("/ $title", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PickerManageButton(onManage: () -> Unit, label: String = stringResource(R.string.extension_content_manage)) {
    TextButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun PresetPickerContent(
    presets: List<PromptPreset>,
    navigation: ExtensionPickerNavigation,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    onManage: () -> Unit,
) {
    when (val page = navigation.presets(presets)) {
        is ExtensionPickerPage.Containers -> LazyColumn {
            items(page.items, key = { it.id }) { preset ->
                ListItem(
                    headlineContent = {
                        Text(preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) })
                    },
                    supportingContent = {
                        Column {
                            if (preset.description.isNotBlank()) Text(preset.description)
                            Text(stringResource(R.string.prompt_page_entries_count_format, preset.entries.size))
                            Text("${preset.entries.count { it.id in selectedIds }}/${preset.entries.size} 已选")
                            if (!preset.enabled) Text("预设已全局禁用（单独绑定条目仍可生效）")
                        }
                    },
                    trailingContent = { Text("›") },
                    modifier = Modifier.clickable { navigation.openPreset(preset.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { PickerManageButton(onManage) }
        }
        is ExtensionPickerPage.Detail -> Column {
            val preset = page.item
            PickerBreadcrumb(
                preset.name.ifBlank { stringResource(R.string.prompt_page_unnamed_preset) },
                navigation::back,
            )
            if (preset.entries.isEmpty()) {
                ExtensionEmptyState(
                    message = stringResource(R.string.prompt_page_mode_injection_empty),
                    buttonText = stringResource(R.string.extension_content_manage),
                    onAction = onManage,
                )
            } else {
                // These switches edit only the existing binding IDs, never global entry.enabled.
                // No aggregate switch: avoid several callbacks based on the same parent snapshot.
                key(preset.id) {
                    ModeInjectionsContent(
                        modeInjections = preset.entries.sortedForDisplay(),
                        selectedIds = selectedIds,
                        onToggle = onToggle,
                        onManage = onManage,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorldbookPickerContent(
    lorebooks: List<Lorebook>,
    navigation: ExtensionPickerNavigation,
    selectedIds: Set<Uuid>,
    onToggle: (Uuid, Boolean) -> Unit,
    onManage: () -> Unit,
) {
    when (val page = navigation.worldbooks(lorebooks)) {
        is ExtensionPickerPage.Containers -> LazyColumn {
            items(page.items, key = { it.id }) { book ->
                ListItem(
                    headlineContent = {
                        Text(book.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) })
                    },
                    supportingContent = {
                        Column {
                            if (book.description.isNotBlank()) Text(book.description)
                            Text(stringResource(R.string.prompt_page_entries_count_format, book.entries.size))
                            if (!book.enabled) Text("世界书已全局禁用")
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = book.id in selectedIds,
                                onCheckedChange = { onToggle(book.id, it) },
                            )
                            Text("›", modifier = Modifier.padding(start = 8.dp))
                        }
                    },
                    modifier = Modifier.clickable { navigation.openWorldbook(book.id) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item { PickerManageButton(onManage) }
        }
        is ExtensionPickerPage.Detail -> Column {
            val book = page.item
            PickerBreadcrumb(
                book.name.ifBlank { stringResource(R.string.extension_content_unnamed_lorebook) },
                navigation::back,
            )
            Text(
                "世界书按整本选用。下方显示条目的全局启用状态；如需修改，请进入管理页，修改会影响所有使用它的对话。",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            PickerManageButton(onManage, "管理世界书条目（全局）")
            key(book.id) {
                LazyColumn {
                    if (book.entries.isEmpty()) {
                        item { ExtensionEmptyState(stringResource(R.string.prompt_page_entries_count_format, 0)) }
                    }
                    items(book.entries.sortedByDescending { it.priority }, key = { it.id }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(entry.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                            },
                            supportingContent = {
                                Column {
                                    Text(if (entry.enabled) "全局已启用" else "全局已禁用")
                                    Text(stringResource(R.string.prompt_page_priority_format, entry.priority))
                                    Text("${entry.position} · ${entry.role}")
                                    if (entry.constantActive) Text("常驻条目")
                                    if (entry.keywords.isNotEmpty()) {
                                        Text(stringResource(R.string.prompt_page_keywords_format, entry.keywords.joinToString()))
                                    }
                                    if (entry.secondaryKeywords.isNotEmpty()) {
                                        Text(stringResource(R.string.prompt_page_secondary_keywords_format, entry.secondaryKeywords.joinToString()))
                                    }
                                    if (entry.content.isNotBlank()) Text(entry.content, maxLines = 3)
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

/** Sheet-owned navigation lets Back return to containers without dismissing the sheet. */
class ExtensionPickerNavigation {
    internal var presetId by mutableStateOf<Uuid?>(null)
        private set
    internal var worldbookId by mutableStateOf<Uuid?>(null)
        private set

    val isDetail: Boolean get() = presetId != null || worldbookId != null

    internal fun openPreset(id: Uuid) { back(); presetId = id }
    internal fun openWorldbook(id: Uuid) { back(); worldbookId = id }
    fun back() { presetId = null; worldbookId = null }

    internal fun presets(items: List<PromptPreset>) = pickerPage(items, presetId) { it.id }
    internal fun worldbooks(items: List<Lorebook>) = pickerPage(items, worldbookId) { it.id }
}

/** Root data is containers, never the flattened Settings.modeInjections view. */
internal sealed interface ExtensionPickerPage<out T> {
    data class Containers<T>(val items: List<T>) : ExtensionPickerPage<T>
    data class Detail<T>(val item: T) : ExtensionPickerPage<T>
}

private fun <T> pickerPage(items: List<T>, id: Uuid?, itemId: (T) -> Uuid): ExtensionPickerPage<T> =
    items.find { itemId(it) == id }?.let { ExtensionPickerPage.Detail(it) }
        ?: ExtensionPickerPage.Containers(items)

@Composable
fun ExtensionSelector(
    modifier: Modifier = Modifier,
    assistant: Assistant,
    settings: Settings,
    onUpdate: (Assistant) -> Unit,
    conversation: Conversation? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    onNavigateToQuickMessages: () -> Unit = {},
    onNavigateToPrompts: () -> Unit = {},
    onNavigateToSkills: () -> Unit = {},
    onNavigateToWorldbooks: () -> Unit = onNavigateToPrompts,
    navigation: ExtensionPickerNavigation = remember { ExtensionPickerNavigation() },
) {
    val skillManager: SkillManager = koinInject()
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        // 打开扩展面板时清理运行时被删除的技能（残留的 enabledSkills 引用），
        // prune 顺带返回现存技能列表，避免重复读盘
        skills = skillManager.pruneOrphanedEnabledSkills()
    }

    val useConversationInjections =
        assistant.allowConversationPromptInjection && conversation != null && onUpdateConversation != null
    val selectedModeInjectionIds = if (useConversationInjections) {
        conversation.modeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val selectedLorebookIds = if (useConversationInjections) {
        conversation.lorebookIds
    } else {
        assistant.lorebookIds
    }

    // Shared tab visibility keeps pager indices aligned.
    val tabs = ExtensionTab.visible
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) { navigation.back() }
    LaunchedEffect(settings.promptPresets, settings.lorebooks) {
        if (navigation.presetId != null && settings.promptPresets.none { it.id == navigation.presetId } ||
            navigation.worldbookId != null && settings.lorebooks.none { it.id == navigation.worldbookId }
        ) navigation.back()
    }
    BackHandler(enabled = navigation.isDetail) { navigation.back() }

    Column(
        modifier = modifier
    ) {
        SecondaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 4.dp,
        ) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) { page ->
            when (tabs[page]) {
                ExtensionTab.QUICK_MESSAGES -> {
                    if (settings.quickMessages.isNotEmpty()) {
                        QuickMessagesContent(
                            quickMessages = settings.quickMessages,
                            selectedIds = assistant.quickMessageIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    assistant.quickMessageIds + id
                                } else {
                                    assistant.quickMessageIds - id
                                }
                                onUpdate(assistant.copy(quickMessageIds = newIds))
                            },
                            onManage = onNavigateToQuickMessages,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_quick_messages_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToQuickMessages,
                        )
                    }
                }

                ExtensionTab.MODE_INJECTIONS -> {
                    if (settings.promptPresets.isNotEmpty()) {
                        PresetPickerContent(
                            presets = settings.promptPresets,
                            navigation = navigation,
                            selectedIds = selectedModeInjectionIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedModeInjectionIds + id
                                } else {
                                    selectedModeInjectionIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(modeInjectionIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(modeInjectionIds = newIds))
                                }
                            },
                            onManage = onNavigateToPrompts,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_mode_injections_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToPrompts,
                        )
                    }
                }

                ExtensionTab.LOREBOOKS -> {
                    if (settings.lorebooks.isNotEmpty()) {
                        WorldbookPickerContent(
                            lorebooks = settings.lorebooks,
                            navigation = navigation,
                            selectedIds = selectedLorebookIds,
                            onToggle = { id, checked ->
                                val newIds = if (checked) {
                                    selectedLorebookIds + id
                                } else {
                                    selectedLorebookIds - id
                                }
                                if (useConversationInjections) {
                                    onUpdateConversation(conversation.copy(lorebookIds = newIds))
                                } else {
                                    onUpdate(assistant.copy(lorebookIds = newIds))
                                }
                            },
                            onManage = onNavigateToWorldbooks,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_lorebooks_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_extensions),
                            onAction = onNavigateToWorldbooks,
                        )
                    }
                }

                ExtensionTab.SKILLS -> {
                    if (skills.isNotEmpty()) {
                        SkillsContent(
                            skills = skills,
                            enabledSkills = assistant.enabledSkills,
                            onToggle = { name, checked ->
                                val newSkills = if (checked) {
                                    assistant.enabledSkills + name
                                } else {
                                    assistant.enabledSkills - name
                                }
                                onUpdate(assistant.copy(enabledSkills = newSkills))
                            },
                            onManage = onNavigateToSkills,
                        )
                    } else {
                        ExtensionEmptyState(
                            message = stringResource(R.string.extension_selector_skills_empty),
                            buttonText = stringResource(R.string.extension_selector_go_to_skills),
                            onAction = onNavigateToSkills,
                        )
                    }
                }
            }
        }
    }
}
