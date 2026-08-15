package app.androidrik.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallerTest {

    // ---- parseGitHubUrl ----

    @Test
    fun `parse github url - root repo`() {
        val info = SkillInstaller.parseGitHubUrl("https://github.com/owner/repo")
        assertNotNull(info)
        assertEquals("owner", info!!.owner)
        assertEquals("repo", info.repo)
        assertEquals("HEAD", info.branch)
        assertEquals("", info.path)
    }

    @Test
    fun `parse github url - branch tree`() {
        val info = SkillInstaller.parseGitHubUrl("https://github.com/owner/repo/tree/main")
        assertNotNull(info)
        assertEquals("main", info!!.branch)
        assertEquals("", info.path)
    }

    @Test
    fun `parse github url - branch with sub path`() {
        val info = SkillInstaller.parseGitHubUrl("https://github.com/owner/repo/tree/main/skills/my-skill")
        assertNotNull(info)
        assertEquals("main", info!!.branch)
        assertEquals("skills/my-skill", info.path)
    }

    @Test
    fun `parse github url - trailing slash trimmed`() {
        val info = SkillInstaller.parseGitHubUrl("https://github.com/owner/repo/tree/main/")
        assertNotNull(info)
        assertEquals("main", info!!.branch)
        assertEquals("", info.path)
    }

    @Test
    fun `parse github url - rejects non-github or malformed`() {
        assertNull(SkillInstaller.parseGitHubUrl("https://example.com/owner/repo"))
        assertNull(SkillInstaller.parseGitHubUrl("not-a-url"))
        assertNull(SkillInstaller.parseGitHubUrl("https://github.com/onlyowner"))
    }

    // ---- validateSkillContent ----

    @Test
    fun `validate skill content - requires name and description`() {
        assertNotNull(SkillInstaller.validateSkillContent("", "desc", "body"))
        assertNotNull(SkillInstaller.validateSkillContent("name", "", "body"))
        assertNotNull(SkillInstaller.validateSkillContent("name", "desc", ""))
    }

    @Test
    fun `validate skill content - rejects traversal names`() {
        assertNotNull(SkillInstaller.validateSkillContent("../evil", "desc", "body"))
        assertNotNull(SkillInstaller.validateSkillContent("a/b", "desc", "body"))
        assertNotNull(SkillInstaller.validateSkillContent("a\\b", "desc", "body"))
    }

    @Test
    fun `validate skill content - accepts valid skill`() {
        assertNull(SkillInstaller.validateSkillContent("my-skill", "desc", "body"))
    }

    @Test
    fun `build skill markdown - generates frontmatter`() {
        val md = SkillInstaller.buildSkillMarkdown("my-skill", "my desc", "body text")
        val parsed = SkillFrontmatterParser.parse(md)
        assertEquals("my-skill", parsed["name"])
        assertEquals("my desc", parsed["description"])
        assertTrue(md.contains("body text"))
    }
}
