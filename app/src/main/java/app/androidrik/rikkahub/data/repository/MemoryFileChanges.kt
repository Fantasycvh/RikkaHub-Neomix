package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.ai.tools.findForbiddenMemoryContent
import app.androidrik.rikkahub.data.model.MemoryFileCategory

internal fun MemoryConsolidationProposal.validFileChange(): Boolean {
    val path = targetId ?: return false
    if (contractVersion != 1 || dependsOn.isNotEmpty() || expectedRevision != null ||
        !path.isCanonicalMemoryPath() || content.toByteArray().size > MAX_MEMORY_FILE_BYTES
    ) return false
    return when (action) {
        "create" -> expectedFileRevision == null && fileDestination == null && content.isSafeMemoryBody()
        "edit" -> !expectedFileRevision.isNullOrBlank() && fileDestination == null && content.isSafeMemoryBody()
        "move" -> {
            val destination = fileDestination
            !expectedFileRevision.isNullOrBlank() && content.isEmpty() &&
                destination != null && destination != path && destination.isCanonicalMemoryPath() &&
                !path.isPinnedMemoryFile() && !destination.isPinnedMemoryFile()
        }
        else -> false
    }
}

private fun String.isSafeMemoryBody() = isNotBlank() && findForbiddenMemoryContent(this) == null
private fun String.isCanonicalMemoryPath() = runCatching { normalizeMemoryFilePath(this) == this }.getOrDefault(false)
private fun String.isPinnedMemoryFile() = MemoryFileCategory.of(this) in setOf(
    MemoryFileCategory.PROFILE, MemoryFileCategory.PREFERENCES,
)

internal suspend fun MemoryFileRepository.applyFileChange(
    storeId: String,
    proposal: MemoryConsolidationProposal,
) {
    require(proposal.validFileChange()) { "Invalid file memory proposal" }
    val path = requireNotNull(proposal.targetId)
    if (proposal.action == "move") {
        move(storeId, path, requireNotNull(proposal.fileDestination), requireNotNull(proposal.expectedFileRevision))
    } else {
        write(storeId, path, proposal.expectedFileRevision) { proposal.content }
    }
}
