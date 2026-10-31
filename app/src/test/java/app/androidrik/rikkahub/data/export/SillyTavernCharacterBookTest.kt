package app.androidrik.rikkahub.data.export

import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.SelectiveLogic
import app.androidrik.rikkahub.data.model.isTriggered
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡内嵌世界书（chara_card_v2 `data.character_book`）→ fork [Lorebook] 转换的保真测试。
 *
 * 卡内嵌书的字段名体系与独立世界书文件不同（`keys`/`enabled`/`insertion_order`，见
 * `docs/design/design-card-lorebook-import.md` §3.1），本文件钉住映射口径与
 * ST `convertCharacterBook` 的一致性；触发行为断言复用 [isTriggered]。
 */
class SillyTavernCharacterBookTest {

    private fun decode(raw: String): SillyTavernCharacterBook =
        Json.decodeFromString(SillyTavernCharacterBook.serializer(), raw)

    // ---- 条目字段映射（§3.1 表格逐行钉住）----

    @Test
    fun `keys map to keywords and insertion_order to priority`() {
        val book = parseSillyTavernCharacterBook(
            cardName = "Alice",
            book = SillyTavernCharacterBook(
                entries = listOf(
                    SillyTavernBookEntry(
                        keys = listOf("dragon"),
                        content = "Dragon lore",
                        insertionOrder = 42,
                        enabled = true,
                    )
                )
            )
        )!!
        val entry = book.entries.single()
        assertEquals(listOf("dragon"), entry.keywords)
        assertEquals(42, entry.priority)
        assertTrue("卡内嵌 enabled 是正向开关，不得像独立文件的 disable 那样反转", entry.enabled)
    }

    @Test
    fun `enabled false stays disabled`() {
        val entry = SillyTavernBookEntry(keys = listOf("x"), content = "x", enabled = false)
            .toRegexInjection(defaultScanDepth = 4)
        assertFalse(entry.enabled)
    }

    @Test
    fun `entry name prefers comment then falls back to name and first key`() {
        assertEquals(
            "备注名",
            SillyTavernBookEntry(keys = listOf("k"), content = "c", comment = "备注名", name = "条目名")
                .toRegexInjection(4).name,
        )
        assertEquals(
            "条目名",
            SillyTavernBookEntry(keys = listOf("k"), content = "c", name = "条目名")
                .toRegexInjection(4).name,
        )
        assertEquals(
            "k",
            SillyTavernBookEntry(keys = listOf("k"), content = "c").toRegexInjection(4).name,
        )
    }

    // ---- 位置映射（§3.2：extensions.position 优先，否则顶层字符串）----

    @Test
    fun `extensions position wins over top level string`() {
        val entry = SillyTavernBookEntry(
            keys = listOf("k"), content = "c",
            position = "before_char",
            extensions = SillyTavernBookEntryExtensions(position = 4), // @Depth
        ).toRegexInjection(4)
        assertEquals(InjectionPosition.AT_DEPTH, entry.position)
        assertEquals(4, entry.injectDepth)
    }

    @Test
    fun `before_char maps to before system prompt`() {
        val entry = SillyTavernBookEntry(keys = listOf("k"), content = "c", position = "before_char")
            .toRegexInjection(4)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, entry.position)
    }

    @Test
    fun `missing position falls back to after_char side`() {
        // ST convertCharacterBook 的 fallback 是 1 = after_char
        val entry = SillyTavernBookEntry(keys = listOf("k"), content = "c").toRegexInjection(4)
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, entry.position)
    }

    @Test
    fun `out of range extensions position falls back to top level string`() {
        val entry = SillyTavernBookEntry(
            keys = listOf("k"), content = "c",
            position = "before_char",
            extensions = SillyTavernBookEntryExtensions(position = 99),
        ).toRegexInjection(4)
        assertEquals(InjectionPosition.BEFORE_SYSTEM_PROMPT, entry.position)
    }

    // ---- 触发行为（与独立世界书导入同口径）----

    @Test
    fun `selective false discards secondary keys`() {
        val entry = SillyTavernBookEntry(
            keys = listOf("dragon"),
            secondaryKeys = listOf("cave"),
            selective = false,
            content = "x",
        ).toRegexInjection(4)
        assertTrue(entry.secondaryKeywords.isEmpty())
        assertTrue(entry.isTriggered("a dragon"))
    }

    @Test
    fun `secondary keys gate triggering`() {
        val entry = SillyTavernBookEntry(
            keys = listOf("dragon"),
            secondaryKeys = listOf("cave"),
            content = "x",
        ).toRegexInjection(4)
        assertFalse("主键命中但次键未命中不得触发", entry.isTriggered("a dragon flying"))
        assertTrue(entry.isTriggered("a dragon in a cave"))
    }

    @Test
    fun `slash regex keys are recognized`() {
        val entry = SillyTavernBookEntry(keys = listOf("/\\d+ apples/"), content = "x")
            .toRegexInjection(4)
        assertTrue(entry.useRegex)
        assertTrue(entry.isTriggered("I have 3 apples"))
    }

    // ---- 书级字段与空书（§3.3 / 决策 4）----

    @Test
    fun `book scan depth is default for entries without own scanDepth`() {
        val book = parseSillyTavernCharacterBook(
            cardName = "Alice",
            book = SillyTavernCharacterBook(
                scanDepth = 8,
                entries = listOf(
                    SillyTavernBookEntry(keys = listOf("a"), content = "x"),
                    SillyTavernBookEntry(
                        keys = listOf("b"), content = "x",
                        extensions = SillyTavernBookEntryExtensions(scanDepth = 2),
                    ),
                ),
            )
        )!!
        assertEquals(8, book.entries[0].scanDepth)
        assertEquals(2, book.entries[1].scanDepth)
    }

    @Test
    fun `book name falls back to card name`() {
        assertEquals(
            "Alice的世界书",
            parseSillyTavernCharacterBook(
                cardName = "Alice",
                book = SillyTavernCharacterBook(entries = listOf(SillyTavernBookEntry(keys = listOf("k")))),
            )!!.name,
        )
        assertEquals(
            "自定义书名",
            parseSillyTavernCharacterBook(
                cardName = "Alice",
                book = SillyTavernCharacterBook(name = "自定义书名", entries = listOf(SillyTavernBookEntry(keys = listOf("k")))),
            )!!.name,
        )
    }

    @Test
    fun `empty entries return null - no silent empty book`() {
        assertNull(parseSillyTavernCharacterBook(cardName = "Alice", book = SillyTavernCharacterBook()))
    }

    @Test
    fun `decode maps snake_case fields`() {
        val book = decode(
            """
            {
              "name": "书名",
              "scan_depth": 6,
              "entries": [
                {
                  "keys": ["k1"],
                  "secondary_keys": ["k2"],
                  "content": "c",
                  "enabled": false,
                  "insertion_order": 7,
                  "case_sensitive": true,
                  "extensions": { "position": 2, "depth": 3, "selectiveLogic": 2 }
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(6, book.scanDepth)
        val entry = book.entries.single()
        assertEquals(listOf("k2"), entry.secondaryKeys)
        assertFalse(entry.enabled)
        assertEquals(7, entry.insertionOrder)
        assertTrue(entry.caseSensitive == true)
        assertEquals(2, entry.extensions.position)
        assertEquals(3, entry.extensions.depth)
        assertEquals(2, entry.extensions.selectiveLogic)
        assertEquals(SelectiveLogic.NOT_ANY, entry.toRegexInjection(4).selectiveLogic)
    }
}
