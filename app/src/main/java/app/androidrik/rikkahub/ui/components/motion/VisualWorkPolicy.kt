package app.androidrik.rikkahub.ui.components.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/**
 * 「装饰性可视工作」的窗口门控（借鉴 BiliPai `VisualWorkPolicy.kt`，按本仓重写）。
 *
 * 只门控**装饰性**工作（循环动画、光斑漂移、玻璃动效），**绝不**门控音频 / 下载 / 持久化 /
 * 生成这类有副作用的逻辑。分屏、自由窗口失焦、页面被遮挡时，装饰动画继续跑是纯耗电。
 *
 * 模糊（haze）侧刻意**不接**窗口焦点：焦点抖动会让模糊层闪断，先只用于自驱动画，
 * 观察一版之后再决定是否下放到 blur。
 */

/** 纯函数便于单测：页面可见 + 宿主 RESUMED + 窗口聚焦，三者同时满足才跑装饰性工作。 */
internal fun shouldRunVisualWork(
    pageActive: Boolean,
    resumed: Boolean,
    focused: Boolean,
): Boolean = pageActive && resumed && focused

@Composable
fun rememberVisualWorkActive(pageActive: Boolean = true): Boolean {
    val windowInfo = LocalWindowInfo.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return shouldRunVisualWork(
        pageActive = pageActive,
        resumed = lifecycleState == Lifecycle.State.RESUMED,
        focused = windowInfo.isWindowFocused,
    )
}
