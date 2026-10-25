package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.migrations.Migration_11_12
import app.androidrik.rikkahub.data.db.migrations.Migration_13_14
import app.androidrik.rikkahub.data.db.migrations.Migration_14_15
import app.androidrik.rikkahub.data.db.migrations.Migration_15_16
import app.androidrik.rikkahub.data.db.migrations.Migration_27_28
import app.androidrik.rikkahub.data.db.migrations.Migration_28_29
import app.androidrik.rikkahub.data.db.migrations.Migration_40_41
import app.androidrik.rikkahub.data.db.migrations.Migration_41_42
import app.androidrik.rikkahub.data.db.migrations.Migration_43_44
import app.androidrik.rikkahub.data.db.migrations.Migration_46_47
import app.androidrik.rikkahub.data.db.migrations.Migration_47_48
import app.androidrik.rikkahub.data.db.migrations.Migration_6_7
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.UpstreamConversationConfig
import app.androidrik.rikkahub.data.model.MemoryKind
import java.io.File
import java.util.UUID

/**
 * 原版 RikkaHub 备份（3.x 文件式记忆）→ 本应用当前版数据库的**读取转换成**导入器。
 *
 * 两套 schema 在 v30 后分叉：原版把记忆改成 `memory_file`（一篇 Markdown），
 * 本应用是 `MemoryEntity` 行式记忆 + 版本/历史。Room 迁移只能沿本应用自己的历史走，
 * 无法把原版库直接升上来，故这里做数据级转换：
 *
 * 1. 目标库用本应用的 [AppDatabase] 新建（自动到当前版本），不写 `fallbackToDestructiveMigration`；
 * 2. 同名的兼容表按「列名交集」逐行复制，缺列走目标表自身的 DEFAULT；
 * 3. `memory_file` 的 Markdown 按**顶层条目**（`- [stated] …`）拆成多条 [MemoryEntity]；
 * 4. 只复制目标库存在的表（同名表按列名交集逐列复制）；目标库没有的原版独有表记录跳过。
 *    `translation_history` 与本应用新表同构，旧备份里的翻译历史会一并带过来；
 * 5. 所有文本列里的旧包名绝对路径改写为当前 applicationId，否则附件指向已不存在的目录。
 *
 * 转换在**换库之前**完成并落在独立文件里：任何一步失败都只删掉半成品，当前库不受影响。
 */
internal class LegacyBackupConverter(private val context: Context) {
    companion object {
        private const val TAG = "LegacyBackupConverter"
        const val CONVERTED_DATABASE_NAME = "rikka_hub_legacy_import"

        fun classify(file: File): LegacyDatabaseFamily {
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            return try {
                val tables = sourceTableNames(db)
                when {
                    "MemoryEntity" in tables -> LegacyDatabaseFamily.CURRENT
                    "memory_file" in tables || "media_creation_session" in tables ->
                        LegacyDatabaseFamily.LEGACY_UPSTREAM
                    else -> LegacyDatabaseFamily.UNKNOWN
                }
            } finally {
                db.close()
            }
        }

        private fun sourceTableNames(db: SQLiteDatabase): Set<String> =
            db.rawQuery("select name from sqlite_master where type='table'", null).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
    }

    /**
     * @return 转换后的数据库文件（位于 databases 目录，名为 [CONVERTED_DATABASE_NAME]）
     * @throws IllegalStateException 源库缺失或目标库写入失败；此时已自动清理半成品
     */
    fun convert(sourceDatabase: File): File {
        check(sourceDatabase.isFile && sourceDatabase.length() > 0) { "Legacy backup database is missing" }
        context.deleteDatabase(CONVERTED_DATABASE_NAME)
        val source = SQLiteDatabase.openDatabase(
            sourceDatabase.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
        )
        val target = Room.databaseBuilder(context, AppDatabase::class.java, CONVERTED_DATABASE_NAME)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15,
                Migration_15_16, Migration_27_28, Migration_28_29, Migration_40_41, Migration_41_42,
                Migration_43_44, Migration_46_47, Migration_47_48,
            )
            .build()
        try {
            val targetDb = target.openHelper.writableDatabase
            // room_master_table 存的是目标库的 schema 身份哈希；复制源的会让 Room 拒绝打开。
            val targetTables = targetTableNames(targetDb) - RESERVED_TABLES
            val copied = mutableListOf<String>()
            val skipped = mutableListOf<String>()
            source.beginTransaction()
            try {
                var total = 0
                targetTables.forEach { table ->
                    if (!sourceHasTable(source, table)) {
                        skipped += table
                        return@forEach
                    }
                    total += copyTable(source, targetDb, table)
                    copied += table
                }
                val memories = importMemoryFiles(source, targetDb)
                Log.i(TAG, "convert: copied=$copied skipped=$skipped rows=$total memories=$memories")
            } finally {
                source.endTransaction()
            }
            return context.getDatabasePath(CONVERTED_DATABASE_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "convert failed; removing partial target", e)
            runCatching { target.close() }
            context.deleteDatabase(CONVERTED_DATABASE_NAME)
            throw IllegalStateException("Failed to import legacy backup: ${e.message}", e)
        } finally {
            runCatching { source.close() }
            runCatching { target.close() }
        }
    }

    /**
     * 可用普通 SQL 遍历的表：排除 Room/SQLite 簿记表，以及 FTS5 虚拟表。
     * 后者需要 fts5 模块才能执行 `PRAGMA table_info`，框架 SQLite 上会直接抛错。
     */
    private fun writableTableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery("select name, sql from sqlite_master where type='table'", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val sql = cursor.getString(1) ?: ""
                    if (name !in RESERVED_TABLES && !sql.contains("VIRTUAL", ignoreCase = true)) add(name)
                }
            }
        }

    /** Room/SQLite 自身的簿记表，绝不能在数据库之间复制。 */
    private val RESERVED_TABLES = setOf("room_master_table", "android_metadata", "sqlite_sequence")

    private fun targetTableNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("select name from sqlite_master where type='table'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun sourceHasTable(source: SQLiteDatabase, table: String): Boolean =
        source.rawQuery(
            "select 1 from sqlite_master where type='table' and name=?", arrayOf(table),
        ).use { it.moveToFirst() }

    /** 按列名交集复制；目标表多出来的列由建表 DEFAULT 兜底。返回复制行数。 */
    private fun copyTable(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        table: String,
    ): Int {
        val sourceCols = sourceColumns(source, table)
        val targetColumns = columnsOf(target, table)
        // Upstream's config is a frozen snapshot, not a native assistant/global setting.
        val copyConfig = table == "ConversationEntity" && "config" in sourceCols &&
            "upstream_config" in targetColumns && "upstream_config" !in sourceCols
        val shared = sourceCols.filter { it in targetColumns } + if (copyConfig) listOf("config") else emptyList()
        val destinationColumns = shared.map { if (copyConfig && it == "config") "upstream_config" else it }
        if (shared.isEmpty()) {
            Log.w(TAG, "copyTable($table): no shared columns, skipped")
            return 0
        }
        // 目标表若有「NOT NULL 且无默认值」的列在源库里不存在，逐行插入必然失败并中止整库导入。
        // 这种情况按表跳过并记录，避免一个可选功能表毁掉整个恢复。
        val missingRequired = requiredTargetColumns(target, table) - destinationColumns.toSet()
        if (missingRequired.isNotEmpty()) {
            Log.w(TAG, "copyTable($table): source lacks required columns $missingRequired, skipped")
            return 0
        }
        val columnList = shared.joinToString(",") { "\"$it\"" }
        val placeholders = shared.joinToString(",") { "?" }
        val destinationList = destinationColumns.joinToString(",") { "\"$it\"" }
        val insert = "INSERT OR REPLACE INTO \"$table\" ($destinationList) VALUES ($placeholders)"
        val rewritten = shared.map(::isRewritableText)
        var rows = 0
        source.rawQuery("select $columnList from \"$table\"", null).use { cursor ->
            while (cursor.moveToNext()) {
                val args = Array<Any?>(shared.size) { index ->
                    val value: Any? = when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index)
                        else -> cursor.getString(index)
                    }
                    if (copyConfig && shared[index] == "config") {
                        // Fail the isolated conversion on malformed known fields, rather than silently
                        // importing a conversation that will run with different settings.
                        UpstreamConversationConfig.normalizeLegacyJson(value as String?)
                    } else if (rewritten[index] && value is String) {
                        LegacyBackupText.rewriteLegacyPaths(value, BuildConfig.APPLICATION_ID)
                    } else value
                }
                target.execSQL(insert, args)
                rows++
            }
        }
        return rows
    }

    private fun sourceColumns(source: SQLiteDatabase, table: String): List<String> =
        source.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun columnsOf(target: SupportSQLiteDatabase, table: String): Set<String> =
        target.query("PRAGMA table_info(\"$table\")").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    /** 目标表里「NOT NULL、无默认值、且不是自增主键」的列：复制时必须由源库提供。 */
    private fun requiredTargetColumns(target: SupportSQLiteDatabase, table: String): Set<String> =
        target.query("PRAGMA table_info(\"$table\")").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val notNull = cursor.getInt(2) != 0
                    val defaultValue = if (cursor.isNull(3)) null else cursor.getString(3)
                    val primaryKey = cursor.getInt(5) != 0
                    if (notNull && defaultValue == null && !primaryKey) add(name)
                }
            }
        }

    /**
     * 就地改写**本应用历史库**里遗留的旧包名绝对路径，返回是否改过。
     *
     * 2.5.6 的库能沿 Room 迁移链升上来，但 `messages`/`nodes` 里的附件 URI 仍指向
     * `me.rerere.rikkahub` 的目录；文件已恢复到当前包目录，不改写就全部失效。
     * 只做精确的包名路径替换，不碰用户正文的其余内容。
     *
     * 改写后会把 WAL checkpoint 进主库并删掉伴随文件：调用方必须丢弃备份里的
     * `-wal`/`-shm` 副本，否则陈旧 WAL 会盖掉改写结果。
     */
    fun rewriteLegacyPathsInPlace(file: File): Boolean {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        var changed = false
        try {
            val pairs = LegacyBackupText.pathRewrites(BuildConfig.APPLICATION_ID)
            writableTableNames(db).forEach { table ->
                sourceColumns(db, table).forEach { column ->
                    pairs.forEach { (from, to) ->
                        val present = db.rawQuery(
                            "SELECT 1 FROM \"$table\" WHERE \"$column\" LIKE ? LIMIT 1",
                            arrayOf("%$from%"),
                        ).use { it.moveToFirst() }
                        if (present) {
                            db.execSQL(
                                "UPDATE \"$table\" SET \"$column\" = REPLACE(\"$column\", ?, ?)",
                                arrayOf(from, to),
                            )
                            changed = true
                        }
                    }
                }
            }
            if (changed) {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        } finally {
            db.close()
        }
        if (changed) {
            File("${file.path}-wal").delete()
            File("${file.path}-shm").delete()
        }
        return changed
    }

    /** 只改写「已知会存 URI/JSON」的文本列，避免误伤用户正文。 */
    private fun isRewritableText(column: String): Boolean = when (column) {
        "nodes", "messages", "custom_system_prompt", "config", "draft", "suggestions",
        "mode_injection_ids", "lorebook_ids", "workspace_cwd" -> true
        else -> false
    }

    /**
     * `memory_file`（每助手可有多篇 Markdown，如 profile/preferences/topics）→ 扁平 [MemoryEntity] 列表。
     *
     * 拆分口径（用户已确认）：按**顶层条目**拆，一行一条主题；`[stated]`/`[inferred]`
     * 是原版的来源标记，本应用没有对应字段，导入时剥掉但保留正文。
     * front matter 的 `description` 是整篇主题，不硬塞给每一条（会污染检索），留空由渲染兜底截断。
     * 原版固定根文件 profile/preferences 映射画像候选，其余默认话题；不自动固化，
     * 不从 areas/自定义 tasks 文件的正文猜事务状态或日期（原版无此结构化契约）。
     */
    private fun importMemoryFiles(source: SQLiteDatabase, target: SupportSQLiteDatabase): Int {
        if (!sourceHasTable(source, "memory_file")) return 0
        var imported = 0
        source.rawQuery(
            "select assistant_id, path, content, updated_at from memory_file", null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val assistantId = cursor.getString(0)
                val path = cursor.getString(1) ?: ""
                val content = cursor.getString(2) ?: ""
                val updatedAt = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                LegacyBackupText.entriesOf(content).forEach { entry ->
                    val entity = MemoryEntity(
                        assistantId = assistantId,
                        content = entry,
                        publicId = UUID.randomUUID().toString(),
                        importance = MemoryEntity.DEFAULT_IMPORTANCE,
                        updatedAt = updatedAt,
                        description = "",
                        isAnchor = false,
                        revision = 1L,
                        kind = LegacyBackupText.kindOfPath(path).storage,
                    )
                    insertMemory(target, entity)
                    imported++
                }
                Log.i(TAG, "importMemoryFiles: $path (${content.length} chars) -> $imported entries so far")
            }
        }
        return imported
    }

    private fun insertMemory(target: SupportSQLiteDatabase, memory: MemoryEntity) {
        target.execSQL(
            """
            INSERT INTO MemoryEntity
                (assistant_id, content, public_id, importance, updated_at, description,
                 read_count, last_read_at, is_anchor, revision,
                 chat_read_count, last_chat_read_at, consolidation_read_count, last_consolidation_read_at,
                 kind, task_status, due_at)
            VALUES (?, ?, ?, ?, ?, ?, 0, 0, ?, ?, 0, 0, 0, 0, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                memory.assistantId, memory.content, memory.publicId, memory.importance,
                memory.updatedAt, memory.description, if (memory.isAnchor) 1 else 0, memory.revision,
                memory.kind, memory.taskStatus, memory.dueAt,
            ),
        )
    }
}

/** 备份库属于哪套 schema 家族；决定「直接迁移」还是「数据级转换」。 */
internal enum class LegacyDatabaseFamily {
    /** 本应用自己的库（任意历史版本）：交给 Room 迁移链。 */
    CURRENT,

    /** 原版 3.x 文件式记忆库：必须走数据级转换。 */
    LEGACY_UPSTREAM,

    /** 既不是本应用历史库、也不是已知原版库：拒绝而不是猜。 */
    UNKNOWN,
}

/** 与 `SettingsJsonMigrator` 保持同一份旧包名清单（改包名时两处一起改）。 */
private val LEGACY_APPLICATION_IDS = listOf("me.rerere.rikkahub", "app.fantasycvh.rikkahub")

/** `[stated]` / `[inferred]` 等原版来源标记；不映射成本应用的字段，只从正文剥离。 */
private val SOURCE_MARKER_RE = Regex("^\\[[^\\]]+\\]\\s*")

/**
 * 备份转换里**纯文本**的两块规则，单独抽出来以便 JVM 单测（不依赖 Android/SQLite）：
 * 1. 原版 Markdown 记忆按顶层条目拆条；
 * 2. 旧包名绝对路径 → 当前 applicationId。
 */
internal object LegacyBackupText {
    /**
     * Upstream 815e9c20b reserves these two root files for identity/response preferences.
     * Its areas/people/topics contain mixed facts, not structured tasks. Unknown paths stay topic;
     * never infer a lifecycle, deadline or system anchor from a filename or prose.
     */
    fun kindOfPath(path: String): MemoryKind =
        when (path.trim().removePrefix("/")) {
            "profile.md", "preferences.md" -> MemoryKind.PROFILE
            else -> MemoryKind.TOPIC
        }

    /** 顶层条目：`- ` / `* ` 开头（可带 `[stated]` 标记）；无条目的正文整体算一条。 */
    fun entriesOf(content: String): List<String> {
        val entries = mutableListOf<String>()
        val builder = StringBuilder()
        var started = false
        fun flush() {
            val text = builder.toString().trim()
            if (text.isNotEmpty()) entries += text
            builder.setLength(0)
        }
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val isTopBullet = line.startsWith("- ") || line.startsWith("* ")
            if (isTopBullet) {
                flush()
                started = true
                builder.append(SOURCE_MARKER_RE.replace(line.removePrefix("- ").removePrefix("* "), ""))
            } else if (started && line.isNotBlank()) {
                builder.append('\n').append(line.trim())
            }
        }
        flush()
        if (entries.isEmpty()) {
            val body = content.removePrefix("\uFEFF")
            val withoutFrontMatter = if (body.startsWith("---")) {
                val end = body.indexOf("\n---", startIndex = 3)
                if (end >= 0) body.substring(end + 4) else body
            } else {
                body
            }
            val whole = withoutFrontMatter.trim()
            if (whole.isNotEmpty()) entries += whole
        }
        return entries
    }

    /** 旧包名 → 当前 applicationId 的精确路径替换规则（`.debug` 长前缀先替换）。 */
    fun pathRewrites(applicationId: String): List<Pair<String, String>> =
        LEGACY_APPLICATION_IDS.flatMap { legacy ->
            listOf("/data/user/0/$legacy", "/data/data/$legacy").flatMap { base ->
                listOf(
                    "$base.debug" to "file:///data/user/0/$applicationId",
                    base to "file:///data/user/0/$applicationId",
                )
            }
        }

    fun rewriteLegacyPaths(text: String, applicationId: String): String {
        var result = text
        pathRewrites(applicationId).forEach { (from, to) -> result = result.replace(from, to) }
        return result
    }
}
