package app.androidrik.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import kotlinx.serialization.json.Json
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.ai.tools.SubagentDispatcher
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.service.ChatNotificationManager
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.MediaCreationService
import app.androidrik.rikkahub.service.SubagentRunner
import app.androidrik.rikkahub.utils.EmojiData
import app.androidrik.rikkahub.utils.EmojiUtils
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.SoundEffectPlayer
import app.androidrik.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalSessionManager
import app.androidrik.rikkahub.web.WebServerManager
import app.androidrik.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        AppEventBus()
    }

    single {
        LocalTools(get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.analytics
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get())
    }

    // 生成通知与业务解耦：ChatService 只发事件，通知由这里消费；
    // createdAtStart 保证进程启动即订阅，否则后台生成的事件会因无订阅者而丢失
    single(createdAtStart = true) {
        ChatNotificationManager(
            context = get(),
            appScope = get(),
            eventBus = get(),
            settingsStore = get(),
        )
    }

    // 生图产物的唯一写路径：会话内 generate_image 工具与生图页共用
    // （生图页的 ImgGenVM 由 viewModelOf 构造，Koin 自动注入本 single）
    single {
        ImageGenerationRunner(
            providerManager = get(),
            filesManager = get(),
            genMediaRepository = get(),
        )
    }

    single {
        ChatToolFactory(
            json = get(),
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            ragRepository = get(),
            workspaceRepository = get(),
            imageGenerationRunner = get(),
            // **延迟解析**：SubagentRunner 依赖本工厂（它要用装配点装配工具），
            // 直接在此 `get()` 会构成构造环。lambda 体在装配工具时才执行 ⇒ 环断在这里。
            // 依赖窄接口而非具体 Runner，见下方 single<SubagentDispatcher> 的绑定。
            subagentDispatcher = { get<SubagentDispatcher>() },
        )
    }

    // 窄接口绑定：解析出的就是同一个 SubagentRunner 单例（不新建实例）
    single<SubagentDispatcher> { get<SubagentRunner>() }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get()
        )
    }

    single {
        SubagentRunner(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            generationHandler = get(),
            chatToolFactory = get(),
            agentRunDAO = get(),
        )
    }

    // 媒体创作的生成任务：每条记录一个协程，挂在应用级作用域上，离开页面后继续运行。
    // 本仓库没有 RemoteFileStore，因此不注册它；需要公网素材地址的请求在服务内明确失败。
    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            okHttpClient = get(),
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            folderRepo = get(),
            settingsStore = get(),
            filesManager = get()
        )
    }
}
