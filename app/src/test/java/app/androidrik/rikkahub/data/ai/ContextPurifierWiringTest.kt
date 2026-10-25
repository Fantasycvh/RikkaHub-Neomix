package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 净化 dry-run 的接线测试（`design-context-purification.md` §10，照
 * `ThinkingDegenerationWiringTest` 的源码扫描思路，这里用行为断言）：
 * 组装函数确实调用观察者、**实发内容未被改动**（P0 契约）、不传观察者的无头路径可正常组装。
 */
class ContextPurifierWiringTest {

    private val settings = Settings.dummy()
    private val model = Model(modelId = "test-model", displayName = "Test")

    private fun assistant() = Assistant(systemPrompt = "", enableMemory = false)

    @Test
    fun `assembly invokes the purge dry-run observer and leaves the real send untouched`() {
        val degenerate = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("先分析文件结构。".repeat(30) + "好的。执行。".repeat(120))),
        )
        val history = listOf(
            UIMessage.user("把 README 的发行说明改一下"),
            degenerate,
            UIMessage.user("继续"),
        )
        var report: ContextPurifier.Result? = null

        val assembled = buildInternalMessagesWithPlan(
            assistant = assistant(),
            settings = settings,
            model = model,
            messages = history,
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
            purgeObserver = { report = it },
        )

        assertNotNull("dry-run 观察者未被调用", report)
        assertTrue("应至少命中一条规则", report!!.decisions.isNotEmpty())
        // P0 契约：不改发送——实发列表里的退化推理原样保留
        val sent = assembled.messages.first { it.role == MessageRole.ASSISTANT }
        assertEquals(degenerate, sent)
    }

    @Test
    fun `assembly without observer headless path still assembles`() {
        val assembled = buildInternalMessagesWithPlan(
            assistant = assistant(),
            settings = settings,
            model = model,
            messages = listOf(UIMessage.user("任务"), UIMessage.user("继续")),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
        )
        assertTrue(assembled.messages.isNotEmpty())
    }
}
