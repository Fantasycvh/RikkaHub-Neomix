package app.androidrik.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.common.android.Logging
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import app.androidrik.rikkahub.data.files.EXPORT_TEMP_PREFIX
import app.androidrik.rikkahub.data.files.exportRetentionCutoff
import app.androidrik.rikkahub.data.files.isExportExpired
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.sync.PendingRestoreStore
import com.whl.quickjs.android.QuickJSLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import android.database.sqlite.SQLiteDatabase
import app.androidrik.rikkahub.di.appModule
import app.androidrik.rikkahub.di.dataSourceModule
import app.androidrik.rikkahub.di.repositoryModule
import app.androidrik.rikkahub.di.viewModelModule
import app.androidrik.rikkahub.data.ai.MemoryConsolidationRuntime
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.files.AvatarMigration
import app.androidrik.rikkahub.data.files.BackgroundsMigration
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.storage.StorageCategoryId
import app.androidrik.rikkahub.data.storage.StorageUsageManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.plugin.installBuiltInPlugins
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.service.MediaCreationService
import app.androidrik.rikkahub.service.WebServerService
import app.androidrik.rikkahub.service.KeepAliveService
import app.androidrik.rikkahub.service.ScheduledTaskSweeperKeeper
import app.androidrik.rikkahub.utils.CrashHandler
import app.androidrik.rikkahub.utils.DatabaseUtil
import app.androidrik.rikkahub.utils.TokenCountDiskCache
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import app.androidrik.common.tokenizer.TokenizerRegistry
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

/** isolatedProcess 里的 Service（uid 99000+ 段，无大多系统服务）识别 */
private fun isIsolatedProcess(): Boolean =
    android.os.Process.myUid() >= 99000

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"
const val KEEP_ALIVE_NOTIFICATION_CHANNEL_ID = "keep_alive"
const val MEDIA_CREATION_NOTIFICATION_CHANNEL_ID = "media_creation"

/**
 * 子代理「需要批准」通知渠道（设计 §十一）。
 *
 * IMPORTANCE_HIGH：这是**会阻塞一个正在跑的 run** 的请求，必须能弹出来（否则 run 只能等到超时）。
 */
const val SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID = "subagent_approval"

/**
 * B 档备份恢复的启动期状态（design-backup-staged-restore §4）。
 *
 * 初值恒为 [Restoring]：RouteActivity 在非 Ready 时全屏阻塞，内容页（及其 ViewModel
 * 的 Koin 解析/DAO 访问）不得提前开始——换库发生在 startKoin 之前，Ready 即
 * 「换库 + Koin 都已成功」。[Failed] 的 canRetry 只在 Koin 尚未启动时为 true。
 */
sealed interface StartupState {
    data object Restoring : StartupState
    data object Ready : StartupState
    data class Failed(val message: String, val canRetry: Boolean) : StartupState
}

class RikkaHubApp : Application() {
    private val startupScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("Startup")
    )
    private val _startupState = MutableStateFlow<StartupState>(StartupState.Restoring)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    // boot 重入闸：Ready/不可重试时拒绝，防止重试按钮连点造成 startKoin 二次启动
    private val startupRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // 隔离进程（TexCompileService，TikZ native 编译）跳过
        // 全部应用初始化：isolatedProcess 无大部分系统服务（WorkManager 依赖的
        // JobScheduler 为 null 直接崩溃），Koin/DB/WorkManager 与编译无关
        if (isIsolatedProcess()) return

        // 这些步骤不依赖 Koin，且 Failed 态下也应当就绪（崩溃处理器/通知渠道）
        createNotificationChannel()
        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)
        // install crash handler
        CrashHandler.install(this)
        // Init QuickJS native library
        QuickJSLoader.init()

        boot()
    }

    /**
     * 启动编排：换库（如有）→ startKoin → Room 打开确认 → Ready → 其余初始化。
     *
     * **startKoin 被推迟到换库成功之后**——换库失败时 Koin/Room/后台写者都不存在，
     * fail-closed 的重试才是无损的（设计稿 §1 的动机）。
     */
    fun boot(): Boolean {
        if (!startupRunning.compareAndSet(false, true)) return false
        startupScope.launch {
            try {
                doBoot()
            } finally {
                startupRunning.set(false)
            }
        }
        return true
    }

    private suspend fun doBoot() {
        val dbFile = getDatabasePath("rikka_hub")
        val applied = runCatching {
            PendingRestoreStore.applyIfPending(filesDir, dbFile, ::validateStagedDatabase)
        }.getOrElse { e ->
            Log.e(TAG, "PendingRestoreStore.applyIfPending failed", e)
            _startupState.value = StartupState.Failed(e.message ?: "Restore failed", canRetry = true)
            return
        }
        if (!applied) {
            // 上次启动换库成功但没活着确认（Room 打开失败/进程早死）：自动回滚
            runCatching { PendingRestoreStore.rollbackIfUnconfirmed(filesDir, dbFile) }
                .onFailure { Log.e(TAG, "rollbackIfUnconfirmed failed", it) }
        }
        runCatching {
            ensureKoinStarted()
        }.getOrElse { e ->
            Log.e(TAG, "startKoin failed", e)
            _startupState.value = StartupState.Failed(e.message ?: "Init failed", canRetry = false)
            return
        }
        if (PendingRestoreStore.hasApplied(filesDir)) {
            // 用 Koin 里的 AppDatabase 强制打开（含迁移链）：SELECT 1 通过才确认换库
            val dbOpened = runCatching {
                get<AppDatabase>().openHelper.writableDatabase
                    .query("SELECT 1").use { it.moveToFirst() }
            }.getOrElse { e ->
                Log.e(TAG, "restore confirmation: database failed to open", e)
                false
            }
            if (!dbOpened) {
                // 不清 applied/rollback：进程重启后 rollbackIfUnconfirmed 自动回滚到旧库
                _startupState.value = StartupState.Failed(
                    getString(R.string.startup_restore_db_open_failed),
                    canRetry = false,
                )
                return
            }
            PendingRestoreStore.confirmApplied(filesDir)
        }
        _startupState.value = StartupState.Ready
        // 原 onCreate 里 startKoin 之后的所有步骤原样后移（依赖 Koin 或 Ready 语义）
        withContext(Dispatchers.Main) { postReadyInit() }
    }

    /**
     * Koin 幂等启动（B 档的 startKoin 唯一入口）。
     *
     * **SafeModeActivity 也会调用**：崩溃后重启进程 → RouteActivity 检测到崩溃标记 →
     * 进 SafeMode，但此时 boot() 可能尚未跑到 startKoin（甚至永远不会——Failed 态），
     * 不在这里补齐的话 SafeMode 自己就会因 Koin 未启动再崩一次（真机踩过同款）。
     */
    fun ensureKoinStarted() {
        if (org.koin.core.context.GlobalContext.getOrNull() != null) return
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
    }

    /** RouteActivity 的重试按钮：仅 applyIfPending 可重试（Koin 未启动，重试无损）。 */
    fun retryPendingRestore() {
        val current = _startupState.value
        if (current is StartupState.Ready) return
        if (current is StartupState.Failed && !current.canRetry) return
        _startupState.value = StartupState.Restoring
        if (!boot()) {
            // 上一轮 boot 协程还没跑到 finally 清 startupRunning（微秒窗口）：
            // 回退 UI，等它的 finally 落定状态，否则永远转圈
            _startupState.value = current
        }
    }

    /** staged 主库入库校验：版本可迁移 + 完整性（Android SQLiteDatabase，生产专用）。 */
    private fun validateStagedDatabase(dbFile: File) {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val version = db.rawQuery("PRAGMA user_version", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            check(version in 1..AppDatabase.SCHEMA_VERSION) {
                "Pending database is from an incompatible app version ($version)"
            }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok") { "Pending database failed integrity check" }
            }
        }
    }

    /**
     * 原 onCreate 中 startKoin 之后的步骤，顺序原样保留。
     * **主线程调用**（MemoryConsolidationRuntime 注册 ProcessLifecycleOwner 观察者的约束）。
     */
    private fun postReadyInit() {
        // 做梦（V3）：接上触发面（轮末 / 切后台 / 启动补漏）。**主线程调用**——
        // ProcessLifecycleOwner 要求在主线程注册观察者（与 ChatNotificationManager 同款）。
        // 整理本身仍然只在设置里那个助手级开关开着时才跑（门控在 scheduler 的 worker 契约里）。
        get<MemoryConsolidationRuntime>().start()

        // 真 tokenizer 预热（设计：token 预算精度）。**后台装载、不阻塞启动**：
        // 预热完成前所有 `estimateTokens` 走字符启发式兜底（行为不中断，只是精度低一点）。
        // 词表是 456KB gz 的 cl100k_base（Java 资源，见 common/src/main/resources/tokenizer）。
        get<AppScope>().launch(Dispatchers.Default) {
            val startedAt = SystemClock.elapsedRealtime()
            val ok = TokenizerRegistry.installFromClasspath()
            Log.i(
                TAG,
                "tokenizer install ok=$ok vocab=${TokenizerRegistry.current?.vocabSize} " +
                    "in ${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            TokenizerRegistry.lastError?.let { Log.e(TAG, "tokenizer install failed", it) }
            // 装好词表后挂持久化计数缓存：上次启动算过的精确值直接复用（词表版本不符会自动丢弃）
            if (ok) {
                TokenCountDiskCache.attach(filesDir)
                Log.i(TAG, "token count cache entries=${TokenCountDiskCache.size}")
            }
        }

        // SCHEMA_VERSION 漂移哨兵（design-db-downgrade-rebuild.md §0）：Room 真实开库版本
        // vs 常量。编译期闸门是 SchemaVersionConsistencyTest（schemas/ 最新 JSON 对账），
        // 这里是运行时兜底。漂移即抛（2026-10-31 拍板：接 CrashHandler 上报）——crash 文件
        // 落 logs/crash-*.txt、下次启动进 SafeMode 展示（进入即清标记，不会循环崩）。
        // **不能用 AppScope**：它的 CoroutineExceptionHandler 会把异常吞成 Log.e，
        // 走不到 CrashHandler 挂的默认 uncaught handler；这里是故意的裸协程（毫秒级即结束）。
        CoroutineScope(Dispatchers.Default).launch {
            val actual = runCatching { get<AppDatabase>().openHelper.writableDatabase.version }
                .getOrElse { e ->
                    Log.e(TAG, "schema version canary: database failed to open", e)
                    return@launch
                }
            check(actual == AppDatabase.SCHEMA_VERSION) {
                "SCHEMA_VERSION drifted: Room opened version=$actual, " +
                    "constant=${AppDatabase.SCHEMA_VERSION}"
            }
        }

        // 插件内核:先启动状态加载,再注册内置插件(register 内部等待加载完成,
        // 避免"注册早于读取"导致持久化的启用状态被默认值覆盖)
        get<PluginManager>().also { manager ->
            manager.start()
            get<AppScope>().launch {
                installBuiltInPlugins(manager)
                // 已安装的 JSON 规则插件物化回注册表(与内置插件同一套状态与开关)
                runCatching {
                    get<app.androidrik.rikkahub.data.plugin.rules.RulePluginInstaller>()
                        .restoreAll(
                            get<app.androidrik.rikkahub.data.plugin.rules.JsonRulePluginStore>().read()
                        )
                }.onFailure { Log.e(TAG, "failed to restore rule plugins", it) }
                // 已安装的 JS 沙箱插件同样物化(沙箱实例按需创建,这里只注册)
                runCatching {
                    get<app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller>()
                        .restoreAll(
                            get<app.androidrik.rikkahub.data.plugin.js.JsPluginStore>().read()
                        )
                }.onFailure { Log.e(TAG, "failed to restore js plugins", it) }
            }
        }

        // 初始化日志落盘目录 + 应用持久化的落盘开关（在生成/网络发生前就绪）
        initLogToFile()

        // delete temp files
        deleteTempFiles()

        // purge expired files from the export retention area (files/exports)
        purgeExpiredExports()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // 把老版本遗留在 upload/ 的头像搬进 avatars/（上游 #2127 的存量部分）。
        // 必须早于用户去点「文件管理」清理聊天文件，否则那些头像仍会被删掉。
        migrateLegacyAvatars()

        // 同款坑的背景图版：老版本的助手背景也在 upload/，搬进 backgrounds/（2026-10-31）。
        migrateLegacyBackgrounds()

        // Extract builtin skills from assets after install/update (上游 3b346748)
        extractBuiltinSkills()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Start keep-alive foreground service if enabled in settings
        startKeepAliveIfEnabled()

        // 定时任务 sweeper 对账（design-scheduled-ai-tasks §三）：有启用任务才排唯一周期工作
        reconcileScheduledTasks()

        // Resume media generations interrupted by the last process death
        resumeMediaCreations()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun resumeMediaCreations() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<MediaCreationService>().resumePending()
            }.onFailure {
                Log.e(TAG, "resumeMediaCreations failed", it)
            }
        }
    }

    private fun reconcileScheduledTasks() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val repository = get<app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository>()
                get<ScheduledTaskSweeperKeeper>().rearm(repository)
            }.onFailure {
                Log.e(TAG, "reconcileScheduledTasks failed", it)
            }
        }
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                // 只原子改 launch_count 单键：原实现「读整份快照 → copy → update() 整份写回」
                // 在启动时机极早、读链尚未产出真实数据时会把默认值当用户数据写回，
                // 抹掉全部设置（上游 70b382f5 修的正是这条路径）。
                val count = get<SettingsStore>().incrementLaunchCount()
                Log.i(TAG, "incrementLaunchCount: $count")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            // 与「缓存管理」共用同一份目录口径（StoragePaths.TEMP）；只清内容、保留目录
            runCatching {
                get<StorageUsageManager>().clear(StorageCategoryId.TEMP)
            }.onSuccess { result ->
                if (!result.success) {
                    Log.w(
                        TAG,
                        "deleteTempFiles: ${result.failures.size} item(s) could not be removed",
                    )
                }
            }.onFailure {
                Log.e(TAG, "deleteTempFiles failed", it)
            }
        }
    }

    /**
     * 清理导出留存区（`files/exports`）中已过期的文件。
     *
     * 与 [deleteTempFiles] 的"清空"不同：留存区是**用户想留的产物**，按 TTL 清理
     * （`Settings.exportRetentionDays`，0 = 不清理）。见 `docs/design/design-export-retention.md` §4.3。
     *
     * **必须读 `settingsFlowRaw.first()`** 而不是 `settingsFlow.value`——后者在 DataStore
     * 尚未加载时是 `Settings.dummy()`（全默认），会静默用默认值而非用户设置
     * （同 [initLogToFile] 里记录的坑）。
     *
     * 顺带清掉写入中断残留的临时文件（`.tmp-` 前缀）：它们未被登记进 `managed_files`，
     * 若不清理会永远留在盘上。
     */
    private fun purgeExpiredExports() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.EXPORTS)
                if (!dir.isDirectory) return@runCatching
                // 残留临时文件无条件清（未登记、无引用价值）
                dir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith(EXPORT_TEMP_PREFIX) }
                    ?.forEach { it.delete() }

                val retentionDays = get<SettingsStore>().settingsFlowRaw.first().exportRetentionDays
                val cutoff = exportRetentionCutoff(retentionDays, System.currentTimeMillis())
                    ?: return@runCatching  // 0 = 不自动清理
                var removed = 0
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && isExportExpired(file.lastModified(), cutoff)) {
                        if (file.delete()) removed++
                    }
                }
                if (removed > 0) {
                    Log.i(TAG, "purgeExpiredExports: removed $removed expired export(s) " +
                        "(retention=${retentionDays}d)")
                }
            }.onFailure {
                Log.e(TAG, "purgeExpiredExports failed", it)
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun extractBuiltinSkills() {
        get<AppScope>().launch(Dispatchers.IO) {
            get<SkillManager>().ensureBuiltinSkillsExtracted()
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
                // avatars 目录同样对账：搬迁（或用户直接放文件）后 managed_files 要跟上
                get<FilesManager>().syncFolder(FileFolders.AVATARS)
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    /**
     * 把老版本留在 `upload/` 的头像搬进 `avatars/` 并改写引用（上游 #2127 的存量部分）。
     *
     * 幂等且静默失败：没有可搬的就直接返回，单个头像搬不动就保持原状（引用不改）。
     */
    private fun migrateLegacyAvatars() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                AvatarMigration.migrate(
                    context = this@RikkaHubApp,
                    repository = get(),
                    settingsStore = get(),
                )
            }.onFailure {
                Log.e(TAG, "migrateLegacyAvatars failed", it)
            }
        }
    }

    /** 把老版本遗留在 `upload/` 的助手背景搬进 `backgrounds/`（#2127 同款坑，2026-10-31）。 */
    private fun migrateLegacyBackgrounds() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                BackgroundsMigration.migrate(
                    context = this@RikkaHubApp,
                    repository = get(),
                    settingsStore = get(),
                )
            }.onFailure {
                Log.e(TAG, "migrateLegacyBackgrounds failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    /**
     * 初始化日志落盘：指定目录 + 应用持久化开关。
     *
     * 同步执行（不 launch）以确保在网络/生成开始前就绪——[Logging] 的落盘写入是
     * 触发性追加，开关状态在进程早期必须正确。
     */
    private fun initLogToFile() {
        Logging.initLogDirectory(File(filesDir, FileFolders.LOGS))
        // 必须读 settingsFlowRaw（真实 DataStore 流）：settingsFlow.value 在 DataStore 尚未加载时
        // 是 Settings.dummy()（全默认 false），同步读会永远得到「关闭」——这正是「开关开着却不写文件」的坑。
        // 持续跟随（而非只读一次）：用户在 Log 页切换后即使不经由直接 setter 也能生效
        get<AppScope>().launch {
            runCatching {
                get<SettingsStore>().settingsFlowRaw.collect { settings ->
                    Logging.setFileLoggingEnabled(settings.logToFileEnabled)
                    // 两个开关都要跟随：只恢复落盘而请求记录重置为关时，文件会一直是空的（静默失效）
                    Logging.setRequestLoggingEnabled(settings.recordRequestsEnabled)
                }
            }.onFailure {
                Log.w(TAG, "initLogToFile: failed to observe settings, keeping logging off", it)
            }
        }
    }

    private fun startKeepAliveIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                if (!KeepAliveService.isEnabled(this@RikkaHubApp)) return@launch
                // 前台服务的常驻通知必须可见：无通知权限时不启动（设置页有引导）
                if (!KeepAliveService.hasNotificationPermission(this@RikkaHubApp)) {
                    Log.w(TAG, "startKeepAliveIfEnabled: notification permission not granted, skipping")
                    return@launch
                }
                KeepAliveService.start(this@RikkaHubApp)
            }.onFailure {
                Log.e(TAG, "startKeepAliveIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)

        val keepAliveChannel = NotificationChannelCompat
            .Builder(KEEP_ALIVE_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_keep_alive))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(keepAliveChannel)

        val mediaCreationChannel = NotificationChannelCompat
            .Builder(MEDIA_CREATION_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(getString(R.string.media_creation_title))
            .build()
        notificationManager.createNotificationChannel(mediaCreationChannel)

        val subagentApprovalChannel = NotificationChannelCompat
            .Builder(
                SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_subagent_approval))
            .setDescription(getString(R.string.notification_channel_subagent_approval_desc))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(subagentApprovalChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
