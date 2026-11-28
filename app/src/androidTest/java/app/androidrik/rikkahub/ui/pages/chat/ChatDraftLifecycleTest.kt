package app.androidrik.rikkahub.ui.pages.chat

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.draft.ChatDraftStore
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.service.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

/** Run with -PnativeIsolatedTests=true: real DI/Room, separate application ID and files. */
@RunWith(AndroidJUnit4::class)
class ChatDraftLifecycleTest {
    private val koin get() = org.koin.core.context.GlobalContext.get()
    private val repo get() = koin.get<ConversationRepository>()
    private val drafts get() = koin.get<ChatDraftStore>()
    private val service get() = koin.get<ChatService>()
    private val owners = mutableListOf<ViewModelStore>()
    private val conversations = mutableListOf<Conversation>()

    @Before
    fun requireIsolatedProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName ==
            "app.androidrik.rikkahub.nativeverification.debug") {
            "Run draft lifecycle tests only with -PnativeIsolatedTests=true"
        }
        assertTrue("Application boot must finish before resolving DI", (context.applicationContext as RikkaHubApp)
            .awaitBootReady(timeoutMs = 30_000))
    }

    private suspend fun newConversation(): Conversation {
        val assistant = koin.get<SettingsStore>().settingsFlow.first().getCurrentAssistant()
        return Conversation(assistantId = assistant.id, messageNodes = emptyList()).also { conversations += it }
    }

    private suspend fun open(conversation: Conversation): ChatVM = withContext(Dispatchers.Main) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ChatVM(
            conversation.id.toString(), context.applicationContext as Application,
            koin.get(), repo, service, koin.get(), koin.get(), drafts, koin.get(),
        ).also { vm ->
            owners += ViewModelStore().apply { put("chat", vm) }
            vm.awaitDraftRestored()
        }
    }

    private suspend fun awaitCondition(predicate: suspend () -> Boolean) = withTimeout(10_000) {
        while (!predicate()) delay(20)
    }

    @After
    fun cleanUp() = runBlocking {
        withContext(Dispatchers.Main) { owners.forEach { it.clear() } }
        conversations.forEach { repo.deleteConversation(it) }
    }

    @Test
    fun newUnsentDraftHasListEntryAndReopensWithoutOverwritingMessages() = runBlocking {
        val conversation = newConversation()
        val vm = open(conversation)
        withContext(Dispatchers.Main) {
            vm.inputState.setMessageText("unsent draft")
            vm.onInputDraftChanged(vm.inputState.toDraft())
            vm.flushInputDraftNow()
            owners.last().clear()
        }
        awaitCondition { repo.existsConversationById(conversation.id) }
        assertTrue(repo.getConversationsOfAssistant(vm.conversation.value.assistantId).first()
            .any { it.id == conversation.id })
        val reopened = open(conversation)
        withContext(Dispatchers.Main) {
            assertEquals("unsent draft", reopened.inputState.textContent.text.toString())
        }
        val sent = repo.getConversationById(conversation.id)!!.copy(
            messageNodes = listOf(MessageNode.of(UIMessage.user("sent message"))),
        )
        repo.updateConversation(sent)
        assertTrue(repo.ensureDraftConversation(conversation))
        assertEquals(sent.messageNodes, repo.getConversationById(conversation.id)!!.messageNodes)
        repo.deleteConversation(sent)
        assertFalse(repo.ensureDraftConversation(conversation))
        assertFalse(repo.existsConversationById(conversation.id))
    }

    @Test
    fun undoRestoresDraftIntoTheSurvivingViewModel() = runBlocking {
        val conversation = newConversation()
        repo.insertConversation(conversation)
        val vm = open(conversation)
        withContext(Dispatchers.Main) {
            vm.inputState.setMessageText("restore this input")
            vm.onInputDraftChanged(vm.inputState.toDraft())
        }
        repo.deleteConversation(conversation, deleteFiles = false)
        awaitCondition { withContext(Dispatchers.Main) { vm.inputState.toDraft().isEmpty } }
        repo.insertConversation(conversation)
        awaitCondition {
            withContext(Dispatchers.Main) {
                vm.inputState.textContent.text.toString() == "restore this input"
            }
        }
        withContext(Dispatchers.Main) { vm.flushInputDraftNow() }
        assertEquals("restore this input", drafts.get(conversation.id)!!.text)
    }

    @Test
    fun deletingEditedMessageKeepsAttachmentUsedByModifiedDraft() = runBlocking {
        val file = File(koin.get<FilesManager>().getFilesDir(), "upload/draft-${Uuid.random()}.png")
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val image = UIMessagePart.Image(file.toUri().toString())
        val message = UIMessage.user("original").copy(parts = listOf(image, UIMessagePart.Text("original")))
        val conversation = newConversation().copy(messageNodes = listOf(MessageNode.of(message)))
        repo.insertConversation(conversation)
        val vm = open(conversation)
        awaitCondition { vm.conversation.value.getMessageNodeByMessageId(message.id) != null }
        withContext(Dispatchers.Main) {
            vm.inputState.editingMessage = message.id
            vm.inputState.setContents(message.parts)
            vm.inputState.setMessageText("modified draft")
            vm.onInputDraftChanged(vm.inputState.toDraft())
            service.deleteMessage(conversation.id, message.id)
        }
        awaitCondition { withContext(Dispatchers.Main) { !vm.inputState.isEditing() } }
        assertTrue(file.isFile)
        withContext(Dispatchers.Main) {
            assertEquals("modified draft", vm.inputState.textContent.text.toString())
            assertTrue(image in vm.inputState.getContents())
        }
    }
}
