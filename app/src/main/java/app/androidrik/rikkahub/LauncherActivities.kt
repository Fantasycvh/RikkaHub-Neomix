package app.androidrik.rikkahub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.androidrik.rikkahub.utils.LauncherAlias

/**
 * 桌面「名称 + 图标」预置入口（utils/LauncherAlias.kt 切换）。
 *
 * 必须是**真实 Activity**（RouteActivity 子类）而不是 activity-alias：
 * ColorOS/HyperOS 一类 ROM 的组件开关 hook 会对 alias 做「组件是否存在」校验，
 * alias 不在 activity 表里 ⇒ setComponentEnabledSetting 被拒（实测崩溃
 * IllegalArgumentException "Component class does not exist"，后台静默丢弃同理）；
 * 真实 Activity 放行（对齐 legado-with-MD3 的 Launcher0~6 做法）。
 * 静态快捷方式声明挂在 RouteActivity（默认入口）上，切走后长按不可见属预期。
 */
class LauncherRikkaHubActivity : RouteActivity()

class LauncherChatActivity : RouteActivity()

class LauncherNotesActivity : RouteActivity()

class LauncherAssistantActivity : RouteActivity()

/**
 * 桌面快捷方式的固定入口：**永不参与预置启停**（始终 enabled）。
 * 快捷方式若直接指向 [RouteActivity]，切换预置后 RouteActivity 会被禁用 ⇒
 * 点快捷方式无法启动（实测）。由本类转发到当前启用的入口 Activity。
 */
class ShortcutEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryClass = runCatching {
            Class.forName(LauncherAlias.variantOf(LauncherAlias.enabledVariantId(this)).activityClass)
        }.getOrNull() ?: RouteActivity::class.java
        startActivity(
            Intent(this, entryClass).apply {
                action = Intent.ACTION_MAIN
                if (getIntent()?.extras != null) putExtras(getIntent()!!.extras!!)
            }
        )
        finish()
    }
}
