package app.androidrik.rikkahub.ui.pages.assistant.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantImporterTest {

    private fun parse(raw: String) =
        parseAssistantFromJson(context = null, json = Json.parseToJsonElement(raw).jsonObject, background = null)

    @Test
    fun `v1 老卡（无 spec 顶层即字段）可导入`() {
        val assistant = parse(
            """
            {
              "name": "Alice",
              "description": "A cheerful girl",
              "first_mes": "Hello!",
              "personality": "cheerful",
              "scenario": "At the park",
              "mes_example": "Alice: Hi there!"
            }
            """.trimIndent()
        )
        assertEquals("Alice", assistant.name)
        assertEquals(1, assistant.presetMessages.size)
        assertTrue(assistant.systemPrompt.contains("You are roleplaying as Alice."))
        assertTrue(assistant.systemPrompt.contains("## Description of the character"))
        assertTrue(assistant.systemPrompt.contains("## Example of the dialogue"))
        assertTrue(assistant.systemPrompt.contains("Alice: Hi there!"))
    }

    @Test
    fun `v1 卡缺少 first_mes 时无预设消息`() {
        val assistant = parse(
            """{"name": "Bob", "description": "quiet"}""".trimIndent()
        )
        assertEquals("Bob", assistant.name)
        assertEquals(0, assistant.presetMessages.size)
    }

    @Test
    fun `v2 卡（spec + data 包裹）可导入`() {
        val assistant = parse(
            """
            {
              "spec": "chara_card_v2",
              "spec_version": "2.0",
              "data": {
                "name": "Carol",
                "description": "Traveller",
                "first_mes": "Where to?"
              }
            }
            """.trimIndent()
        )
        assertEquals("Carol", assistant.name)
        assertEquals(1, assistant.presetMessages.size)
        assertTrue(assistant.systemPrompt.contains("You are roleplaying as Carol."))
    }

    @Test
    fun `v3 卡（spec + data）可导入`() {
        val assistant = parse(
            """
            {
              "spec": "chara_card_v3",
              "data": {
                "name": "Dave",
                "first_mes": "Hey"
              }
            }
            """.trimIndent()
        )
        assertEquals("Dave", assistant.name)
        assertEquals(1, assistant.presetMessages.size)
    }

    @Test
    fun `无 spec 且顶层无 name 时视为非角色卡并报错`() {
        val exception = try {
            parse("""{"foo": 1, "bar": "baz"}""".trimIndent())
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(exception != null)
        assertTrue(exception!!.message!!.contains("spec"))
    }

    @Test
    fun `未知 spec 报 unsupported 错`() {
        val exception = try {
            parse("""{"spec": "chara_card_v9", "data": {"name": "X"}}""".trimIndent())
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(exception != null)
        assertTrue(exception!!.message!!.contains("Unsupported spec"))
    }

    @Test
    fun `酒馆预设文件被识别为非角色卡并给指引`() {
        val exception = try {
            parse(
                """
                {
                  "prompts": [{"name": "p", "content": "x"}],
                  "prompt_order": []
                }
                """.trimIndent()
            )
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(exception != null)
        assertTrue(exception!!.message!!.contains("preset"))
    }
}
