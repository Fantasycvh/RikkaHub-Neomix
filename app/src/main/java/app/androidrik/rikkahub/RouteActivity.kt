package app.androidrik.rikkahub

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import app.androidrik.rikkahub.ui.pages.share.handler.readSharedUris
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker
import app.androidrik.rikkahub.data.db.MigrationState
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.repository.MediaCreationRepository
import app.androidrik.rikkahub.service.MediaCreationForegroundService
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
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreRestartGate
import app.androidrik.rikkahub.ui.pages.backup.components.SearchIndexRebuildBanner
import app.androidrik.rikkahub.ui.pages.debug.PerfOverlay
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.utils.CrashHandler
import app.androidrik.rikkahub.utils.LauncherAlias
import app.androidrik.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

import androidx.core.net.toUri
import androidx.navigation3.runtime.NavEntry
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunDetailPage
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunsPage
import app.androidrik.rikkahub.ui.pages.assistant.AssistantPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantBasicPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantImagePage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantKnowledgeBasePage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import app.androidrik.rikkahub.ui.pages.backup.BackupImportExportPage
import app.androidrik.rikkahub.ui.pages.backup.BackupPage
import app.androidrik.rikkahub.ui.pages.backup.BackupReminderPage
import app.androidrik.rikkahub.ui.pages.backup.BackupS3Page
import app.androidrik.rikkahub.ui.pages.backup.BackupSnapshotPage
import app.androidrik.rikkahub.ui.pages.backup.BackupWebDavPage
import app.androidrik.rikkahub.ui.pages.chat.ChatPage
import app.androidrik.rikkahub.ui.pages.debug.DebugPage
import app.androidrik.rikkahub.ui.pages.extensions.ExtensionsPage
import app.androidrik.rikkahub.ui.pages.extensions.PromptPage
import app.androidrik.rikkahub.ui.pages.extensions.QuickMessagesPage
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillsPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceSshEditorPage
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import app.androidrik.rikkahub.ui.pages.favorite.FavoritePage
import app.androidrik.rikkahub.ui.pages.history.HistoryPage
import app.androidrik.rikkahub.ui.pages.imggen.ImageGenPage
import app.androidrik.rikkahub.ui.pages.log.LogPage
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaCreationPage
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaCreationSessionsPage
import app.androidrik.rikkahub.ui.pages.plugins.PluginDetailPage
import app.androidrik.rikkahub.ui.pages.plugins.PluginEditorPage
import app.androidrik.rikkahub.ui.pages.plugins.PluginsPage
import app.androidrik.rikkahub.ui.pages.promptinspect.PromptInspectPage
import app.androidrik.rikkahub.ui.pages.search.SearchPage
import app.androidrik.rikkahub.ui.pages.setting.SettingAboutPage
import app.androidrik.rikkahub.ui.pages.setting.SettingFilesPage
import app.androidrik.rikkahub.ui.pages.setting.SettingKnowledgeBaseDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingKnowledgeBasePage
import app.androidrik.rikkahub.ui.pages.setting.SettingMcpPage
import app.androidrik.rikkahub.ui.pages.setting.SettingMediaPage
import app.androidrik.rikkahub.ui.pages.setting.SettingModelPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesAppIdentityPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesPage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import app.androidrik.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import app.androidrik.rikkahub.ui.pages.setting.SettingProviderDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingProviderPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSearchDetailPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSearchPage
import app.androidrik.rikkahub.ui.pages.setting.SettingSpeechPage
import app.androidrik.rikkahub.ui.pages.setting.SettingStoragePage
import app.androidrik.rikkahub.ui.pages.setting.SettingThemePage
import app.androidrik.rikkahub.ui.pages.setting.SettingWebPage
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTaskCreatePage
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTaskDetailPage
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTaskRunsPage
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTasksPage
import app.androidrik.rikkahub.ui.pages.setting.search.SettingGlobalSearchPage
import app.androidrik.rikkahub.ui.pages.share.handler.ShareHandlerPage
import app.androidrik.rikkahub.ui.pages.stats.StatsPage
import app.androidrik.rikkahub.ui.pages.subagent.SubagentPresetsPage
import app.androidrik.rikkahub.ui.pages.taskcenter.TaskCenterPage
import app.androidrik.rikkahub.ui.pages.translator.TranslatorPage
import app.androidrik.rikkahub.ui.pages.webview.WebViewPage
import app.androidrik.workspace.WorkspaceStorageArea
private const val TAG = "RouteActivity"
private const val ACTION_TRANSLATE = "app.androidrik.rikkahub.action.TRANSLATE"
private const val ACTION_IMAGE_GEN = "app.androidrik.rikkahub.action.IMAGE_GEN"
private const val ACTION_MEDIA_CREATION = "app.androidrik.rikkahub.action.MEDIA_CREATION"
private const val EXTRA_CONVERSATION_ID = "conversationId"

/** 打开指定会话的聊天页 */
fun conversationIntent(context: Context, conversationId: String): Intent =
    Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }

open class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val mediaCreationRepository by inject<MediaCreationRepository>()
    private var navStack: MutableList<NavKey>? = null

    /**
     * ADB inbox 快速通道（R3，设计稿 §5.1）：前台恢复时扫描一次
     * `files/plugin_revisions/inbox/`；仅 debug 构建编译进包（release 无此代码路径）。
     * 必须先 [RikkaHubApp.awaitBootReady]——boot 异步在 IO 上执行，onResume 可能抢在
     * startKoin 之前（R3 真机实证：早期 inject 对未启动的空根作用域直接抛）。
     */
    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) {
            lifecycleScope.launch {
                val app = application as? RikkaHubApp ?: return@launch
                if (!app.awaitBootReady()) return@launch // 本轮放弃，下次 onResume 重扫
                val inbox = org.koin.core.context.GlobalContext.get()
                    .get<app.androidrik.rikkahub.data.plugin.revision.PluginRevisionInbox>()
                runCatching { inbox.scanAndProcess() }
                    .onFailure { android.util.Log.w("PluginRevisionInbox", "scan failed", it) }
            }
        }
    }

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
            // B 档备份：非 Ready 全屏阻塞——换库发生在 startKoin 之前，Ready 即
            // 「换库 + Koin + Room 打开确认都成功」；内容页在此前不渲染（防 DAO
            // 访问落在换库中途/已失败的库上）。
            // **gate 屏不能包 RikkahubTheme**：主题内部 koinInject(SettingsStore)，
            // Koin 未启动时合成即崩（真机踩过）；只有 Ready 分支进 Koin 主题。
            val startupApp = LocalContext.current.applicationContext as RikkaHubApp
            val startupState by startupApp.startupState.collectAsState()
            when (val state = startupState) {
                // Ready 之后再套「恢复后强制重启」闸门：恢复成功只把换库 stage 起来，
                // 本进程不重启就会继续在旧库上写、写入在下次启动被覆盖（审计 #16）。
                // 闸门放根 UI 外层，用户离开备份页也无法绕过。
                StartupState.Ready -> RikkahubTheme {
                    RestoreRestartGate { SearchIndexRebuildBanner { AppStartupGateContent(okHttpClient) { AppRoutes() } } }
                    // 降级告知 / 恢复提示（design-db-downgrade-rebuild.md §4.5/§5）
                    DowngradeDialogs(startupApp)
                }
                StartupState.Restoring -> StartupBlockingScreen()
                is StartupState.Failed -> StartupFailedScreen(state) { startupApp.retryPendingRestore() }
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    /**
     * 统一 Intent 入口（对齐上游 513b784c）：冷启动/onNewIntent 都走这里；
     * 导航栈未就绪时排队（AppRoutes SideEffect flush）；末尾去重防连点。
     * fork 适配：单项/多项分享统一合并 EXTRA_STREAM 和 ClipData，保留相机 URI 字符串兼容；
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
            ACTION_MEDIA_CREATION -> {
                openNewMediaCreation(backStack)
                null
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().normalizeNewlines(),
                streamUris = intent.readSharedUris(),
            )
            Intent.ACTION_PROCESS_TEXT -> Screen.ShareHandler(
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty().normalizeNewlines(),
            )
            // 子代理审批通知点进来 → 运行详情页（那里有入参全文与批准/拒绝卡片）；
            // 媒体创作通知带着会话 ID，点开后直接进入那个会话；
            // 否则沿用会话 id 的老路径
            else -> mediaCreationDestination(intent)
                ?: intent.getStringExtra("runId")?.let { Screen.AgentRunDetail(it) }
                ?: intent.getStringExtra(EXTRA_CONVERSATION_ID)?.let { Screen.Chat(it) }
        }
        if (destination != null && backStack.lastOrNull() != destination) {
            backStack.add(destination)
        }
    }

    // 快捷方式没有指定会话，打开一个空会话
    private fun openNewMediaCreation(backStack: MutableList<NavKey>) {
        lifecycleScope.launch {
            val destination = Screen.MediaCreation(sessionId = mediaCreationRepository.newSession().id.toString())
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
    }

    // 媒体创作的通知带着会话 ID，点击后直接打开那个会话
    private fun mediaCreationDestination(intent: Intent): Screen? {
        val sessionId = intent.getStringExtra(MediaCreationForegroundService.EXTRA_MEDIA_SESSION_ID) ?: return null
        return runCatching { Uuid.parse(sessionId) }.getOrNull()
            ?.let { Screen.MediaCreation(sessionId = it.toString()) }
    }

    // F6：分享/划词文本换行归一（Windows CRLF 残留防护，turbo 3652fd6 语义）
    private fun String.normalizeNewlines(): String =
        replace("\r\n", "\n").replace('\r', '\n')

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        // 最近任务窗口标题跟随自定义应用名（桌面 label 不归它管，走 activity-alias）
        val appDisplayName = settings.displaySetting.customAppName.ifBlank { getString(R.string.app_name) }
        LaunchedEffect(appDisplayName) {
            setTaskDescription(ActivityManager.TaskDescription(appDisplayName))
        }
        // activity-alias（桌面名称/图标预置）切换必须在**后台**执行：Android 15 起系统
        // PackageManagerService 会直接抛异常拒绝前台应用禁用自己的 launcher 入口
        //（实测崩溃：RemoteException from setEnabledSettings）。设置页只写偏好，
        // 这里在 ON_STOP（应用离开前台）时对齐系统组件状态；上一次前台切换失败
        // 留下的不一致也会在此自愈。
        DisposableEffect(Unit) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    Log.d(TAG, "ON_STOP: applying alias preset")
                    runCatching {
                        // 幂等对齐（跳过状态一致的组件）：顺带清理前台切换失败
                        // 可能留下的「多个别名同时启用」脏状态
                        LauncherAlias.apply(
                            applicationContext,
                            settingsStore.settingsFlow.value.displaySetting.launcherAliasId
                                .ifBlank { LauncherAlias.DEFAULT_ID },
                        )
                    }.onFailure { Log.e(TAG, "alias apply on ON_STOP failed", it) }
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
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
                        entryProvider = appEntryProvider(),
                    )
                    // 性能采样浮层：覆盖所有 App 页面（Debug 页自身除外，避免与 Perf 页重复），
                    // 采样进行中才显示，点 × 原地停止。
                    PerfOverlay(
                        visible = backStack.lastOrNull() !is Screen.Debug,
                        modifier = Modifier.align(Alignment.TopEnd),
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
                                CircularWavyProgressIndicator()
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
