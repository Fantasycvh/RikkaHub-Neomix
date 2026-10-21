package app.androidrik.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.web.startWebServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_ALL_INTERFACES = "0.0.0.0"
private const val HOST_LOOPBACK = "127.0.0.1"
private const val DEFAULT_PORT = 8080

/** 等待 Settings 从 DataStore 加载完成的上限：超时退回落当前值（安全侧优先，宁可拒绝启动）。 */
private const val SETTINGS_LOAD_TIMEOUT_MS = 5_000L
private const val STOP_GRACE_PERIOD_MS = 1_000L
private const val STOP_TIMEOUT_MS = 2_000L

/**
 * Web 服务启动被安全策略拒绝时的文案（也供设置页/测试断言）。
 *
 * 策略：**不提供「不鉴权」模式**。口令为空或鉴权被关（存量偏好/备份恢复等）一律拒绝启动，
 * 而不是退化成裸奔的 LAN API。
 */
internal const val WEB_STARTUP_ERROR_PASSWORD_REQUIRED =
    "Web server requires a non-empty access password (Settings → Web server)"
internal const val WEB_STARTUP_ERROR_AUTH_REQUIRED =
    "Web server authentication is required and cannot be disabled"

/**
 * Web 服务启动前的**鉴权强制校验**（纯函数，便于 JVM 单测）。
 *
 * @return `null` 表示可以启动；否则为拒绝原因。
 */
internal fun webStartupAuthError(accessPassword: String, jwtEnabled: Boolean): String? = when {
    accessPassword.isBlank() -> WEB_STARTUP_ERROR_PASSWORD_REQUIRED
    !jwtEnabled -> WEB_STARTUP_ERROR_AUTH_REQUIRED
    else -> null
}

/**
 * 生命周期「意图」：start / stop 都先登记成最新意图，再串行执行。
 *
 * 意图对象化是为了解决两件事：
 * 1. **同步准备状态**：start 一进来就清掉上一次 attempt 的 `error`/`isRunning` 并置 `isLoading`，
 *    前台服务观察器不会先订阅到旧 error 而把新一次重试立刻 `stopSelf` 掉；
 * 2. **最新意图胜出**：排队中的旧 stop 不会覆盖随后到来的 start（反之亦然）。
 */
internal sealed interface WebLifecycleIntent {
    data class Start(
        val port: Int,
        val serviceName: String,
        val localhostOnly: Boolean,
    ) : WebLifecycleIntent

    data object Stop : WebLifecycleIntent
}

/**
 * 收到意图时**同步**产出的状态（纯函数，便于 JVM 单测）。
 *
 * start 必须显式清空 `error`：否则失败重试时观察器先看到上一次的 error，会按「启动失败」收尾，
 * 把正在进行的重试连同前台服务一起停掉。
 * 但「同一配置且仍在 running 的重复 start」必须保留 `isRunning/hostname/address`——mDNS 白名单
 * 依赖 `state.hostname`，清掉会让 `<service>.local` 在瞬时 403。
 */
internal fun prepareStateForIntent(
    intent: WebLifecycleIntent,
    previous: WebServerState,
): WebServerState = when (intent) {
    is WebLifecycleIntent.Start -> {
        // 同一配置的重复 start：服务其实还在 running，不能把 hostname/address 清掉——
        // `additionalAllowedHosts = { setOfNotNull(_state.value.hostname) }` 依赖它，
        // 清掉会让 mDNS 主机名（`<service>.local`）访问在写 mDNS 白名单时被 403。
        val sameRunningConfig = previous.isRunning &&
            previous.port == intent.port &&
            previous.localhostOnly == intent.localhostOnly
        if (sameRunningConfig) {
            previous.copy(error = null)
        } else {
            WebServerState(
                port = intent.port,
                serviceName = intent.serviceName,
                localhostOnly = intent.localhostOnly,
                isLoading = true,
            )
        }
    }

    WebLifecycleIntent.Stop -> previous.copy(
        isRunning = false,
        isLoading = true,
        hostname = null,
        address = null,
        error = null,
    )
}

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    /** 安全默认：只听回环（需要局域网访问必须由用户显式切换）。 */
    val localhostOnly: Boolean = true,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

/** 当前 Ktor 实例实际运行的参数，用于判断重复 start 是否真的需要重启。 */
private data class RunningConfig(
    val port: Int,
    val serviceName: String,
    val localhostOnly: Boolean,
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var runningConfig: RunningConfig? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    /**
     * 串行化 start / stop 的实际启停动作。
     *
     * 旧实现的 `start()` 先查 `server != null` 再 `appScope.launch`：两个几乎同时到达的 start
     * （设置页连点 / 开机自动拉起 / 通知栏操作）都能通过守卫，第二个会在同端口绑定失败并
     * 留下错误态；stop 与 start 交错时还会出现「先停后启但启动被旧守卫挡掉」。
     */
    private val lifecycleMutex = Mutex()

    /** 保护 [latestIntent] 的同步块：登记与读取都必须原子。 */
    private val intentLock = Any()
    private var latestIntent: WebLifecycleIntent? = null

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = DEFAULT_PORT,
        serviceName: String = DEFAULT_SERVICE_NAME,
        // 安全默认：loopback。局域网模式必须由调用方显式传 false（设置页开关）。
        localhostOnly: Boolean = true
    ) {
        requestIntent(WebLifecycleIntent.Start(port, serviceName, localhostOnly))
    }

    fun stop() {
        requestIntent(WebLifecycleIntent.Stop)
    }

    /**
     * 以新参数重启。语义等价于「确保最终以这套参数运行」：参数没变则保持现有连接不重启，
     * 参数变了才真正先停后启。
     */
    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) {
        requestIntent(WebLifecycleIntent.Start(port, serviceName, localhostOnly))
    }

    fun reportError(message: String) {
        _state.value = _state.value.copy(isRunning = false, isLoading = false, error = message)
    }

    /**
     * 登记最新意图 → **同步**更新状态（清旧 error）→ 在 AppScope 上串行执行。
     *
     * 同步这一步就是修复「失败后重试被旧 error 立刻 stopSelf」的关键：观察器不论在 start 之前
     * 还是之后订阅，看到的都是本次 attempt 的 loading 态，而不是上一次的 error。
     */
    private fun requestIntent(intent: WebLifecycleIntent) {
        synchronized(intentLock) { latestIntent = intent }
        _state.value = prepareStateForIntent(intent, _state.value)
        appScope.launch {
            lifecycleMutex.withLock {
                // 只执行**最新**意图：排队中的旧 stop 不会覆盖之后登记的 start。
                val latest = synchronized(intentLock) { latestIntent } ?: return@withLock
                when (latest) {
                    WebLifecycleIntent.Stop -> stopServer()
                    is WebLifecycleIntent.Start -> applyStart(latest)
                }
            }
        }
    }

    private suspend fun applyStart(intent: WebLifecycleIntent.Start) {
        val config = RunningConfig(intent.port, intent.serviceName, intent.localhostOnly)
        if (server != null && runningConfig == config) {
            // 重复 start：已在以同样参数运行，恢复 running 态即可，不要重启断掉现有连接。
            _state.value = _state.value.copy(isRunning = true, isLoading = false, error = null)
            Log.i(TAG, "Web server already running with the same config")
            return
        }
        if (server != null) {
            // 参数变化（restart 路径）：先停再启。
            stopServer()
        }
        startServer(intent.port, intent.serviceName, intent.localhostOnly)
    }

    private suspend fun startServer(port: Int, serviceName: String, localhostOnly: Boolean) {
        // requestIntent 已同步准备过；stop→start 串行路径在这里再保证一次 loading。
        _state.value = _state.value.copy(
            isRunning = false,
            isLoading = true,
            hostname = null,
            address = null,
            error = null
        )

        // 强制鉴权：读「已加载完成」的 Settings（启动极早期 settingsFlow 可能还是 dummy）。
        val settings = withTimeoutOrNull(SETTINGS_LOAD_TIMEOUT_MS) {
            settingsStore.settingsFlow.first { !it.init }
        } ?: settingsStore.settingsFlow.value
        webStartupAuthError(settings.webServerAccessPassword, settings.webServerJwtEnabled)?.let { message ->
            Log.w(TAG, "Refusing to start web server: $message")
            _state.value = WebServerState(
                port = port,
                serviceName = serviceName,
                localhostOnly = localhostOnly,
                error = message
            )
            return
        }

        // 仅本机模式绑定回环地址
        val host = if (localhostOnly) HOST_LOOPBACK else HOST_ALL_INTERFACES
        val baseState = WebServerState(
            port = port,
            serviceName = serviceName,
            localhostOnly = localhostOnly
        )
        Log.i(TAG, "Starting web server on $host:$port")

        if (!isPortAvailable(host, port)) {
            Log.w(TAG, "Port $port is already in use")
            _state.value = baseState.copy(error = "Port $port is already in use")
            return
        }

        var starting: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        try {
            starting = startWebServer(
                port = port,
                host = host,
                // mDNS 主机名（`<service>.local`）在注册成功后才出现在 state 里；传闭包让
                // Host 白名单在**每次请求**取最新值，避免注册完成前拒绝自己的 mDNS 访问。
                additionalAllowedHosts = { setOfNotNull(_state.value.hostname) }
            ) {
                configureWebApi(context, chatService, conversationRepo, folderRepo, settingsStore, filesManager)
            }
            starting.start(wait = false)
            server = starting
            runningConfig = RunningConfig(port, serviceName, localhostOnly)

            _state.value = baseState.copy(isRunning = true)
            // 仅局域网模式注册 mDNS
            if (!localhostOnly) {
                runCatching {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                        onRegistered = { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress
                            )
                        }
                    )
                }.onFailure {
                    Log.w(TAG, "NSD register failed", it)
                }
            }
            Log.i(TAG, "Web server started successfully on $host:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server", e)
            // 失败清理：Ktor 可能已部分绑定端口，mDNS 也可能已注册；两者都必须回收。
            runCatching { starting?.stop(STOP_GRACE_PERIOD_MS, STOP_TIMEOUT_MS) }
            runCatching { nsdRegistrar.unregister() }
            server = null
            runningConfig = null
            _state.value = baseState.copy(error = e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun stopServer() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        Log.i(TAG, "Stopping web server")
        try {
            val running = server
            server = null
            runningConfig = null
            runCatching { running?.stop(STOP_GRACE_PERIOD_MS, STOP_TIMEOUT_MS) }
                .onFailure { Log.w(TAG, "Failed to stop web server", it) }
            runCatching { nsdRegistrar.unregister() }
                .onFailure { Log.w(TAG, "NSD unregister failed", it) }
            // 如果停的过程中已经被更新的 start 取代，不要再用 stopped 态覆盖它。
            if (latestIntentIsStart().not()) {
                _state.value = _state.value.copy(isLoading = false)
            }
            Log.i(TAG, "Web server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop web server", e)
            if (latestIntentIsStart().not()) {
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    private fun latestIntentIsStart(): Boolean =
        synchronized(intentLock) { latestIntent } is WebLifecycleIntent.Start

    private fun isPortAvailable(host: String, port: Int): Boolean {
        // 按**实际将绑定的地址**探测：localhostOnly 用旧写法（通配）会把「别的应用只占了
        // 127.0.0.1:port」误报为端口占用；LAN 模式则必须探测通配绑定。
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName(host), port))
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}
