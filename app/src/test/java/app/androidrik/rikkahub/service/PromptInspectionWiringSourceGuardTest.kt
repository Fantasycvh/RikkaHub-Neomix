package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼装预览「重活离开主线程」的**调用点源码守卫**。
 *
 * 为什么必须源码扫描：`PromptInspectionDispatchTest` 只证明 [withPromptInspectionDispatcher]
 * 自身会换线程、能传播取消，却证明不了 `ChatService.inspectPromptAssembly` **真的经过它**——
 * 谁把入口里那一句删掉/改回直接调用，helper 的行为测试依然全绿，主线程 ANR 会静默回归。
 * ChatService 依赖 Application / Room / 大量仓库，JVM 单测起不来（与
 * `TitleWriteBackSourceGuardTest` 同一处境，本仓既有同类做法），源码守卫是这里唯一可行的
 * 自动防线；helper 的行为由 `PromptInspectionDispatchTest` 负责，两者互补。
 *
 * 钉三条（按语义断言，不绑格式）：
 * 1. 入口函数体里必须出现 `withPromptInspectionDispatcher`；
 * 2. 快照（settings / conversation）必须在调度**之前**读——`getConversationFlow` 会
 *    `getOrCreateSession`，其 `_sessionsVersion.value++` 非原子，不能搬到 Default 线程；
 * 3. 重活（完整组装 + 记忆读取 + 工具装配 + BPE 分词）必须在调度**之后**。
 */
class PromptInspectionWiringSourceGuardTest {

    @Test
    fun `inspectPromptAssembly dispatches heavy work through the background helper`() {
        val body = indentedBlock(chatServiceSource(), "suspend fun inspectPromptAssembly(")

        assertTrue(
            "入口必须经 withPromptInspectionDispatcher 调度（删掉即主线程 ANR 回归）",
            body.contains("withPromptInspectionDispatcher"),
        )
        val dispatchAt = body.indexOf("withPromptInspectionDispatcher")

        val settingsSnapshotAt = body.indexOf("settingsStore.settingsFlow.first()")
        assertTrue("入口必须读 settings 快照", settingsSnapshotAt >= 0)
        assertTrue(
            "settings 快照必须在调度之前读（session 创建有 Main 线程约定）",
            settingsSnapshotAt < dispatchAt,
        )

        val conversationSnapshotAt = body.indexOf("getConversationFlow(conversationId).value")
        assertTrue("入口必须读 conversation 快照", conversationSnapshotAt >= 0)
        assertTrue(
            "conversation 快照必须在调度之前读（getOrCreateSession 不能跑在 Default 线程）",
            conversationSnapshotAt < dispatchAt,
        )

        listOf("inspectPrompt(", "memoryRepository.get", "chatToolFactory.createTools(").forEach { heavy ->
            val heavyAt = body.indexOf(heavy)
            assertTrue("入口里找不到重活调用：$heavy", heavyAt >= 0)
            assertTrue(
                "$heavy 必须在后台调度之后执行（否则仍在调用方/主线程上跑）",
                heavyAt > dispatchAt,
            )
        }
    }

    @Test
    fun `dispatcher helper wraps the block in a real background dispatcher`() {
        val source = chatServiceSource()
        val start = source.indexOf("internal suspend fun <T> withPromptInspectionDispatcher(")
        assertTrue("找不到 withPromptInspectionDispatcher 定义", start >= 0)
        val classAt = source.indexOf("class ChatService(")
        assertTrue("withPromptInspectionDispatcher 必须在 ChatService 类声明之前（顶层 helper）", classAt > start)
        val helper = source.substring(start, classAt)

        assertTrue(
            "helper 必须默认真切到后台 dispatcher（Default/IO）；默认 Main 等于没修",
            helper.contains("Dispatchers.Default") || helper.contains("Dispatchers.IO"),
        )
        assertFalse("helper 默认不能是 Dispatchers.Main", helper.contains("Dispatchers.Main"))
        assertTrue("helper 必须用 withContext 执行 block", helper.contains("withContext("))
        assertTrue("helper 必须真的执行传入的 block()", helper.contains("block()"))
    }
}

/**
 * 半截草稿 / 停止落库的**调用点源码守卫**（同文件内独立成类：与拼装预览调度不是同一语义）。
 *
 * 钉两条其它测试覆盖不到的调用点不变量：
 * 1. `GenerationPartialFailureException`（失败跳过 `onGenerationFinish`）分支必须先把
 *    `data:` 图片转成本地文件、并收尾 reasoning，再 `saveConversation`——前者不转则
 *    `ConversationRepository.conversationToConversationEntity` 的
 *    `require(!hasBase64Part())` 会让整轮草稿写不进库；后者不收尾则重进会话时思考卡片会按
 *    「进行中」永动计时（`onCompletion` 先修好的内存态会被该分支用未收尾的 partialMessages 盖掉）；
 * 2. `persistStoppedGeneration` 必须在 `withContext(NonCancellable)` 内、先清洗图片并收尾
 *    reasoning 再写库——取消在飞时不能把这次半截内容写入丢掉，且旧实现不洗图会因同一条
 *    `require` 整轮失败。
 */
class DraftPersistenceWiringSourceGuardTest {

    @Test
    fun `partial failure draft converts inline images before persisting`() {
        val block = indentedBlock(
            chatServiceSource(),
            "if (it is GenerationPartialFailureException) {",
        )

        val convertAt = block.indexOf("convertBase64ImagePartToLocalFile(")
        val saveAt = block.indexOf("saveConversation(")
        assertTrue(
            "半截草稿必须把 data: 图片转成本地文件（否则 require(!hasBase64Part) 拒绝整轮写入）",
            convertAt >= 0,
        )
        assertTrue("半截草稿必须落库", saveAt >= 0)
        assertTrue("图片转换必须在 saveConversation 之前执行", convertAt < saveAt)
        assertTrue(
            "半截草稿必须收尾 reasoning（否则重进会话思考卡片会按「进行中」永动计时）",
            block.contains("finishReasoning()"),
        )
    }

    @Test
    fun `stopped generation sanitizes images and persists inside NonCancellable`() {
        val body = indentedBlock(chatServiceSource(), "private suspend fun persistStoppedGeneration(")

        val nonCancellableAt = body.indexOf("withContext(NonCancellable)")
        val convertAt = body.indexOf("convertBase64ImagePartToLocalFile(")
        val saveAt = body.indexOf("saveConversation(")

        assertTrue(
            "停止落库必须在 withContext(NonCancellable) 内执行（取消在飞时不能丢半截内容）",
            nonCancellableAt >= 0,
        )
        assertTrue(
            "停止落库必须清洗 data: 图片（否则 require(!hasBase64Part) 拒绝整轮写入）",
            convertAt >= 0,
        )
        assertTrue("停止落库必须写库", saveAt >= 0)
        // 注意：persist 里清洗是 saveConversation 的**实参表达式**（源码顺序 save 在前），
        // 所以只钉「两者都在 NonCancellable 之内」，不钉 convert 与 save 的文本先后。
        assertTrue("图片清洗必须在 NonCancellable 内执行", nonCancellableAt < convertAt)
        assertTrue("写库必须在 NonCancellable 内执行", nonCancellableAt < saveAt)
        assertTrue(
            "停止落库必须收尾 reasoning（否则重进会话思考卡片会按「进行中」永动计时）",
            body.contains("finishReasoning()"),
        )
    }
}

// ---- 源码读取 / 代码块截取 ----

/**
 * 读取 ChatService.kt（Gradle 单测工作目录可能是模块根，也可能是仓库根——两种都试）。
 */
private fun chatServiceSource(): String {
    val file = listOf(
        "src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
    ).map(::File).firstOrNull { it.exists() }
        ?: error("找不到 ChatService.kt（工作目录=${File(".").absolutePath}）")
    return file.readText().replace("\r\n", "\n")
}

/**
 * 取 `marker` 所在的代码块：从 marker 行起第一个**同缩进**、以 `{` 结尾的行开始，
 * 到之后第一个**同缩进**、只含 `}` 的行结束（ChatService.kt 的 4 空格缩进格式稳定）。
 *
 * 用缩进而不是手写词法分析：源码里有大量字符串模板（`"${...}"`）与注释，
 * 朴素花括号计数会被字符串里的花括号干扰；同缩进闭合行在本文件里没有歧义。
 */
private fun indentedBlock(source: String, marker: String): String {
    val lines = source.lines()
    val markerIndex = lines.indexOfFirst { it.contains(marker) }
    check(markerIndex >= 0) { "ChatService.kt 里找不到 $marker" }

    val indent = lines[markerIndex].takeWhile { it == ' ' }
    val openIndex = (markerIndex until lines.size).firstOrNull { index ->
        val line = lines[index]
        line.trimEnd().endsWith("{") &&
            line.length > indent.length &&
            line.takeWhile { it == ' ' }.length == indent.length &&
            line.trimStart().isNotEmpty()
    } ?: error("$marker 之后找不到同缩进的 `{` 行")

    val closeIndex = (openIndex + 1 until lines.size).firstOrNull { index ->
        lines[index].trimEnd() == "$indent}"
    } ?: error("$marker 的代码块未闭合（找不到同缩进的 `}` 行）")

    return lines.subList(openIndex, closeIndex + 1).joinToString("\n")
}
