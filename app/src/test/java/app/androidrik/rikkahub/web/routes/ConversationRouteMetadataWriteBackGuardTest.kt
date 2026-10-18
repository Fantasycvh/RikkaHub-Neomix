package app.androidrik.rikkahub.web.routes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Web 会话路由的**整对象写回**源码守卫（与 `TitleWriteBackSourceGuardTest` 同一套做法）。
 *
 * 被钉住的缺陷：`ConversationRoutes.kt` 的置顶 / 改标题 / 改注入三处曾是
 * 「`getConversationById` 读 DB 整对象 → `conversation.copy(改一个字段)` → `saveConversation`」。
 * 而 `saveConversation` → `ChatService.updateConversation` 会用这份快照**整份替换内存态**：
 * 若该会话**正在生成**，已收到的流式正文当场被旧快照覆盖（与 App 侧刚修掉的标题/建议同族）。
 *
 * 为什么必须是源码扫描：行为断言需要一个能起 Ktor 路由 + ChatService + Room 的环境，
 * JVM 单测起不来；而「原语本身是否正确」由 `ConversationMetadataPrimitivesTest` /
 * `ConversationInjectionPrimitiveTest` 覆盖，钉不住**调用点**。所以这里把调用点的形态钉死：
 *
 * 1. 三处入口各自调用**单列写原语**（改回 `saveConversation` 即红）；
 * 2. 三处入口的函数体里**不出现** `getConversationById(` / `saveConversation(` /
 *    `conversationRepo.updateConversation(`；
 * 3. 整个文件里**没有任何**整对象写回；
 * 4. 剩下的整份 DB 读取只允许出现在「确实需要整份对象」的那几条路由里
 *    （GET 单条 / DELETE 需要对象 / 重新起名要正文 / move 与 folder 的归属校验），
 *    新增一处就会被 [全扫] 那条挡住。
 */
class ConversationRouteMetadataWriteBackGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun routesSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/web/routes/ConversationRoutes.kt",
        "app/src/main/java/app/androidrik/rikkahub/web/routes/ConversationRoutes.kt",
    ).readText().replace("\r\n", "\n")

    private fun chatServiceSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
        "app/src/main/java/app/androidrik/rikkahub/service/ChatService.kt",
    ).readText().replace("\r\n", "\n")

    /**
     * 截出某条路由的 handler 源码：从路由声明到**下一条路由的注释**之间
     * （本文件每条路由前都有一行 8 空格缩进的 `// …` 注释 + 空行）。
     */
    private fun routeBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "ConversationRoutes.kt 里找不到路由 $marker（守卫已失效，需同步测试）" }
        val next = source.indexOf("\n\n        // ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    /** 截出 `suspend fun <name>(` 到下一个同级 `suspend fun` 之间的源码。 */
    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("suspend fun $name(")
        check(start >= 0) { "ChatService.kt 里找不到 suspend fun $name" }
        val next = source.indexOf("\n    suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, next)
    }

    /** 三处元数据入口的共同断言：走单列原语、不读 DB 整对象当基线、不整对象写回。 */
    private fun assertMetadataWriteBack(block: String, primitive: String, what: String) {
        assertTrue("$what 必须走单列写原语 $primitive：$block", primitive in block)
        assertFalse(
            "$what 不得读 DB 整对象当基线（生成中会把在途正文换成旧快照）：$block",
            "getConversationById(" in block,
        )
        assertFalse("$what 不得整对象写回（saveConversation）：$block", "saveConversation(" in block)
        assertFalse(
            "$what 不得整对象写回（conversationRepo.updateConversation）：$block",
            "conversationRepo.updateConversation(" in block,
        )
        assertFalse(
            "$what 不得先 copy 出一份会话再落库（那就是整对象写回的形态）：$block",
            "conversation.copy(" in block,
        )
    }

    @Test
    fun `pin route writes the pin column only`() {
        assertMetadataWriteBack(
            block = routeBlock(routesSource(), "post(\"/{id}/pin\")"),
            primitive = "chatService.togglePinStatus(",
            what = "置顶入口",
        )
    }

    @Test
    fun `title route writes the title column only`() {
        assertMetadataWriteBack(
            block = routeBlock(routesSource(), "post(\"/{id}/title\")"),
            primitive = "chatService.setConversationTitle(",
            what = "改标题入口",
        )
    }

    @Test
    fun `injections route writes the injection columns only`() {
        assertMetadataWriteBack(
            block = routeBlock(routesSource(), "post(\"/{id}/injections\")"),
            primitive = "chatService.setConversationInjections(",
            what = "改注入入口",
        )
    }

    @Test
    fun `no conversation route writes a whole conversation object back`() {
        val source = routesSource()

        assertFalse(
            "ConversationRoutes.kt 里不得再有整对象写回（saveConversation）：" +
                "生成中的会话会被旧快照整份覆盖",
            "saveConversation(" in source,
        )
        assertFalse(
            "ConversationRoutes.kt 里不得再有整对象写回（conversationRepo.updateConversation）",
            "conversationRepo.updateConversation(" in source,
        )
    }

    @Test
    fun `full conversation reads stay in the routes that genuinely need the object`() {
        val source = routesSource()
        // 已知的「整份读」路由，各有明确理由（都**不**写回整对象）：
        // - GET 单条：响应体就是整份会话；
        // - DELETE：删除需要对象（附件清理要读 files）；
        // - regenerate-title：起名要正文；
        // - move / folder：目标归属校验（文件夹要与会话的 assistantId 比对）。
        val allowedReadRoutes = listOf(
            "get(\"/{id}\")",
            "delete(\"/{id}\")",
            "post(\"/{id}/regenerate-title\")",
            "post(\"/{id}/move\")",
            "post(\"/{id}/folder\")",
        )

        allowedReadRoutes.forEach { marker ->
            assertTrue(
                "$marker 应仍然读整份会话（读侧，允许）：" +
                    routeBlock(source, marker),
                "getConversationById(" in routeBlock(source, marker),
            )
        }
        val occurrences = source.split("getConversationById(").size - 1
        assertEquals(
            "ConversationRoutes.kt 里 getConversationById( 只能出现在已知的整份读路由里" +
                "（新增一处 = 可能又变成「读整对象 → 改字段 → 写回」）",
            allowedReadRoutes.size,
            occurrences,
        )
    }

    /**
     * 路由改用的三个 ChatService 入口必须各自落**单列**（route → ChatService → 仓库单列原语
     * 这条链的最后一环）。缺了这层，「路由调了 setXxx」也可能内部整对象写回。
     */
    @Test
    fun `metadata entry points persist through single-column repository writes`() {
        val source = chatServiceSource()
        val expectedPrimitives = mapOf(
            "togglePinStatus" to "conversationRepo.updateConversationPinStatus(",
            "setConversationTitle" to "conversationRepo.updateTitle(",
            "setConversationInjections" to "conversationRepo.updateConversationInjections(",
        )

        expectedPrimitives.forEach { (name, primitive) ->
            val body = functionBody(source, name)
            assertTrue("$name 必须落单列原语 $primitive：$body", primitive in body)
            assertFalse("$name 不得整对象写回（saveConversation）：$body", "saveConversation(" in body)
            assertTrue(
                "$name 必须走统一元数据入口（基线 = 当前内存值，不接外部快照）：$body",
                "updateConversationMetadata(" in body,
            )
            assertFalse(
                "$name 不得把「读 DB 整对象」当基线（有活跃 session 时内存态才是最新）：$body",
                "getConversationById(" in body,
            )
        }
    }
}
