package app.androidrik.rikkahub.data.sync.s3

/**
 * 循环取完 S3 `ListObjectsV2` 的全部页（按服务端返回顺序）。
 *
 * 为什么需要：`S3Sync.listBackupFiles` 原来只请求首页 1000 条，对象数超过 1000 后
 * 旧备份在列表里**静默消失**（用户以为备份丢了），而增量仓库的对象数只会有增无减。
 *
 * 停止条件（任一满足即停，返回已取到的页）：
 * - 本页 `isTruncated == false`（正常结束）；
 * - 本页没有 continuation token（空 token 也不能再发，否则会退回首页）；
 * - token 与**本页请求时用的** token 相同——服务端不前进时再取就是死循环；
 * - 已取满 [maxPages] 页——给异常服务端兜底，避免无限请求。
 *
 * 抽成纯函数（无 Android 依赖）是为了能用假 fetch 单测翻页语义，不必起 HTTP。
 */
internal suspend fun collectS3Pages(
    maxPages: Int = 10_000,
    fetch: suspend (continuationToken: String?) -> S3ListResult,
): List<S3ListResult> {
    val pages = mutableListOf<S3ListResult>()
    var token: String? = null

    while (pages.size < maxPages) {
        val page = fetch(token)
        pages += page

        val next = page.nextContinuationToken
        if (!page.isTruncated || next.isNullOrBlank()) break
        // 服务端把同一个 token 还回来：再取还是同一页，直接停，避免死循环。
        if (next == token) break
        token = next
    }

    return pages
}
