package app.androidrik.rikkahub.data.sync.webdav

import java.net.URI

/** PROPFIND response order and displayname are not path identities. */
internal fun directWebDavChildren(collectionUrl: String, resources: List<WebDavResourceInfo>): List<WebDavResourceInfo> {
    val parent = URI(collectionUrl.trimEnd('/') + "/")
    val parentPath = parent.path.trimEnd('/')
    val seen = HashSet<String>()
    return resources.mapNotNull { item ->
        val href = parent.resolve(URI(item.href))
        require(href.scheme == parent.scheme && href.rawAuthority == parent.rawAuthority && href.rawQuery == null && href.rawFragment == null) {
            "WebDAV returned a resource outside the requested collection"
        }
        val path = href.path.trimEnd('/')
        if (path == parentPath) return@mapNotNull null
        require(path.startsWith("$parentPath/")) { "WebDAV returned an unrelated resource" }
        val name = path.removePrefix("$parentPath/")
        require(name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') &&
            !name.contains('\\') && !name.contains('\u0000') && seen.add(name)) { "Ambiguous WebDAV child path" }
        item.copy(displayName = name)
    }
}
