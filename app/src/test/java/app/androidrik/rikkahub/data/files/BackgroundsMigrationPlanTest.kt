package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 背景图迁移**判据**的 JVM 单测（#2127 同款坑的背景图版，2026-10-31）。
 *
 * 只管「哪些引用要搬、搬到哪」这个纯函数（`BackgroundsMigration.planBackgroundMigration`）；
 * 真正的 rename / DataStore 写入走 `migrate`（依赖 Context 与 SettingsStore，JVM 够不着）。
 * 判据必须足够窄：只认 `/upload/` 这个路径段——宽一点会把 `images/` 或已经是
 * `backgrounds/` 的引用也列进搬迁计划，去动本不该动的文件。
 */
class BackgroundsMigrationPlanTest {

    private fun fileUrl(path: String) = "file://$path"

    private fun plan(vararg urls: String) =
        BackgroundsMigration.planBackgroundMigration(urls.toList())

    @Test
    fun `background in upload is planned into backgrounds`() {
        val old = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/bg12.jpg")
        assertEquals(
            "upload/ 下的助手背景必须搬进 backgrounds/，且保持文件名不变",
            mapOf(old to fileUrl("/data/user/0/app.androidrik.rikkahub/files/backgrounds/bg12.jpg")),
            plan(old),
        )
    }

    @Test
    fun `already migrated background is idempotent`() {
        val url = fileUrl("/data/user/0/app.androidrik.rikkahub/files/backgrounds/bg.jpg")
        assertTrue("已是 backgrounds/ 的引用不能再进计划（幂等）", plan(url).isEmpty())
    }

    @Test
    fun `remote url and other folders are not planned`() {
        assertTrue("http(s) 外链背景不动", plan("https://example.com/bg.jpg").isEmpty())
        assertTrue(
            "images/ 等其他目录不是本迁移的对象（判据必须只认 /upload/）",
            plan(fileUrl("/data/user/0/app.androidrik.rikkahub/files/images/pic.jpg")).isEmpty(),
        )
    }

    @Test
    fun `multiple assistants each get their own mapping`() {
        val a = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/a.png")
        val b = fileUrl("/data/user/0/app.androidrik.rikkahub/files/upload/b.webp")
        assertEquals(
            mapOf(
                a to fileUrl("/data/user/0/app.androidrik.rikkahub/files/backgrounds/a.png"),
                b to fileUrl("/data/user/0/app.androidrik.rikkahub/files/backgrounds/b.webp"),
            ),
            plan(a, b),
        )
    }

    @Test
    fun `wiring collects every assistant background`() {
        // 接线判据：migrate 用 assistants.mapNotNull { it.background } 取 URL——
        // 钉住「背景为 null 的助手不出现在收集结果里」
        val assistants = listOf(
            Assistant(id = Uuid.random(), name = "a", background = fileUrl("/f/files/upload/x.jpg")),
            Assistant(id = Uuid.random(), name = "b", background = null),
        )
        assertEquals(listOf(fileUrl("/f/files/upload/x.jpg")), assistants.mapNotNull { it.background })
    }
}
