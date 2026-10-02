package app.androidrik.rikkahub.ui.components.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.files.SkillCategoryFilter
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.rikkahub.data.files.filterSkills
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.ai.ExtensionEmptyState
import app.androidrik.rikkahub.ui.components.ai.ExtensionPickerNavigation
import app.androidrik.rikkahub.ui.components.ai.ExtensionTab
import app.androidrik.rikkahub.ui.components.ai.PresetPickerContent
import app.androidrik.rikkahub.ui.components.ai.QuickMessagesContent
import app.androidrik.rikkahub.ui.components.ai.SkillCategoryFilterSaver
import app.androidrik.rikkahub.ui.components.ai.SkillFilterBar
import app.androidrik.rikkahub.ui.components.ai.SkillsContent
import app.androidrik.rikkahub.ui.components.ai.WorldbookPickerContent
import org.koin.compose.koinInject

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
                            // 预设级绑定是**助手专属**（不随会话覆盖）：会话作用域传 null 隐藏开关
                            selectedPresetIds = if (useConversationInjections) null else assistant.promptPresetIds,
                            onTogglePreset = if (useConversationInjections) null else { id, checked ->
                                val newIds = if (checked) {
                                    assistant.promptPresetIds + id
                                } else {
                                    assistant.promptPresetIds - id
                                }
                                onUpdate(assistant.copy(promptPresetIds = newIds))
                            },
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
                        // #1901：技能多时列表难找，这里加与管理页同源的搜索 + 单分类
                        var skillQuery by rememberSaveable { mutableStateOf("") }
                        var skillCategory by rememberSaveable(stateSaver = SkillCategoryFilterSaver) {
                            mutableStateOf<SkillCategoryFilter>(SkillCategoryFilter.All)
                        }
                        val filteredSkills = remember(skills, skillQuery, skillCategory) {
                            skills.filterSkills(query = skillQuery, category = skillCategory)
                        }
                        SkillsContent(
                            skills = filteredSkills,
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
                            header = {
                                SkillFilterBar(
                                    skills = skills,
                                    query = skillQuery,
                                    onQueryChange = { skillQuery = it },
                                    category = skillCategory,
                                    onCategoryChange = { skillCategory = it },
                                )
                            },
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
