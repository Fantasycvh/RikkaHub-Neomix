package app.androidrik.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SCHEMA_VERSION` 防漂移的**编译期闸门**（设计稿 docs/design/design-db-downgrade-rebuild.md §0）。
 *
 * `app/schemas/<fqcn>/N.json` 由 KSP 在每次构建时从 `@Database(version = …)` 注解自动生成，
 * 最大的 N 就是 Room 认定的真实 schema 版本。常量（启动期 staged 库校验、未来的降级检测都以
 * 它为基准）与 N 对不上时在这里红，而不是等用户机上启动校验炸。
 *
 * 历史事故：常量停在 54、真实版本已到 56，v55/56 的库过不了 `validateStagedDatabase`
 * 的 `version in 1..SCHEMA_VERSION` 检查——从最新备份恢复被直接拒绝（2026-10-31 根治）。
 *
 * 纯 JVM 可跑：只读文件系统；`AppDatabase.SCHEMA_VERSION` 是 const（编译期内联），
 * 运行时不会初始化 AppDatabase 类（其父类 RoomDatabase 依赖 Android 运行时）。
 */
class SchemaVersionConsistencyTest {

    @Test
    fun `SCHEMA_VERSION 与最新导出的 Room schema 一致`() {
        val schemaDir = sequenceOf("schemas", "app/schemas")
            .map { File(it, "app.androidrik.rikkahub.data.db.AppDatabase") }
            .firstOrNull { it.isDirectory }
            ?: error(
                "Room schema directory not found — AGP unit test 的工作目录应为 app 模块根目录，" +
                    "或 schemas 导出（room.schemaLocation）被移走了",
            )

        val latest = schemaDir
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?.maxOrNull()
        assertTrue("No schema JSON under $schemaDir", latest != null)

        assertEquals(
            "AppDatabase.SCHEMA_VERSION 与 @Database(version = …) 漂移了：schemas 最新为 " +
                "$latest，常量为 ${AppDatabase.SCHEMA_VERSION}。请同步常量（或检查是否漏跑 KSP）。",
            latest,
            AppDatabase.SCHEMA_VERSION,
        )
    }
}
