package app.androidrik.rikkahub.data.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.TimeSource

/**
 * Coalesce complete snapshots, never raw provider deltas. The first snapshot and all boundaries
 * publish immediately; a trailing timer also publishes when the provider pauses mid-stream.
 * [onUndelivered] preserves a pending tail if cancellation prevents normal Flow emission.
 * It runs in the collector context and must not suspend or emit into the cancelled flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<GenerationChunk>.coalesceMessageUpdates(
    intervalMillis: Long = 50,
    timeSource: TimeSource = TimeSource.Monotonic,
    onUndelivered: (GenerationChunk.Messages) -> Unit,
): Flow<GenerationChunk> = flow {
    require(intervalMillis > 0)
    coroutineScope {
        val input = Channel<GenerationChunk>(Channel.RENDEZVOUS)
        val producer = launch {
            try {
                this@coalesceMessageUpdates.collect { input.send(it) }
            } catch (error: Throwable) {
                // Deliver upstream failure after flushing its last complete snapshot.
                input.close(error)
            } finally {
                input.close()
            }
        }
        var pending: GenerationChunk.Messages? = null
        var lastPublished: kotlin.time.TimeMark? = null

        suspend fun publish(chunk: GenerationChunk) {
            emit(chunk)
            pending = null
            lastPublished = timeSource.markNow()
        }

        try {
            var open = true
            while (open) {
                val remaining = lastPublished?.let {
                    (intervalMillis - it.elapsedNow().inWholeMilliseconds).coerceAtLeast(0)
                } ?: 0
                select<Unit> {
                    // Register the timer first so a busy provider cannot starve the trailing update.
                    if (pending != null) {
                        onTimeout(remaining) { publish(pending!!) }
                    }
                    input.onReceiveCatching { received ->
                        val chunk = received.getOrNull()
                        if (chunk == null) {
                            pending?.let { publish(it) }
                            received.exceptionOrNull()?.let { throw it }
                            open = false
                        } else if (chunk is GenerationChunk.Messages && chunk.coalescible) {
                            pending = chunk
                            if (remaining == 0L) publish(chunk)
                        } else {
                            // A boundary snapshot supersedes pending content. Notices carry no
                            // content, so publish the tail before delivering the notice.
                            if (chunk !is GenerationChunk.Messages) pending?.let { publish(it) }
                            if (chunk is GenerationChunk.Messages) pending = chunk
                            publish(chunk)
                        }
                    }
                }
            }
        } finally {
            producer.cancel()
            input.cancel()
            pending?.let(onUndelivered)
        }
    }
}
