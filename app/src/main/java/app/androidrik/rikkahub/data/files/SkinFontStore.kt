package app.androidrik.rikkahub.data.files

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.UUID

data class ImportedSkinFont(val relativePath: String, val displayName: String)

/** A skin draft must not replace a font still referenced by chat or by the active skin. */
class SkinFontStore(private val context: Context) {
    fun importFont(uri: Uri): ImportedSkinFont = context.contentResolver.openInputStream(uri)?.use {
        importFont(it, FileUtils.getFileNameFromUri(context, uri).orEmpty())
    } ?: error("无法读取字体")

    fun importFont(input: InputStream, displayName: String): ImportedSkinFont {
        val name = displayName.take(256).ifBlank { "自定义字体" }
        val extension = name.substringAfterLast('.', "ttf").lowercase().takeIf { it in setOf("ttf", "otf", "ttc") } ?: "ttf"
        val directory = File(context.filesDir, FileFolders.FONTS).apply { check(isDirectory || mkdirs()) }
        val id = UUID.randomUUID().toString()
        val temp = File(directory, "skin_font_import.$id.tmp")
        val target = File(directory, "skin_font.$id.$extension")
        try {
            val bytes = readLimited(input, MAX_BYTES)
            require(bytes.isNotEmpty()) { "字体文件为空" }
            temp.writeBytes(bytes)
            Typeface.createFromFile(temp)
            check(temp.renameTo(target)) { "无法保存字体" }
            return ImportedSkinFont("${FileFolders.FONTS}/${target.name}", name)
        } finally { temp.delete() }
    }

    companion object { const val MAX_BYTES = 16 * 1024 * 1024 }
}
