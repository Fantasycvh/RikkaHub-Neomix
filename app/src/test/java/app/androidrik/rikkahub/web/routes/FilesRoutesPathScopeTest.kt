package app.androidrik.rikkahub.web.routes

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/api/files/path/{path}` 的**读取边界**（旧实现只校验 canonical 前缀是 filesDir）：
 *
 * 被钉住的缺陷：`datastore/settings.preferences_pb`（Preferences DataStore，明文 API key /
 * 密码 / WebDAV-S3 凭据）与 `logs/` 下的文件（诊断日志）都能被整份下载；目录内软链还能指向私有目录。
 * 修复是「根目录白名单 + canonical 边界」，这里直接测纯函数 [resolveWebReadableFile]。
 *
 * 同时确认**正常媒体/附件**仍可读（upload / avatars / images / media_creation / exports），
 * 避免"修安全把功能修坏"。
 */
class FilesRoutesPathScopeTest {

    private val filesDir: File = Files.createTempDirectory("rikkahub-files").toFile()

    @After
    fun cleanup() {
        filesDir.deleteRecursively()
    }

    private fun write(relativePath: String): File =
        File(filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeText("x")
        }

    @Test
    fun `media and attachment roots stay readable`() {
        listOf(
            "upload/photo.png",
            "avatars/assistant.png",
            "images/1729500000_model_0.png",
            "media_creation/session-1/output.png",
            "exports/conversation.json",
        ).forEach { path ->
            val file = write(path)
            assertEquals(
                "正常媒体/附件必须可读：$path",
                file.canonicalFile,
                resolveWebReadableFile(filesDir, path),
            )
        }
    }

    @Test
    fun `datastore and logs are not exposed`() {
        write("datastore/settings.preferences_pb")
        write("logs/app.log")

        listOf("datastore/settings.preferences_pb", "logs/app.log").forEach { path ->
            assertNull("凭据/日志目录不能暴露：$path", resolveWebReadableFile(filesDir, path))
        }
        assertFalse("datastore" in WEB_READABLE_FILE_FOLDERS)
        assertFalse("logs" in WEB_READABLE_FILE_FOLDERS)
        assertFalse("workspaces" in WEB_READABLE_FILE_FOLDERS)
    }

    @Test
    fun `non-media internal folders are not exposed`() {
        listOf(
            "skills/evil.md",
            "builtin_skills/x.md",
            "fonts/x.ttf",
            "tool_outputs/x.txt",
            "avatar_backup/x.bin",
            "other/x.txt",
        ).forEach { path ->
            write(path)
            assertNull("非媒体目录不能暴露：$path", resolveWebReadableFile(filesDir, path))
        }
    }

    @Test
    fun `traversal and malformed paths are rejected`() {
        write("datastore/settings.preferences_pb")
        write("upload/ok.png")

        listOf(
            "../datastore/settings.preferences_pb",
            "upload/../datastore/settings.preferences_pb",
            "upload/../../etc/passwd",
            "/etc/passwd",
            "upload//ok.png",
            "upload/./ok.png",
            "upload/",
            "",
            "   ",
            "upload\\..\\datastore\\settings.preferences_pb",
            "upload/\u0000ok.png",
            "..",
        ).forEach { path ->
            assertNull("非法路径必须拒绝：${path.replace("\u0000", "\\0")}", resolveWebReadableFile(filesDir, path))
        }
    }

    @Test
    fun `symlink escaping an allowed root is rejected`() {
        write("datastore/settings.preferences_pb")
        File(filesDir, "upload").mkdirs()
        val link = File(filesDir, "upload/leak")
        val created = runCatching {
            Files.createSymbolicLink(link.toPath(), File(filesDir, "datastore").toPath())
        }.isSuccess
        if (!created) {
            // Windows 默认无符号链接权限时跳过；Linux CI 会真正执行这条断言。
            return
        }
        assertNull(
            "upload 内的软链不能读到 datastore",
            resolveWebReadableFile(filesDir, "upload/leak/settings.preferences_pb"),
        )
    }

    @Test
    fun `symlinked allowed root cannot point at a private directory`() {
        write("datastore/settings.preferences_pb")
        val root = File(filesDir, "upload")
        root.deleteRecursively()
        val created = runCatching {
            // 整目录软链：upload -> datastore。仅靠「canonical 仍在 filesDir 下」的前缀校验会被绕过。
            Files.createSymbolicLink(root.toPath(), File(filesDir, "datastore").toPath())
        }.isSuccess
        if (!created) {
            return
        }
        assertNull(
            "白名单根自身是软链时必须拒绝（否则 upload -> datastore 可读明文凭据）",
            resolveWebReadableFile(filesDir, "upload/settings.preferences_pb"),
        )
    }
}
