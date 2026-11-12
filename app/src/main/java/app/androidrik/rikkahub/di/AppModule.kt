package app.androidrik.rikkahub.di

import kotlinx.serialization.json.Json
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.account.ProviderAccountManager
import app.androidrik.rikkahub.data.perf.PerformanceSampler
import app.androidrik.rikkahub.data.sync.BackupRunCoordinator
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.image.ImageGenerationRunner
import app.androidrik.rikkahub.data.ai.tools.SubagentDispatcher
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.plugin.DataStorePluginStateStore
import app.androidrik.rikkahub.phoneuse.PhoneUseManager
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.plugin.manager.PluginStateStore
import app.androidrik.rikkahub.service.ChatFloatingTracker
import app.androidrik.rikkahub.ui.floating.ChatFloatingWindows
import app.androidrik.rikkahub.ui.floating.PerfFloatingWindows
import app.androidrik.rikkahub.service.ChatNotificationManager
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.MediaCreationService
import app.androidrik.rikkahub.service.MemoryTriggerScheduleManager
import app.androidrik.rikkahub.service.ScheduledTaskExecutor
import app.androidrik.rikkahub.service.ScheduledTaskRunner
import app.androidrik.rikkahub.service.ScheduledTaskSweeperKeeper
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.data.task.TaskCenter
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

    // Debug Mode 性能采样器：进程级单例，切到其他界面/离开 Debug 页后仍可继续采样（浮层用）。
    single { PerformanceSampler() }

    // 系统级性能浮层（其他 App 之上）：复用聊天浮窗的 SYSTEM_ALERT_WINDOW 权限与 FloatingX 设施。
    single {
        PerfFloatingWindows(
            context = get(),
            appScope = get(),
            sampler = get(),
        )
    }

    // 备份 / 恢复长任务的进程级执行器：任务挂在应用作用域，离开备份页/切后台后继续，
    // 并由 BackupForegroundService 提供前台优先级与进度通知。
    single {
        BackupRunCoordinator(
            context = get(),
            appScope = get(),
        )
    }

    // 无障碍服务可能在 startKoin 之前绑定，必须与静态单例共享同一个实例
    single {
        PhoneUseManager.instance
    }

    single {
        LocalTools(get(), get(), get(), get(), get(), get())
    }

    single {
        AppScope()
    }

    // 账号型提供商（ChatGPT/Grok）的登录、token 刷新与凭据持久化（P3 账号层）
    single {
        ProviderAccountManager(settingsStore = get(), appScope = get(), client = get())
    }

    // 插件状态:独立 DataStore(单 key JSON),与主设置解耦
    single<PluginStateStore> {
        DataStorePluginStateStore(get())
    }

    // 插件注册表:进程内单例,Application 启动时 start() 并由内置插件清单注册
    single {
        PluginManager(scope = get<AppScope>(), stateStore = get())
    }

    // JSON 规则插件:定义存储 + 安装器(URL 抓取/校验/物化注册)
    single {
        app.androidrik.rikkahub.data.plugin.rules.JsonRulePluginStore(get())
    }

    single {
        app.androidrik.rikkahub.data.plugin.rules.RulePluginInstaller(
            okHttpClient = get(),
            store = get(),
            manager = get(),
            revisionStore = get(),
        )
    }

    single {
        app.androidrik.rikkahub.data.plugin.revision.PluginRevisionInbox(
            context = get(),
            jsInstaller = get(),
            ruleInstaller = get(),
        )
    }

    // JS 沙箱插件:受控出站网关 + 定义存储 + 安装器
    single {
        // 显式按 OkHttpClient 解析:网关构造参数声明为 Call.Factory,
        // 直接 get() 会按 Call.Factory 查找而 Koin 里只有 OkHttpClient 定义(运行期才炸)
        app.androidrik.rikkahub.data.plugin.net.PluginHttpGateway(
            callFactory = get<okhttp3.OkHttpClient>(),
        )
    }

    single {
        app.androidrik.rikkahub.data.plugin.js.JsPluginStore(get())
    }

    single {
        app.androidrik.rikkahub.data.plugin.js.PluginKeyValueStore(context = get())
    }

    single {
        app.androidrik.rikkahub.data.plugin.revision.PluginRevisionStore(
            baseDir = get<android.content.Context>().filesDir,
            json = get(),
        )
    }

    single<app.androidrik.rikkahub.data.plugin.revision.JsSyntaxChecker> {
        app.androidrik.rikkahub.data.plugin.revision.WebViewJsSyntaxChecker(context = get())
    }

    single {
        app.androidrik.rikkahub.data.plugin.revision.PluginPrecheck(
            syntaxChecker = get(),
            // R1：跨段重名对照集留空（JS 工具名经 pluginToolName 命名空间化，跨源重名
            // 结构性不可能）；将来接入受信任 Provider 时改从装配面快照取
            existingToolNames = { emptySet() },
        )
    }

    single {
        app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller(
            context = get(),
            okHttpClient = get(),
            store = get(),
            manager = get(),
            gateway = get(),
            keyValueStore = get(),
            precheck = get(),
            revisionStore = get(),
        )
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        SoundEffectPlayer(get())
    }

    single {
        WorkspaceTerminalSessionManager(get(), get(), get())
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

    // ChatService 只在后台才用到，延迟创建
    single {
        ChatFloatingTracker(
            appScope = get(),
            lazyChatService = lazy { get() },
            settingsStore = get(),
        )
    }

    // 同上，进程启动即开始监听前后台切换
    single(createdAtStart = true) {
        ChatFloatingWindows(
            context = get(),
            appScope = get(),
            tracker = get(),
            phoneUseManager = get(),
            lazyChatService = lazy { get() },
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
            filesDirPath = get<android.content.Context>().filesDir.absolutePath,
            memoryRepository = get(),
            conversationRepository = get(),
            localTools = get(),
            mcpManager = get(),
            skillManager = get(),
            ragRepository = get(),
            workspaceRepository = get(),
            pluginManager = get(),
            imageGenerationRunner = get(),
            // **延迟解析**：SubagentRunner 依赖本工厂（它要用装配点装配工具），
            // 直接在此 `get()` 会构成构造环。lambda 体在装配工具时才执行 ⇒ 环断在这里。
            // 依赖窄接口而非具体 Runner，见下方 single<SubagentDispatcher> 的绑定。
            subagentDispatcher = { get<SubagentDispatcher>() },
            memoryFiles = get(),
        )
    }

    // 窄接口绑定：解析出的就是同一个 SubagentRunner 单例（不新建实例）
    single<SubagentDispatcher> { get<SubagentRunner>() }
    single { app.androidrik.rikkahub.service.TaskHandoffRegistry() }

    single {
        ChatService(
            database = get(),
            context = get(),
            appScope = get(),
            appEventBus = get(),
            settingsStore = get(),
            conversationRepo = get(),
            memoryRepository = get(),
            generationHandler = get(),
            templateTransformer = get(),
            pluginManager = get(),
            providerManager = get(),
            chatToolFactory = get(),
            mcpManager = get(),
            filesManager = get(),
            workspaceRepository = get(),
            folderRepository = get(),
            usageRecorder = get(),
            taskHandoffs = get(),
            memoryFiles = get(),
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
            workspaceRepository = get(),
            taskHandoffs = get(),
        )
    }

    // 定时任务执行层：只做「注入 + 等结束 + 报结果」，生成本体走 ChatService 老管线。
    // settingsStore 用于冷启动等设置落盘（Settings.dummy() 陷阱，见 Runner KDoc）。
    // runRegistry 是 P0.5 定时取消用的进程内注册表（FGS 通知「停止」靠 runId 反查 Job）。
    single {
        ScheduledRunRegistry()
    }

    single {
        ScheduledTaskRunner(
            context = get(),
            chatService = get(),
            conversationRepository = get(),
            taskRepository = get(),
            settingsStore = get(),
            workspaceRepository = get(),
            runRegistry = get(),
            subagentRunner = get(),
        )
    }

    single {
        ScheduledTaskSweeperKeeper(context = get())
    }

    single {
        MemoryTriggerScheduleManager(
            context = get(),
            settingsStore = get(),
            scheduler = get(),
            appScope = get(),
        )
    }

    // 任务中心读模型（P0）：三套底座只读聚合 + 取消转发；不持有并发准入。
    single {
        TaskCenter(
            agentRunDAO = get(),
            workspaceRepository = get(),
            scheduledTaskRepository = get(),
            scheduledRunRegistry = get(),
            subagentRunner = get(),
        )
    }

    // 三个触发入口（闹钟 / 一次性唤醒作业 / 15 分钟 sweep）共用同一个执行器：
    // 「查到期 → idle 门控 → 认领 → 执行 → 重排」只此一份，入口只负责把自己叫醒。
    single {
        ScheduledTaskExecutor(
            chatService = get(),
            taskRepository = get(),
            runner = get(),
            keeper = get(),
        )
    }

    // 媒体创作的生成任务：每条记录一个协程，挂在应用级作用域上，离开页面后继续运行。
    // 需要公网素材地址的接口走 RemoteFileStore 上传到素材桶；未配置时服务内明确失败。
    single {
        MediaCreationService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            repository = get(),
            manager = get(),
            remoteFileStore = get(),
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
