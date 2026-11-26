package app.androidrik.rikkahub.ui.pages.setting.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.androidrik.rikkahub.Screen
import kotlinx.coroutines.delay

/**
 * 设置页 `advancedGroup` 的稳定标识符，供全局搜索跳转后自动展开并定位。
 *
 * 与页面内的 `rememberSaveable` 状态解耦：不改 `Screen` 路由签名，避免破坏 Nav3
 * 返回栈序列化与 `NavStats` 路由名统计。
 */
object SettingAdvancedSection {
    const val GENERAL_GLASS = "general_glass"
    const val UI_CODE_DISPLAY = "ui_code_display"
    const val MODEL_CONTEXT_LENGTH_OVERRIDE = "model_context_length_override"
    const val MODEL_OCR_BACKEND = "model_ocr_backend"
}

data class SettingSearchRevealTarget(
    val route: Screen,
    val advancedSection: String,
)

/** 进程内一次性交接通道：全局搜索写入目标，目标设置页首帧消费后清空。 */
open class SettingSearchRevealChannel {
    private val lock = Any()
    private var pending: SettingSearchRevealTarget? = null

    fun request(route: Screen, advancedSection: String?) {
        synchronized(lock) {
            pending = advancedSection?.takeIf { it.isNotBlank() }?.let {
                SettingSearchRevealTarget(route = route, advancedSection = it)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            pending = null
        }
    }

    fun peekRoute(route: Screen): String? = synchronized(lock) {
        pending?.takeIf { it.route == route }?.advancedSection
    }

    fun consumeRoute(route: Screen): String? = synchronized(lock) {
        val current = pending
        if (current != null && current.route == route) {
            pending = null
            current.advancedSection
        } else {
            null
        }
    }
}

object SettingSearchReveal : SettingSearchRevealChannel()

private const val REVEAL_HIGHLIGHT_DURATION_MS = 1200L

/** 搜索命中展开后的短时高亮标记，超时自动淡出。 */
@Composable
fun rememberAdvancedRevealHighlight(revealed: Boolean): Boolean {
    var active by remember(revealed) { mutableStateOf(revealed) }
    LaunchedEffect(revealed) {
        if (revealed) {
            active = true
            delay(REVEAL_HIGHLIGHT_DURATION_MS)
            active = false
        }
    }
    return active
}
