package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入位置**双向**批量迁移（#1542 Tier A 及其反向）。
 *
 * 语义边界：**只改 position**，内容/顺序/启停/优先级一律不动；幂等；
 * 覆盖预设条目与世界书条目两个数据源；方向由调用方选择（不做强制）。
 */
class InjectionCacheMigrationTest {

    private fun entry(
        name: String,
        position: InjectionPosition,
        enabled: Boolean = true,
    ) = PromptInjection.ModeInjection(
        name = name,
        enabled = enabled,
        position = position,
        content = "c-$name",
    )

    private fun worldEntry(
        name: String,
        position: InjectionPosition,
    ) = PromptInjection.RegexInjection(
        name = name,
        position = position,
        content = "w-$name",
        keywords = listOf("k"),
    )

    @Test
    fun `forward direction moves system-area entries to bottom of chat`() {
        val presets = listOf(
            PromptPreset(
                name = "p",
                entries = listOf(
                    entry("before", InjectionPosition.BEFORE_SYSTEM_PROMPT),
                    entry("after", InjectionPosition.AFTER_SYSTEM_PROMPT),
                    entry("tail", InjectionPosition.END_OF_CHAT),
                    entry("depth", InjectionPosition.AT_DEPTH),
                ),
            ),
        )
        val (newPresets, newLorebooks) =
            migrateInjections(presets, emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT)
        assertEquals(
            listOf(
                InjectionPosition.BOTTOM_OF_CHAT, // before → 迁移
                InjectionPosition.BOTTOM_OF_CHAT, // after → 迁移
                InjectionPosition.END_OF_CHAT,    // 尾部不动
                InjectionPosition.AT_DEPTH,       // 深度不动
            ),
            newPresets.single().entries.map { it.position },
        )
        assertTrue(newLorebooks.isEmpty())
        // 内容/启停/名字不动
        assertEquals("c-before", newPresets.single().entries[0].content)
        assertEquals("before", newPresets.single().entries[0].name)
    }

    @Test
    fun `backward direction moves end-of-chat entries back to after system prompt`() {
        // 反向：Tier A 正向迁移的产物（或 Learning Mode 种子的现位置）迁回系统区强调
        val presets = listOf(
            PromptPreset(
                name = "p",
                entries = listOf(
                    entry("learning", InjectionPosition.BOTTOM_OF_CHAT),
                    entry("tail", InjectionPosition.END_OF_CHAT),
                    entry("after", InjectionPosition.AFTER_SYSTEM_PROMPT),
                ),
            ),
        )
        val (newPresets, newLorebooks) =
            migrateInjections(presets, emptyList(), InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
        assertEquals(
            listOf(
                InjectionPosition.AFTER_SYSTEM_PROMPT, // bottom → 迁回系统区
                InjectionPosition.END_OF_CHAT,         // 尾部不动
                InjectionPosition.AFTER_SYSTEM_PROMPT, // after 不动（已在目标）
            ),
            newPresets.single().entries.map { it.position },
        )
        assertTrue(newLorebooks.isEmpty())
    }

    @Test
    fun `backward direction covers lorebook entries including disabled ones`() {
        val lorebooks = listOf(
            Lorebook(
                name = "l",
                entries = listOf(
                    worldEntry("st-tail", InjectionPosition.BOTTOM_OF_CHAT).let { it.copy(enabled = false) },
                ),
            ),
        )
        val (newPresets, newLorebooks) =
            migrateInjections(emptyList(), lorebooks, InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
        assertTrue(newPresets.isEmpty())
        // 禁用条目也迁移：用户日后启用它时位置已符合其迁移意图
        assertEquals(InjectionPosition.AFTER_SYSTEM_PROMPT, newLorebooks.single().entries.single().position)
    }

    @Test
    fun `each direction is idempotent`() {
        val presets = listOf(
            PromptPreset(
                name = "p",
                entries = listOf(
                    entry("a", InjectionPosition.AFTER_SYSTEM_PROMPT),
                    entry("b", InjectionPosition.BOTTOM_OF_CHAT),
                ),
            ),
        )
        val (p1, l1) = migrateInjections(presets, emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT)
        val (p2, l2) = migrateInjections(p1, l1, InjectionMigrationDirection.TO_BOTTOM_OF_CHAT)
        assertEquals(p1, p2)
        assertEquals(l1, l2)
        val (p3, l3) = migrateInjections(p2, l2, InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
        val (p4, l4) = migrateInjections(p3, l3, InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
        assertEquals(p3, p4)
        assertEquals(l3, l4)
    }

    @Test
    fun `no-op direction returns the same lists untouched`() {
        // 只在系统区的条目：反向无匹配 → 原列表原样返回
        val presets = listOf(PromptPreset(name = "p", entries = listOf(entry("a", InjectionPosition.TOP_OF_CHAT))))
        val lorebooks = listOf(Lorebook(name = "l"))
        val (p2, l2) = migrateInjections(presets, lorebooks, InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT)
        assertEquals(presets, p2)
        assertEquals(lorebooks, l2)
    }

    @Test
    fun `counts are per direction`() {
        val presets = listOf(
            PromptPreset(
                name = "p",
                entries = listOf(
                    entry("a", InjectionPosition.AFTER_SYSTEM_PROMPT),
                    entry("b", InjectionPosition.BOTTOM_OF_CHAT),
                ),
            ),
        )
        assertEquals(1, countMigratableInjections(presets, emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT))
        assertEquals(1, countMigratableInjections(presets, emptyList(), InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT))
        assertFalse(hasMigratableInjections(emptyList(), emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT))
        assertTrue(hasMigratableInjections(presets, emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT))
    }

    @Test
    fun `messages without migrations are unaffected`() {
        val plain = listOf(PromptPreset(name = "p", entries = listOf(entry("a", InjectionPosition.TOP_OF_CHAT))))
        val result = migrateInjections(plain, emptyList(), InjectionMigrationDirection.TO_BOTTOM_OF_CHAT)
        assertEquals(plain, result.first)
    }
}
