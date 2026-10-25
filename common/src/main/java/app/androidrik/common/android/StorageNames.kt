package app.androidrik.common.android

/**
 * 应用缓存目录名（相对 `cacheDir`）。
 *
 * 缓存管理（app 模块）与 [appTempFolder] 共用同一份常量，避免两处各写一个字符串导致口径漂移。
 */
object StorageNames {
    const val TEMP = "temp"
}
