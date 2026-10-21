package app.androidrik.rikkahub.data.files

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val TAG = "AtomicFileWrite"

/**
 * 原子写入临时文件名前缀：`atomic-<目标名>.<随机>.tmp`。
 *
 * 目录扫描（`FilesManager.syncFolder` / `countChatFiles`）要跳过这类**未登记**的中间文件；
 * 进程被杀时可能残留（正常路径在 finally 里已删）。
 */
internal const val ATOMIC_TEMP_PREFIX = "atomic-"

/**
 * 临时文件 + rename 的原子写入；**任何失败都返回 false 而非抛出**。
 *
 * 出处：上游 `9f02586d` 修的是「技能保存失败导致崩溃」——`target.writeText(content)` 在
 * IO 失败（mkdirs 不成、磁盘写满、路径异常）时抛异常，而调用方 `SkillDetailVM.saveFile`
 * 在 IO 协程里没有 try/catch ⇒ 异常冒到 viewModelScope，**保存失败 = 应用崩溃**；
 * 且直接写目标会留下半截文件（原内容已毁）。
 *
 * **与上游的一处差异**：这里检查 `renameTo` 的返回值（上游忽略）。本函数存在的意义就是
 * 「把保存失败如实报出去」，忽略返回值会让失败静默变成成功。
 *
 * 临时文件由 [createAtomicTempFile] 生成**唯一名**（不是固定 `.name.tmp`）：并发写同一
 * 目标时两个写入者不会共用/互删临时文件，见该函数注释。
 *
 * 顶层函数而非 `SkillManager` 成员：便于 JVM 单测用临时文件直接驱动（管理器需要 Context）。
 */
internal fun writeFileAtomically(target: File, content: String): Boolean =
    writeAtomically(target) { tempFile -> tempFile.writeText(content) }

/**
 * 字节版原子写入（附件/图片落盘用）：先写同目录临时文件，再 rename 到目标名。
 *
 * 失败（mkdirs 失败 / 写入异常 / rename 失败）返回 false，并保证**临时文件被清理**、
 * **目标文件不被创建或破坏**——调用方据此不登记 `managed_files`，也不会留下半成品附件。
 */
internal fun writeBytesAtomically(target: File, bytes: ByteArray): Boolean =
    writeAtomically(target) { tempFile -> tempFile.writeBytes(bytes) }

/**
 * 输入流版原子写入（SAF/相册 `content://` 拷贝用）：由 [input] 读多少写多少，
 * **不关闭**调用方的流（关闭由调用方 `use` 负责）。
 *
 * 语义同 [writeBytesAtomically]：失败（含 [input] 为 null，即 SAF 打不开流）返回 false
 * 且不留半成品——P1-7 就是「流打不开还登记 0 字节附件」。
 */
internal fun writeStreamAtomically(target: File, input: InputStream?): Boolean {
    if (input == null) return false
    return writeAtomically(target) { tempFile ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }
}

/**
 * 为 [target] 在**同目录**创建一个唯一临时文件（`File.createTempFile`）。
 *
 * 为什么不能用固定名（`.name.tmp`）：并发写同一个目标时（重复导出的同名文件、同一附件的
 * 并行落盘），两个写入者会命中同一个临时文件——A 正在写、B 截断重写；先失败的一方
 * `finally` 还会把**另一方正在用的**临时文件删掉，导致 rename 失败或内容串味。
 * 唯一名 + 各自清理自己的临时文件即可隔离。
 *
 * 放**同目录**而不是 cacheDir：rename 必须同文件系统才是原子的（跨分区会退化成复制）。
 */
internal fun createAtomicTempFile(target: File): File {
    val parent = target.parentFile
        ?: throw IOException("Target has no parent directory: ${target.absolutePath}")
    // 前缀固定加 `atomic-`：既保证长度 ≥3（createTempFile 要求），也便于目录扫描跳过/排查识别
    return File.createTempFile("$ATOMIC_TEMP_PREFIX${target.name}.", ".tmp", parent)
}

private inline fun writeAtomically(target: File, write: (File) -> Unit): Boolean {
    val parent = target.parentFile ?: return false
    var tempFile: File? = null
    return try {
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = createAtomicTempFile(target)
        tempFile = temp
        write(temp)
        temp.renameTo(target)
    } catch (e: Exception) {
        Log.w(TAG, "failed to save ${target.absolutePath}", e)
        false
    } finally {
        tempFile?.takeIf { it.exists() }?.delete()
    }
}
