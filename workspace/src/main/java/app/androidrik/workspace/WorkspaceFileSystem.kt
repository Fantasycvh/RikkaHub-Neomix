package app.androidrik.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class WorkspaceFileSystem(
    private val config: WorkspaceConfig = WorkspaceConfig(),
) {
    fun list(root: File, path: String = ""): List<WorkspaceFileEntry> {
        val dir = resolvePath(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles()
            .orEmpty()
            .filter { !it.name.startsWith(".l2s.") }
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(config.maxListEntries)
            .map { it.toEntry(root) }
    }

    fun readText(root: File, path: String, charset: Charset = StandardCharsets.UTF_8): String {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= config.maxReadBytes) {
            "File is too large to read: ${file.length()} bytes"
        }
        // 先 open 后按 fd 复核（审计 #4）：窗口内路径被换成指向工作区外的链接时拒绝，
        // 不会静默把外部文件内容读进上下文/导出
        return String(AnchoredFileAccess.readBytes(root, file), charset)
    }

    /** 导出/分享：与 [readText] 同一条 fd 复核的读路径。 */
    fun exportTo(root: File, path: String, outputStream: OutputStream) {
        val file = resolvePath(root, path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        AnchoredFileAccess.openRead(root, file).use { input ->
            outputStream.use { out -> input.copyTo(out) }
        }
    }

    fun writeText(
        root: File,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
    ): WorkspaceFileEntry {
        val bytes = text.toByteArray(charset)
        require(bytes.size <= config.maxWriteBytes) {
            "Content is too large to write: ${bytes.size} bytes"
        }
        val file = resolvePath(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        AnchoredFileAccess.ensureParentDirectories(root, file)
        // rw 打开（不截断）→ fd 复核 → 才截断写入：复核失败时原文件内容完好（审计 #4）
        AnchoredFileAccess.writeTruncate(root, file) { it.write(bytes) }
        return file.toEntry(root)
    }

    /** Reserve the output with CREATE_NEW: concurrent imports never truncate an existing file. */
    fun importBytes(root: File, path: String, inputStream: InputStream): WorkspaceFileEntry = inputStream.use { input ->
        // 导入是「落进这个目录」的操作：父目录允许 root 内的目录别名（如 Linux 区 bin→usr/bin，
        // 用户能浏览到它们），只有最终文件名本身要求不是链接。rename/move/delete 才用逐段
        // 拒绝链接的 mutationPath（它们操作既有节点，绝不能踩到链接上）。
        val file = importPath(root, path)
        require(!Files.isSymbolicLink(file.toPath())) { "Symlinks are not allowed in file operations: $path" }
        require(file.parentFile?.isDirectory == true) { "Destination directory does not exist" }
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isNotEmpty()) ".$it" else "" }
        var n = 0
        while (true) {
            checkInterrupted()
            val target = if (n == 0) file else File(file.parentFile, "$stem ($n)$ext")
            // CREATE_NEW 占位（O_CREAT|O_EXCL：末级是链接时同样 EEXIST），再按 fd 复核后写入
            try {
                Files.createFile(target.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                n++
                continue
            }
            try {
                AnchoredFileAccess.writeTruncate(root, target) { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkInterrupted()
                        val size = input.read(buffer)
                        if (size < 0) break
                        out.write(buffer, 0, size)
                    }
                }
                return@use target.toEntry(root)
            } catch (error: Throwable) {
                // 防御性清理：校验后并发把祖先换成外部链接的话，target 可能已指向工作区外。
                // 删除半成品前重新确认「不是链接且 canonical 仍在 root 内」，否则不碰它
                //（缩小但不能根除 TOCTOU 窗口；强保证需要目录 FD 锚定的 openat/unlinkat）。
                if (isSafeToDelete(target, root)) target.delete()
                throw error
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable")
    }

    /** 失败清理前的最后一道确认：目标仍是 root 内的普通文件（非链接）才允许删除。 */
    private fun isSafeToDelete(target: File, root: File): Boolean = runCatching {
        val path = target.toPath()
        val base = root.canonicalFile.toPath()
        Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path) &&
            Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            path.toRealPath().startsWith(base)
    }.getOrDefault(false)

    /** UI rename is same-directory only and never replaces a destination (files or directories). */
    @Synchronized
    fun rename(root: File, path: String, name: String): WorkspaceFileEntry {
        validateName(name)
        val source = mutationPath(root, path)
        require(Files.exists(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Source does not exist: $path" }
        val target = mutationPath(root, source.parentFile!!.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')
            .let { if (it.isEmpty()) name else "$it/$name" })
        require(AnchoredFileAccess.verifyAncestors(root, source)) {
            "Path contains a symlinked component: $path"
        }
        if (source == target) return source.toEntry(root)
        // Files.move without REPLACE_EXISTING rejects occupied destinations, including dangling links.
        Files.move(source.toPath(), target.toPath())
        return target.toEntry(root)
    }

    internal fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && name.length <= 255 &&
            name.none { it == '/' || it == '\\' || it == '\u0000' || it.code < 32 } &&
            !name.startsWith(".l2s.")) { "Invalid file name: $name" }
    }

    /** Mutation paths must be literal relative paths, with no symlink at any existing component. */
    internal fun mutationPath(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && !path.startsWith('/') && !path.contains('\\')) {
            "A relative file path is required"
        }
        val parts = path.split('/')
        parts.forEach(::validateName)
        val base = root.canonicalFile
        var current = base
        for (part in parts) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { "Symlinks are not allowed in file operations: $path" }
            require(current.canonicalPath.startsWith(base.path + File.separator)) { "Path escapes workspace root" }
        }
        return current
    }

    /**
     * 导入目标路径：允许路径中已存在的 **root 内目录别名**（如 Linux 区 bin→usr/bin 的目录
     * 链接——用户能浏览到它们，导入却被逐段拒绝是回归）。命中的链接先 canonical 解析并
     * 确认仍包含于 root（别名永远出不去）；末级文件名本身不允许是链接。TOCTOU 限定：并发
     * 把祖先换成指向外部的链接仍可能逃逸（需要 openat/目录 FD 才能根绝），本方法只承诺
     * 「静态路径下不逃逸」。
     */
    internal fun importPath(root: File, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute && !path.startsWith('/') && !path.contains('\\')) {
            "A relative file path is required"
        }
        val parts = path.split('/')
        parts.forEach(::validateName)
        val base = root.canonicalFile
        var current = base
        for ((index, part) in parts.withIndex()) {
            val next = File(current, part)
            if (index < parts.lastIndex && Files.isSymbolicLink(next.toPath())) {
                // 中间段是链接：解析真实目录并确认仍在 root 内（目录别名；指向文件的别名
                // 会在 parentFile.isDirectory 检查中失败）
                val resolved = next.canonicalFile
                require(resolved.path.startsWith(base.path + File.separator)) { "Path escapes workspace root: $path" }
                current = resolved
            } else {
                current = next
            }
        }
        require(current.canonicalPath.startsWith(base.path + File.separator)) { "Path escapes workspace root" }
        return current
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("File operation cancelled")
    }

    fun delete(root: File, path: String, recursive: Boolean = false): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = mutationPath(root, path)
        // [FIX] "./"（或 "/"、" ." 等变体）可绕过上面的字面量检查，resolvePath 会解析到
        // 工作区根目录 → recursive=true 时 deleteRecursively 删除整个工作区。
        // 用 canonical 对比根目录，任何等价写法都拒绝（移植自 rikkahub-turbo 9677cd4）。
        require(file.canonicalFile != root.canonicalFile) { "Refusing to delete workspace root" }
        // 解析与操作之间无 fd 可用，只能在删之前再逐级复核一次（窗口收窄，未根除——审计 #4）
        require(AnchoredFileAccess.verifyAncestors(root, file)) {
            "Path contains a symlinked component: $path"
        }
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        return if (Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(recursive) { "Directory delete requires recursive = true" }
            // deleteRecursively 会跟随目录内的符号链接遍历（link → 工作区外目录时删到外部）。
            // 改用 walkFileTree 默认不跟随链接：目录递归删、符号链接只 unlink 本身。
            var allDeleted = true
            Files.walkFileTree(
                file.toPath(),
                java.util.EnumSet.noneOf(java.nio.file.FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : java.nio.file.SimpleFileVisitor<Path>() {
                    override fun visitFile(target: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                        checkInterrupted()
                        // NOFOLLOW 遍历下 target 可能是链接/文件，直接 delete 只断链本身
                        if (!Files.deleteIfExists(target)) allDeleted = false
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(target: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                        checkInterrupted()
                        error?.let { throw it }
                        if (!Files.deleteIfExists(target)) allDeleted = false
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
            allDeleted
        } else {
            file.delete()
        }
    }

    fun move(root: File, source: String, target: String, overwrite: Boolean = false): WorkspaceFileEntry {
        require(source.isNotBlank() && source != ".") { "Refusing to move workspace root" }
        val sourceFile = resolvePath(root, source)
        val targetFile = resolvePath(root, target)
        // [FIX] 同 delete："./" 变体可解析到根目录 → renameTo 移动整个工作区
        //（移植自 rikkahub-turbo 9677cd4）。
        require(sourceFile.canonicalFile != root.canonicalFile) { "Refusing to move workspace root" }
        require(
            AnchoredFileAccess.verifyAncestors(root, sourceFile) &&
                AnchoredFileAccess.verifyAncestors(root, targetFile)
        ) { "Path contains a symlinked component: $source -> $target" }
        require(sourceFile.exists()) { "Source does not exist: $source" }
        if (targetFile.exists()) {
            require(overwrite) { "Target already exists: $target" }
            if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
        }
        targetFile.parentFile?.mkdirs()
        require(sourceFile.renameTo(targetFile)) {
            "Failed to move $source to $target"
        }
        return targetFile.toEntry(root)
    }

    fun glob(root: File, pattern: String, path: String = ""): List<WorkspaceFileEntry> {
        require(pattern.isNotBlank()) { "Glob pattern is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        return walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .filter { matcher.matches(root.toPath().relativize(it).normalizeForMatch()) }
                .take(config.maxListEntries)
                .map { it.toFile().toEntry(root) }
                .toList()
        }
    }

    fun grep(
        root: File,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
    ): List<WorkspaceSearchMatch> {
        require(query.isNotBlank()) { "Search query is required" }
        val start = resolvePath(root, path)
        require(start.exists()) { "Path does not exist: $path" }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val matcher = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        val includeMatcher = includeGlob
            ?.takeIf { it.isNotBlank() }
            ?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }

        val results = mutableListOf<WorkspaceSearchMatch>()
        walk(start) { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { !it.toFile().name.startsWith(".l2s.") }
                .forEach { path ->
                    if (results.size >= config.maxSearchResults) return@forEach
                    if (includeMatcher != null &&
                        !includeMatcher.matches(root.toPath().relativize(path).normalizeForMatch())
                    ) {
                        return@forEach
                    }
                    val file = path.toFile()
                    if (file.length() > config.maxReadBytes) return@forEach
                    file.useLines(StandardCharsets.UTF_8) { lines ->
                        lines.forEachIndexed { index, line ->
                            if (results.size >= config.maxSearchResults) return@useLines
                            if (matcher.containsMatchIn(line)) {
                                results += WorkspaceSearchMatch(
                                    path = file.relativePath(root),
                                    line = index + 1,
                                    text = line,
                                )
                            }
                        }
                    }
                }
        }
        return results
    }

    private fun <T> walk(start: File, block: (Sequence<Path>) -> T): T =
        Files.walk(start.toPath()).use { stream ->
            block(stream.iterator().asSequence())
        }

    private fun resolvePath(root: File, path: String): File {
        root.mkdirs()
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }

        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        val rootPath = rootFile.path
        val targetPath = target.path
        require(targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
            "Path escapes workspace root: $path"
        }
        return target
    }

    fun resolve(root: File, path: String): File = resolvePath(root, path)

    private fun File.toEntry(root: File): WorkspaceFileEntry = WorkspaceFileEntry(
        path = relativePath(root),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0L,
        updatedAt = lastModified(),
    )

    private fun File.relativePath(root: File): String {
        val rootCanonical = root.canonicalFile
        val parentCanonical = (parentFile ?: rootCanonical).canonicalFile
        return File(parentCanonical, name).relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
    }

    private fun Path.normalizeForMatch(): Path =
        FileSystems.getDefault().getPath(relativeToString())

    private fun Path.relativeToString(): String =
        joinToString("/") { it.name }
}
