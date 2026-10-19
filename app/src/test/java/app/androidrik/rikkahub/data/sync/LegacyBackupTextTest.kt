package app.androidrik.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原版备份导入的**纯文本规则**（不依赖 Android/SQLite，故可在 JVM 上跑）：
 * Markdown 记忆按顶层条目拆条、旧包名路径改写。
 *
 * 这两条规则直接决定导入后的记忆是否可用、附件是否还能打开，属于最容易静默出错的地方，
 * 因此与真实数据解耦地钉住。
 */
class LegacyBackupTextTest {

    @Test
    fun splitsTopLevelBulletsAndStripsSourceMarkers() {
        val markdown = """
            ---
            name: profile
            description: Who the user is
            ---

            - [stated] 用户喜欢手冲咖啡
            - [inferred] 用户常在晚上工作
            - 没有标记的条目
        """.trimIndent()
        assertEquals(
            listOf("用户喜欢手冲咖啡", "用户常在晚上工作", "没有标记的条目"),
            LegacyBackupText.entriesOf(markdown),
        )
    }

    @Test
    fun keepsContinuationLinesInsideOneEntry() {
        val markdown = """
            - 第一条
              续行一
              续行二
            - 第二条
        """.trimIndent()
        assertEquals(listOf("第一条\n续行一\n续行二", "第二条"), LegacyBackupText.entriesOf(markdown))
    }

    @Test
    fun frontMatterAloneIsNotAnEntry() {
        val onlyFrontMatter = "---\nname: profile\ndescription: x\n---\n"
        assertTrue(LegacyBackupText.entriesOf(onlyFrontMatter).isEmpty())
    }

    @Test
    fun contentWithoutBulletsFallsBackToWholeBody() {
        val plain = "---\nname: profile\n---\n\n用户偏好简洁回答。"
        assertEquals(listOf("用户偏好简洁回答。"), LegacyBackupText.entriesOf(plain))
    }

    @Test
    fun rewritesLegacyApplicationPathsOnly() {
        val text = """{"uri":"file:///data/user/0/me.rerere.rikkahub/files/upload/a.md"}"""
        val rewritten = LegacyBackupText.rewriteLegacyPaths(text, "app.androidrik.rikkahub")
        assertTrue(rewritten.contains("file:///data/user/0/app.androidrik.rikkahub/files/upload/a.md"))
        assertFalse(rewritten.contains("me.rerere.rikkahub/files"))
    }

    @Test
    fun rewritesDebugSuffixAndLegacyForkRoot() {
        val text = listOf(
            "/data/data/me.rerere.rikkahub.debug/files/upload/a.md",
            "/data/user/0/app.fantasycvh.rikkahub/files/upload/b.md",
        ).joinToString(" ")
        val rewritten = LegacyBackupText.rewriteLegacyPaths(text, "app.androidrik.rikkahub")
        assertFalse(rewritten.contains("me.rerere.rikkahub.debug"))
        assertFalse(rewritten.contains("app.fantasycvh.rikkahub"))
        assertEquals(2, Regex("file:///data/user/0/app\\.androidrik\\.rikkahub/files/upload/").findAll(rewritten).count())
    }

    @Test
    fun packageNamesInsidePlainTextAreNotRewritten() {
        // 旧版堆栈信息属于用户可见正文，不能因为含包名就被改写
        val trace = "at me.rerere.rikkahub.data.ai.tools.LocalTools.invoke(LocalTools.kt:27)"
        assertEquals(trace, LegacyBackupText.rewriteLegacyPaths(trace, "app.androidrik.rikkahub"))
    }
}
