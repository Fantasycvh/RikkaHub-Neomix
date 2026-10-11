package app.androidrik.rikkahub

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.Serializable
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker
import app.androidrik.rikkahub.data.db.MigrationState
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.ui.activity.SafeModeActivity
import app.androidrik.rikkahub.ui.components.ui.TTSController
import app.androidrik.rikkahub.ui.context.LocalASRState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalSharedTransitionScope
import app.androidrik.rikkahub.ui.context.repairIfEmpty
import app.androidrik.rikkahub.ui.context.LocalTTSState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.hooks.readBooleanPreference
import app.androidrik.rikkahub.ui.hooks.readStringPreference
import app.androidrik.rikkahub.ui.hooks.rememberCustomAsrState
import app.androidrik.rikkahub.ui.hooks.rememberCustomTtsState
import app.androidrik.rikkahub.ui.pages.assistant.AssistantPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantKnowledgeBasePage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import app.androidrik.rikkahub.ui.pages.backup.BackupPage
import app.androidrik.rikkahub.ui.pages.chat.ChatPage
import app.androidrik.rikkahub.ui.pages.debug.DebugPage
import app.androidrik.rikkahub.ui.pages.extensions.ExtensionsPage
import app.androidrik.rikkahub.ui.pages.extensions.PromptPage
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunsPage
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunDetailPage
import app.androidrik.rikkahub.ui.pages.subagent.SubagentPresetsPage
import app.androidrik.rikkahub.ui.pages.extensions.QuickMessagesPage
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillsPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.rikkahub.ui.pages.favorite.FavoritePage
import app.androidrik.rikkahub.ui.pages.history.HistoryPage
import app.androidrik.rikkahub.ui.pages.imggen.ImageGenPage
import app.androidrik.rikkahub.ui.pages.log.LogPage
import app.androidrik.rikkahub.ui.pages.promptinspect.PromptInspectPage
import app.androidrik.rikkahub.ui.pages.search.SearchPage
import app.androidrik.rikkahub.ui.pages.setting.SettingAboutPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import app.androidrik.rikkahub.ui.pages.setting.SettingThemePage
import app.androidrik.rikkahub.ui.pages.setting.SettingFilesPage
import app.androidrik.rikkahub.ui.pages.setting.SettingMcpPage
import app.androidrik.rikkahub.ui.pages.setting.SettingKnowledgeBaseDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingKnowledgeBasePage
import app.androidrik.rikkahub.ui.pages.setting.SettingModelPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPage
import app.androidrik.rikkahub.ui.pages.setting.SettingProviderDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingProviderPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSearchDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSearchPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSpeechPage
import app.androidrik.rikkahub.ui.pages.setting.SettingWebPage
import app.androidrik.rikkahub.ui.pages.share.handler.ShareHandlerPage
import app.androidrik.rikkahub.ui.pages.stats.StatsPage
import app.androidrik.rikkahub.ui.pages.translator.TranslatorPage
import app.androidrik.rikkahub.ui.pages.webview.WebViewPage
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.utils.CrashHandler
import app.androidrik.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "app.androidrik.rikkahub.action.TRANSLATE"
private const val ACTION_IMAGE_GEN = "app.androidrik.rikkahub.action.IMAGE_GEN"

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private var navStack: MutableList<NavKey>? = null

    // Compose 导航栈就绪前到达的 Intent 排队，SideEffect flush（对齐上游 513b784c）
    private val pendingIntents = ArrayDeque<Intent>()

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) {
            handleIntent(intent)
        }
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * 兼容读取分享 Intent 的 EXTRA_STREAM：部分系统/App 以 Parcelable Uri 传递
     * （getStringExtra 返回 null → 图片/文件丢失），多图分享时 Uri 在 clipData 而非 extra。
     * 依次尝试：String extra → Parcelable Uri extra → clipData 第一项。
     */
    private fun Intent?.readShareStreamUri(): String? {
        if (this == null) return null
        return getStringExtra(Intent.EXTRA_STREAM)
            ?: getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.toString()
            ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString()
    }

    /**
     * 统一 Intent 入口（对齐上游 513b784c）：冷启动/onNewIntent 都走这里；
     * 导航栈未就绪时排队（AppRoutes SideEffect flush）；末尾去重防连点。
     * fork 适配：ACTION_SEND 的 streamUri 保留 readShareStreamUri 三级兜底（D6/D7）；
     * 文本换行归一（F6：\r\n → \n，防 Windows 来源文本残留 \r）。
     */
    private fun handleIntent(intent: Intent) {
        val backStack = navStack ?: run {
            pendingIntents.addLast(intent)
            return
        }
        val destination = when (intent.action) {
            ACTION_TRANSLATE -> Screen.Translator
            ACTION_IMAGE_GEN -> Screen.ImageGen
            Intent.ACTION_SEND -> Screen.ShareHandler(
                text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().normalizeNewlines(),
                streamUri = intent.readShareStreamUri(),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().normalizeNewlines(),
            )
            // 子代理审批通知点进来 → 运行详情页（那里有入参全文与批准/拒绝卡片）；
            // 否则沿用会话 id 的老路径
            else -> intent.getStringExtra("runId")?.let { Screen.AgentRunDetail(it) }
                ?: intent.getStringExtra("conversationId")?.let { Screen.Chat(it) }
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    // F6：分享/划词文本换行归一（Windows CRLF 残留防护，turbo 3652fd6 语义）
    private fun String.normalizeNewlines(): String =
        replace("\r\n", "\n").replace('\r', '\n')

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.McpOAuthCallback -> Unit // 由 McpManager 消费
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                    // 做梦（V3）整理完成的一次性提示：只在**真的改了东西或攒下建议**时才会来
                    is AppEvent.MemoryConsolidationFinished -> toastState.show(
                        getString(
                            R.string.chat_memory_consolidated_toast,
                            event.applied,
                            event.proposals,
                        )
                    )
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        val startScreen = Screen.Chat(
            id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
                Uuid.random().toString()
            } else {
                readStringPreference(
                    "lastConversationId",
                    Uuid.random().toString()
                ) ?: Uuid.random().toString()
            }
        )

        // 空 backstack 防线（2026-10-05 真机崩溃修复）：
        // nav3 的 `NavDisplay` 在重组时对空栈直接 `require(backStack.isNotEmpty())`
        // ⇒ IllegalArgumentException("NavDisplay backstack cannot be empty")；
        // 更糟的是**崩溃现场会被写进 saved state**，Activity 重建时 `rememberNavBackStack`
        // 原样还原空栈 ⇒ 崩溃循环（实测 3 秒内连崩两次，直到系统 Force finishing）。
        // 成因是返回手势的重复派发：nav3 内部按
        // `repeat(entries.size - currentScene.previousEntries.size) { onBack() }` 补投，
        // 手势状态过期时计数会多出一次，把根页也弹掉（1.1.5/1.2.0 同此数学，见 NavDisplay.kt）。
        // 因此在**进入 NavDisplay 组合之前**同步修复（只在为空时写一次，幂等、不会反复触发重组）：
        // 已经是空栈的存量用户（崩溃过、saved state 里躺着空列表）也能自愈，否则每次开屏都崩。
        val backStack = rememberNavBackStack(startScreen).also { stack ->
            stack.repairIfEmpty(startScreen)
        }
        SideEffect {
            this@RouteActivity.navStack = backStack
            while (pendingIntents.isNotEmpty()) {
                handleIntent(pendingIntents.removeFirst())
            }
        }

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        // 空栈防线之第二道（首选防线）：只在栈内还有页面时才 pop。
                        // nav3 会按 `entries.size - previousEntries.size` 重复派发 onBack 来
                        // 对齐差值，手势状态过期时可能多投一次；不守卫的话这一次会把根页也弹掉
                        // ⇒ 空栈 ⇒ NavDisplay 抛异常（见上面 rememberNavBackStack 处的注释）。
                        // 根页返回维持系统默认语义 = 退出应用（此前由 back 处理器自行 finish）。
                        onBack = {
                            if (backStack.size > 1) backStack.removeLastOrNull()
                            else finish()
                        },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) }
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.AssistantBasic> { key ->
                                AssistantBasicPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingPreferences> {
                                SettingPreferencesPage()
                            }

                            entry<Screen.SettingPreferencesTheme> {
                                SettingPreferencesThemePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }
                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingKnowledgeBase> {
                                SettingKnowledgeBasePage()
                            }

                            entry<Screen.SettingKnowledgeBaseDetail> { key ->
                                SettingKnowledgeBaseDetailPage(key.kbId)
                            }

                            entry<Screen.AssistantKnowledgeBase> { key ->
                                val id = Uuid.parse(key.assistantId)
                                AssistantKnowledgeBasePage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }

                            entry<Screen.PromptInspect> { key ->
                                PromptInspectPage(conversationId = key.conversationId)
                            }

                            entry<Screen.Extensions> {
                                ExtensionsPage()
                            }

                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Worldbooks> {
                                PromptPage(initialWorldbooks = true)
                            }

                            entry<Screen.PromptEntryTarget> { key ->
                                PromptPage(
                                    initialWorldbooks = key.lorebook,
                                    initialContainerId = key.containerId,
                                    initialEntryId = key.entryId,
                                )
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.AgentRuns> {
                                AgentRunsPage()
                            }

                            entry<Screen.AgentRunDetail> { key ->
                                AgentRunDetailPage(runId = key.runId)
                            }

                            // 子代理预设（「按名称调度的模型 + 提示词」组合，设计 §十）——
                            // 与账本列表分开：那是"跑过的记录"，这是"可派的组合"
                            entry<Screen.SubagentPresets> {
                                SubagentPresetsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(key.id)
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }
                        }
                    )
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    /** 知识库管理（RAG-as-tool）：列表 + 详情（编辑/文档/索引） */
    @Serializable
    data object SettingKnowledgeBase : Screen

    @Serializable
    data class SettingKnowledgeBaseDetail(val kbId: String) : Screen

    /** 助手绑定知识库（决定 search_knowledge_base 工具检索哪些库） */
    @Serializable
    data class AssistantKnowledgeBase(val assistantId: String) : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    /** 拼装后提示词预览（只读，见 私有仓记录·前期调研 §6.7 ③）；带会话 id 故为 data class。 */
    @Serializable
    data class PromptInspect(val conversationId: String) : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    /**
     * Tier 4：从拼装预览的某个注入段跳到**产出它的条目**编辑处。
     *
     * 为什么单独一条路由而不是给 [Prompts] 加可空参数：`Screen` 是 Navigation 的类型化路由，
     * 给既有 route 加参数会改变其签名（影响深链与返回栈恢复）；独立路由让「带目标的跳转」
     * 与「普通进入」互不干扰，语义也更清楚。
     */
    @Serializable
    data class PromptEntryTarget(
        val containerId: String,
        val entryId: String,
        val lorebook: Boolean,
    ) : Screen

    @Serializable
    data object Worldbooks : Screen

    @Serializable
    data object Skills : Screen

    /** 无头 agent run 的账本列表（设计见 docs/design-subagent-context.md §9.3/9.4）。 */
    @Serializable
    data object AgentRuns : Screen

    /** 单个 run 的详情（状态 + 轨迹回放）。 */
    @Serializable
    data class AgentRunDetail(val runId: String) : Screen

    /** 子代理预设管理（「按名称调度的模型 + 提示词」组合，设计 §十）。 */
    @Serializable
    data object SubagentPresets : Screen


    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}
