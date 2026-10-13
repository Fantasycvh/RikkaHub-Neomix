package app.androidrik.rikkahub.di

import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import android.content.Context
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FavoriteRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.data.repository.GenMediaRepository
import app.androidrik.rikkahub.data.repository.MemoryConsolidationRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.ProotBackgroundLauncher
import app.androidrik.workspace.ProotShellRunner
import app.androidrik.workspace.RootfsInstaller
import app.androidrik.workspace.WorkspaceBindMount
import app.androidrik.workspace.WorkspaceManager
import kotlinx.coroutines.flow.first
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        // 同一实例既做同步执行也做后台任务启动：预检/命令行构造只有一份实现，避免两处漂移
        val prootRunner = ProotShellRunner(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
        )
        WorkspaceManager(
            baseDir = WorkspaceBackupHelper.workspacesDir(context),
            shellRunner = prootRunner,
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.BUILTIN_SKILLS).apply { mkdirs() },
                    target = "/builtin_skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
            backgroundLauncher = ProotBackgroundLauncher(prootRunner),
            // 后台任务时长上限可配（Tier 1）：此单例首次注入发生在首次会话/工作区使用时，
            // DataStore 已加载完毕（dummy 值 = Settings 默认值 30，即使真提前读也回落到原行为）
            maxBackgroundTaskLifetimeMillis =
                get<SettingsStore>().settingsFlow.value.workspaceTaskMaxDurationMinutes * 60_000L,
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    /**
     * 做梦（V3）的数据面：门控、基线与进度都在这里（见 `docs/design/design-memory-consolidation.md` §3）。
     *
     * 两个 lambda 是刻意的：助手列表与「按 id 载会话」都按需取，避免把整个 Settings / Conversation
     * 仓库拖进构造，也让这一层在 JVM 单测里能替换成假数据。
     */
    single {
        val settingsStore: SettingsStore = get()
        val conversationRepository: ConversationRepository = get()
        MemoryConsolidationRepository(
            consolidationDAO = get(),
            assistants = { settingsStore.settingsFlow.first().assistants },
            conversationLoader = { id -> conversationRepository.getConversationById(id) },
        )
    }
}
