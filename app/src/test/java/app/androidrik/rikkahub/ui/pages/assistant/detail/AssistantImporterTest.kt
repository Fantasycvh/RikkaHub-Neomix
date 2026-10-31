package app.androidrik.rikkahub.ui.pages.assistant.detail

import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.ImportSourceRecord
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
            .assistant

    /** 未解包结果（世界书拆分测试用）。 */
    private fun parseRaw(raw: String) =
        parseAssistantFromJson(context = null, json = Json.parseToJsonElement(raw).jsonObject, background = null)

    // ---- #1942：从**文本**导入（对话内 JSON 代码块入口）----
    //
    // 该入口只比 parseAssistantFromJson 多一层「文本 → JsonObject」，
    // 所以这里钉的是那层的行为：合法 JSON 照常解析、不合法给可读错误、
    // 解析层既有的友好指引（预设误认等）对文本入口自动生效。

    @Test
    fun `文本入口可解析 v2 卡`() {
        val assistant = parseAssistantFromJsonText(
            context = null,
            jsonText = """
                {
                  "spec": "chara_card_v2",
                  "data": { "name": "文本入口卡", "description": "desc" }
                }
            """.trimIndent(),
        ).assistant

        assertEquals("文本入口卡", assistant.name)
        assertTrue(assistant.hasCardParts)
    }

    @Test
    fun `文本入口对不合法 JSON 给出可读错误而非序列化异常`() {
        val error = runCatching {
            parseAssistantFromJsonText(context = null, jsonText = "{ 这不是合法 JSON ")
        }.exceptionOrNull()

        assertTrue(error != null)
        // context = null 时回落到英文兜底串；关键是**不是** kotlinx 的 SerializationException
        assertTrue(
            "unexpected: ${error!!.javaClass.name} / ${error.message}",
            error.message?.contains("Invalid JSON") == true,
        )
    }

    @Test
    fun `文本入口对合法但非角色卡的 JSON 沿用解析层的指引`() {
        // 酒馆预设文件（prompts + prompt_order）——解析层的「这是预设不是角色卡」指引
        // 对文本入口同样生效（这正是共用 parseAssistantFromJson 的收益）
        val error = runCatching {
            parseAssistantFromJsonText(
                context = null,
                jsonText = """{"prompts":[],"prompt_order":[]}""",
            )
        }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(
            "unexpected: ${error!!.message}",
            error.message?.contains("preset") == true,
        )
    }

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

    // region 内嵌世界书拆分（design-card-lorebook-import.md：拆独立世界书 + id 绑定）

    @Test
    fun `v2 卡内嵌世界书拆分为独立 Lorebook 并预绑助手`() {
        val result = parseRaw(
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Alice",
                "character_book": {
                  "name": "Alice 的书",
                  "scan_depth": 6,
                  "entries": [
                    { "keys": ["dragon"], "content": "Dragon lore", "insertion_order": 5 }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val book = result.lorebook!!
        assertEquals("Alice 的书", book.name)
        assertEquals(1, book.entries.size)
        assertEquals(listOf("dragon"), book.entries[0].keywords)
        assertEquals(5, book.entries[0].priority)
        assertEquals(6, book.entries[0].scanDepth)
        // 绑定：书 id 已写进助手
        assertEquals(setOf(book.id), result.assistant.lorebookIds)
    }

    @Test
    fun `无内嵌世界书的卡 lorebook 为 null 且不绑定`() {
        val result = parseRaw("""{"spec": "chara_card_v2", "data": {"name": "Bob"}}""")
        assertNull(result.lorebook)
        assertTrue(result.assistant.lorebookIds.isEmpty())
    }

    @Test
    fun `内嵌世界书条目为空时不建书也不绑定`() {
        val result = parseRaw(
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Bob",
                "character_book": { "name": "空书", "entries": [] }
              }
            }
            """.trimIndent()
        )
        assertNull(result.lorebook)
        assertTrue(result.assistant.lorebookIds.isEmpty())
    }

    @Test
    fun `内嵌世界书损坏时助手照常导入（无书）`() {
        // ST 同款行为：卡照常导入，书失败不拦整个卡（此层无 toast 通道，降级为无书）
        val result = parseRaw(
            """
            {
              "spec": "chara_card_v2",
              "data": {
                "name": "Carol",
                "character_book": { "entries": "不是数组" }
              }
            }
            """.trimIndent()
        )
        assertEquals("Carol", result.assistant.name)
        assertNull(result.lorebook)
        assertTrue(result.assistant.lorebookIds.isEmpty())
    }

    // endregion

    // region 重复导入检测（design-card-lorebook-import.md §六：卡内容指纹）

    @Test
    fun `同一张卡两次解析指纹一致`() {
        val raw = """
            {
              "spec": "chara_card_v2",
              "data": { "name": "Alice", "description": "d" }
            }
        """.trimIndent()
        assertEquals(parseRaw(raw).sourceKey, parseRaw(raw).sourceKey)
    }

    @Test
    fun `指纹只看卡数据不看书不_hashed 外层信封`() {
        // hash 的是 data 对象：外层 spec_version 等信封字段不同不影响指纹
        val a = parseRaw(
            """{"spec": "chara_card_v2", "spec_version": "2.0", "data": {"name": "Alice"}}"""
        ).sourceKey
        val b = parseRaw(
            """{"spec": "chara_card_v2", "spec_version": "2.1", "data": {"name": "Alice"}}"""
        ).sourceKey
        assertEquals(a, b)
    }

    @Test
    fun `内容不同的卡指纹不同`() {
        val a = parseRaw("""{"spec": "chara_card_v2", "data": {"name": "Alice"}}""").sourceKey
        val b = parseRaw("""{"spec": "chara_card_v2", "data": {"name": "Bob"}}""").sourceKey
        assertTrue(a != null && b != null && a != b)
    }

    @Test
    fun `v1 老卡也有指纹`() {
        assertTrue(parseRaw("""{"name": "Old", "description": "v1"}""").sourceKey != null)
    }

    @Test
    fun `记录 upsert 空指纹不动_非空指纹加前缀键`() {
        val existing = mapOf("uuid-of-conversation" to ImportSourceRecord(importedAt = 1L, title = "会话"))
        // null 指纹（手工创建等）→ 原样返回
        assertEquals(existing, upsertCardSourceRecord(existing, null, "Alice"))
        // 正常路径：card: 前缀隔离键空间，title 空白降为 null
        val updated = upsertCardSourceRecord(existing, "abc", "Alice", now = 42L)
        assertEquals(2, updated.size)
        val record = updated["card:abc"]!!
        assertEquals(42L, record.importedAt)
        assertEquals("Alice", record.title)
        assertEquals(ImportSourceRecord(1L, "会话"), updated["uuid-of-conversation"])
        // 二次导入刷新时间戳而非新增键
        val refreshed = upsertCardSourceRecord(updated, "abc", "Alice", now = 99L)
        assertEquals(2, refreshed.size)
        assertEquals(99L, refreshed["card:abc"]!!.importedAt)
    }

    // endregion
}
