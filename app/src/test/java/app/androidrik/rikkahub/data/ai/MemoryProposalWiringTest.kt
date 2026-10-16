package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「建议落库 + 二次确认应用」的**接线守卫**与确认页文案（**纯函数**）。
 *
 * 起因：这一段的价值全在链路上，而断链是**静默失效**——不落库就等于「重启即丢」，
 * 不接应用入口就等于建议永远无法采纳（而那正是本轮要修的缺陷）。
 * 应用本身的语义（按批原子 / stale 留痕 / 审计上限）见 `MemoryProposalRepositoryTest`。
 */
class MemoryProposalWiringTest {

    // ---- 确认页文案（纯函数） ----

    private fun target(content: String) = AssistantMemory(id = "abc12345", content = content)

    @Test
    fun `create line shows only the new content`() {
        val line = proposeChangeSummary(
            MemoryConsolidationProposal("create", content = "likes tea"),
            target = null,
        )
        assertTrue(line.startsWith("[new]"))
        assertTrue(line.contains("likes tea"))
    }

    @Test
    fun `edit line shows what will be replaced and what replaces it`() {
        // 确认页的责任是让人在不可逆动作前看清后果 ⇒ 必须**两边都显示**
        val line = proposeChangeSummary(
            MemoryConsolidationProposal("edit", targetId = "abc12345", content = "new text"),
            target = target("old text"),
        )
        assertTrue("要显示目标 id", line.contains("abc12345"))
        assertTrue("要显示改前（否则用户不知道将改掉什么）", line.contains("old text"))
        assertTrue("要显示改后", line.contains("new text"))
    }

    @Test
    fun `delete line shows the text that will be lost`() {
        // 删除尤其如此：应用之后那段内容就没处看了
        val line = proposeChangeSummary(
            MemoryConsolidationProposal("delete", targetId = "abc12345"),
            target = target("precious content"),
        )
        assertTrue(line.startsWith("[delete]"))
        assertTrue(line.contains("precious content"))
    }

    @Test
    fun `a vanished target is called out before the user presses apply`() {
        val line = proposeChangeSummary(
            MemoryConsolidationProposal("edit", targetId = "deadbeef", content = "x"),
            target = null,
        )
        assertTrue("提前说明比让用户按完再看到「0 条应用」好", line.contains("no longer exists"))
    }

    @Test
    fun `long content is truncated so the dialog stays readable`() {
        val long = "字".repeat(300)
        val line = proposeChangeSummary(
            MemoryConsolidationProposal("create", content = long),
            target = null,
            maxContent = 40,
        )
        assertTrue("确认页每行要截断（全文仍可在详情弹层看）", line.length < 60)
        assertTrue(line.endsWith("…"))
    }

    // ---- 接线守卫 ----

    @Test
    fun `scheduler persists proposals and reloads them with ids`() {
        val scheduler = read("data/ai/MemoryConsolidationScheduler.kt")
        assertTrue(
            "跑完必须落库，否则「重启即失」没修",
            scheduler.contains("worker.saveProposals(memoryId, run.proposals)"),
        )
        assertTrue(
            "落库后要重读带 id 的版本（内存那份 id 全是 0，界面无法提交）",
            scheduler.contains("worker.pendingProposals(memoryId)"),
        )
        assertTrue(
            "启动时要恢复待决策建议（落库的正是为了这件事）",
            scheduler.contains("worker.memoryIdsWithProposals()"),
        )
    }

    @Test
    fun `consolidator delegates the whole proposal path to storage`() {
        val consolidator = read("data/ai/MemoryConsolidator.kt")
        assertTrue("落库委托", consolidator.contains("proposals.savePending(memoryId, proposals)"))
        assertTrue("应用委托", consolidator.contains("proposals.apply(memoryId, ids)"))
        assertTrue("拒绝委托", consolidator.contains("proposals.reject(memoryId, ids)"))
    }

    @Test
    fun `the apply button lives inside the confirmation dialog`() {
        val page = read("ui/pages/assistant/detail/AssistantMemoryPage.kt")
        assertTrue("必须有「打开确认页」这一步", page.contains("confirmProposals = true"))
        assertTrue(
            "真正的写库调用只能在确认按钮里 —— 不可逆操作不放在一次点击里",
            page.contains("onApplyProposals(ids)"),
        )
        assertTrue("确认按钮文案独立于「采纳」按钮", page.contains("memory_consolidation_confirm_apply"))
        assertTrue(
            "删除要在确认页上显式警示",
            page.contains("memory_consolidation_confirm_delete_warning"),
        )
    }

    @Test
    fun `the tool path is the only place that builds unpersisted proposals`() {
        // 工具层构造的建议 id 恒为 0（还没落库）——这条守卫防的是「有人误以为它带 id」
        val tools = read("data/ai/tools/MemoryTools.kt")
        assertFalse(
            "工具层不得伪造 id",
            tools.contains("MemoryConsolidationProposal(\"create\", null, requiredContent, importance, description, "),
        )
    }

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java/app/androidrik/rikkahub"), File("app/src/main/java/app/androidrik/rikkahub"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative)
        assertTrue("源码不存在：${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
