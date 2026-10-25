package app.androidrik.rikkahub.data.storage

import app.androidrik.common.android.StorageNames

/**
 * 缓存目录名（相对 `cacheDir` / `filesDir`）。
 *
 * 缓存管理页、启动清理与各写入方共用同一份常量，避免路径口径漂移。
 * 写入方见各项注释；新增分类时先在这里加常量，再改 [StorageUsageManager]。
 */
object StoragePaths {
    /** Coil 3 默认磁盘缓存（ImageLoader 未显式配置 diskCache）。 */
    const val IMAGE_CACHE = "image_cache"

    /** `Context.appTempFolder`（common/ContextUtil.kt）。 */
    const val TEMP = StorageNames.TEMP

    /** `WebViewContentCache`。 */
    const val WEBVIEW = "webview_content"

    /** `WorkspaceFileBrowser` 的 SVG 缩略图。 */
    const val THUMBS = "workspace_svg_thumb"

    /** `TectonicNative` 的 TeX 格式缓存。 */
    const val TEX = "texfmt"
}
