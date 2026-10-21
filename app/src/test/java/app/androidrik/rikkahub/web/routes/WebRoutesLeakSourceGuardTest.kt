package app.androidrik.rikkahub.web.routes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两条 Web 泄漏修复的**调用点源码守卫**（与 `ConversationRouteMetadataWriteBackGuardTest`
 * 同一套做法：行为断言需要起 Ktor + DataStore 环境，JVM 单测起不来，只能钉调用形态）。
 *
 * 1. `EventsRoutes` 的 `settings` 事件必须走 `toWebSettingsDto()` 白名单，
 *    不得回到 `encodeToString(settings)` 全量下发（那会把 provider apiKey / 助手人设 /
 *    MCP headers 一起发出去）；
 * 2. `FilesRoutes` 的 `/api/files/path` 必须走 `resolveWebReadableFile`（根目录白名单 +
 *    canonical 边界），不得回到「只比 filesDir canonical 前缀」的旧校验（会放行 datastore/、logs/）。
 */
class WebRoutesLeakSourceGuardTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun eventsRoutesSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/web/routes/EventsRoutes.kt",
        "app/src/main/java/app/androidrik/rikkahub/web/routes/EventsRoutes.kt",
    ).readText().replace("\r\n", "\n")

    private fun filesRoutesSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/web/routes/FilesRoutes.kt",
        "app/src/main/java/app/androidrik/rikkahub/web/routes/FilesRoutes.kt",
    ).readText().replace("\r\n", "\n")

    @Test
    fun `settings sse event goes through the whitelist dto`() {
        val source = eventsRoutesSource()
        assertTrue(
            "settings 事件必须映射成白名单 DTO",
            source.contains("toWebSettingsDto()"),
        )
        assertFalse(
            "不能直接下发全量 Settings（凭据/人设泄漏）",
            source.contains("encodeToString(settings)"),
        )
    }

    @Test
    fun `files path route keeps root whitelist and canonical boundary`() {
        val source = filesRoutesSource()
        assertTrue(
            "/api/files/path 必须走根目录白名单解析",
            source.contains("resolveWebReadableFile(context.filesDir, relativePath)"),
        )
        assertTrue(
            "/api/files/id 也必须过同一套解析（DB 行可能被篡改 / 目录被软链替换）",
            source.contains("resolveWebReadableFile(context.filesDir, entity.relativePath)"),
        )
        assertFalse(
            "旧的「仅校验 filesDir canonical 前缀」会放行 datastore/ 与 logs/",
            source.contains("file.canonicalPath.startsWith(filesDir.canonicalPath)"),
        )
        assertFalse(
            "白名单不得放行 logs（诊断日志）",
            source.contains("FileFolders.LOGS"),
        )
        // 两个下载端点都要加 CSP sandbox：上传的 HTML/SVG 以应用同源打开会偷 sessionStorage 里的 token
        assertEquals(
            "两个文件下载端点都必须加 Content-Security-Policy",
            2,
            Regex("Content-Security-Policy").findAll(source).count(),
        )
        assertTrue(
            "CSP 必须是 sandbox 且禁子资源",
            source.contains("SANDBOXED_FILE_CSP"),
        )
    }
}
