package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class LanSyncUploadSessionTest {
    @get:Rule val files = TemporaryFolder()
    private val localDevice = LanManifestDevice("a".repeat(64), "A", 82, LAN_FORMAT_VERSION)
    private val remoteDevice = localDevice.copy(id = "b".repeat(64), name = "B")

    @Test fun `partial apply can resume in a fresh round without reopening the receiver`() = runBlocking {
        val first = fixture("first object")
        val second = fixture("second object")
        val remote = LanManifest(LAN_PROTOCOL, remoteDevice, listOf(first.first, second.first))
        var local = LanManifest(LAN_PROTOCOL, localDevice)
        var localFiles = emptyMap<String, File>()
        var applications = 0
        val staging = LanObjectStore(files.newFolder())
        val session = LanSyncUploadSession(local, staging, JsonInstant, { 100L },
            refreshLocal = { local to localFiles },
        ) { _, plan ->
            applications++
            local = local.copy(conversations = (local.conversations + plan.pull).distinctBy { it.key })
            localFiles = local.conversations.associate { it.sha256 to requireNotNull(staging.completed(it.sha256)) }
            LanSyncReport(added = plan.pull.size)
        }
        val round1 = UUID.randomUUID().toString()
        assertEquals(200, status(session.offer(remoteDevice.id, offer(remote, round1))))
        assertEquals(204, status(session.receive(first.first.sha256, upload(first, round1))))
        val finish1 = { request("POST", LAN_APPLIED_PATH, JsonInstant.encodeToString(LanSyncApplyRequest(setOf(first.first.key))), round1) }
        assertEquals(200, status(session.finish(finish1())))
        assertEquals(200, status(session.finish(finish1())))
        assertEquals(1, applications)

        val round2 = UUID.randomUUID().toString()
        val offer2 = response(session.offer(remoteDevice.id, offer(remote, round2)))
        assertTrue(offer2.startsWith("HTTP/1.1 200"))
        val refreshed = JsonInstant.decodeFromString<LanSyncOffer>(offer2.substringAfter("\r\n\r\n"))
        assertEquals(listOf(first.first), refreshed.manifest.conversations)
        assertEquals(first.second.readText(), session.downloadObject(first.first.sha256)?.readText())
        assertEquals(409, status(session.finish(finish1())))
        assertEquals(409, status(session.receive(second.first.sha256, upload(second, round1))))
        assertEquals(204, status(session.receive(second.first.sha256, upload(second, round2))))
        assertEquals(200, status(session.finish(request("POST", LAN_APPLIED_PATH,
            JsonInstant.encodeToString(LanSyncApplyRequest(setOf(second.first.key))), round2))))
        assertEquals(2, applications)
        assertEquals(remote.conversations.toSet(), local.conversations.toSet())
    }

    @Test fun `interrupted upload resumes verified prefix in a fresh round`() = runBlocking {
        val item = fixture("interrupted object")
        val local = LanManifest(LAN_PROTOCOL, localDevice)
        val remote = LanManifest(LAN_PROTOCOL, remoteDevice, listOf(item.first))
        val staging = LanObjectStore(files.newFolder())
        val session = LanSyncUploadSession(local, staging, JsonInstant, { 100L },
            refreshLocal = { local to emptyMap() },
        ) { _, _ -> LanSyncReport() }
        assertEquals(200, status(session.offer(remoteDevice.id, offer(remote, "round-1"))))
        assertEquals(204, status(session.receive(item.first.sha256, upload(item, "round-1", 0, 4))))
        assertEquals(4L, staging.offset(item.first.sha256))
        assertEquals(200, status(session.offer(remoteDevice.id, offer(remote, "round-2"))))
        assertEquals(4L, staging.offset(item.first.sha256))
        assertEquals(204, status(session.receive(item.first.sha256, upload(item, "round-2", 4))))
        assertEquals(item.second.readText(), staging.completed(item.first.sha256)?.readText())
    }

    private suspend fun fixture(text: String): Pair<LanManifestObject, File> {
        val file = files.newFile().apply { writeText(text) }
        return LanManifestObject("conversation:${UUID.randomUUID()}", hashLanFile(file), 1, file.length()) to file
    }
    private fun offer(manifest: LanManifest, round: String) = request("POST", "/v1/manifest", JsonInstant.encodeToString(manifest), round)
    private fun upload(item: Pair<LanManifestObject, File>, round: String, start: Int = 0, count: Int = item.second.length().toInt() - start): LanHttpRequest =
        request("PUT", LAN_INBOX_PATH + item.first.sha256, item.second.readText().substring(start, start + count), round,
            "Content-Range: bytes $start-${start + count - 1}/${item.second.length()}\r\n")
    private fun request(method: String, path: String, body: String, round: String, extra: String = ""): LanHttpRequest =
        requireNotNull(LanHttpRequest.read(("$method $path HTTP/1.1\r\nContent-Length: ${body.encodeToByteArray().size}\r\n" +
            "$LAN_ROUND_HEADER: $round\r\n$extra\r\n$body").byteInputStream()))
    private fun response(response: LanHttpResponse): String = ByteArrayOutputStream().also(response::writeTo).toString(Charsets.UTF_8)
    private fun status(result: LanHttpResponse): Int = response(result).substringAfter(' ').substringBefore(' ').toInt()
}
