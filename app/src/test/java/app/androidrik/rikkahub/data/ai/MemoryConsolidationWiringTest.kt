package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 做梦（V3-b2）的**接线守卫**（源码扫描，与 [ThinkingDegenerationWiringTest] 同款）。
 *
 * 起因：这一批的价值全在几条"编译期与普通单测都发现不了"的接线上——
 * - 只提案模式没接上 `proposalSink` ⇒ 审批开着的用户**被静默绕过审批**（后台直接改库）；
 * - 提案模式没在系统提示里说明 ⇒ 模型读到 `applied:false` 以为自己写失败了，反复重试烧钱；
 * - 做梦用了真实助手的 profile ⇒ 用户的人设/世界书/记忆注入全被带进后台调用（贵且跑偏）；
 * - 水位推进用了 `conversationIds` 而不是 `transcript.consumed` ⇒ **没装进这一轮的轮次被标记成整理过**（静默丢失）；
 * - 护栏停止没当失败 ⇒ 没走完的一轮被当成"整理完了"，剩下的一半永远不再看；
 * - 读取没走 `readMemory` ⇒ 整理 agent 的读取不进遥测，信用记账少一类证据。
 *
 * 这些都在 Android 那一侧（`GenerationHandler` 需要 Context），JVM 单测够不着，所以扫源码。
 */
class MemoryConsolidationWiringTest {

    @Test
    fun `the generation agent routes writes through the proposal sink when asked`() {
        val source = read("app/androidrik/rikkahub/data/ai/MemoryConsolidationGenerationAgent.kt")

        assertTrue(
            "proposeOnly 必须真的接到工具上（否则审批开着的用户被静默绕过审批）",
            source.contains("proposalSink = if (request.proposeOnly)"),
        )
        assertTrue(
            "提案模式要在系统提示里说清（否则模型以为写失败、反复重试）",
            source.contains("MEMORY_CONSOLIDATION_PROPOSAL_NOTE"),
        )
        assertTrue(
            "做梦用临时 profile：不把用户的人设/世界书/记忆注入带进后台调用",
            source.contains("enableMemory = false"),
        )
        assertTrue(
            "整理 agent 的读取也要记遥测（读数进打分的输入）",
            source.contains("memoryRepository.readMemory("),
        )
        assertTrue(
            "护栏停止必须当失败（这一轮没走完，不能当成整理完了）",
            source.contains("stopped by guard"),
        )
    }

    @Test
    fun `the worker advances only the watermarks of what the model saw`() {
        val source = read("app/androidrik/rikkahub/data/ai/MemoryConsolidator.kt")

        assertTrue(
            "水位只能按 transcript.consumed 推进",
            source.contains("markConsolidated(it.conversationId, it.watermark)"),
        )
        assertFalse(
            "不许用请求里的 conversationIds 直接推进（会把没装进这一轮的轮次标记成整理过）",
            source.contains("conversationIds.forEach { repository.markConsolidated"),
        )
        assertTrue(
            "没有可用模型时记成失败换退避，而不是「没可做的事」（后者会每 2 分钟空转）",
            source.contains("no model available for consolidation"),
        )
        assertTrue("自动 16 步", source.contains("MAX_AUTO_STEPS"))
        assertTrue("手动 32 步", source.contains("MAX_MANUAL_STEPS"))
    }

    @Test
    fun `the runtime wires the three triggers and the one-time notice`() {
        val source = read("app/androidrik/rikkahub/data/ai/MemoryConsolidationRuntime.kt")

        assertTrue("切后台要通知调度器（后台的空闲计时不一定走得完）", source.contains("scheduler.onAppHidden()"))
        assertTrue("轮末走应用自己的事件总线", source.contains("event is AppEvent.ChatGenerationEnded"))
        assertTrue("启动补漏", source.contains("trigger.resumePending()"))
        assertTrue(
            "只有真有产出才提示（每次后台整理都弹一下就是噪音）",
            source.contains("run.changedMemories == 0 && run.proposals.isEmpty()"),
        )
        assertTrue("前台状态要跟着生命周期更新", source.contains("foreground.isForeground = false"))
    }

    @Test
    fun `the dream is reachable from the app`() {
        // 任何一条断了，那个开关就变成「看得见但不工作」——静默不整理，没有任何报错
        val di = read("app/androidrik/rikkahub/di/DataSourceModule.kt") +
            read("app/androidrik/rikkahub/di/RepositoryModule.kt")
        assertTrue("数据面没注册", di.contains("MemoryConsolidationRepository("))
        assertTrue("进度表 DAO 没注册（真机启动会 FATAL）", di.contains("memoryConsolidationDao()"))
        assertTrue("整理 agent 没注册", di.contains("MemoryConsolidationGenerationAgent("))
        assertTrue(
            "整理 agent 也必须绑定到接口（worker 要的是 MemoryConsolidationAgent）",
            di.contains("single<MemoryConsolidationAgent>"),
        )
        assertTrue("worker 没注册", di.contains("MemoryConsolidator("))
        assertTrue(
            "worker 必须显式绑定到**接口**：按具体类型注册时 Koin 解析不到 MemoryConsolidationWorker，" +
                "启动直接 FATAL——这条是源码扫描能看出来的部分，绑定是否正确只能靠真机启动冒烟",
            di.contains("single<MemoryConsolidationWorker>"),
        )
        assertTrue("调度器没注册", di.contains("MemoryConsolidationScheduler("))
        assertTrue("运行时没注册", di.contains("MemoryConsolidationRuntime("))
        assertTrue(
            "应用启动没接上（轮末/切后台/启动补漏都在这一个 start 里）",
            read("app/androidrik/rikkahub/RikkaHubApp.kt").contains("get<MemoryConsolidationRuntime>().start()"),
        )
        assertTrue(
            "记忆页的「整理一下」没接到调度器",
            read("app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt")
                .contains("consolidationScheduler.organize("),
        )
        assertTrue(
            "记忆页没有把状态传下去（建议列表就永远是空的）",
            read("app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt")
                .contains("consolidationStatus = consolidationStatus"),
        )
        assertTrue(
            "会话内的一次性提示没接",
            read("app/androidrik/rikkahub/RouteActivity.kt").contains("AppEvent.MemoryConsolidationFinished"),
        )
    }

    @Test
    fun `the dream does not hijack the user keep-alive switch`() {
        val source = read("app/androidrik/rikkahub/data/ai/MemoryConsolidationRuntime.kt") +
            read("app/androidrik/rikkahub/di/DataSourceModule.kt")

        assertFalse(
            "KeepAliveService 是用户自己的开关（常驻通知 + 电池白名单），不该被后台整理悄悄打开；" +
                "保活因此留空实现，退化是可接受的（水位不推进、内容不丢）",
            source.contains("KeepAliveService.start"),
        )
    }

    private fun srcRoot(): File {        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun read(relative: String): String {
        val file = srcRoot().resolve(relative)
        assertTrue("源文件不存在：${file.path}", file.isFile)
        return file.readText()
    }
}
