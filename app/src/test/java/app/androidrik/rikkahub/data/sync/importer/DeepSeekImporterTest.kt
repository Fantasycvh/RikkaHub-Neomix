package app.androidrik.rikkahub.data.sync.importer

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.pathNodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.uuid.Uuid

/**
 * DeepSeek 官方导出包导入（`docs/design/drafts/design-deepseek-export-import.md` §十一 测试计划）：
 *
 * 1. 版本门禁（v2 / v4 / 缺失 manifest → 拒绝）
 * 2. 线性会话（REQUEST/RESPONSE/THINK 映射、parent 链、activeLeafNodeId）
 * 3. 分支会话（current_message_id 指向旁支）
 * 4. 幂等（同一 zip 两次导入产出相同确定性 id）
 * 5. 容错（坏 fragment 丢片段、坏会话不中止、孤儿 parent 按创建序回填）
 * 6. status（INCOMPLETE → finishIncomplete；FAILED → 丢弃 + 计数）
 * 7. 图片（export_path 有/无条目两态；非图片 FILE 计数无 part）
 * 8. 时间（小数秒 0/3/6 位精度）
 */
class DeepSeekImporterTest {

    private val assistantId = Uuid.random()
    private val sid = "aaaaaaaa-1111-2222-3333-444444444444"
    private val sid2 = "bbbbbbbb-1111-2222-3333-444444444444"

    // ---- fixture 构造 ----

    private fun buildZip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("deepseek-test-", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun manifest(version: Int = 3) =
        """{"export_format_version":$version}""".toByteArray()

    private fun session(
        id: String,
        currentMessageId: Int?,
        vararg messages: String,
        insertedAt: String = "2026-01-01T00:00:00Z",
        updatedAt: String = "2026-01-01T00:00:01Z",
    ) = """
    {
      "id": "$id",
      "title": "Test $id",
      "pinned": false,
      "current_message_id": ${currentMessageId?.toString() ?: "null"},
      "inserted_at": "$insertedAt",
      "updated_at": "$updatedAt",
      "messages": [${messages.joinToString(",")}]
    }
    """.trimIndent().toByteArray()

    private fun msg(
        id: Int,
        parentId: Int?,
        role: String,
        status: String = "FINISHED",
        vararg fragments: String,
        insertedAt: String = "2026-01-01T00:00:00Z",
        tokenUsage: Long = 0,
    ) = """
    {
      "id": $id,
      "parent_id": ${parentId?.toString() ?: "null"},
      "role": "$role",
      "status": "$status",
      "thinking_enabled": false,
      "search_enabled": false,
      "accumulated_token_usage": $tokenUsage,
      "inserted_at": "$insertedAt",
      "fragments": [${fragments.joinToString(",")}]
    }
    """.trimIndent()

    private fun request(content: String) = """{"type":"REQUEST","content":"$content"}"""
    private fun response(content: String) = """{"type":"RESPONSE","content":"$content"}"""
    private fun think(content: String, secs: Double? = 1.5) =
        """{"type":"THINK","content":"$content","elapsed_secs":${secs?.toString() ?: "null"}}"""

    /** 假物化器：记录调用并返回确定性 URL。 */
    private class FakeMaterializer(
        private val succeed: Boolean = true,
    ) : DeepSeekImporter.ImageMaterializer {
        val calls = mutableListOf<Pair<String, String>>() // displayName to mimeType

        override suspend fun materialize(bytes: ByteArray, displayName: String, mimeType: String): String? {
            calls.add(displayName to mimeType)
            return if (succeed) "file://fake/$displayName" else null
        }
    }

    private data class ImportOutcome(
        val conversations: List<Conversation>,
        val result: DeepSeekImportResult,
        val materializer: FakeMaterializer,
    )

    private fun import(
        zip: File,
        keepSearchTrails: Boolean = false,
        materializer: FakeMaterializer = FakeMaterializer(),
        shouldImportSession: suspend (String) -> Boolean = { true },
    ): ImportOutcome = runBlocking {
        val conversations = mutableListOf<Conversation>()
        val result = DeepSeekImporter.importStreaming(
            file = zip,
            assistantId = assistantId,
            keepSearchTrails = keepSearchTrails,
            imageMaterializer = materializer,
            onConversation = { conversations += it },
            onProgress = { _, _ -> },
            shouldImportSession = shouldImportSession,
        )
        ImportOutcome(conversations, result, materializer)
    }

    // ---- 1. 版本门禁 ----

    @Test
    fun `version newer than 3 is rejected`() {
        val zip = buildZip("manifest.json" to manifest(4), "chat_sessions/$sid.json" to session(sid, null))
        try {
            import(zip)
            fail("expected VersionException")
        } catch (e: DeepSeekFormat.VersionException) {
            assertTrue(e.newer)
        }
    }

    @Test
    fun `version 2 is rejected as unrecognized`() {
        val zip = buildZip("manifest.json" to manifest(2), "chat_sessions/$sid.json" to session(sid, null))
        try {
            import(zip)
            fail("expected VersionException")
        } catch (e: DeepSeekFormat.VersionException) {
            assertFalse(e.newer)
        }
    }

    @Test
    fun `missing manifest first entry is rejected`() {
        val zip = buildZip(
            "user.json" to "{}".toByteArray(),
            "manifest.json" to manifest(),
        )
        try {
            import(zip)
            fail("expected ContainerException")
        } catch (e: DeepSeekFormat.ContainerException) {
            assertTrue(e.message!!.contains("manifest"))
        }
    }

    // ---- 2. 线性会话 ----

    @Test
    fun `linear session maps request response think and links parents`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 4,
                msg(1, null, "USER", fragments = arrayOf(request("你好"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(think("想一下", 2.0), response("你好呀"))),
                msg(3, 2, "USER", fragments = arrayOf(request("继续"))),
                msg(4, 3, "ASSISTANT", fragments = arrayOf(response("好的"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        assertEquals(1, conversations.size)
        assertEquals(1, result.parsedConversations)

        val conversation = conversations[0]
        assertEquals(4, conversation.messageNodes.size)

        val (n1, n2, n3, n4) = conversation.messageNodes
        // parent 链：1(root) → 2 → 3 → 4
        assertNull(n1.parentId)
        assertEquals(n1.id, n2.parentId)
        assertEquals(n2.id, n3.parentId)
        assertEquals(n3.id, n4.parentId)
        // 激活路径叶 = current_message_id
        assertEquals(n4.id, conversation.activeLeafNodeId)
        // 路径 = 全量线性
        assertEquals(listOf(n1.id, n2.id, n3.id, n4.id), conversation.pathNodes().map { it.id })

        // THINK → Reasoning（elapsed_secs 进 metadata），RESPONSE → Text
        val node2Parts = n2.messages.single().parts
        assertEquals(2, node2Parts.size)
        val reasoning = node2Parts[0] as UIMessagePart.Reasoning
        assertEquals("想一下", reasoning.reasoning)
        assertNotNull(reasoning.metadata)
        assertEquals("2.0", reasoning.metadata!!["deepseekElapsedSecs"].toString())
        assertTrue(node2Parts[1] is UIMessagePart.Text)

        // lastMessageAt = 叶节点消息 inserted_at
        assertNotNull(conversation.lastMessageAt)
    }

    // ---- 3. 分支会话 ----

    @Test
    fun `branch session activates the branch pointed by current_message_id`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 4,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("主支答"))),
                msg(3, 1, "USER", fragments = arrayOf(request("旁支问"))),
                msg(4, 3, "ASSISTANT", fragments = arrayOf(response("旁支答"))),
            ),
        )
        val conversation = import(zip).conversations.single()
        val (n1, n2, n3, n4) = conversation.messageNodes

        // 旁支与主支共享父节点（兄弟节点）
        assertEquals(n1.id, n2.parentId)
        assertEquals(n1.id, n3.parentId)
        // 激活路径走旁支 1 → 3 → 4，主支节点保留为兄弟
        assertEquals(n4.id, conversation.activeLeafNodeId)
        assertEquals(listOf(n1.id, n3.id, n4.id), conversation.pathNodes().map { it.id })
    }

    // ---- 4. 幂等 ----

    @Test
    fun `same zip imports produce identical deterministic ids`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答"))),
            ),
        )
        val first = import(zip).conversations.single()
        val second = import(zip).conversations.single()

        assertEquals(first.id, second.id)
        assertEquals(first.messageNodes.map { it.id }, second.messageNodes.map { it.id })
        assertEquals(
            first.messageNodes.flatMap { it.messages.map { m -> m.id } },
            second.messageNodes.flatMap { it.messages.map { m -> m.id } },
        )
        assertEquals(first.activeLeafNodeId, second.activeLeafNodeId)
    }

    /**
     * 回归（退出再导入资源重复）：前置判重命中的会话必须在**物化任何图片之前**被跳过——
     * 旧实现在 onConversation（构建完、图已真拷贝落盘）才判重，重复导入同一包
     * 会跳过会话却把全部图片重新落盘（upload/ 文件 + managed_files 行双重复制）。
     */
    @Test
    fun `gate rejected session skips before materializing images`() {
        val imagePath = "chat_sessions/${sid}_files/f1_pic.png"
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("看图"), fileFragment(imagePath, true))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("好的"))),
            ),
            imagePath to fakePng,
        )
        val (conversations, result, materializer) = import(zip, shouldImportSession = { false })

        assertTrue(conversations.isEmpty())
        assertTrue("命中判重的会话不得物化任何图片", materializer.calls.isEmpty())
        assertEquals(1, result.skippedExistingSessions)
        assertEquals(0, result.parsedConversations)
    }

    /** gate 只拦截命中的会话，同包其他会话正常导入。 */
    @Test
    fun `gate only blocks matched sessions`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid2.json" to session(
                sid2, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问2"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答2"))),
            ),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答"))),
            ),
        )
        val (conversations, result, _) =
            import(zip, shouldImportSession = { it != sid })

        assertEquals(1, conversations.size)
        assertEquals(
            DeepSeekImporter.stableUuid("deepseek-session:$sid2"),
            conversations.single().id,
        )
        assertEquals(1, result.skippedExistingSessions)
        assertEquals(1, result.parsedConversations)
    }

    // ---- 5. 容错 ----

    @Test
    fun `broken fragment drops only that fragment`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(
                    1, null, "USER", fragments = arrayOf(
                        request("问"),
                        // TOOL_SEARCH 缺 queries → 解析阶段整 fragment 丢弃（不计入 droppedSearchTrails），REQUEST 保留
                        """{"type":"TOOL_SEARCH","results":[{"url":"https://a.com","title":"t","snippet":"s"}]}""",
                    )
                ),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        assertEquals(1, conversations.size)
        assertEquals(2, conversations[0].messageNodes.size)
        // 坏 fragment 被丢 → 默认不保留搜索轨迹（不计 droppedSearchTrails，因为它连类型都没解析成功）
        assertEquals(0, result.droppedSearchTrails)
        val userParts = conversations[0].messageNodes[0].messages.single().parts
        assertEquals(1, userParts.size)
        assertEquals("问", (userParts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `broken session does not abort the archive`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            // messages 非法（非数组）→ 该会话整体失败
            "chat_sessions/$sid2.json" to """{"id":"$sid2","title":null,"pinned":false,"current_message_id":1,"inserted_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:01Z","messages":"bad"}""".toByteArray(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        assertEquals(1, conversations.size)
        assertEquals(1, result.parsedConversations)
        assertEquals(1, result.failedSessions)
    }

    @Test
    fun `orphan parent heals to creation-order predecessor`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 3,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答"))),
                // parent_id=999 不存在 → 孤儿，按创建序回填前驱（msg 2 的节点）
                msg(3, 999, "USER", fragments = arrayOf(request("孤儿"))),
            ),
        )
        val conversation = import(zip).conversations.single()
        val (n1, n2, n3) = conversation.messageNodes
        assertEquals(n2.id, n3.parentId)
        assertEquals(n3.id, conversation.activeLeafNodeId)
        assertEquals(listOf(n1.id, n2.id, n3.id), conversation.pathNodes().map { it.id })
    }

    @Test
    fun `orphan after dropped failed reply heals across the gap`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 3,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", status = "FAILED", fragments = arrayOf(response("失败的答"))),
                // parent 指向被丢弃的 FAILED 节点 → 回填到创建序前驱（msg 1）
                msg(3, 2, "USER", fragments = arrayOf(request("再问"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        val conversation = conversations.single()
        assertEquals(2, conversation.messageNodes.size)
        assertEquals(1, result.droppedFailedReplies)
        val (n1, n3) = conversation.messageNodes
        assertNull(n1.parentId)
        assertEquals(n1.id, n3.parentId)
    }

    // ---- 6. status ----

    @Test
    fun `incomplete reply keeps body with finishIncomplete flag`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", status = "INCOMPLETE", fragments = arrayOf(response("半截答"))),
            ),
        )
        val conversation = import(zip).conversations.single()
        val reply = conversation.messageNodes[1].messages.single()
        assertTrue(reply.finishIncomplete)
        assertNull(reply.finishReason)
        assertEquals("半截答", (reply.parts[0] as UIMessagePart.Text).text)
    }

    @Test
    fun `failed reply is dropped and parent question becomes leaf`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2, // current_message_id 指向被丢弃的节点 → 兜底取最大 id 保留节点
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(2, 1, "ASSISTANT", status = "FAILED", fragments = arrayOf(response("答"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        val conversation = conversations.single()
        assertEquals(1, result.droppedFailedReplies)
        assertEquals(1, conversation.messageNodes.size)
        // 父提问保留为激活路径的合法叶尾（设计稿 §6.4）
        assertEquals(conversation.messageNodes[0].id, conversation.activeLeafNodeId)
    }

    // ---- 7. 图片 ----

    private val fakePng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4,
    )

    private fun fileFragment(exportPath: String?, isImage: Boolean) = """
    {
      "type": "FILE",
      "files": [
        {
          "id": "f1",
          "file_name": "pic.png",
          "file_size": 12,
          "token_usage": null,
          "inserted_at": "2026-01-01T00:00:00Z",
          "export_path": ${exportPath?.let { "\"$it\"" } ?: "null"},
          "is_image": $isImage,
          "width": null,
          "height": null
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `image with matching entry is materialized as Image part`() {
        val imagePath = "chat_sessions/${sid}_files/f1_pic.png"
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 1,
                msg(1, null, "USER", fragments = arrayOf(request("看图"), fileFragment(imagePath, true))),
            ),
            imagePath to fakePng,
        )
        val (conversations, result, materializer) = import(zip)
        assertEquals(1, result.restoredImages)
        assertEquals(0, result.missingImages)
        assertEquals(listOf("pic.png" to "image/png"), materializer.calls)

        val parts = conversations[0].messageNodes[0].messages.single().parts
        val image = parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals("file://fake/pic.png", image.url)
    }

    @Test
    fun `image with missing entry is counted without part`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 1,
                msg(1, null, "USER", fragments = arrayOf(request("看图"), fileFragment("chat_sessions/${sid}_files/f1_pic.png", true))),
            ),
            // 无图片条目
        )
        val (conversations, result, materializer) = import(zip)
        assertEquals(0, result.restoredImages)
        assertEquals(1, result.missingImages)
        assertTrue(materializer.calls.isEmpty())
        val parts = conversations[0].messageNodes[0].messages.single().parts
        assertTrue(parts.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertEquals(1, parts.size) // 只剩 REQUEST 的 Text
    }

    @Test
    fun `non image file counts without part`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 1,
                msg(1, null, "USER", fragments = arrayOf(request("附件"), fileFragment(null, false))),
            ),
        )
        val (conversations, result, materializer) = import(zip)
        assertEquals(1, result.nonImageAttachments)
        assertTrue(materializer.calls.isEmpty())
        assertEquals(1, conversations[0].messageNodes[0].messages.single().parts.size)
    }

    // ---- 8. 时间精度 ----

    @Test
    fun `timestamps with 0 3 6 fractional digits all parse`() {
        listOf(
            "2026-01-01T00:00:00Z",
            "2026-01-01T00:00:00.123Z",
            "2026-01-01T00:00:00.123456Z",
        ).forEach { time ->
            val parsed = DeepSeekFormat.parseTime(time)
            assertNotNull("$time should parse", parsed)
        }
        assertNull(DeepSeekFormat.parseTime("2026-01-01 00:00:00")) // 非 RFC3339 Z → 拒绝
    }

    @Test
    fun `session with six digit fractional seconds imports`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 1,
                msg(1, null, "USER", fragments = arrayOf(request("问")), insertedAt = "2026-01-01T00:00:00.123456Z"),
                insertedAt = "2026-01-01T00:00:00.654321Z",
            ),
        )
        val conversation = import(zip).conversations.single()
        assertEquals("2026-01-01T00:00:00.654321", conversation.createAt.toString().removeSuffix("Z"))
    }

    // ---- 搜索轨迹开关（§7.1） ----

    @Test
    fun `search trails dropped by default and kept as text when enabled`() {
        val searchFragment = """
        {
          "type": "TOOL_SEARCH",
          "queries": ["kimi k2"],
          "results": [{"url":"https://example.com","title":"Example","snippet":"s","published_at":null,"site_name":null}]
        }
        """.trimIndent()
        val readLinkFragment = """
        {
          "type": "READ_LINK",
          "results": [{"signature":"SECRET-SIGNATURE","url":"https://link.example","title":"Link"}]
        }
        """.trimIndent()

        val makeZip = {
            buildZip(
                "manifest.json" to manifest(),
                "chat_sessions/$sid.json" to session(
                    sid, 2,
                    msg(1, null, "USER", fragments = arrayOf(request("搜一下"))),
                    msg(2, 1, "ASSISTANT", fragments = arrayOf(searchFragment, readLinkFragment, response("答"))),
                ),
            )
        }

        // 默认丢弃 + 计数（两个块）；READ_LINK 正文不可还原独立计数
        val dropped = import(makeZip())
        assertEquals(2, dropped.result.droppedSearchTrails)
        assertEquals(1, dropped.result.droppedReadLinks)
        val defaultParts = dropped.conversations[0].messageNodes[1].messages.single().parts
        assertEquals(1, defaultParts.size)

        // 开启保留 → Text part，signature 永不落库；但正文仍算「不可还原」
        val kept = import(makeZip(), keepSearchTrails = true)
        assertEquals(0, kept.result.droppedSearchTrails)
        assertEquals(1, kept.result.droppedReadLinks)
        val keptParts = kept.conversations[0].messageNodes[1].messages.single().parts
        assertEquals(3, keptParts.size)
        val trail = (keptParts[0] as UIMessagePart.Text).text
        assertTrue(trail.contains("> 🔍 kimi k2"))
        assertTrue(trail.contains("Example"))
        val linkTrail = (keptParts[1] as UIMessagePart.Text).text
        assertTrue(linkTrail.contains("https://link.example"))
        assertFalse(linkTrail.contains("SECRET-SIGNATURE"))
    }

    // ---- 历史 token 用量（§7.4：口径不符 → 丢弃 + 计数） ----

    @Test
    fun `accumulated token usage is dropped and counted only for kept messages`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 4,
                msg(1, null, "USER", fragments = arrayOf(request("问")), tokenUsage = 12),
                msg(2, 1, "ASSISTANT", fragments = arrayOf(response("答")), tokenUsage = 34),
                // 失败轮整条丢弃 → 它的 token 用量不再重复计入
                msg(3, 2, "ASSISTANT", status = "FAILED", fragments = arrayOf(response("失败")), tokenUsage = 56),
                msg(4, 1, "USER", fragments = arrayOf(request("再问"))),
            ),
        )
        val (conversations, result, _) = import(zip)
        assertEquals(2, result.droppedTokenUsageMessages)
        // 历史用量不落到 UIMessage.usage（统计页不显示导入会话的历史用量）
        val usages = conversations.single().messageNodes.flatMap { node ->
            node.messages.map { it.usage }
        }
        assertTrue(usages.all { it == null })
    }

    // ---- 模板回复 / 提示条丢弃（§6.3） ----

    @Test
    fun `template response and tips are dropped with counters`() {
        val zip = buildZip(
            "manifest.json" to manifest(),
            "chat_sessions/$sid.json" to session(
                sid, 2,
                msg(1, null, "USER", fragments = arrayOf(request("问"))),
                msg(
                    2, 1, "ASSISTANT", fragments = arrayOf(
                        """{"type":"TEMPLATE_RESPONSE","content":"服务器繁忙"}""",
                        """{"type":"TIP","content":"tip","style":"INFO"}""",
                        """{"type":"CONTENT_RISK","content":"risk","style":"WARNING"}""",
                        """{"type":"PARTIAL_FILE_READ","ratio":50}""",
                        """{"type":"TOOL_IMAGE_SEARCH","status":"FINISHED","results":[]}""",
                        response("答"),
                    )
                ),
            ),
        )
        val (conversations, result, _) = import(zip)
        assertEquals(1, result.droppedTemplateResponses)
        assertEquals(4, result.droppedServiceTips)
        val parts = conversations[0].messageNodes[1].messages.single().parts
        assertEquals(1, parts.size)
        assertEquals("答", (parts[0] as UIMessagePart.Text).text)
    }
}
