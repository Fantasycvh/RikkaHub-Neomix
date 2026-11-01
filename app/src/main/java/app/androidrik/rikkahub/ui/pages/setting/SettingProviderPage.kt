package app.androidrik.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.Tag
import app.androidrik.rikkahub.ui.components.ui.TagType
import app.androidrik.rikkahub.ui.components.ui.cardGroupItemShape
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.DragOrderState
import app.androidrik.rikkahub.utils.reorderForDrag
import app.androidrik.rikkahub.utils.plus
import kotlin.uuid.Uuid
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var searchQuery by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    // 拖动只改本地顺序、松手才落盘：设置异步回流会把每帧移动改回去（抽搐，同 ModelList dc2a43003）
    val providerDragOrder = remember { DragOrderState<ProviderSetting>() }
    LaunchedEffect(searchQuery) {
        // 搜索时拖拽与过滤显示脱钩，丢弃本地顺序
        if (searchQuery.isNotBlank()) providerDragOrder.reset()
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        providerDragOrder.onMove(settings.providers) { it.reorderForDrag(from.index, to.index) }
    }
    LaunchedEffect(settings.providers) { providerDragOrder.syncFromSource(settings.providers) }

    val filteredProviders = remember(settings.providers, searchQuery) {
        if (searchQuery.isBlank()) {
            settings.providers
        } else {
            settings.providers.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // 设置是异步写入的：等新提供商真正出现在列表里再滚过去；新建会跳详情页，所以状态要能跨页面保存
    var pendingScrollId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingScrollId, filteredProviders) {
        val id = pendingScrollId ?: return@LaunchedEffect
        val index = filteredProviders.indexOfFirst { it.id.toString() == id }
        if (index >= 0) {
            lazyListState.animateScrollToItem(index)
            pendingScrollId = null
        }
    }

    fun addProvider(provider: ProviderSetting) {
        // 搜索词可能把新提供商过滤掉，用户会以为「没加上」
        searchQuery = ""
        pendingScrollId = provider.id.toString()
        vm.updateSettings { latest ->
            latest.copy(providers = listOf(provider) + latest.providers)
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.setting_provider_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    AddButton { provider ->
                        addProvider(provider)
                        navController.navigate(
                            Screen.SettingProviderDetail(providerId = provider.id.toString())
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_providers)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
            )


            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) +
                    PaddingValues(bottom = innerPadding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                state = lazyListState,
            ) {
                items(
                    if (searchQuery.isBlank()) providerDragOrder.pendingOrder ?: filteredProviders else filteredProviders,
                    key = { it.id },
                ) { provider ->
                    ReorderableItem(
                        state = reorderableState,
                        key = provider.id
                    ) { isDragging ->
                        ProviderItem(
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth(),
                            provider = provider,
                            dragHandle = {
                                val haptic = LocalHapticFeedback.current
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                providerDragOrder.onDragStopped { order ->
                                                    vm.updateSettings(settings.copy(providers = order))
                                                }
                                            }
                                        )
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.DragDropHorizontal,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                            }
                        )
                    }
                }
            }
        }
    }
}

// 新建时只选类型，其余配置到详情页里填
@Composable
private fun AddButton(onAdd: (ProviderSetting) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    // 账号类（ChatGPT / Grok）也在新建入口里：它们的 API 格式由登录方式决定，
    // 不能像 API Key 类那样事后切换类型，只能从这里创建。
    val templates = remember {
        listOf(
            ProviderSetting.OpenAI(),
            ProviderSetting.Google(),
            ProviderSetting.Claude(),
            ProviderSetting.ChatGPT(),
            ProviderSetting.Grok(),
        )
    }

    IconButton(
        onClick = { showSheet = true }
    ) {
        Icon(HugeIcons.Add01, "Add")
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_add_provider),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    templates.fastForEachIndexed { index, template ->
                        Surface(
                            onClick = {
                                showSheet = false
                                onAdd(template.copyProvider(id = Uuid.random()))
                            },
                            shape = cardGroupItemShape(index, templates.size),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AutoAIIcon(
                                    name = template.name,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderItem(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (provider.enabled) {
                CustomColors.listItemColors.containerColor
            } else MaterialTheme.colorScheme.errorContainer,
        ),
        onClick = {
            onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AutoAIIcon(
                name = provider.name,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    CompositionLocalProvider(LocalContentColor provides LocalContentColor.current.copy(alpha = 0.7f)) {
                        provider.shortDescription()
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Tag(type = if (provider.enabled) TagType.SUCCESS else TagType.WARNING) {
                        Text(stringResource(if (provider.enabled) R.string.setting_provider_page_enabled else R.string.setting_provider_page_disabled))
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                    if (provider.name == "AiHubMix") {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.setting_provider_page_aihubmix_discount))
                        }
                    }
                }
            }
            dragHandle()
        }
    }
}
