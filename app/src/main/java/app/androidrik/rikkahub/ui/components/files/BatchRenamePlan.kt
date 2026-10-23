package app.androidrik.rikkahub.ui.components.files

/** Stable identity is separate from the visible filename (managed files use database IDs). */
data class RenameItem(val key: String, val name: String, val isDirectory: Boolean = false)

internal fun validateRenameName(name: String) {
    require(name.isNotBlank() && name == name.trim()) { "名称不能为空或以空格开头、结尾" }
    require(name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }) {
        "名称不能包含路径分隔符或控制字符"
    }
    require(name.toByteArray(Charsets.UTF_8).size <= 255) { "名称过长（最多 255 字节）" }
}

/** Same ordered input produces the exact plan shown in the confirmation preview. */
internal fun buildRenamePlan(
    items: List<RenameItem>,
    baseName: String,
    startIndex: Int = 1,
    keepExtension: Boolean = true,
): Map<String, String> {
    require(items.isNotEmpty()) { "请选择文件" }
    require(items.map { it.key }.distinct().size == items.size) { "选择中存在重复项" }
    validateRenameName(baseName)
    require(startIndex >= 0 && startIndex.toLong() + items.size - 1 <= Int.MAX_VALUE) { "起始序号超出范围" }
    val names = items.mapIndexed { index, item ->
        val name = if (items.size == 1) baseName else {
            val dot = item.name.lastIndexOf('.')
            val extension = if (keepExtension && !item.isDirectory && dot > 0 && dot < item.name.lastIndex) {
                item.name.substring(dot)
            } else ""
            "${baseName}_${startIndex + index}$extension"
        }
        validateRenameName(name)
        name
    }
    require(names.distinct().size == names.size) { "新名称不能重复" }
    return items.mapIndexed { index, item -> item.key to names[index] }.toMap()
}
