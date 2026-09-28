package app.androidrik.rikkahub.ui.pages.debug

import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 助手恢复方案（上游 `b9c0d3b7` 的页面，本仓按墓碑机制做了分类）。
 *
 * 背景：对话列表按助手过滤（`getConversationsOfAssistant`），助手没了那些对话
 * **完全不可见**。恢复=补齐助手让旧对话重新可见。两类缺失处理方式不同：
 * 内置助手清墓碑由回填带回**真身**（含完整配置），其余只能建占位。
 */
class AssistantRecoveryPlanTest {

    private val builtIn = DEFAULT_ASSISTANTS_IDS

    @Test
    fun `built-in ids are never planned as placeholders`() {
        // 关键不变量：给内置 id 建占位会占住该 id，refillDefaultAssistants 见「已存在」
        // 便不再补真身 ⇒ 用户永久拿到空壳「Recovered assistant N」
        val missing = builtIn.take(2).toSet()
        val plan = planAssistantRecovery(missing)

        assertTrue("内置 id 必须走墓碑清理路径", plan.builtInIds.containsAll(missing))
        assertTrue(
            "内置 id 绝不能变成占位：${plan.placeholderIds}",
            plan.placeholderIds.none { it in builtIn },
        )
        assertEquals(0, plan.placeholderIds.size)
    }

    @Test
    fun `unknown ids become placeholders`() {
        val custom = List(3) { Uuid.random() }
        val plan = planAssistantRecovery(custom)

        assertEquals(custom, plan.placeholderIds)
        assertTrue("未命中内置 id", plan.builtInIds.isEmpty())
    }

    @Test
    fun `mixed ids are split correctly`() {
        val builtInMissing = builtIn.take(1)
        val custom = List(2) { Uuid.random() }
        val plan = planAssistantRecovery(builtInMissing + custom)

        assertEquals(builtInMissing.toSet(), plan.builtInIds)
        assertEquals(custom, plan.placeholderIds)
        // 两拨不相交且并集为全部（不丢不重）
        assertTrue(plan.builtInIds.intersect(plan.placeholderIds.toSet()).isEmpty())
        assertEquals((builtInMissing + custom).toSet(), plan.builtInIds + plan.placeholderIds.toSet())
    }

    @Test
    fun `empty input yields empty plan`() {
        val plan = planAssistantRecovery(emptyList())
        assertTrue(plan.placeholderIds.isEmpty())
        assertTrue(plan.builtInIds.isEmpty())
    }
}
