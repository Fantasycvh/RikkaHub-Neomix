package app.androidrik.rikkahub.utils

import android.content.Context
import java.io.File

/**
 * file:// 本地文件 URL 归一化：备份恢复/跨包迁移（release↔debug）后存档里的绝对路径
 * 指向别的包私有目录（uid 不同不可读）——取 `.../files/upload/<name>` 或 `.../files/avatars/<name>` 
 * 文件名段，重写到当前包 filesDir 下对应文件夹的同名文件。仅显示层兜底，不迁移数据；
 * 非 upload/avatars 路径原样返回。
 * 头像（UIAvatar）与消息附件（ChatMessage Image/Document、AttachmentChips）共用。
 */
fun normalizeLocalFileUrl(filesDir: File, url: String): String {
    if (!url.startsWith("file://")) return url
    
    // Check for avatars path first
    val avatarsMarker = "/files/avatars/"
    val avatarsIndex = url.indexOf(avatarsMarker)
    if (avatarsIndex >= 0) {
        val name = url.substring(avatarsIndex + avatarsMarker.length)
        return "file://" + File(filesDir, "avatars").resolve(name).absolutePath
    }
    
    // Then check for upload path
    val uploadMarker = "/files/upload/"
    val uploadIndex = url.indexOf(uploadMarker)
    if (uploadIndex >= 0) {
        val name = url.substring(uploadIndex + uploadMarker.length)
        return "file://" + File(filesDir, "upload").resolve(name).absolutePath
    }
    
    // Not a recognized path, return as-is
    return url
}

fun Context.normalizeLocalFileUrl(url: String): String =
    normalizeLocalFileUrl(filesDir, url)
