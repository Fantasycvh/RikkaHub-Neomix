package app.androidrik.rikkahub.ui.components.files

/** Stable identity is separate from the visible filename (managed files use database IDs). */
data class RenameItem(val key: String, val name: String, val isDirectory: Boolean = false)

/** Validation failures are typed so the dialog can localize them; no raw messages cross the boundary. */
internal enum class RenameValidationError {
    BLANK_NAME, INVALID_CHARS, NAME_TOO_LONG, NO_FILES, DUPLICATE_ITEMS, START_INDEX_OUT_OF_RANGE, DUPLICATE_NAMES
}

internal class RenamePlanException(val error: RenameValidationError) : IllegalArgumentException()

internal fun validateRenameName(name: String): RenameValidationError? {
    if (!(name.isNotBlank() && name == name.trim())) return RenameValidationError.BLANK_NAME
    if (name == "." || name == ".." || name.any { it == '/' || it == '\\' || it.isISOControl() }) return RenameValidationError.INVALID_CHARS
    if (name.toByteArray(Charsets.UTF_8).size > 255) return RenameValidationError.NAME_TOO_LONG
    return null
}

/** Same ordered input produces the exact plan shown in the confirmation preview. */
internal fun buildRenamePlan(
    items: List<RenameItem>,
    baseName: String,
    startIndex: Int = 1,
    keepExtension: Boolean = true,
): Map<String, String> {
    fun fail(error: RenameValidationError): Nothing = throw RenamePlanException(error)
    if (items.isEmpty()) fail(RenameValidationError.NO_FILES)
    if (items.map { it.key }.distinct().size != items.size) fail(RenameValidationError.DUPLICATE_ITEMS)
    validateRenameName(baseName)?.let { fail(it) }
    if (!(startIndex >= 0 && startIndex.toLong() + items.size - 1 <= Int.MAX_VALUE)) fail(RenameValidationError.START_INDEX_OUT_OF_RANGE)
    val names = items.mapIndexed { index, item ->
        val name = if (items.size == 1) baseName else {
            val dot = item.name.lastIndexOf('.')
            val extension = if (keepExtension && !item.isDirectory && dot > 0 && dot < item.name.lastIndex) {
                item.name.substring(dot)
            } else ""
            "${baseName}_${startIndex + index}$extension"
        }
        validateRenameName(name)?.let { fail(it) }
        name
    }
    if (names.distinct().size != names.size) fail(RenameValidationError.DUPLICATE_NAMES)
    return items.mapIndexed { index, item -> item.key to names[index] }.toMap()
}
