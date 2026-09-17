package app.androidrik.rikkahub.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST 世界书导入的**拒绝**逻辑测试。
 *
 * 真机发现（2026-09-17）：设备上两个世界书都是「0 条目」，其中一个是用户导入的
 * ST **预设**文件（不是世界书）。根因是 `SillyTavernLorebook.entries` 声明为
 * **默认空 map**，配合 `ignoreUnknownKeys = true` ⇒ **任意 JSON 都能解码成
 * 「0 条目的世界书」并静默导入成功**。这比直接失败更糟：用户看到一个以文件名命名的
 * 世界书，以为导入成功了。
 *
 * 修复：`entries` 改为可空 + 无默认值，缺失即判为「不是世界书」。
 */
class SillyTavernLorebookRejectTest {

    @Test
    fun `valid st lorebook is accepted`() {
        val json = """
            {
              "entries": {
                "0": {"key": ["dragon"], "content": "Dragon lore", "comment": "dragon"}
              }
            }
        """.trimIndent()
        val book = parseSillyTavernLorebook(json, "my-lorebook")
        assertNotNull(book)
        assertEquals("my-lorebook", book!!.name)
        assertEquals(1, book.entries.size)
    }

    @Test
    fun `empty entries map is accepted as an empty world book`() {
        // ST 世界书就是可以没有条目——这与「不是世界书」必须区分开
        val book = parseSillyTavernLorebook("""{"entries": {}}""", "empty")
        assertNotNull(book)
        assertTrue(book!!.entries.isEmpty())
    }

    @Test
    fun `sillytavern preset file is rejected`() {
        // 用户实际导入过的那类文件：ST 预设（prompts + prompt_order），不是世界书
        val preset = """
            {
              "prompts": [{"identifier":"main","content":"x"}],
              "prompt_order": [],
              "wi_format": "{0}"
            }
        """.trimIndent()
        assertNull("a preset file must NOT import as an empty world book", parseSillyTavernLorebook(preset, "preset"))
    }

    @Test
    fun `unrelated json is rejected`() {
        assertNull(parseSillyTavernLorebook("""{"foo": 1, "bar": [1,2]}""", "junk"))
    }

    @Test
    fun `character card is rejected`() {
        val card = """
            {
              "spec": "chara_card_v2",
              "data": {"name": "Alice", "description": "A girl"}
            }
        """.trimIndent()
        assertNull(parseSillyTavernLorebook(card, "alice"))
    }

    @Test
    fun `malformed json is rejected without throwing`() {
        assertNull(parseSillyTavernLorebook("{not json", "broken"))
    }

    @Test
    fun `imported entries keep secondary keys and logic`() {
        // 串起批次 A/B/D：一次导入要同时保住 次要键/逻辑/多源开关
        val json = """
            {
              "entries": {
                "7": {
                  "key": ["dragon"],
                  "keysecondary": ["cave"],
                  "selectiveLogic": 3,
                  "matchCharacterDescription": true,
                  "content": "x",
                  "comment": "dragon cave"
                }
              }
            }
        """.trimIndent()
        val book = parseSillyTavernLorebook(json, "b")!!
        val entry = book.entries.single()
        assertEquals(listOf("cave"), entry.secondaryKeywords)
        assertEquals(app.androidrik.rikkahub.data.model.SelectiveLogic.AND_ALL, entry.selectiveLogic)
        assertTrue(entry.matchAssistantPrompt)
    }
}
