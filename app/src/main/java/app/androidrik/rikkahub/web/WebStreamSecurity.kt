package app.androidrik.rikkahub.web

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A JWT is checked at connect time; also revoke already-open SSE streams on credential changes. */
internal fun <T> Flow<T>.untilWebPasswordChanges(passwords: Flow<String>, connectedPassword: String): Flow<T> =
    channelFlow {
        val forwarding = launch {
            try {
                this@untilWebPasswordChanges.collect { send(it) }
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        val revocation = launch {
            passwords.first { it != connectedPassword || it.isBlank() }
            close()
        }
        awaitClose {
            forwarding.cancel()
            revocation.cancel()
        }
    }.buffer(0)
