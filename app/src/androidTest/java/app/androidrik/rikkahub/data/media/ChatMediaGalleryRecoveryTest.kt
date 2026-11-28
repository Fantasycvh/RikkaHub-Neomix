package app.androidrik.rikkahub.data.media

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.rikkahub.data.ai.tools.imageToolMediaResult
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.export.collectAttachmentUrls
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.data.repository.MediaCreationRepository
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ChatMediaGalleryRecoveryTest {
    @Test fun pendingChatRestoresGalleryAfterTimelineDeletionAndRepositoryRecreation() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "gallery-recovery-${Uuid.random()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = root }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = MediaCreationRepository(context, database, database.mediaCreationDao(), JsonInstant)
            val session = repository.getOrCreateSession(Uuid.random(), "fixture", MediaCreationDraft(sourceConversationId = Uuid.random().toString()))
            val pending = MediaCreationRecord(sessionId = session.id, providerId = Uuid.random(), providerName = "fixture",
                modelId = "image", kind = MediaKind.IMAGE, prompt = "draw")
            repository.insertRecord(pending)
            val tool = UIMessagePart.Tool("call", "generate_image", "{}",
                listOf(UIMessagePart.Text(imageToolMediaResult(pending) { it })))
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            val conversation = Conversation(assistantId = Uuid.random(),
                messageNodes = listOf(MessageNode(messages = listOf(message))))
            val savedBeforeCompletion = JsonInstant.encodeToString(conversation)
            val output = MediaCreationOutput("media_creation/${session.id}/${pending.id}/out_0.png", "image/png")
            repository.resolve(output.path).apply { parentFile!!.mkdirs(); writeText("original image bytes") }
            val galleryPath = chatMediaGalleryPath(pending.id, 0, output)
            repository.resolve(galleryPath).apply { parentFile!!.mkdirs(); writeText("gallery image bytes") }
            database.genMediaDao().insert(GenMediaEntity(path = galleryPath, modelId = "image", prompt = "draw",
                createAt = pending.createAt.toEpochMilli(), type = "image_generation"))
            repository.updateRecord(pending.id) { it.copy(status = MediaCreationStatus.SUCCEEDED, outputs = listOf(output)) }
            repository.deleteSession(session.id)
            assertNull(repository.getRecord(pending.id))
            assertFalse(repository.resolve(output.path).exists())

            val reopened = MediaCreationRepository(context, database, database.mediaCreationDao(), JsonInstant)
            val snapshot = materializeChatMediaSnapshot(JsonInstant.decodeFromString<Conversation>(savedBeforeCompletion),
                reopened::getRecord, loadGallery = reopened::getChatGalleryImages,
            ) { _, _, _ -> error("deleted timeline cannot be consulted") }
            assertEquals(listOf("file://${repository.resolve(galleryPath).absolutePath}"), snapshot.collectAttachmentUrls())
            assertEquals("gallery image bytes", File(snapshot.collectAttachmentUrls().single().removePrefix("file://")).readText())
            assertTrue(reopened.getChatGalleryImages(Uuid.random()).isEmpty())
            repository.resolve(galleryPath).delete()
            assertTrue(reopened.getChatGalleryImages(pending.id).isEmpty())
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }
}
