package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.transformers.transformMessages
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 拼装预览 Tier 4：注入段的**来源归因**与合并段拆分。
 *
 * 两条硬约束：
 * 1. **请求内容逐字节不变**——归因靠 `UIMessageAnnotation`（不参与 part→API 转换），
 *    故合并后的文本与加注解前完全一致。
 * 2. **宁可整段显示、不可少显示**——任一贡献者查不到内容时整段回退，不半拆。
 */
class PromptSegmentAttributionTest {

    private fun modeInjection(
        id: Uuid = Uuid.random(),
        name: String = "条目",
        content: String = "内容",
        position: InjectionPosition = InjectionPosition.TOP_OF_CHAT,
        role: MessageRole = MessageRole.USER,
        priority: Int = 0,
    ) = PromptInjection.ModeInjection(
        id = id,
        name = name,
        enabled = true,
        priority = priority,
        position = position,
        content = content,
        role = role,
    )

    /** 注入要生效必须显式绑定条目 id：`allowConversationPromptInjection=false`（默认）时
     *  生效集合 = `assistant.modeInjectionIds`，不绑定就等于没有注入。 */
    private fun assistantBound(vararg ids: Uuid) = Assistant(modeInjectionIds = ids.toSet())

    private fun textOf(message: UIMessage): String =
        message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    private fun sourcesOf(message: UIMessage): List<Uuid> =
        message.annotations
            .filterIsInstance<UIMessageAnnotation.InjectionSources>()
            .flatMap { it.entryIds }

    // region 注解不影响请求内容

    @Test
    fun `merged injection content is byte-identical to pre-annotation behavior`() {
        val first = modeInjection(name = "A", content = "第一条", priority = 2)
        val second = modeInjection(name = "B", content = "第二条", priority = 1)

        val result = transformMessages(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            assistant = assistantBound(first.id, second.id),
            modeInjections = listOf(first, second),
            lorebooks = emptyList(),
        )

        val merged = result.first { it.isSynthetic && it.role == MessageRole.USER }
        // 合并规则照旧：按 priority 降序、`\n` 连接，与加注解前逐字符相同
        assertEquals("第一条\n第二条", textOf(merged))
    }

    @Test
    fun `merged injection carries contributor ids in priority order`() {
        val low = modeInjection(name = "low", content = "低", priority = 1)
        val high = modeInjection(name = "high", content = "高", priority = 9)

        val result = transformMessages(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            assistant = assistantBound(low.id, high.id),
            modeInjections = listOf(low, high),
            lorebooks = emptyList(),
        )

        val merged = result.first { it.isSynthetic && it.role == MessageRole.USER }
        // 顺序必须与内容拼接顺序一致，否则拆出来的段落会与正文错位
        assertEquals(listOf(high.id, low.id), sourcesOf(merged))
    }

    @Test
    fun `injections at different positions each get their own annotation`() {
        val top = modeInjection(name = "top", content = "顶部", position = InjectionPosition.TOP_OF_CHAT)
        val bottom = modeInjection(
            name = "bottom", content = "底部", position = InjectionPosition.BOTTOM_OF_CHAT
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            assistant = assistantBound(top.id, bottom.id),
            modeInjections = listOf(top, bottom),
            lorebooks = emptyList(),
        )

        val annotated = result.filter { sourcesOf(it).isNotEmpty() }
        assertEquals(2, annotated.size)
        assertEquals(listOf(top.id), sourcesOf(annotated[0]))
        assertEquals(listOf(bottom.id), sourcesOf(annotated[1]))
    }

    @Test
    fun `tail injection annotations follow role grouping`() {
        val systemEntry = modeInjection(
            name = "sys tail", content = "尾系统",
            position = InjectionPosition.END_OF_CHAT, role = MessageRole.SYSTEM,
        )
        val userEntry = modeInjection(
            name = "user tail", content = "尾用户",
            position = InjectionPosition.END_OF_CHAT, role = MessageRole.USER, priority = -1,
        )

        val result = transformMessages(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            assistant = assistantBound(systemEntry.id, userEntry.id),
            modeInjections = listOf(systemEntry, userEntry),
            lorebooks = emptyList(),
        )

        val tailSystem = result.last { it.role == MessageRole.SYSTEM && it.isSynthetic }
        val tailUser = result.last { it.role == MessageRole.USER && it.isSynthetic }
        assertEquals(listOf(systemEntry.id), sourcesOf(tailSystem))
        assertEquals(listOf(userEntry.id), sourcesOf(tailUser))
    }

    @Test
    fun `no injections means no annotation noise`() {
        val result = transformMessages(
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            assistant = assistantBound(),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )
        assertTrue(result.none { sourcesOf(it).isNotEmpty() })
    }

    // endregion

    // region 合并段拆分

    private val fallback = PromptSegment(
        kind = PromptSegmentKind.INJECTION,
        label = "injected",
        role = "user",
        content = "合并后的整段",
        isSynthetic = true,
        tokens = 1,
    )

    private fun source(name: String, origin: PromptSegmentOrigin) = PromptSegmentSource(
        entryId = Uuid.random().toString(),
        name = name,
        origin = origin,
        containerId = Uuid.random().toString(),
        containerName = if (origin == PromptSegmentOrigin.PRESET) "我的预设" else "我的世界书",
    )

    @Test
    fun `splits merged segment back into one segment per contributor`() {
        val a = modeInjection(name = "A", content = "甲")
        val b = modeInjection(name = "B", content = "乙")
        val srcA = source("A", PromptSegmentOrigin.PRESET)
        val srcB = source("B", PromptSegmentOrigin.LOREBOOK)

        val segments = splitMergedInjectionSegment(
            annotations = listOf(UIMessageAnnotation.InjectionSources(listOf(a.id, b.id))),
            fallback = fallback,
            contentById = mapOf(a.id to "甲", b.id to "乙"),
            sourceById = mapOf(a.id to srcA.copy(entryId = a.id.toString()), b.id to srcB.copy(entryId = b.id.toString())),
        )

        assertEquals(2, segments.size)
        assertEquals("甲", segments[0].content)
        assertEquals("乙", segments[1].content)
        // label 换成条目名 —— 目录里就能看清是哪条，而不是一堆「注入」
        assertEquals("A", segments[0].label)
        assertEquals("B", segments[1].label)
        assertEquals(PromptSegmentOrigin.PRESET, segments[0].source?.origin)
        assertEquals(PromptSegmentOrigin.LOREBOOK, segments[1].source?.origin)
        assertEquals(PromptSegmentOrigin.PRESET, segments[0].source?.origin)
    }

    @Test
    fun `unknown contributor falls back to the whole merged segment`() {
        val known = modeInjection(name = "known", content = "已知")
        val missing = Uuid.random()

        val segments = splitMergedInjectionSegment(
            annotations = listOf(UIMessageAnnotation.InjectionSources(listOf(known.id, missing))),
            fallback = fallback,
            contentById = mapOf(known.id to "已知"),
            sourceById = emptyMap(),
        )

        // 宁可整段显示、不可少显示（半拆会让预览与实际请求不符，比不拆更糟）
        assertEquals(1, segments.size)
        assertEquals("合并后的整段", segments[0].content)
        assertNull(segments[0].source)
    }

    @Test
    fun `message without source annotation is left as a single segment`() {
        val segments = splitMergedInjectionSegment(
            annotations = emptyList(),
            fallback = fallback,
            contentById = emptyMap(),
            sourceById = emptyMap(),
        )
        assertEquals(listOf(fallback), segments)
    }

    @Test
    fun `empty contributor list is treated as no annotation`() {
        val segments = splitMergedInjectionSegment(
            annotations = listOf(UIMessageAnnotation.InjectionSources(emptyList())),
            fallback = fallback,
            contentById = emptyMap(),
            sourceById = emptyMap(),
        )
        assertEquals(1, segments.size)
        assertEquals("合并后的整段", segments[0].content)
    }

    @Test
    fun `unmapped entry keeps fallback label but still splits`() {
        val a = modeInjection(name = "A", content = "甲")
        val segments = splitMergedInjectionSegment(
            annotations = listOf(UIMessageAnnotation.InjectionSources(listOf(a.id))),
            fallback = fallback,
            contentById = mapOf(a.id to "甲"),
            // 条目已被删除（映射表里没有）——内容仍可正确拆出，只是无处可跳
            sourceById = emptyMap(),
        )
        assertEquals(1, segments.size)
        assertEquals("甲", segments[0].content)
        assertEquals("injected", segments[0].label)
        assertNull(segments[0].source)
    }

    @Test
    fun `blank contributor name falls back to the generic label`() {
        val a = modeInjection(name = "", content = "无名条目")
        val segments = splitMergedInjectionSegment(
            annotations = listOf(UIMessageAnnotation.InjectionSources(listOf(a.id))),
            fallback = fallback,
            contentById = mapOf(a.id to "无名条目"),
            sourceById = mapOf(
                a.id to PromptSegmentSource(
                    entryId = a.id.toString(),
                    name = "",
                    origin = PromptSegmentOrigin.PRESET,
                    containerId = "p",
                    containerName = "预设",
                )
            ),
        )
        assertEquals("injected", segments[0].label)
        assertNotNull(segments[0].source)
    }

    // endregion
}
