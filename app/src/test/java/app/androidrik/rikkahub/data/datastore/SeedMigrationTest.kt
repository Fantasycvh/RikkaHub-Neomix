package app.androidrik.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import kotlin.uuid.Uuid

class SeedMigrationTest {
    private val seedId = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74")
    private val otherId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private fun entry(
        id: Uuid,
        content: String,
        position: InjectionPosition = InjectionPosition.BOTTOM_OF_CHAT,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = PromptInjection.ModeInjection(
        id = id,
        name = "Learning Mode",
        enabled = enabled,
        priority = priority,
        position = position,
        content = content,
        role = MessageRole.USER,
    )

    private fun preset(vararg entries: PromptInjection.ModeInjection) =
        PromptPreset(name = "default", entries = entries.toList())

    private val currentContent = SEED_PRESET_ENTRIES.single().content

    @Test
    fun `stale copy is migrated to latest keeping enabled state`() {
        val stale = entry(seedId, currentContent, position = InjectionPosition.AFTER_SYSTEM_PROMPT, enabled = false)
        val migrated = migrateSeedPresetEntries(listOf(preset(stale)))

        assertNotNull(migrated)
        val entry = migrated!!.single().entries.single()
        assertEquals(InjectionPosition.BOTTOM_OF_CHAT, entry.position)
        assertEquals(currentContent, entry.content)
        // 用户开关状态保留：关着的迁移后依然是关的
        assertEquals(false, entry.enabled)
    }

    @Test
    fun `customized entry is left untouched`() {
        val customized = entry(seedId, "$currentContent\n\n用户自己的补充")
        assertNull(migrateSeedPresetEntries(listOf(preset(customized))))
    }

    @Test
    fun `user moved position is left untouched`() {
        val moved = entry(seedId, currentContent, position = InjectionPosition.TOP_OF_CHAT)
        assertNull(migrateSeedPresetEntries(listOf(preset(moved))))
    }

    @Test
    fun `already latest version is idempotent`() {
        val latest = entry(seedId, currentContent, position = InjectionPosition.BOTTOM_OF_CHAT)
        assertNull(migrateSeedPresetEntries(listOf(preset(latest))))
    }

    @Test
    fun `deleted entry is not resurrected`() {
        val other = entry(otherId, "用户自己的条目")
        val result = migrateSeedPresetEntries(listOf(preset(other)))
        assertNull(result)
    }

    @Test
    fun `unrelated entries are preserved when migrating`() {
        val stale = entry(seedId, currentContent, position = InjectionPosition.AFTER_SYSTEM_PROMPT)
        val unrelated = entry(otherId, "用户自己的条目", enabled = false)
        val migrated = migrateSeedPresetEntries(listOf(preset(stale, unrelated)))!!

        val entries = migrated.single().entries
        assertEquals(2, entries.size)
        assertEquals("用户自己的条目", entries.first { it.id == otherId }.content)
        assertEquals(false, entries.first { it.id == otherId }.enabled)
        assertEquals(InjectionPosition.BOTTOM_OF_CHAT, entries.first { it.id == seedId }.position)
    }
}
