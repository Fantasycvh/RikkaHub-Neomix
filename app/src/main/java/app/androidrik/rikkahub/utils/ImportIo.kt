package app.androidrik.rikkahub.utils

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 把 SAF 选中的 URI 复制到 `cacheDir` 下的临时文件（导入流程用）。
 *
 * 抽出来的原因：备份页四条导入分支各自写了一遍同样的
 * `openInputStream(uri)?.use { FileOutputStream(f).use { copyTo } }`。
 *
 * **修正了原写法的静默失败**：原 `openInputStream(uri)?.use{...}` 在流为 null 时不会抛错，
 * 于是留下一个**空临时文件**，下游只会报"不是有效的导出文件"——把"读不到文件"误报成"格式不对"，
 * 排查时极易误解。这里改为显式失败，错误信息说明是读取失败。
 *
 * @param ext 临时文件扩展名（含或不含点都可），如 `"zip"`。
 * @return 临时文件（**调用方负责在用完后删除**）。
 * @throws IllegalStateException 打不开输入流（URI 失效/权限被回收）。
 */
fun Context.copyUriToTempFile(uri: Uri, ext: String): File {
    val suffix = ext.removePrefix(".")
    val tempFile = File(cacheDir, "temp_import_${System.currentTimeMillis()}.$suffix")
    val input = contentResolver.openInputStream(uri)
        ?: error("Cannot open input stream for $uri")
    input.use { source ->
        tempFile.outputStream().use { target -> source.copyTo(target) }
    }
    return tempFile
}
