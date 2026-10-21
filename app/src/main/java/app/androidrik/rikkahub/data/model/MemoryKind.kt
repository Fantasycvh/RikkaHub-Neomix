package app.androidrik.rikkahub.data.model

/** Semantic lifecycle; unknown future storage values retain the ordinary topic behavior. */
enum class MemoryKind(val storage: String) {
    PROFILE("profile"), TOPIC("topic"), TASK("task");

    companion object {
        fun fromStorage(value: String?): MemoryKind = entries.firstOrNull { it.storage == value } ?: TOPIC
    }
}

enum class MemoryTaskStatus(val storage: String) {
    OPEN("open"), DONE("done"), DROPPED("dropped");

    companion object {
        fun fromStorage(value: String?): MemoryTaskStatus = entries.firstOrNull { it.storage == value } ?: OPEN
    }
}
