package app.androidrik.rikkahub.data.files

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-7 回归（源码守卫）：`AssistantImporter` 不能对「背景图落盘结果」直接 `.first()`。
 *
 * `FilesManager.createChatFilesByContents` 在文件名/MIME/输入流失败时按既有语义**跳过**该项
 * 并返回空列表；此时 `.first()` 抛 `NoSuchElementException`（文案只有 "List is empty."），
 * 导入被中断且提示不可读。修复后必须走 `firstOrNull() ?: error(明确文案)`。
 *
 * 为什么不写行为测试：该路径要 Compose + Koin + SAF `content://`（本仓无 Robolectric）。
 * 本仓已有同类源码守卫先例（`ConversationRouteMetadataWriteBackGuardTest`）。
 * 文件放在 `data/files` 测试目录，是因为本任务的可写测试 scope 限定在这里。
 */
class AssistantImporterBackgroundSaveGuardTest {

    private fun importerSource(): String {
        val candidates = listOf(
            "src/main/java/app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt",
            "app/src/main/java/app/androidrik/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt",
        )
        return candidates.map(::File)
            .firstOrNull { it.exists() }
            ?.readText()
            ?.replace("\r\n", "\n")
            ?: error("找不到 AssistantImporter.kt（测试工作目录=${File(".").absolutePath}）")
    }

    @Test
    fun `character card background save uses firstOrNull with an explicit failure message`() {
        val source = importerSource()
        assertFalse(
            "背景图落盘结果不能直接 .first()（空列表会抛 NoSuchElementException 中断导入）",
            source.contains("createChatFilesByContents(listOf(uri)).first()"),
        )
        assertTrue(
            "必须用 firstOrNull() 并显式失败",
            source.contains("createChatFilesByContents(listOf(uri)).firstOrNull()"),
        )
        assertTrue(
            "失败信息必须可读（不能只有 'List is empty.'）",
            source.contains("Failed to save character card background image"),
        )
    }
}
