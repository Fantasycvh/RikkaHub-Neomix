package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `FILES` 备份的**真实打包**（产出 zip 后断言条目名）。
 *
 * 为什么必须有这层测试：2026-10-04 真机事故——判据从 `== SKILLS` 改成
 * `in RECURSIVE_FILE_FOLDERS` 时 `entryPrefix` 漏改（仍是硬编码 `skills/`），
 * 生成图片被打成 `skills/...`：既不在 `images/` 下（记录找不到），恢复还会解进
 * `files/skills/`。当时打包逻辑藏在需要 Android Context 的引擎里，测不到；
 * 现在 [packAppFiles] 只依赖 filesDir，可直接验证「每个目录的条目落在自己的前缀下」。
 */
class AppFilesPackingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesDirWithContents(): File {
        val filesDir = tmp.newFolder("files")
        File(filesDir, FileFolders.UPLOAD).apply { mkdirs() }.resolve("a.jpg").writeText("upload")
        File(filesDir, FileFolders.IMAGES).apply { mkdirs() }.resolve("pic.png").writeText("img")
        // 遗留形态：含 "/" 的模型名曾让 imggen 产物落进子目录
        File(filesDir, "${FileFolders.IMAGES}/1788750812843_vendor").mkdirs()
        File(filesDir, "${FileFolders.IMAGES}/1788750812843_vendor/model_0.png").writeText("legacy-img")
        File(filesDir, FileFolders.FONTS).apply { mkdirs() }.resolve("f.ttf").writeText("font")
        File(filesDir, "${FileFolders.SKILLS}/my-skill").apply { mkdirs() }.resolve("SKILL.md").writeText("skill")
        return filesDir
    }

    private fun packAndList(filesDir: File): List<String> {
        val zip = File(tmp.root, "out.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            packAppFiles(filesDir = filesDir, zipOut = out, onBytes = {})
        }
        return ZipFile(zip).use { zf -> zf.entries().toList().map(ZipEntry::getName).sorted() }
    }

    @Test
    fun `every folder is written under its own prefix`() {
        val entries = packAndList(filesDirWithContents())

        assertEquals(
            listOf(
                "fonts/f.ttf",
                "images/pic.png",
                // 遗留子目录按原层级保留（换设备恢复后仍能按旧形态找回）
                "images/1788750812843_vendor/model_0.png",
                "skills/my-skill/SKILL.md",
                "upload/a.jpg",
            ).sorted(),
            entries,
        )
        // 事故形态：任何非 skills 的文件都不允许落在 skills/ 前缀下
        assertTrue(
            "只有技能自己的文件才该出现在 skills/ 下",
            entries.filter { it.startsWith("${FileFolders.SKILLS}/") } == listOf("skills/my-skill/SKILL.md"),
        )
    }

    @Test
    fun `missing folders are skipped without failing the backup`() {
        val filesDir = tmp.newFolder("empty")
        File(filesDir, FileFolders.IMAGES).mkdirs() // 只有 images
        File(filesDir, FileFolders.IMAGES).resolve("only.png").writeText("x")

        assertEquals(listOf("images/only.png"), packAndList(filesDir))
    }
}
