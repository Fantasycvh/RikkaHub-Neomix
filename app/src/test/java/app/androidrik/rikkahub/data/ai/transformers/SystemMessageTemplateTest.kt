package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import io.pebbletemplates.pebble.PebbleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `systemMessageTemplate`（合成内容的独立模板，2026-10-06 新增）的两条不变量。
 *
 * 背景：合成消息（system 提示本体 / 注入 / 提醒 / 压缩检查点）默认被 `TemplateTransformer` 跳过，
 * 因为 [Assistant.messageTemplate] 含 `{{time}}/{{date}}`，而合成消息的 `createdAt` 就是**本次请求
 * 时间** ⇒ 套用会让 system 前缀每请求都变、prompt 缓存必破（上游 #1790 的取舍）。
 * 新增的 [Assistant.systemMessageTemplate] 是给「要形态一致」的用户的 opt-in，其安全性靠两件事：
 *
 * 1. **上下文里没有时间/日期**（[syntheticTemplateContext]）⇒ 渲染结果与请求时刻无关；
 * 2. **缺 `{{ message }}` 就回退成不套用**（[usableSystemTemplate]）⇒ 不会把整段 system 提示渲染成空。
 *
 * 注：`TemplateTransformer.transform` 本身依赖 Android `Context`（`TransformerContext`），
 * 故这里只覆盖上述纯逻辑 + 真实 Pebble 渲染；**接线（合成消息走该模板、非合成仍走原模板）由真机
 * 报文验证**（见 docs/roadmap.md 2026-10-06 条目）。
 */
class SystemMessageTemplateTest {

    /** 与 DI 中一致的引擎配置（`DataSourceModule`：autoEscaping(false)）。 */
    private val engine = PebbleEngine.Builder().autoEscaping(false).build()

    @Test
    fun `blank template means not set`() {
        assertNull(usableSystemTemplate(""))
        assertNull("  \n ".let { usableSystemTemplate(it) })
    }

    @Test
    fun `template missing message placeholder falls back to not set`() {
        // 漏写 {{ message }} 会让整段 system 提示渲染成空 ⇒ 必须回退（与 messageTemplate 刻意不对称）
        assertNull(usableSystemTemplate("<|{{ role }}|>{{ messages }}"))
        assertNull(usableSystemTemplate("{{role}}{{time}}"))
        assertNull(usableSystemTemplate("<|{{ role }}|>"))
    }

    @Test
    fun `template with message placeholder is usable`() {
        assertNotNull(usableSystemTemplate("{{ message }}"))
        assertNotNull(usableSystemTemplate("<|{{ role }}|>\n{{ message }}"))
    }

    @Test
    fun `synthetic context carries no time or date`() {
        val keys = syntheticTemplateContext(message = "x", role = "system").keys

        // 缓存稳定性不变量：合成消息的上下文**不得**含时间/日期，否则 system 前缀每请求都变。
        assertEquals(setOf("message", "role"), keys)
    }

    @Test
    fun `chatml style template wraps synthetic content as system`() {
        val template = engine.getLiteralTemplate(usableSystemTemplate("<|{{ role }}|>\n{{ message }}")!!)

        val rendered = renderTemplate(
            template,
            syntheticTemplateContext(message = "你是我的专属私人全能助手…", role = "system"),
        )

        assertEquals("<|system|>\n你是我的专属私人全能助手…", rendered)
    }

    // ---- 接线：合成走 system 模板、非合成走会话模板（两路互不串线）----

    private val chatTemplate = engine.getLiteralTemplate("<|{{ role }}|>{{ message }}<{{ date }}>")
    private val systemChatml = engine.getLiteralTemplate("<|{{ role }}|>{{ message }}")

    @Test
    fun `synthetic message is untouched when no system template set`() {
        val synthetic = UIMessage.system("system 提示本体").copy(isSynthetic = true)

        val result = applyTemplates(
            messages = listOf(synthetic),
            chatTemplate = chatTemplate,
            systemTemplate = null,
        )

        // 默认行为不变：合成消息**不套用**任何模板（上游 #1790）
        assertEquals("system 提示本体", (result.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `synthetic message uses system template while chat message keeps using chat template`() {
        val synthetic = UIMessage.system("system 提示").copy(isSynthetic = true)
        val user = UIMessage.user("你好")

        val result = applyTemplates(
            messages = listOf(synthetic, user),
            chatTemplate = chatTemplate,
            systemTemplate = systemChatml,
        )

        assertEquals("<|system|>system 提示", (result[0].parts.single() as UIMessagePart.Text).text)
        // 非合成仍走会话模板（含 time/date 位），两路互不串线
        val userText = (result[1].parts.single() as UIMessagePart.Text).text
        assertTrue(userText, userText.startsWith("<|user|>你好<"))
    }

    @Test
    fun `time and date render empty and stay stable`() {
        // 用户若把 {{ time }} 写进系统模板：Pebble 非严格模式渲染为空串，且**多次渲染结果一致**
        // ⇒ 即使写错变量名也不会破坏缓存（这正是「上下文不给时间」的设计目的）。
        val template = engine.getLiteralTemplate(usableSystemTemplate("[{{ date }} {{ time }}] {{ message }}")!!)

        val first = renderTemplate(template, syntheticTemplateContext(message = "正文", role = "system"))
        val second = renderTemplate(template, syntheticTemplateContext(message = "正文", role = "system"))

        // 模板里的字面空格保留、两个占位符各自渲染为空 ⇒ "[ ] 正文"（内容不丢、结果稳定）
        assertEquals("[ ] 正文", first)
        assertEquals(first, second)
    }
}
