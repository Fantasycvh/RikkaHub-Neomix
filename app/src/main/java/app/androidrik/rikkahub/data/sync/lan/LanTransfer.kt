package app.androidrik.rikkahub.data.sync.lan

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

internal data class LanTransferResult(val transferredBytes: Long, val remainingObjects: Set<String>)

internal fun lanObjectSizes(entries: List<LanManifestObject>): Map<String, Long?> {
    val objects = linkedMapOf<String, Long?>()
    entries.forEach { entry ->
        entry.validate()
        val previousSize = objects.put(entry.sha256, entry.size)
        require(previousSize == null || previousSize == entry.size)
        entry.attachmentShas.forEach { objects.putIfAbsent(it, null) }
    }
    return objects
}

/** Applies one side of the transfer plan to local staging. The sender exposes only its manifest's objects. */
internal suspend fun receiveLanObjects(
    entries: List<LanManifestObject>,
    target: LanObjectStore,
    budgetBytes: Long = LAN_OBJECT_MAX_BYTES,
    sourceSize: suspend (String) -> Long,
    localObjects: Map<String, File> = emptyMap(),
    readRange: suspend (digest: String, start: Long, size: Int) -> InputStream,
): LanTransferResult {
    require(budgetBytes >= 0)
    val objects = lanObjectSizes(entries)
    var transferred = 0L
    val remaining = linkedSetOf<String>()
    for ((digest, knownSize) in objects) {
        currentCoroutineContext().ensureActive()
        // The local inventory already hashed the file. Application rechecks its digest before writing any chat.
        val cached = target.completed(digest) ?: localObjects[digest]?.takeIf(File::isFile)
        if (cached != null) {
            require(knownSize == null || cached.length() == knownSize)
            continue
        }
        if (transferred >= budgetBytes) {
            remaining += digest
            continue
        }
        val total = sourceSize(digest)
        require(total in 1..LAN_OBJECT_MAX_BYTES && (knownSize == null || knownSize == total))
        var offset = target.offset(digest)
        require(offset <= total)
        if (offset == total) {
            target.publishComplete(digest, total)
            continue
        }
        while (offset < total && transferred < budgetBytes) {
            val chunk = minOf(LAN_CHUNK_BYTES.toLong(), total - offset, budgetBytes - transferred).toInt()
            val next = readRange(digest, offset, chunk).use { target.append(digest, offset, total, chunk, it) }
            if (next != offset + chunk) throw java.io.IOException("LAN object offset changed")
            transferred += chunk
            offset = next
        }
        if (offset < total) remaining += digest
    }
    return LanTransferResult(transferred, remaining)
}

internal fun readLanFileRange(file: File, start: Long, size: Int): InputStream {
    require(start >= 0 && size in 1..LAN_CHUNK_BYTES && file.length() - start >= size)
    val source = RandomAccessFile(file, "r")
    try {
        source.seek(start)
        return object : InputStream() {
            private var remaining = size

            override fun read(): Int {
                if (remaining == 0) return -1
                return source.read().also { if (it >= 0) remaining-- }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0) return -1
                return source.read(buffer, offset, minOf(length, remaining)).also { if (it > 0) remaining -= it }
            }

            override fun close() = source.close()
        }
    } catch (failure: Throwable) {
        source.close()
        throw failure
    }
}
