package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 内置助手回填与墓碑（默认助手可删除的机制基底）。
 *
 * 此前的机制缺陷：读路径回填把「不在列表里的内置 id」无条件加回 ⇒ 内置助手删不掉
 * （UI 隐藏删除按钮正是配套妥协）。墓碑（removedAssistantIds）让「用户显式删除」
 * 与「缺省补回」可以区分。
 */
class DefaultAssistantRefillTest {

    private val defaults = DEFAULT_ASSISTANTS

    @Test
    fun `empty list is refilled with all defaults on first install`() {
        val existing = mutableListOf<Assistant>()
        refillDefaultAssistants(existing, removedIds = emptySet())
        assertEquals(defaults.map { it.id }, existing.map { it.id })
    }

    @Test
    fun `deleted default assistant is refilled without tombstone`() {
        // 旧机制：删掉内置助手 → 下次读取加回（= 删不掉）。回填顺序随删除时序变化，
        // 故比较集合而非顺序。
        val existing = mutableListOf<Assistant>()
        refillDefaultAssistants(existing, removedIds = emptySet())
        existing.removeAll { it.id == defaults.first().id }
        refillDefaultAssistants(existing, removedIds = emptySet())
        assertEquals(defaults.map { it.id }.toSet(), existing.map { it.id }.toSet())
    }

    @Test
    fun `tombstoned default assistant stays deleted`() {
        val tombstoned = defaults.first().id
        val existing = mutableListOf<Assistant>()
        refillDefaultAssistants(existing, removedIds = setOf(tombstoned))
        assertTrue("墓碑中的内置助手不得回填", existing.none { it.id == tombstoned })
        // 其余内置助手照常回填
        assertEquals(defaults.size - 1, existing.size)
    }

    @Test
    fun `non-default assistants are never touched by refill`() {
        val custom = Assistant(id = Uuid.random(), name = "custom")
        val existing = mutableListOf(custom)
        refillDefaultAssistants(existing, removedIds = emptySet())
        assertEquals(custom, existing.first())
        assertEquals(defaults.size + 1, existing.size)
    }

    @Test
    fun `refill keeps the existing instance when the default is already present`() {
        val existing = mutableListOf<Assistant>()
        refillDefaultAssistants(existing, removedIds = emptySet())
        val renamed = existing.first().copy(name = "用户改过的名字")
        existing[0] = renamed
        refillDefaultAssistants(existing, removedIds = emptySet())
        // 已在位（即使用户改了名）不回填不覆盖
        assertEquals("用户改过的名字", existing.first().name)
    }
}
