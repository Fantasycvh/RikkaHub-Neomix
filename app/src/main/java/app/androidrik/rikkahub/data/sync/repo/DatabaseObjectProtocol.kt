package app.androidrik.rikkahub.data.sync.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Base64

/**
 * 增量备份 P2（DB 逻辑对象）的载荷格式。
 *
 * 每个对象是一个 **JSONL 文件**：首行是 header（格式版本、逻辑对象键、每张表的列名），
 * 之后每行是一条完整行 `{"t":"表名","r":[列值...]}`。行值保留 SQLite 的类型：
 * INTEGER/FLOAT 编码为 JSON number、TEXT 为 JSON string、BLOB 为 `{"$blob":base64}`、NULL 为 null。
 *
 * 之所以不用强类型 Kotlin 序列化：列集合来自 `sqlite_master`，跨 schema 版本天然是动态的；
 * 恢复端按「当前表列 ∩ 对象列」取交集插入（见 [DatabaseObjectMerge]），不需要编译期 schema。
 *
 * 本文件只依赖 kotlinx.serialization 与 java.io，可 JVM 单测；Android 侧只负责把 Cursor 行喂进来。
 */
internal const val DB_OBJECT_FORMAT_VERSION = 1

/** 一个会话对象包含的三张表；顺序即恢复时的插入/删除顺序（父表在子表前）。 */
internal val CONVERSATION_OBJECT_TABLES = listOf("conversationentity", "message_node", "compression_checkpoint")

/**
 * 不进入 DB 对象快照的表：
 * - FTS 全家族（`message_fts*`）：可重建，体积占比最大（本机 ~58 MB），恢复后统一重建；
 * - Room/SQLite 簿记表：由当前库保留，不应由快照覆盖；
 * - `memory_review_cursor`：设备本地的全量复核水位，无外键、可重建。
 *
 * `memory_consolidation` **不在排除清单**：它挂 CASCADE 外键指向会话；若排除，合并时删旧会话后
 * 基底里会留下孤儿进度行 → `foreign_key_check` 直接失败。它随快照整表替换（体积可忽略）。
 */
private val EXCLUDED_DB_TABLES = setOf(
    "room_master_table",
    "android_metadata",
    "sqlite_sequence",
    "memory_review_cursor",
)

private val VALID_DB_TABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun isDbObjectTableExcluded(name: String): Boolean {
    val lower = name.lowercase()
    return lower in EXCLUDED_DB_TABLES || lower.startsWith("message_fts") || lower.startsWith("sqlite_")
}

internal fun isConversationObjectTable(name: String): Boolean = name.lowercase() in CONVERSATION_OBJECT_TABLES

internal fun validDbTableName(name: String): Boolean = name.matches(VALID_DB_TABLE) && !isDbObjectTableExcluded(name)

/** 逻辑对象键：`conversation:<uuid>` 或 `table:<表名>`。 */
internal fun validateDbObjectKey(key: String) {
    val kind = key.substringBefore(':', missingDelimiterValue = "")
    val value = key.substringAfter(':', missingDelimiterValue = "")
    require(kind.isNotEmpty() && value.isNotEmpty()) { "Invalid database object key" }
    when (kind) {
        "conversation" -> require(validUuid(value)) { "Invalid conversation object key" }
        "table" -> require(validDbTableName(value)) { "Invalid table object key" }
        else -> throw IllegalArgumentException("Unknown database object kind: $kind")
    }
}

internal fun conversationObjectKey(conversationId: String): String {
    require(validUuid(conversationId)) { "Invalid conversation id" }
    return "conversation:$conversationId"
}

internal fun tableObjectKey(table: String): String {
    require(validDbTableName(table)) { "Invalid table name: $table" }
    return "table:$table"
}

/** 从对象键解析表过滤条件；恢复端用它决定往哪张表写。 */
internal fun dbObjectKind(key: String): String = key.substringBefore(':')

internal fun dbObjectValue(key: String): String = key.substringAfter(':')

/** 把 Android Cursor 的 `getType` 结果映射为 JSON 值（在 Android 侧调用，避免 JVM 依赖 android.*）。 */
internal fun dbNull(): JsonElement = JsonNull
internal fun dbLong(value: Long): JsonElement = JsonPrimitive(value)
internal fun dbText(value: String): JsonElement = JsonPrimitive(value)

internal fun dbReal(value: Double): JsonElement =
    if (value.isFinite()) JsonPrimitive(value)
    else buildJsonObject { put("\$real", JsonPrimitive(value.toString())) }

internal fun dbBlob(value: ByteArray): JsonElement =
    buildJsonObject { put("\$blob", JsonPrimitive(Base64.getEncoder().encodeToString(value))) }

/** 与 [dbBlob] 相反；恢复端绑定参数时调用。 */
internal fun dbBlobBytes(value: JsonObject): ByteArray? {
    val text = value["\$blob"]?.jsonPrimitive?.content ?: return null
    return Base64.getDecoder().decode(text)
}

internal fun dbRealValue(value: JsonObject): Double? {
    val text = value["\$real"]?.jsonPrimitive?.content ?: return null
    return when (text) {
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> text.toDoubleOrNull()
    }
}

internal data class DbTableSchema(val name: String, val columns: List<String>)

/** JSON 字符串转义（与 kotlinx 输出语义一致；供快速写入器直接拼 JSONL 行，省掉 JsonElement 树分配）。 */
internal fun appendJsonString(out: StringBuilder, value: String) {
    out.append('"')
    var index = 0
    while (index < value.length) {
        val c = value[index]
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (c < ' ') {
                out.append("\\u00")
                out.append(HEX_DIGITS[(c.code shr 4) and 0xF])
                out.append(HEX_DIGITS[c.code and 0xF])
            } else out.append(c)
        }
        index++
    }
    out.append('"')
}

private const val HEX_DIGITS = "0123456789abcdef"

/** [DbObjectPayloadFastWriter.row] 的单行值输出口：按列顺序 append，避免每行构造 List<JsonElement>。 */
internal class DbRowAppender(private val out: StringBuilder) {
    var count: Int = 0
        private set

    private fun separator() {
        if (count > 0) out.append(',')
        count++
    }

    fun appendNull() { separator(); out.append("null") }
    fun appendLong(value: Long) { separator(); out.append(value) }
    fun appendDouble(value: Double) {
        separator()
        if (value.isFinite()) out.append(value.toString())
        else {
            out.append("{\"\$real\":")
            appendJsonString(out, value.toString())
            out.append('}')
        }
    }

    fun appendText(value: String) { separator(); appendJsonString(out, value) }
    fun appendBlob(value: ByteArray) {
        separator()
        out.append("{\"\$blob\":")
        appendJsonString(out, Base64.getEncoder().encodeToString(value))
        out.append('}')
    }
}

/**
 * 与 [DbObjectPayloadWriter] 输出同一 JSONL 格式的快速写入器：直接用 StringBuilder 拼行，
 * 每行只做一次字符串转义，不做 JsonElement/JsonArray/JsonObject 分配。
 *
 * 语义完全一致（同一 reader 可读），字节不保证与旧写入器逐字节相同；快照哈希因此变化时
 * 只影响一次全量对象重传，恢复兼容性不变。
 */
internal class DbObjectPayloadFastWriter(file: File, private val objectKey: String) : Closeable {
    private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 1 shl 16)
    private val declared = HashMap<String, Int>()
    private val line = StringBuilder(512)
    private var started = false
    private var rowCount = 0L

    fun start(tables: List<DbTableSchema>) {
        require(!started) { "Payload header already written" }
        require(tables.isNotEmpty()) { "A database object must contain at least one table" }
        val header = buildJsonObject {
            put("v", JsonPrimitive(DB_OBJECT_FORMAT_VERSION))
            put("objectKey", JsonPrimitive(objectKey))
            put("tables", buildJsonObject {
                tables.forEach { table ->
                    require(validDbTableName(table.name)) { "Invalid table name" }
                    require(table.columns.isNotEmpty() && table.columns.distinct() == table.columns) { "Invalid table columns" }
                    declared[table.name] = table.columns.size
                    put(table.name, JsonArray(table.columns.map(::JsonPrimitive)))
                }
            })
        }
        out.write(header.toString())
        out.write("\n")
        started = true
    }

    fun row(table: String, write: (DbRowAppender) -> Unit) {
        check(started) { "Payload header missing" }
        val width = requireNotNull(declared[table]) { "Row references an undeclared table" }
        rowCount = Math.addExact(rowCount, 1)
        require(rowCount <= 50_000_000L) { "Too many rows in database object" }
        line.setLength(0)
        line.append("{\"t\":")
        appendJsonString(line, table)
        line.append(",\"r\":[")
        val appender = DbRowAppender(line)
        write(appender)
        require(appender.count == width) { "Row width does not match its declared table" }
        line.append("]}\n")
        out.write(line.toString())
    }

    override fun close() {
        out.close()
    }
}

/**
 * 流式写对象载荷：先写 header，再逐行写。行按表顺序写入即可，读取端不要求同一表连续，
 * 但恢复端实现按 header 的表集合处理。
 */
internal class DbObjectPayloadWriter(file: File, private val objectKey: String) : Closeable {
    private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8), 1 shl 16)
    private var started = false
    private var rowCount = 0L

    fun start(tables: List<DbTableSchema>) {
        require(!started) { "Payload header already written" }
        require(tables.isNotEmpty()) { "A database object must contain at least one table" }
        val header = buildJsonObject {
            put("v", JsonPrimitive(DB_OBJECT_FORMAT_VERSION))
            put("objectKey", JsonPrimitive(objectKey))
            put("tables", buildJsonObject {
                tables.forEach { table ->
                    require(validDbTableName(table.name)) { "Invalid table name" }
                    require(table.columns.isNotEmpty() && table.columns.distinct() == table.columns) { "Invalid table columns" }
                    put(table.name, JsonArray(table.columns.map(::JsonPrimitive)))
                }
            })
        }
        out.write(header.toString())
        out.write("\n")
        started = true
    }

    fun row(table: String, values: List<JsonElement>) {
        check(started) { "Payload header missing" }
        require(values.size <= 4096) { "Unexpected wide row" }
        rowCount = Math.addExact(rowCount, 1)
        require(rowCount <= 50_000_000L) { "Too many rows in database object" }
        out.write(buildJsonObject {
            put("t", JsonPrimitive(table))
            put("r", JsonArray(values))
        }.toString())
        out.write("\n")
    }

    override fun close() {
        out.close()
    }
}

internal data class DbObjectTableData(val columns: List<String>, val rows: List<List<JsonElement>>)

internal data class DbObjectPayload(val objectKey: String, val tables: LinkedHashMap<String, DbObjectTableData>)

/** 只读 header（两遍式恢复的第一遍：先确定要整表替换哪些表，再逐对象插入）。 */
internal data class DbObjectHeader(val objectKey: String, val tables: LinkedHashMap<String, List<String>>)

internal fun readDbObjectHeader(file: File): DbObjectHeader {
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        val headerLine = requireNotNull(reader.readLine()) { "Database object payload is empty" }
        val header = Json.parseToJsonElement(headerLine).jsonObject
        require(header["v"]?.jsonPrimitive?.int == DB_OBJECT_FORMAT_VERSION) { "Unsupported database object format" }
        val objectKey = requireNotNull(header["objectKey"]?.jsonPrimitive?.content) { "Missing object key" }
        validateDbObjectKey(objectKey)
        val schema = requireNotNull(header["tables"]?.jsonObject) { "Missing table schema" }
        require(schema.isNotEmpty() && schema.size <= 256) { "Invalid table schema" }
        val columns = LinkedHashMap<String, List<String>>()
        schema.forEach { (table, columnsJson) ->
            require(validDbTableName(table)) { "Invalid table name" }
            val names = columnsJson.jsonArray.map { it.jsonPrimitive.content }
            require(names.isNotEmpty() && names.size <= 4096 && names.distinct() == names) { "Invalid columns" }
            columns[table] = names
        }
        return DbObjectHeader(objectKey, columns)
    }
}

/** 读取完整对象载荷。行数由文件大小与 [ENTRY_LIMIT] 间接约束。 */
internal fun readDbObjectPayload(file: File): DbObjectPayload {
    val header = readDbObjectHeader(file)
    val columns = header.tables
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        check(reader.readLine() != null) { "Database object payload is empty" }
        val rows = LinkedHashMap<String, MutableList<List<JsonElement>>>()
        columns.keys.forEach { rows[it] = ArrayList() }
        var count = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            count = Math.addExact(count, 1)
            require(count <= 50_000_000) { "Too many rows in database object" }
            val row = Json.parseToJsonElement(line).jsonObject
            val table = requireNotNull(row["t"]?.jsonPrimitive?.content) { "Missing row table" }
            val columnCount = requireNotNull(columns[table]) { "Row references an undeclared table" }.size
            val values = requireNotNull(row["r"]?.jsonArray) { "Missing row values" }
            require(values.size == columnCount) { "Row width does not match its declared table" }
            rows.getValue(table).add(values)
        }
        val tables = LinkedHashMap<String, DbObjectTableData>()
        columns.forEach { (table, names) ->
            tables[table] = DbObjectTableData(names, rows.getValue(table))
        }
        return DbObjectPayload(header.objectKey, tables)
    }
}
