package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆打分的**相关性维度**（`docs/design/design-memory-injection-tiering.md` §3.2）。
 *
 * 两条最该被钉住的契约：
 * 1. **无查询 / 无重叠 ⇒ 与改动前逐位一致**（相关性只抬不压，绝不淘汰"与查询没有词汇重叠"
 *    的身份型记忆）；
 * 2. **有重叠时真的改变入选**（否则这个维度等于没接）。
 *
 * 第 2 条是第 1 条的反面：只测第 1 条的话，把整个相关性维度删掉也全绿。
 */
class MemoryRelevanceTest {

    private val now = 1_800_000_000_000L
    private val dayMs = 24 * 60 * 60 * 1000L

    private fun memory(
        id: Int,
        content: String,
        importance: Float = 0.6f,
        ageDays: Float = 0f,
        description: String = "",
    ) = AssistantMemory(
        id = id.toString(),
        content = content,
        description = description,
        importance = importance,
        updatedAt = now - (ageDays * dayMs).toLong(),
        // sortKey 是输出排序键（= 生产里的自增主键），必须各不相同：
        // 并列会让"先按分数排"的结果被稳定排序抹平，测不出相关性到底有没有生效
        sortKey = id,
    )

    private fun userMessage(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    // region 查询派生

    @Test
    fun `query is the last real user message`() {
        val messages = listOf(
            userMessage("第一条"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("回复"))),
            userMessage("第二条"),
            // 合成 USER（压缩检查点 / 注入）不是用户说的，不得当作查询
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("合成内容")),
                isSynthetic = true,
            ),
        )
        assertEquals("第二条", memoryQueryOf(messages))
    }

    @Test
    fun `no user message means no query`() {
        assertNull(memoryQueryOf(emptyList()))
        assertNull(memoryQueryOf(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("x"))))))
        // 空白消息也算"没有查询"：空查询下相关性必须整体弃权，而不是给出 0 以外的噪声
        assertNull(memoryQueryOf(listOf(userMessage("   "))))
    }

    // endregion

    // region 相关性本身

    @Test
    fun `latin word overlap yields relevance`() {
        val tokens = memoryQueryTokens("How do I install TikZ?")
        val related = tokens.relevanceOf(memory(1, "TikZ install steps for the app"))
        val unrelated = tokens.relevanceOf(memory(2, "User prefers dark coffee"))
        assertTrue("共享实词必须算得出相关性", related > 0f)
        assertEquals("毫无重叠必须是 0（增益退化成 1.0）", 0f, unrelated, 0f)
    }

    @Test
    fun `cjk bigram overlap yields relevance`() {
        val tokens = memoryQueryTokens("TikZ 渲染器怎么切换")
        assertTrue(
            "中文必须算得出相关性（bigram 近似分词）",
            tokens.relevanceOf(memory(1, "TikZ 渲染器已从轻量级 WASM 切到完整 LaTeX")) > 0f,
        )
        assertEquals(
            "不同主题的中文记忆不该被算成相关",
            0f,
            tokens.relevanceOf(memory(2, "用户喜欢在下雨天喝手冲咖啡")),
            0f,
        )
    }

    @Test
    fun `single character cjk query still works`() {
        // 单字查询产不出 bigram —— 若不兜底成单字，短查询永远算不出相关性
        val tokens = memoryQueryTokens("猫")
        assertFalse(tokens.isEmpty)
        assertTrue(tokens.relevanceOf(memory(1, "用户养了一只猫")) > 0f)
    }

    @Test
    fun `function words alone are not relevance evidence`() {
        // 「的是」「我们」「可以」这类双虚词不得构成相关性
        val tokens = memoryQueryTokens("我们的是可以")
        assertTrue("双虚词应当被过滤掉，实际词元=$tokens", tokens.isEmpty)
        assertEquals(0f, tokens.relevanceOf(memory(1, "我们的是可以随便写点什么")), 0f)
    }

    @Test
    fun `long queries do not dilute a genuine match`() {
        // 一整段话里命中若干实词，必须仍能越过下限；否则"用户写了一整段话"时相关性形同不存在。
        // 注意这是**真实长度的自然语句**，不是把同一个词重复三十遍——后者等于人为把
        // 分母灌满，测的是测试自己而不是实现。
        val long = "我最近在用 TikZ 画图，但渲染器好像出了问题，中文节点和数学公式都不太对，" +
            "想问问怎么切换到完整的 LaTeX 引擎，或者有没有别的排查办法？"
        val tokens = memoryQueryTokens(long)
        assertTrue(
            "长查询下命中实词仍须有相关性（分母封顶）",
            tokens.relevanceOf(memory(1, "TikZ 渲染器已切换到完整 LaTeX 引擎")) > 0f,
        )
        assertTrue(
            "同一段话对不相干的记忆必须仍算 0",
            tokens.relevanceOf(memory(2, "用户喜欢在下雨天喝手冲咖啡")) == 0f,
        )
    }

    // endregion

    // region 接入选取：向后兼容 + 真的生效

    @Test
    fun `no query is byte identical to the pre-relevance behaviour`() {
        val memories = listOf(
            memory(1, "身份记忆", importance = 0.9f, ageDays = 300f),
            memory(2, "次要且老化", importance = 0.3f, ageDays = 300f),
            memory(3, "中等记忆", importance = 0.6f, ageDays = 10f),
        )
        // **预算刻意只够一条**：宽预算下三条都入选，四个路径当然一样——那等于没测。
        // 紧预算下"选谁"完全由排序决定，才能证明相关性在没有证据时**没有扰动排序**。
        val before = selectMemoriesForPrompt(memories, budgetTokens = 10, now = now)
        val withNull = selectMemoriesForPrompt(memories, budgetTokens = 10, now = now, query = null)
        val withBlank = selectMemoriesForPrompt(memories, budgetTokens = 10, now = now, query = "   ")
        val noOverlap = selectMemoriesForPrompt(
            memories,
            budgetTokens = 10,
            now = now,
            query = "zzz qqq unrelated latin words",
        )
        // 四条路径都必须给出**同一个**集合，而且顺序一致（顺序决定尾部字节）
        assertEquals(before, withNull)
        assertEquals(before, withBlank)
        assertEquals(
            "查询与所有记忆都没有词汇重叠时，结果必须与改动前逐位一致（相关性只抬不压）",
            before.map { it.id },
            noOverlap.map { it.id },
        )
    }

    @Test
    fun `relevance changes which memories are injected`() {
        // 两条同重要度、同新鲜度的记忆；查询只讲其中一条。
        // 不接相关性时排序按 sortKey 降序（2 在 1 前），接入后相关的那条必须排到前面。
        val memories = listOf(
            memory(1, "TikZ 渲染器已切换到完整 LaTeX 引擎", importance = 0.6f),
            memory(2, "用户喜欢在下雨天喝手冲咖啡", importance = 0.6f),
        )
        val query = "TikZ 渲染器怎么切换"

        // **预算只够一条**是关键：输出顺序恒为 sortKey 降序（字节稳定的要求），
        // 所以"顺序变了"永远观察不到——相关性只能通过**改了谁被选中**体现出来。
        val without = selectMemoriesForPrompt(memories, budgetTokens = 10, now = now)
        val with = selectMemoriesForPrompt(memories, budgetTokens = 10, now = now, query = query)

        assertEquals("前置：不接相关性时按 sortKey 降序取（2 在 1 前）", "2", without.single().id)
        assertEquals("接了相关性必须改选相关的那条", "1", with.single().id)
    }

    @Test
    fun `relevance can rescue a below floor memory`() {
        // 刻意标低重要度 + 老化 ⇒ 打分在下限之下（见 MemoryInjectionFloorTest 的同款构造）
        val ignored = memory(1, "用户提过偏好 Python", importance = 0.3f, ageDays = 300f)
        val fresh = memory(2, "无关的新记忆", importance = 0.6f, ageDays = 0f)

        val without = selectMemoriesForPrompt(listOf(ignored, fresh), budgetTokens = 10_000, now = now)
        assertFalse("前置：不接相关性时它应沉底", without.any { it.id == "1" })

        val with = selectMemoriesForPrompt(
            listOf(ignored, fresh),
            budgetTokens = 10_000,
            now = now,
            query = "Python 的偏好是什么",
        )
        assertTrue("本轮查询确实在讲它 ⇒ 相关性应把它救回线上", with.any { it.id == "1" })
    }

    @Test
    fun `relevance never outranks a much more important memory`() {
        // 相关性是增益不是闸门：身份型记忆（与查询无词汇重叠）仍须留在入选集合里。
        // 这条是"只抬不压"最重要的一面 —— 若相关性去淘汰低分记忆，
        // 第一批被淘汰的正是最该留住的那批。
        val identity = memory(1, "用户是素食者", importance = 1f, ageDays = 0f)
        val topical = memory(2, "TikZ 渲染器笔记", importance = 0.3f, ageDays = 300f)

        val selected = selectMemoriesForPrompt(
            listOf(identity, topical),
            budgetTokens = 10_000,
            now = now,
            query = "TikZ 渲染器怎么切换",
        )
        assertTrue("身份型记忆不得因「与查询无重叠」而被挤掉", selected.any { it.id == "1" })
    }

    @Test
    fun `same query produces identical bytes and a different query may not`() {
        val memories = listOf(
            memory(1, "TikZ 渲染器笔记", importance = 0.6f),
            memory(2, "咖啡偏好记录", importance = 0.6f),
        )
        // 同样只够一条：宽预算下两条都入选，换话题也不会改变字节，测不出相关性
        fun render(query: String) = buildMemoryDataPrompt(memories, budgetTokens = 10, now = now, query = query)

        // 同一查询 ⇒ 逐字节一致（尾部块也因此可在同一话题的连续轮次里命中缓存）
        assertEquals(render("TikZ 渲染器怎么切换"), render("TikZ 渲染器怎么切换"))
        // 换话题 ⇒ 允许变化（这正是把记忆放到尾部换来的自由，§3.2）
        assertNotEquals(render("TikZ 渲染器怎么切换"), render("咖啡怎么冲"))
    }

    // endregion
}
