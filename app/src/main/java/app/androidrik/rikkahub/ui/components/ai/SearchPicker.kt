package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.hasBuiltInSearch
import app.androidrik.ai.provider.isDeepSeekWebTool
import app.androidrik.ai.provider.supportsDeepSeekWebTools
import app.androidrik.ai.registry.ModelRegistry
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.AiSearch02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.ToggleSurface
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.pages.setting.SearchAbilityTagLine
import app.androidrik.search.SearchServiceOptions
import app.androidrik.search.enabledServicesWithIndex
import org.koin.compose.koinInject

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    model: Model?,
    onUpdateBuiltInSearch: ((Boolean) -> Unit)? = null,
) {
    var showSearchPicker by remember { mutableStateOf(false) }
    val currentService = settings.searchServices.getOrNull(settings.searchServiceSelected)
    val builtInSearchEnabled = model?.let { it.hasBuiltInSearch(it.findProvider(settings.providers)) } == true

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch || builtInSearchEnabled,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                // 水平内边距 4dp：与相邻 picker 图标保持 ~8dp 视觉间距（历史 8dp 偏松）
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (builtInSearchEnabled) {
                    Icon(
                        imageVector = HugeIcons.AiSearch02,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                } else if (enableSearch && currentService != null) {
                    AutoAIIcon(
                        name = currentService.displayName,
                        color = Color.Transparent
                    )
                } else {
                    Icon(
                        imageVector = HugeIcons.Search01,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )

                SearchPicker(
                    enableSearch = enableSearch,
                    settings = settings,
                    onToggleSearch = onToggleSearch,
                    onUpdateSearchService = { index ->
                        onUpdateSearchService(index)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    model = model,
                    onUpdateBuiltInSearch = onUpdateBuiltInSearch,
                    onDismiss = {
                        showSearchPicker = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onDismiss: () -> Unit,
    onUpdateBuiltInSearch: ((Boolean) -> Unit)? = null,
) {
    val navBackStack = LocalNavController.current

    val provider = model?.findProvider(settings.providers)
    val hasBuiltInSearchEnabled = model?.hasBuiltInSearch(provider) == true
    if (model != null) {
        val searchTool = when {
            model.supportsDeepSeekWebTools(provider) -> BuiltInTools.DeepSeekWebSearch
            ModelRegistry.GEMINI_SERIES.match(model.modelId) ||
                model.modelId.contains("gpt-") ||
                BuiltInTools.Search in model.tools -> BuiltInTools.Search
            else -> null
        }
        if (searchTool != null) {
            BuiltInSearchSetting(model, searchTool, onUpdateBuiltInSearch)
        }
    }

    // 如果没有开启内置搜索，显示搜索服务选择
    if (!hasBuiltInSearchEnabled) {
        AppSearchSettings(
            enableSearch = enableSearch,
            onDismiss = onDismiss,
            navBackStack = navBackStack,
            onToggleSearch = onToggleSearch,
            modifier = modifier,
            settings = settings,
            onUpdateSearchService = onUpdateSearchService
        )
    }
}

@Composable
private fun AppSearchSettings(
    enableSearch: Boolean,
    onDismiss: () -> Unit,
    navBackStack: Navigator,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier,
    settings: Settings,
    onUpdateSearchService: (Int) -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.GlobalSearch, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.use_web_search),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (enableSearch) {
                        stringResource(R.string.web_search_enabled)
                    } else {
                        stringResource(R.string.web_search_disabled)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }
            IconButton(
                onClick = {
                    onDismiss()
                    navBackStack.navigate(Screen.SettingSearch)
                }
            ) {
                Icon(HugeIcons.Settings03, null)
            }
            Switch(
                checked = enableSearch,
                onCheckedChange = onToggleSearch
            )
        }
    }

    // 停用的服务在选择器里隐藏。注意回传的是**真实索引**（searchServiceSelected 是完整
    // 列表的索引、不是 id），故用 enabledServicesWithIndex 而不是裸 withIndex。
    val visibleServices = remember(settings.searchServices) {
        settings.searchServices.enabledServicesWithIndex()
    }

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(150.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (visibleServices.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.search_picker_empty_all_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                )
            }
        }
        // key 用真实索引：服务被停用后可见列表下标会整体前移，用显示下标做 key 会串位
        items(
            count = visibleServices.size,
            key = { visibleServices[it].first },
        ) { position ->
            val (index, service) = visibleServices[position]
            val containerColor = animateColorAsState(
                if (settings.searchServiceSelected == index) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            val textColor = animateColorAsState(
                if (settings.searchServiceSelected == index) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = containerColor.value,
                    contentColor = textColor.value,
                ),
                onClick = {
                    onUpdateSearchService(index)
                },
                shape = MaterialTheme.shapes.large
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AutoAIIcon(
                        name = service.displayName,
                        modifier = Modifier.size(24.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = service.displayName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        SearchAbilityTagLine(
                            options = service,
                            modifier = Modifier
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuiltInSearchSetting(
    model: Model,
    searchTool: BuiltInTools,
    onUpdateBuiltInSearch: ((Boolean) -> Unit)?,
) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    Card {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HugeIcons.GlobalSearch, null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.built_in_search_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.built_in_search_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f)
                )
            }

            Switch(
                checked = model.tools.contains(searchTool),
                onCheckedChange = { checked ->
                    if (onUpdateBuiltInSearch != null) {
                        onUpdateBuiltInSearch(checked)
                        return@Switch
                    }
                    scope.launch {
                        settingsStore.update { settings ->
                            val currentModel = settings.findModelById(model.id) ?: return@update settings
                            if (checked && searchTool.isDeepSeekWebTool &&
                                !currentModel.supportsDeepSeekWebTools(currentModel.findProvider(settings.providers))
                            ) return@update settings
                            settings.copy(
                                providers = settings.providers.map { providerSetting ->
                                    providerSetting.editModel(
                                        currentModel.copy(
                                            tools = if (checked) {
                                                currentModel.tools + searchTool
                                            } else {
                                                currentModel.tools - BuiltInTools.Search - BuiltInTools.DeepSeekWebSearch
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    }
}
