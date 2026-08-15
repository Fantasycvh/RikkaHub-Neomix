package app.androidrik.rikkahub.data.ai.tools

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.rikkahub.data.files.SkillSaver

class SkillInstallToolsTest {

    private class FakeSkillSaver(
        var installResult: Result<SkillMetadata> = Result.failure(IllegalStateException("not set")),
        var saveResult: Result<SkillMetadata> = Result.failure(IllegalStateException("not set")),
    ) : SkillSaver {
        var installCalled = false
        var saveCalled = false
        override suspend fun installFromGitHub(repoUrl: String): Result<SkillMetadata> {
            installCalled = true
            return installResult
        }
        override suspend fun saveFromContent(name: String, description: String, content: String): Result<SkillMetadata> {
            saveCalled = true
            return saveResult
        }
    }

    private fun metadata(name: String) = SkillMetadata(
        name = name,
        description = "desc",
        skillDir = File("tmp/$name"),
    )

    @Test
    fun `install skill tool exposes github url parameter and calls saver`() = runBlocking {
        val fake = FakeSkillSaver(installResult = Result.success(metadata("git-skill")))
        val tools = createSkillInstallTools(fake)
        assertEquals(2, tools.size)

        val install = tools[0]
        assertEquals("install_skill", install.name)
        val schema = install.parameters() as InputSchema.Obj
        assertTrue(schema.required == listOf("github_url"))

        val parts = install.execute(
            buildJsonObject { put("github_url", "https://github.com/o/r") }
        )
        assertTrue(fake.installCalled)
        val text = (parts[0] as UIMessagePart.Text).text
        assertTrue(text.contains("git-skill"))
    }

    @Test
    fun `install skill tool reports failure message`() = runBlocking {
        val fake = FakeSkillSaver(installResult = Result.failure(IllegalStateException("no SKILL.md")))
        val tools = createSkillInstallTools(fake)
        var thrown: Exception? = null
        try {
            tools[0].execute(buildJsonObject { put("github_url", "https://github.com/o/r") })
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertTrue(thrown!!.message!!.contains("no SKILL.md"))
    }

    @Test
    fun `save skill tool requires name description content`() = runBlocking {
        val fake = FakeSkillSaver(saveResult = Result.success(metadata("saved-skill")))
        val tools = createSkillInstallTools(fake)
        val save = tools[1]
        assertEquals("save_skill", save.name)
        val schema = save.parameters() as InputSchema.Obj
        assertEquals(listOf("name", "description", "content"), schema.required)

        val parts = save.execute(
            buildJsonObject {
                put("name", "my-skill")
                put("description", "desc")
                put("content", "body")
            }
        )
        assertTrue(fake.saveCalled)
        val text = (parts[0] as UIMessagePart.Text).text
        assertTrue(text.contains("saved-skill"))
    }

    @Test
    fun `save skill tool reports validation failure`() = runBlocking {
        val fake = FakeSkillSaver(saveResult = Result.failure(IllegalArgumentException("缺少 name 字段")))
        val tools = createSkillInstallTools(fake)
        var thrown: Exception? = null
        try {
            tools[1].execute(
                buildJsonObject {
                    put("name", "")
                    put("description", "d")
                    put("content", "c")
                }
            )
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertTrue(thrown!!.message!!.contains("缺少 name 字段"))
    }
}
