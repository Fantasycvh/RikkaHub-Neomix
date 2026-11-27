package app.androidrik.rikkahub.data.sync.lan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanFamiliesTest {
    private val left = LanManifestDevice("1".repeat(64), "left", 81, LAN_FORMAT_VERSION)
    private val right = left.copy(id = "2".repeat(64), name = "right")

    @Test
    fun familyPlansConvergeAndRejectUnknownOrOldFormats() {
        val entry = LanManifestObject(lanFamilyKey("workspace", "root/src/file"), "a".repeat(64), 10, 12, listOf("b".repeat(64)))
        val a = LanManifest(LAN_PROTOCOL, left, families = listOf(entry))
        val b = LanManifest(LAN_PROTOCOL, right)
        val forward = planLanMerge(a, b, 20)
        val backward = planLanMerge(b, a, 20)
        assertEquals(listOf(entry), forward.push)
        assertEquals(forward.push, backward.pull)
        assertEquals(listOf(entry), forward.objectsFrom(a))
        val deleted = b.copy(tombstones = listOf(LanTombstone(entry.key, 11)))
        assertEquals(deleted.tombstones, planLanMerge(a, deleted, 20).deleteLocal)
        assertTrue(runCatching { a.copy(device = left.copy(formatVersion = 1)).validate(81, left.id) }.isFailure)
        assertTrue(runCatching { a.copy(families = listOf(entry.copy(key = lanFamilyKey("unknown", "x")))).validate(81, left.id) }.isFailure)
    }

    @Test
    fun workspaceGetsReservedCapacityAndSpareCapacityIsReused() = runBlocking {
        val primary = LanManifestObject(lanFamilyKey("assistant", "one"), "a".repeat(64), 0, 12)
        val workspace = primary.copy(key = lanFamilyKey("workspace", "root/file"), sha256 = "b".repeat(64))
        val calls = arrayListOf<Pair<List<LanManifestObject>, Long>>()
        val result = transferLanLanes(listOf(workspace, primary)) { entries, budget ->
            calls += entries to budget
            LanTransferResult(minOf(budget, 12), emptySet())
        }
        assertEquals(listOf(primary), calls.first().first)
        assertEquals(LAN_OBJECT_MAX_BYTES * 3 / 4, calls.first().second)
        assertEquals(listOf(workspace), calls.last().first)
        assertEquals(LAN_OBJECT_MAX_BYTES - 12, calls.last().second)
        assertEquals(24L, result.transferredBytes)
    }

    @Test
    fun completedConfigurationCanApplyWhileWorkspaceIsStillTransferring() {
        val assistant = LanManifestObject(lanFamilyKey("assistant", "one"), "a".repeat(64), 0, 12)
        val workspace = assistant.copy(key = lanFamilyKey("workspace", "root/file"), sha256 = "b".repeat(64),
            attachmentShas = listOf("c".repeat(64)))
        val local = LanManifest(LAN_PROTOCOL, left, assistants = listOf(assistant), families = listOf(workspace))
        val remote = LanManifest(LAN_PROTOCOL, right)
        val plan = planLanMerge(local, remote, 20)
        val ready = plan.completedKeys(local, remote, LanTransferResult(12, setOf("c".repeat(64))),
            LanTransferResult(0, emptySet()))
        assertEquals(setOf(assistant.key), ready)
        assertEquals(listOf(assistant), plan.restrictTo(ready).push)
    }
}
