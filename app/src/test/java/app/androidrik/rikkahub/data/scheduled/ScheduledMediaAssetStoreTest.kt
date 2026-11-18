package app.androidrik.rikkahub.data.scheduled

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 素材锁定仓库的纯文件操作：清理只动未引用的文件，删除不出素材根目录。 */
class ScheduledMediaAssetStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // 与生产一致：实例方法传的是 filesDir/scheduled_media 这一层
    private val assetsRoot get() = File(tmp.root, "scheduled_media")

    private fun touch(path: String): File {
        val file = File(tmp.root, path)
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1))
        return file
    }

    @Test
    fun `pruning keeps referenced files and removes replaced ones`() = runTest {
        val kept = touch("scheduled_media/task-a/keep.jpg")
        val replaced = touch("scheduled_media/task-a/stale.jpg")
        pruneUnreferenced(assetsRoot, "task-a", setOf("scheduled_media/task-a/keep.jpg"))
        assertTrue(kept.isFile)
        assertFalse(replaced.exists())
    }

    @Test
    fun `deleting assets removes only referenced files and prunes the empty dir`() = runTest {
        val other = touch("scheduled_media/task-a/other.jpg")
        val asset = touch("scheduled_media/task-b/gone.jpg")
        deleteAssetFiles(assetsRoot, listOf(ScheduledMediaAsset("scheduled_media/task-b/gone.jpg")))
        assertFalse(asset.exists())
        assertFalse(File(assetsRoot, "task-b").exists())
        assertTrue(other.isFile)
    }

    @Test
    fun `malformed asset paths cannot escape the assets root`() = runTest {
        val outside = touch("scheduled_media/innocent.jpg")
        val referenced = touch("scheduled_media/task-c/real.jpg")
        deleteAssetFiles(
            assetsRoot,
            listOf(
                ScheduledMediaAsset("../secret.jpg"),
                ScheduledMediaAsset("scheduled_media//no-dir/escape.jpg"),
                ScheduledMediaAsset("random.jpg"),
            ),
        )
        // 畸形路径一个文件都不动，素材根之外也不该出现文件
        assertTrue(outside.isFile)
        assertFalse(File(tmp.root, "secret.jpg").exists())
        // 合法引用照常删除
        deleteAssetFiles(assetsRoot, listOf(ScheduledMediaAsset("scheduled_media/task-c/real.jpg")))
        assertFalse(referenced.exists())
    }
}
