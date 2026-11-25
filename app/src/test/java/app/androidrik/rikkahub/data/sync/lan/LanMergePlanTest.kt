package app.androidrik.rikkahub.data.sync.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanMergePlanTest {
    private val key = "conversation:6f725a48-8772-44c3-8df9-a188ce6a79db"
    private val left = LanManifestDevice("1".repeat(64), "left", 80, LAN_FORMAT_VERSION)
    private val right = LanManifestDevice("2".repeat(64), "right", 80, LAN_FORMAT_VERSION)
    private val attachment = "c".repeat(64)

    @Test
    fun `new and edited rows produce complementary plans including attachments`() {
        val a = row('a', 10)
        val b = row('b', 11)
        val forward = planLanMerge(manifest(left, a), manifest(right, b), 20)
        val reverse = planLanMerge(manifest(right, b), manifest(left, a), 20)
        assertEquals(listOf(b), forward.pull)
        assertEquals(forward.pull, reverse.push)
        assertEquals(forward.conflicts, reverse.conflicts)
        assertEquals(listOf(attachment), forward.pull.single().attachmentShas)
        assertEquals(listOf(a), planLanMerge(manifest(left, a), manifest(right), 20).push)
        assertTrue(planLanMerge(manifest(left, b), manifest(right, b), 20).pull.isEmpty())
    }

    @Test
    fun `equal timestamps converge by digest and never depend on direction`() {
        val smaller = row('a', 11)
        val larger = row('b', 11)
        val forward = planLanMerge(manifest(left, larger), manifest(right, smaller), 20)
        val reverse = planLanMerge(manifest(right, smaller), manifest(left, larger), 20)
        assertEquals(listOf(smaller), forward.pull)
        assertEquals(forward.pull, reverse.push)
    }

    @Test
    fun `newer deletion wins both directions while equal and older deletions allow restoration`() {
        val record = row('a', 10)
        val remoteDeleted = manifest(right).copy(tombstones = listOf(LanTombstone(key, 11)))
        assertEquals(remoteDeleted.tombstones, planLanMerge(manifest(left, record), remoteDeleted, 20).deleteLocal)
        assertEquals(remoteDeleted.tombstones, planLanMerge(remoteDeleted, manifest(left, record), 20).deleteRemote)
        for (time in listOf(9L, 10L)) {
            val remoteOlder = remoteDeleted.copy(tombstones = listOf(LanTombstone(key, time)))
            assertEquals(listOf(record), planLanMerge(manifest(left, record), remoteOlder, 20).push)
        }
        assertEquals(remoteDeleted.tombstones, planLanMerge(manifest(left), remoteDeleted, 20).tombstones)
        assertTrue(planLanMerge(manifest(left), remoteDeleted, THIRTY_WHOLE_DAYS + 12).tombstones.isEmpty())
    }

    @Test
    fun `rejects schemas duplicate keys and invalid digests`() {
        val record = row('a', 10)
        assertTrue(runCatching { planLanMerge(manifest(left), manifest(right.copy(dbVersion = 81)), 20) }.isFailure)
        assertTrue(runCatching { planLanMerge(manifest(left), manifest(right, record, record), 20) }.isFailure)
        assertTrue(runCatching { planLanMerge(manifest(left), manifest(right, record.copy(sha256 = "invalid")), 20) }.isFailure)
        assertTrue(runCatching { manifest(right, record).validate(80, left.id) }.isFailure)
    }

    private fun row(hash: Char, time: Long) = LanManifestObject(key, hash.toString().repeat(64), time, 12, listOf(attachment))
    private fun manifest(device: LanManifestDevice, vararg rows: LanManifestObject) = LanManifest(LAN_PROTOCOL, device, rows.toList())

    private companion object { const val THIRTY_WHOLE_DAYS = 30L * 24 * 60 * 60 * 1000 }
}
