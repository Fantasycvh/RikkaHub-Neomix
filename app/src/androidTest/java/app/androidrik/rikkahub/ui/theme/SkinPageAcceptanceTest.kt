package app.androidrik.rikkahub.ui.theme

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.*
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.*
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.di.*
import app.androidrik.rikkahub.ui.context.*
import app.androidrik.rikkahub.ui.hooks.*
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/** Opt-in real page matrix. Run with -PlongTextBaseline=shared; never production startup/data. */
@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
abstract class SkinPageAcceptanceProfile(private val fontScale: Float) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target,
            if (fontScale == 2f) SkinLargeFontTestHostActivity::class.java else SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    private lateinit var directory: File
    private lateinit var db: AppDatabase
    private lateinit var store: SettingsStore
    private lateinit var profile: RikkaHubApp
    private var webContentId = ""
    private var active = false
    private var workspaceId = ""
    private val chatId = Uuid.parse("00000000-0000-4000-8000-000000000042")

    @Before fun isolatedProfile() = runBlocking {
        Assume.assumeTrue("Use -PlongTextBaseline=shared", target.applicationContext.javaClass == Application::class.java)
        check(GlobalContext.getOrNull() == null)
        directory = File(target.cacheDir, "skin-acceptance-${Uuid.random()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = profile
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("skin-acceptance-${directory.name}-$name", mode)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        db.openHelper.writableDatabase.apply {
            execSQL("CREATE TABLE message_fts (text TEXT, node_id TEXT, message_id TEXT, conversation_id TEXT)")
            execSQL("CREATE TABLE message_fts_rowid (fts_rowid INTEGER PRIMARY KEY, conversation_id TEXT)")
        }
        // Attach the real Application type to private files without ever running production onCreate/boot.
        profile = RikkaHubApp()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true; invoke(profile, context)
        }
        active = true
        val koin = startKoin {
            androidContext(profile)
            modules(appModule, dataSourceModule, repositoryModule, viewModelModule, module {
                single<AppDatabase> { db }
                single { SettingsStore(context, get(), File(directory, "settings.preferences_pb")) }
                single { OkHttpClient.Builder().addInterceptor { throw IOException("Offline skin acceptance") }.build() }
            })
        }.koin
        active = true
        store = koin.get()
        withTimeout(10_000) { store.awaitLoaded() }
        val model = Model(modelId = "skin-offline-model", displayName = "Acceptance model · 超长模型名称用于布局验收")
        val provider = ProviderSetting.OpenAI(id = DEFAULT_PROVIDERS.first().id, name = "本地验收提供商",
            apiKey = "test-only", baseUrl = "https://invalid.example", models = listOf(model))
        store.update { Settings(dynamicColor = false, developerMode = true,
            providers = listOf(provider), chatModelId = model.id, fastModelId = model.id,
            promptPresets = listOf(PromptPreset(id = Uuid.parse("00000000-0000-4000-8000-000000000044"),
                name = "皮肤验收预设", entries = listOf(PromptInjection.ModeInjection(
                    id = Uuid.parse("00000000-0000-4000-8000-000000000045"), name = "皮肤验收条目", content = "本地静态内容")))),
            enableAutoTitle = false, enableSuggestion = false, legacyDefaultAssistantMerged = true) }
        withTimeout(10_000) { store.settingsFlow.first { !it.dynamicColor && it.developerMode } }
        koin.get<ConversationRepository>().insertConversation(Conversation.ofId(chatId, DEFAULT_ASSISTANT_ID,
            listOf(UIMessage.user("明暗皮肤与大字体验收").toMessageNode(),
                UIMessage.assistant("Skin acceptance\n\n正文应可读，设置与输入应可达。\n\n```kotlin\nval theme = \"skin\"\n```").toMessageNode())))
        db.knowledgeBaseDao().insertKnowledgeBase(KnowledgeBaseEntity("skin-kb", "皮肤验收知识库"))
        db.scheduledTaskDao().upsert(ScheduledTaskEntity(id = "skin-task", conversationId = chatId.toString(),
            title = "皮肤验收任务", prompt = "本地测试，不执行请求", scheduleType = "ONCE", schedulePayload = "{}",
            nextRunAt = Long.MAX_VALUE, missPolicy = "SKIP", idleOnly = true, enabled = false, createdAt = 1))
        db.agentRunDao().insertRun(AgentRunEntity(id = "skin-run", entryPoint = "subagent", status = "completed",
            profileAssistantId = DEFAULT_ASSISTANT_ID.toString(), profileName = "皮肤验收", modelId = null,
            modelName = null, goal = "本地静态验收", toolEnvelope = "[]", transcript = "[]",
            resultSummary = "验收示例", error = null, createdAt = 1, startedAt = 1, finishedAt = 2))
        db.mediaCreationDao().insertSession(MediaCreationSessionEntity("00000000-0000-4000-8000-000000000043", "皮肤验收媒体", "{}", 1, 1))
        workspaceId = koin.get<WorkspaceRepository>().create("皮肤验收工作区").id
        koin.get<WorkspaceRepository>().writeText(workspaceId, "acceptance.txt", "皮肤验收文件\n正文内容应可编辑。", true)
        koin.get<app.androidrik.rikkahub.data.files.SkillManager>().saveSkill("skin-acceptance",
            "---\nname: skin-acceptance\ndescription: 皮肤验收技能\n---\n# 皮肤验收\n本地静态内容。")
        val definition = app.androidrik.rikkahub.data.plugin.js.JsPluginDefinition(
            app.androidrik.rikkahub.data.plugin.js.JsPluginManifest("skin-plugin", "皮肤验收插件", "本地静态示例"),
            "function onOutput({text}) { return text; }")
        koin.get<app.androidrik.rikkahub.data.plugin.js.JsPluginStore>().save(listOf(definition))
        koin.get<app.androidrik.plugin.manager.PluginManager>().register(app.androidrik.rikkahub.data.plugin.js.JsPlugin(
            definition, profile, koin.get(), { false }, koin.get(), { false }))
        webContentId = app.androidrik.rikkahub.ui.components.webview.WebViewContentCache.store(context.cacheDir,
            "<html><head><meta name=\"rikka-app-skin\" content=\"v1\"><title>皮肤验收</title></head><body><h1>自有 HTML</h1><p>明暗主题与字号验收</p></body></html>")
        Unit
    }

    @After fun cleanup() {
        if (!active) return
        try {
            // Destroy roots and ViewModels before closing the process-global DI graph.
            compose.activityRule.scenario.close()
        } finally {
            GlobalContext.getOrNull()?.get<AppScope>()?.cancel()
            stopKoin()
            db.close()
            directory.deleteRecursively()
        }
    }

    protected fun matrix(dark: Boolean) {
        val cases = cases()
        val requested = args.getString("skinRoutes")?.split(',')?.toSet()
        require(requested == null || requested.all { name -> cases.any { it.first == name } })
        val mode = "${if (dark) "dark" else "light"}-${(fontScale * 100).toInt()}"
        val evidence = File(target.filesDir, "skin-acceptance/$mode").apply { mkdirs() }
        File(evidence, "results.json").delete()
        var page by mutableStateOf(cases.first { requested == null || it.first in requested })
        val rows = JSONArray()
        var observedScale = 0f
        var observedDark = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(androidx.compose.ui.unit.DpSize(360.dp, 720.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                    val configuredContext = LocalContext.current
                    val pageContext = remember(configuredContext) { object : ContextWrapper(configuredContext) {
                        override fun getApplicationContext(): Context = profile
                        override fun getFilesDir() = profile.filesDir
                        override fun getCacheDir() = profile.cacheDir
                        override fun getSharedPreferences(name: String, mode: Int) = profile.getSharedPreferences(name, mode)
                    } }
                    CompositionLocalProvider(LocalContext provides pageContext, LocalActivity provides compose.activity,
                        LocalActivityResultRegistryOwner provides compose.activity) {
                    RikkahubTheme(if (dark) ColorMode.DARK else ColorMode.LIGHT) {
                        observedScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                        observedDark = LocalDarkMode.current
                        val settings by rememberUserSettingsState()
                        val tts = rememberCustomTtsState()
                        val asr = rememberCustomAsrState()
                        SharedTransitionLayout {
                            val backStack = remember(page) { mutableStateListOf<androidx.navigation3.runtime.NavKey>(page.second) }
                            CompositionLocalProvider(LocalSettings provides settings,
                                LocalNavController provides Navigator(backStack), LocalSharedTransitionScope provides this,
                                LocalToaster provides rememberToasterState(), LocalTTSState provides tts, LocalASRState provides asr) {
                                Surface(Modifier.fillMaxSize()) {
                                    key(page.first) {
                                        NavDisplay(backStack, entryDecorators = listOf(
                                            rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                                            onBack = {}, entryProvider = appEntryProvider())
                                    }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(fontScale, observedScale, .001f); assertEquals(dark, observedDark) }
        for (case in cases.filter { requested == null || it.first in requested }) {
            instrumentation.sendStatus(0, Bundle().apply { putString("skinPage", "$mode/${case.first}") })
            compose.runOnIdle { page = case }
            compose.waitForIdle()
            // Allow asynchronous Room/DataStore page projections to arrive, using real time.
            Thread.sleep(150)
            compose.waitUntil(10_000) {
                compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                    androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            if (case.first == "Stats") {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(compose.activity.getString(R.string.stats_page_heatmap_title), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("Heatmap heading must remain readable beside its controls",
                    layouts.all { layout -> (0 until layout.lineCount).none { layout.isLineEllipsized(it) } })
            }
            val nodes = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("${case.first} must mount a real page", nodes.isNotEmpty())
            val clips = JSONArray()
            val ellipses = JSONArray()
            val seen = mutableSetOf<String>()
            var viewports = 0
            var stalled = 0
            var reachedEnd = false
            while (viewports < 40) {
                inspectText(clips, ellipses, seen)
                if (viewports == 0) screenshot(File(evidence, "${case.first}.png"))
                viewports++
                val scrollers = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange), useUnmergedTree = true)
                val scrollNodes = scrollers.fetchSemanticsNodes()
                val index = scrollNodes.indexOfFirst {
                    val range = it.config[SemanticsProperties.VerticalScrollAxisRange]
                    range.maxValue().isFinite() && range.value() < range.maxValue() - .01f
                }
                if (index < 0) { reachedEnd = true; break }
                val range = scrollNodes[index].config[SemanticsProperties.VerticalScrollAxisRange]
                val before = range.value()
                scrollers[index].performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, scrollNodes[index].size.height * .8f)
                }
                compose.waitForIdle()
                val after = scrollers[index].fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                // A first scroll can be fully consumed by the collapsing top bar.
                stalled = if (after == before) stalled + 1 else 0
                if (stalled >= 3) break
            }
            if (viewports > 1) screenshot(File(evidence, "${case.first}-end.png"))
            rows.put(JSONObject().put("route", case.first).put("clipped", clips).put("ellipsized", ellipses)
                .put("viewports", viewports).put("reachedEnd", reachedEnd))
            File(evidence, "results.json").writeText(rows.toString(2))
        }
        // Keep full-page diagnostics as evidence; intentional single-line regions are reviewed separately.
        assertEquals(cases.count { requested == null || it.first in requested }, rows.length())
        assertTrue("Review clipped text in $mode/results.json",
            (0 until rows.length()).all { rows.getJSONObject(it).getJSONArray("clipped").length() == 0 })
    }

    private fun screenshot(file: File) {
        val roots = compose.onAllNodes(isRoot())
        for (index in roots.fetchSemanticsNodes().indices) roots[index].captureToImage().asAndroidBitmap().let { bitmap ->
            val output = if (index == 0) file else File(file.parentFile, "${file.nameWithoutExtension}-root$index.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun inspectText(clips: JSONArray, ellipses: JSONArray, seen: MutableSet<String>) {
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val nodes = texts.fetchSemanticsNodes()
        for (i in nodes.indices) {
            // Editor viewports scroll by design; their laid-out text need not fit the visible field.
            if (nodes[i].config.contains(SemanticsProperties.EditableText)) continue
            val layouts = mutableListOf<TextLayoutResult>()
            texts[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            for (layout in layouts) {
                val widthOverflow = (0 until layout.lineCount).maxOfOrNull {
                    layout.getLineRight(it) - layout.getLineLeft(it) - layout.size.width
                } ?: 0f
                val heightOverflow = if (layout.lineCount > 0) layout.getLineBottom(layout.lineCount - 1) - layout.size.height else 0f
                // TextLayoutResult's strict floating-point comparison flags subpixel rounding as overflow.
                if (!layout.multiParagraph.didExceedMaxLines && widthOverflow <= 1f && heightOverflow <= 1f) continue
                val key = "${layout.layoutInput.text}/${layout.size}"
                if (!seen.add(key)) continue
                val row = JSONObject().put("text", layout.layoutInput.text.text.take(160))
                    .put("width", layout.size.width).put("height", layout.size.height)
                    .put("widthOverflowPx", widthOverflow).put("heightOverflowPx", heightOverflow)
                if (layout.layoutInput.overflow == TextOverflow.Ellipsis) ellipses.put(row) else clips.put(row)
            }
        }
    }

    private fun cases(): List<Pair<String, Screen>> {
        val assistant = DEFAULT_ASSISTANT_ID.toString()
        val provider = DEFAULT_PROVIDERS.first().id.toString()
        val service = app.androidrik.search.SearchServiceOptions.DEFAULT.id.toString()
        return listOf(
            "Chat" to Screen.Chat(chatId.toString()),
            "ShareHandler" to Screen.ShareHandler("Skin acceptance"),
            "History" to Screen.History,
            "Favorite" to Screen.Favorite,
            "Assistant" to Screen.Assistant,
            "AssistantDetail" to Screen.AssistantDetail(assistant),
            "AssistantBasic" to Screen.AssistantBasic(assistant),
            "AssistantPrompt" to Screen.AssistantPrompt(assistant),
            "AssistantMemory" to Screen.AssistantMemory(assistant),
            "AssistantRequest" to Screen.AssistantRequest(assistant),
            "AssistantMcp" to Screen.AssistantMcp(assistant),
            "AssistantLocalTool" to Screen.AssistantLocalTool(assistant),
            "AssistantImage" to Screen.AssistantImage(assistant),
            "AssistantInjections" to Screen.AssistantInjections(assistant),
            "Translator" to Screen.Translator,
            "Setting" to Screen.Setting,
            "Backup" to Screen.Backup,
            "BackupImportExport" to Screen.BackupImportExport,
            "BackupWebDav" to Screen.BackupWebDav,
            "BackupS3" to Screen.BackupS3,
            "BackupReminder" to Screen.BackupReminder,
            "BackupSnapshot" to Screen.BackupSnapshot,
            "ImageGen" to Screen.ImageGen,
            "MediaCreationSessions" to Screen.MediaCreationSessions,
            "MediaCreation" to Screen.MediaCreation("00000000-0000-4000-8000-000000000043"),
            "WebView" to Screen.WebView(contentId = webContentId),
            "SettingTheme" to Screen.SettingTheme,
            "SettingPreferences" to Screen.SettingPreferences,
            "SettingPreferencesTheme" to Screen.SettingPreferencesTheme,
            "SettingPreferencesNotification" to Screen.SettingPreferencesNotification,
            "SettingPreferencesGeneral" to Screen.SettingPreferencesGeneral,
            "SettingPreferencesUI" to Screen.SettingPreferencesUI,
            "SettingPreferencesAppIdentity" to Screen.SettingPreferencesAppIdentity,
            "SettingPreferencesNetwork" to Screen.SettingPreferencesNetwork,
            "SettingProvider" to Screen.SettingProvider,
            "SettingProviderDetail" to Screen.SettingProviderDetail(provider),
            "SettingModels" to Screen.SettingModels,
            "SettingAbout" to Screen.SettingAbout,
            "SettingSearch" to Screen.SettingSearch,
            "SettingGlobalSearch" to Screen.SettingGlobalSearch,
            "SettingKnowledgeBase" to Screen.SettingKnowledgeBase,
            "SettingKnowledgeBaseDetail" to Screen.SettingKnowledgeBaseDetail("skin-kb"),
            "AssistantKnowledgeBase" to Screen.AssistantKnowledgeBase(assistant),
            "SettingSearchDetail" to Screen.SettingSearchDetail(service),
            "SettingSpeech" to Screen.SettingSpeech,
            "SettingScheduledTasks" to Screen.SettingScheduledTasks,
            "SettingScheduledTaskDetail" to Screen.SettingScheduledTaskDetail("skin-task"),
            "SettingScheduledTaskRuns" to Screen.SettingScheduledTaskRuns("skin-task"),
            "SettingScheduledTaskCreate" to Screen.SettingScheduledTaskCreate(),
            "SettingMedia" to Screen.SettingMedia,
            "SettingMcp" to Screen.SettingMcp,
            "SettingFiles" to Screen.SettingFiles,
            "SettingStorage" to Screen.SettingStorage,
            "SettingWeb" to Screen.SettingWeb,
            "Debug" to Screen.Debug,
            "Log" to Screen.Log,
            "PromptInspect" to Screen.PromptInspect(chatId.toString()),
            "Extensions" to Screen.Extensions,
            "Plugins" to Screen.Plugins,
            "PluginDetail" to Screen.PluginDetail("skin-plugin"),
            "PluginEditor" to Screen.PluginEditor("skin-plugin"),
            "QuickMessages" to Screen.QuickMessages,
            "Prompts" to Screen.Prompts,
            "PromptEntryTarget" to Screen.PromptEntryTarget("00000000-0000-4000-8000-000000000044", "00000000-0000-4000-8000-000000000045", false),
            "Worldbooks" to Screen.Worldbooks,
            "Skills" to Screen.Skills,
            "AgentRuns" to Screen.AgentRuns,
            "TaskCenter" to Screen.TaskCenter,
            "AgentRunDetail" to Screen.AgentRunDetail("skin-run"),
            "SubagentPresets" to Screen.SubagentPresets,
            "Workspaces" to Screen.Workspaces,
            "WorkspaceDetail" to Screen.WorkspaceDetail(workspaceId),
            "WorkspaceTerminal" to Screen.WorkspaceTerminal(workspaceId),
            "WorkspaceSshEditor" to Screen.WorkspaceSshEditor(),
            "WorkspaceFileEditor" to Screen.WorkspaceFileEditor(workspaceId, "FILES", "acceptance.txt"),
            "SkillDetail" to Screen.SkillDetail("skin-acceptance"),
            "MessageSearch" to Screen.MessageSearch,
            "Stats" to Screen.Stats,
        )
    }
}

class SkinPageAcceptanceTest : SkinPageAcceptanceProfile(1f) {
    @Test fun light100() = matrix(false)
    @Test fun dark100() = matrix(true)
}

class SkinPageLargeFontAcceptanceTest : SkinPageAcceptanceProfile(2f) {
    @Test fun light200() = matrix(false)
    @Test fun dark200() = matrix(true)
}
