package app.androidrik.rikkahub.di

import app.androidrik.rikkahub.ui.pages.assistant.AssistantVM
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantDetailVM
import app.androidrik.rikkahub.ui.pages.assistant.detail.AssistantKnowledgeBaseVM
import app.androidrik.rikkahub.ui.pages.backup.BackupVM
import app.androidrik.rikkahub.ui.pages.chat.ChatDrawerVM
import app.androidrik.rikkahub.ui.pages.chat.ChatVM
import app.androidrik.rikkahub.ui.pages.debug.DebugVM
import app.androidrik.rikkahub.ui.pages.favorite.FavoriteVM
import app.androidrik.rikkahub.ui.pages.search.SearchVM
import app.androidrik.rikkahub.ui.pages.history.HistoryVM
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunsVM
import app.androidrik.rikkahub.ui.pages.agentrun.AgentRunDetailVM
import app.androidrik.rikkahub.ui.pages.subagent.SubagentPresetsVM
import app.androidrik.rikkahub.ui.pages.stats.StatsVM
import app.androidrik.rikkahub.ui.pages.imggen.ImgGenVM
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaCreationSessionsVM
import app.androidrik.rikkahub.ui.pages.mediacreation.MediaCreationVM
import app.androidrik.rikkahub.ui.pages.promptinspect.PromptInspectVM
import app.androidrik.rikkahub.ui.pages.extensions.PromptVM
import app.androidrik.rikkahub.ui.pages.extensions.QuickMessagesVM
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillDetailVM
import app.androidrik.rikkahub.ui.pages.extensions.skills.SkillsVM
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailVM
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceSshEditorVM
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceVM
import app.androidrik.rikkahub.ui.pages.setting.KnowledgeBaseDetailVM
import app.androidrik.rikkahub.ui.pages.setting.KnowledgeBasesVM
import app.androidrik.rikkahub.ui.pages.setting.SettingVM
import app.androidrik.rikkahub.ui.pages.setting.StorageVM
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTaskDetailVM
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTasksVM
import app.androidrik.rikkahub.ui.pages.share.handler.ShareHandlerVM
import app.androidrik.rikkahub.ui.pages.translator.TranslatorVM
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val viewModelModule = module {
    viewModel<ChatVM> { params ->
        ChatVM(
            id = params.get(),
            context = get(),
            settingsStore = get(),
            conversationRepo = get(),
            chatService = get(),
            filesManager = get(),
            favoriteRepository = get(),
        )
    }
    viewModelOf(::ChatDrawerVM)
    viewModelOf(::SettingVM)
    viewModelOf(::StorageVM)
    viewModelOf(::ScheduledTasksVM)
    viewModel { params ->
        ScheduledTaskDetailVM(
            taskId = params.get(),
            taskRepository = get(),
            conversationRepository = get(),
            sweeperKeeper = get(),
        )
    }
    viewModelOf(::KnowledgeBasesVM)
    viewModel<KnowledgeBaseDetailVM> { params ->
        KnowledgeBaseDetailVM(
            kbId = params.get(),
            ragRepository = get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::DebugVM)
    viewModelOf(::HistoryVM)
    viewModelOf(::AgentRunsVM)
    viewModelOf(::AgentRunDetailVM)
    viewModelOf(::SubagentPresetsVM)
    viewModelOf(::AssistantVM)
    viewModel<AssistantKnowledgeBaseVM> { params ->
        AssistantKnowledgeBaseVM(
            assistantId = params.get(),
            settingsStore = get(),
            ragRepository = get(),
        )
    }
    viewModel<AssistantDetailVM> {
        AssistantDetailVM(
            id = it.get(),
            settingsStore = get(),
            memoryRepository = get(),
            filesManager = get(),
            skillManager = get(),
            workspaceRepository = get(),
            consolidationScheduler = get(),
            chatService = get(),
        )
    }
    viewModelOf(::TranslatorVM)
    viewModel<ShareHandlerVM> {
        ShareHandlerVM(
            text = it.get(),
            settingsStore = get(),
        )
    }
    viewModelOf(::BackupVM)
    viewModelOf(::ImgGenVM)
    viewModelOf(::MediaCreationSessionsVM)
    viewModel<MediaCreationVM> {
        MediaCreationVM(
            id = it.get(),
            context = get(),
            settingsStore = get(),
            repository = get(),
            service = get(),
            remoteFileStore = get(),
            appScope = get(),
        )
    }
    viewModelOf(::PromptInspectVM)
    viewModelOf(::PromptVM)
    viewModelOf(::QuickMessagesVM)
    viewModelOf(::SkillsVM)
    viewModelOf(::SkillDetailVM)
    viewModelOf(::WorkspaceVM)
    viewModel<WorkspaceDetailVM> {
        WorkspaceDetailVM(
            id = it.get(),
            repository = get(),
            terminalSessionManager = get(),
        )
    }
    viewModel<WorkspaceSshEditorVM> { params ->
        WorkspaceSshEditorVM(
            // 新建工作区时没有 id，Koin 的参数表里找不到这个 String
            id = params.getOrNull<String>(),
            repository = get(),
        )
    }
    viewModelOf(::FavoriteVM)
    viewModelOf(::SearchVM)
    viewModelOf(::StatsVM)
}
