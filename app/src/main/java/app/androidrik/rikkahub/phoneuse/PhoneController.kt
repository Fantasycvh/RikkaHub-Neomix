package app.androidrik.rikkahub.phoneuse

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.androidrik.rikkahub.data.datastore.Settings

// 亮屏锁的兜底上限：正常由空闲释放逻辑显式 release，进程被杀时系统也会兜底
private const val SCREEN_HOLD_TIMEOUT_MS = 24 * 60 * 60 * 1000L

// 两次手机操作之间（模型思考、等审批）允许的息屏宽限
private const val SCREEN_IDLE_RELEASE_MS = 120_000L

/** 屏幕上一个可以操作或带文字的元素，[index] 在下一次 [PhoneController.captureScreen] 之前有效，坐标是屏幕像素 */
data class PhoneElement(
    val index: Int,
    val className: String,
    val label: String,
    /** 布局里的资源 ID 名（如 btn_send），应用没有设置或被系统隐藏时为空 */
    val resourceId: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val focused: Boolean,
    /** 密码框不返回文字，只保留可输入语义 */
    val password: Boolean,
)

data class PhoneScreen(
    val packageName: String?,
    val appLabel: String?,
    /** 这份元素来自哪些应用的窗口，[packageName] 只是最上层的那个 */
    val packages: Set<String>,
    val width: Int,
    val height: Int,
    val keyboardVisible: Boolean,
    val elements: List<PhoneElement>,
    /** 元素太多时只保留前面的一部分 */
    val truncated: Boolean,
)

data class PhoneApp(val packageName: String, val label: String)

enum class PhoneKey { BACK, HOME, RECENTS, NOTIFICATIONS, ENTER }

/**
 * 操作手机的底层能力，坐标都是屏幕像素。
 * 由 [PhoneUseAccessibilityService] 提供；Android 11 以下与无服务时不可用。
 */
interface PhoneController {
    val isVirtual: Boolean get() = false

    /**
     * 屏幕上所有可见窗口所属的应用，和 [captureScreen] 读取的窗口范围一致，不算本应用自己的悬浮窗。
     * 分屏时不止一个；只用来判断屏幕上有谁，不影响元素编号
     */
    fun visiblePackages(): Set<String>

    /** 读取当前屏幕上的元素，不包含本应用自己的悬浮窗 */
    suspend fun captureScreen(): PhoneScreen

    /** JPEG 截图，长边不超过 [maxSide] */
    suspend fun screenshot(maxSide: Int): ByteArray

    /** 起点和终点相同时是点击，[durationMs] 决定是轻点还是长按 */
    suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean

    /** 把文字填进 [elementIndex] 指向的输入框；为空时填进当前有输入焦点的输入框 */
    suspend fun setText(elementIndex: Int?, text: String): Boolean

    suspend fun pressKey(key: PhoneKey): Boolean

    fun listApps(): List<PhoneApp>

    /**
     * 最近使用过的应用（最近优先）。仅虚拟屏支持：HOME/RECENTS 是 PhoneWindowManager 全局键，
     * `input -d` 只会作用于主屏（会把用户真机拉回桌面），不能按屏分发，所以用任务列表替代。
     */
    fun listRecent(): List<PhoneApp> =
        error("list_recent is only available on the Phone Use virtual display.")

    fun openApp(packageName: String): Boolean
}

/**
 * 后台悬浮窗是否真的能显示：开关打开并且有悬浮窗权限。
 * Phone Use 要求它开着，用户才看得到 AI 在做什么、随时能停。
 */
fun isFloatingChatWindowOn(context: Context, settings: Settings): Boolean =
    settings.displaySetting.enableFloatingChatWindow && android.provider.Settings.canDrawOverlays(context)

/**
 * 解析系统无障碍设置中的 `ENABLED_ACCESSIBILITY_SERVICES` 字符串（冒号分隔），兼容完整类名与
 * 简写类名（`pkg/.sub.Service`）。纯函数，供单测直接覆盖。
 */
internal fun isAccessibilityServiceListed(
    enabledServices: String?,
    packageName: String,
    className: String,
): Boolean {
    if (enabledServices.isNullOrBlank()) return false
    val expectedFull = "$packageName/$className"
    val expectedShort = if (className.startsWith("$packageName.")) {
        "$packageName/${className.removePrefix(packageName)}"
    } else {
        null
    }
    return enabledServices.split(':').any { raw ->
        val entry = raw.trim()
        entry == expectedFull || (expectedShort != null && entry == expectedShort)
    }
}

/**
 * 系统无障碍设置里是否勾选了 Phone Use。勾选了不代表服务在运行：
 * ColorOS 等系统在最近任务里划掉应用后会停掉服务，设置里却仍显示开启，也不会再自动绑定，只能让用户关掉重开。
 */
fun isPhoneUseCheckedInSystem(context: Context): Boolean {
    val enabled = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }.getOrNull()
    return isAccessibilityServiceListed(
        enabledServices = enabled,
        packageName = context.packageName,
        className = PhoneUseAccessibilityService::class.java.name,
    )
}

/**
 * 无障碍服务连接后把自己登记在这里，工具和设置页从这里拿。
 *
 * 并发约束（本仓可能有多个会话同时生成）：
 * - [withPhoneControl] 是全局手机控制锁，同一时刻只允许一个会话动手；
 * - [phoneEpoch] 在每次释放锁时递增，调用方据此丢弃上一次读屏缓存（元素编号只对那份屏幕有效）；
 * - [beginActing]/[endActing] 是引用计数，嵌套动作不会提前把悬浮窗恢复成可触摸。
 */
class PhoneUseManager {
    companion object {
        /**
         * 进程级单例。
         *
         * **不能靠 Koin 解析**：`RikkaHubApp` 的 startKoin 在 IO 上异步执行（换库之后），
         * 无障碍服务可能在 Application.onCreate 返回后、startKoin 之前就绑定，
         * `by inject()` 会抛 NoDefinitionFound 并把应用打进安全模式（真机实证 2026-11-11）。
         * 本类无依赖，直接静态持有；Koin 侧 single 返回同一个实例。
         */
        val instance = PhoneUseManager()

        /** 截图与窗口级截图都是 Android 11 才有的无障碍接口 */
        val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    private val _controller = MutableStateFlow<PhoneController?>(null)
    val controller: StateFlow<PhoneController?> = _controller.asStateFlow()

    private val _acting = MutableStateFlow(false)
    /** 正在截图或执行手势，这期间悬浮窗要让出屏幕：不能出现在截图里，也不能挡住点击 */
    val acting: StateFlow<Boolean> = _acting.asStateFlow()

    private val controlMutex = Mutex()
    private val actingLock = Any()
    private var actingCount = 0

    @Volatile
    private var epochCounter = 0L

    /** 上一个持有控制权的会话；换人时递增世代 */
    @Volatile
    private var lastOwner: Any? = null

    /**
     * 取得全局手机控制权后执行 [block]，参数是本次持有的世代。
     *
     * [owner] 是同一次工具调用会话的稳定对象：连续动作不换世代，中间被别的会话插进来才换。
     * 调用方保存世代；下一次取得控制权后若世代变了，必须丢弃上一次的 [PhoneScreen]。
     *
     * 持有控制权期间保持亮屏（WakeLock）：本应用在后台时 FLAG_KEEP_SCREEN_ON 无效。
     * 释放锁后再空闲 [SCREEN_IDLE_RELEASE_MS] 才熄屏，覆盖两次动作之间的模型思考与审批等待。
     */
    suspend fun <T> withPhoneControl(owner: Any, block: suspend (epoch: Long) -> T): T =
        controlMutex.withLock {
            keepScreenOn()
            try {
                if (lastOwner !== owner) epochCounter++
                lastOwner = owner
                block(epochCounter)
            } finally {
                scheduleScreenRelease()
            }
        }

    /**
     * 由 Koin 装配时注入。不能在构造时拿 Context：无障碍服务可能早于 startKoin 使用本类；
     * 工具执行一定发生在 Koin 就绪之后，所以 [keepScreenOn] 时它已可用。未注入时静默跳过（单测）。
     */
    fun providePowerManager(manager: PowerManager) {
        wakeLock = manager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rikkahub:phone_use")
            .apply { setReferenceCounted(false) }
    }

    private fun keepScreenOn() {
        screenReleaseJob?.cancel()
        screenReleaseJob = null
        runCatching {
            wakeLock?.takeIf { !it.isHeld }?.acquire(SCREEN_HOLD_TIMEOUT_MS)
        }
    }

    private fun scheduleScreenRelease() {
        screenReleaseJob?.cancel()
        screenReleaseJob = screenScope.launch {
            delay(SCREEN_IDLE_RELEASE_MS)
            runCatching {
                wakeLock?.takeIf { it.isHeld }?.release()
            }
        }
    }

    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReleaseJob: Job? = null

    fun attach(controller: PhoneController) {
        _controller.value = controller
        invalidateScreenCache()
    }

    fun detach(controller: PhoneController) {
        if (_controller.compareAndSet(controller, null)) {
            PhoneUseVirtualDisplays.current.value?.close(PhoneUseDisplayCloseReason.A11Y_DISCONNECTED)
        }
        invalidateScreenCache()
    }

    /** 服务断开/重连后，旧会话缓存里的元素编号不再可信 */
    private fun invalidateScreenCache() {
        synchronized(actingLock) {
            epochCounter++
            lastOwner = null
        }
    }

    fun beginActing() {
        synchronized(actingLock) {
            actingCount++
            _acting.value = true
        }
    }

    fun endActing() {
        synchronized(actingLock) {
            actingCount = (actingCount - 1).coerceAtLeast(0)
            _acting.value = actingCount > 0
        }
    }
}
