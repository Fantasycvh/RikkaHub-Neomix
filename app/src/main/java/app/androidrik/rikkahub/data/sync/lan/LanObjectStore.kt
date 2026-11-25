package app.androidrik.rikkahub.data.sync.lan

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal const val LAN_CHUNK_BYTES = 1024 * 1024

/** Only a complete SHA-256-verified object is visible to readers. Partial objects can resume by offset. */
internal class LanObjectStore(private val root: File) {
    private val lock = Mutex()

    suspend fun completed(sha256: String): File? = lock.withLock {
        file(sha256).takeIf { it.isFile }
    }

    suspend fun offset(sha256: String): Long = lock.withLock {
        val complete = file(sha256)
        if (complete.isFile) complete.length() else partial(sha256).length()
    }

    suspend fun publishComplete(sha256: String, total: Long) = lock.withLock {
        require(total in 1..LAN_OBJECT_MAX_BYTES)
        val destination = file(sha256)
        if (destination.isFile) {
            require(destination.length() == total)
        } else {
            val staging = partial(sha256)
            require(staging.length() == total)
            publish(staging, destination, sha256)
        }
    }

    suspend fun append(sha256: String, start: Long, total: Long, size: Int, bytes: InputStream): Long = lock.withLock {
        require(total in 1..LAN_OBJECT_MAX_BYTES && size in 1..LAN_CHUNK_BYTES && start in 0 until total && size <= total - start)
        val complete = file(sha256)
        if (complete.isFile) {
            require(complete.length() == total)
            return@withLock total
        }
        check(root.isDirectory || root.mkdirs())
        val staging = partial(sha256)
        val offset = staging.length()
        if (offset == total) {
            publish(staging, complete, sha256)
            return@withLock total
        }
        if (offset != start) return@withLock offset
        // Roll back a short or cancelled chunk, keeping any earlier verified prefix position.
        RandomAccessFile(staging, "rw").use { output ->
            try {
                output.seek(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val count = bytes.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw java.io.EOFException("Incomplete LAN object chunk")
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            } catch (failure: Throwable) {
                output.setLength(start)
                throw failure
            }
        }
        val next = start + size
        if (next == total) publish(staging, complete, sha256)
        next
    }

    private suspend fun publish(staging: File, complete: File, sha256: String) {
        if (hashLanFile(staging) != sha256) {
            check(staging.delete()) { "Could not remove a corrupt LAN object" }
            throw java.io.IOException("LAN object checksum mismatch")
        }
        check(staging.renameTo(complete)) { "Could not publish LAN object" }
    }

    private fun file(digest: String): File {
        require(isLanDigest(digest))
        return File(root, digest)
    }

    private fun partial(digest: String): File = File(root, "${file(digest).name}.part")
}

internal suspend fun hashLanFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
