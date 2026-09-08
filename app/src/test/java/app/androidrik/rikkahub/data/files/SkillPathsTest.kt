package app.androidrik.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillPathsTest {
    @Test
    fun `parse supports CRLF frontmatter`() {
        val content = "---\r\nname: test-skill\r\ndescription: test\r\n---\r\n\r\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertEquals("test-skill", frontmatter["name"])
        assertEquals("test", frontmatter["description"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `parse supports YAML syntax`() {
        val content = """
            ---
            name: test-skill
            description: |
              A multiline description
              with a colon: supported
            metadata:
              author: tester
            ---
            body
        """.trimIndent()

        val frontmatter = SkillFrontmatterParser.parse(content)

        // SnakeYAML 正规解析：支持多行/冒号值（对齐上游 54030ec4，手写逐行解析会错）
        assertEquals("test-skill", frontmatter["name"])
        assertEquals(
            "A multiline description\nwith a colon: supported\n",
            frontmatter["description"],
        )
    }

    @Test
    fun `parse returns empty frontmatter for malformed YAML`() {
        val content = "---\nname: [invalid\n---\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        // 畸形 YAML 回退空 frontmatter，不抛异常；正文仍可抽取
        assertNull(frontmatter["name"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `resolve skill dir rejects traversal and nested names`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()

        try {
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "../upload"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo/bar"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo\\bar"))
            assertNotNull(SkillPaths.resolveSkillDir(skillsRoot, "valid-skill"))
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    @Test
    fun `resolve skill file rejects sibling prefix escape`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()
        val skillDir = File(skillsRoot, "foo").apply { mkdirs() }
        File(skillsRoot, "foobar").apply { mkdirs() }

        try {
            val safeFile = SkillPaths.resolveSkillFile(skillDir, "notes.md")
            val escapedFile = SkillPaths.resolveSkillFile(skillDir, "../foobar/secret.md")

            assertEquals(File(skillDir, "notes.md").canonicalFile, safeFile)
            assertNull(escapedFile)
        } finally {
            skillsRoot.deleteRecursively()
        }
    }
}
