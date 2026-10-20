package app.androidrik.rikkahub.ui.components.floating

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingChatStateTest {
    private fun message(role: MessageRole = MessageRole.ASSISTANT) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text("hello")))

    private fun actions(
        messages: List<UIMessage> = listOf(message()),
        generating: Boolean = false,
        ready: Boolean = true,
        configured: Boolean = true,
        stopping: Boolean = false,
        draft: String = "hello",
    ) = floatingChatActions(ready, configured, generating, stopping, draft, messages)

    @Test fun `explicit saved recent and new ID fallback order`() {
        val explicit = Uuid.random()
        val saved = Uuid.random()
        val recent = Uuid.random()
        assertEquals(explicit, floatingChatInitialId(explicit.toString(), saved.toString(), recent))
        assertEquals(saved, floatingChatInitialId("invalid", saved.toString(), recent))
        assertEquals(recent, floatingChatInitialId(null, "invalid", recent))
        assertNotNull(floatingChatInitialId(null, null, null))
        assertNull(floatingChatConversationId(""))
        assertNull(floatingChatConversationId(null))
    }

    @Test fun `not initialized disables every mutation`() {
        assertEquals(FloatingChatActions(false, false, false, false), actions(ready = false))
    }

    @Test fun `no model disables generation but preserves stop`() {
        assertEquals(FloatingChatActions(false, true, false, false),
            actions(configured = false, generating = true))
    }

    @Test fun `active generation supports queued send and stop only`() {
        assertEquals(FloatingChatActions(true, true, false, false), actions(generating = true))
        assertFalse(actions(draft = " \n").send)
    }

    @Test fun `pending approval is read only including stop and send`() {
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call", "delete", "{}", approvalState = ToolApprovalState.Pending)
        ))
        assertTrue(listOf(pending).floatingChatHasPendingApproval())
        assertEquals(FloatingChatActions(false, false, false, false), actions(listOf(pending), generating = true))
        assertEquals(FloatingChatActions(false, false, false, false), actions(listOf(pending)))
    }

    @Test fun `executed pending metadata does not block actions`() {
        val executed = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Tool("call", "read", "{}", output = listOf(UIMessagePart.Text("done")),
                approvalState = ToolApprovalState.Pending)
        ))
        assertFalse(listOf(executed).floatingChatHasPendingApproval())
        assertTrue(actions(listOf(executed)).continueReply)
    }

    @Test fun `stopping gates further actions`() {
        assertEquals(FloatingChatActions(false, false, false, false), actions(stopping = true))
    }

    @Test fun `continue only applies to final assistant and regeneration never selects system`() {
        val user = message(MessageRole.USER)
        val system = message(MessageRole.SYSTEM)
        assertFalse(actions(listOf(user)).continueReply)
        assertTrue(actions(listOf(user)).regenerate)
        assertEquals(user, floatingChatRegenerateTarget(listOf(user, system)))
        assertNull(floatingChatRegenerateTarget(listOf(system)))
    }

    @Test fun `errors from other conversations stay hidden`() {
        val id = Uuid.random()
        assertTrue(floatingChatErrorVisible(null, id))
        assertTrue(floatingChatErrorVisible(id, id))
        assertFalse(floatingChatErrorVisible(Uuid.random(), id))
    }

    @Test fun `attachments render safe placeholder instead of URI`() {
        val image = UIMessagePart.Image("file:///private/image.png")
        val message = UIMessage(role = MessageRole.USER, parts = listOf(image))
        assertTrue(message.floatingChatHasAttachment())
        assertFalse(floatingChatPartText(image).contains("file:"))
        assertTrue(floatingChatPartText(image).contains("主界面"))
    }

    @Test fun `reference acquired before initialize and released once at disposal`() = runBlocking {
        val events = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            holdFloatingChatSession(
                { events += "acquire" }, { events += "initialize" }, { events += "ready" },
                { events += "error" }, { events += "release" },
            )
        }
        assertEquals(listOf("acquire", "initialize", "ready"), events)
        holder.cancelAndJoin()
        assertEquals(listOf("acquire", "initialize", "ready", "release"), events)
    }

    @Test fun `failed initialize remains referenced until disposal`() = runBlocking {
        val events = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            holdFloatingChatSession(
                { events += "acquire" }, { error("DB unavailable") }, { events += "ready" },
                { events += "error" }, { events += "release" },
            )
        }
        assertEquals(listOf("acquire", "error"), events)
        holder.cancelAndJoin()
        assertEquals(listOf("acquire", "error", "release"), events)
    }

    @Test fun `cancelling initialization releases without reporting cancellation as error`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val holder = launch(start = CoroutineStart.UNDISPATCHED) {
            holdFloatingChatSession(
                { events += "acquire" }, { gate.await() }, { events += "ready" },
                { events += "error" }, { events += "release" },
            )
        }
        holder.cancelAndJoin()
        assertEquals(listOf("acquire", "release"), events)
    }

    @Test fun `pane disposal does not cancel independently owned generation`() = runBlocking {
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val generation = serviceScope.launch { awaitCancellation() }
            val holder = launch(start = CoroutineStart.UNDISPATCHED) {
                holdFloatingChatSession({}, {}, {}, {}, {})
            }
            holder.cancelAndJoin()
            assertTrue(generation.isActive)
        } finally { serviceScope.cancel() }
    }
}
