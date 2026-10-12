package app.androidrik.rikkahub.data.export

import app.androidrik.workspace.WorkspaceFileEntry
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 工作区批量导出（设计见 `docs/design/design-workspace-multiselect-export.md`）。
 *
 * 分三层，**纯逻辑与 IO 分开**，因为这里的错误几乎都是"静默错"：
 *
 * 1. [zipEntryName] / [defaultWorkspaceArchiveName]：**纯函数**，管"条目名怎么写"。
 * 2. [collectWorkspaceExportPlan]：遍历选中项 → 条目计划（走文件系统，但只读目录树，可用临时目录单测）。
 * 3. [writeWorkspaceZip]：把计划写成 zip（**流式写宿主的 OutputStream，不落临时文件**）。
 *
 * ## 为什么条目名要单独一个纯函数（而不是拼字符串）
 *
 * zip 条目名是**外部可见的路径**：写错会变成 zip-slip（`../` 逃逸）或把绝对路径带进归档。
 * 本仓对话导入那边已有"拒绝 zip-slip 条目"的先例，这里在**写**的方向上同样必须成立——
 * 而且这里的输入（选中项的相对路径）来自 UI，一旦解析出 `..` 就说明上游的路径校验被动过，
 * 应当**拒绝该条目**而不是"尽力修正"。
 */
object WorkspaceExportNames {

    /**
     * 把「相对当前目录的路径」转成 zip 条目名；**非法即拒绝（返回 null）**。
     *
     * 拒绝的三类：空/全空白、绝对路径（以 `/` 或盘符开头）、含 `..` 段。
     * 另外统一分隔符为 `/`（Windows 侧构建/测试才不会写出反斜杠条目）。
     */
    fun zipEntryName(relativePath: String): String? {
        val normalized = relativePath.trim().replace('\\', '/')
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("/")) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return null
        if (segments.any { it == ".." }) return null
        return segments.joinToString("/")
    }

    /** 目录条目名（zip 约定：目录以 `/` 结尾，否则某些解压器会建成普通文件）。 */
    fun zipDirectoryName(relativePath: String): String? = zipEntryName(relativePath)?.plus("/")

    /**
     * 默认归档名：`<当前目录名>.zip`；根目录用 `workspace-<时间戳>.zip`。
     *
     * 用目录名而不是"选中项名"：多选时没有单一名字可用，而"从哪个目录导出的"是用户
     * 唯一能立刻认出来的信息。
     */
    fun defaultArchiveName(directoryPath: String, timestampMillis: Long): String {
        val dirName = directoryPath.trim('/').substringAfterLast('/', "")
        return if (dirName.isBlank()) {
            "workspace-$timestampMillis.zip"
        } else {
            "$dirName-$timestampMillis.zip"
        }
    }

    /**
     * 计算 [path] 相对 [basePath] 的路径（用于 zip 条目名）。
     *
     * 不在 [basePath] 之下时返回 null（调用方据此拒绝该条目）——**不做"尽力而为"的拼接**：
     * 那正是逃逸的来源。
     */
    fun relativeTo(basePath: String, path: String): String? {
        val base = basePath.trim('/')
        val target = path.trim('/')
        if (base.isEmpty()) return target
        if (target == base) return null
        if (!target.startsWith("$base/")) return null
        return target.removePrefix("$base/")
    }
}

/**
 * 目标目录树的**写入抽象**（`T` = 宿主自己的句柄类型：生产是 `DocumentFile`，单测是内存节点）。
 *
 * **为什么要这层**：SAF 树写入此前直接写在仓库里、全程依赖 `DocumentFile` ⇒ **一行都测不了**
 * （JVM 单测里没有 DocumentsProvider，Robolectric 也造不出真实 provider）。
 * 而这段逻辑恰恰是最需要测的：跳过 vs 失败的分档、同名不覆盖、目录缓存、部分成功。
 * 把它抽到一个 5 方法的接口上，就能用内存假实现把整条路径跑一遍。
 *
 * 接口刻意只暴露"结构操作 + 打开写流"——**不暴露 uri/路径**，因为不同宿主的句柄里能拿到什么
 * 并不一样（SAF 给的是 uri，内存假实现给的是节点），逻辑不该依赖那个差异。
 */
interface ExportTreeWriter<T> {
    /** 根句柄（用户授权的那个目录）。 */
    val root: T

    /** 在 [parent] 下按名查找；不存在返回 null。 */
    fun findChild(parent: T, name: String): T?

    /** 是不是目录（用于判断"同名但不是目录"这种无法继续的情形）。 */
    fun isDirectory(handle: T): Boolean

    /** 是不是文件（用于判"同名已存在 ⇒ 跳过"）。 */
    fun isFile(handle: T): Boolean

    /** 建子目录；宿主拒绝时返回 null。 */
    fun createDirectory(parent: T, name: String): T?

    /** 建文件（[mimeType] 由实现按扩展名决定）；宿主拒绝时返回 null。 */
    fun createFile(parent: T, name: String): T?

    /** 打开写流；不支持写时返回 null。 */
    fun openOutputStream(handle: T): OutputStream?
}

/**
 * 把条目计划写进目标目录树（**可用内存假实现完整单测**）。
 *
 * 三档结果的判定规则（与 `WorkspaceBatchResult` 的语义一一对应）：
 * - **成功**：建好文件并写完字节；
 * - **跳过**：目标位置**已有同名文件** ⇒ 不覆盖（覆盖用户既有文件是破坏性的，静默改名又让人找不到）；
 * - **失败**：父目录建不出来（含"同名但不是目录"）、`createFile`/`openOutputStream` 返回 null、
 *   或写入过程中抛异常（磁盘满、provider 抽风）。**单项失败不影响其余项**——这正是"逐个导出
 *   会部分成功"的落地口径。
 *
 * 目录句柄带缓存：同一父目录只 find/create 一次（大目录下省掉重复的 children 查询）。
 */
fun <T> exportPlanToTree(
    plan: WorkspaceExportPlan,
    writer: ExportTreeWriter<T>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): WorkspaceBatchResult {
    val skippedNames = mutableListOf<String>()
    val failedNames = mutableListOf<String>()
    var succeeded = 0
    val total = plan.fileCount
    var done = 0

    val dirCache = mutableMapOf("" to writer.root)

    fun ensureDir(segments: List<String>): T? {
        var current = writer.root
        var path = ""
        segments.forEach { segment ->
            path = if (path.isEmpty()) segment else "$path/$segment"
            val cached = dirCache[path]
            if (cached != null) {
                current = cached
                return@forEach
            }
            val existing = writer.findChild(current, segment)
            val next = when {
                existing == null -> writer.createDirectory(current, segment)
                writer.isDirectory(existing) -> existing
                else -> null // 同名但不是目录：无法继续
            } ?: return null
            dirCache[path] = next
            current = next
        }
        return current
    }

    plan.entries.forEach { entry ->
        val segments = entry.entryName.trimEnd('/').split('/').filter { it.isNotEmpty() }
        if (entry.isDirectory) {
            if (ensureDir(segments) == null) failedNames += entry.entryName
            return@forEach
        }
        val parent = ensureDir(segments.dropLast(1))
        if (parent == null) {
            failedNames += entry.entryName
            return@forEach
        }
        val fileName = segments.last()
        val existing = writer.findChild(parent, fileName)
        if (existing != null && writer.isFile(existing)) {
            skippedNames += entry.entryName
            return@forEach
        }
        runCatching {
            val created = writer.createFile(parent, fileName) ?: error("createFile returned null")
            val out = writer.openOutputStream(created) ?: error("openOutputStream returned null")
            out.use { stream ->
                File(entry.absolutePath).inputStream().use { input -> input.copyTo(stream) }
            }
        }.onSuccess {
            succeeded += 1
            done += 1
            onProgress(done, total)
        }.onFailure {
            failedNames += entry.entryName
        }
    }

    return WorkspaceBatchResult.ofTree(
        succeeded = succeeded,
        skippedNames = skippedNames,
        failedNames = failedNames,
    )
}

/**
 * 批量导出的结果。
 *
 * **为什么要有"跳过"这一档**（而不是只有成功/失败）：逐个导出到目录时会撞上"同名已存在"，
 * 而覆盖用户既有文件是破坏性的、静默改名又会让用户找不到——**跳过**是唯一不越权的处理；
 * 但它必须被计数并报出来，否则用户会以为"N 个都导出好了"。
 */
data class WorkspaceBatchResult(
    val succeeded: Int,
    val skipped: Int,
    val skippedNames: List<String>,
    val failed: Int,
    val failedNames: List<String>,
) {
    val total: Int get() = succeeded + skipped + failed

    companion object {
        fun ofTree(
            succeeded: Int,
            skippedNames: List<String>,
            failedNames: List<String>,
        ) = WorkspaceBatchResult(
            succeeded = succeeded,
            skipped = skippedNames.size,
            skippedNames = skippedNames,
            failed = failedNames.size,
            failedNames = failedNames,
        )
    }
}

/** 计划里的一个条目。 */
data class PlannedExportEntry(
    /** zip 条目名（相对当前目录，已归一化；目录以 `/` 结尾）。 */
    val entryName: String,
    val isDirectory: Boolean,
    val absolutePath: String,
    val sizeBytes: Long,
)

/** 一次批量导出的条目计划（同时也是"这批有多大"的答案，供 UI 先提示再压）。 */
data class WorkspaceExportPlan(
    val entries: List<PlannedExportEntry>,
) {
    val fileCount: Int get() = entries.count { !it.isDirectory }
    val directoryCount: Int get() = entries.count { it.isDirectory }
    val totalBytes: Long get() = entries.filterNot { it.isDirectory }.sumOf { it.sizeBytes }
}

/**
 * 遍历选中项，产出条目计划（**只读目录树**，可用临时目录单测）。
 *
 * 规则（都是刻意的）：
 * - **空目录会被保留**（用户决议）：一个空文件夹出现在 zip 里，比"消失了"更容易解释；
 * - 选中文件夹 ⇒ 递归展开，条目名保留层级；
 * - 单个条目名非法（`..`/绝对路径）⇒ **跳过它并计入 [skippedNames]**，而不是让整批判失败；
 * - 符号链接当作普通文件处理（不跟随）：跟随会引入环与"打包了工作区以外的文件"两类风险。
 *
 * @param root 该存储区的宿主根目录（FILES 区就是 `<workspaceDir>/files`）
 * @param basePath 当前浏览目录（相对 [root]），zip 条目名以它为基准
 */
fun collectWorkspaceExportPlan(
    root: File,
    selection: List<WorkspaceFileEntry>,
    basePath: String,
): Pair<WorkspaceExportPlan, List<String>> {
    val entries = mutableListOf<PlannedExportEntry>()
    val skipped = mutableListOf<String>()

    fun addFileOrDir(file: File, relativePath: String) {
        if (file.isDirectory) {
            val dirName = WorkspaceExportNames.zipDirectoryName(relativePath)
            if (dirName == null) {
                skipped += relativePath
                return
            }
            entries += PlannedExportEntry(
                entryName = dirName,
                isDirectory = true,
                absolutePath = file.absolutePath,
                sizeBytes = 0,
            )
            // 排序：让同一批导出的条目顺序稳定（便于比对两次导出，也让 zip 更可复现）
            file.listFiles()?.sortedBy { it.name }?.forEach { child ->
                addFileOrDir(child, "$relativePath/${child.name}")
            }
        } else {
            val fileName = WorkspaceExportNames.zipEntryName(relativePath)
            if (fileName == null) {
                skipped += relativePath
                return
            }
            entries += PlannedExportEntry(
                entryName = fileName,
                isDirectory = false,
                absolutePath = file.absolutePath,
                sizeBytes = file.length(),
            )
        }
    }

    selection.sortedBy { it.path }.forEach { selected ->
        val relative = WorkspaceExportNames.relativeTo(basePath, selected.path)
        if (relative == null) {
            skipped += selected.path
            return@forEach
        }
        val file = File(root, selected.path.trim('/'))
        if (!file.exists()) {
            // 选中之后被别处删掉了：算跳过，不算失败（并发修改不是错误）
            skipped += selected.path
            return@forEach
        }
        addFileOrDir(file, relative)
    }

    return WorkspaceExportPlan(entries) to skipped
}

/**
 * 把计划写成 zip（**流式**：直接写宿主的 `OutputStream`，不落临时文件）。
 *
 * [onProgress] 报（已完成文件数, 总文件数）——大目录要能显示进度、也要能被取消
 * （调用方在自己的协程里取消即可，`ZipOutputStream` 会随流关闭收尾）。
 */
fun writeWorkspaceZip(
    outputStream: OutputStream,
    plan: WorkspaceExportPlan,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Int {
    val total = plan.fileCount
    var done = 0
    ZipOutputStream(outputStream.buffered()).use { zip ->
        plan.entries.forEach { entry ->
            zip.putNextEntry(ZipEntry(entry.entryName))
            if (!entry.isDirectory) {
                File(entry.absolutePath).inputStream().use { input ->
                    input.copyTo(zip)
                }
                done += 1
                onProgress(done, total)
            }
            zip.closeEntry()
        }
    }
    return done
}
