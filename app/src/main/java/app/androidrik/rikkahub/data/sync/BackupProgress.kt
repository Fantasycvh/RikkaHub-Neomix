package app.androidrik.rikkahub.data.sync

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * 备份 / 恢复的**进度模型**（纯数据，可 JVM 单测）。
 *
 * 采用「阶段 + 字节」模型：每个阶段内部单调递增；跨阶段由阶段序号决定先后。
 * 不确定性体现在 [totalBytes]（如 chunked 下载拿不到 contentLength）——此时 UI 退化为不确定态。
 *
 * 设计见 `docs/design/design-backup-workspace-files.md` §9.1。
 */
enum class BackupPhase {
    /** 打包：读取源文件写入 zip */
    PACKING,

    /** 上传前准备（如 S3 计算 SHA-256 的全量读盘——必须上报，否则进度会停在 0%） */
    PREPARING,

    /** 上传到远端 */
    UPLOADING,

    /** 从远端下载 */
    DOWNLOADING,

    /** 从 zip 解压恢复 */
    EXTRACTING,
}

/**
 * 一次进度快照。[totalBytes] 为 null 表示总量未知（UI 显示不确定进度）。
 */
data class BackupProgress(
    val phase: BackupPhase,
    val processedBytes: Long,
    val totalBytes: Long?,
) {
    /** 0f..1f；总量未知时为 null。 */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (processedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/**
 * 进度上报的节流器：变化不足 [minFractionStep] 且未超过 [minIntervalMillis] 时丢弃，
 * 但**必达 100%**（末次不丢，否则 UI 停在 99%）。
 *
 * 为何需要：ktor 的 `onUpload`/`onDownload` 按 chunk 触发，直写 StateFlow 会高频触发 Compose 重组。
 */
internal class ProgressThrottle(
    private val minFractionStep: Float = 0.01f,
    private val minIntervalMillis: Long = 100L,
) {
    private var lastFraction = -1f
    private var lastEmitAt = 0L
    // 用显式标记而非 lastEmitAt==0 作哨兵：时间戳本身可能为 0（单测/时钟起点），会误判为「从未上报」
    private var hasEmitted = false

    /** @return true = 应当上报 */
    fun shouldEmit(progress: BackupProgress, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val fraction = progress.fraction
        // 总量未知：仅按时间节流
        if (fraction == null) {
            if (!hasEmitted || nowMillis - lastEmitAt >= minIntervalMillis) {
                markEmitted(nowMillis)
                return true
            }
            return false
        }
        val reachedEnd = fraction >= 1f
        val steppedEnough = !hasEmitted || fraction - lastFraction >= minFractionStep
        val timeEnough = !hasEmitted || nowMillis - lastEmitAt >= minIntervalMillis
        if (reachedEnd || steppedEnough || timeEnough) {
            markEmitted(nowMillis)
            lastFraction = fraction
            return true
        }
        return false
    }

    private fun markEmitted(nowMillis: Long) {
        hasEmitted = true
        lastEmitAt = nowMillis
    }
}

/**
 * 统计已读取字节的输入流包装（用于「已消费归档字节」进度）。
 *
 * 选择统计**压缩归档的消费量**而非逐条目的未压缩 size：`ZipInputStream` 流式读时
 * 条目 size 常为 -1（本地头用 data descriptor），逐条目统计不可靠。
 */
internal class CountingInputStream(
    input: InputStream,
    private val onBytes: (Long) -> Unit,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) {
            consumed++
            onBytes(consumed)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) {
            consumed += n
            onBytes(consumed)
        }
        return n
    }
}

/**
 * 边拷贝边按增量上报字节（用于打包时对**每个文件内部**也推进进度）。
 *
 * 为何不能只在文件拷完后报一次：单个大文件（如 85MB 的 DB、2.8GB 的仓库克隆）会占绝大部分时间，
 * 只报一次会让进度条在这一整个文件期间**冻结不动**，看起来像卡死。
 */
internal fun copyReporting(
    input: InputStream,
    output: java.io.OutputStream,
    onDelta: (Long) -> Unit,
    bufferSize: Int = 64 * 1024,
) {
    val buffer = ByteArray(bufferSize)
    while (true) {
        val n = input.read(buffer)
        if (n <= 0) break
        output.write(buffer, 0, n)
        onDelta(n.toLong())
    }
}

/**
 * 空间预检的**纯文件**逻辑（可 JVM 单测）。
 *
 * 见设计稿 §9.1：备份按「输入总量不压缩」保守估算；恢复按「zip + 解压产物同时存在」累计。
 */
internal object BackupSpaceCheck {

    /** 走盘求和：文件返回自身大小，目录递归求和；不存在返回 0。 */
    fun totalSize(target: File): Long {
        if (!target.exists()) return 0
        if (target.isFile) return target.length()
        return target.listFiles()?.sumOf { totalSize(it) } ?: 0
    }

    /**
     * 读 zip **中央目录**求各条目未压缩大小之和（不解压，开销极小）。
     * 读失败（非 zip / 损坏）返回 null，调用方应跳过该项检查而非报错。
     */
    fun uncompressedSizeOfZip(zip: File): Long? = runCatching {
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().sumOf { it.size.coerceAtLeast(0) }
        }
    }.getOrNull()

    /**
     * 备份打包所需空间估算（**保守**：按不压缩计，故用各源文件原始大小之和）。
     *
     * @param inputs 将被写入 zip 的所有源文件/目录（DB 三件套 + 文件区 + 工作区文件区）
     */
    fun estimateBackupBytes(inputs: Collection<File>): Long =
        inputs.sumOf { totalSize(it) }

    /**
     * 是否空间充足。需求与可用都用同一口径；`needed > available` 即不足。
     * 额外留 [headroomRatio] 的余量（默认 5%），避免「刚好写满」。
     */
    fun hasEnoughSpace(available: Long, needed: Long, headroomRatio: Double = 0.05): Boolean {
        if (needed <= 0) return true
        val required = needed + (needed * headroomRatio).toLong()
        return available >= required
    }
}
