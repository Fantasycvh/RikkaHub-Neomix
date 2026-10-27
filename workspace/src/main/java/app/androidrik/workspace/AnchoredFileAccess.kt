package app.androidrik.workspace

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/**
 * 文件工具 resolve-then-open 窗口的**打开后复核**（audit 2026-10-27 #4）。
 *
 * 问题：`WorkspaceFileSystem.resolvePath` 用 `canonicalFile` 解析一次并校验前缀，随后用这个
 * File 直接 IO。解析与 open 之间没有锚定，同一 uid 的 PRoot shell（前台工具与后台任务并行）
 * 可以在窗口内把某一级目录换成指向工作区外的 symlink，让随后的 open 跟着链接逃出去——绕过
 * `writableRoots` 审批层与 `/upload` 只读快照。
 *
 * 本对象做的事：**先 open、再按已打开的 fd 复核**，确认「这条路径现在解析到的 inode」正是
 * 我们打开的 inode，且根到目标的每一级都是真目录（不是链接）。校验通过后继续用同一个 fd
 * 读写 —— 此时即使路径立即被换，写/读也只会落在已锚定的 inode 上；校验失败则关闭流并抛
 * [SecurityException]，不写入任何字节。
 *
 * 为什么不是 openat：Android 公开 API 没有 `openat`/`unlinkat`，只有 `android.system.Os`
 * 的 `open`/`lstat`/`fstat`。所以读/写（数据面）做到 fd 锚定；`move` / `delete` / `rename`
 * 这类**没有 fd 可用**的路径操作只能做「操作前逐级 lstat 复核 + 失败即拒绝」，把窗口压到
 * 极窄但仍非零。残余风险与依据写在 `docs/bug-audit-2026-10-27.md` 的 #4 条目里。
 *
 * 非 Android 的 JVM（单测）没有 `Os`：`Os` 调用抛 "not mocked" ⇒ 退化为「canonical 路径
 * 复核」，语义与修复前一致。测试通过替换 [verifier] 来强制走拒绝路径。
 */
internal object AnchoredFileAccess {

    /** 打开后复核：fd 是我们打开的 inode，且路径每一级都是真目录（非链接）。 */
    fun interface Verifier {
        fun verify(fd: FileDescriptor, target: File, root: File): Boolean
    }

    /** 可替换的复核实现：生产是 [AndroidVerifier]，单测可注入拒绝/放行的假实现。 */
    @Volatile
    var verifier: Verifier = AndroidVerifier

    /** 恢复生产实现（测试收尾用，避免污染其它用例）。 */
    fun resetVerifier() {
        verifier = AndroidVerifier
    }

    // ---- 数据面：读 ----

    /** 打开 [target] 并在校验通过后返回流；校验失败关闭流并抛 [SecurityException]。 */
    fun openRead(root: File, target: File): InputStream {
        val stream = FileInputStream(target)
        try {
            checkVerified(stream.fd, target, root)
        } catch (error: Throwable) {
            runCatching { stream.close() }
            throw error
        }
        return stream
    }

    /** 读整份文件（先开、后校验、再读；大小上限由调用方预检）。 */
    fun readBytes(root: File, target: File): ByteArray = openRead(root, target).use { it.readBytes() }

    // ---- 数据面：写 ----

    /**
     * 以 `rw` 打开（**不截断**）、校验通过后再截断并写入。
     *
     * 顺序很关键：若先截断再校验，校验失败时原文件已被清空；因此这里先在 `rw` 模式下拿 fd、
     * 复核，再在回调里 `setLength(0)`。
     */
    fun writeTruncate(root: File, target: File, block: (RandomAccessFile) -> Unit) {
        val file = RandomAccessFile(target, "rw")
        try {
            checkVerified(file.fd, target, root)
            file.setLength(0)
            block(file)
        } finally {
            runCatching { file.close() }
        }
    }

    /** 逐级创建父目录，每一级创建/存在后都要求是真目录（非链接）。 */
    fun ensureParentDirectories(root: File, target: File) {
        val parent = target.parentFile ?: throw SecurityException("Target has no parent: ${target.name}")
        val rootPath = root.canonicalPath
        val parentPath = parent.canonicalPath
        if (parentPath == rootPath) return
        require(parentPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: ${target.name}"
        }
        val relative = parentPath.substring(rootPath.length + 1)
        var current = File(rootPath)
        for (segment in relative.split(File.separatorChar).filter { it.isNotEmpty() }) {
            current = File(current, segment)
            if (!current.exists()) {
                // createDirectory 的末级不会是链接（链接已存在时其目标存在 ⇒ exists 为 true，
                // 悬空链接时 exists 为 false 但 createDirectory 会 EEXIST 失败）——两种都安全。
                Files.createDirectory(current.toPath())
            }
            val stat = runCatching { androidLstat(current) }.getOrNull()
                ?: continue // 非 Android：交给 open 时的复核兜底
            if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISDIR(stat.st_mode)) {
                throw SecurityException("Path contains a non-directory component: ${current.name}")
            }
        }
    }

    // ---- 无 fd 的路径操作：操作前逐级复核 ----

    /**
     * 复核 [target] 的**祖先链**（root 到父目录）每一级都是真目录。
     *
     * 供 `move` / `delete` / `rename` 在操作前调用：这两类操作没有 fd 可用，只能把
     * resolve 与操作之间的窗口压到「本次调用内」，不能根除（审计 #4 的残余风险）。
     */
    fun verifyAncestors(root: File, target: File): Boolean {
        val rootPath = root.canonicalPath
        val targetPath = target.absolutePath
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return false
        if (targetPath == rootPath) return true
        val relative = targetPath.substring(rootPath.length + 1)
        var current = File(rootPath)
        val segments = relative.split(File.separatorChar).filter { it.isNotEmpty() }
        for (index in 0 until segments.lastIndex) {
            current = File(current, segments[index])
            if (!isRealDirectory(current)) return false
        }
        return true
    }

    private fun isRealDirectory(file: File): Boolean {
        val stat = runCatching { androidLstat(file) }.getOrNull()
            ?: return runCatching {
                Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())
            }.getOrDefault(false)
        return !OsConstants.S_ISLNK(stat.st_mode) && OsConstants.S_ISDIR(stat.st_mode)
    }

    private fun checkVerified(fd: FileDescriptor, target: File, root: File) {
        if (!verifier.verify(fd, target, root)) {
            throw SecurityException("File operation escaped workspace root via a symlink: ${target.name}")
        }
    }

    private fun androidLstat(file: File): StructStat = Os.lstat(file.absolutePath)

    // ---- 生产实现 ----

    private object AndroidVerifier : Verifier {
        override fun verify(fd: FileDescriptor, target: File, root: File): Boolean {
            // 非 Android（单测）没有 Os：fstat 抛异常 ⇒ 退化路径复核
            val fdStat = try {
                Os.fstat(fd)
            } catch (_: ErrnoException) {
                return pathOnlyVerify(target, root)
            } catch (_: Throwable) {
                return pathOnlyVerify(target, root)
            }
            val rootPath = root.canonicalPath
            val targetPath = target.absolutePath
            if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return false
            if (targetPath == rootPath) return false
            val relative = targetPath.substring(rootPath.length + 1)
            var current = File(rootPath)
            val segments = relative.split(File.separatorChar).filter { it.isNotEmpty() }
            for (index in 0 until segments.lastIndex) {
                current = File(current, segments[index])
                val stat = try {
                    Os.lstat(current.absolutePath)
                } catch (_: ErrnoException) {
                    return false
                }
                if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISDIR(stat.st_mode)) return false
            }
            val last = File(current, segments.last())
            val stat = try {
                Os.lstat(last.absolutePath)
            } catch (_: ErrnoException) {
                return false
            }
            if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISREG(stat.st_mode)) return false
            return stat.st_dev == fdStat.st_dev && stat.st_ino == fdStat.st_ino
        }
    }

    /**
     * 无 `android.system.Os` 时的退化复核（仅单测/非 Android JVM）：路径仍 canonical 在 root 内、
     * 末级不是链接且为普通文件。**不比较 inode**，所以生产环境不会走这条（Android 上 fstat 可用）。
     */
    private fun pathOnlyVerify(target: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalFile.toPath()
        val path = target.toPath()
        val canonical = path.toRealPath()
        !Files.isSymbolicLink(path) &&
            canonical.startsWith(rootPath) &&
            Files.isRegularFile(canonical, NOFOLLOW_LINKS)
    }.getOrDefault(false)
}
