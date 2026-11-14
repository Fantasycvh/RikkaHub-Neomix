package app.androidrik.rikkahub.ui.pages.debug

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.ui.components.ui.UIAvatar
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.richtext.Mermaid
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    GlassTopBarScaffold(displaySetting = LocalSettings.current.displaySetting,
        topBar = {
            TopAppBar(
                    colors = pageGlassTopBarColors(CustomColors.topBarColors),
                title = {
                    Text("Debug Mode")
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        val state = rememberPagerState { 5 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text("Main")
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text("Colors")
                    }
                )
                Tab(
                    selected = state.currentPage == 2,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(2)
                        }
                    },
                    text = {
                        Text("Logging")
                    }
                )
                Tab(
                    selected = state.currentPage == 3,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(3)
                        }
                    },
                    text = {
                        Text("Recovery")
                    }
                )
                Tab(
                    selected = state.currentPage == 4,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(4)
                        }
                    },
                    text = {
                        Text("Perf")
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> ColorsPage()
                    2 -> Box {}
                    3 -> RecoveryPage(vm)
                    4 -> PerfPage()
                }
            }
        }
    }
}

/**
 * 数据恢复：把「聊天记录里引用了、但设置里已不存在」的助手补回来
 * （上游 `b9c0d3b7` 的页面，本仓按墓碑机制做了分类处理，见 [DebugVM.recoverAssistantsFromConversations]）。
 *
 * 对话列表是按助手过滤的（`getConversationsOfAssistant`），助手没了那些对话就**完全不可见**，
 * 因此这里做的是「让旧对话重新可见」，不是「找回助手配置」——除内置助手外配置不可恢复。
 */
@Composable
private fun RecoveryPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val conversationAssistants by vm.conversationAssistants.collectAsStateWithLifecycle()
    val existingIds = settings.assistants.map { it.id }.toSet()
    val missing = conversationAssistants
        ?.filterKeys { it !in existingIds }
        ?.entries
        ?.sortedByDescending { it.value }
    var recovering by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.debug_page_recover_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Assistants in settings: ${settings.assistants.size}")
        Text("Assistants referenced by chats: ${conversationAssistants?.size?.toString() ?: "..."}")
        Text("Missing: ${missing?.size?.toString() ?: "..."}")
        missing?.forEach { (id, count) ->
            val kind = if (id in DEFAULT_ASSISTANTS_IDS) "built-in" else "custom"
            Text(
                "$id ($count chats, $kind)",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanConversationAssistants() }) {
                Text("Rescan")
            }
            Button(
                enabled = !recovering && !settings.init && !missing.isNullOrEmpty(),
                onClick = {
                    recovering = true
                    scope.launch {
                        runCatching { vm.recoverAssistantsFromConversations() }
                            .onSuccess { result ->
                                when {
                                    result == null -> toaster.show("Settings not loaded yet", type = ToastType.Error)
                                    result.placeholders == 0 && result.builtInRestored == 0 ->
                                        toaster.show("Nothing to recover")
                                    else -> toaster.show(
                                        "Recovered ${result.placeholders} placeholder(s) + " +
                                            "${result.builtInRestored} built-in(s); " +
                                            "${result.conversations} chat(s) visible again",
                                        type = ToastType.Success,
                                    )
                                }
                            }
                            .onFailure {
                                toaster.show("Recovery failed: ${it.message}", type = ToastType.Error)
                            }
                        recovering = false
                    }
                }
            ) {
                Text("Recover")
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val context = LocalContext.current
    val conversationCount by vm.conversationCount.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        UIAvatar(
            value = avatar,
            onUpdate = {
                println("Avatar updated: $it")
                avatar = it
            },
            name = "A"
        )
        Mermaid(
            code = """
                mindmap
                  root((mindmap))
                    Origins
                      Long history
                      ::icon(fa fa-book)
                      Popularisation
                        British popular psychology author Tony Buzan
                    Research
                      On effectiveness<br/>and features
                      On Automatic creation
                        Uses
                            Creative techniques
                            Strategic planning
                            Argument mapping
                    Tools
                      Pen and paper
                      Mermaid
                """.trimIndent(),
            modifier = Modifier.fillMaxWidth(),
        )

        var counter by remember {
            mutableIntStateOf(0)
        }
        val toaster = LocalToaster.current
        Button(
            onClick = {
                toaster.show(context.getString(R.string.debug_page_toast_test, counter++))
                toaster.show(context.getString(R.string.debug_page_toast_test, counter++), type = ToastType.Info)
                toaster.show(context.getString(R.string.debug_page_toast_test, counter++), type = ToastType.Error)
            }
        ) {
            Text("toast")
        }
        Button(
            onClick = {
                vm.updateSettings(
                    settings.copy(
                        chatModelId = Uuid.random()
                    )
                )
            }
        ) {
            Text(stringResource(R.string.debug_page_reset_chat_model))
        }

        Button(
            onClick = {
                error("测试崩溃 ${Random.nextInt(0..1000)}")
            }
        ) {
            Text(stringResource(R.string.debug_page_crash))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.debug_page_conversation_count, conversationCount?.toString() ?: "..."),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.refreshConversationCount() }) {
                Text(stringResource(R.string.setting_storage_page_refresh))
            }
        }

        Button(
            onClick = {
                vm.createOversizedConversation(30)
                toaster.show(context.getString(R.string.debug_page_creating_oversized))
            }
        ) {
            Text(stringResource(R.string.debug_page_create_oversized))
        }

        Button(
            onClick = {
                vm.createConversationWithMessages(1024)
                toaster.show(context.getString(R.string.debug_page_creating_1024))
            }
        ) {
            Text(stringResource(R.string.debug_page_create_1024))
        }

        Button(
            onClick = {
                vm.createConversationWithTokens(500_000)
                toaster.show(context.getString(R.string.debug_page_creating_500k))
            }
        ) {
            Text(stringResource(R.string.debug_page_create_500k))
        }

        var markdown by remember { mutableStateOf(BETTERTEXT_DEBUG_SAMPLE) }
        MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = markdown,
            onValueChange = { markdown = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ColorsPage() {
    val colorScheme = MaterialTheme.colorScheme
    val colorTokens = remember(colorScheme) {
        listOf(
            "primary" to colorScheme.primary,
            "onPrimary" to colorScheme.onPrimary,
            "primaryContainer" to colorScheme.primaryContainer,
            "onPrimaryContainer" to colorScheme.onPrimaryContainer,
            "inversePrimary" to colorScheme.inversePrimary,
            "secondary" to colorScheme.secondary,
            "onSecondary" to colorScheme.onSecondary,
            "secondaryContainer" to colorScheme.secondaryContainer,
            "onSecondaryContainer" to colorScheme.onSecondaryContainer,
            "tertiary" to colorScheme.tertiary,
            "onTertiary" to colorScheme.onTertiary,
            "tertiaryContainer" to colorScheme.tertiaryContainer,
            "onTertiaryContainer" to colorScheme.onTertiaryContainer,
            "background" to colorScheme.background,
            "onBackground" to colorScheme.onBackground,
            "surface" to colorScheme.surface,
            "onSurface" to colorScheme.onSurface,
            "surfaceVariant" to colorScheme.surfaceVariant,
            "onSurfaceVariant" to colorScheme.onSurfaceVariant,
            "surfaceTint" to colorScheme.surfaceTint,
            "inverseSurface" to colorScheme.inverseSurface,
            "inverseOnSurface" to colorScheme.inverseOnSurface,
            "surfaceBright" to colorScheme.surfaceBright,
            "surfaceDim" to colorScheme.surfaceDim,
            "surfaceContainer" to colorScheme.surfaceContainer,
            "surfaceContainerHigh" to colorScheme.surfaceContainerHigh,
            "surfaceContainerHighest" to colorScheme.surfaceContainerHighest,
            "surfaceContainerLow" to colorScheme.surfaceContainerLow,
            "surfaceContainerLowest" to colorScheme.surfaceContainerLowest,
            "error" to colorScheme.error,
            "onError" to colorScheme.onError,
            "errorContainer" to colorScheme.errorContainer,
            "onErrorContainer" to colorScheme.onErrorContainer,
            "outline" to colorScheme.outline,
            "outlineVariant" to colorScheme.outlineVariant,
            "scrim" to colorScheme.scrim,
            "primaryFixed" to colorScheme.primaryFixed,
            "primaryFixedDim" to colorScheme.primaryFixedDim,
            "onPrimaryFixed" to colorScheme.onPrimaryFixed,
            "onPrimaryFixedVariant" to colorScheme.onPrimaryFixedVariant,
            "secondaryFixed" to colorScheme.secondaryFixed,
            "secondaryFixedDim" to colorScheme.secondaryFixedDim,
            "onSecondaryFixed" to colorScheme.onSecondaryFixed,
            "onSecondaryFixedVariant" to colorScheme.onSecondaryFixedVariant,
            "tertiaryFixed" to colorScheme.tertiaryFixed,
            "tertiaryFixedDim" to colorScheme.tertiaryFixedDim,
            "onTertiaryFixed" to colorScheme.onTertiaryFixed,
            "onTertiaryFixedVariant" to colorScheme.onTertiaryFixedVariant,
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(colorTokens, key = { it.first }) { (name, color) ->
            ColorTokenItem(name, color)
        }
    }
}

@Composable
private fun ColorTokenItem(name: String, color: Color) {
    val shape = appSkinRoundedShape(8f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .weight(1f)
                .clip(appSkinRoundedShape(4f))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, appSkinRoundedShape(4f))
        )
        Column(modifier = Modifier.weight(2f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                color.toHexString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Color.toHexString(): String {
    val argb = toArgb()
    return "#%08X".format(argb)
}

private val BETTERTEXT_DEBUG_SAMPLE = """
## BetterText 渲染验证

高公式：矩阵 ${'$'}\begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'} 的行列式是 ${'$'}ad - bc${'$'}。连分数 ${'$'}\cfrac{1}{1 + \cfrac{1}{1 + x}}${'$'} 和 ${'$'}\sqrt{\begin{matrix} 1 \\ 2 \\ 3 \end{matrix}}${'$'} 会把所在的行撑高，而 ${'$'}\displaystyle\int_0^1 x^2\,dx = \frac{1}{3}${'$'} 这样的大型运算符也一样。

长公式：${'$'}f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n = f(a) + f'(a)(x-a) + \frac{f''(a)}{2!}(x-a)^2 + \cdots${'$'} 应该按关系符和二元运算符折行。

| 名称 | 值 |
| ---- | --- |
| 矩阵 | ${'$'}\begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'} |
| 连分数 | ${'$'}\cfrac{1}{1 + \cfrac{1}{1 + x}}${'$'} |

搜索引用：Compose 设置 lineHeight 之后每一行都一样高[citation,example.com](abc123)，相关接口也还没有公开。

> 引用块与行内公式 ${'$'}e^{i\pi} + 1 = 0${'$'} 混排。

<details><summary>点开看折叠内容</summary>

折叠内容里的公式 ${'$'}a^2 + b^2 = c^2${'$'}。

</details>

```kotlin
fun main() {
    println("Hello BetterText")
}
```
"""
