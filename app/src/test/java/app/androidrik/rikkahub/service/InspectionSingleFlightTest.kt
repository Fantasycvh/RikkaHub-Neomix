package app.androidrik.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionSingleFlightTest {
    private fun checkFlight(
        block: suspend CoroutineScope.(InspectionSingleFlight<String, Int>, CoroutineScope) -> Unit,
    ) = runBlocking {
        withTimeout(5_000) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                block(InspectionSingleFlight(owner), owner)
            } finally {
                owner.cancel()
            }
        }
    }

    @Test
    fun `same key shares one running block`() = checkFlight { flight, _ ->
        val release = CompletableDeferred<Int>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { calls++; release.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { calls++; -1 }
        }
        assertEquals(1, calls)
        release.complete(42)
        assertEquals(42, first.await())
        assertEquals(42, second.await())
    }

    @Test
    fun `different keys run independently`() = checkFlight { flight, _ ->
        val releaseFirst = CompletableDeferred<Int>()
        val releaseSecond = CompletableDeferred<Int>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("first") { calls++; releaseFirst.await() }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("second") { calls++; releaseSecond.await() }
        }
        assertEquals(2, calls)
        releaseSecond.complete(2)
        assertEquals(2, second.await())
        assertFalse(first.isCompleted)
        releaseFirst.complete(1)
        assertEquals(1, first.await())
    }

    @Test
    fun `completed value is not cached including immediate completion`() = checkFlight { flight, _ ->
        var calls = 0
        assertEquals(1, flight.run("key") { ++calls })
        assertEquals(2, flight.run("key") { ++calls })
        assertEquals(2, calls)
    }

    @Test
    fun `failure reaches all waiters and permits retry`() = checkFlight { flight, _ ->
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("inspection failed")
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { flight.run("key") { release.await(); throw failure } }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { flight.run("key") { -1 } }
        }
        release.complete(Unit)
        // Coroutine stack-trace recovery may copy the exception; assert semantics, not identity.
        listOf(first.await(), second.await()).forEach { result ->
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
        }
        assertEquals(7, flight.run("key") { 7 })
    }

    @Test
    fun `cancelling one waiter does not cancel shared work`() = checkFlight { flight, _ ->
        val release = CompletableDeferred<Int>()
        var stopped = false
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") {
                try {
                    release.await()
                } finally {
                    stopped = true
                }
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { -1 }
        }
        first.cancelAndJoin()
        assertFalse(stopped)
        assertFalse(second.isCompleted)
        release.complete(42)
        assertEquals(42, second.await())
        assertTrue(stopped)
    }

    @Test
    fun `last waiter leaving cancels work and allows retry`() = checkFlight { flight, _ ->
        val stopped = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") {
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { -1 }
        }
        first.cancelAndJoin()
        assertFalse(stopped.isCompleted)
        second.cancelAndJoin()
        stopped.await()
        assertEquals(9, flight.run("key") { 9 })
    }

    @Test
    fun `block cancellation does not remain cached`() = checkFlight { flight, _ ->
        val result = runCatching {
            flight.run("key") { throw CancellationException("cancel inspection") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(3, flight.run("key") { 3 })
    }

    @Test
    fun `scope cancellation reaches work and every waiter`() = checkFlight { flight, owner ->
        val stopped = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                flight.run("key") {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { flight.run("key") { -1 } }
        }
        owner.cancel()
        stopped.await()
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(second.await().exceptionOrNull() is CancellationException)
        var ran = false
        val retry = runCatching { flight.run("key") { ran = true; 1 } }
        assertTrue(retry.exceptionOrNull() is CancellationException)
        assertFalse(ran)
    }

    @Test
    fun `old cancelled completion cannot remove replacement entry`() = checkFlight { flight, _ ->
        val allowOldCleanup = CompletableDeferred<Unit>()
        lateinit var oldTask: Job
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") {
                oldTask = currentCoroutineContext()[Job]!!
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { allowOldCleanup.await() }
                }
            }
        }
        first.cancelAndJoin()
        val releaseNew = CompletableDeferred<Int>()
        var calls = 0
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { calls++; releaseNew.await() }
        }
        allowOldCleanup.complete(Unit)
        oldTask.join()
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            flight.run("key") { calls++; -1 }
        }
        assertEquals(1, calls)
        releaseNew.complete(8)
        assertEquals(8, second.await())
        assertEquals(8, third.await())
    }
}
