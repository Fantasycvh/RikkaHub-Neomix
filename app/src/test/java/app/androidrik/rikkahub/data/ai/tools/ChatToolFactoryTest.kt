package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatToolFactory 装配判定的纯逻辑单测。
 *
 * `shouldUseExternalWebSearch` 随 ChatToolFactory 从 ChatService 迁到本包
 * （上游 `aac6e963` / #1666「统一内置搜索与外挂搜索行为」），测试随之迁移。
 */
class ChatToolFactoryTest {

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    // ---- P2a：ToolSource 段名契约锚定（子代理信封/折叠提升按它判断，漂移即静默失效） ----

    @Test
    fun `tool source segment names are frozen contracts`() {
        val expected = mapOf(
            ToolSource.Memory to "memory",
            ToolSource.Search to "search",
            ToolSource.Local to "local",
            ToolSource.Image to "image",
            ToolSource.TaskManage to "task_manage",
            ToolSource.Conversation to "conversation",
            ToolSource.Workspace to "workspace",
            ToolSource.SkillInstall to "skill-install",
            ToolSource.Skills to "skills",
            ToolSource.Knowledge to "knowledge",
            ToolSource.Subagent to "subagent",
            ToolSource.McpDynamic to "mcp-dynamic",
        )
        expected.forEach { (source, name) -> assertEquals(name, source.segmentName) }
        // 参数化来源的格式与历史字符串逐字符相同（含前缀与冒号）
        assertEquals("mcp:日历服务器", ToolSource.McpServer("日历服务器").segmentName)
        assertEquals("plugin:sample.notebook_tool", ToolSource.Plugin("sample.notebook_tool").segmentName)
    }

    // ---- P2a：插件段跨段重名闸门（拍板 1：整段拒绝，模型可见名不变） ----

    private fun seg(source: ToolSource, vararg names: String) =
        ToolSegment(source, names.map { Tool(name = it, description = "", execute = { emptyList() }) })

    @Test
    fun `plugin segment conflicting with existing tool is rejected as a whole`() {
        val existing = listOf(seg(ToolSource.Local, "search_web"))
        val plugin = listOf(seg(ToolSource.Plugin("p1"), "note", "search_web", "extra"))

        val (accepted, rejected) = acceptPluginToolSegments(existing, plugin)

        assertTrue(accepted.isEmpty())
        assertEquals(listOf(RejectedPluginTools("p1", setOf("search_web"))), rejected)
    }

    @Test
    fun `later conflicting plugin segment is rejected while earlier one stays accepted`() {
        val existing = listOf(seg(ToolSource.Local, "get_time"))
        val plugins = listOf(
            seg(ToolSource.Plugin("p1"), "note"),
            seg(ToolSource.Plugin("p2"), "note2"),
            seg(ToolSource.Plugin("p3"), "note2"),
        )

        val (accepted, rejected) = acceptPluginToolSegments(existing, plugins)

        assertEquals(listOf("p1", "p2"), accepted.map { (it.source as ToolSource.Plugin).pluginId })
        assertEquals(listOf(RejectedPluginTools("p3", setOf("note2"))), rejected)
    }

    @Test
    fun `intra-plugin duplicate names reject the whole segment`() {
        val plugin = listOf(seg(ToolSource.Plugin("p1"), "note", "note"))

        val (accepted, rejected) = acceptPluginToolSegments(emptyList(), plugin)

        assertTrue(accepted.isEmpty())
        assertEquals(listOf(RejectedPluginTools("p1", setOf("note"))), rejected)
    }

    // ---- P2a/P2b：审批/超时画像（诊断三问之二：要不要审批、超时多少） ----

    @Test
    fun `approval baselines cover every source with stable policies`() {
        // 拍板 2：基线只描述现状，关键取值漂移即测试失败
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.Memory).policy)
        assertEquals(ApprovalPolicy.NONE, approvalBaseline(ToolSource.Search).policy)
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.Local).policy)
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.Image).policy)
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.TaskManage).policy)
        assertEquals(ApprovalPolicy.NONE, approvalBaseline(ToolSource.Conversation).policy)
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.Workspace).policy)
        assertEquals(ApprovalPolicy.NONE, approvalBaseline(ToolSource.SkillInstall).policy)
        assertEquals(ApprovalPolicy.NONE, approvalBaseline(ToolSource.Skills).policy)
        assertEquals(ApprovalPolicy.NONE, approvalBaseline(ToolSource.Knowledge).policy)
        assertEquals(ApprovalPolicy.DYNAMIC, approvalBaseline(ToolSource.Subagent).policy)
        assertEquals(ApprovalPolicy.FOLLOWS_TARGET, approvalBaseline(ToolSource.McpDynamic).policy)
        assertEquals(ApprovalPolicy.PROVIDER, approvalBaseline(ToolSource.McpServer("s")).policy)
        assertEquals(ApprovalPolicy.PLUGIN_DEFINED, approvalBaseline(ToolSource.Plugin("p")).policy)
    }

    @Test
    fun `approval diagnostic flags closures on none-baseline sources`() {
        val plain = Tool("a", "", execute = { emptyList() })
        assertEquals("none", approvalDiagnostic(approvalBaseline(ToolSource.Search), listOf(plain)))

        val withDynamic = listOf(
            plain,
            Tool("b", "", needsApproval = { true }, execute = { emptyList() }),
        )
        assertEquals(
            "dynamic(1/2dyn)",
            approvalDiagnostic(approvalBaseline(ToolSource.Image), withDynamic),
        )

        // 基线为 NONE 的来源测得闭包 → 异常信号（可能有人配了不该配的审批）
        val flagged = listOf(Tool("x", "", needsApproval = { true }, execute = { emptyList() }))
        assertEquals("none(1dyn!)", approvalDiagnostic(approvalBaseline(ToolSource.Search), flagged))
    }

    @Test
    fun `timeout summary reports max and self-managed count`() {
        val defaults = listOf(Tool("a", "", execute = { emptyList() }))
        assertEquals("60s", timeoutSummary(defaults))

        val mixed = listOf(
            Tool("a", "", executionTimeoutMillis = 615_000, execute = { emptyList() }),
            Tool("b", "", executionTimeoutMillis = null, execute = { emptyList() }),
        )
        assertEquals("615s+1self", timeoutSummary(mixed))

        val allSelf = listOf(Tool("a", "", executionTimeoutMillis = null, execute = { emptyList() }))
        assertEquals("self", timeoutSummary(allSelf))
    }
}
