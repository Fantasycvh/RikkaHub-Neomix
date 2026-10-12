package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 预设容器（`PromptPreset`）与扁平视图的等价性测试。
 *
 * 动机（`docs/peer-apps-benchmark.md` §6.2 ④）：把注入从「一个扁平全局列表」升级为
 * 「多个具名预设」，但**下游全部按扁平列表消费**（transformer / Web API / web-ui / 选择器）
 * ⇒ 容器必须能无损投影回扁平列表，否则 web-ui 与 Web API 会静默失配。
 */
class PromptPresetTest {

    private fun entry(name: String, id: Uuid = Uuid.random()) =
        PromptInjection.ModeInjection(id = id, name = name, content = name)

    // region 扁平视图

    @Test
    fun `flat view concatenates preset entries in preset order`() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        val presets = listOf(
            PromptPreset(name = "P1", entries = listOf(a, b)),
            PromptPreset(name = "P2", entries = listOf(c)),
        )
        val flat = presets.flatMap { it.entries }
        assertEquals(listOf("a", "b", "c"), flat.map { it.name })
    }

    @Test
    fun `single preset wrapping preserves entry order`() {
        val entries = listOf(entry("x"), entry("y"), entry("z"))
        val preset = entries.asSinglePreset()
        assertEquals(entries, preset.entries)
        assertTrue(preset.enabled)
    }

    @Test
    fun `empty list wraps into an empty preset`() {
        assertTrue(emptyList<PromptInjection.ModeInjection>().asSinglePreset().entries.isEmpty())
    }

    // endregion

    // region reconcileEntries（扁平编辑 → 归位到预设）

    @Test
    fun `reconcile keeps entries in their original preset`() {
        val a = entry("a")
        val b = entry("b")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(a)), PromptPreset(name = "P2", entries = listOf(b)))

        // 扁平编辑：只改 b 的内容，不改归属
        val edited = listOf(a, b.copy(content = "b2"))
        val result = presets.reconcileEntries(edited)

        assertEquals("P1", result[0].name)
        assertEquals("P2", result[1].name)
        assertEquals(listOf("a"), result[0].entries.map { it.name })
        assertEquals(listOf("b"), result[1].entries.map { it.name })
        assertEquals("b2", result[1].entries.single().content)
    }

    @Test
    fun `reconcile drops deleted entries`() {
        val a = entry("a")
        val b = entry("b")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(a, b)))

        val result = presets.reconcileEntries(listOf(a))
        assertEquals(listOf("a"), result.single().entries.map { it.name })
    }

    @Test
    fun `reconcile appends new entries to the first preset`() {
        val a = entry("a")
        val fresh = entry("new")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(a)), PromptPreset(name = "P2", entries = emptyList()))

        val result = presets.reconcileEntries(listOf(a, fresh))
        assertEquals(listOf("a", "new"), result[0].entries.map { it.name })
        assertTrue("P2 must stay untouched", result[1].entries.isEmpty())
    }

    @Test
    fun `reconcile creates a preset when there are none`() {
        val fresh = entry("new")
        val result = emptyList<PromptPreset>().reconcileEntries(listOf(fresh))
        assertEquals(1, result.size)
        assertEquals(listOf("new"), result.single().entries.map { it.name })
    }

    @Test
    fun `reconcile reorders within a preset`() {
        val a = entry("a")
        val b = entry("b")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(a, b)))

        // 扁平序调换 → 预设内顺序随之调换
        val result = presets.reconcileEntries(listOf(b, a))
        assertEquals(listOf("b", "a"), result.single().entries.map { it.name })
    }

    @Test
    fun `reconcile round trip is stable when nothing changed`() {
        val a = entry("a")
        val b = entry("b")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(a)), PromptPreset(name = "P2", entries = listOf(b)))
        val flat = presets.flatMap { it.entries }
        assertEquals(presets, presets.reconcileEntries(flat))
    }

    @Test
    fun `reconcile does not duplicate when an entry id exists in two presets`() {
        // 防御：畸形数据（同一条目出现在两个预设）不应在归位时复制
        val shared = entry("shared")
        val presets = listOf(PromptPreset(name = "P1", entries = listOf(shared)), PromptPreset(name = "P2", entries = listOf(shared)))
        val flat = presets.flatMap { it.entries }.distinctBy { it.id }
        val result = presets.reconcileEntries(flat)
        val total = result.flatMap { it.entries }.count { it.id == shared.id }
        assertEquals("must not duplicate", 1, total)
    }

    // endregion

    // region 排序语义（priority 为唯一来源）

    @Test
    fun `normalize assigns descending priorities top to bottom`() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val result = normalizeEntryPriorities(entries)
        // 第一个（视觉最靠前）优先级最高
        assertEquals(listOf(2, 1, 0), result.map { it.priority })
        // 顺序原样保留，便于直接回写
        assertEquals(listOf("a", "b", "c"), result.map { it.name })
    }

    @Test
    fun `normalize is idempotent`() {
        val once = normalizeEntryPriorities(listOf(entry("a"), entry("b"), entry("c")))
        val twice = normalizeEntryPriorities(once)
        assertEquals(once.map { it.priority }, twice.map { it.priority })
    }

    @Test
    fun `normalize handles empty and single lists`() {
        assertTrue(normalizeEntryPriorities(emptyList()).isEmpty())
        assertEquals(listOf(0), normalizeEntryPriorities(listOf(entry("only"))).map { it.priority })
    }

    @Test
    fun `display order equals applied order after normalize`() {
        // 这是本轮修复的核心不变量：显示顺序（按 priority 降序）与生效顺序一致
        val dragged = listOf(entry("c"), entry("a"), entry("b"))
        val normalized = normalizeEntryPriorities(dragged)
        assertEquals(listOf("c", "a", "b"), normalized.sortedForDisplay().map { it.name })
    }

    @Test
    fun `sortForDisplay is priority descending and stable`() {
        val low = entry("low").copy(priority = 1)
        val high = entry("high").copy(priority = 9)
        val mid1 = entry("mid1").copy(priority = 5)
        val mid2 = entry("mid2").copy(priority = 5)
        val result = listOf(low, mid1, high, mid2).sortedForDisplay()
        // 高优先级在前；同优先级保持原相对顺序
        assertEquals(listOf("high", "mid1", "mid2", "low"), result.map { it.name })
    }

    @Test
    fun `sortForDisplay empty list is safe`() {
        assertTrue(emptyList<PromptInjection.ModeInjection>().sortedForDisplay().isEmpty())
    }

    // endregion
}
