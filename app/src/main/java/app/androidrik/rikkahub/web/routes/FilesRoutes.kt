package app.androidrik.rikkahub.web.routes

import android.content.Context
import androidx.core.net.toUri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.files.saveUploadFromBytes
import app.androidrik.rikkahub.web.BadRequestException
import app.androidrik.rikkahub.web.NotFoundException
import app.androidrik.rikkahub.web.dto.UploadFilesResponseDto
import app.androidrik.rikkahub.web.dto.UploadedFileDto
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_UPLOAD_FILE_SIZE_BYTES = 20 * 1024 * 1024 // 20 MB

/**
 * 文件下载响应统一加的 CSP：`sandbox`（不带 `allow-scripts`）把文档放进 opaque origin 并禁脚本，
 * `default-src 'none'` 禁一切子资源。防止用户上传的 HTML/SVG 被直接打开时以**应用同源**
 * 执行脚本、读取 Web 会话的 access token（web-ui 把 token 放在 sessionStorage）。
 *
 * 注意：作为 `<img>`/`<video>`/`<audio>` 子资源加载时，该响应头不作用于父文档，媒体预览正常。
 */
private const val SANDBOXED_FILE_CSP = "sandbox; default-src 'none'"

fun Route.filesRoutes(
    filesManager: FilesManager,
    context: Context
) {
    route("/files") {
        // POST /api/files/upload - Upload files
        post("/upload") {
            val multipart = call.receiveMultipart()
            val uploadedFiles = mutableListOf<UploadedFileDto>()

            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FileItem -> {
                            val originalFileName = part.originalFileName
                                ?.takeIf { it.isNotBlank() }
                                ?: "file"
                            val displayName = sanitizeDisplayName(originalFileName)
                            val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                            val bytes = readPartBytes(part, MAX_UPLOAD_FILE_SIZE_BYTES)
                            if (bytes.isEmpty()) {
                                throw BadRequestException("Uploaded file is empty")
                            }

                            val entity = filesManager.saveUploadFromBytes(
                                bytes = bytes,
                                displayName = displayName,
                                mimeType = mimeType,
                            )
                            uploadedFiles.add(entity.toUploadedFileDto(filesManager))
                        }

                        else -> {
                            // Ignore non-file fields.
                        }
                    }
                } finally {
                    part.dispose()
                }
            }

            if (uploadedFiles.isEmpty()) {
                throw BadRequestException("No files uploaded")
            }

            call.respond(
                status = HttpStatusCode.Created,
                message = UploadFilesResponseDto(files = uploadedFiles)
            )
        }

        // DELETE /api/files/{id} - Delete uploaded file
        delete("/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            val deleted = filesManager.delete(id, deleteFromDisk = true)
            if (!deleted) {
                throw NotFoundException("File not found")
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // GET /api/files/id/{id} - Get file by ID
        get("/id/{id}") {
            val idParam = call.pathParameters["id"]
                ?: throw BadRequestException("Missing file id")
            val id = idParam.toLongOrNull()
                ?: throw BadRequestException("Invalid file id")

            val entity = filesManager.get(id)
                ?: throw NotFoundException("File not found")

            // 与 /path 同一套边界：`relative_path` 正常只可能是 upload/avatars/exports 里的相对路径，
            // 但库被篡改 / 旧数据 / 目录被软链替换时 `getFile()` 能指到 filesDir 内任意位置
            // （包括 datastore/ 的明文 API key），所以这里必须重新过白名单 + canonical 校验。
            val file = resolveWebReadableFile(context.filesDir, entity.relativePath)
                ?: throw NotFoundException("File not found")
            if (!file.exists()) {
                throw NotFoundException("File not found on disk")
            }

            call.response.header("Content-Type", entity.mimeType)
            call.response.header("Content-Security-Policy", SANDBOXED_FILE_CSP)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }

        // GET /api/files/path/{...} - Get file by relative path
        get("/path/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing file path")

            // 只允许 FilesManager 的「用户媒体/附件」根目录（upload/avatars/images/media_creation/exports）。
            // 旧实现只校验 canonical 前缀是 filesDir —— 于是 `datastore/settings.preferences_pb`
            // （Preferences DataStore，明文 API key / 密码）与 `logs/`（诊断日志）都能整份下载；
            // 目录内软链还能绕到私有目录之外。根目录白名单 + canonical 边界见 resolveWebReadableFile。
            val file = resolveWebReadableFile(context.filesDir, relativePath)
                ?: throw NotFoundException("File not found")

            if (!file.exists() || !file.isFile) {
                throw NotFoundException("File not found")
            }

            // Use managed file MIME type first because on-disk file names are UUID-only.
            // 注意：DB 里存的是**请求的原始相对路径**，不是 canonical 路径（软链/规范化可能不同）。
            val managedFileMime = filesManager.getByRelativePath(relativePath)?.mimeType
            val contentType = if (!managedFileMime.isNullOrBlank()) {
                managedFileMime
            } else {
                when (file.extension.lowercase()) {
                    "jpg", "jpeg" -> ContentType.Image.JPEG.toString()
                    "png" -> ContentType.Image.PNG.toString()
                    "gif" -> ContentType.Image.GIF.toString()
                    "webp" -> ContentType("image", "webp").toString()
                    "svg" -> ContentType.Image.SVG.toString()
                    "pdf" -> ContentType.Application.Pdf.toString()
                    "json" -> ContentType.Application.Json.toString()
                    "txt", "log", "conf", "config", "diff", "patch" -> ContentType.Text.Plain.toString()
                    "md" -> ContentType("text", "markdown").toString()
                    "html", "htm" -> ContentType.Text.Html.toString()
                    "css" -> ContentType.Text.CSS.toString()
                    "xml" -> ContentType.Text.Xml.toString()
                    "csv" -> ContentType.Text.CSV.toString()
                    "yml", "yaml" -> ContentType("text", "yaml").toString()
                    "ini", "toml", "env" -> ContentType.Text.Plain.toString()
                    "go", "py", "rs", "java", "kt", "c", "cpp", "h", "cs", "rb", "php",
                    "swift", "dart", "scala", "sh", "bash", "zsh", "js", "ts", "tsx",
                    "jsx", "vue", "scss", "less", "sql", "graphql", "proto" -> ContentType.Text.Plain.toString()
                    "mp4" -> ContentType("video", "mp4").toString()
                    "webm" -> ContentType("video", "webm").toString()
                    "mp3" -> ContentType.Audio.MPEG.toString()
                    "wav" -> ContentType("audio", "wav").toString()
                    "ogg" -> ContentType("audio", "ogg").toString()
                    else -> ContentType.Application.OctetStream.toString()
                }
            }

            call.response.header("Content-Type", contentType)
            // 上传文件可能是 HTML/SVG：直接打开会以 **应用同源** 执行脚本，进而读走
            // sessionStorage 里的 Web access token。`sandbox`（无 allow-scripts）让文档进入
            // opaque origin、`default-src 'none'` 禁掉子资源；作为 <img>/<video> 子资源加载
            // 不受该响应头影响，媒体预览照常。
            call.response.header("Content-Security-Policy", SANDBOXED_FILE_CSP)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.respondFile(file)
        }
    }
}

/**
 * Web 端 `/api/files/path/{path}` 允许读取的根目录白名单（相对 `filesDir`）。
 *
 * 取自 [FileFolders] 里**用户可见的媒体/附件**目录：
 * - [FileFolders.UPLOAD]：聊天附件（图片/文档/音频/视频），web-ui 消息气泡要渲染；
 * - [FileFolders.AVATARS]：助手头像 + 用户头像，web-ui 设置/侧栏要渲染；
 * - [FileFolders.IMAGES]：生图产物（会话内 `generate_image` 工具输出绝对路径指向这里）；
 * - [FileFolders.MEDIA_CREATION]：媒体创作产物（按会话分子目录）；
 * - [FileFolders.EXPORTS]：导出留存区（用户自己的对话导出副本）。
 *
 * **刻意排除**（在本工程里都真实存在）：
 * - `datastore/`：`Preferences DataStore` 的 `settings.preferences_pb`，明文 API key/密码；
 * - `logs/`：诊断日志（`Logging.initLogDirectory`），可能含请求头/异常栈；
 * - `skills/`、`builtin_skills/`、`fonts/`、`tool_outputs/`：原生端专属，Web 端不消费；
 * - 其余任何目录（`filesDir` 下还有 `workspaces/`、`avatar_backup` 等）。
 *
 * 新增白名单项前先确认 web-ui 真有消费点（`resolveFileUrl` 会把这些 URL 变成
 * `/api/files/path/...`），不要为了「以后可能用到」放行。
 */
internal val WEB_READABLE_FILE_FOLDERS: Set<String> = setOf(
    FileFolders.UPLOAD,
    FileFolders.AVATARS,
    FileFolders.IMAGES,
    FileFolders.MEDIA_CREATION,
    FileFolders.EXPORTS,
)

/**
 * 把请求里的相对路径解析为 `filesDir` 下的真实文件；不允许的路径返回 `null`（路由按 404 处理）。
 *
 * 三层校验（缺一不可）：
 * 1. **段级黑名单**：拒绝 `/` 开头、`\`、NUL、空段、`.`、`..`（`..` 必须在段级判断，
 *    `contains("..")` 会把合法文件名 `a..b` 也误杀，而 `%2e%2e` 解码后同样是 `..` 段）；
 * 2. **根目录白名单**：[WEB_READABLE_FILE_FOLDERS]，挡住 `datastore/`、`logs/` 这类内部目录；
 * 3. **canonical 边界**：白名单根**必须真实存在且不是软链**（`canonical == 预期路径`；否则
 *    `upload -> datastore` 这种整目录软链会让根落在 filesDir 内的敏感目录），最终文件
 *    canonical 后必须仍位于该根之下（`+ File.separator` 前缀，避免同前缀兄弟目录混过）。
 */
internal fun resolveWebReadableFile(filesDir: File, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    if (relativePath.startsWith('/')) return null
    if (relativePath.contains('\\') || relativePath.contains('\u0000')) return null

    val segments = relativePath.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null

    val rootName = segments.first()
    if (rootName !in WEB_READABLE_FILE_FOLDERS) return null

    val canonicalFilesDir = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
    val expectedRoot = File(canonicalFilesDir, rootName)
    val canonicalRoot = runCatching { expectedRoot.canonicalFile }.getOrNull()
        ?: return null
    // 根本身必须是**真实目录**，不能是软链：若 `filesDir/upload` 整个软链到 `filesDir/datastore`，
    // 则 canonicalRoot 会等于 datastore 路径（仍在 filesDir 前缀内），仅靠下面的前缀校验就会被绕过。
    // 判断方式：canonical 结果必须逐字等于「canonical filesDir / 白名单根名」。
    if (canonicalRoot.path != expectedRoot.path) return null

    val candidate = File(canonicalFilesDir, segments.joinToString("/"))
    val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
    if (!canonicalCandidate.path.startsWith(canonicalRoot.path + File.separator)) return null

    return canonicalCandidate
}

// GET /api/assets/{...} - Get file from app assets
fun Route.assetsRoutes(context: Context) {
    route("/assets") {
        get("/{path...}") {
            val relativePath = call.pathParameters.getAll("path")?.joinToString("/")
                ?: throw BadRequestException("Missing asset path")

            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                throw BadRequestException("Invalid asset path")
            }

            val contentType = when (relativePath.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> ContentType.Image.JPEG
                "png" -> ContentType.Image.PNG
                "gif" -> ContentType.Image.GIF
                "webp" -> ContentType("image", "webp")
                "svg" -> ContentType.Image.SVG
                "json" -> ContentType.Application.Json
                "html" -> ContentType.Text.Html
                "txt" -> ContentType.Text.Plain
                else -> ContentType.Application.OctetStream
            }

            try {
                val inputStream = context.assets.open(relativePath)
                call.response.header("Content-Type", contentType.toString())
                call.respondOutputStream {
                    inputStream.use { it.copyTo(this) }
                }
            } catch (_: Exception) {
                throw NotFoundException("Asset not found: $relativePath")
            }
        }
    }
}

private suspend fun readPartBytes(part: PartData.FileItem, maxBytes: Int): ByteArray {
    val input = part.provider()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0

    while (true) {
        val read = input.readAvailable(buffer, 0, buffer.size)
        if (read <= 0) break

        totalBytes += read
        if (totalBytes > maxBytes) {
            throw BadRequestException("File too large: max ${maxBytes / (1024 * 1024)} MB")
        }

        output.write(buffer, 0, read)
    }

    return output.toByteArray()
}

private fun sanitizeDisplayName(fileName: String): String {
    val normalized = fileName.substringAfterLast('/').substringAfterLast('\\')
    return normalized
        .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        .ifBlank { "file" }
}

private fun ManagedFileEntity.toUploadedFileDto(filesManager: FilesManager) = UploadedFileDto(
    id = id,
    url = filesManager.getFile(this).toUri().toString(),
    fileName = displayName,
    mime = mimeType,
    size = sizeBytes,
)
