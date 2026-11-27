package app.androidrik.rikkahub.data.sync.lan

internal data class LanMergePlan(
    val push: List<LanManifestObject>,
    val pull: List<LanManifestObject>,
    val deleteLocal: List<LanTombstone>,
    val deleteRemote: List<LanTombstone>,
    val tombstones: List<LanTombstone>,
    val conflicts: List<LanConflict>,
)

internal data class LanConflict(val winner: LanManifestObject, val loser: LanManifestObject)

internal fun LanMergePlan.restrictTo(keys: Set<String>) = copy(
    push = push.filter { it.key in keys },
    pull = pull.filter { it.key in keys },
    deleteLocal = deleteLocal.filter { it.key in keys },
    deleteRemote = deleteRemote.filter { it.key in keys },
    tombstones = tombstones.filter { it.key in keys },
    conflicts = conflicts.filter { it.winner.key in keys },
)

internal fun LanMergePlan.completedKeys(
    local: LanManifest,
    remote: LanManifest,
    sending: LanTransferResult,
    receiving: LanTransferResult,
): Set<String> {
    val incomplete = objectsFrom(local).filter { entry ->
        entry.sha256 in sending.remainingObjects || entry.attachmentShas.any { it in sending.remainingObjects }
    }.map { it.key } + objectsFrom(remote).filter { entry ->
        entry.sha256 in receiving.remainingObjects || entry.attachmentShas.any { it in receiving.remainingObjects }
    }.map { it.key }
    return (push.map { it.key } + pull.map { it.key } + conflicts.map { it.winner.key } +
        deleteLocal.map { it.key } + deleteRemote.map { it.key } + tombstones.map { it.key }).toSet() - incomplete.toSet()
}

internal fun LanMergePlan.objectsFrom(manifest: LanManifest): List<LanManifestObject> {
    val entries = manifest.objects.associateBy { it.key }
    return (push + pull + conflicts.map { it.loser })
        .filter { entries[it.key] == it }
        .distinctBy { it.key }
}

/** Each side derives the same winners and deletion timestamps using its own local view first. */
internal fun planLanMerge(local: LanManifest, remote: LanManifest, now: Long): LanMergePlan {
    local.validate(local.device.dbVersion, local.device.id)
    remote.validate(local.device.dbVersion, remote.device.id)
    require(local.device.id != remote.device.id)
    val here = local.objects.associateBy { it.key }
    val there = remote.objects.associateBy { it.key }
    val deletions = (local.tombstones + remote.tombstones).groupBy { it.key }.mapValues { (_, marks) ->
        marks.maxBy { it.deletedAt }
    }
    val push = mutableListOf<LanManifestObject>()
    val pull = mutableListOf<LanManifestObject>()
    val deleteLocal = mutableListOf<LanTombstone>()
    val deleteRemote = mutableListOf<LanTombstone>()
    val conflicts = mutableListOf<LanConflict>()
    val tombstones = mutableListOf<LanTombstone>()
    for (key in (here.keys + there.keys + deletions.keys).sorted()) {
        val left = here[key]
        val right = there[key]
        val mark = deletions[key]?.takeIf { it.deletedAt >= now - LAN_TOMBSTONE_RETENTION_MS }
        val winner = listOfNotNull(left, right).minWithOrNull(precedence)
        if (mark != null && (winner == null || mark.deletedAt > winner.updateTime)) {
            tombstones.add(mark)
            if (left != null) deleteLocal.add(mark)
            if (right != null) deleteRemote.add(mark)
        } else if (winner != null) {
            if (left?.sha256 != winner.sha256) pull.add(winner)
            if (right?.sha256 != winner.sha256) push.add(winner)
            if (left != null && right != null && left.sha256 != right.sha256) {
                val loser = if (winner.sha256 == left.sha256) right else left
                conflicts.add(LanConflict(winner, loser))
            }
        }
    }
    return LanMergePlan(push, pull, deleteLocal, deleteRemote, tombstones, conflicts)
}

private val precedence = compareByDescending<LanManifestObject> { it.updateTime }.thenBy { it.sha256 }
internal const val LAN_TOMBSTONE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
