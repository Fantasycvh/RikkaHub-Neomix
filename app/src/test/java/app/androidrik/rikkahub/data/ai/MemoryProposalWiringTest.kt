package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import java.io.File
import org.junit.Assert.assertEquals
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

    @Test
    fun `refused changes are rendered as short action lines`() {
        val lines = refusedChangeLines(
            listOf(
                MemoryConsolidationProposal("delete", targetId = "23f0e8f1"),
                MemoryConsolidationProposal("edit", targetId = "0d01f604", content = "新内容"),
            ),
        )
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("delete 23f0e8f1"))
        assertTrue(lines[1].startsWith("edit 0d01f604"))
        assertTrue("要带内容摘要，模型才认得出上一轮提过什么", lines[1].contains("新内容"))
    }

    @Test
    fun `long refused content is truncated`() {
        val lines = refusedChangeLines(
            listOf(MemoryConsolidationProposal("create", content = "字".repeat(200))),
            maxContent = 30,
        )
        assertTrue("这几行是提示不是日志，不能跟库快照抢预算", lines.single().length < 45)
    }

    // ---- 接线守卫 ----

    @Test
    fun `refused suggestions are fed back into the consolidation prompt`() {
        // 真机缺陷：同一条 delete 被两轮整理原地重提（上一轮被拒绝、下一轮又提一模一样的）
        val agent = read("rikkahub/data/ai/MemoryConsolidationGenerationAgent.kt")
        assertTrue(
            "整理提示必须包含「用户已拒绝」这一段，否则模型每轮重做同样的工作",
            agent.contains("request.refusedChanges"),
        )
        assertTrue("而且要明说不要再提", agent.contains("Already refused"))
        val consolidator = read("rikkahub/data/ai/MemoryConsolidator.kt")
        assertTrue(
            "拒绝的清单要从存储读出来（只喂 rejected，不喂 stale）",
            consolidator.contains("proposals.recentlyRejected(memoryId)"),
        )
    }

    @Test
    fun `the library is reviewed page by page with a cursor`() {
        // 「全量整理」= 跨轮次分页。没有游标时每轮都从同一位置取 ⇒ 剩下的永不见天日（设计稿 §7）
        val consolidator = read("rikkahub/data/ai/MemoryConsolidator.kt")
        assertTrue("库快照要按页取", consolidator.contains("pageOfLibrary("))
        assertTrue("水位要推进（且只在成功之后——异常时走不到这行）",
            consolidator.contains("repository.setReviewCursor(memoryId, page.nextCursor)"))
        assertTrue("读水位", consolidator.contains("repository.reviewCursor(memoryId)"))
        assertTrue(
            "校准表只列本页（列了读不到正文的 id 就是盲改的来源）",
            consolidator.contains("buildMemoryCalibrationTable(page.records)"),
        )
        val db = read("rikkahub/data/db/AppDatabase.kt")
        assertTrue("需要游标表 + 迁移", db.contains("MemoryReviewCursorEntity::class") && db.contains("to = 40"))
    }

    @Test
    fun `the page prompt never lets the model guess unseen records`() {
        val prompts = read("rikkahub/data/ai/MemoryConsolidationPrompts.kt")
        assertTrue("本页给全文（不是 40 字摘要）", prompts.contains("buildLibraryPagePrompt"))
        assertTrue("要明确阻止对看不到的记录下判断", prompts.contains("do not guess"))
        assertTrue(
            "有剩余记录时本页不许为空，否则水位永远卡住",
            prompts.contains("首条豁免"),
        )
    }

    @Test
    fun `automatic tidy is gated on unhandled suggestions`() {
        val scheduler = read("rikkahub/data/ai/MemoryConsolidationScheduler.kt")
        assertTrue(
            "有未处理建议时不许自动整理（产出会把那些建议冲掉 + 重复计费）",
            scheduler.contains("if (hasUnhandledProposals(memoryId))"),
        )
        assertTrue("闸门状态要暴露出去，不能静默跳过", scheduler.contains("waitingForReview = true"))
    }

    @Test
    fun `delete may not be applied without its batch siblings`() {
        val repo = read("rikkahub/data/repository/MemoryProposalRepository.kt")
        assertTrue(
            "判「同批成员有没有被勾选」必须看全量 pending，不能先按勾选过滤",
            repo.contains("val allPending = proposalDAO.getPending(memoryId)"),
        )
        assertTrue("只应用半批时跳过 delete", repo.contains("if (siblingsUnselected && hasDelete)"))
    }


    @Test
    fun `scheduler persists proposals and reloads them with ids`() {
        val scheduler = read("rikkahub/"+"data/ai/MemoryConsolidationScheduler.kt")
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
        val consolidator = read("rikkahub/"+"data/ai/MemoryConsolidator.kt")
        assertTrue("落库委托", consolidator.contains("proposals.savePending(memoryId, proposals)"))
        assertTrue("应用委托", consolidator.contains("proposals.apply(memoryId, ids)"))
        assertTrue("拒绝委托", consolidator.contains("proposals.reject(memoryId, ids)"))
    }

    @Test
    fun `the apply button lives inside the confirmation dialog`() {
        val page = read("rikkahub/"+"ui/pages/assistant/detail/AssistantMemoryPage.kt")
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
    fun `the proposal store is bound to its interface in DI`() {
        // 真机装机当场崩出来的坑（2026-10-16）：按具体类型注册时 Koin 只认 MemoryProposalRepository，
        // 而 MemoryConsolidator 的形参是窄接口 MemoryProposalStore ⇒ get() 抛 NoDefinitionFound
        // ⇒ Application.onCreate 直接 FATAL。**源码扫描看不出这种绑定缺失**，与下面 worker 那条同一个坑。
        val di = read("rikkahub/di/DataSourceModule.kt")
        assertTrue(
            "Koin 必须显式绑定到接口 MemoryProposalStore（真机装机会崩在 Application.onCreate）",
            di.contains("single<MemoryProposalStore>"),
        )
        assertTrue("worker 也要绑到接口", di.contains("single<MemoryConsolidationWorker>"))
    }

    @Test
    fun `the tool path is the only place that builds unpersisted proposals`() {
        // 工具层构造的建议 id 恒为 0（还没落库）——这条守卫防的是「有人误以为它带 id」
        val tools = read("rikkahub/"+"data/ai/tools/MemoryTools.kt")
        assertFalse(
            "工具层不得伪造 id",
            tools.contains("MemoryConsolidationProposal(\"create\", null, requiredContent, importance, description, "),
        )
    }

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java/app/androidrik"), File("app/src/main/java/app/androidrik"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        // 允许 ../ 前缀（DI 模块在 app/androidrik/rikkahub/di，而本文件的其余目标在 rikkahub/ 下）
        val file = srcRoot().resolve(relative).normalize()
        assertTrue("源码不存在：${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
