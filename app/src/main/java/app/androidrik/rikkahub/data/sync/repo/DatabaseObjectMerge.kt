package app.androidrik.rikkahub.data.sync.repo

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * P2 恢复：把逻辑对象合并进**当前库副本**，得到一份可直接走既有换库管线的 DB 文件。
 *
 * 语义（快照 = 某个时刻的完整视图）：
 * 1. 当前库所有非排除表**整表清空**后按对象行插入——快照覆盖的表被替换，新版本新增的表留空
 *    （等价于「旧版本全新库 + 迁移」，避免旧数据与新表混在一起产生悬空引用）；
 *    删除语义由「不在快照里」表达，无需墓碑；
 * 2. 被排除的表保留当前库内容（FTS 由启动后重建、Room/SQLite 簿记表、本机复核水位）；
 * 3. 插入按「对象列 ∩ 当前表列」取交集：未知列忽略、缺失列走列默认值；当前版本已删除的表
 *    跳过对应数据。跨 schema 的语义变化（列改名/含义变化）无法自动推断，通过 warning 上报；
 * 4. 事务提交后做 `foreign_key_check`，失败即整体回滚到合并前（副本丢弃，现库未动）。
 *
 * 合并发生在恢复暂存阶段，操作的是 VACUUM 副本而非现库；只有整条恢复链成功才由
 * [PendingRestoreStore] 换库，因此这里任何异常都不会破坏现有数据。
 */
internal object DatabaseObjectMerge {
    private val SAFE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** 删除顺序：子表在前，减少（即便 FK 关闭也仍存在的）级联代价。 */
    private val CONVERSATION_DELETE_ORDER = listOf("compression_checkpoint", "message_node", "conversationentity")

    /**
     * @param currentDbVersion 当前应用 DB schema（基底副本的 user_version），必须与副本一致。
     * @param snapshotDbVersion 快照来源 schema；允许 `<= currentDbVersion`（旧版本快照按当前结构恢复），
     *   更新版本的快照在引擎层已拒绝。
     * @param onWarning 跨 schema 降级信息（已删除的表/列、默认值填充等），供调用方记录/展示。
     */
    fun merge(
        baseDb: File,
        objects: List<Pair<String, File>>,
        currentDbVersion: Int,
        snapshotDbVersion: Int = currentDbVersion,
        onWarning: (String) -> Unit = {},
    ): File {
        require(objects.isNotEmpty()) { "No database objects to merge" }
        require(baseDb.isFile && baseDb.length() > 0) { "Missing base database copy" }
        require(snapshotDbVersion in 1..currentDbVersion) {
            "Snapshot database version $snapshotDbVersion is newer than this app ($currentDbVersion)"
        }
        val database = SQLiteDatabase.openDatabase(baseDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val version = database.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            require(version == currentDbVersion) {
                "Base database version $version does not match this app ($currentDbVersion)"
            }
            if (snapshotDbVersion != currentDbVersion) {
                onWarning("快照来自数据库 v$snapshotDbVersion，当前为 v$currentDbVersion；将按当前结构恢复")
            }
            val headers = objects.map { objectKey -> readDbObjectHeader(objectKey.second).also {
                require(it.objectKey == objectKey.first) { "Database object key mismatch" }
            } }
            val actualTables = readTables(database)
            // 快照里存在、当前库也存在的表：恢复目标。当前库已删除的表直接跳过。
            val targets = LinkedHashMap<String, String>()
            headers.forEach { header ->
                header.tables.keys.forEach { table ->
                    require(!isDbObjectTableExcluded(table)) { "Snapshot contains an excluded table: $table" }
                    val actual = actualTables[table.lowercase()]
                    if (actual == null) {
                        onWarning("快照表 $table 在当前版本已不存在，已跳过该表数据")
                    } else {
                        targets[table.lowercase()] = actual
                    }
                }
            }
            // 清空范围：
            // - 同 schema：只清快照覆盖的表（快照未覆盖的是本机/新增表，保留——见设计 §5.9 第 2 条）；
            // - 跨 schema：清所有非排除表。旧快照不含新版本新增的表，若保留当前内容会与新恢复的
            //   会话产生悬空引用（FK 校验会失败）；清空等价于「旧版本全新库 → 迁移」。
            val clearCrossSchema = snapshotDbVersion < currentDbVersion
            val tablesToClear = if (clearCrossSchema) {
                actualTables.values.filter { !isDbObjectTableExcluded(it) }
            } else {
                targets.values.toList()
            }
            val clearOrder = CONVERSATION_DELETE_ORDER.mapNotNull { name ->
                tablesToClear.firstOrNull { it.equals(name, ignoreCase = true) }
            } + tablesToClear
                .filter { table -> CONVERSATION_DELETE_ORDER.none { it.equals(table, ignoreCase = true) } }
                .sorted()

            database.execSQL("PRAGMA foreign_keys = OFF")
            database.beginTransaction()
            try {
                clearOrder.forEach { table -> database.execSQL("DELETE FROM \"$table\"") }
                // 先会话对象（父表在 header 里排最前），再整表对象；FK 已关，顺序只影响可读性。
                objects.sortedBy { if (dbObjectKind(it.first) == "conversation") 0 else 1 }.forEach { (objectKey, file) ->
                    val payload = readDbObjectPayload(file)
                    require(payload.objectKey == objectKey) { "Database object key mismatch" }
                    payload.tables.forEach { (table, data) ->
                        val actual = targets[table.lowercase()]
                        if (actual == null) {
                            onWarning("对象 $objectKey 引用的表 $table 在当前版本已不存在，已跳过")
                        } else {
                            insertTable(database, actual, data, onWarning)
                        }
                    }
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            val violations = ArrayList<String>()
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                while (cursor.moveToNext()) {
                    violations += "${cursor.getString(0)} rowid=${cursor.getLong(1)} parent=${cursor.getString(2)}"
                }
            }
            require(violations.isEmpty()) {
                "Restored database violates foreign keys: ${violations.take(5).joinToString("; ")}"
            }
            // 换库管线只搬运主库文件（+wal/-shm），这里确保 WAL 已落盘并切回单文件模式。
            database.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
            return baseDb
        } finally {
            database.close()
        }
    }

    private fun insertTable(
        database: SQLiteDatabase,
        actualTable: String,
        data: DbObjectTableData,
        onWarning: (String) -> Unit,
    ) {
        val currentColumns = readColumns(database, actualTable)
        val insertColumns = data.columns.filter { column ->
            require(column.matches(SAFE_IDENTIFIER)) { "Invalid column name in snapshot: $column" }
            column in currentColumns
        }
        val droppedColumns = data.columns.filter { it !in currentColumns }
        if (droppedColumns.isNotEmpty()) {
            onWarning("表 $actualTable 的快照列 ${droppedColumns.joinToString()} 在当前版本已不存在，已忽略")
        }
        val addedColumns = currentColumns.filter { it !in data.columns }
        if (addedColumns.isNotEmpty() && data.rows.isNotEmpty()) {
            onWarning("表 $actualTable 的新增列 ${addedColumns.joinToString()} 使用默认值")
        }
        if (insertColumns.isEmpty()) {
            if (data.rows.isNotEmpty()) onWarning("表 $actualTable 没有可用的列，已跳过 ${data.rows.size} 行")
            return
        }
        val columnIndexes = insertColumns.map { data.columns.indexOf(it) }
        val sql = "INSERT INTO \"$actualTable\" (" + insertColumns.joinToString(", ") { "\"$it\"" } +
            ") VALUES (" + insertColumns.joinToString(", ") { "?" } + ")"
        val statement = database.compileStatement(sql)
        try {
            data.rows.forEach { row ->
                statement.clearBindings()
                insertColumns.forEachIndexed { index, _ -> bind(statement, index + 1, row[columnIndexes[index]]) }
                statement.executeInsert()
            }
        } finally {
            statement.close()
        }
    }

    private fun bind(statement: SQLiteStatement, index: Int, value: JsonElement) {
        when {
            value is JsonNull -> statement.bindNull(index)
            value is JsonPrimitive -> if (value.isString) {
                statement.bindString(index, value.content)
            } else {
                val long = value.longOrNull
                when {
                    long != null -> statement.bindLong(index, long)
                    value.content == "true" -> statement.bindLong(index, 1)
                    value.content == "false" -> statement.bindLong(index, 0)
                    else -> statement.bindDouble(index, value.content.toDouble())
                }
            }
            value is JsonObject -> {
                val blob = dbBlobBytes(value)
                if (blob != null) {
                    statement.bindBlob(index, blob)
                } else {
                    val real = dbRealValue(value)
                    require(real != null) { "Unsupported database value in snapshot" }
                    if (real.isNaN()) statement.bindNull(index) else statement.bindDouble(index, real)
                }
            }
            else -> throw IllegalArgumentException("Unsupported database value in snapshot")
        }
    }

    private fun readTables(database: SQLiteDatabase): Map<String, String> = buildMap {
        database.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                put(name.lowercase(), name)
            }
        }
    }

    private fun readColumns(database: SQLiteDatabase, table: String): List<String> {
        val columns = ArrayList<String>()
        database.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns += cursor.getString(nameIndex)
        }
        require(columns.isNotEmpty()) { "Table has no columns: $table" }
        return columns
    }
}
