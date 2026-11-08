package app.androidrik.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** An awaited task can outlive its waiter only after an explicit, scope-specific handoff. */
class TaskHandoffRegistry {
    private class Entry(val id: String, var retained: Boolean = false)
    private val entries = mutableMapOf<String, MutableSet<Entry>>()

    @Synchronized
    fun retain(scope: String): List<String> = entries[scope].orEmpty().map {
        it.retained = true
        it.id
    }

    suspend fun <T> await(
        scope: String,
        id: String,
        taskScope: CoroutineScope,
        onRetained: suspend () -> Unit,
        block: suspend () -> T,
    ): T {
        val entry = Entry(id)
        synchronized(this) { entries.getOrPut(scope) { mutableSetOf() }.add(entry) }
        // Independent execution, but ordinary cancellation still explicitly cancels and joins it.
        val task = taskScope.async { block() }
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (synchronized(this@TaskHandoffRegistry) { entry.retained }) {
                    onRetained()
                } else {
                    task.cancel()
                    task.join()
                }
            }
            throw cancelled
        } finally {
            synchronized(this) {
                entries[scope]?.let { group ->
                    group.remove(entry)
                    if (group.isEmpty()) entries.remove(scope)
                }
            }
        }
    }
}
