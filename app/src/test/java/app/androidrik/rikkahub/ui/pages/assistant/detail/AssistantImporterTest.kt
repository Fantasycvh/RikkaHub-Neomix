package app.androidrik.rikkahub.ui.pages.assistant.detail

import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.hasCardParts
import app.androidrik.rikkahub.data.model.rebuildSystemPromptFromCardParts
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

    // region 分区零件保留（§6.2 ④：导入不再压平后丢槽位）

    @Test
    fun `导入保留分区零件而不只是压平后的字符串`() {
        val assistant = parse(
            """
            {
              "name": "Alice",
              "description": "A cheerful girl",
              "personality": "cheerful",
              "scenario": "At the park",
              "mes_example": "Alice: Hi there!"
            }
            """.trimIndent()
        )
        assertEquals("A cheerful girl", assistant.cardDescription)
        assertEquals("cheerful", assistant.cardPersonality)
        assertEquals("At the park", assistant.cardScenario)
        assertEquals("Alice: Hi there!", assistant.cardExampleDialogue)
        assertTrue(assistant.hasCardParts)
    }

    @Test
    fun `导入渲染与大改前逐字节一致`() {
        // **特征测试**：buildCardSystemPrompt 是原先内联 buildString 的抽取，
        // 输出必须逐字节不变（否则等于静默改变了所有新导入助手的请求内容）。
        val expected = buildString {
            appendLine("You are roleplaying as Alice.")
            appendLine()
            appendLine("You are a maid.")
            appendLine()
            appendLine("## Description of the character")
            appendLine("A cheerful girl")
            appendLine()
            appendLine("## Personality of the character")
            appendLine("cheerful")
            appendLine()
            appendLine("## Scenario")
            append("At the park")
            appendLine()
            appendLine()
            appendLine("## Example of the dialogue")
            append("Alice: Hi there!")
        }
        val assistant = parse(
            """
            {
              "name": "Alice",
              "system_prompt": "You are a maid.",
              "description": "A cheerful girl",
              "personality": "cheerful",
              "scenario": "At the park",
              "mes_example": "Alice: Hi there!"
            }
            """.trimIndent()
        )
        assertEquals(expected, assistant.systemPrompt)
    }

    @Test
    fun `缺失字段渲染为 Empty 占位符`() {
        val assistant = parse("""{"name": "Bob"}""".trimIndent())
        assertTrue(assistant.systemPrompt.contains("## Description of the character\nEmpty"))
        assertTrue(assistant.systemPrompt.contains("## Personality of the character\nEmpty"))
        assertTrue(assistant.systemPrompt.contains("## Scenario\nEmpty"))
        // 无示例对话时不追加该段
        assertTrue(!assistant.systemPrompt.contains("## Example of the dialogue"))
    }

    @Test
    fun `重建提示词与导入结果一致`() {
        val assistant = parse(
            """{"name": "Ann", "description": "d", "personality": "p", "scenario": "s"}""".trimIndent()
        )
        // 用户手改了发送文本后，一键重建应还原为零件渲染的结果
        val dirty = assistant.copy(systemPrompt = "手改过的内容")
        assertEquals(assistant.systemPrompt, dirty.rebuildSystemPromptFromCardParts().systemPrompt)
    }

    @Test
    fun `手工助手（无零件）重建不会清空提示词`() {
        val manual = Assistant(name = "Manual", systemPrompt = "keep me")
        val rebuilt = manual.rebuildSystemPromptFromCardParts()
        assertEquals("keep me", rebuilt.systemPrompt)
        assertTrue(!manual.hasCardParts)
    }

    // endregion
}
