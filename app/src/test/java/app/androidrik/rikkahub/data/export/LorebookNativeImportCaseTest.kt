package app.androidrik.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.ai.core.MessageRole

/**
 * 原生信封导入的枚举大小写兼容（`withCaseInsensitiveEnums`）。
 *
 * 背景：外部工具按本仓信封导出的世界书里，枚举值写的是 Kotlin 常量名（大写），
 * 而 kotlinx.serialization 只认 `@SerialName` 的小写形式，一处不符整份文件报「格式不支持」。
 */
class LorebookNativeImportCaseTest {

    private fun envelope(entriesJson: String): String = """
        {
          "version": 1,
          "type": "lorebook",
          "data": {
            "id": "bccd6354-04b1-41f6-8907-009263d96120",
            "name": "主 AGENT",
            "description": "",
            "enabled": true,
            "entries": [$entriesJson]
          }
        }
    """.trimIndent()

    // 外部工具的同形条目：无 type 判别符、position/role 大写、含 constantActive
    private val externalEntry = """
        {
          "id": "12b0c4b5-73ac-4146-a05d-33ab8432cb30",
          "name": "这份在什么情况下适用",
          "enabled": true,
          "priority": 200,
          "position": "AFTER_SYSTEM_PROMPT",
          "content": "内容",
          "injectDepth": 4,
          "role": "USER",
          "keywords": ["适用"],
          "useRegex": false,
          "caseSensitive": false,
          "scanDepth": 4,
          "constantActive": true
        }
    """.trimIndent()

    private fun decode(json: String): Lorebook {
        val exportData = ExportSerializer.DefaultJson.decodeFromString(ExportData.serializer(), json)
        check(exportData.type == LorebookSerializer.type) { "wrong type" }
        return ExportSerializer.DefaultJson.decodeFromJsonElement<Lorebook>(
            ExportSerializer.withCaseInsensitiveEnums(exportData.data)
        )
    }

    @Test
    fun `uppercase enum values from external tools decode`() {
        val lorebook = decode(envelope(externalEntry))
        assertEquals(1, lorebook.entries.size)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, lorebook.entries[0].position)
        assertEquals(MessageRole.USER, lorebook.entries[0].role)
        assertEquals(true, lorebook.entries[0].constantActive)
        assertEquals(listOf("适用"), lorebook.entries[0].keywords)
    }

    @Test
    fun `lowercase serial names still decode`() {
        val lorebook = decode(
            envelope(
                """
                {
                  "id": "12b0c4b5-73ac-4146-a05d-33ab8432cb30",
                  "name": "条目",
                  "enabled": true,
                  "priority": 0,
                  "position": "at_depth",
                  "content": "内容",
                  "injectDepth": 2,
                  "role": "assistant"
                }
                """.trimIndent()
            )
        )
        assertEquals(InjectionPosition.AT_DEPTH, lorebook.entries[0].position)
        assertEquals(MessageRole.ASSISTANT, lorebook.entries[0].role)
    }

    @Test
    fun `invalid enum value still fails instead of silently succeeding`() {
        assertThrows(Exception::class.java) {
            decode(
                envelope(
                    externalEntry.replace("\"AFTER_SYSTEM_PROMPT\"", "\"NOWHERE\"")
                )
            )
        }
    }
}
