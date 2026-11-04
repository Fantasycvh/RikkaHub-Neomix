package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupZipStrategy
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal object SnapshotObjectCodec {
    fun encode(raw: File, encoded: File, tick: (Long) -> Unit = {}) {
        val sample = raw.inputStream().use { input ->
            val buffer = ByteArray(BackupZipStrategy.SAMPLE_SIZE)
            val count = input.read(buffer)
            if (count < 0) byteArrayOf() else buffer.copyOf(count)
        }
        encoded.outputStream().buffered().use { out ->
            val stored = BackupZipStrategy.shouldStoreUncompressed(sample)
            out.write(if (stored) 0 else 1)
            if (stored) raw.inputStream().use { copyBounded(it, out, raw.length(), tick) }
            else {
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                try {
                    DeflaterOutputStream(out, deflater).use { compressed ->
                        raw.inputStream().use { copyBounded(it, compressed, raw.length(), tick) }
                    }
                } finally { deflater.end() }
            }
        }
    }

    fun decode(encoded: File, raw: File, entry: SnapshotFileEntry, tick: (Long) -> Unit = {}) {
        encoded.inputStream().buffered().use { input ->
            when (input.read()) {
                0 -> raw.outputStream().use { require(copyBounded(input, it, entry.size, tick) == entry.size) }
                1 -> {
                    val inflater = Inflater(true)
                    try {
                        InflaterInputStream(input, inflater).use { decoded ->
                            raw.outputStream().use { require(copyBounded(decoded, it, entry.size, tick) == entry.size) }
                        }
                        require(inflater.finished()) { "Truncated compressed object" }
                    } finally { inflater.end() }
                }
                else -> error("Unsupported object encoding")
            }
        }
        require(fileSha256(raw) == entry.sha256) { "Backup object checksum mismatch: ${entry.path}" }
    }
}
