package app.androidrik.rikkahub.data.files

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 头像迁移改写引用时**不许走 `SettingsStore.update { }`**（2026-10-13 真机事故的守卫）。
 *
 * ## 失效形态
 *
 * `SettingsStore.update(Settings)` 开头是：
 *
 * ```kotlin
 * if (settings.init) { Log.w(TAG, "Cannot update dummy settings"); return }
 * ```
 *
 * 而头像迁移在**启动极早期**运行（`RikkaHubApp.onCreate`），此时 `settingsFlow.value` 仍是
 * `Settings.dummy()`（`init = true`）⇒ 走 `update { }` 的改写会被**静默丢弃**，
 * 只留一行日志。文件已经搬到 `avatars/`，引用却仍指向 `upload/` —— 引用当场悬空，
 * 表现就是「助手头像显示不出来」。真机实证：日志打出 `init=true` 才定位到（迁移报
 * `moved 5`，但设置里一条引用都没改）。
 *
 * 因此改写必须走 `rewriteAvatarReferences`（内部用 `dataStore.edit` 做定点原子改写，
 * 不依赖 `settingsFlow` 是否就绪）。
 *
 * ## 局限
 *
 * 源码扫描只能证明**接线**（用的是 `dataStore.edit` 而不是 `update`），证明不了运行时行为
 * —— 那需要真机或 instrumented test（本模块没有）。它是防回退的钉子，不是行为证明。
 */
class AvatarReferenceRewriteWiringTest {

    private fun source(relative: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
        val file = root.resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }

    @Test
    fun `rewrite uses datastore edit rather than the guarded settings update`() {
        val store = source("app/androidrik/rikkahub/data/datastore/PreferencesStore.kt")
        val fn = store.substringAfter("suspend fun rewriteAvatarReferences")
            .substringBefore("\n    suspend fun")
            .substringBefore("\n    fun ")

        assertTrue(
            "必须用 dataStore.edit 定点改写（原子读-改-写，不经过 settingsFlow）",
            fn.contains("dataStore.edit"),
        )
        assertTrue(
            "必须碰 assistants 键",
            fn.contains("preferences[ASSISTANTS]"),
        )
        assertTrue(
            "必须碰 display_setting 键（用户头像也在这里）",
            fn.contains("preferences[DISPLAY_SETTING]"),
        )
        assertTrue(
            "改写前后要能判断「有没有变」，否则每次都重写一遍",
            fn.contains("rewritten != assistants"),
        )
    }

    @Test
    fun `avatar migration rewrites references through that helper`() {
        val migration = source("app/androidrik/rikkahub/data/files/AvatarMigration.kt")

        assertTrue(
            "迁移必须调 rewriteAvatarReferences",
            migration.contains("settingsStore.rewriteAvatarReferences(urlMap)"),
        )
        // 反向钉子：迁移里**不许**再出现 update { }（那正是被 init 守卫静默丢弃的写法）
        val code = migration
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        assertTrue(
            "迁移里不许用 SettingsStore.update { }（启动早期 init=true 会把改写静默丢弃）",
            !code.contains("settingsStore.update {"),
        )
    }
}
