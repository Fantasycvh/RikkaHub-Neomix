package app.androidrik.rikkahub.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 的**源码级守卫**：自动标题 / 聊天建议的回写必须走「单列写原语」，不得整对象写回。
 *
 * 为什么必须是源码扫描：这两处的缺陷是「时序 + 数据来源」问题——网络请求（数秒）返回后
 * 重新读 DB 拿快照、改字段、`saveConversation` 整对象写回，把请求期间收到的流式正文盖掉。
 * 行为断言需要一个能跑协程的 ChatService（依赖 Application / Room / 一大堆仓库，JVM 单测
 * 起不来），而 `updateMetadataSafely` 的单测只覆盖「原语本身正确」，钉不住**调用点**。
 * 所以用源码扫描把调用点的形态钉死（本仓既有同类做法，见 `ProviderTypeLabelSourceGuardTest`、
 * `EditOrderMoveGuardsTest`）。
 *
 * 钉的两条：
 * 1. 两个入口都出现 `conversationRepo.updateTitle(` / `conversationRepo.updateChatSuggestions(`
 *    ——单列写原语（改回 `saveConversation` 即红）；
 * 2. 两个函数体里**不出现** `getConversationById(` / `saveConversation(` ——不读 DB 快照当基线、
 *    不整对象写回。
 */
class TitleWriteBackSourceGuardTest {

    private fun chatServiceSource(): String {
        val file = listOf(
            "src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
            "app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
        ).map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ChatService.kt（工作目录=${File(".").absolutePath}）")
        return file.readText().replace("\r\n", "\n")
    }

    /** 截出 `suspend fun <name>(` 到下一个同级 `suspend fun` / 文件尾之间的源码。 */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("suspend fun $name(")
        check(start >= 0) { "ChatService.kt 里找不到 suspend fun $name" }
        val next = source.indexOf("\n    suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    @Test
    fun `generateTitle writes the title column only`() {
        val body = functionBody(chatServiceSource(), "generateTitle")

        assertTrue(
            "自动标题必须走单列写原语 conversationRepo.updateTitle(：$body",
            "conversationRepo.updateTitle(" in body,
        )
        assertFalse(
            "自动标题不得重新读 DB 整对象当基线（会把生成中的在途正文盖掉）：$body",
            "getConversationById(" in body,
        )
        assertFalse(
            "自动标题不得整对象写回（saveConversation）：$body",
            "saveConversation(" in body,
        )
    }

    @Test
    fun `generateSuggestion writes the suggestions column only`() {
        val body = functionBody(chatServiceSource(), "generateSuggestion")

        assertTrue(
            "聊天建议必须走单列写原语 conversationRepo.updateChatSuggestions(：$body",
            "conversationRepo.updateChatSuggestions(" in body,
        )
        assertFalse(
            "聊天建议不得重新读 DB 整对象当基线：$body",
            "getConversationById(" in body,
        )
        assertFalse(
            "聊天建议不得整对象写回（saveConversation）：$body",
            "saveConversation(" in body,
        )
    }

    @Test
    fun `both write-backs go through the shared metadata entry point`() {
        val source = chatServiceSource()
        listOf("generateTitle", "generateSuggestion").forEach { name ->
            val body = functionBody(source, name)
            assertTrue(
                "$name 必须走 updateConversationMetadata（基线 = 当前内存值）：$body",
                "updateConversationMetadata(" in body,
            )
        }
    }
}
