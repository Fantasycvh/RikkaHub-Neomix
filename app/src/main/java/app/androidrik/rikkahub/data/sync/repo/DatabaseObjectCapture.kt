package app.androidrik.rikkahub.data.sync.repo

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.androidrik.rikkahub.data.sync.BackupManifest
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * P2 捕获：从**一致性 DB 快照**（`VACUUM main INTO` 产物）导出逻辑对象。
 *
 * - 每个会话一个对象：`conversationentity` 行 + 该会话全部 `message_node` 行 + `compression_checkpoint` 行；
 * - 其余非排除表每表一个对象（含空表：恢复端据此执行整表替换，快照是完整视图）；
 * - 排除 FTS（`message_fts*`，可重建且体积最大）、Room/SQLite 簿记、记忆整理进度（设备本地状态）。
 *
 * 这里只读不写 SQLite 文件（VACUUM 产物是独立副本）；读的是普通表，不触 FTS 虚表，
 * 因此不依赖 app 的 `simple` 分词扩展，平台 SQLite 即可。
 */
internal object DatabaseObjectCapture {
    private data class SourceTable(val actualName: String, val columns: List<String>, val orderBy: String)

    /** 会话对象的三张表及各自的过滤列（按插入顺序：父表在前）。 */
    private val CONVERSATION_FILTERS = listOf(
        "conversationentity" to "id",
        "message_node" to "conversation_id",
        "compression_checkpoint" to "conversation_id",
    )

    fun capture(snapshotDb: File, outputDir: File): List<SnapshotDbObjectInput> {
        require(snapshotDb.isFile && snapshotDb.length() > 0) { "Missing consistent database snapshot" }
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create database object staging directory" }
        val database = SQLiteDatabase.openDatabase(snapshotDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val inputs = ArrayList<SnapshotDbObjectInput>()
        var total = 0L
        try {
            val tables = readTables(database)
            val byLower = tables.associateBy { it.actualName.lowercase() }
            fun write(input: SnapshotDbObjectInput) {
                inputs += input
                require(inputs.size <= ENTRY_LIMIT) { "Too many database objects" }
                total = Math.addExact(total, input.file.length())
                require(total <= BackupManifest.MAX_TOTAL_BYTES) { "Database objects exceed the backup size limit" }
            }

            // 1) 会话对象：一条会话 = 三张表的相关行，哈希天然按会话增量。
            val conversationTable = byLower["conversationentity"]
                ?: throw IllegalStateException("Database snapshot has no conversation table")
            val conversationIds = database.rawQuery(
                "SELECT \"id\" FROM \"${conversationTable.actualName}\" ORDER BY \"id\"",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        require(validUuid(id)) { "Conversation id is not a UUID; update the backup protocol first" }
                        add(id)
                    }
                }
            }
            val present = CONVERSATION_FILTERS.mapNotNull { (table, column) ->
                byLower[table]?.let { source -> Triple(source, column, table) }
            }
            val schemas = present.map { (source, filterColumn, _) ->
                DbTableSchema(source.actualName, readColumns(database, source.actualName, filterColumn))
            }
            conversationIds.forEachIndexed { index, conversationId ->
                val file = File(outputDir, "conversation-$index.jsonl")
                DbObjectPayloadWriter(file, conversationObjectKey(conversationId)).use { writer ->
                    writer.start(schemas)
                    present.forEach { (source, filterColumn, _) ->
                        readRows(database, source.actualName, filterColumn, conversationId) { cursor ->
                            writer.row(source.actualName, rowValues(cursor))
                        }
                    }
                }
                write(SnapshotDbObjectInput(conversationObjectKey(conversationId), file))
            }

            // 2) 其余表：整表对象（含空表），按表名排序保证哈希稳定。
            tables.asSequence()
                .filter { !isConversationObjectTable(it.actualName) }
                .sortedBy { it.actualName }
                .forEach { source ->
                    val file = File(outputDir, "table-${source.actualName}.jsonl")
                    DbObjectPayloadWriter(file, tableObjectKey(source.actualName)).use { writer ->
                        writer.start(listOf(DbTableSchema(source.actualName, source.columns)))
                        readWholeTable(database, source) { cursor -> writer.row(source.actualName, rowValues(cursor)) }
                    }
                    write(SnapshotDbObjectInput(tableObjectKey(source.actualName), file))
                }
            require(inputs.isNotEmpty()) { "Consistent database snapshot contains no user tables" }
            return inputs
        } finally {
            database.close()
            listOf("", "-wal", "-shm").forEach { suffix -> File(snapshotDb.path + suffix).delete() }
        }
    }

    private fun readTables(database: SQLiteDatabase): List<SourceTable> {
        val names = database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return names.filterNot(::isDbObjectTableExcluded).map { name ->
            SourceTable(name, readColumns(database, name, null), readOrderBy(database, name))
        }
    }

    /** 列名；带 [filterColumn] 时用 `WHERE col = NULL AND 0` 空查询探测（不读行）。 */
    private fun readColumns(database: SQLiteDatabase, table: String, filterColumn: String?): List<String> {
        val sql = if (filterColumn == null) {
            "SELECT * FROM \"$table\" WHERE 0"
        } else {
            "SELECT * FROM \"$table\" WHERE \"$filterColumn\" = NULL AND 0"
        }
        return database.rawQuery(sql, null).use { it.columnNames.toList() }
            .also { require(it.isNotEmpty()) { "Table has no columns: $table" } }
    }

    /** 有主键按主键排序，避免 VACUUM 后隐式 rowid 变化导致对象哈希漂移、每次全量重传。 */
    private fun readOrderBy(database: SQLiteDatabase, table: String): String {
        val primaryKey = ArrayList<Pair<Int, String>>()
        database.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                val order = cursor.getInt(pkIndex)
                if (order > 0) primaryKey += order to cursor.getString(nameIndex)
            }
        }
        return if (primaryKey.isEmpty()) " ORDER BY rowid"
        else " ORDER BY " + primaryKey.sortedBy { it.first }.joinToString(", ") { "\"${it.second}\"" }
    }

    private fun readRows(database: SQLiteDatabase, table: String, column: String, value: String, consume: (Cursor) -> Unit) {
        database.rawQuery(
            "SELECT * FROM \"$table\" WHERE \"$column\" = ? ORDER BY \"id\"",
            arrayOf(value),
        ).use { cursor -> while (cursor.moveToNext()) consume(cursor) }
    }

    private fun readWholeTable(database: SQLiteDatabase, table: SourceTable, consume: (Cursor) -> Unit) {
        database.rawQuery("SELECT * FROM \"${table.actualName}\"${table.orderBy}", null).use { cursor ->
            while (cursor.moveToNext()) consume(cursor)
        }
    }

    private fun rowValues(cursor: Cursor): List<JsonElement> =
        (0 until cursor.columnCount).map { rowValue(cursor, it) }

    private fun rowValue(cursor: Cursor, index: Int): JsonElement = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> dbNull()
        Cursor.FIELD_TYPE_INTEGER -> dbLong(cursor.getLong(index))
        Cursor.FIELD_TYPE_FLOAT -> dbReal(cursor.getDouble(index))
        Cursor.FIELD_TYPE_STRING -> dbText(cursor.getString(index))
        Cursor.FIELD_TYPE_BLOB -> dbBlob(cursor.getBlob(index))
        else -> dbNull()
    }
}
