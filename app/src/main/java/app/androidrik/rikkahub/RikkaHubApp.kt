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
import app.androidrik.common.android.appTempFolder
import app.androidrik.rikkahub.data.files.EXPORT_TEMP_PREFIX
import app.androidrik.rikkahub.data.files.exportRetentionCutoff
import app.androidrik.rikkahub.data.files.isExportExpired
import com.whl.quickjs.android.QuickJSLoader
import app.androidrik.rikkahub.di.appModule
import app.androidrik.rikkahub.di.dataSourceModule
import app.androidrik.rikkahub.di.repositoryModule
import app.androidrik.rikkahub.di.viewModelModule
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.service.WebServerService
import app.androidrik.rikkahub.service.KeepAliveService
import app.androidrik.rikkahub.utils.CrashHandler
import app.androidrik.rikkahub.utils.DatabaseUtil
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

/**
 * 子代理「需要批准」通知渠道（设计 §十一）。
 *
 * IMPORTANCE_HIGH：这是**会阻塞一个正在跑的 run** 的请求，必须能弹出来（否则 run 只能等到超时）。
 */
const val SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID = "subagent_approval"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 隔离进程（TexCompileService，TikZ native 编译）跳过
        // 全部应用初始化：isolatedProcess 无大部分系统服务（WorkManager 依赖的
        // JobScheduler 为 null 直接崩溃），Koin/DB/WorkManager 与编译无关
        if (isIsolatedProcess()) return
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

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
        }

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

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

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Start keep-alive foreground service if enabled in settings
        startKeepAliveIfEnabled()

        // Increment launch count
        incrementLaunchCount()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
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
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * 清理导出留存区（`files/exports`）中已过期的文件。
     *
     * 与 [deleteTempFiles] 的"清空"不同：留存区是**用户想留的产物**，按 TTL 清理
     * （`Settings.exportRetentionDays`，0 = 不清理）。见 `docs/design-export-retention.md` §4.3。
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

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
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
