package app.androidrik.rikkahub.data.sync.importer

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.Uuid

/**
 * DeepSeek 官方导出包 → Conversation 流式导入（设计稿 design-deepseek-export-import.md）。
 *
 * **容器选择**：用 [ZipFile]（随机访问）而非 ZipInputStream 顺序流——官方布局里图片条目
 * 排在会话 JSON **之后**，单向流在会话落库前拿不到图片字节；ZipFile 先做「只读条目名的
 * 索引遍历」（不解压，即设计稿 §9.4 的第一遍），会话解析时**按需**解压被引用的图片（第二遍），
 * 内存纪律与设计稿 §9.1 相同：不整包进内存，单会话 JSON + 单图片字节即用即弃。
 *
 * **映射定案**（设计稿 §5/§6/§7，勿改）：
 * - 1 DeepSeek message = 1 MessageNode（1:1，不折叠进 alternatives）；
 * - 确定性 id：`UUID.nameUUIDFromBytes("deepseek-session:" + sid)` /
 *   `"deepseek-node:" + sid + ":" + mid` / `"deepseek-message:" + sid + ":" + mid`
 *   → 重复导入同一 zip 时 `existsConversationById` 天然幂等；
 * - `current_message_id` → activeLeafNodeId（指向的节点被丢弃时兜底取最大 id 保留节点）；
 * - status：FAILED/TIMEOUT/CONTEXT_LENGTH_EXCEEDED/CONTENT_FILTER 整条丢弃（父提问保留为叶尾），
 *   INCOMPLETE/WIP → finishIncomplete=true，FINISHED → finishReason=null；
 * - THINK → Reasoning（elapsed_secs 存 metadata "deepseekElapsedSecs"）；
 * - 模板回复/提示条/搜索轨迹默认丢弃并计数；「保留搜索轨迹」开启时序列化为 Text
 *   （signature 永不落库）；
 * - 孤儿 parent_id 按创建序回填前驱（对齐本仓 healMissingParents 策略）；
 * - 单 fragment 失败丢片段、单会话失败不中止整包。
 */
object DeepSeekImporter {
    const val MANIFEST_ENTRY = "manifest.json"
    private val SESSION_JSON_REGEX = Regex("""^chat_sessions/[^/]+\.json$""")
    private val IMAGE_ENTRY_REGEX = Regex("""^chat_sessions/[^/]+_files/.+$""")

    /** 图片物化回调：zip 字节 → 管理文件（登记 managed_files）→ `file://` URL。null = 物化失败（计缺失）。 */
    fun interface ImageMaterializer {
        suspend fun materialize(bytes: ByteArray, displayName: String, mimeType: String): String?
    }

    /**
     * 会话判重回调 [shouldImportSession]：返回 false = 该会话已导入过，直接跳过
     * （不构建、不物化图片）。
     *
     * **必须在物化之前调用**：图片物化每次都生成新文件真拷贝，若等构建完再在
     * `onConversation` 里判重，重复导入同一包会跳过会话却把全部图片重新落盘
     * （upload/ 文件 + managed_files 行双重复制）。对齐 ChatboxImporter 的
     * `shouldImportConversation` 前置判重纪律。
     */
    suspend fun importStreaming(
        file: File,
        assistantId: Uuid,
        keepSearchTrails: Boolean,
        imageMaterializer: ImageMaterializer,
        onConversation: suspend (Conversation) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        shouldImportSession: suspend (sessionId: String) -> Boolean = { true },
    ): DeepSeekImportResult {
        val stats = MutableStats()
        ZipFile(file, StandardCharsets.UTF_8).use { zip ->
            val entries = zip.entries().asSequence().toList()

            // ---- 版本门禁：manifest 必须是首个条目（对齐官方 checkArchiveFormat）----
            val first = entries.firstOrNull() ?: throw DeepSeekFormat.ContainerException("archive is empty")
            if (first.name != MANIFEST_ENTRY) {
                throw DeepSeekFormat.ContainerException("first entry is ${first.name}, expected $MANIFEST_ENTRY")
            }
            val manifestText = zip.getInputStream(first).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val manifestJson = runCatching { Json.parseToJsonElement(manifestText) }
                .getOrElse { throw DeepSeekFormat.ContainerException("manifest.json is not valid JSON") }
            DeepSeekFormat.checkManifest(manifestJson)

            // ---- 第一遍：只读条目名建图片索引（不解压字节）----
            val sessionEntries = entries.filter { SESSION_JSON_REGEX.matches(it.name) }
            val imageEntries = HashMap<String, ZipEntry>()
            entries.filter { IMAGE_ENTRY_REGEX.matches(it.name) }.forEach { entry ->
                // 条目名按 NFC 归一（对齐官方）；同名重复条目保留首个
                imageEntries.putIfAbsent(nfc(entry.name), entry)
            }

            // ---- 第二遍：逐会话解析 → 落库回调 ----
            val total = sessionEntries.size
            var done = 0
            val seenSessionIds = HashSet<String>()
            for (entry in sessionEntries) {
                done++
                val conversation = importSession(
                    zip = zip,
                    entry = entry,
                    assistantId = assistantId,
                    keepSearchTrails = keepSearchTrails,
                    imageEntries = imageEntries,
                    imageMaterializer = imageMaterializer,
                    seenSessionIds = seenSessionIds,
                    shouldImportSession = shouldImportSession,
                    stats = stats,
                )
                if (conversation != null) {
                    stats.parsedConversations++
                    onConversation(conversation)
                }
                onProgress(done, total)
            }
        }
        return stats.toResult()
    }

    /** 解析并构建单个会话；容器级失败返回 null（计数，不中止整包）。 */
    private suspend fun importSession(
        zip: ZipFile,
        entry: ZipEntry,
        assistantId: Uuid,
        keepSearchTrails: Boolean,
        imageEntries: Map<String, ZipEntry>,
        imageMaterializer: ImageMaterializer,
        seenSessionIds: MutableSet<String>,
        shouldImportSession: suspend (sessionId: String) -> Boolean,
        stats: MutableStats,
    ): Conversation? {
        try {
            val text = zip.getInputStream(entry).use { it.readBytes().toString(StandardCharsets.UTF_8) }
            val session = DeepSeekFormat.parseSession(Json.parseToJsonElement(text))
            // 会话 id 重复（同一包内两份同名会话）与容器非法同级别：只收首个
            if (session == null || !seenSessionIds.add(session.id)) {
                stats.failedSessions++
                return null
            }
            // 前置判重（调用方查库）：已导入过的会话在物化任何图片之前跳过
            if (!shouldImportSession(session.id)) {
                stats.skippedExistingSessions++
                return null
            }
            return buildConversation(
                session = session,
                assistantId = assistantId,
                keepSearchTrails = keepSearchTrails,
                zip = zip,
                imageEntries = imageEntries,
                imageMaterializer = imageMaterializer,
                stats = stats,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 单会话失败不中止整包（设计稿 §2.4）
            stats.failedSessions++
            return null
        }
    }

    private suspend fun buildConversation(
        session: DeepSeekSessionFile,
        assistantId: Uuid,
        keepSearchTrails: Boolean,
        zip: ZipFile,
        imageEntries: Map<String, ZipEntry>,
        imageMaterializer: ImageMaterializer,
        stats: MutableStats,
    ): Conversation? {
        val sid = session.id
        val nodes = ArrayList<MessageNode>(session.messages.size)
        val nodeByMsgId = HashMap<Long, MessageNode>()
        val seenMsgIds = HashSet<Long>()

        for (message in session.messages) {
            // status 门禁（设计稿 §6.4）：失败轮整条丢弃，父提问保留为激活路径的合法叶尾
            if (message.status in DROP_STATUSES) {
                stats.droppedFailedReplies++
                continue
            }
            // 防御：重复消息 id 会撞确定性节点 id，保留首个
            if (!seenMsgIds.add(message.id)) continue

            // 历史 token 用量口径不符 → 不导入，仅计数（设计稿 §7.4）
            if ((message.accumulatedTokenUsage ?: 0L) > 0L) stats.droppedTokenUsageMessages++

            val parts = mapFragments(
                fragments = message.fragments,
                insertedAt = message.insertedAt,
                keepSearchTrails = keepSearchTrails,
                zip = zip,
                imageEntries = imageEntries,
                imageMaterializer = imageMaterializer,
                stats = stats,
            )
            if (parts.isEmpty()) continue // 全部片段被丢弃 → 无可展示内容，节点不落库

            val uiMessage = UIMessage(
                id = stableUuid("deepseek-message:$sid:${message.id}"),
                role = when (message.role) {
                    DeepSeekRole.USER -> MessageRole.USER
                    DeepSeekRole.ASSISTANT -> MessageRole.ASSISTANT
                },
                parts = parts,
                createdAt = message.insertedAt.toLocalDateTime(),
                // FINISHED → finishReason=null；INCOMPLETE/WIP → 裸断流标记（设计稿 §6.4）
                finishIncomplete = message.status == DeepSeekStatus.INCOMPLETE || message.status == DeepSeekStatus.WIP,
            )
            val node = MessageNode(
                id = stableUuid("deepseek-node:$sid:${message.id}"),
                parentId = message.parentId?.let { stableUuid("deepseek-node:$sid:$it") },
                messages = listOf(uiMessage),
                selectIndex = 0,
            )
            nodes.add(node)
            nodeByMsgId[message.id] = node
        }

        // messages 非法/空 或全部被丢弃 → 会话级失败（设计稿 §7.3）
        if (nodes.isEmpty()) {
            stats.failedSessions++
            return null
        }

        // 树完整性防御（设计稿 §7.3）：孤儿 parent_id（父节点被丢弃/不存在）按创建序回填前驱。
        // 注意 parent_id=null 保持为根（设计稿 §5），只有「指向了不存在的父」才回填。
        val preservedIds = HashSet<Uuid>(nodes.size * 2)
        nodes.forEach { preservedIds.add(it.id) }
        var previous: MessageNode? = null
        val healed = nodes.map { node ->
            val parentId = when {
                node.parentId == null -> null
                node.parentId in preservedIds -> node.parentId
                else -> previous?.id
            }
            previous = node
            node.copy(parentId = parentId)
        }

        // 激活路径定位（设计稿 §7.2）：current_message_id → 节点；指向的节点被丢弃/缺失时
        // 兜底取「id 最大的**保留**节点」（不能取原始消息的最大 id——失败轮已被丢弃）。
        val leafMsgId = session.currentMessageId
            ?.takeIf { nodeByMsgId.containsKey(it) }
            ?: nodeByMsgId.keys.maxOrNull()
        val leafNode = leafMsgId?.let(nodeByMsgId::get)

        return Conversation(
            id = stableUuid("deepseek-session:$sid"),
            assistantId = assistantId,
            title = session.title ?: "", // 设计稿 §6.1：null → ""
            messageNodes = healed,
            activeLeafNodeId = leafNode?.id,
            isPinned = session.pinned,
            createAt = session.insertedAt,
            updateAt = session.updatedAt,
            // lastMessageAt = 激活路径末消息（= 叶节点）的 inserted_at（设计稿 §6.1）
            lastMessageAt = leafMsgId?.let { id -> session.messages.firstOrNull { it.id == id }?.insertedAt },
        )
    }

    /** fragments → parts（设计稿 §6.3 映射矩阵 + §7.1 搜索轨迹开关）。 */
    private suspend fun mapFragments(
        fragments: List<DeepSeekFragment>,
        insertedAt: java.time.Instant,
        keepSearchTrails: Boolean,
        zip: ZipFile,
        imageEntries: Map<String, ZipEntry>,
        imageMaterializer: ImageMaterializer,
        stats: MutableStats,
    ): List<UIMessagePart> = buildList {
        for (fragment in fragments) {
            when (fragment) {
                is DeepSeekFragment.Request -> addText(fragment.content)
                is DeepSeekFragment.Response -> addText(fragment.content)

                is DeepSeekFragment.Think -> if (fragment.content.isNotBlank()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = fragment.content,
                            // createdAt/finishedAt 用消息 inserted_at 填充（设计稿 §6.3）
                            createdAt = KotlinInstant.fromEpochMilliseconds(insertedAt.toEpochMilli()),
                            finishedAt = KotlinInstant.fromEpochMilliseconds(insertedAt.toEpochMilli()),
                            metadata = fragment.elapsedSecs?.let { secs ->
                                buildJsonObject { put("deepseekElapsedSecs", JsonPrimitive(secs)) }
                            },
                        )
                    )
                }

                is DeepSeekFragment.FileRef -> fragment.files.forEach { meta ->
                    when {
                        // 非图片附件：zip 只含元数据无本体 → 无 part，计数报告（设计稿 §6.3）
                        !meta.isImage -> stats.nonImageAttachments++

                        // 物化走调用方回调（FilesManager.saveManagedFromBytes，登记 managed_files）；
                        // 复用原路径会造成删副本连带删原件，故一律生成新文件名真拷贝（设计稿 §八）
                        else -> {
                            val bytes = meta.exportPath
                                ?.let { imageEntries[nfc(it)] }
                                ?.let { entry ->
                                    runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull()
                                }
                            val url = bytes?.let {
                                runCatching {
                                    imageMaterializer.materialize(it, meta.fileName, sniffImageMime(it))
                                }.getOrNull()
                            }
                            if (url != null) {
                                stats.restoredImages++
                                add(UIMessagePart.Image(url = url))
                            } else {
                                stats.missingImages++ // 条目缺失/读取/物化失败 → 跳过并计数
                            }
                        }
                    }
                }

                is DeepSeekFragment.TemplateResponse -> stats.droppedTemplateResponses++
                is DeepSeekFragment.Tip,
                is DeepSeekFragment.PartialFileRead,
                is DeepSeekFragment.ImageSearch,
                    -> stats.droppedServiceTips++ // 官方亦无展示对应（设计稿 §6.3）

                is DeepSeekFragment.ToolSearch -> {
                    if (keepSearchTrails) {
                        addSearchTrail(fragment.queries, fragment.results.map { it.title to it.url })
                    } else {
                        stats.droppedSearchTrails++
                    }
                }

                is DeepSeekFragment.ToolOpen -> {
                    if (keepSearchTrails) {
                        addSearchTrail(emptyList(), listOf((fragment.title ?: "") to fragment.url))
                    } else {
                        stats.droppedSearchTrails++
                    }
                }

                is DeepSeekFragment.ToolFind -> {
                    if (keepSearchTrails) {
                        addSearchTrail(listOfNotNull(fragment.pattern), emptyList())
                    } else {
                        stats.droppedSearchTrails++
                    }
                }

                is DeepSeekFragment.ReadLink -> {
                    // signature 是服务端私有签名，物理上无法跨端恢复正文 → 永不落库（设计稿 §7.4）。
                    // 无论是否开启「保留搜索轨迹」都记一次「正文不可还原」，完成摘要里要如实报出来。
                    stats.droppedReadLinks++
                    if (keepSearchTrails) {
                        addSearchTrail(emptyList(), fragment.results.map { (it.title ?: "") to it.url })
                    } else {
                        stats.droppedSearchTrails++
                    }
                }
            }
        }
    }

    private val DROP_STATUSES = setOf(
        DeepSeekStatus.FAILED,
        DeepSeekStatus.TIMEOUT,
        DeepSeekStatus.CONTEXT_LENGTH_EXCEEDED,
        DeepSeekStatus.CONTENT_FILTER,
    )

    /** 非空白才成 Text part（空白请求/回答不落库）。 */
    private fun MutableList<UIMessagePart>.addText(content: String) {
        if (content.isNotBlank()) add(UIMessagePart.Text(content))
    }

    /** 搜索轨迹序列化（设计稿 §7.1：`> 🔍 {queries} → {title} ({url})` 逐行）。 */
    private fun MutableList<UIMessagePart>.addSearchTrail(
        queries: List<String>,
        results: List<Pair<String, String?>>,
    ) {
        val head = queries.joinToString(", ")
        if (results.isEmpty()) {
            if (head.isNotBlank()) add(UIMessagePart.Text("> 🔍 $head"))
            return
        }
        val text = results.joinToString("\n") { (title, url) ->
            val target = listOfNotNull(
                title.takeIf { it.isNotBlank() },
                url?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { null } ?: "-"
            if (head.isBlank()) "> 🔍 → $target" else "> 🔍 $head → $target"
        }
        add(UIMessagePart.Text(text))
    }

    /** 图片 MIME 由字节识别补充；识别不出仍按图片导入（对齐官方 imageType）。 */
    internal fun sniffImageMime(bytes: ByteArray): String = when {
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        bytes.startsWithText("GIF87a") || bytes.startsWithText("GIF89a") -> "image/gif"
        bytes.startsWithText("RIFF") && bytes.startsWithText("WEBP", 8) -> "image/webp"
        bytes.startsWith(0x42, 0x4D) -> "image/bmp"
        else -> "application/octet-stream"
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) {
            if ((this[i].toInt() and 0xFF) != (prefix[i] and 0xFF)) return false
        }
        return true
    }

    private fun ByteArray.startsWithText(text: String, offset: Int = 0): Boolean {
        if (size < offset + text.length) return false
        val ascii = text.toByteArray(StandardCharsets.US_ASCII)
        for (i in ascii.indices) {
            if (this[offset + i] != ascii[i]) return false
        }
        return true
    }

    private fun java.time.Instant.toLocalDateTime() =
        KotlinInstant.fromEpochMilliseconds(toEpochMilli())
            .toLocalDateTime(TimeZone.currentSystemDefault())

    private fun nfc(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

    /** MD5-based v3 UUID（ChatboxImporter 既有约定）：同一输入恒得同一输出即可，不必引入 v5 依赖。 */
    internal fun stableUuid(value: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString())

    private class MutableStats {
        var parsedConversations = 0
        var failedSessions = 0
        var skippedExistingSessions = 0
        var droppedFailedReplies = 0
        var droppedSearchTrails = 0
        var droppedTemplateResponses = 0
        var droppedServiceTips = 0
        var droppedReadLinks = 0
        var droppedTokenUsageMessages = 0
        var nonImageAttachments = 0
        var restoredImages = 0
        var missingImages = 0

        fun toResult() = DeepSeekImportResult(
            parsedConversations = parsedConversations,
            failedSessions = failedSessions,
            skippedExistingSessions = skippedExistingSessions,
            droppedFailedReplies = droppedFailedReplies,
            droppedSearchTrails = droppedSearchTrails,
            droppedTemplateResponses = droppedTemplateResponses,
            droppedServiceTips = droppedServiceTips,
            droppedReadLinks = droppedReadLinks,
            droppedTokenUsageMessages = droppedTokenUsageMessages,
            nonImageAttachments = nonImageAttachments,
            restoredImages = restoredImages,
            missingImages = missingImages,
        )
    }
}

data class DeepSeekImportResult(
    /** 解析成功并回调给调用方落库的会话数（去重在调用方，最终导入数 ≤ 此值）。 */
    val parsedConversations: Int,
    val failedSessions: Int,
    /** 命中 [DeepSeekImporter.importStreaming] 的 shouldImportSession 判重而跳过的会话数。 */
    val skippedExistingSessions: Int,
    /** FAILED/TIMEOUT/CONTEXT_LENGTH_EXCEEDED/CONTENT_FILTER 轮（整条丢弃）。 */
    val droppedFailedReplies: Int,
    /** 搜索轨迹块（TOOL_SEARCH/TOOL_OPEN/TOOL_FIND/READ_LINK），默认丢弃。 */
    val droppedSearchTrails: Int,
    /** 服务端模板回复（官方语义不进上下文）。 */
    val droppedTemplateResponses: Int,
    /** TIP/CONTENT_RISK/PARTIAL_FILE_READ/TOOL_IMAGE_SEARCH（官方亦无展示对应）。 */
    val droppedServiceTips: Int,
    /** READ_LINK 块数：正文依赖服务端签名，物理上无法跨端还原（即使保留搜索轨迹也只留标题/链接）。 */
    val droppedReadLinks: Int,
    /** 携带 `accumulated_token_usage` 的消息数：口径不符，历史用量不导入。 */
    val droppedTokenUsageMessages: Int,
    /** 非图片附件（zip 只含元数据无本体）。 */
    val nonImageAttachments: Int,
    /** 成功物化的图片数。 */
    val restoredImages: Int,
    /** 原件缺失/物化失败的图片数。 */
    val missingImages: Int,
)
