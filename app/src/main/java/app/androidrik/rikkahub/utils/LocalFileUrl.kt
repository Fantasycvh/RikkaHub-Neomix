package app.androidrik.rikkahub.utils

import android.content.Context
import java.io.File

/**
 * file:// 本地文件 URL 归一化：备份恢复/跨包迁移（release↔debug）后存档里的绝对路径
 * 指向别的包私有目录（uid 不同不可读）——取 `.../files/upload/<name>` 文件名段，
 * 重写到当前包 filesDir/upload 下同名文件。仅显示层兜底，不迁移数据；非 upload 路径原样返回。
 * 头像（UIAvatar）与消息附件（ChatMessage Image/Document、AttachmentChips）共用。
 */
fun normalizeLocalFileUrl(filesDir: File, url: String): String {
    if (!url.startsWith("file://")) return url
    val marker = "/files/upload/"
    val index = url.indexOf(marker)
    if (index < 0) return url
    val name = url.substring(index + marker.length)
    // 字符串拼接（等价 Uri.fromFile 的 "file://" + 绝对路径），不依赖 android.net.Uri，纯 JVM 可单测
    return "file://" + File(filesDir, "upload").resolve(name).absolutePath
}

fun Context.normalizeLocalFileUrl(url: String): String =
    normalizeLocalFileUrl(filesDir, url)
