package app.androidrik.rikkahub.data.model

import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 助手**归档**开关（`Assistant.enabled`，语义 A：只影响选择器可见性，不阻断解析）。
 *
 * 两条边界钉进测试：
 * - `filterForPicker` 是「停用语义」的落地处——若有人把它改成「连解析也挡掉」，
 *   这里会先红；
 * - 旧数据（JSON 里没有 `enabled` 字段）必须读到 `true`——归档是纯增量字段，零迁移。
 */
class AssistantEnabledTest {

    private fun assistant(
        name: String,
        enabled: Boolean = true,
        tags: List<Uuid> = emptyList(),
    ) = Assistant(name = name, enabled = enabled, tags = tags)

    private val tagA = Uuid.random()
    private val tagB = Uuid.random()

    @Test
    fun `picker hides disabled assistants by default`() {
        val assistants = listOf(
            assistant("a"),
            assistant("archived", enabled = false),
            assistant("b"),
        )
        assertEquals(listOf("a", "b"), assistants.filterForPicker().map { it.name })
    }

    @Test
    fun `management-action picker keeps disabled assistants visible`() {
        val assistants = listOf(assistant("a"), assistant("archived", enabled = false))
        assertEquals(listOf("a", "archived"), assistants.filterForPicker(showDisabled = true).map { it.name })
    }

    @Test
    fun `tag filter combines with enabled state`() {
        val assistants = listOf(
            assistant("a", tags = listOf(tagA)),
            assistant("archived-tagged", enabled = false, tags = listOf(tagA)),
            assistant("b", tags = listOf(tagB)),
        )
        // 标签选中 + 主切换器：归档的即使有该标签也隐藏
        assertEquals(
            listOf("a"),
            assistants.filterForPicker(selectedTagIds = setOf(tagA)).map { it.name },
        )
        // 管理动作场景：标签 + 停用都显示
        assertEquals(
            listOf("a", "archived-tagged"),
            assistants.filterForPicker(showDisabled = true, selectedTagIds = setOf(tagA)).map { it.name },
        )
    }

    @Test
    fun `defaults to enabled and round trips the disabled state`() {
        assertTrue(Assistant().enabled)
        val archived = assistant("x", enabled = false)
        val decoded = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(archived))
        assertFalse(decoded.enabled)
    }

    @Test
    fun `legacy json without the enabled field reads as enabled`() {
        // 手写最小 JSON：enabled 字段缺失 = 旧版本写入的数据
        val legacyJson = """{"id":"0198c3d2-0000-7000-8000-000000000001","name":"old","systemPrompt":""}"""
        val decoded = JsonInstant.decodeFromString<Assistant>(legacyJson)
        assertTrue("旧数据零迁移必须读到启用", decoded.enabled)
    }

    @Test
    fun `disabling does not change identity fields`() {
        val original = assistant("x")
        val disabled = original.copy(enabled = false)
        assertEquals(original.id, disabled.id)
        assertEquals(original.name, disabled.name)
        assertEquals(original.systemPrompt, disabled.systemPrompt)
        assertFalse(disabled.enabled)
    }
}
