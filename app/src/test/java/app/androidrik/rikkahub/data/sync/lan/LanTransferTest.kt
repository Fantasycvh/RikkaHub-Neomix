package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.rikkahub.data.sync.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LanTransferTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `two peers stage missing conversations and attachments under the same budget`() = runBlocking {
        val attached = fixture("picture content")
        val left = fixture("conversation at first device")
        val right = fixture("conversation at second device")
        val entryA = LanManifestObject("conversation:6f725a48-8772-44c3-8df9-a188ce6a79db", left.first, 5,
            left.second.length(), listOf(attached.first))
        val entryB = LanManifestObject("conversation:37daccbd-3a0c-47aa-89f8-2468c42ba94d", right.first, 7,
            right.second.length(), listOf(attached.first))
        val a = LanManifest(LAN_PROTOCOL, LanManifestDevice("a".repeat(64), "A", 80, LAN_FORMAT_VERSION), listOf(entryA))
        val b = LanManifest(LAN_PROTOCOL, LanManifestDevice("b".repeat(64), "B", 80, LAN_FORMAT_VERSION), listOf(entryB))
        val plan = planLanMerge(a, b, 0)
        val atA = LanObjectStore(files.newFolder())
        val atB = LanObjectStore(files.newFolder())

        val first = transfer(plan.pull, atA, mapOf(right, attached), 4)
        assertEquals(4L, first.transferredBytes)
        assertEquals(setOf(right.first, attached.first), first.remainingObjects)
        assertNull(atA.completed(right.first))
        val rest = transfer(plan.pull, atA, mapOf(right, attached), LAN_OBJECT_MAX_BYTES)
        assertTrue(rest.remainingObjects.isEmpty())
        assertEquals(right.second.length() + attached.second.length() - 4, rest.transferredBytes)
        assertEquals("picture content", atA.completed(attached.first)?.readText())

        val back = transfer(plan.push, atB, mapOf(left, attached), LAN_OBJECT_MAX_BYTES)
        assertTrue(back.remainingObjects.isEmpty())
        assertEquals(left.second.readText(), atB.completed(left.first)?.readText())
        assertEquals(attached.second.readText(), atB.completed(attached.first)?.readText())
        assertEquals(0L, transfer(plan.pull, atA, emptyMap(), LAN_OBJECT_MAX_BYTES).transferredBytes)
    }

    private fun fixture(content: String): Pair<String, File> =
        sha256Hex(content.toByteArray()) to files.newFile().apply { writeText(content) }

    private suspend fun transfer(entries: List<LanManifestObject>, target: LanObjectStore, source: Map<String, File>, budget: Long) =
        receiveLanObjects(entries, target, budget, { source.getValue(it).length() }) { hash, start, size ->
            readLanFileRange(source.getValue(hash), start, size)
        }
}
