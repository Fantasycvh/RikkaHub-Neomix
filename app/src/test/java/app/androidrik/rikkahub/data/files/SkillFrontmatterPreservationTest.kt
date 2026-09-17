package app.androidrik.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1901：frontmatter 的**行级**保留与分类读取。
 *
 * 两个重点：
 * 1. `category` 是标量，`.get` 能正常读到（这与 `tags:` 块式列表读不到形成对比，是本次
 *    只做单分类、暂缓标签的技术依据）；
 * 2. AI 覆盖保存时，`name`/`description` 之外的条目（含注释与块式列表）要**原样**带回，
 *    不能被重新序列化成别的形态。
 */
class SkillFrontmatterPreservationTest {

    private val withExtras = """
        ---
        name: word-documents
        description: Create and edit Word documents
        # 用户的注释
        category: office
        tags:
          - word
          - document
        compatibility: requires python
        ---

        body content
    """.trimIndent()

    @Test
    fun `category scalar is readable`() {
        assertEquals("office", SkillFrontmatterParser.parse(withExtras)["category"])
    }

    @Test
    fun `block style list is NOT readable through scalar get`() {
        // 这正是 issue 正文给的 tags 写法：块式列表经 `as? String` 得到 null。
        // 记录此行为，避免日后误以为「导入的标签生效了」。
        assertNull(SkillFrontmatterParser.parse(withExtras)["tags"])
        // 逗号分隔的字符串则能读到（另一种常见写法）
        val inline = "---\nname: a\ndescription: b\ntags: word, document\n---\n"
        assertEquals("word, document", SkillFrontmatterParser.parse(inline)["tags"])
    }

    @Test
    fun `entries split into top level keys with their continuation lines`() {
        val entries = splitFrontmatterEntries(withExtras)
        assertEquals(
            listOf("name", "description", "category", "tags", "compatibility"),
            entries.map { it.key },
        )
        // 块式列表的两行都属于 tags 条目
        assertEquals(3, entries.first { it.key == "tags" }.lines.size)
    }

    @Test
    fun `comment above a key belongs to that key so it survives preservation`() {
        // 注释归属**下一个**键（YAML 惯例）：若归属上一个键，位于 category 上方的注释
        // 会被算进 description，而 description 属于被排除项 → 注释连同分类一起消失。
        val entries = splitFrontmatterEntries(withExtras)
        val category = entries.first { it.key == "category" }
        assertTrue(
            "注释应随 category 一起保留",
            category.lines.any { it.trimStart().startsWith("#") },
        )
    }

    @Test
    fun `other entry lines preserve originals excluding name and description`() {
        val preserved = otherFrontmatterEntryLines(withExtras, excludeKeys = setOf("name", "description"))
        val joined = preserved.joinToString("\n")
        assertTrue("注释应保留", joined.contains("# 用户的注释"))
        assertTrue("分类应保留", joined.contains("category: office"))
        assertTrue("块式列表应保留两行", joined.contains("- word") && joined.contains("- document"))
        assertTrue("其它键应保留", joined.contains("compatibility: requires python"))
        assertTrue("name/description 不应出现", !joined.contains("name: word") && !joined.contains("description:"))
    }

    @Test
    fun `build markdown with preserved lines keeps user category`() {
        val preserved = otherFrontmatterEntryLines(withExtras, excludeKeys = setOf("name", "description"))
        val md = SkillInstaller.buildSkillMarkdown(
            name = "word-documents",
            description = "new description",
            content = "new body",
            preservedFrontmatterLines = preserved,
        )
        val parsed = SkillFrontmatterParser.parse(md)
        assertEquals("office", parsed["category"])
        assertEquals("requires python", parsed["compatibility"])
        assertEquals("word-documents", parsed["name"])
        // 覆盖的是 description 与 body，其它原样
        assertEquals("new description", parsed["description"])
        assertTrue(md.contains("new body"))
        assertTrue("旧 body 不应残留", !md.contains("body content"))
    }

    @Test
    fun `content without frontmatter yields nothing to preserve`() {
        assertEquals(emptyList<String>(), otherFrontmatterEntryLines("plain text", excludeKeys = setOf("name")))
        assertTrue(splitFrontmatterEntries("plain text").isEmpty())
    }

    @Test
    fun `malformed lines do not throw and are absorbed`() {
        val weird = "---\nname: a\n: no key\nkey with spaces: x\n---\n"
        val entries = splitFrontmatterEntries(weird)
        // 畸形行（无键名 / 键名含空格）不会成为顶层键，也不会抛异常
        assertEquals(listOf("name"), entries.map { it.key })
    }
}
