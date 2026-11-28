package app.androidrik.rikkahub.data.draft

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftStoreTest {

    private fun tempStorageFile(): Pair<File, File> {
        val dir = Files.createTempDirectory("chat_draft_store_test").toFile()
        dir.deleteOnExit()
        return dir to File(dir, CHAT_DRAFTS_FILE_NAME)
    }

    @Test
    fun `保存与跨实例恢复完整草稿含附件引用技能与编辑态`() = runTest {
        val (_, storageFile) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val convoId = Uuid.random()
        val editingMsgId = Uuid.random()
        val quoteMsgId = Uuid.random()
        val imgOrig = UIMessagePart.Image("file:///data/user/0/app/files/upload/orig.png")
        val imgReplaced = UIMessagePart.Image("file:///data/user/0/app/files/upload/new.png")
        val doc = UIMessagePart.Document(
            url = "file:///data/user/0/app/files/upload/spec.md",
            fileName = "spec.md",
            mime = "text/markdown",
        )
        val draft = ChatDraft(
            text = "继续写这段代码",
            attachments = listOf(imgReplaced, doc),
            pendingQuote = UIMessageAnnotation.QuotedMessage(
                messageId = quoteMsgId,
                role = MessageRole.ASSISTANT,
                content = "上一段回答摘要",
            ),
            pendingSkills = listOf("kotlin-skill", "debug-skill"),
            editing = EditingDraftState(
                messageId = editingMsgId,
                originalParts = listOf(imgOrig, UIMessagePart.Text("原始段1"), UIMessagePart.Text("原始段2")),
                textIndex = 1,
                replacedAttachments = listOf(ReplacedAttachmentDraft(imgOrig, imgReplaced)),
                keepFollowing = false,
            ),
        )

        val store1 = ChatDraftStore(
            storageFile = storageFile,
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            debounceMillis = 300L,
        )
        advanceUntilIdle()
        store1.save(convoId, draft)
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertEquals(setOf(convoId), store1.draftConversationIds.value)

        // 新建实例模拟杀进程重建，确认可完整从磁盘恢复
        val store2 = ChatDraftStore(
            storageFile = storageFile,
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            debounceMillis = 300L,
        )
        advanceUntilIdle()
        val restored = store2.getImmediate(convoId)
        assertTrue(restored != null)
        assertTrue(restored!!.contentEquals(draft))
        assertEquals(setOf(convoId), store2.draftConversationIds.value)
        assertEquals(
            setOf(imgOrig.url, imgReplaced.url, doc.url),
            store2.allReferencedLocalFileUrls(),
        )
    }

    @Test
    fun `空草稿保存等价删除_仅编辑态非空仍保留`() = runTest {
        val (_, storageFile) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatDraftStore(storageFile, TestScope(dispatcher), ioDispatcher = dispatcher)
        advanceUntilIdle()

        val id1 = Uuid.random()
        store.save(id1, ChatDraft(text = "hello"), immediate = true)
        advanceUntilIdle()
        assertEquals(setOf(id1), store.draftConversationIds.value)

        // 空白字符视为清空草稿
        store.save(id1, ChatDraft(text = "   \n"))
        advanceUntilIdle()
        assertNull(store.getImmediate(id1))
        assertTrue(store.draftConversationIds.value.isEmpty())

        // 文本和附件都空，但处于历史消息编辑态（如把消息编辑成待输入状态）仍应保留
        val editingOnly = ChatDraft(
            text = "",
            editing = EditingDraftState(
                messageId = Uuid.random(),
                originalParts = listOf(UIMessagePart.Text("原文")),
            ),
        )
        assertFalse(editingOnly.isEmpty)
        store.save(id1, editingOnly, immediate = true)
        advanceUntilIdle()
        assertEquals(setOf(id1), store.draftConversationIds.value)
    }

    @Test
    fun `防抖合并连续写入且LRU超出上限淘汰最旧条目`() = runTest {
        val (_, storageFile) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var now = 1000L
        val store = ChatDraftStore(
            storageFile = storageFile,
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            debounceMillis = 300L,
            maxEntries = 2,
            clock = { now += 10L; now },
        )
        advanceUntilIdle()

        val id1 = Uuid.random()
        val id2 = Uuid.random()
        val id3 = Uuid.random()

        store.save(id1, ChatDraft(text = "draft-1-a"))
        advanceTimeBy(100L)
        store.save(id1, ChatDraft(text = "draft-1-b"))
        advanceTimeBy(100L)
        // 尚在防抖窗口内，文件尚未落盘
        assertFalse(storageFile.exists())
        advanceTimeBy(350L)
        advanceUntilIdle()
        assertTrue(storageFile.exists())

        store.save(id2, ChatDraft(text = "draft-2"), immediate = true)
        store.save(id3, ChatDraft(text = "draft-3"), immediate = true)
        advanceUntilIdle()

        assertNull(store.getImmediate(id1))
        assertEquals("draft-2", store.getImmediate(id2)?.text)
        assertEquals("draft-3", store.getImmediate(id3)?.text)
        assertEquals(setOf(id2, id3), store.draftConversationIds.value)
    }

    @Test
    fun `损坏JSON容错且冷启动未加载完成前的写入不被旧磁盘覆盖`() = runTest {
        val (_, storageFile) = tempStorageFile()
        storageFile.writeText("{not-valid-json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val brokenStore = ChatDraftStore(storageFile, TestScope(dispatcher), ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertTrue(brokenStore.draftConversationIds.value.isEmpty())

        // 写回正常数据并验证冷启动竞态
        val oldId = Uuid.random()
        val editedId = Uuid.random()
        brokenStore.save(oldId, ChatDraft(text = "old-from-disk"), immediate = true)
        brokenStore.save(editedId, ChatDraft(text = "stale-on-disk"), immediate = true)
        advanceUntilIdle()

        val storeReloading = ChatDraftStore(
            storageFile = storageFile,
            scope = TestScope(dispatcher),
            ioDispatcher = dispatcher,
            debounceMillis = 0L,
        )
        // 在 init 协程尚未跑完前立刻覆盖 editedId 并删除 oldId
        storeReloading.save(editedId, ChatDraft(text = "fresh-user-input"), immediate = true)
        storeReloading.remove(oldId, immediate = true)
        advanceUntilIdle()

        assertNull(storeReloading.get(oldId))
        assertEquals("fresh-user-input", storeReloading.get(editedId)?.text)
    }

    @Test
    fun `localDraftFileExists 兼容跨包路径归一化并过滤不存在的本地文件`() {
        val (filesDir, _) = tempStorageFile()
        val uploadDir = File(filesDir, "upload").apply { mkdirs() }
        val existingFile = File(uploadDir, "exist.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(localDraftFileExists("file://${existingFile.absolutePath}", filesDir))
        // 旧包名路径经 normalizeLocalFileUrl 归一到当前 filesDir/upload/exist.png
        assertTrue(localDraftFileExists("file:///data/user/0/me.rerere.rikkahub/files/upload/exist.png", filesDir))
        assertFalse(localDraftFileExists("file:///data/user/0/me.rerere.rikkahub/files/upload/missing.png", filesDir))
        assertTrue(localDraftFileExists("https://example.com/a.png", filesDir))
    }

    @Test
    fun `onConversationDeleted返回被移除草稿并触发删除广播`() = runTest {
        val (_, storageFile) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatDraftStore(storageFile, TestScope(dispatcher), ioDispatcher = dispatcher)
        advanceUntilIdle()

        val id = Uuid.random()
        val img = UIMessagePart.Image("file:///files/upload/draft-only.png")
        store.save(id, ChatDraft(text = "待发内容", attachments = listOf(img)), immediate = true)
        advanceUntilIdle()

        val observedDeleted = mutableListOf<ConversationDraftEvent>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.conversationEvents.collect { observedDeleted += it }
        }

        val removed = store.onConversationDeleted(id)
        advanceUntilIdle()
        collectJob.cancel()

        assertEquals("待发内容", removed?.text)
        assertEquals(setOf(img.url), removed?.referencedLocalFileUrls())
        assertEquals(listOf(ConversationDraftEvent.Deleted(id)), observedDeleted)
        assertNull(store.getImmediate(id))
        assertTrue(store.allReferencedLocalFileUrls().isEmpty())
    }

    @Test
    fun `undo emits ordered delete and restore events and restores attachment protection`() = runTest {
        val (dir, file) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatDraftStore(file, TestScope(dispatcher), ioDispatcher = dispatcher)
        advanceUntilIdle()
        val id = Uuid.random()
        val image = UIMessagePart.Image("file:///data/user/0/old.package/files/upload/a.png")
        val draft = ChatDraft(text = "unsent", attachments = listOf(image))
        store.save(id, draft)
        val events = mutableListOf<ConversationDraftEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.conversationEvents.collect { events += it }
        }
        val removed = store.onConversationDeleted(id)!!
        assertFalse(store.referencesLocalFile("file://${dir.absolutePath}/upload/a.png", dir))
        store.restore(id, removed)
        advanceUntilIdle()
        assertEquals(listOf(ConversationDraftEvent.Deleted(id), ConversationDraftEvent.Restored(id)), events)
        assertTrue(store.get(id)!!.contentEquals(draft))
        assertTrue(store.referencesLocalFile("file://${dir.absolutePath}/upload/a.png", dir))
        assertFalse(store.referencesLocalFile("file://${dir.absolutePath}/upload/b.png", dir))
    }

    @Test
    fun `attachment cleanup is conservative until disk drafts finish loading`() = runTest {
        val (dir, file) = tempStorageFile()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ChatDraftStore(file, TestScope(dispatcher), ioDispatcher = dispatcher)
        val url = "file://${dir.absolutePath}/upload/a.png"
        assertTrue(store.referencesLocalFile(url, dir))
        advanceUntilIdle()
        assertFalse(store.referencesLocalFile(url, dir))
    }
}
