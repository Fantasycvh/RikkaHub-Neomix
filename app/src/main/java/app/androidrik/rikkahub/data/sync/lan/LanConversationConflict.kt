package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.rikkahub.data.model.Conversation
import java.time.Instant
import java.util.UUID
import kotlin.uuid.Uuid

/** The tree supports multiple candidates at its single root, and multiple subtrees under that root. */
internal fun mergeLanConflict(winner: Conversation, loser: Conversation, loserDigest: String): Conversation {
    require(winner.id == loser.id && isLanDigest(loserDigest))
    val original = loser.messageNodes
    val destination = winner.messageNodes
    if (original.isEmpty()) return winner
    if (destination.isEmpty()) return winner.copy(messageNodes = original, activeLeafNodeId = loser.activeLeafNodeId ?: original.last().id)
    val byId = destination.associateBy { it.id }
    val winnerRoot = destination.first()
    val loserRoot = original.first()
    val addedIds = original.drop(1).associate { it.id to conflictId(loserDigest, it.id) }
    if (addedIds.isNotEmpty() && addedIds.values.all(byId::containsKey)) return winner
    if (original.all { node ->
            val existing = byId[node.id]
            existing != null && existing.parentId == node.parentId && existing.messages.containsAll(node.messages)
        }
    ) return winner

    val knownMessages = destination.flatMapTo(hashSetOf()) { node -> node.messages.map { it.id } }
    val extraRoot = loserRoot.messages.filterNot { it in winnerRoot.messages }.map { message ->
        if (knownMessages.add(message.id)) message else message.copy(id = conflictId(loserDigest, message.id))
    }.filterNot { it in winnerRoot.messages }
    val branch = original.drop(1).map { node ->
        node.copy(
            id = addedIds.getValue(node.id),
            parentId = if (node.parentId == loserRoot.id) winnerRoot.id else addedIds.getValue(requireNotNull(node.parentId)),
            messages = node.messages.map { it.copy(id = conflictId(loserDigest, it.id)) },
        )
    }
    if (extraRoot.isEmpty() && branch.isEmpty()) return winner
    return winner.copy(
        messageNodes = listOf(winnerRoot.copy(messages = winnerRoot.messages + extraRoot)) + destination.drop(1) + branch,
        activeLeafNodeId = winner.activeLeafNodeId ?: destination.last().id,
        updateAt = Instant.ofEpochMilli(maxOf(winner.updateAt.toEpochMilli(), loser.updateAt.toEpochMilli()) + 1),
    )
}

private fun conflictId(digest: String, id: Uuid): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("rikka-lan-conflict:$digest:$id".encodeToByteArray()).toString())
