package app.androidrik.rikkahub.data.sync

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `FILES` 备份项的**打包实现**（从 [BackupArchiveEngine] 抽到顶层的原因见下）。
 *
 * 为什么要抽出来：这里是 2026-10-04 一次真机事故的现场——判据从 `== SKILLS` 改成
 * `in RECURSIVE_FILE_FOLDERS` 时，`entryPrefix` 漏改、仍是硬编码的 `skills/`，
 * 于是新增的递归目录（images）被打成 `skills/...`：图片既不在 `images/` 下（记录找不到），
 * 恢复时还会被解进 `files/skills/`。事故之所以只能靠真机备份发现，是因为打包逻辑当时
 * 藏在需要 Android `Context` 的引擎里、**无法被 JVM 测试覆盖**。
 * 现在 [packAppFiles] 只依赖 `filesDir`，端到端测试可直接产出 zip 并断言条目名。
 */
internal const val PACKING_LOG_TAG = "BackupArchive"

/**
 * 按 [appFilesPackingPlan] 把 `filesDir` 下的用户数据目录写进 zip。
 *
 * @param filesDir 应用 files 目录（注入而非从 Context 取，便于测试）
 * @param onBytes 已写入字节数回调（进度）
 * @param onLog 诊断日志（引擎接 `Log`，测试可忽略）
 */
internal fun packAppFiles(
    filesDir: File,
    zipOut: ZipOutputStream,
    onBytes: (Long) -> Unit,
    onLog: (String) -> Unit = {},
) {
    appFilesPackingPlan().forEach { packing ->
        val dir = File(filesDir, packing.folder)
        if (!dir.isDirectory) {
            onLog("prepareBackupFile: ${packing.folder} does not exist")
            return@forEach
        }
        onLog("prepareBackupFile: packing ${packing.folder} (${dir.absolutePath})")
        if (packing.recursive) {
            addDirectoryToZip(
                zipOut = zipOut,
                rootDir = dir,
                currentDir = dir,
                // 前缀由**本目录**派生（`packing.entryPrefix`）——绝不能再写死某个目录名
                entryPrefix = packing.entryPrefix,
                onBytes = onBytes,
                onLog = onLog,
            )
        } else {
            // 平铺文件（非递归）
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    addFileToZip(zipOut, file, "${packing.entryPrefix}${file.name}", onBytes, onLog)
                }
            }
        }
    }
}

/** 递归写入一个目录（保留相对层级，用于 skills 与 images 的遗留子目录）。 */
internal fun addDirectoryToZip(
    zipOut: ZipOutputStream,
    rootDir: File,
    currentDir: File,
    entryPrefix: String,
    onBytes: (Long) -> Unit,
    onLog: (String) -> Unit = {},
) {
    currentDir.listFiles()?.forEach { file ->
        if (file.isDirectory) {
            addDirectoryToZip(zipOut, rootDir, file, entryPrefix, onBytes, onLog)
        } else if (file.isFile) {
            val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
            addFileToZip(zipOut, file, "$entryPrefix$relativePath", onBytes, onLog)
        }
    }
}

internal fun addFileToZip(
    zipOut: ZipOutputStream,
    file: File,
    entryName: String,
    onBytes: (Long) -> Unit = {},
    onLog: (String) -> Unit = {},
) {
    val entry = prepareZipEntry(file, entryName, onLog)
    FileInputStream(file).use { fis ->
        zipOut.putNextEntry(entry)
        // 边拷贝边上报：单个大文件（85MB DB / 2.8GB 克隆）内部也要推进，否则进度条会冻住
        copyReporting(fis, zipOut, onBytes)
        zipOut.closeEntry()
    }
}

/**
 * 按可压缩性准备条目（大文件加固 P0，设计稿 §九）：
 * 头部采样判定为不可压缩（git pack / 图片 / 视频等）→ `STORED` 原样拷贝，
 * **DEFLATE 的单核 CPU 成本归零**（实测 24 MB/s × 2.8GB ≈ 数分钟白烧）。
 *
 * Java API 要求 STORED 条目在 `putNextEntry` 前预置 size/crc ⇒ 一次 CRC 预读。
 * 该预读是纯顺序读（无 DEFLATE CPU），远比「1 次读 + 压缩」便宜；进度只按写入
 * 遍计（预读不报，避免双倍计数）。
 */
private fun prepareZipEntry(file: File, entryName: String, onLog: (String) -> Unit): ZipEntry {
    val entry = ZipEntry(entryName)
    if (file.length() < BackupZipStrategy.SAMPLE_SIZE) return entry
    val sample = FileInputStream(file).use { input ->
        val buf = ByteArray(BackupZipStrategy.SAMPLE_SIZE)
        val read = input.read(buf)
        if (read <= 0) null else buf.copyOf(read)
    } ?: return entry
    if (!BackupZipStrategy.shouldStoreUncompressed(sample)) return entry

    val crc = java.util.zip.CRC32()
    var size = 0L
    FileInputStream(file).use { input ->
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            crc.update(buf, 0, n)
            size += n
        }
    }
    onLog("prepareZipEntry: $entryName stored uncompressed ($size bytes, incompressible)")
    return entry.apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc.value
    }
}
