package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.TextButton
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.files.SkillCategoryFilter
import app.androidrik.rikkahub.data.files.filterSkills
import app.androidrik.rikkahub.ui.components.ai.ExtensionEmptyState
import app.androidrik.rikkahub.ui.components.ai.ExtensionPickerNavigation
import app.androidrik.rikkahub.ui.components.ai.ExtensionTab
import app.androidrik.rikkahub.ui.components.ai.PresetPickerContent
import app.androidrik.rikkahub.ui.components.ai.QuickMessagesContent
import app.androidrik.rikkahub.ui.components.ai.SkillCategoryFilterSaver
import app.androidrik.rikkahub.ui.components.ai.SkillFilterBar
import app.androidrik.rikkahub.ui.components.ai.SkillsContent
import app.androidrik.rikkahub.ui.components.ai.WorldbookPickerContent
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantExtensionsPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val tabs = ExtensionTab.visible
    val pagerState = rememberPagerState { tabs.size }
    // 预设/世界书用**两级**选择（容器 → 条目）：与对话内扩展面板同一套组件，
    // 平铺会把「哪个条目属于哪个预设」的结构丢掉。
    val pickerNavigation = remember { ExtensionPickerNavigation() }
    LaunchedEffect(pagerState.currentPage) { pickerNavigation.back() }
    BackHandler(enabled = pickerNavigation.isDetail) { pickerNavigation.back() }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.assistant_extensions_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
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
                    .weight(1f),
            ) { page ->
                when (tabs[page]) {
                    ExtensionTab.QUICK_MESSAGES -> {
                        if (settings.quickMessages.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_quick_messages),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.QuickMessages) },
                            )
                        } else {
                            Column {
                                QuickMessagesContent(
                                    modifier = Modifier.weight(1f),
                                    quickMessages = settings.quickMessages,
                                    selectedIds = assistant.quickMessageIds,
                                    onToggle = { quickMessageId, checked ->
                                        val newIds = if (checked) assistant.quickMessageIds + quickMessageId
                                        else assistant.quickMessageIds - quickMessageId
                                        vm.update(assistant.copy(quickMessageIds = newIds))
                                    },
                                )
                                TextButton(
                                    onClick = { navController.navigate(Screen.QuickMessages) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.assistant_extensions_page_goto_extensions))
                                }
                            }
                        }
                    }

                    ExtensionTab.MODE_INJECTIONS -> {
                        if (settings.promptPresets.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_mode_injections),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Prompts) },
                            )
                        } else {
                            PresetPickerContent(
                                modifier = Modifier.fillMaxSize(),
                                presets = settings.promptPresets,
                                navigation = pickerNavigation,
                                selectedIds = assistant.modeInjectionIds,
                                onToggle = { injId, checked ->
                                    val newIds = if (checked) assistant.modeInjectionIds + injId
                                    else assistant.modeInjectionIds - injId
                                    vm.update(assistant.copy(modeInjectionIds = newIds))
                                },
                                onManage = { navController.navigate(Screen.Prompts) },
                            )
                        }
                    }

                    ExtensionTab.LOREBOOKS -> {
                        if (settings.lorebooks.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_lorebooks),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_prompts),
                                onAction = { navController.navigate(Screen.Worldbooks) },
                            )
                        } else {
                            WorldbookPickerContent(
                                modifier = Modifier.fillMaxSize(),
                                lorebooks = settings.lorebooks,
                                navigation = pickerNavigation,
                                selectedIds = assistant.lorebookIds,
                                onToggle = { injId, checked ->
                                    val newIds = if (checked) assistant.lorebookIds + injId
                                    else assistant.lorebookIds - injId
                                    vm.update(assistant.copy(lorebookIds = newIds))
                                },
                                onManage = { navController.navigate(Screen.Worldbooks) },
                            )
                        }
                    }

                    ExtensionTab.SKILLS -> {
                        if (skills.isEmpty()) {
                            ExtensionEmptyState(
                                message = stringResource(R.string.assistant_extensions_page_empty_skills),
                                buttonText = stringResource(R.string.assistant_extensions_page_goto_extensions),
                                onAction = { navController.navigate(Screen.Skills) },
                            )
                        } else {
                            // #1901：与管理页同源的搜索 + 单分类，避免技能多了以后在面板里翻不到
                            var skillQuery by rememberSaveable { mutableStateOf("") }
                            var skillCategory by rememberSaveable(stateSaver = SkillCategoryFilterSaver) {
                                mutableStateOf<SkillCategoryFilter>(SkillCategoryFilter.All)
                            }
                            val filteredSkills = remember(skills, skillQuery, skillCategory) {
                                skills.filterSkills(query = skillQuery, category = skillCategory)
                            }
                            SkillsContent(
                                modifier = Modifier.fillMaxSize(),
                                skills = filteredSkills,
                                enabledSkills = assistant.enabledSkills,
                                onToggle = { name, checked ->
                                    val newSkills = if (checked) assistant.enabledSkills + name
                                    else assistant.enabledSkills - name
                                    vm.update(assistant.copy(enabledSkills = newSkills))
                                },
                                onManage = { navController.navigate(Screen.Skills) },
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
                        }
                    }
                }
            }
        }
    }
}
