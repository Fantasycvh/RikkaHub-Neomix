package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.addFileToZip
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 小于该上限的对象进 pack；更大的单文件/DB 对象走独立 `v3/objects/`。 */
internal const val PACK_ENTRY_LIMIT = 256L * 1024

/** 单个 pack 的原始内容上限（压前），与设计稿的 WebDAV 单 PUT / S3 分片承受范围一致。 */
internal const val PACK_TARGET_BYTES = 32L * 1024 * 1024

/** v3 仓库里的 pack 目录与固定条目时间（保证同一批内容写出可复现的 zip 字节）。 */
internal const val PACK_DIR = "v3/packs"
private const val PACK_FIXED_TIME = 315_532_800_000L // 1980-01-01T00:00:00Z

internal fun objectKeyFor(sha256: String) = "v3/objects/${sha256.take(2)}/$sha256"

internal fun packKeyFor(sha256: String) = "$PACK_DIR/$sha256.zip"

internal fun packShaFromKey(key: String): String? {
    if (!key.startsWith("$PACK_DIR/") || !key.endsWith(".zip")) return null
    val sha = key.removePrefix("$PACK_DIR/").removeSuffix(".zip")
    return sha.takeIf { validHash(it) }
}

/**
 * v3 pack：把一批小对象（原始字节）打进一个内容寻址的 zip，条目名 = 对象 SHA-256。
 *
 * 这样清单仍只记录每个条目的原始 SHA/大小，额外只多一个 pack 指针；恢复时按 pack 分组、
 * 每个 pack 只下载一次，再按 SHA 取条目并校验。pack 名 = 整份 zip 字节的 SHA-256。
 */
internal object SnapshotPacking {

    /** 生成 pack 的结果：packKey → zip 文件，以及每个对象 SHA → 所属 pack 的内容 SHA。 */
    data class PackResult(val files: Map<String, File>, val shaToPack: Map<String, String>)

    /** 生成 pack：按 SHA 排序后顺序累加，达到 [PACK_TARGET_BYTES] 换下一个 zip。 */
    fun write(dir: File, entries: List<Pair<String, File>>, onBytes: (Long) -> Unit = {}): PackResult {
        if (entries.isEmpty()) return PackResult(emptyMap(), emptyMap())
        val files = LinkedHashMap<String, File>()
        val shaToPack = HashMap<String, String>()
        var batch = ArrayList<Pair<String, File>>()
        var batchBytes = 0L

        fun flush() {
            if (batch.isEmpty()) return
            val packFile = File.createTempFile("pack-", ".zip", dir)
            ZipOutputStream(packFile.outputStream().buffered()).use { out ->
                batch.forEach { (sha256, raw) -> addFileToZip(out, raw, sha256, onBytes, entryTime = PACK_FIXED_TIME) }
            }
            val packSha = fileSha256(packFile)
            val key = packKeyFor(packSha)
            if (files.containsKey(key)) packFile.delete() else files[key] = packFile
            batch.forEach { (sha256, _) -> shaToPack[sha256] = packSha }
            batch = ArrayList()
            batchBytes = 0L
        }

        entries.sortedBy { it.first }.forEach { entry ->
            batch += entry
            batchBytes += entry.second.length()
            if (batchBytes >= PACK_TARGET_BYTES) flush()
        }
        flush()
        return PackResult(files, shaToPack)
    }

    /** 上传前本地校验：整份 pack 的 SHA 与每个被引用条目的内容 SHA（不做远端回读）。 */
    fun verify(pack: File, expectedSha256: String, refs: List<SnapshotObjectRef>, scratch: File) {
        require(fileSha256(pack) == expectedSha256) { "Packed objects changed before upload" }
        ZipFile(pack).use { zip ->
            refs.forEach { ref ->
                val entry = requireNotNull(zip.getEntry(ref.sha256)) { "Missing packed object ${ref.sha256}" }
                require(entry.size in setOf(-1L, ref.size)) { "Packed object size mismatch" }
                val result = File(scratch, "verify-${ref.sha256.take(12)}-${System.nanoTime()}")
                try {
                    zip.getInputStream(entry).use { input ->
                        result.outputStream().use { output -> copyBounded(input, output, ref.size) }
                    }
                    require(result.length() == ref.size && fileSha256(result) == ref.sha256) {
                        "Packed object checksum mismatch: ${ref.sha256}"
                    }
                } finally { result.delete() }
            }
        }
    }

    /** 从一个已下载的 pack 中抽取条目到 [targets]（与 [refs] 一一对应）；逐条校验原始 SHA。 */
    fun extractAll(pack: File, refs: List<SnapshotObjectRef>, targets: List<File>, onBytes: (Long) -> Unit = {}) {
        ZipFile(pack).use { zip ->
            refs.forEachIndexed { index, ref ->
                val entry = requireNotNull(zip.getEntry(ref.sha256)) { "Missing packed object ${ref.sha256}" }
                require(entry.size in setOf(-1L, ref.size)) { "Packed object size mismatch" }
                val target = targets[index]
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        require(copyBounded(input, output, ref.size, onBytes) == ref.size) { "Packed object is truncated" }
                    }
                }
                require(fileSha256(target) == ref.sha256) { "Packed object checksum mismatch: ${ref.sha256}" }
            }
        }
    }

    /** pack 下载预算：原始总字节 + 最坏压缩/头部开销，防止伪造清单撑爆缓存。 */
    fun downloadLimit(refs: List<SnapshotObjectRef>): Long =
        Math.addExact(Math.multiplyExact(refs.sumOf { it.size }, 2L), Math.multiplyExact(refs.size.toLong(), 4096L) + 1024 * 1024)
}
