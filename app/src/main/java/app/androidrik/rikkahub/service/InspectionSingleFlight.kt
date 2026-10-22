package app.androidrik.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Shares only in-flight work for a key; completed results are never cached.
 *
 * Work belongs to [scope], not to an individual caller. Cancelling one caller leaves the other
 * callers alone, while the last caller leaving cancels the work. Cancellation is cooperative.
 * The owner controls the scope's lifecycle and failure policy (use a supervisor scope if failures
 * must not cancel unrelated work). A joining caller's [run] block is ignored.
 */
internal class InspectionSingleFlight<K, V>(private val scope: CoroutineScope) {
    private class Entry<V>(val task: Deferred<V>, var waiters: Int = 0)

    private val lock = Any()
    private val entries = mutableMapOf<K, Entry<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        val entry = synchronized(lock) {
            val existing = entries[key]
            val selected = if (existing != null &&
                !existing.task.isCompleted && !existing.task.isCancelled
            ) {
                existing
            } else {
                // LAZY publishes the entry before any user code can execute, even on Unconfined.
                val created = Entry(scope.async(start = CoroutineStart.LAZY) { block() })
                entries[key] = created
                created.task.invokeOnCompletion {
                    synchronized(lock) {
                        if (entries[key] === created) entries.remove(key)
                    }
                }
                created
            }
            selected.waiters++
            selected
        }

        try {
            // Neither starting the coroutine nor executing the caller's block holds the lock.
            entry.task.start()
            return entry.task.await()
        } finally {
            val cancel = synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0) {
                    // Remove before cancelling: a new caller must not join abandoned work, and
                    // the old completion callback must not remove that caller's replacement.
                    if (entries[key] === entry) entries.remove(key)
                    true
                } else {
                    false
                }
            }
            if (cancel) entry.task.cancel()
        }
    }
}
