package app.androidrik.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.androidrik.rikkahub.LauncherAssistantActivity
import app.androidrik.rikkahub.LauncherChatActivity
import app.androidrik.rikkahub.LauncherNotesActivity
import app.androidrik.rikkahub.LauncherRikkaHubActivity
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity

/**
 * 桌面「名称 + 图标」预置切换。
 *
 * 原理：每个预置是一个真实 Activity（RouteActivity 子类，见 RouteActivity.kt 末尾），
 * RouteActivity 本体作为默认预置（Neomix）也参与互斥启停（对齐 legado-with-MD3）。
 * 不用 activity-alias：ColorOS/HyperOS 的组件开关 hook 对 alias 组件做存在性校验会
 * 失败，调用被拒（前台抛异常、后台静默丢弃），真实 Activity 放行。
 *
 * 注意：
 * - 预置集合在编译期固定，无法接受用户任意图标（那是 pinned shortcut 的事）；
 * - 切换会触发桌面图标刷新，图标可能消失数秒；DONT_KILL_APP 只是请求不保证进程存活；
 * - 系统持久化组件开关状态；清数据后回到 Manifest 默认（Neomix）。
 */
object LauncherAlias {
    private const val TAG = "LauncherAlias"

    const val DEFAULT_ID = "default"

    data class Variant(
        val id: String,
        /**
         * 桌面入口 Activity 的**真实全类名**（KClass 取，勿用 applicationId 拼接：
         * 本包 applicationId 带 `.debug` 后缀，而 Manifest 相对名按 manifest package
         * 解析，拼出来是不存在的类 ⇒ PMS 抛 "Component class does not exist"）。
         */
        val activityClass: String,
        val labelRes: Int,
        val iconRes: Int,
    )

    val variants: List<Variant> = listOf(
        Variant(DEFAULT_ID, RouteActivity::class.java.name, R.string.app_name, R.mipmap.ic_launcher),
        Variant("rikkahub", LauncherRikkaHubActivity::class.java.name, R.string.app_name_variant_rikkahub, R.mipmap.ic_launcher),
        Variant("chat", LauncherChatActivity::class.java.name, R.string.app_name_variant_chat, R.mipmap.ic_launcher_chat),
        Variant("notes", LauncherNotesActivity::class.java.name, R.string.app_name_variant_notes, R.mipmap.ic_launcher_notes),
        Variant("assistant", LauncherAssistantActivity::class.java.name, R.string.app_name_variant_assistant, R.mipmap.ic_launcher_assistant),
    )

    fun variantOf(id: String): Variant = variants.firstOrNull { it.id == id } ?: variants.first()

    private fun component(context: Context, activityClass: String) =
        ComponentName(context.packageName, activityClass)

    /** 当前系统里实际启用的预置 id（DEFAULT 状态 = 跟随 Manifest，即 RouteActivity 本体） */
    fun enabledVariantId(context: Context): String {
        val pm = context.packageManager
        return variants.firstOrNull { v ->
            when (pm.getComponentEnabledSetting(component(context, v.activityClass))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> v.id == DEFAULT_ID
                else -> false
            }
        }?.id ?: DEFAULT_ID
    }

    /**
     * 启用 [id] 对应的别名、禁用其余。幂等：已处于目标状态的组件跳过，
     * 因此可以反复调用——这也承担**自愈**职责：前台期的切换会被系统 PackageManager
     * 拒绝（Android 15 起禁止前台应用改自己的 launcher 入口），崩溃/失败可能留下
     * 「多个别名同时启用」的脏状态，等应用退到后台再调本方法即可清理对齐。
     * 先启用目标再禁其它，避免出现「没有任何 enabled 入口」的中间态。
     */
    fun apply(context: Context, id: String) {
        val pm = context.packageManager
        val target = variantOf(id)
        val ordered = listOf(target) + variants.filter { it.id != target.id }
        for (variant in ordered) {
            val component = component(context, variant.activityClass)
            val newState =
                if (variant.id == target.id) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(component) != newState) {
                Log.d(TAG, "set ${variant.activityClass} -> ${if (variant.id == target.id) "ENABLED" else "DISABLED"}")
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
        Log.d(TAG, "apply(id=$id) done")
    }
}
