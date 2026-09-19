package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1542 复核 Tier A：一键迁移「改写 system 区」的注入条目（纯函数）。
 *
 * 迁移的语义边界：**只改 position**（BEFORE/AFTER_SYSTEM_PROMPT → BOTTOM_OF_CHAT），
 * 内容/顺序/启停/优先级一律不动；幂等；覆盖预设条目与世界书条目两个数据源。
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
    fun `detects system-rewriting entries across presets and lorebooks`() {
        val presets = listOf(
            PromptPreset(name = "p", entries = listOf(entry("a", InjectionPosition.BOTTOM_OF_CHAT))),
        )
        val lorebooks = listOf(
            Lorebook(name = "l", entries = listOf(worldEntry("w", InjectionPosition.AFTER_SYSTEM_PROMPT))),
        )
        assertTrue(hasSystemRewritingInjections(presets, lorebooks))
        assertEquals(1, countSystemRewritingInjections(presets, lorebooks))
        assertFalse(hasSystemRewritingInjections(presets, emptyList()))
    }

    @Test
    fun `migration moves only before-after entries to bottom of chat`() {
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
        val (newPresets, newLorebooks) = migrateSystemRewritingInjections(presets, emptyList())
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
    fun `migration covers lorebook entries including disabled ones`() {
        val lorebooks = listOf(
            Lorebook(
                name = "l",
                entries = listOf(
                    worldEntry("st-imported", InjectionPosition.AFTER_SYSTEM_PROMPT).let {
                        it.copy(enabled = false)
                    },
                ),
            ),
        )
        val (newPresets, newLorebooks) = migrateSystemRewritingInjections(emptyList(), lorebooks)
        assertTrue(newPresets.isEmpty())
        // 禁用条目也迁移：用户日后启用它时不应重新踩缓存坑
        assertEquals(InjectionPosition.BOTTOM_OF_CHAT, newLorebooks.single().entries.single().position)
    }

    @Test
    fun `migration is idempotent`() {
        val presets = listOf(PromptPreset(name = "p", entries = listOf(entry("a", InjectionPosition.AFTER_SYSTEM_PROMPT))))
        val (p1, l1) = migrateSystemRewritingInjections(presets, emptyList())
        val (p2, l2) = migrateSystemRewritingInjections(p1, l1)
        assertEquals(p1, p2)
        assertEquals(l1, l2)
        assertFalse(hasSystemRewritingInjections(p2, l2))
    }

    @Test
    fun `no-op returns the same lists untouched`() {
        val presets = listOf(PromptPreset(name = "p", entries = listOf(entry("a", InjectionPosition.TOP_OF_CHAT))))
        val lorebooks = listOf(Lorebook(name = "l"))
        val (p2, l2) = migrateSystemRewritingInjections(presets, lorebooks)
        assertEquals(presets, p2)
        assertEquals(lorebooks, l2)
    }

    @Test
    fun `seed learning mode is cache friendly in new installs`() {
        // 回归锚：DEFAULT_MODE_INJECTIONS 的位置已从 AFTER_SYSTEM_PROMPT 改为 BOTTOM_OF_CHAT
        // ——该断言防止未来有人改回去（改回去 = 新装用户开箱即破缓存）。
        assertEquals(
            InjectionPosition.BOTTOM_OF_CHAT,
            app.androidrik.rikkahub.data.datastore.DEFAULT_MODE_INJECTIONS.single().position,
        )
    }
}
