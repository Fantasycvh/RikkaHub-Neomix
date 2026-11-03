package app.androidrik.rikkahub.data.sync.webdav

import java.net.URLEncoder

/**
 * WebDAV 路径段 percent-encode（UTF-8，空格用 %20 而非 +）。
 * 文件名含空格/中文/#/?/& 时 URL 非法会导致同步失败。
 */
internal fun encodeWebDavPathSegment(segment: String): String =
    URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

/**
 * 把远端根目录（`config.path`）与各相对路径**按 `/` 拆成逐段列表**。
 *
 * 为什么必须拆段：旧实现把 `config.path` 与每个参数当成**一整段**编码，含 `/` 的
 * `v3/objects/aa` 会变成 `v3%2Fobjects%2Faa`——服务端只看到一个名为 "v3/objects/aa" 的
 * 单级目录，多级目录既建不出来也访问不到（增量仓库必须写 `v3/objects/<aa>/`，见设计稿 §2.4）。
 *
 * 只 trim 首尾 `/`、**不 trim 空格**：远端文件名里的空格是合法的（"my backup.zip"），
 * trim 掉会改变指向；空段（连续 `/`、纯 `/` 的路径）直接丢弃。
 */
internal fun webDavPathSegments(configPath: String?, relativePaths: List<String>): List<String> =
    // config.path 为空白时视为「未配置」（与旧实现 `takeIf { it.isNotBlank() }` 一致）；
    // 相对路径参数不做此过滤——文件名本身可能含前导/尾随空格。
    (listOfNotNull(configPath?.takeUnless { it.isBlank() }) + relativePaths)
        .flatMap { raw -> raw.trim('/').split('/') }
        .filter { it.isNotEmpty() }

/**
 * 由 base URL + 逐段路径拼出最终 URL：base 去掉尾部 `/`，各段分别编码后用 `/` 连接。
 * 逐段编码是关键——分隔符 `/` 本身不能进编码器，否则又会退回 `%2F` 的单级路径。
 */
internal fun buildWebDavUrl(baseUrl: String, segments: List<String>): String {
    val base = baseUrl.trimEnd('/')
    if (segments.isEmpty()) return base
    return "$base/${segments.joinToString("/") { encodeWebDavPathSegment(it) }}"
}

/**
 * 各级前缀：`[a]`、`[a, b]`、`[a, b, c]`。
 *
 * 为什么需要：WebDAV 的 MKCOL 只能逐级创建（父目录不存在时服务端返回 409），
 * 所以 `mkdirs("v3/objects/aa")` 必须按这个顺序一级级确保存在；抽成纯函数便于单测。
 */
internal fun cumulativeSegments(segments: List<String>): List<List<String>> =
    segments.indices.map { segments.subList(0, it + 1).toList() }
