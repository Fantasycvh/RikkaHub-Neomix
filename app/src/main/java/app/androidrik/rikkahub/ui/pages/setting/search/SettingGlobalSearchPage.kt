package app.androidrik.rikkahub.ui.pages.setting.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors

/**
 * 设置页全局搜索：输入关键词，列出所有命中的设置项，点一条直接跳到对应设置页。
 *
 * 为什么要做：设置项分散在 20 多个页面里，用户经常「知道有这功能但不知道在哪」。
 * 结果只显示「条目 + 页面 › 分组」而不做页内滚动（见 docs/design/design-settings-search.md）。
 */
@Composable
fun SettingGlobalSearchPage() {
    val navController = LocalNavController.current
    // LocalResources + LocalConfiguration 而不是 LocalContext.getString：
    // 后者不随配置变化重组（compose lint 会报 LocalContextResources），切语言后会留着旧文案
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val focusRequester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }

    // 索引只读、字符串只有在查询/语言变化时才需要重解析
    val hits = remember(query, configuration, resources) {
        SettingSearchMatcher.search(
            query = query,
            entries = SettingSearchIndex.entries,
            titleOf = { resources.getString(it) },
            descriptionOf = { resources.getString(it) },
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton() },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                stringResource(R.string.setting_global_search_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        HugeIcons.Cancel01,
                                        contentDescription = stringResource(R.string.setting_global_search_clear),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusRequester.requestFocus() }),
                        // 顶栏里不要输入框的容器与下划线，否则像嵌了个盒子
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        when {
            query.isBlank() -> EmptySearchHint(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .imePadding(),
                onQuickLink = { navController.navigate(it) },
            )

            hits.isEmpty() -> NoResultHint(
                query = query,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .imePadding(),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item("resultCount") {
                    Text(
                        text = stringResource(R.string.setting_global_search_result_count, hits.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                items(hits, key = { it.entry.id }) { hit ->
                    CardGroup(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        item(
                            onClick = { navController.navigate(hit.entry.route) },
                            headlineContent = {
                                Text(stringResource(hit.entry.title))
                            },
                            supportingContent = {
                                val titleText = stringResource(hit.entry.title)
                                val pageText = stringResource(hit.entry.pageTitle)
                                val sectionText = hit.entry.section?.let { stringResource(it) }
                                // 「主题 / 主题」这种页面名等于条目名的面包屑是噪声，直接省掉
                                val breadcrumb = buildString {
                                    if (pageText != titleText) append(pageText)
                                    if (sectionText != null) {
                                        if (isNotEmpty()) append(" › ")
                                        append(sectionText)
                                    }
                                }
                                if (breadcrumb.isNotEmpty() || hit.matchedInDescription) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (breadcrumb.isNotEmpty()) {
                                            Text(
                                                breadcrumb,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (hit.matchedInDescription) {
                                            hit.entry.description?.let { desc ->
                                                Text(
                                                    stringResource(desc),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            trailingContent = {
                                Icon(
                                    HugeIcons.ArrowRight01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 还没输入时的引导：说明能搜什么 + 几个常去页面的快捷入口。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptySearchHint(
    modifier: Modifier = Modifier,
    onQuickLink: (Screen) -> Unit,
) {
    val quickLinks = listOf(
        R.string.setting_page_preferences_general to Screen.SettingPreferencesGeneral,
        R.string.setting_page_preferences_ui to Screen.SettingPreferencesUI,
        R.string.setting_page_preferences_theme to Screen.SettingPreferencesTheme,
        R.string.setting_page_providers to Screen.SettingProvider,
        R.string.setting_page_default_model to Screen.SettingModels,
        R.string.speech_page_title to Screen.SettingSpeech,
        R.string.setting_page_data_backup to Screen.Backup,
    )
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    HugeIcons.Search01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.setting_global_search_empty),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.setting_global_search_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.setting_global_search_quick_links),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quickLinks.forEach { (titleRes, screen) ->
                AssistChip(
                    onClick = { onQuickLink(screen) },
                    label = { Text(stringResource(titleRes)) },
                )
            }
        }
    }
}

@Composable
private fun NoResultHint(query: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.setting_global_search_no_result, query),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.setting_global_search_no_result_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
