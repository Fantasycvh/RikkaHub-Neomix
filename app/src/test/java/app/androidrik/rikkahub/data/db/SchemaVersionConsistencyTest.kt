package app.androidrik.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    fun `v58 adds only delivery metadata with backward compatible defaults`() {
        val directory = sequenceOf("schemas", "app/schemas")
            .map { File(it, "app.androidrik.rikkahub.data.db.AppDatabase") }
            .first { it.isDirectory }
        fun entities(version: Int) = Json.parseToJsonElement(File(directory, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
        val old = entities(57)
        val new = entities(58)
        assertEquals(old.keys, new.keys)
        old.filterKeys { it != "agent_run" }.forEach { (table, schema) ->
            assertEquals("Unrelated table $table changed", schema, new.getValue(table))
        }
        fun fields(version: Map<String, kotlinx.serialization.json.JsonObject>) =
            version.getValue("agent_run").getValue("fields").jsonArray
                .associate { it.jsonObject.getValue("columnName").jsonPrimitive.content to it.jsonObject }
        val previous = fields(old)
        val current = fields(new)
        previous.forEach { (column, schema) -> assertEquals(schema, current.getValue(column)) }
        assertEquals(setOf("parent_run_id", "depth", "scope_token", "delivery_mode", "delivery_state",
            "delivered_at", "cancel_reason"), current.keys - previous.keys)
        mapOf("depth" to "0", "delivery_mode" to "'await'", "delivery_state" to "'none'")
            .forEach { (column, default) ->
                assertEquals(default, current.getValue(column).getValue("defaultValue").jsonPrimitive.content)
                assertEquals("true", current.getValue(column).getValue("notNull").jsonPrimitive.content)
            }
    }

    @Test
    fun `v59 adds only the nullable per-conversation export display column`() {
        val directory = sequenceOf("schemas", "app/schemas")
            .map { File(it, "app.androidrik.rikkahub.data.db.AppDatabase") }
            .first { it.isDirectory }
        fun entities(version: Int) = Json.parseToJsonElement(File(directory, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
        val old = entities(58)
        val new = entities(59)
        assertEquals(old.keys, new.keys)
        // 只允许 conversations 表变化（其余表逐字节一致）
        old.filterKeys { it != "ConversationEntity" }.forEach { (table, schema) ->
            assertEquals("Unrelated table $table changed", schema, new.getValue(table))
        }
        fun fields(version: Map<String, kotlinx.serialization.json.JsonObject>) =
            version.getValue("ConversationEntity").getValue("fields").jsonArray
                .associate { it.jsonObject.getValue("columnName").jsonPrimitive.content to it.jsonObject }
        val previous = fields(old)
        val current = fields(new)
        previous.forEach { (column, schema) -> assertEquals(schema, current.getValue(column)) }
        assertEquals(setOf("export_display_config"), current.keys - previous.keys)
        // 可空纯列 ⇒ AutoMigration 免 spec、无需回填：Room 导出的字段 schema 里没有
        // notNull / defaultValue（null 就是默认），行为与改动前一致
        val added = current.getValue("export_display_config")
        assertTrue("export_display_config 应为可空列（无 notNull）", "notNull" !in added)
        assertTrue("export_display_config 不应有默认值（null 就是默认）", "defaultValue" !in added)
    }

    @Test
    fun `v60 adds only the nullable per-message export display overrides column`() {
        val directory = sequenceOf("schemas", "app/schemas")
            .map { File(it, "app.androidrik.rikkahub.data.db.AppDatabase") }
            .first { it.isDirectory }
        fun entities(version: Int) = Json.parseToJsonElement(File(directory, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
        val old = entities(59)
        val new = entities(60)
        assertEquals(old.keys, new.keys)
        old.filterKeys { it != "ConversationEntity" }.forEach { (table, schema) ->
            assertEquals("Unrelated table $table changed", schema, new.getValue(table))
        }
        fun fields(version: Map<String, kotlinx.serialization.json.JsonObject>) =
            version.getValue("ConversationEntity").getValue("fields").jsonArray
                .associate { it.jsonObject.getValue("columnName").jsonPrimitive.content to it.jsonObject }
        val previous = fields(old)
        val current = fields(new)
        previous.forEach { (column, schema) -> assertEquals(schema, current.getValue(column)) }
        assertEquals(setOf("export_display_overrides"), current.keys - previous.keys)
        // 可空纯列 ⇒ AutoMigration 免 spec、无需回填（null/{} = 没有覆盖）
        val added = current.getValue("export_display_overrides")
        assertTrue("export_display_overrides 应为可空列（无 notNull）", "notNull" !in added)
        assertTrue("export_display_overrides 不应有默认值（null 就是默认）", "defaultValue" !in added)
    }

    @Test
    fun `v66 adds only the nullable tree-branching metadata columns`() {
        val directory = sequenceOf("schemas", "app/schemas")
            .map { File(it, "app.androidrik.rikkahub.data.db.AppDatabase") }
            .first { it.isDirectory }
        fun entities(version: Int) = Json.parseToJsonElement(File(directory, "$version.json").readText())
            .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .associate { it.jsonObject.getValue("tableName").jsonPrimitive.content to it.jsonObject }
        val old = entities(65)
        val new = entities(66)
        assertEquals(old.keys, new.keys)
        // 只允许会话表与节点表变化（其余表逐字节一致）
        old.filterKeys { it != "ConversationEntity" && it != "message_node" }.forEach { (table, schema) ->
            assertEquals("Unrelated table $table changed", schema, new.getValue(table))
        }
        fun fields(version: Map<String, kotlinx.serialization.json.JsonObject>, table: String) =
            version.getValue(table).getValue("fields").jsonArray
                .associate { it.jsonObject.getValue("columnName").jsonPrimitive.content to it.jsonObject }
        // conversations：仅增 active_leaf_node_id（可空纯列，免 spec 免回填）
        val prevConv = fields(old, "ConversationEntity")
        val currConv = fields(new, "ConversationEntity")
        prevConv.forEach { (column, schema) -> assertEquals(schema, currConv.getValue(column)) }
        assertEquals(setOf("active_leaf_node_id"), currConv.keys - prevConv.keys)
        assertTrue("notNull" !in currConv.getValue("active_leaf_node_id"))
        assertTrue("defaultValue" !in currConv.getValue("active_leaf_node_id"))
        // message_node：仅增 parent_id（可空纯列；P0 惰性回填，读路径按 node_index 排序）
        val prevNode = fields(old, "message_node")
        val currNode = fields(new, "message_node")
        prevNode.forEach { (column, schema) -> assertEquals(schema, currNode.getValue(column)) }
        assertEquals(setOf("parent_id"), currNode.keys - prevNode.keys)
        assertTrue("notNull" !in currNode.getValue("parent_id"))
        assertTrue("defaultValue" !in currNode.getValue("parent_id"))
    }

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
