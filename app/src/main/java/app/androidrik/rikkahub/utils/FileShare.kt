package app.androidrik.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.androidrik.rikkahub.R
import java.io.File

/**
 * 分享本地文件的公共实现（`Export.kt` 与「导出管理」页共用）。
 *
 * 原本这两个函数是 `Export.kt` 的 `private`；导出留存区要有"再分享"能力，
 * 故提为公共工具，避免两处各写一份。
 */

/** 取某文件的 FileProvider URI（authority 由包名推导，与 Manifest 声明一致）。 */
fun Context.fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
    this,
    "$packageName.fileprovider",
    file,
)

/** 单个文件走系统分享面板。 */
fun Context.shareFile(uri: Uri, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(
        Intent.createChooser(intent, getString(R.string.chat_page_export_share_via))
    )
}

/** 多文件一起分享（长对话分页导出的多张 PNG 用它）。 */
fun Context.shareFiles(uris: List<Uri>, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(
        Intent.createChooser(intent, getString(R.string.chat_page_export_share_via))
    )
}
