package app.androidrik.rikkahub.data.model

internal enum class MemoryFileCategory(val pathPrefix: String, val examplePath: String) {
    PROFILE("/profile.md", "/profile.md"),
    PREFERENCES("/preferences.md", "/preferences.md"),
    TOPICS("/topics/", "/topics/example.md"),
    AREAS("/areas/", "/areas/example.md"),
    PEOPLE("/people/", "/people/example.md"),
    OTHER("/", "/example.md"),
    ;

    companion object {
        fun of(path: String): MemoryFileCategory = when (path) {
            PROFILE.pathPrefix -> PROFILE
            PREFERENCES.pathPrefix -> PREFERENCES
            else -> entries.firstOrNull { it.ordinal >= TOPICS.ordinal && path.startsWith(it.pathPrefix) } ?: OTHER
        }
    }
}

internal const val MEMORY_FILE_CATEGORIES_GUIDE =
    "/profile.md: stable personal facts; /preferences.md: lasting preferences; " +
        "/topics/: domain knowledge; /areas/: ongoing projects and responsibilities; " +
        "/people/: relevant people and relationships. Preserve custom paths unless explicitly relocating a file."
