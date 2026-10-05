package app.androidrik.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import app.androidrik.rikkahub.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

/**
 * 恢复期空 backstack 修复（nav3 空栈崩溃的第三道防线，见 `RouteActivity` 的注释）。
 *
 * `NavDisplay` 对空 backstack 直接 `require(isNotEmpty())` 抛异常；而**崩溃现场会被写进
 * saved state**，Activity 重建时 `rememberNavBackStack` 原样还原空栈 ⇒ 崩溃循环。
 * 存量用户（崩过一次、saved state 里躺着空列表）不修就会每次开屏都崩，故这里在进入
 * `NavDisplay` 组合之前把栈补回 start 页。
 *
 * @return 是否发生了修复（幂等：仅空栈时写一次；返回值为真只用于断言与日志）
 */
fun <T> MutableList<T>.repairIfEmpty(fallback: T): Boolean =
    if (isEmpty()) {
        add(fallback)
        true
    } else {
        false
    }

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
