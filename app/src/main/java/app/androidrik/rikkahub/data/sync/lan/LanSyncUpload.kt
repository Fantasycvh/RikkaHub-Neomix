package app.androidrik.rikkahub.data.sync.lan

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
internal data class LanSyncOffer(val manifest: LanManifest, val plannedAt: Long)

@Serializable
internal data class LanSyncReport(val added: Int = 0, val updated: Int = 0, val deleted: Int = 0, val conflicts: Int = 0)

internal const val LAN_INBOX_PATH = "/v1/inbox/"
internal const val LAN_APPLIED_PATH = "/v1/applied"
internal const val LAN_OFFSET_HEADER = "X-Rikka-Offset"
private val contentRange = Regex("bytes ([0-9]{1,9})-([0-9]{1,9})/([0-9]{1,9})")

/** One explicit receive window for one paired fingerprint. Only objects required by its negotiated plan may be uploaded. */
internal class LanSyncUploadSession(
    private val local: LanManifest,
    private val staging: LanObjectStore,
    private val json: Json,
    private val now: () -> Long,
    private val localObjects: Map<String, File> = emptyMap(),
    private val apply: suspend (LanManifest, LanMergePlan) -> LanSyncReport,
) {
    private var exchange: Exchange? = null
    private var transferred = 0L

    private class Exchange(
        val remote: LanManifest,
        val plan: LanMergePlan,
        val plannedAt: Long,
        val expected: Map<String, Long?>,
        var report: LanSyncReport? = null,
    )

    fun offer(peer: String, request: LanHttpRequest): LanHttpResponse {
        val bytes = request.readBytes(LAN_MANIFEST_BYTES.toInt()) ?: return LanHttpResponse(400)
        val remote = try {
            json.decodeFromString<LanManifest>(bytes.decodeToString()).also {
                it.validate(local.device.dbVersion, peer)
                requireConversationOnly(it)
            }
        } catch (_: IllegalArgumentException) {
            return LanHttpResponse(426)
        }
        val active = exchange
        if (active != null && active.remote != remote) return LanHttpResponse(409)
        val accepted = active ?: run {
            val timestamp = now()
            val plan = planLanMerge(local, remote, timestamp)
            Exchange(remote, plan, timestamp, lanObjectSizes(plan.objectsFrom(remote))).also { exchange = it }
        }
        return LanHttpResponse.bytes(200, json.encodeToString(LanSyncOffer(local, accepted.plannedAt)).encodeToByteArray(), "application/json")
    }

    suspend fun receive(digest: String, request: LanHttpRequest): LanHttpResponse {
        val active = exchange ?: return LanHttpResponse(409)
        if (!active.expected.containsKey(digest)) return LanHttpResponse(404)
        val complete = completed(digest)
        val offset = complete?.length() ?: staging.offset(digest)
        val expected = active.expected[digest]
        if (request.method == "HEAD" && request.contentLength == 0L) {
            val total = request.headers[LAN_OBJECT_SIZE_HEADER.lowercase()]?.toLongOrNull() ?: return LanHttpResponse(400)
            if (total !in 1..LAN_OBJECT_MAX_BYTES || (expected != null && total != expected) || offset > total) return LanHttpResponse(416)
            if (offset == total && complete == null) {
                try {
                    staging.publishComplete(digest, total)
                } catch (_: IOException) {
                    return LanHttpResponse(422)
                }
            }
            return offsetResponse(200, offset)
        }
        if (request.method != "PUT") return LanHttpResponse(405)
        if (active.report != null) return LanHttpResponse(409)
        val range = contentRange.matchEntire(request.headers["content-range"].orEmpty()) ?: return LanHttpResponse(416)
        val start = range.groupValues[1].toLong()
        val end = range.groupValues[2].toLong()
        val total = range.groupValues[3].toLong()
        if (total !in 1..LAN_OBJECT_MAX_BYTES || (expected != null && total != expected) ||
            end !in start until total || end - start + 1 != request.contentLength ||
            request.contentLength !in 1..LAN_CHUNK_BYTES.toLong()
        ) return LanHttpResponse(416)
        if (offset != start) return offsetResponse(409, offset)
        if (transferred + request.contentLength > LAN_OBJECT_MAX_BYTES) return LanHttpResponse(413)
        val body = request.readBytes(LAN_CHUNK_BYTES) ?: return LanHttpResponse(400)
        transferred += body.size
        val next = try {
            staging.append(digest, start, total, body.size, body.inputStream())
        } catch (_: IOException) {
            return LanHttpResponse(422)
        }
        return offsetResponse(204, next)
    }

    suspend fun finish(request: LanHttpRequest): LanHttpResponse {
        if (request.contentLength != 0L) return LanHttpResponse(400)
        val active = exchange ?: return LanHttpResponse(409)
        val report = active.report ?: run {
            for ((digest, size) in active.expected) {
                val file = completed(digest) ?: return LanHttpResponse(409)
                if (size != null && file.length() != size) return LanHttpResponse(422)
            }
            try {
                apply(active.remote, active.plan).also { active.report = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return LanHttpResponse(409)
            }
        }
        return LanHttpResponse.bytes(200, json.encodeToString(report).encodeToByteArray(), "application/json")
    }

    private suspend fun completed(digest: String): File? =
        staging.completed(digest) ?: localObjects[digest]?.takeIf(File::isFile)

    private fun offsetResponse(status: Int, offset: Long) =
        LanHttpResponse(status, headers = mapOf(LAN_OFFSET_HEADER to offset.toString()))
}
