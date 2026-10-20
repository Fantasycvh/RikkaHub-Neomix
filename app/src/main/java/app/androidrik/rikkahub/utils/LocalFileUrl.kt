package app.androidrik.rikkahub.utils

import android.content.Context
import java.io.File

/**
 * 本地文件 URL / 路径归一化：备份恢复、换设备、release↔debug 跨包恢复后，存档里记的
 * 绝对路径仍然指向**另一个包**的私有目录（uid 不同、路径不可读甚至不存在）。
 *
 * 做法：识别 `.../files/<folder>/<相对路径>` 这一段，重写到**当前** filesDir 下的同名文件。
 * 只改显示/加载层，不迁移数据；识别不出的路径原样返回。
 *
 * 两处必须都支持：
 * - `file://` URL：聊天附件（upload）、头像（avatars）用这种；
 * - **裸绝对路径**：会话内生图工具（`generate_image`）把 `File.absolutePath` 直接写进了
 *   工具输出 JSON（没有 `file://` 前缀）。这正是「备份恢复后生图显示『已失效或无法读取』」
 *   的根因：文件已经被恢复到当前包的 `files/images/`，但记录还指着旧包。
 */
internal fun normalizeLocalFilePath(
    filesDir: File,
    path: String,
    folders: List<String>,
): String {
    if (path.isBlank()) return path
    // http(s)/content:// 等非本地路径不碰
    if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("content://")) {
        return path
    }
    val marked = folders.firstNotNullOfOrNull { folder ->
        val marker = "/files/$folder/"
        val index = path.indexOf(marker)
        if (index >= 0) folder to path.substring(index + marker.length) else null
    } ?: return path
    val (folder, relative) = marked
    if (relative.isBlank()) return path
    val baseDir = File(filesDir, folder)
    val target = baseDir.resolve(relative)
    // 防目录穿越：解析结果必须仍在目标目录内，否则原样返回
    val canonicalBase = runCatching { baseDir.canonicalFile }.getOrNull() ?: return path
    val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return path
    if (!canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) return path
    return canonicalTarget.absolutePath
}

/** 聊天附件/头像使用的目录（历史行为保持不变）。 */
private val CHAT_FILE_FOLDERS = listOf("avatars", "upload")

/** 会话内生图产物目录（`generate_image` 工具输出的绝对路径）。 */
private val GENERATED_IMAGE_FOLDERS = listOf("images")

/** 附件/头像归一化：识别成功时返回带 `file://` 的 URL，否则原样返回。 */
fun normalizeLocalFileUrl(filesDir: File, url: String): String {
    val path = normalizeLocalFilePath(filesDir, url, CHAT_FILE_FOLDERS)
    if (path == url) return url
    return "file://" + path
}

fun Context.normalizeLocalFileUrl(url: String): String =
    normalizeLocalFileUrl(filesDir, url)

/**
 * 会话内生图路径归一化：工具输出里存的是**裸绝对路径**，这里按当前安装的 filesDir 重写。
 * 路径没变或识别不出时原样返回（Coil 对裸路径与 file:// 都能加载）。
 */
fun normalizeGeneratedImagePath(filesDir: File, path: String): String =
    normalizeLocalFilePath(filesDir, path, GENERATED_IMAGE_FOLDERS)

fun Context.normalizeGeneratedImagePath(path: String): String =
    normalizeGeneratedImagePath(filesDir, path)
