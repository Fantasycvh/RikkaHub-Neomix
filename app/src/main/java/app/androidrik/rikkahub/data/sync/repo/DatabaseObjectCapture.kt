package app.androidrik.rikkahub.data.sync.repo

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.androidrik.rikkahub.data.sync.BackupManifest
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * P2 捕获：从**一致性 DB 快照**（`VACUUM main INTO` 产物）导出逻辑对象。
 *
 * - 每个会话一个对象：`conversationentity` 行 + 该会话全部 `message_node` 行 + `compression_checkpoint` 行；
 * - 其余非排除表每表一个对象（含空表：恢复端据此执行整表替换，快照是完整视图）；
 * - 排除 FTS（`message_fts*`，可重建且体积最大）、Room/SQLite 簿记、记忆整理进度（设备本地状态）。
 *
 * 性能（2026-11-04）：写出用 [DbObjectPayloadFastWriter] 直接拼 JSONL，不再为每个单元格分配
 * `JsonElement`；会话对象按 id 列表分给固定线程池并行导出（每个 worker 一个只读连接）。
 * 对象顺序与哈希仍确定：会话按 id 排序、表按表名排序，结果按原顺序组装。
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

    private fun workerCount(count: Int): Int =
        minOf(count, Runtime.getRuntime().availableProcessors().coerceIn(1, 4), 4)

    fun capture(snapshotDb: File, outputDir: File): List<SnapshotDbObjectInput> {
        require(snapshotDb.isFile && snapshotDb.length() > 0) { "Missing consistent database snapshot" }
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create database object staging directory" }
        val inputs = ArrayList<SnapshotDbObjectInput>()
        try {
            val schemaDb = SQLiteDatabase.openDatabase(snapshotDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val tables: List<SourceTable>
            val byLower: Map<String, SourceTable>
            val conversationIds: List<String>
            val present: List<Triple<SourceTable, String, String>>
            val schemas: List<DbTableSchema>
            try {
                tables = readTables(schemaDb)
                byLower = tables.associateBy { it.actualName.lowercase() }
                val conversationTable = byLower["conversationentity"]
                    ?: throw IllegalStateException("Database snapshot has no conversation table")
                conversationIds = readConversationIds(schemaDb, conversationTable.actualName)
                present = CONVERSATION_FILTERS.mapNotNull { (table, column) ->
                    byLower[table]?.let { source -> Triple(source, column, table) }
                }
                schemas = present.map { (source, filterColumn, _) ->
                    DbTableSchema(source.actualName, readColumns(schemaDb, source.actualName, filterColumn))
                }
            } finally {
                schemaDb.close()
            }

            // 1) 会话对象：一条会话 = 三张表的相关行，哈希天然按会话增量；并行导出。
            val conversationInputs = captureConversations(snapshotDb, outputDir, conversationIds, present, schemas)
            conversationInputs.forEach { write(inputs, it) }

            // 2) 其余表：整表对象（含空表），按表名排序保证哈希稳定。
            val tableDb = SQLiteDatabase.openDatabase(snapshotDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                tables.asSequence()
                    .filter { !isConversationObjectTable(it.actualName) }
                    .sortedBy { it.actualName }
                    .forEach { source ->
                        val file = File(outputDir, "table-${source.actualName}.jsonl")
                        DbObjectPayloadFastWriter(file, tableObjectKey(source.actualName)).use { writer ->
                            writer.start(listOf(DbTableSchema(source.actualName, source.columns)))
                            readWholeTable(tableDb, source) { cursor ->
                                writer.row(source.actualName) { appender -> appendCursorRow(cursor, appender) }
                            }
                        }
                        write(inputs, SnapshotDbObjectInput(tableObjectKey(source.actualName), file))
                    }
            } finally {
                tableDb.close()
            }
            require(inputs.isNotEmpty()) { "Consistent database snapshot contains no user tables" }
            return inputs
        } finally {
            listOf("", "-wal", "-shm").forEach { suffix -> File(snapshotDb.path + suffix).delete() }
        }
    }

    private fun write(inputs: MutableList<SnapshotDbObjectInput>, input: SnapshotDbObjectInput) {
        inputs += input
        require(inputs.size <= ENTRY_LIMIT) { "Too many database objects" }
        require(inputs.sumOf { it.file.length() } <= BackupManifest.MAX_TOTAL_BYTES) {
            "Database objects exceed the backup size limit"
        }
    }

    private fun captureConversations(
        snapshotDb: File,
        outputDir: File,
        conversationIds: List<String>,
        present: List<Triple<SourceTable, String, String>>,
        schemas: List<DbTableSchema>,
    ): List<SnapshotDbObjectInput> {
        if (conversationIds.isEmpty()) return emptyList()
        val results = arrayOfNulls<SnapshotDbObjectInput>(conversationIds.size)
        val connections = ConcurrentLinkedQueue<SQLiteDatabase>()
        val perThread = ThreadLocal<SQLiteDatabase>()
        val workers = workerCount(conversationIds.size)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until conversationIds.size).map { index ->
                pool.submit(Callable {
                    val database = perThread.get() ?: SQLiteDatabase.openDatabase(
                        snapshotDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
                    ).also { perThread.set(it); connections += it }
                    val conversationId = conversationIds[index]
                    val file = File(outputDir, "conversation-$index.jsonl")
                    DbObjectPayloadFastWriter(file, conversationObjectKey(conversationId)).use { writer ->
                        writer.start(schemas)
                        present.forEach { (source, filterColumn, _) ->
                            readRows(database, source.actualName, filterColumn, conversationId) { cursor ->
                                writer.row(source.actualName) { appender -> appendCursorRow(cursor, appender) }
                            }
                        }
                    }
                    results[index] = SnapshotDbObjectInput(conversationObjectKey(conversationId), file)
                })
            }
            futures.forEach { future ->
                try {
                    future.get()
                } catch (failure: java.util.concurrent.ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } finally {
            pool.shutdownNow()
            connections.forEach { runCatching { it.close() } }
            perThread.remove()
        }
        return results.map { requireNotNull(it) { "Missing captured conversation object" } }
    }

    private fun readConversationIds(database: SQLiteDatabase, table: String): List<String> =
        database.rawQuery("SELECT \"id\" FROM \"$table\" ORDER BY \"id\"", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    require(validUuid(id)) { "Conversation id is not a UUID; update the backup protocol first" }
                    add(id)
                }
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

    private fun appendCursorRow(cursor: Cursor, appender: DbRowAppender) {
        var index = 0
        while (index < cursor.columnCount) {
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> appender.appendNull()
                Cursor.FIELD_TYPE_INTEGER -> appender.appendLong(cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> appender.appendDouble(cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> appender.appendText(cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> appender.appendBlob(cursor.getBlob(index))
                else -> appender.appendNull()
            }
            index++
        }
    }
}
