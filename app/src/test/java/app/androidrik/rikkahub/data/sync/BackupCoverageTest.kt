package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份目录覆盖护栏（Yuihub `3eddec42` 同款）：`FILES` 项覆盖的目录清单此前在
 * 打包 / 恢复 / 空间预检三处各自手列，新增目录漏改一处就是「静默不备份」。
 * 现由 [BACKED_UP_FILE_FOLDERS] 统一驱动，本测试钉住它与 [FileFolders] 全集的
 * 对应关系——新增目录只改 FileFolders 就会让测试红，强制显式决策「进备份还是豁免」。
 */
class BackupCoverageTest {

    @Test
    fun `backed up folders and excluded folders cover every declared folder`() {
        val all = (BACKED_UP_FILE_FOLDERS + EXCLUDED_FILE_FOLDERS.keys).toSet()
        val declared = setOf(
            FileFolders.UPLOAD,
            FileFolders.IMAGES,
            FileFolders.SKILLS,
            FileFolders.BUILTIN_SKILLS,
            FileFolders.FONTS,
            FileFolders.TOOL_OUTPUTS,
            FileFolders.LOGS,
            FileFolders.EXPORTS,
        )
        assertEquals(
            "BACKED_UP + EXCLUDED 必须恰好覆盖 FileFolders 全集（漏掉的目录 = 静默不备份）",
            declared,
            all,
        )
    }

    @Test
    fun `backed up folders and excluded folders do not overlap`() {
        val overlap = BACKED_UP_FILE_FOLDERS.toSet() intersect EXCLUDED_FILE_FOLDERS.keys
        assertTrue("同一目录不能既进备份又豁免: $overlap", overlap.isEmpty())
    }

    @Test
    fun `excluded folders all carry a documented reason`() {
        EXCLUDED_FILE_FOLDERS.forEach { (folder, reason) ->
            assertTrue("豁免目录 $folder 缺少理由说明", reason.isNotBlank())
        }
    }
}
