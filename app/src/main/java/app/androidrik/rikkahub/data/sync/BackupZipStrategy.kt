package app.androidrik.rikkahub.data.sync

import java.util.zip.Deflater

/**
 * 备份 zip 的**压缩策略**（大文件加固 P0，设计见 `docs/design/design-backup-workspace-files.md` §九）。
 *
 * **要解决的问题**：对已压缩/不可压缩数据（git pack、图片、视频、pdf）跑 DEFLATE 纯属浪费——
 * 实测比率 1.000x 仍以 24 MB/s 烧单核，2.8GB 下 ≈ 数分钟白烧。判定为不可压缩的条目改用
 * `ZipEntry.STORED`（原样拷贝），DEFLATE 的 CPU 成本**归零**。
 *
 * **判定用采样而非扩展名**：扩展名表覆盖不了 git pack / sqlite / 未知二进制；而读文件头
 * [SAMPLE_SIZE] 字节用 [Deflater]（与 ZipOutputStream 同库同级别）压一遍，比率接近 1 即
 * 判定为不可压缩。采样成本 64KB × 一次 deflate ≈ 微秒级。
 *
 * **纯 JVM、无 Android 依赖**——`ZipOutputStream`/`Deflater` 都是 java.util.zip，可直接单测。
 */
internal object BackupZipStrategy {

    /** 可压缩性采样的头部字节数。 */
    const val SAMPLE_SIZE = 64 * 1024

    /** 采样低于此长度不判 STORED（小文件 STORED 预读不划算，DEFLATE 也快）。 */
    const val MIN_SAMPLE_BYTES = 4096

    /** 压缩级别与 `ZipOutputStream` 默认一致（DEFLATED 默认 level = -1 ≈ 6）。 */
    private const val DEFLATE_LEVEL = -1

    /**
     * 头部采样判定是否「原样存储」。
     *
     * @param sample 文件头 ≤[SAMPLE_SIZE] 字节（空/极短返回 false——小文件不值得 STORED 预读）
     * @return true = 该条目应使用 [java.util.zip.ZipEntry.STORED]
     */
    fun shouldStoreUncompressed(sample: ByteArray): Boolean {
        if (sample.size < MIN_SAMPLE_BYTES) return false
        val deflater = Deflater(DEFLATE_LEVEL, true)
        try {
            deflater.setInput(sample)
            deflater.finish()
            val out = ByteArray(sample.size + 64)
            var compressed = 0
            while (!deflater.finished()) {
                compressed += deflater.deflate(out, compressed, out.size - compressed)
            }
            // 比率 ≥ 0.98：几乎压不动 → STORED。留 2% 余量防「勉强可压」的误判
            // （DEFLATE 花 CPU 只省 2% 空间，不如直接拷贝）。
            return compressed >= (sample.size * 98) / 100
        } finally {
            deflater.end()
        }
    }
}
