package app.androidrik.rikkahub.phoneuse

import androidx.compose.ui.graphics.ImageBitmap
import app.androidrik.rikkahub.data.datastore.PhoneUseTargetMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 虚拟屏关闭原因：区分用户/锁屏等静默路径与 Shizuku 死亡等需要通知用户的异常路径。 */
internal enum class PhoneUseDisplayCloseReason {
    USER_STOP,
    DEVICE_LOCKED,
    A11Y_DISCONNECTED,
    SHIZUKU_DIED,
    SERVICE_DISCONNECTED,
    DISPLAY_REMOVED,
    STARTUP_FAILED,
    APP_INITIATED,
}

/** 仅非用户主动、非锁屏、非无障碍解绑的异常中断需要发一次性通知，避免打扰。 */
internal fun PhoneUseDisplayCloseReason.shouldNotifyUser(): Boolean = when (this) {
    PhoneUseDisplayCloseReason.SHIZUKU_DIED,
    PhoneUseDisplayCloseReason.SERVICE_DISCONNECTED,
    PhoneUseDisplayCloseReason.DISPLAY_REMOVED,
    -> true

    PhoneUseDisplayCloseReason.USER_STOP,
    PhoneUseDisplayCloseReason.DEVICE_LOCKED,
    PhoneUseDisplayCloseReason.A11Y_DISCONNECTED,
    PhoneUseDisplayCloseReason.STARTUP_FAILED,
    PhoneUseDisplayCloseReason.APP_INITIATED,
    -> false
}

/** A user-started display, identified by its lifetime as well as Android's numeric display ID. */
internal interface PhoneUseVirtualDisplay {
    val id: Int
    val controller: PhoneController
    val apps: List<PhoneApp>
    fun openApp(packageName: String): Boolean
    fun openUrl(packageName: String, url: String): Boolean
    fun back(): Boolean

    /** Apps whose tasks are currently listed on this display (`am stack list`), most recent first. */
    fun listRecent(): List<PhoneApp>

    fun close(reason: PhoneUseDisplayCloseReason = PhoneUseDisplayCloseReason.USER_STOP)

    /** Latest frame for the live preview; only produced while [setPreviewActive] is on. */
    val previewFrames: StateFlow<ImageBitmap?>
    fun setPreviewActive(active: Boolean)
}

internal fun phoneUseHttpUrl(value: String): String? {
    val url = value.trim()
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
    if (url.any { it.isISOControl() || it == '\\' }) return null
    val parsed = url.toHttpUrlOrNull() ?: return null
    return parsed.toString().takeIf { parsed.username.isEmpty() && parsed.password.isEmpty() }
}

internal interface PhoneUseDisplayControllerFactory {
    fun forDisplay(display: PhoneUseVirtualDisplay): PhoneController
}

internal class PhoneUseDisplayTarget(
    val display: PhoneUseVirtualDisplay?,
    /** 本轮冻结的操作目标模式（design-phoneuse-target-mode.md §3.2）。 */
    val mode: PhoneUseTargetMode = PhoneUseTargetMode.AUTO,
) :
    AbstractCoroutineContextElement(Key) {
    /**
     * 用户选择了「虚拟屏」模式：本轮 `display == null` 时工具必须报错，
     * 不能静默落到实体手机。
     */
    val requireVirtual: Boolean get() = mode == PhoneUseTargetMode.VIRTUAL

    companion object Key : CoroutineContext.Key<PhoneUseDisplayTarget>
}

internal object PhoneUseVirtualDisplays {
    private val active = MutableStateFlow<PhoneUseVirtualDisplay?>(null)
    val current = active.asStateFlow()

    fun attach(display: PhoneUseVirtualDisplay): Boolean = active.compareAndSet(null, display)

    fun detach(display: PhoneUseVirtualDisplay) {
        active.compareAndSet(display, null)
    }

    fun checkTarget(display: PhoneUseVirtualDisplay?) {
        check(current.value === display) {
            "The Phone Use display changed. Start a new chat turn to use the currently selected display."
        }
    }
}
