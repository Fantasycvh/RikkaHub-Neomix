package app.androidrik.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移 35 → 36 的**导出 schema 守卫**。
 *
 * 钉的是两件容易静默出错的事：
 * 1. 自动迁移的 DDL 由导出的 schema 决定——schema 没跟着一起提交，别人拉下来构建时会直接失败
 *    （或者更糟：本地留着一份没提交的 schema，只有自己跑得过）；
 * 2. `@ColumnInfo(defaultValue = ...)` 与实体里的默认值**必须一致**：否则"新装"与"升级上来"的
 *    默认值会不同（老库拿到的是 DDL 默认值），表现为"同一份设置，两个用户行为不一样"。
 */
class WorkspaceReadLimitSchemaTest {

    private fun schemaRoot(): File {
        val candidates = listOf(
            File("schemas/app.androidrik.rikkahub.data.db.AppDatabase"),
            File("app/schemas/app.androidrik.rikkahub.data.db.AppDatabase"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到导出的 schema 目录，工作目录=${File(".").absolutePath}")
    }

    private fun schema(version: Int): String {
        val file = schemaRoot().resolve("$version.json")
        assertTrue("缺少导出的 schema：${file.path}（构建后应当生成并提交）", file.isFile)
        return file.readText()
    }

    @Test
    fun `schema 36 carries the new workspace column with the entity default`() {
        val json = schema(36)

        assertTrue(
            "36 的 workspaces 表必须带上 max_read_file_bytes",
            json.contains("max_read_file_bytes"),
        )
        assertTrue(
            "列默认值必须存在，否则升级上来的老工作区拿到的是 null/0 而不是 8MB",
            json.contains("8388608"),
        )
    }

    @Test
    fun `the entity default and the column default are the same number`() {
        val entity = entitySource()

        // 实体：const val DEFAULT_MAX_READ_FILE_BYTES = 8L * 1024 * 1024
        val expression = Regex("""const val DEFAULT_MAX_READ_FILE_BYTES = (\d+)L \* 1024 \* 1024""")
            .find(entity)?.groupValues?.get(1)?.toLong()
            ?: error("WorkspaceEntity 里找不到 DEFAULT_MAX_READ_FILE_BYTES 的常量声明")
        val entityDefault = expression * 1024 * 1024

        // DDL：@ColumnInfo("max_read_file_bytes", defaultValue = "8388608")
        val ddlDefault = Regex("""@ColumnInfo\("max_read_file_bytes", defaultValue = "(\d+)"""")
            .find(entity)?.groupValues?.get(1)?.toLong()
            ?: error("WorkspaceEntity 里找不到 max_read_file_bytes 的列默认值")

        assertEquals(
            "实体默认值与列默认值必须一致：不一致时「新装」与「从旧版升级」会拿到不同上限，",
            entityDefault,
            ddlDefault,
        )
        assertTrue(
            "导出的 schema 必须与之一致（自动迁移的 DDL 以它为准）",
            schema(36).contains("\"max_read_file_bytes\"") && schema(36).contains(ddlDefault.toString()),
        )
    }

    private fun entitySource(): String {
        val file = listOf(
            File("src/main/java/app/androidrik/rikkahub/data/db/entity/WorkspaceEntity.kt"),
            File("app/src/main/java/app/androidrik/rikkahub/data/db/entity/WorkspaceEntity.kt"),
        ).firstOrNull { it.isFile }
            ?: error("找不到 WorkspaceEntity.kt，工作目录=${File(".").absolutePath}")
        return file.readText()
    }
}
