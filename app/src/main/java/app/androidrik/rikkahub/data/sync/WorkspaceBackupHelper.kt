package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 工作区文件备份的共享逻辑（S3Sync 与 WebDavSync 复用，避免两套实现各自踩坑）。
 *
 * 设计见 `docs/design-backup-workspace-files.md`：
 * - 只备份工作区**文件区**（每个工作区的 files 子目录），**排除 rootfs（linux）与 tmp**
 * - zip 内路径：workspace_files/ + root + / + 相对路径
 * - **不含 rootfs**：rootfs 可用「安装 rootfs」重装，含则备份体积至少 +30MB/工作区
 */
internal object WorkspaceBackupHelper {
    private const val TAG = "WorkspaceBackup"

    /** zip 内工作区条目的前缀（与 FileFolders 各项并列，避免与 upload/skills/fonts 混淆） */
    const val ZIP_PREFIX = "workspace_files/"

    /**
     * 工作区根目录名（位于 filesDir 下）。
     *
     * 单一真源：`RepositoryModule` 创建 WorkspaceManager 时也应使用本常量，
     * 避免备份路径与运行时路径各自硬编码而漂移。
     */
    private const val WORKSPACES_DIR = "workspaces"

    /** 工作区根目录（`<filesDir>/workspaces`） */
    fun workspacesDir(context: Context): File = File(context.filesDir, WORKSPACES_DIR)

    /**
     * 工作区目录名校验：与 `WorkspaceManager.ROOT_NAME_REGEX` 同一规则。
     *
     * **恢复路径必须校验**——备份文件内的 `<root>` 是外部输入，若不校验可含 `..`/绝对路径，
     * 从而逃逸到 app 私有目录之外（zip-slip 的变体）。
     */
    private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

    fun isValidRoot(root: String): Boolean = ROOT_NAME_REGEX.matches(root)

    /**
     * 当前磁盘上存在的工作区 root 列表（目录扫描）。
     *
     * 用扫描而非查 DB：能覆盖「DB 无记录但磁盘有」的情形，且无需注入 DAO。
     * 名字不合法的目录（非本应用所建）跳过。
     */
    fun listWorkspaceRoots(workspaceBaseDir: File): List<String> =
        workspaceBaseDir.listFiles()
            ?.filter { it.isDirectory && isValidRoot(it.name) }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    /**
     * 把某工作区的文件区写入 zip。
     *
     * @param workspaceBaseDir `workspaces` 目录（其下每个子目录名 = root）
     * @param roots 要备份的 root 名列表（通常取自 DB 的 WorkspaceEntity.root）
     * @return 写入的文件数
     */
    fun addWorkspaceFilesToZip(
        zipOut: ZipOutputStream,
        workspaceBaseDir: File,
        roots: Collection<String>,
    ): Int {
        var count = 0
        roots.forEach { root ->
            if (!isValidRoot(root)) {
                Log.w(TAG, "skip invalid workspace root: $root")
                return@forEach
            }
            val filesDir = File(workspaceBaseDir, "$root/files")
            if (!filesDir.isDirectory) return@forEach
            count += addDirRecursive(zipOut, rootDir = filesDir, currentDir = filesDir, entryPrefix = "$ZIP_PREFIX$root/")
        }
        return count
    }

    private fun addDirRecursive(
        zipOut: ZipOutputStream,
        rootDir: File,
        currentDir: File,
        entryPrefix: String,
    ): Int {
        var count = 0
        currentDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                count += addDirRecursive(zipOut, rootDir, file, entryPrefix)
            } else if (file.isFile) {
                val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                zipOut.putNextEntry(ZipEntry("$entryPrefix$relative"))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
                count++
            }
        }
        return count
    }

    /**
     * zip 条目名 → 目标文件；不合法（前缀不符 / root 非法 / zip-slip）返回 null。
     *
     * 解析规则：`workspace_files/<root>/<relative>`；`<relative>` 可含子目录。
     * 安全：canonical 化后必须仍在 workspaces/root/files/ 之内。
     */
    fun resolveRestoreTarget(
        workspaceBaseDir: File,
        zipEntryName: String,
    ): File? {
        if (!zipEntryName.startsWith(ZIP_PREFIX)) return null
        val rest = zipEntryName.removePrefix(ZIP_PREFIX)
        val root = rest.substringBefore('/', missingDelimiterValue = "")
        val relative = rest.substringAfter('/', missingDelimiterValue = "")
        if (root.isEmpty() || relative.isEmpty()) return null
        if (!isValidRoot(root)) {
            Log.w(TAG, "reject invalid root in backup entry: $zipEntryName")
            return null
        }

        val filesDir = File(workspaceBaseDir, "$root/files")
        val target = File(filesDir, relative).canonicalFile
        val boundary = filesDir.canonicalFile
        if (!target.path.startsWith(boundary.path + File.separator)) {
            Log.w(TAG, "reject path-traversal entry: $zipEntryName")
            return null
        }
        return target
    }

    /** 便捷：按 zip 条目消费输入流写入目标（父目录自动创建）。返回是否成功写入。 */
    fun restoreEntry(
        context: Context,
        workspaceBaseDir: File,
        zipEntryName: String,
        write: (OutputStream) -> Unit,
    ): Boolean {
        val target = resolveRestoreTarget(workspaceBaseDir, zipEntryName) ?: return false
        target.parentFile?.mkdirs()
        target.outputStream().use(write)
        return true
    }
}
