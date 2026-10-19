package app.androidrik.rikkahub.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 画板替换原图时的原图留存（`media_creation/{session}/originals/`）。
 *
 * 草稿目录在下次打开会话时会清理没有引用的文件，所以被替换掉的原图必须先复制到会话目录的
 * originals/ 下（随会话备份、随会话删除），否则「原图保留」在下次 load 时会被清掉。
 */
@RunWith(AndroidJUnit4::class)
class MediaCreationSketchOriginalTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.cacheDir, "sketch_original_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun preserveOriginalCopiesOutsideDraftAndKeepsSourceInPlace() {
        val draft = File(root, "session/draft").apply { mkdirs() }
        val originals = File(root, "session/originals")
        val source = File(draft, "asset.png").apply { writeText("original-bytes") }

        val preserved = MediaCreationFiles.preserveOriginal(source, originals)

        assertTrue("原图应当复制成功", preserved != null)
        // 副本按来源目录分子目录，保持原文件名
        val copy = preserved!!
        assertEquals("draft", copy.parentFile!!.name)
        assertEquals(originals.canonicalPath, copy.parentFile!!.parentFile!!.canonicalPath)
        assertEquals("asset.png", copy.name)
        assertEquals("original-bytes", copy.readText())
        // 复制而不是移动：旧引用在草稿目录里仍然可读
        assertTrue(source.isFile)
        assertEquals("original-bytes", source.readText())
    }

    @Test
    fun preserveOriginalReusesExistingCopyInsteadOfOverwritingIt() {
        val draft = File(root, "session/draft").apply { mkdirs() }
        val originals = File(root, "session/originals")
        val source = File(draft, "asset.png").apply { writeText("original-bytes") }

        MediaCreationFiles.preserveOriginal(source, originals)
        // 源文件后来被改写/清空时，已经留存的副本不能被覆盖
        source.writeText("mutated")
        val again = MediaCreationFiles.preserveOriginal(source, originals)

        assertNotNull(again)
        assertEquals("original-bytes", again!!.readText())
    }

    @Test
    fun preserveOriginalKeepsSameNamedFilesFromDifferentFoldersApart() {
        val originals = File(root, "session/originals")
        val first = File(root, "session/recordA/in_0.jpg").apply {
            parentFile!!.mkdirs()
            writeText("first-record")
        }
        val second = File(root, "session/recordB/in_0.jpg").apply {
            parentFile!!.mkdirs()
            writeText("second-record")
        }

        val preservedFirst = MediaCreationFiles.preserveOriginal(first, originals)
        val preservedSecond = MediaCreationFiles.preserveOriginal(second, originals)

        assertNotNull(preservedFirst)
        assertNotNull(preservedSecond)
        val firstCopy = preservedFirst!!
        val secondCopy = preservedSecond!!
        assertEquals("first-record", firstCopy.readText())
        assertEquals("second-record", secondCopy.readText())
        assertTrue(firstCopy.path != secondCopy.path)
    }

    @Test
    fun preserveOriginalFailsCleanlyWhenDestinationCannotBeCreated() {
        val originals = File(root, "session/originals")
        // 目标父目录的位置被一个普通文件占住：mkdirs/写临时文件都会失败
        File(originals, "draft").apply {
            parentFile!!.mkdirs()
            writeText("not-a-directory")
        }
        val source = File(root, "session/draft/asset.png").apply {
            parentFile!!.mkdirs()
            writeText("original-bytes")
        }

        val preserved = MediaCreationFiles.preserveOriginal(source, originals)

        assertNull("目标不可写时必须失败，让调用方放弃替换", preserved)
        assertEquals("not-a-directory", File(originals, "draft").readText())
        assertTrue("失败时不能留下 .part 临时文件", originals.walk().none { it.name.endsWith(".part") })
        assertEquals("original-bytes", source.readText())
    }

    @Test
    fun preserveOriginalFailsForMissingSourceWithoutLeavingFiles() {
        val originals = File(root, "session/originals")

        val preserved = MediaCreationFiles.preserveOriginal(File(root, "missing.png"), originals)

        assertNull("缺源文件时必须失败，让调用方放弃替换", preserved)
        assertTrue(!originals.exists() || originals.listFiles().isNullOrEmpty())
    }
}
