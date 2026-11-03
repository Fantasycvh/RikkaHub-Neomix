package app.androidrik.rikkahub.ui.components.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 系统「移除动画」（开发者选项 / 无障碍）读取（借鉴 BiliPai `ReduceMotion.kt`，按本仓重写）。
 *
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0` 即用户要求去掉动画。Compose 的 `animate*`
 * 会自动遵循它，但**自驱的循环动画不会**——`MeshGradientBackground` 改成限速时间源后就是
 * 这一类，必须自己读。
 */

/** 纯函数便于单测：scale 为 0 表示系统要求减弱动效。 */
internal fun isReduceMotionScale(scale: Float): Boolean = scale == 0f

/** 读取失败（个别 ROM / 权限异常）按「不减弱」处理，宁可动也别静止。 */
internal fun readSystemReduceMotion(context: Context): Boolean = runCatching {
    isReduceMotionScale(
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )
    )
}.getOrDefault(false)

/**
 * 跟随系统「移除动画」设置，实时变化（`ContentObserver`）：
 * 注册后先同步一次，避免注册窗口期的变化被漏掉。
 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember { mutableStateOf(readSystemReduceMotion(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = readSystemReduceMotion(context)
            }
        }
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer,
            )
        }
        reduceMotion = readSystemReduceMotion(context)
        onDispose {
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    return reduceMotion
}
