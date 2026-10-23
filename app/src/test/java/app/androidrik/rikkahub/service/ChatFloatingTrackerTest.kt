package app.androidrik.rikkahub.service

import app.androidrik.rikkahub.service.ChatFloatingTracker.Action
import app.androidrik.rikkahub.service.ChatFloatingTracker.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class ChatFloatingTrackerTest {
    private val a = Uuid.random()
    private val b = Uuid.random()

    private fun reduce(vararg actions: Action): TrackingState =
        actions.fold(TrackingState()) { state, action -> state.reduce(action) }

    @Test
    fun `conversation already generating when going to background becomes the target`() {
        val state = reduce(Action.JobsChanged(setOf(a)))
        assertEquals(FloatingChatWindow(a, expanded = false), state.window)
    }

    @Test
    fun `target stays after its generation finishes so the user can reply`() {
        val state = reduce(Action.JobsChanged(setOf(a)), Action.JobsChanged(emptySet()))
        assertEquals(a, state.window?.conversationId)
    }

    @Test
    fun `newly started conversation replaces the target`() {
        val state = reduce(Action.JobsChanged(setOf(a)), Action.JobsChanged(setOf(a, b)))
        assertEquals(b, state.window?.conversationId)
    }

    @Test
    fun `dismissed conversation does not come back until a generation starts`() {
        val dismissed = reduce(Action.JobsChanged(setOf(a, b)), Action.Dismiss)
        assertNull(dismissed.window)
        // Another conversation finishing is not a newly started generation.
        assertNull(dismissed.reduce(Action.JobsChanged(setOf(a))).window)
        val restarted = dismissed.reduce(Action.JobsChanged(emptySet()))
            .reduce(Action.JobsChanged(setOf(a)))
        assertEquals(a, restarted.window?.conversationId)
    }

    @Test
    fun `expanding requires a target and dismiss collapses the panel`() {
        assertNull(reduce(Action.Expand).window)
        assertEquals(false, reduce(Action.Expand, Action.JobsChanged(setOf(a))).window?.expanded)

        val expanded = reduce(Action.JobsChanged(setOf(a)), Action.Expand)
        assertEquals(true, expanded.window?.expanded)
        assertEquals(false, expanded.reduce(Action.Collapse).window?.expanded)

        val restarted = expanded.reduce(Action.Dismiss)
            .reduce(Action.JobsChanged(emptySet()))
            .reduce(Action.JobsChanged(setOf(a)))
        assertEquals(false, restarted.window?.expanded)
    }

    @Test
    fun `pending approval candidate arriving through jobs changed becomes the target`() {
        // The service merges running jobs and approval-only conversations into this same action.
        val state = reduce(Action.JobsChanged(emptySet()), Action.JobsChanged(setOf(b)))
        assertEquals(setOf(b), state.running)
        assertEquals(FloatingChatWindow(b, expanded = false), state.window)
    }

    @Test
    fun `dismissed pending approval does not reopen on repeated candidate events`() {
        val dismissed = reduce(Action.JobsChanged(setOf(a)), Action.Expand, Action.Dismiss)
        val repeated = dismissed.reduce(Action.JobsChanged(setOf(a)))
            .reduce(Action.Expand)
            .reduce(Action.JobsChanged(setOf(a)))
        assertEquals(setOf(a), repeated.running)
        assertNull(repeated.window)
        assertEquals(false, repeated.expanded)
    }

    @Test
    fun `new pending approval candidate replaces a completed target`() {
        val state = reduce(
            Action.JobsChanged(setOf(a)),
            Action.JobsChanged(emptySet()),
            Action.JobsChanged(setOf(b)),
        )
        assertEquals(FloatingChatWindow(b, expanded = false), state.window)
    }

    @Test
    fun `unchanged candidate set does not replace the currently selected conversation`() {
        val state = TrackingState(running = setOf(a, b), target = a, expanded = true)
        assertEquals(state, state.reduce(Action.JobsChanged(setOf(b, a))))
    }

    @Test
    fun `production turn revision reopens dismissed conversation only for a new turn`() {
        val dismissed = reduce(
            Action.TurnsChanged(mapOf(a to 1L)),
            Action.Expand,
            Action.Dismiss,
        )
        val sameTurn = dismissed.reduce(Action.TurnsChanged(mapOf(a to 1L)))
        assertNull(sameTurn.window)
        assertEquals(mapOf(a to 1L), sameTurn.turns)

        // No intermediate empty candidate emission is required for a new round.
        val nextTurn = sameTurn.reduce(Action.TurnsChanged(mapOf(a to 2L)))
        assertEquals(FloatingChatWindow(a, expanded = false), nextTurn.window)
        assertEquals(mapOf(a to 2L), nextTurn.turns)
    }

    @Test
    fun `production approval updates in the same turn do not reopen dismissed panel`() {
        val dismissed = reduce(
            Action.TurnsChanged(mapOf(a to 1L, b to 4L)),
            Action.Dismiss,
        )
        // Running -> approval -> resumed generation keeps the same turn revision.
        val sameTurn = dismissed.reduce(Action.TurnsChanged(mapOf(b to 4L, a to 1L)))
            .reduce(Action.Expand)
            .reduce(Action.TurnsChanged(mapOf(a to 1L, b to 4L)))
        assertNull(sameTurn.window)
        assertEquals(false, sameTurn.expanded)
        assertEquals(mapOf(a to 1L, b to 4L), sameTurn.turns)
    }

    @Test
    fun `production candidate can reappear with the same revision after leaving candidate set`() {
        val dismissed = reduce(Action.TurnsChanged(mapOf(a to 1L)), Action.Dismiss)
        val absent = dismissed.reduce(Action.TurnsChanged(emptyMap()))
        assertNull(absent.window)
        assertEquals(emptyMap<Uuid, Long>(), absent.turns)
        assertEquals(emptySet<Uuid>(), absent.running)

        val rejoined = absent.reduce(Action.TurnsChanged(mapOf(a to 1L)))
        assertEquals(FloatingChatWindow(a, expanded = false), rejoined.window)
        assertEquals(setOf(a), rejoined.running)
    }

    @Test
    fun `new conversation can appear after dismiss while the old candidate remains`() {
        val state = reduce(
            Action.JobsChanged(setOf(a)),
            Action.Expand,
            Action.Dismiss,
            Action.JobsChanged(setOf(a, b)),
        )
        assertEquals(FloatingChatWindow(b, expanded = false), state.window)
    }
}
