package app.androidrik.rikkahub.phoneuse

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.androidrik.rikkahub.data.datastore.Settings

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
 * 实现是 [PhoneUseAccessibilityService]；Android 11 以下与无服务时不可用。
 */
interface PhoneController {
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

    fun openApp(packageName: String): Boolean
}

/**
 * 后台悬浮窗是否真的能显示：开关打开并且有悬浮窗权限。
 * Phone Use 要求它开着，用户才看得到 AI 在做什么、随时能停。
 */
fun isFloatingChatWindowOn(context: Context, settings: Settings): Boolean =
    settings.displaySetting.enableFloatingChatWindow && android.provider.Settings.canDrawOverlays(context)

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
     */
    suspend fun <T> withPhoneControl(owner: Any, block: suspend (epoch: Long) -> T): T =
        controlMutex.withLock {
            if (lastOwner !== owner) epochCounter++
            lastOwner = owner
            block(epochCounter)
        }

    fun attach(controller: PhoneController) {
        _controller.value = controller
        invalidateScreenCache()
    }

    fun detach(controller: PhoneController) {
        _controller.compareAndSet(controller, null)
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
