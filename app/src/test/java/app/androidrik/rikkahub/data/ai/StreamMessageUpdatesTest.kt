package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamMessageUpdatesTest {
    private val base = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    private fun snapshot(text: String, coalescible: Boolean = true) = GenerationChunk.Messages(
        listOf(base.copy(parts = listOf(UIMessagePart.Text(text)))), coalescible,
    )

    @Test fun `burst publishes first and latest complete content`() = runTest {
        val first = snapshot("a")
        val last = snapshot("abc")
        val output = flow {
            emit(first)
            emit(snapshot("ab"))
            emit(last)
        }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() }).toList()
        assertEquals(listOf(first, last), output)
    }

    @Test fun `trailing timer flushes even when provider pauses`() = runTest {
        val output = mutableListOf<GenerationChunk>()
        val first = snapshot("a")
        val tail = snapshot("ab")
        val job = launch {
            flow {
                emit(first)
                delay(10)
                emit(tail)
                awaitCancellation()
            }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() })
                .toList(output)
        }
        runCurrent()
        assertEquals(listOf(first), output)
        advanceTimeBy(49)
        runCurrent()
        assertEquals(listOf(first), output)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(first, tail), output)
        job.cancelAndJoin()
    }

    @Test fun `tool and finish boundaries immediately replace pending content`() = runTest {
        val first = snapshot("a")
        val boundary = snapshot("tool ready", false)
        val finish = snapshot("complete", false)
        val output = flow {
            emit(first)
            emit(snapshot("ab"))
            emit(boundary)
            emit(snapshot("more"))
            emit(finish)
        }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() }).toList()
        assertEquals(listOf(first, boundary, finish), output)
        assertEquals(0, testScheduler.currentTime)
    }

    @Test fun `cancellation preserves delayed tail without emitting to cancelled collector`() = runTest {
        val output = mutableListOf<GenerationChunk>()
        val recovered = mutableListOf<GenerationChunk.Messages>()
        val tail = snapshot("ab")
        val job = launch {
            flow {
                emit(snapshot("a"))
                emit(tail)
                awaitCancellation()
            }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = recovered::add)
                .toList(output)
        }
        runCurrent()
        job.cancelAndJoin()
        assertEquals(1, output.size)
        assertEquals(listOf(tail), recovered)
    }

    @Test fun `upstream failure flushes pending content before propagating`() = runTest {
        val output = mutableListOf<GenerationChunk>()
        val error = IllegalStateException("disconnected")
        val tail = snapshot("ab")
        try {
            flow {
                emit(snapshot("a"))
                emit(tail)
                throw error
            }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() })
                .toList(output)
            fail("failure must propagate")
        } catch (actual: IllegalStateException) {
            // Coroutine stack-trace recovery may copy the exception across the channel.
            assertEquals(error.message, actual.message)
        }
        assertEquals(tail, output.last())
    }

    @Test fun `continuous stream publishes periodically rather than waiting for silence`() = runTest {
        val times = mutableListOf<Long>()
        val output = flow {
            repeat(121) { index ->
                emit(snapshot("x".repeat(index + 1)))
                delay(1)
            }
        }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() })
        val snapshots = mutableListOf<GenerationChunk>()
        output.collect {
            times += testScheduler.currentTime
            snapshots += it
        }
        assertEquals(listOf(0L, 50L, 100L, 121L), times)
        assertEquals(snapshot("x".repeat(121)), snapshots.last())
    }

    @Test fun `notice is delivered after pending content`() = runTest {
        val first = snapshot("a")
        val tail = snapshot("ab")
        val notice = GenerationChunk.Notice(GenerationNotice.ContextBudgetExhausted(90, 100))
        val output = flow {
            emit(first)
            emit(tail)
            emit(notice)
        }.coalesceMessageUpdates(timeSource = testScheduler.timeSource, onUndelivered = { fail() }).toList()
        assertEquals(listOf(first, tail, notice), output)
    }
}
