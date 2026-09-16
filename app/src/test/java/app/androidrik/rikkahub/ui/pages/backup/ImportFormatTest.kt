package app.androidrik.rikkahub.ui.pages.backup

import app.androidrik.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 导入格式描述符的不变量 + `mergeProviders` 语义。
 *
 * 这些是"共享管线"赖以成立的前提：管线按描述符字段分流，字段写错就会静默走错分支
 * （例如把某一格式的 `needsRestartDialog` 写反 → 导入后不提示重启，用户以为没生效）。
 */
class ImportFormatTest {

    @Test
    fun `every format has exactly one spec`() {
        assertEquals(ImportFormat.entries.size, ImportFormatSpecs.size)
        ImportFormat.entries.forEach { format ->
            assertEquals(
                "每个 ImportFormat 必须有且仅有一个描述符",
                1,
                ImportFormatSpecs.count { it.format == format },
            )
            assertEquals(format, specOf(format).format)
        }
    }

    @Test
    fun `every spec declares at least one mime and non-zero strings`() {
        ImportFormatSpecs.forEach { spec ->
            assertTrue("${spec.format} 需要 MIME 过滤器", spec.mimes.isNotEmpty())
            spec.mimes.forEach { assertTrue("MIME 不能为空", it.isNotBlank()) }
            assertTrue("${spec.format} 缺少标题", spec.titleRes != 0)
            assertTrue("${spec.format} 缺少副标题", spec.descRes != 0)
        }
    }

    @Test
    fun `conversation and chatbox require an assistant while provider-only formats do not`() {
        // 这两个格式会导入**会话**，必须由用户指定归属助手（chatbox 是本轮补上的缺口）
        assertTrue(specOf(ImportFormat.CONVERSATION).needsAssistant)
        assertTrue(specOf(ImportFormat.CHATBOX).needsAssistant)
        // 整库覆盖与 cherry（仅 providers，无会话）结构上不适用"按会话归属"
        assertFalse(specOf(ImportFormat.LOCAL_BACKUP).needsAssistant)
        assertFalse(specOf(ImportFormat.CHERRY_STUDIO).needsAssistant)
    }

    @Test
    fun `only the whole-database restore confirms before picking a file`() {
        // 覆盖不可逆 → 先确认;其余格式无需事前确认（conversation 的确认在解析后，含重复警告）
        assertTrue(specOf(ImportFormat.LOCAL_BACKUP).needsPrePickConfirm)
        ImportFormat.entries
            .filter { it != ImportFormat.LOCAL_BACKUP }
            .forEach { assertFalse("${it} 不应在选文件前确认", specOf(it).needsPrePickConfirm) }
    }

    @Test
    fun `restart is required for db-level and provider imports but not single conversation`() {
        // 整库/chatbox/cherry 都改了 DB 或设置 → 提示重启
        assertTrue(specOf(ImportFormat.LOCAL_BACKUP).needsRestartDialog)
        assertTrue(specOf(ImportFormat.CHATBOX).needsRestartDialog)
        assertTrue(specOf(ImportFormat.CHERRY_STUDIO).needsRestartDialog)
        // 单会话导入不需重启（会话已直接落库并可立即查看）
        assertFalse(specOf(ImportFormat.CONVERSATION).needsRestartDialog)
    }

    @Test
    fun `conversation accepts both zip and legacy bare json`() {
        // 阶段 2 容器是 zip；仍接受阶段 1 的裸 JSON（解析侧按 zip 魔数判别）
        val mimes = specOf(ImportFormat.CONVERSATION).mimes
        assertTrue(mimes.contains("application/zip"))
        assertTrue(mimes.contains("application/json"))
    }

    @Test
    fun `chatbox uses json and cherry uses zip`() {
        assertEquals(listOf("application/json"), specOf(ImportFormat.CHATBOX).mimes)
        assertEquals(listOf("application/zip"), specOf(ImportFormat.CHERRY_STUDIO).mimes)
    }

    @Test
    fun `conversation is listed first because it is the only lossless round trip`() {
        assertEquals(ImportFormat.CONVERSATION, ImportFormatSpecs.first().format)
    }

    // ---- mergeProviders ----

    /** 造一个最小可用的 provider（直接用 OpenAI 类型，避开 sealed 父类无 copy 的问题）。 */
    private fun provider(name: String): ProviderSetting = ProviderSetting.OpenAI(
        id = Uuid.random(),
        name = name,
        baseUrl = "https://example.com/v1",
        apiKey = "k",
    )

    @Test
    fun `mergeProviders puts imported ones first`() {
        val current = listOf(provider("current"))
        val imported = listOf(provider("imported"))
        val merged = mergeProviders(current, imported)
        assertEquals(listOf("imported", "current"), merged.map { it.name })
    }

    @Test
    fun `mergeProviders keeps duplicates by design`() {
        // 既有行为：不去重（重复导入同一文件会产生重复 provider）。
        // 去重要先定义"什么算同一个 provider"，属独立决策 —— 此测试把这个行为钉住，
        // 将来若决定去重，会在这里失败并提醒改文档。
        val same = provider("dup")
        val merged = mergeProviders(listOf(same), listOf(same))
        assertEquals(2, merged.size)
    }

    @Test
    fun `mergeProviders with empty imported is identity`() {
        val current = listOf(provider("a"), provider("b"))
        assertEquals(current.map { it.id }, mergeProviders(current, emptyList()).map { it.id })
    }

    @Test
    fun `mergeProviders with empty current just yields imported`() {
        val imported = listOf(provider("x"))
        assertEquals(listOf("x"), mergeProviders(emptyList(), imported).map { it.name })
    }

    @Test
    fun `spec lookups stay in sync with the list`() {
        ImportFormatSpecs.forEach { spec ->
            assertNotNull(specOf(spec.format))
        }
    }
}
