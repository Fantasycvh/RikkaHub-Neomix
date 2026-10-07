package app.androidrik.common.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 「列表 + 启停」家族共享契约（[Enableable]）：
 *
 * - [resolveSelected]：**选中项被停用时不静默回退**——这是 TTS/ASR 踩过并钉死的取舍
 *   （用户关了它，就不该悄悄改用另一个可能计费的提供商）；「选中项被删 → 回退第一个启用」
 *   则是删除路径的既有行为。
 * - [refillBuiltIns]：墓碑跳过 + **无 ifEmpty 兜底**——「内置项删光后 ifEmpty 整体复活」
 *   陷阱（chat 提供商 2026-09-09、TTS 2026-10-01 两次踩过）在此构造上钉死。
 */
private data class Item(
    override val id: Uuid,
    override val enabled: Boolean = true,
    val tag: String = "",
) : Enableable

class EnableableTest {

    private val a = Item(Uuid.parse("00000000-0000-0000-0000-00000000000a"))
    private val b = Item(Uuid.parse("00000000-0000-0000-0000-00000000000b"))
    private val c = Item(Uuid.parse("00000000-0000-0000-0000-00000000000c"))
    private val items = listOf(a, b, c)

    @Test
    fun `selected and enabled returns it`() {
        assertEquals(b, resolveSelected(items, b.id))
    }

    @Test
    fun `selected but disabled returns null - no silent fallback`() {
        val disabled = b.copy(enabled = false)
        assertNull(resolveSelected(listOf(a, disabled, c), disabled.id))
    }

    @Test
    fun `selected missing falls back to first enabled`() {
        assertEquals(a, resolveSelected(items, null))
        assertEquals(a, resolveSelected(items, Uuid.random()))
    }

    @Test
    fun `nothing enabled returns null`() {
        val allDisabled = items.map { it.copy(enabled = false) }
        assertNull(resolveSelected(allDisabled, a.id))
        assertNull(resolveSelected(allDisabled, null))
    }

    @Test
    fun `refill adds only missing defaults`() {
        val refilled = refillBuiltIns(listOf(a), listOf(a, b, c))
        assertEquals(listOf(a, b, c), refilled)
    }

    @Test
    fun `refill skips tombstoned defaults`() {
        val refilled = refillBuiltIns(listOf(a), listOf(a, b, c), removedIds = setOf(b.id))
        assertEquals(listOf(a, c), refilled)
    }

    @Test
    fun `refill does not revive when existing empty and all tombstoned`() {
        // ifEmpty { DEFAULT } 陷阱的构造性钉死：全部墓碑 + 空列表 ⇒ 保持空
        val refilled = refillBuiltIns(emptyList(), listOf(a, b), removedIds = setOf(a.id, b.id))
        assertEquals(emptyList<Item>(), refilled)
    }

    @Test
    fun `refill clones added defaults`() {
        val cloned = refillBuiltIns(listOf(a), listOf(b)) { it.copy(tag = "cloned") }
        assertEquals("cloned", cloned.single { it.id == b.id }.tag)
    }
}
