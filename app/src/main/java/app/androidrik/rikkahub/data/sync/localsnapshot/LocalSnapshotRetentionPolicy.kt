package app.androidrik.rikkahub.data.sync.localsnapshot

import app.androidrik.rikkahub.data.datastore.LocalSnapshotConfig
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

internal data class LocalSnapshot(
    val id: String,
    val name: String,
    val bytes: Long?,
    val createdAt: LocalDateTime,
) {
    companion object {
        private val format = DateTimeFormatter.ofPattern("'local_backup_'uuuuMMdd_HHmmss'.zip'", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val namePattern = Regex("local_backup_[0-9]{8}_[0-9]{6}\\.zip")

        fun fileName(at: LocalDateTime): String = at.format(format)

        fun fromDocument(id: String, name: String, bytes: Long?): LocalSnapshot? {
            if (!namePattern.matches(name)) return null
            val time = runCatching { LocalDateTime.parse(name, format) }.getOrNull() ?: return null
            return LocalSnapshot(id, name, bytes?.takeIf { it >= 0 }, time)
        }
    }
}

internal object LocalSnapshotRetentionPolicy {
    fun deletions(snapshots: List<LocalSnapshot>, now: LocalDateTime, keepRecent: Int): List<LocalSnapshot> {
        val newestFirst = snapshots.sortedByDescending { it.name }
        val usable = newestFirst.filter { it.bytes != 0L }
        val keptIds = usable.take(keepRecent.coerceIn(1, 10)).mapTo(mutableSetOf()) { it.id }
        // Reserve the monthly copy first, so it remains available when only one older copy qualifies.
        listOf(30L, 7L).forEach { days ->
            val cutoff = now.minusDays(days)
            usable.firstOrNull { it.id !in keptIds && it.createdAt <= cutoff }?.let { keptIds.add(it.id) }
        }
        return newestFirst.asReversed().filter { it.id !in keptIds }
    }

    fun newestNonEmpty(snapshots: List<LocalSnapshot>): LocalSnapshot? =
        snapshots.filter { it.bytes != 0L }.maxByOrNull { it.name }
}

@Serializable
internal data class LocalSnapshotState(
    val treeUri: String? = null,
    val lastSuccessAt: Long? = null,
    val lastArchiveName: String? = null,
    val lastArchiveBytes: Long? = null,
    val fingerprint: String? = null,
    val failures: Int = 0,
    val retryAt: Long = 0,
    val status: LocalSnapshotStatus? = null,
) {
    fun isDue(config: LocalSnapshotConfig, now: Long): Boolean =
        config.enabled && config.treeUri != null && now >= retryAt &&
            (lastSuccessAt == null || now - lastSuccessAt >= config.intervalDays.coerceAtLeast(1).days.inWholeMilliseconds)

    fun isUnchanged(currentFingerprint: String?, latest: LocalSnapshot?): Boolean =
        currentFingerprint != null && currentFingerprint == fingerprint &&
            latest != null && latest.name == lastArchiveName && latest.bytes != null && latest.bytes == lastArchiveBytes

    fun failed(now: Long, reason: LocalSnapshotStatus): LocalSnapshotState {
        val count = (failures + 1).coerceIn(1, 5)
        return copy(failures = count, retryAt = now + (1 shl (count - 1)).hours.inWholeMilliseconds, status = reason)
    }
}

@Serializable
internal enum class LocalSnapshotStatus { SAVED, UNCHANGED, FAILED, PERMISSION_REQUIRED, CLEANUP_FAILED }
