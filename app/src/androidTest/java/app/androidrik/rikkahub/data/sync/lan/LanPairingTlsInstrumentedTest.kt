package app.androidrik.rikkahub.data.sync.lan

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

@RunWith(AndroidJUnit4::class)
class LanPairingTlsInstrumentedTest {
    @Test
    fun downloadsOnlyAuthorizedInventoryAndResumesFromTheLastRange() = runBlocking {
        val address = findPrivateAddress()
        assumeNotNull(address)
        val endpoint = requireNotNull(address)
        val hostAlias = "rikka-lan-sync-test-host-${UUID.randomUUID()}"
        val clientAlias = "rikka-lan-sync-test-client-${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = File(context.cacheDir, "lan-sync-test-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val host = LanIdentity.load(hostAlias)
            val client = LanIdentity.load(clientAlias)
            val token = newPairToken()
            val conversation = File(temp, "conversation").apply { writeText("portable conversation") }
            val attachment = File(temp, "attachment").apply { writeBytes(ByteArray(LAN_CHUNK_BYTES + 20) { (it % 113).toByte() }) }
            val attachmentSha = hashLanFile(attachment)
            val conversationSha = hashLanFile(conversation)
            val entry = LanManifestObject("conversation:${UUID.randomUUID()}", conversationSha, 50, conversation.length(), listOf(attachmentSha))
            val manifest = LanManifest(LAN_PROTOCOL, LanManifestDevice(host.fingerprint, "Host", 10, LAN_FORMAT_VERSION),
                conversations = listOf(entry))
            val files = mapOf(conversationSha to conversation, attachmentSha to attachment)
            LanSyncDownloadServer(host, endpoint, manifest, files, Json, tokenForPeer = { fingerprint -> token.takeIf { client.fingerprint == fingerprint } })
                .use { server ->
                    val handler = launch(Dispatchers.IO) { server.serve() }
                    try {
                        val wrongToken = LanSyncDownloadClient(client, endpoint, server.port, host.fingerprint, newPairToken(), Json)
                        assertTrue(runCatching { wrongToken.fetchManifest(10) }.exceptionOrNull() is IOException)
                        val unknownClient = LanSyncDownloadClient(host, endpoint, server.port, host.fingerprint, token, Json)
                        assertTrue(runCatching { unknownClient.fetchManifest(10) }.exceptionOrNull() is IOException)
                        val wrongHost = LanSyncDownloadClient(client, endpoint, server.port, "0".repeat(64), token, Json)
                        assertTrue(runCatching { wrongHost.fetchManifest(10) }.exceptionOrNull() is SSLHandshakeException)

                        val download = LanSyncDownloadClient(client, endpoint, server.port, host.fingerprint, token, Json)
                        assertTrue(runCatching { download.fetchManifest(11) }.exceptionOrNull() is IllegalArgumentException)
                        assertEquals(manifest, download.fetchManifest(10))
                        val objects = LanObjectStore(File(temp, "staging"))
                        val absent = entry.copy(sha256 = "1".repeat(64))
                        assertTrue(runCatching { download.pull(listOf(absent), objects) }.exceptionOrNull() is IOException)
                        val first = download.pull(listOf(entry), objects, LAN_CHUNK_BYTES.toLong())
                        assertEquals(setOf(attachmentSha), first.remainingObjects)
                        assertEquals(LAN_CHUNK_BYTES.toLong() - conversation.length(), objects.offset(attachmentSha))
                        assertEquals(null, objects.completed(attachmentSha))
                        val second = download.pull(listOf(entry), objects)
                        assertTrue(second.remainingObjects.isEmpty())
                        assertEquals(conversation.length() + 20, second.transferredBytes)
                        assertEquals(attachmentSha, hashLanFile(requireNotNull(objects.completed(attachmentSha))))
                        val repeat = download.pull(listOf(entry), objects)
                        assertEquals(0L, repeat.transferredBytes)
                    } finally {
                        server.close()
                        handler.join()
                    }
                }
        } finally {
            temp.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let {
                it.deleteEntry(hostAlias)
                it.deleteEntry(clientAlias)
            }
        }
    }

    @Test
    fun confirmsBothKeystoreCertificatesAndRejectsWrongCertificateBeforeHttp() = runBlocking {
        val address = findPrivateAddress()
        assumeNotNull(address)
        val hostAlias = "rikka-lan-tls-test-host-${UUID.randomUUID()}"
        val clientAlias = "rikka-lan-tls-test-client-${UUID.randomUUID()}"
        try {
            val host = LanIdentity.load(hostAlias)
            val client = LanIdentity.load(clientAlias)
            val requests = AtomicInteger()
            LanPairingServer(host, requireNotNull(address)).use { server ->
                val invitation = LanInvite(address, server.port, host.fingerprint, "000038")
                val handler = launch(Dispatchers.IO) {
                    server.serve { fingerprint, body ->
                        requests.incrementAndGet()
                        val request = Json.decodeFromString<LanPairRequest>(body)
                        assertEquals(client.fingerprint, fingerprint)
                        assertEquals(client.fingerprint, request.deviceId)
                        assertEquals(invitation.pin, request.pin)
                        200 to Json.encodeToString(LanPairResponse(LAN_PROTOCOL, newPairToken(), host.fingerprint, "Host"))
                    }
                }
                try {
                    val wrong = LanInvite(address, server.port, "0".repeat(64), invitation.pin)
                    val failure = withContext(Dispatchers.IO) { runCatching { requestLanPair(client, wrong, "Client", Json) }.exceptionOrNull() }
                    assertTrue(failure is SSLHandshakeException)
                    assertEquals(0, requests.get())
                    val response = withContext(Dispatchers.IO) { requestLanPair(client, invitation, "Client", Json) }
                    assertEquals(host.fingerprint, response.deviceId)
                    assertEquals(1, requests.get())
                } finally {
                    server.close()
                    handler.join()
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let {
                it.deleteEntry(hostAlias)
                it.deleteEntry(clientAlias)
            }
        }
    }
}
