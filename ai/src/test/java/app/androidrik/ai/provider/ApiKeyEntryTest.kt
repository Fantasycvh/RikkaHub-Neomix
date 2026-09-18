package app.androidrik.ai.provider

import app.androidrik.ai.util.json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ApiKeyEntry` 的稳定 id（编辑器抖动 bug 的修复基底）。
 *
 * 背景：编辑器 LazyColumn 若用 `key.hashCode()` 作 item key，每敲一个字符 key 就变 ⇒
 * 行被销毁重建 ⇒ 焦点丢失（IME 收起）+ 动画抖动。id 的存在就是为了给 UI 一个
 * **不随内容变化**的身份。
 */
class ApiKeyEntryTest {

    @Test
    fun `legacy json without id field still decodes`() {
        // 旧版本写出的数据没有 id 字段
        val legacy = """{"key":"sk-abc","enabled":false}"""
        val decoded = json.decodeFromString<ApiKeyEntry>(legacy)
        assertEquals("sk-abc", decoded.key)
        assertEquals(false, decoded.enabled)
        // 解析时获得随机 id；同一对象实例内保持稳定（UI key 依赖这一点）
        assertEquals(decoded.id, decoded.id)
    }

    @Test
    fun `id round trips through serialization`() {
        val entry = ApiKeyEntry(key = "sk-xyz", enabled = true, id = "fixed-id")
        val encoded = json.encodeToString(entry)
        assertTrue(encoded.contains("\"id\":\"fixed-id\""))
        val decoded = json.decodeFromString<ApiKeyEntry>(encoded)
        assertEquals("fixed-id", decoded.id)
        assertEquals("sk-xyz", decoded.key)
    }

    @Test
    fun `fromLegacy assigns a distinct id per entry`() {
        val entries = ApiKeyEntry.fromLegacy("a, b, a")
        assertEquals(listOf("a", "b"), entries.map { it.key })
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
    }

    @Test
    fun `copy preserves id so typing keeps the item identity`() {
        val entry = ApiKeyEntry(key = "old")
        val typed = entry.copy(key = "old2")
        assertEquals(entry.id, typed.id)
        assertNotEquals(entry.key, typed.key)
    }

    @Test
    fun `two blank entries never collide as map keys`() {
        // 旧实现用 key.hashCode()：两个空行都是 0 → Compose duplicate-key 崩溃
        val a = ApiKeyEntry("")
        val b = ApiKeyEntry("")
        assertNotEquals(a.id, b.id)
    }
}
