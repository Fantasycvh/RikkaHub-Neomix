package app.androidrik.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 技能文件保存的原子性与失败语义（上游 `9f02586d`）。
 *
 * 原实现 `target.writeText(content)` 直接写目标：IO 失败**抛异常**——调用方
 * `SkillDetailVM.saveFile` 在 IO 协程里没有 try/catch，异常冒到 viewModelScope ⇒
 * **保存失败 = 应用崩溃**；且写到一半失败会留下半截文件（原内容已毁）。
 *
 * 测试目标是抽出来的顶层 [writeFileAtomically]（`SkillManager` 需要 Android Context，
 * 本仓无 Robolectric/ mock 库，故把纯逻辑独立出来）。
 */
class SkillAtomicWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `writes content and leaves no temp residue`() {
        val target = File(tempFolder.root, "demo/SKILL.md")
        assertTrue(writeFileAtomically(target, "hello"))

        assertEquals("hello", target.readText())
        assertFalse("临时文件必须清理", File(target.parentFile, ".SKILL.md.tmp").exists())
    }

    @Test
    fun `creates missing parent directory`() {
        val target = File(tempFolder.root, "nested/one/two/file.txt")
        assertTrue(writeFileAtomically(target, "x"))
        assertEquals("x", target.readText())
    }

    @Test
    fun `overwrites existing file`() {
        val target = File(tempFolder.root, "file.txt").apply { writeText("old") }
        assertTrue(writeFileAtomically(target, "new"))
        assertEquals("new", target.readText())
    }

    @Test
    fun `failure returns false instead of throwing and leaves no temp file`() {
        // 目标位置被同名**目录**占住 ⇒ rename 必然失败（POSIX EISDIR / Windows MoveFile 失败）
        val target = File(tempFolder.root, "occupied")
        target.mkdirs()
        File(target, "inner.txt").writeText("in the way")

        val result = runCatching { writeFileAtomically(target, "payload") }

        // 关键契约：不抛异常（否则调用方协程崩溃），而是如实返回 false
        assertEquals("不应抛出，应返回 false", false, result.getOrNull())
        assertFalse("失败时临时文件必须清理", File(tempFolder.root, ".occupied.tmp").exists())
        // 目录内容未被破坏
        assertEquals("in the way", File(target, "inner.txt").readText())
    }

    @Test
    fun `existing file survives when the write path fails`() {
        // 用「目标父目录被同名文件占住」模拟 mkdirs 失败：写入无法进行，但已有文件不受影响
        val blocker = File(tempFolder.root, "blocker").apply { writeText("I am a file") }
        val target = File(blocker, "child.txt") // blocker 是文件 ⇒ 无法作为目录使用

        val result = runCatching { writeFileAtomically(target, "payload") }

        assertEquals("不应抛出，应返回 false", false, result.getOrNull())
        assertEquals("原有文件必须完好", "I am a file", blocker.readText())
    }
}
