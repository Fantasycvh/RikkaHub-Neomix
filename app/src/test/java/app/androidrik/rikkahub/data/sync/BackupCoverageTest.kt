package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.files.FileFolders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 备份目录覆盖护栏（P0 重构后）：`BackupFolderRegistry` 是唯一真源，
 * 本测试用**反射**读取 [FileFolders] 声明的全部目录常量，断言「进备份 ∪ 豁免」恰好覆盖全集。
 *
 * 为什么要反射：旧实现里 `declared` 是手写集合，新增目录（`AVATAR_FRAMES`）不会让测试红，
 * 于是「avatar-frames 静默不备份」「backgrounds 打包但恢复丢」两处漂移都没被发现。
 * 现在新增一个 `FileFolders` 常量而不同时登记进 registry，本测试就会失败。
 *
 * 另一组断言覆盖**恢复分派**：每个进备份的目录都必须能通过 [backupFolderSpecFor] 反查到规格，
 * 否则引擎会像旧代码一样漏掉恢复分支。
 */
class BackupCoverageTest {

    /** `object FileFolders` 的 `const val` 在 JVM 上是静态 String 字段。 */
    private fun declaredFileFolderConstants(): Set<String> =
        FileFolders::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as String }
            .toSet()

    @Test
    fun `backed up folders and excluded folders cover every declared folder`() {
        val declared = declaredFileFolderConstants()
        // 反射至少要能读出目前已知的目录，否则说明护栏本身失效了
        assertTrue("反射应至少读到 upload/avatars 等已知目录", FileFolders.UPLOAD in declared)
        assertTrue("反射应至少读到新增的 backup_index", FileFolders.BACKUP_INDEX in declared)

        val all = (BACKED_UP_FILE_FOLDERS + EXCLUDED_FILE_FOLDERS.keys).toSet()
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
    fun `every backed up folder has a restore dispatch spec`() {
        BACKED_UP_FILE_FOLDERS.forEach { folder ->
            val spec = backupFolderSpecFor("$folder/some-file.bin")
            assertNotNull("目录 $folder 进了备份但反查不到恢复规格（恢复会静默跳过）", spec)
            assertEquals("恢复规格必须指向同名前缀", folder, spec!!.folder)
        }
    }

    @Test
    fun `backgrounds and avatar-frames restore through the flat path`() {
        // 2026-11 调研发现的两处真实漂移，回归钉住：
        // ① backgrounds 进了打包清单但恢复 when 没有分支 → 恢复后助手背景丢；
        // ② avatar-frames 既不打包也不豁免 → 静默不备份。
        assertTrue(FileFolders.BACKGROUNDS in BACKED_UP_FILE_FOLDERS)
        assertTrue(FileFolders.AVATAR_FRAMES in BACKED_UP_FILE_FOLDERS)
        assertEquals(
            BackupRestoreKind.FLAT,
            backupFolderSpecFor("${FileFolders.BACKGROUNDS}/bg.png")?.restoreKind,
        )
        assertEquals(
            BackupRestoreKind.FLAT,
            backupFolderSpecFor("${FileFolders.AVATAR_FRAMES}/frame.png")?.restoreKind,
        )
    }

    @Test
    fun `registry specs are unique and every spec is either backed up or excluded`() {
        val folders = BACKUP_FOLDER_SPECS.map { it.folder }
        assertEquals("registry 目录不能重复", folders.size, folders.toSet().size)
        BACKUP_FOLDER_SPECS.forEach { spec ->
            assertTrue(
                "规格必须二选一：进备份或写豁免理由（${spec.folder}）",
                (spec.excludedReason != null) xor spec.backedUp,
            )
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
        assertEquals(true, plan.getValue(FileFolders.MEDIA_CREATION).recursive)
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
