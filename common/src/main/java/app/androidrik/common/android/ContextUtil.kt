package app.androidrik.common.android

import android.content.Context
import java.io.File

val Context.appTempFolder: File
    get() {
        val dir = File(cacheDir, StorageNames.TEMP)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

fun Context.getCacheDirectory(namespace: String): File {
    val dir = File(cacheDir, "disk_cache/$namespace")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}
