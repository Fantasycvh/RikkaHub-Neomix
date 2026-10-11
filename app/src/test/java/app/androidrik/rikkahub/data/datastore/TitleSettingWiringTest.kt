package app.androidrik.rikkahub.data.datastore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「自动标题」开关的**接线守卫**（源码扫描）。
 *
 * 起因：这类开关的失效方式是**静默**的——key 字符串写错、只写了读没写回、或 UI 的 `copy(...)` 漏了字段，
 * 编译全都通过，表现为「拨了开关但重启后又变回来」或「开关根本没接上」。三处（key / 读 / 写）
 * 必须一起存在，所以按本仓的守卫习惯扫一遍源码钉住。
 */
class TitleSettingWiringTest {

    @Test
    fun `the auto title switch is persisted end to end`() {
        val store = read("app/androidrik/rikkahub/data/datastore/PreferencesStore.kt")

        assertTrue(
            "缺少偏好键：开关无法落盘",
            store.contains("""booleanPreferencesKey("enable_auto_title")"""),
        )
        assertTrue(
            "缺少读取（或读取没有把缺键当默认开）",
            store.contains("enableAutoTitle = preferences[ENABLE_AUTO_TITLE] != false"),
        )
        assertTrue(
            "缺少写回：关掉后重启会变回开",
            store.contains("preferences[ENABLE_AUTO_TITLE] = settings.enableAutoTitle"),
        )
        assertTrue(
            "Settings 里必须存在该字段且默认 true（老用户行为零变化）",
            store.contains("val enableAutoTitle: Boolean = true,"),
        )
    }

    @Test
    fun `the setting page exposes the switch and writes it`() {
        val page = read("app/androidrik/rikkahub/ui/pages/setting/SettingModelPage.kt")

        assertTrue(
            "设置页没有把这个开关接上 updateSettings",
            page.contains("settings.copy(enableAutoTitle = it)"),
        )
        assertTrue(
            "开关没有绑定到设置值",
            page.contains("checked = settings.enableAutoTitle"),
        )
        assertTrue(
            "关掉时应当隐藏标题模型选择器（与「建议模型」同形）",
            page.contains("if (settings.enableAutoTitle)"),
        )
    }

    /** 判定本身（开关/手动/标题现状）在 `TitleGenerationPolicyTest` 里测；这里只管接线。 */
    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }
}
