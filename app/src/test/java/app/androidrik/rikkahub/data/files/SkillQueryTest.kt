package app.androidrik.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1901：技能搜索与单分类筛选（纯函数）。
 *
 * 钉住三件事：搜索匹配范围（名称 + 描述）、空查询不过滤、**分类与搜索是「与」关系**。
 */
class SkillQueryTest {

    private fun skill(
        name: String,
        description: String = "",
        category: String? = null,
    ) = SkillMetadata(
        name = name,
        description = description,
        category = category,
        skillDir = File("/tmp/$name"),
    )

    private val skills = listOf(
        skill("word-documents", "Create and edit Word documents", "office"),
        skill("pdf-tools", "Merge and split PDF files", "office"),
        skill("github", "GitHub operations", "dev"),
        skill("scratch", "Unfiled helper"),
    )

    @Test
    fun `empty query and all category returns everything`() {
        assertEquals(skills, skills.filterSkills())
    }

    @Test
    fun `matches name and description case-insensitively`() {
        assertEquals(listOf("pdf-tools"), skills.filterSkills(query = "PDF").map { it.name })
        assertEquals(listOf("github"), skills.filterSkills(query = "github").map { it.name })
        // 描述命中
        assertEquals(listOf("word-documents"), skills.filterSkills(query = "edit word").map { it.name })
    }

    @Test
    fun `query is trimmed and does not match unrelated skills`() {
        assertEquals(listOf("github"), skills.filterSkills(query = "  github  ").map { it.name })
        assertEquals(emptyList<SkillMetadata>(), skills.filterSkills(query = "nope"))
    }

    @Test
    fun `single category filters exactly`() {
        assertEquals(
            listOf("word-documents", "pdf-tools"),
            skills.filterSkills(category = SkillCategoryFilter.Named("office")).map { it.name },
        )
        assertEquals(
            listOf("github"),
            skills.filterSkills(category = SkillCategoryFilter.Named("dev")).map { it.name },
        )
    }

    @Test
    fun `uncategorized matches skills without category`() {
        assertEquals(
            listOf("scratch"),
            skills.filterSkills(category = SkillCategoryFilter.Uncategorized).map { it.name },
        )
    }

    @Test
    fun `query and category combine with and`() {
        // github 在 dev 分类下：用 office 分类搜索它应命中 0 条（而不是忽略分类）
        assertEquals(
            emptyList<SkillMetadata>(),
            skills.filterSkills(query = "github", category = SkillCategoryFilter.Named("office")),
        )
        assertEquals(
            listOf("pdf-tools"),
            skills.filterSkills(query = "pdf", category = SkillCategoryFilter.Named("office")).map { it.name },
        )
    }

    @Test
    fun `blank category string counts as uncategorized`() {
        val list = listOf(skill("a", category = "  "), skill("b", category = "office"))
        assertEquals(
            listOf("a"),
            list.filterSkills(category = SkillCategoryFilter.Uncategorized).map { it.name },
        )
    }

    @Test
    fun `distinct categories are deduplicated sorted and drop blanks`() {
        val list = listOf(
            skill("a", category = "office"),
            skill("b", category = "dev"),
            skill("c", category = "office"),
            skill("d"),
            skill("e", category = "  "),
        )
        assertEquals(listOf("dev", "office"), list.distinctCategories())
    }
}
