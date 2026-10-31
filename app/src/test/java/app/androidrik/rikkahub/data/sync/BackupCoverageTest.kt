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
            FileFolders.AVATARS,
            FileFolders.BACKGROUNDS,
            FileFolders.SKILLS,
            FileFolders.BUILTIN_SKILLS,
            FileFolders.FONTS,
            FileFolders.TOOL_OUTPUTS,
            FileFolders.LOGS,
            FileFolders.EXPORTS,
            FileFolders.MEDIA_CREATION,
            FileFolders.SAFETY_BACKUP,
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

    @Test
    fun `recursive classification only covers backed up folders`() {
        val unknown = RECURSIVE_FILE_FOLDERS - BACKED_UP_FILE_FOLDERS.toSet()
        assertTrue("递归清单里有未进备份的目录: $unknown", unknown.isEmpty())
    }

    @Test
    fun `entry prefix is always derived from the folder name`() {
        // 事故护栏（2026-10-04 真机）：判据从 `== skills` 改成 `in RECURSIVE_FILE_FOLDERS` 时，
        // entryPrefix 曾漏改而硬编码 skills/ ⇒ 生成的图片被打成 `skills/...`（图片既不在
        // images/ 下、恢复又会被解进 files/skills/）。这里对**每一个**目录钉住前缀来源。
        appFilesPackingPlan().forEach { packing ->
            assertEquals(
                "条目前缀必须由目录名派生（曾把 images 打成 skills/ 前缀）",
                "${packing.folder}/",
                packing.entryPrefix,
            )
        }
    }

    @Test
    fun `images is packed recursively while upload and fonts stay flat`() {
        val plan = appFilesPackingPlan().associateBy { it.folder }
        assertEquals(true, plan.getValue(FileFolders.IMAGES).recursive)
        assertEquals(true, plan.getValue(FileFolders.SKILLS).recursive)
        assertEquals(false, plan.getValue(FileFolders.UPLOAD).recursive)
        assertEquals(false, plan.getValue(FileFolders.FONTS).recursive)
    }

    @Test
    fun `images must stay recursive to preserve legacy subdirectory files`() {
        // 2026-10-04 之前含 "/" 的模型名让产物落进 images/<ts>_<vendor>/ 子目录。
        // 平铺打包会**静默跳过**这些文件 ⇒ 读取侧虽已做旧形态回退查找（ImgGenVM），
        // 换设备恢复后仍会丢图。这条测试防止将来有人把它改回平铺。
        assertTrue(FileFolders.IMAGES in RECURSIVE_FILE_FOLDERS)
    }
}
