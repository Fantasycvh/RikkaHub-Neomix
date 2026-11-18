package app.androidrik.rikkahub.phoneuse

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A user-started display, identified by its lifetime as well as Android's numeric display ID. */
internal interface PhoneUseVirtualDisplay {
    val id: Int
    val controller: PhoneController
    val apps: List<PhoneApp>
    fun openApp(packageName: String): Boolean
    fun openUrl(packageName: String, url: String): Boolean
    fun back(): Boolean
    fun close()
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

internal class PhoneUseDisplayTarget(val display: PhoneUseVirtualDisplay?) :
    AbstractCoroutineContextElement(Key) {
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
