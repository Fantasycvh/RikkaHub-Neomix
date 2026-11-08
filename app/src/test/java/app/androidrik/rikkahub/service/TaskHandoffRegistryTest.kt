package app.androidrik.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TaskHandoffRegistryTest {
    @Test fun `retained task survives waiter cancellation and a newer scope starts immediately`() = runTest {
        val registry = TaskHandoffRegistry()
        val workers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        var resultSaved = false
        var receiptSaved = false
        val old = launch {
            registry.await("turn:A", "run-A", workers, { receiptSaved = true }) {
                finish.await()
                resultSaved = true
            }
        }
        try {
            runCurrent()
            assertEquals(listOf("run-A"), registry.retain("turn:A"))
            old.cancelAndJoin()
            assertTrue(receiptSaved)
            assertFalse(resultSaved)
            var newReply = false
            val newer = launch { newReply = true }
            newer.join()
            assertTrue(newReply)
            assertFalse(resultSaved)
            finish.complete(Unit); runCurrent()
            assertTrue(resultSaved)
            assertTrue(registry.retain("turn:A").isEmpty())
        } finally { workers.cancel() }
    }

    @Test fun `ordinary cancellation cancels worker and waits for cleanup`() = runTest {
        val registry = TaskHandoffRegistry()
        val workers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var cleaned = false
        var retained = false
        val parent = launch {
            registry.await("A", "run", workers, { retained = true }) {
                try { CompletableDeferred<Unit>().await() } finally { cleaned = true }
            }
        }
        try {
            runCurrent(); parent.cancelAndJoin()
            assertTrue(cleaned)
            assertFalse(retained)
            assertTrue(registry.retain("A").isEmpty())
        } finally { workers.cancel() }
    }

    @Test fun `handoff is restricted to registered tasks of the selected scope`() = runTest {
        val registry = TaskHandoffRegistry()
        val workers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val cleaned = mutableSetOf<String>()
        val finish = CompletableDeferred<Unit>()
        val parents = listOf("A", "B").associateWith { scope -> launch {
            registry.await(scope, scope, workers, {}) {
                try { finish.await() } finally { cleaned.add(scope) }
            }
        } }
        try {
            runCurrent(); registry.retain("A")
            parents.values.forEach { it.cancelAndJoin() }
            assertEquals(setOf("B"), cleaned)
            finish.complete(Unit); runCurrent()
            assertEquals(setOf("A", "B"), cleaned)
        } finally { workers.cancel() }
    }

    @Test fun `finished task racing with cancelled waiter still gets a delivery receipt`() = runTest {
        val registry = TaskHandoffRegistry()
        val workers = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        var receipt = false
        val parent = launch { registry.await("A", "run", workers, { receipt = true }) { finish.await() } }
        try {
            runCurrent(); registry.retain("A")
            finish.complete(Unit)
            parent.cancelAndJoin()
            assertTrue(receipt)
        } finally { workers.cancel() }
    }
}
