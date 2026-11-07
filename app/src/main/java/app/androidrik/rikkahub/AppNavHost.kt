package app.androidrik.rikkahub

// 导航路由表（批次 3）：~80 个 entry 整块迁移，metadata/popTransitionSpec 原样保留，
// 与 Activity 字段零耦合。调用点：RouteActivity.AppRoutes 的 NavDisplay 参数。

import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.core.net.toUri
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
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
import kotlin.uuid.Uuid
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavEntry

/** 全部导航 entry（NavDisplay 的 entryProvider 参数）。 */
internal fun appEntryProvider(): (NavKey) -> NavEntry<NavKey> = entryProvider {
                    entry<Screen.Chat>(
    metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
        + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                    ) { key ->
    ChatPage(
        id = Uuid.parse(key.id),
        text = key.text,
        files = key.files.map { it.toUri() },
        nodeId = key.nodeId?.let { Uuid.parse(it) },
    )
                    }

                    entry<Screen.ShareHandler> { key ->
    ShareHandlerPage(
        text = key.text,
        files = (listOfNotNull(key.streamUri) + key.streamUris).distinct()
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
                    entry<Screen.AssistantImage> { key ->
    AssistantImagePage(key.id)
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

                    // 备份二级入口页里的具体功能页（三级）：原来的顶栏 tab 拆成独立路由。
                    entry<Screen.BackupImportExport> {
    BackupImportExportPage()
                    }

                    entry<Screen.BackupWebDav> {
    BackupWebDavPage()
                    }

                    entry<Screen.BackupS3> {
    BackupS3Page()
                    }

                    entry<Screen.BackupReminder> {
    BackupReminderPage()
                    }

                    entry<Screen.BackupSnapshot> {
    BackupSnapshotPage()
                    }

                    entry<Screen.ImageGen> {
    ImageGenPage()
                    }

                    entry<Screen.MediaCreationSessions> {
    MediaCreationSessionsPage()
                    }

                    entry<Screen.MediaCreation> { key ->
    MediaCreationPage(key.sessionId)
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

                    entry<Screen.SettingPreferencesAppIdentity> {
    SettingPreferencesAppIdentityPage()
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

                    entry<Screen.SettingGlobalSearch> {
    SettingGlobalSearchPage()
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

                    entry<Screen.SettingScheduledTasks> {
    ScheduledTasksPage()
                    }

                    entry<Screen.SettingScheduledTaskDetail> { key ->
    ScheduledTaskDetailPage(taskId = key.taskId)
                    }
                    entry<Screen.SettingScheduledTaskRuns> { key ->
    ScheduledTaskRunsPage(taskId = key.taskId)
                    }
                    entry<Screen.SettingScheduledTaskCreate> { key ->
    ScheduledTaskCreatePage(prefillConversationId = key.conversationId)
                    }

                    entry<Screen.SettingMedia> {
    SettingMediaPage()
                    }

                    entry<Screen.SettingMcp> {
    SettingMcpPage()
                    }

                    entry<Screen.SettingFiles> {
    SettingFilesPage()
                    }

                    entry<Screen.SettingStorage> {
    SettingStoragePage()
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

                    entry<Screen.Plugins> {
    PluginsPage()
                    }

                    entry<Screen.PluginDetail> { key ->
    PluginDetailPage(pluginId = key.pluginId)
                    }

                    entry<Screen.PluginEditor> { key ->
    PluginEditorPage(pluginId = key.pluginId)
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

                    // 任务中心（P0）：三段合一的只读聚合 + 取消/批准入口
                    entry<Screen.TaskCenter> {
    TaskCenterPage()
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

                    entry<Screen.WorkspaceSshEditor> { key ->
    WorkspaceSshEditorPage(key.id)
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
