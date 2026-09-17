package app.androidrik.rikkahub.data.model

import app.androidrik.rikkahub.utils.reorderForDragByKey

/** Display order only: opening a book never rewrites imported priorities or stored order. */
fun Lorebook.entriesForDisplay(): List<PromptInjection.RegexInjection> = entries.sortedByDescending { it.priority }

/** Only an actual move between entry keys rewrites priorities. Header/obsolete keys are ignored. */
fun Lorebook.reorderEntries(fromKey: Any, toKey: Any): Lorebook {
    val displayed = entriesForDisplay()
    val moved = displayed.reorderForDragByKey(fromKey, toKey) { it.id }
    if (moved === displayed) return this
    return copy(entries = moved.mapIndexed { index, entry -> entry.copy(priority = moved.lastIndex - index) })
}

/** Replace by identity (not stale object equality); unrelated entries and book metadata are retained. */
fun Lorebook.updateEntry(edited: PromptInjection.RegexInjection): Lorebook {
    val index = entries.indexOfFirst { it.id == edited.id }
    return copy(entries = if (index < 0) entries + edited else entries.toMutableList().apply { set(index, edited) })
}
