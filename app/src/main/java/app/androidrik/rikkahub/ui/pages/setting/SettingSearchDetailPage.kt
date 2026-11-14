package app.androidrik.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import app.androidrik.rikkahub.ui.context.LocalSettings
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.bettertext.highlight.LocalCodeHighlighter
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.ui.pages.setting.components.ProviderApiKeysEditor
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.plus
import app.androidrik.search.DoubaoSearchMode
import app.androidrik.search.SearchCommonOptions
import app.androidrik.search.SearchResult
import app.androidrik.search.SearchService
import app.androidrik.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingSearchDetailPage(
    serviceId: Uuid,
    vm: SettingVM = koinViewModel()
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val nav = LocalNavController.current

    val service = settings.searchServices.find { it.id == serviceId } ?: return
    val serviceIndex = settings.searchServices.indexOf(service)
    var options by remember(service) { mutableStateOf(service) }

    fun save(updated: SearchServiceOptions) {
        options = updated
        // 读-改-写（transform）：文本域提交是高频路径。旧写法 `settings.copy(...)` 用组合期
        // 的 Settings 快照整对象覆盖，文本域落盘后旧值会再经 settingsFlow 回灌活跃编辑框
        // （AGENTS「TextField Host Rules」第 4 条）。transform 让 DataStore 的串行 edit 承担原子性。
        vm.updateSettings { current ->
            current.copy(
                searchServices = current.searchServices.map {
                    if (it.id == updated.id) updated else it
                }
            )
        }
    }

    GlassTopBarScaffold(displaySetting = LocalSettings.current.displaySetting,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(options.displayName)
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (settings.searchServices.size > 1) {
                        IconButton(
                            onClick = {
                                val newServices = settings.searchServices.toMutableList()
                                newServices.removeAt(serviceIndex)
                                vm.updateSettings(settings.copy(searchServices = newServices))
                                nav.popBackStack()
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(R.string.delete)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = pageGlassTopBarColors(CustomColors.topBarColors)
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = padding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("config") {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = CustomColors.listItemColors.containerColor
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .animateContentSize()
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.setting_page_search_config),
                            style = MaterialTheme.typography.titleMedium
                        )

                        SearchServiceOptionsEditor(
                            options = options,
                            onUpdateOptions = { save(it) }
                        )

                        ProvideTextStyle(MaterialTheme.typography.labelMedium) {
                            SearchService.getService(options).Description()
                        }
                    }
                }
            }

            item("test") {
                SearchTestSection(
                    options = options,
                    commonOptions = settings.searchCommonOptions
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun SearchServiceOptionsEditor(
    options: SearchServiceOptions,
    onUpdateOptions: (SearchServiceOptions) -> Unit
) {
    when (options) {
        is SearchServiceOptions.TavilyOptions -> {
            TavilyOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ExaOptions -> {
            ExaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.ZhipuOptions -> {
            ZhipuOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DoubaoOptions -> {
            DoubaoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SearXNGOptions -> {
            SearXNGOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.LinkUpOptions -> {
            SearchLinkUpOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BraveOptions -> {
            BraveOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.MetasoOptions -> {
            MetasoOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.OllamaOptions -> {
            OllamaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.PerplexityOptions -> {
            PerplexityOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BingLocalOptions -> {}
        is SearchServiceOptions.FirecrawlOptions -> {
            FirecrawlOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.JinaOptions -> {
            JinaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.BochaOptions -> {
            BochaOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.DeepSeekOptions -> {
            DeepSeekOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.GrokOptions -> {
            GrokOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.TinyfishOptions -> {
            TinyfishOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.SerperOptions -> {
            SerperOptions(options) { onUpdateOptions(it) }
        }
        is SearchServiceOptions.CustomJsOptions -> {
            CustomJsOptions(options) { onUpdateOptions(it) }
        }
    }
}

@Composable
private fun SearchTestSection(
    options: SearchServiceOptions,
    commonOptions: SearchCommonOptions
) {
    var query by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<SearchResult>?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.setting_page_search_test),
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.setting_page_search_test_query_hint)) },
                    singleLine = true
                )

                IconButton(
                    onClick = {
                        if (query.isNotBlank() && !testing) {
                            testing = true
                            result = null
                            scope.launch {
                                val service = SearchService.getService(options)
                                val params = JsonObject(
                                    mapOf("query" to JsonPrimitive(query))
                                )
                                result = service.search(params, commonOptions, options)
                                testing = false
                            }
                        }
                    },
                    enabled = query.isNotBlank() && !testing
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(4.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = HugeIcons.Play,
                            contentDescription = stringResource(R.string.setting_page_search_test_run)
                        )
                    }
                }
            }

            result?.let { res ->
                res.onSuccess { searchResult ->
                    searchResult.answer?.let { answer ->
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    searchResult.items.forEachIndexed { index, item ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${index + 1}. ${item.title}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = item.text.take(200),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                res.onFailure { error ->
                    Text(
                        text = error.message ?: stringResource(R.string.search_detail_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
internal fun TavilyOptions(
    options: SearchServiceOptions.TavilyOptions,
    onUpdateOptions: (SearchServiceOptions.TavilyOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("basic", "advanced")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_include_answer))
        },
        description = {
            Text(stringResource(R.string.search_detail_include_answer_description))
        },
        tail = {
            Switch(
                checked = options.includeAnswer,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(includeAnswer = checked))
                }
            )
        }
    )
}

@Composable
internal fun ExaOptions(
    options: SearchServiceOptions.ExaOptions,
    onUpdateOptions: (SearchServiceOptions.ExaOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun ZhipuOptions(
    options: SearchServiceOptions.ZhipuOptions,
    onUpdateOptions: (SearchServiceOptions.ZhipuOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun DoubaoOptions(
    options: SearchServiceOptions.DoubaoOptions,
    onUpdateOptions: (SearchServiceOptions.DoubaoOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(label = { Text("Mode") }) {
        val modes = DoubaoSearchMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    onClick = { onUpdateOptions(options.copy(mode = mode)) },
                    selected = options.mode == mode
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
    }
}

@Composable
internal fun SearXNGOptions(
    options: SearchServiceOptions.SearXNGOptions,
    onUpdateOptions: (SearchServiceOptions.SearXNGOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_api_url))
        }
    ) {
        // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条；范本 1808d418f）。
        // 旧写法 `value = options.url`（options 是 async 持久态投影的本地镜像）每键写回，
        // 落盘后旧值回灌活跃编辑框 ⇒ 逐字符抖动/丢字。
        val urlState = rememberPersistedTextFieldState(
            externalValue = options.url,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(url = text)) },
        )
        OutlinedTextField(
            state = urlState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_engines))
        }
    ) {
        val enginesState = rememberPersistedTextFieldState(
            externalValue = options.engines,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(engines = text)) },
        )
        OutlinedTextField(
            state = enginesState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_language))
        }
    ) {
        val languageState = rememberPersistedTextFieldState(
            externalValue = options.language,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(language = text)) },
        )
        OutlinedTextField(
            state = languageState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_username))
        }
    ) {
        val usernameState = rememberPersistedTextFieldState(
            externalValue = options.username,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(username = text)) },
        )
        OutlinedTextField(
            state = usernameState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_password))
        }
    ) {
        val passwordState = rememberPersistedTextFieldState(
            externalValue = options.password,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(password = text)) },
        )
        OutlinedTextField(
            state = passwordState,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun SearchLinkUpOptions(
    options: SearchServiceOptions.LinkUpOptions,
    onUpdateOptions: (SearchServiceOptions.LinkUpOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_depth))
        }
    ) {
        val depthOptions = listOf("standard", "deep")
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            depthOptions.forEachIndexed { index, depth ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = depthOptions.size),
                    onClick = {
                        onUpdateOptions(options.copy(depth = depth))
                    },
                    selected = options.depth == depth
                ) {
                    Text(depth.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}

@Composable
internal fun BraveOptions(
    options: SearchServiceOptions.BraveOptions,
    onUpdateOptions: (SearchServiceOptions.BraveOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun SerperOptions(
    options: SearchServiceOptions.SerperOptions,
    onUpdateOptions: (SearchServiceOptions.SerperOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun MetasoOptions(
    options: SearchServiceOptions.MetasoOptions,
    onUpdateOptions: (SearchServiceOptions.MetasoOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun OllamaOptions(
    options: SearchServiceOptions.OllamaOptions,
    onUpdateOptions: (SearchServiceOptions.OllamaOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun PerplexityOptions(
    options: SearchServiceOptions.PerplexityOptions,
    onUpdateOptions: (SearchServiceOptions.PerplexityOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens))
        }
    ) {
        // 数值投影（D1）：本地 TextFieldState 权威；解析成功才写、失败不写，绝不把 Int 投影
        // `.toString()` 回填输入框（旧写法每键回写，落盘后旧值会把 "4096" 截成 "4"）。
        val maxTokensState = rememberPersistedTextFieldState(
            externalValue = options.maxTokens?.takeIf { it > 0 }?.toString() ?: "",
            resetKey = options.id,
            onCommit = { text ->
                if (text.isBlank()) {
                    onUpdateOptions(options.copy(maxTokens = null))
                } else {
                    text.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { onUpdateOptions(options.copy(maxTokens = it)) }
                }
            },
        )
        OutlinedTextField(
            state = maxTokensState,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_max_tokens_per_page))
        }
    ) {
        val maxTokensPerPageState = rememberPersistedTextFieldState(
            externalValue = options.maxTokensPerPage?.takeIf { it > 0 }?.toString() ?: "",
            resetKey = options.id,
            onCommit = { text ->
                if (text.isBlank()) {
                    onUpdateOptions(options.copy(maxTokensPerPage = null))
                } else {
                    text.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { onUpdateOptions(options.copy(maxTokensPerPage = it)) }
                }
            },
        )
        OutlinedTextField(
            state = maxTokensPerPageState,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }
}

@Composable
internal fun FirecrawlOptions(
    options: SearchServiceOptions.FirecrawlOptions,
    onUpdateOptions: (SearchServiceOptions.FirecrawlOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun JinaOptions(
    options: SearchServiceOptions.JinaOptions,
    onUpdateOptions: (SearchServiceOptions.JinaOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_url))
        }
    ) {
        // trim 只在提交时做：旧写法每键 trim 会改写输入框内容，再经持久态回灌。
        val searchUrlState = rememberPersistedTextFieldState(
            externalValue = options.searchUrl,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(searchUrl = text.trim())) },
        )
        OutlinedTextField(
            state = searchUrlState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://s.jina.ai/")
            }
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_url))
        }
    ) {
        val scrapeUrlState = rememberPersistedTextFieldState(
            externalValue = options.scrapeUrl,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(scrapeUrl = text.trim())) },
        )
        OutlinedTextField(
            state = scrapeUrlState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("https://r.jina.ai/")
            }
        )
    }
}

@Composable
internal fun BochaOptions(
    options: SearchServiceOptions.BochaOptions,
    onUpdateOptions: (SearchServiceOptions.BochaOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_summary))
        },
        description = {
            Text(stringResource(R.string.search_detail_summary_desc))
        },
        tail = {
            Switch(
                checked = options.summary,
                onCheckedChange = { checked ->
                    onUpdateOptions(options.copy(summary = checked))
                }
            )
        }
    )
}

@Composable
internal fun DeepSeekOptions(
    options: SearchServiceOptions.DeepSeekOptions,
    onUpdateOptions: (SearchServiceOptions.DeepSeekOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    Text(
        text = "Shares the DeepSeek chat API key. Search / Fetch are billed per successful call " +
            "(¥5 / 1000 calls each); the QPS limit is per account, not per key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun TinyfishOptions(
    options: SearchServiceOptions.TinyfishOptions,
    onUpdateOptions: (SearchServiceOptions.TinyfishOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )
}

@Composable
internal fun GrokOptions(
    options: SearchServiceOptions.GrokOptions,
    onUpdateOptions: (SearchServiceOptions.GrokOptions) -> Unit
) {
    ApiKeysFormItem(
        apiKeys = options.apiKeys,
        legacyKey = options.apiKey,
        onChange = { newKeys -> onUpdateOptions(options.copy(apiKeys = newKeys)) },
    )

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_model))
        }
    ) {
        val modelState = rememberPersistedTextFieldState(
            externalValue = options.model,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(model = text)) },
        )
        OutlinedTextField(
            state = modelState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_custom_url))
        }
    ) {
        val customUrlState = rememberPersistedTextFieldState(
            externalValue = options.customUrl,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(customUrl = text)) },
        )
        OutlinedTextField(
            state = customUrlState,
            modifier = Modifier.fillMaxWidth()
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_system_prompt))
        }
    ) {
        val systemPromptState = rememberPersistedTextFieldState(
            externalValue = options.systemPrompt,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(systemPrompt = text)) },
        )
        OutlinedTextField(
            state = systemPromptState,
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun CustomJsOptions(
    options: SearchServiceOptions.CustomJsOptions,
    onUpdateOptions: (SearchServiceOptions.CustomJsOptions) -> Unit
) {
    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_name))
        }
    ) {
        val nameState = rememberPersistedTextFieldState(
            externalValue = options.name,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(name = text)) },
        )
        OutlinedTextField(
            state = nameState,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.search_detail_custom_search_placeholder)) }
        )
    }

    val highlighter = LocalCodeHighlighter.current
    val darkMode = LocalDarkMode.current

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_search_script))
        }
    ) {
        // 语法高亮（VisualTransformation）目前只有 legacy `value:` 重载支持，故用本地权威
        // TextFieldState 作唯一数据源桥接：value 来自**本地** state（不再是异步持久态投影），
        // 写回仍走 hook 的去抖单向提交。
        val searchScriptState = rememberPersistedTextFieldState(
            externalValue = options.searchScript,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(searchScript = text)) },
        )
        OutlinedTextField(
            value = searchScriptState.text.toString(),
            onValueChange = { searchScriptState.setTextAndPlaceCursorAtEnd(it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 8,
            maxLines = 20,
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }

    FormItem(
        label = {
            Text(stringResource(R.string.search_detail_scrape_script))
        },
        description = {
            Text(stringResource(R.string.search_detail_scrape_script_desc))
        }
    ) {
        // 同 searchScript：高亮需要 legacy `value:` 重载，本地 state 是唯一数据源。
        val scrapeScriptState = rememberPersistedTextFieldState(
            externalValue = options.scrapeScript,
            resetKey = options.id,
            onCommit = { text -> onUpdateOptions(options.copy(scrapeScript = text)) },
        )
        OutlinedTextField(
            value = scrapeScriptState.text.toString(),
            onValueChange = { scrapeScriptState.setTextAndPlaceCursorAtEnd(it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 20,
            placeholder = {
                Text(
                    text = SearchServiceOptions.CustomJsOptions.DEFAULT_SCRAPE_SCRIPT.trimIndent(),
                    style = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            visualTransformation = HighlightCodeVisualTransformation(
                language = "javascript",
                highlighter = highlighter,
                darkMode = darkMode
            ),
            textStyle = MaterialTheme.typography.bodySmall.merge(fontFamily = JetbrainsMono),
        )
    }
}

/** 搜索服务 API Key 编辑器（每 key 一框 + 启停 + 拖拽优先级，结构化存入 apiKeys，兼容旧 apiKey 字段） */
@Composable
private fun ApiKeysFormItem(
    apiKeys: List<ApiKeyEntry>,
    legacyKey: String,
    onChange: (List<ApiKeyEntry>) -> Unit,
) {
    FormItem(
        label = { Text(stringResource(R.string.search_detail_api_key)) }
    ) {
        ProviderApiKeysEditor(
            keys = apiKeys,
            legacyKey = legacyKey,
            onChange = onChange,
        )
    }
}
