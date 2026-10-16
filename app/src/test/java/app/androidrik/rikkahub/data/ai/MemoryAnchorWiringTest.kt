package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 锚点的**纪律守卫**：AI 不得自填锚点。
 *
 * 为什么用源码扫描而不是行为测试：要钉住的是一条「**不能有这个参数**」的约束 ——
 * 它没有编译错误、也没有运行时异常，只能靠"源码里不存在"来表达。一旦有人给
 * `addMemory` 加上 `isAnchor` 参数、或在 `memory_tool` 里把它暴露给模型，
 * 模型就能把自己写的记忆全标成锚点 ⇒ **system 块每次写入都变 ⇒ 每次写入全量失效**
 * （正是本次改造要根治的问题，实测命中率 98% → 0%）。
 *
 * 依据：bigeye 的同款安全约束（`fragment_store.py:965-971` 白名单 +
 * `tools/memory_tools.py:64`「不接受 AI 自填」）。
 * 见 `docs/design/design-memory-injection-tiering.md`。
 */
class MemoryAnchorWiringTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun source(relative: String): String =
        File(srcRoot(), relative).readText()

    /** `setAnchor` 是**唯一**能设置锚点的入口（用户路径）。 */
    @Test
    fun `setAnchor is the only anchor-writing entry point`() {
        val repo = source("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        assertTrue(
            "缺少 setAnchor —— 用户将没有任何途径固化记忆（L1 会退化成死代码）",
            repo.contains("suspend fun setAnchor("),
        )
    }

    /**
     * `addMemory`（模型的写入路径）**不得**接受锚点参数。
     *
     * 注意断言的是参数表而不是函数体注释 —— 注释里出现 `isAnchor` 是允许的（正说明纪律）。
     */
    @Test
    fun `addMemory must not accept an anchor parameter`() {
        val repo = source("app/androidrik/rikkahub/data/repository/MemoryRepository.kt")
        val start = repo.indexOf("suspend fun addMemory(")
        assertTrue("找不到 addMemory", start >= 0)
        val params = repo.substring(start).substringBefore("): ")
        assertFalse(
            "addMemory 不得接受 isAnchor：模型写入路径一旦能设置锚点，" +
                "system 块就每次写入都变，前缀缓存全废",
            params.contains("isAnchor"),
        )
    }

    /** 锚点标志**不得**出现在对外序列化里（否则模型看得见、会试图影响它）。 */
    @Test
    fun `isAnchor is not serialized to the model`() {
        val model = source("app/androidrik/rikkahub/data/model/Assistant.kt")
        val anchorIdx = model.indexOf("val isAnchor: Boolean = false")
        assertTrue("找不到 AssistantMemory.isAnchor", anchorIdx >= 0)
        // 它前面最近的注解必须是 @Transient（不是 @Serializable 暴露给模型的字段）
        val before = model.substring(0, anchorIdx)
        val lastAnnotation = before.trimEnd().substringAfterLast('\n').trim()
        assertTrue(
            "isAnchor 必须是 @Transient（模型不该看到或设置锚点标志），实际前一行是：$lastAnnotation",
            lastAnnotation.startsWith("@Transient"),
        )
    }

    // ---- 冻结快照的接线（设计稿 §5：会话开始冻结 + 记忆页手动刷新） ----

    /**
     * **冻结了却没传给装配** —— 最容易出的错：写库、日志都对，请求里还是实时锚点。
     *
     * 这条链路（ChatService → GenerationHandler → buildInternalMessages）跨三个文件、
     * 且 ChatService 需要 Android Context 才能跑起来，行为测试够不着，故用源码扫描。
     */
    @Test
    fun `conversation start freezes the anchors and passes the snapshot into generation`() {
        val service = source("app/androidrik/rikkahub/service/ChatService.kt")
        val callIdx = service.indexOf("val frozenAnchorIds = ensureFrozenAnchors(")
        assertTrue("会话开始必须冻结锚点快照（ensureFrozenAnchors）", callIdx >= 0)

        // 冻结必须发生在 generateText **之前**（否则首次请求用的是实时锚点，与库里的快照相差一轮）
        val generateIdx = service.indexOf("generationHandler.generateText(", callIdx)
        assertTrue("冻结必须紧邻 generateText 调用之前", generateIdx > callIdx)

        // 且渲染出的快照必须真的传进去
        val call = service.substring(generateIdx).substringBefore(".onCompletion {")
        assertTrue(
            "generateText 必须收到 frozenAnchorIds —— 只在库里冻结、不传给装配等于没冻",
            call.contains("frozenAnchorIds = frozenAnchorIds"),
        )
    }

    /**
     * **手动刷新点**的两个要求：① 真被记忆页调用；② 先同步内存态再批量落库。
     *
     * ②的反例（先写库）会留下「库已换档、内存态仍旧」的窗口：此刻任意一次整对象保存或
     * 拼装预览都还在用旧快照（`moveConversationToFolder` 的注释记着同一条教训）。
     */
    @Test
    fun `manual refresh is called by the memory page and syncs memory before the database`() {
        val vm = source("app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt")
        assertTrue(
            "记忆页固化锚点后必须刷新各会话的 L1 快照，否则用户改了锚点当前会话毫无变化",
            vm.contains("chatService.refreshFrozenAnchorsForAssistant("),
        )

        val service = source("app/androidrik/rikkahub/service/ChatService.kt")
        val start = service.indexOf("suspend fun refreshFrozenAnchorsForAssistant(")
        assertTrue("找不到 refreshFrozenAnchorsForAssistant", start >= 0)
        val body = service.substring(start).substringBefore("\n    /**")

        val sessionSyncIdx = body.indexOf("session.updateMetadata(")
        val bulkWriteIdx = body.indexOf("updateFrozenAnchorsForAssistant(")
        assertTrue("刷新点必须同步活跃 session 的内存态", sessionSyncIdx >= 0)
        assertTrue("刷新点必须批量落库（覆盖没有活跃 session 的会话）", bulkWriteIdx >= 0)
        assertTrue(
            "必须先同步内存态、再批量落库：反过来会留下「库已换档、内存态仍旧」的窗口",
            sessionSyncIdx < bulkWriteIdx,
        )
    }

    /**
     * **新建记忆时那个「固化为锚点」开关不能是死的**。
     *
     * `addMemory`（仓储层）按纪律**不接受** `isAnchor` 参数（AI 不得自填锚点），
     * 而记忆页的新建与编辑共用同一个对话框、锚点开关也照常显示 ⇒ 新建路径必须**自己补一次**
     * `setAnchor`。不补的形态是：用户打开开关、保存，开关被**静默丢弃**，界面看起来一切正常
     * —— 这是真实发生过的一版 bug。
     */
    @Test
    fun `creating a memory keeps the anchor flag chosen in the dialog`() {
        val vm = source("app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt")
        val start = vm.indexOf("fun addMemory(")
        assertTrue("找不到 AssistantDetailVM.addMemory", start >= 0)
        val newline = '\n'
        val body = vm.substring(start).substringBefore("$newline    }")
        assertTrue(
            "新建记忆必须自己补 setAnchor：仓储层的 addMemory 不接受 isAnchor（反自填纪律），" +
                "不补就会把新建对话框里的锚点开关静默丢掉",
            body.contains("setAnchor("),
        )
    }

    /**
     * **改锚点不得静默刷新已有会话**（设计稿 §5，2026-10-16 用户决定"两个入口都显式"）。
     *
     * 曾经的做法是「保存记忆时顺带刷新该助手全部会话」。它的形态是：用户在记忆页点一下
     * 「固化为锚点」，该助手**所有**会话的缓存被静默作废 —— 而那正是这套分层设计要消灭的
     * 东西。改锚点现在只影响**新会话**；已有会话要采纳，必须显式按下两个入口之一。
     */
    @Test
    fun `editing an anchor never silently refreshes existing chats`() {
        val vm = source("app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt")
        // 用 0x0A.toChar() 而不是字符字面量转义：本文件由脚本批量生成，转义容易被吃掉
        val newline = 0x0A.toChar()
        for (fn in listOf("fun addMemory(", "fun updateMemory(")) {
            val start = vm.indexOf(fn)
            assertTrue("找不到 $fn", start >= 0)
            val body = vm.substring(start).substringBefore("$newline    }")
            assertFalse(
                "$fn 不得顺带刷新已有会话：点一下锚点开关就把该助手所有会话的缓存静默作废，" +
                    "正是分层设计要消灭的形态",
                body.contains("refreshFrozenAnchors"),
            )
        }
        // 但助手指级入口必须还在（记忆页那个显式按钮）
        assertTrue(
            "记忆页的助手指级「固化记忆」入口不见了",
            vm.contains("suspend fun freezeMemoryAnchors("),
        )
    }

    /**
     * **会话级入口的接线**：会话菜单 → ChatVM → ChatService。
     *
     * 两个入口都显式是用户的选择（B）：会话级只让选中的那一条付一次前缀失效，
     * 助手指级一次改完所有。少任何一环，菜单里就会出现一个按了没反应的项。
     */
    @Test
    fun `conversation level freeze is wired from the chat menu`() {
        val list = source("app/androidrik/rikkahub/ui/pages/chat/ConversationList.kt")
        assertTrue(
            "会话菜单缺少「本对话固化记忆」项",
            list.contains("R.string.chat_memory_freeze_action"),
        )
        assertTrue(
            "菜单项必须回调 onFreezeMemory（否则按了没反应）",
            list.contains("onFreezeMemory(conversation)"),
        )

        val vm = source("app/androidrik/rikkahub/ui/pages/chat/ChatVM.kt")
        assertTrue(
            "ChatVM 未接到 ChatService.freezeAnchorsForConversation",
            vm.contains("chatService.freezeAnchorsForConversation("),
        )
    }

    /**
     * **会话内的记忆入口**必须同时给出「隐身」与「固化」，且隐身状态做在图标上。
     *
     * 缺任何一半，这个按钮就退化成半成品：只有隐身开关 ⇒ 固化又回到抽屉菜单里两次点击；
     * 只有固化 ⇒ 用户看不出这个对话当前碰不碰记忆库（而"隐身"最需要的就是一眼可见）。
     */
    @Test
    fun `in-chat memory entry offers both incognito and freeze`() {
        val button = source("app/androidrik/rikkahub/ui/components/ai/MemoryPicker.kt")
        assertTrue(
            "会话内入口缺少隐身开关",
            button.contains("R.string.chat_memory_disabled_toggle"),
        )
        assertTrue(
            "会话内入口缺少固化动作",
            button.contains("R.string.chat_memory_freeze_action"),
        )
        assertTrue(
            "隐身在状态上要一眼可见：图标本身必须换成 Incognito",
            button.contains("HugeIcons.Incognito"),
        )

        val input = source("app/androidrik/rikkahub/ui/components/ai/ChatInput.kt")
        assertTrue("ChatInput 的图标行未接入 MemoryButton", input.contains("MemoryButton("))

        val page = source("app/androidrik/rikkahub/ui/pages/chat/ChatPage.kt")
        assertTrue(
            "ChatPage 未把本对话的记忆状态喂给入口",
            page.contains("memoryDisabled = conversation.memoryDisabled"),
        )
        assertTrue(
            "ChatPage 未接固化回调（按钮会点了没反应）",
            page.contains("vm.freezeMemoryAnchors(conversation)"),
        )
    }

    /**
     * **拼装预览必须与实发同参**（本仓明确防的坑）。
     *
     * 实测过的回归形态：尾部记忆块加进来时，预览这份 `buildInternalMessages` 调用漏传了
     * `memoryDisabled` ⇒ 隐身对话的预览凭空多出一段尾部记忆块，而实发里没有。
     */
    @Test
    fun `preview passes the same memory flags as the real request`() {
        val inspector = source("app/androidrik/rikkahub/data/ai/PromptInspector.kt")
        val callIdx = inspector.indexOf("val internalMessages = buildInternalMessages(")
        assertTrue("找不到预览侧的 buildInternalMessages 调用", callIdx >= 0)
        val call = inspector.substring(callIdx).substringBefore("\n    )")
        assertTrue(
            "预览漏传 memoryDisabled ⇒ 隐身对话的预览多出尾部记忆块（实发里没有）",
            call.contains("memoryDisabled = conversation.memoryDisabled"),
        )
        assertTrue(
            "预览漏传 frozenAnchorIds ⇒ 锚点归因按实时集合算，与实发不一致",
            call.contains("frozenAnchorIds = conversation.frozenAnchorIds"),
        )
    }
}
