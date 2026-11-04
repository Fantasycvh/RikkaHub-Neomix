package app.androidrik.rikkahub.data.sync.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class DatabaseObjectProtocolTest {
    @get:Rule val temp = TemporaryFolder()

    private fun writePayload(
        key: String,
        schemas: List<DbTableSchema>,
        block: (DbObjectPayloadWriter) -> Unit,
    ): File {
        val file = temp.newFile("${UUID.randomUUID()}.jsonl")
        DbObjectPayloadWriter(file, key).use { writer ->
            writer.start(schemas)
            block(writer)
        }
        return file
    }

    @Test fun payloadRoundTripPreservesTypesAndValues() {
        val conversationId = UUID.randomUUID().toString()
        val nodeId = UUID.randomUUID().toString()
        val blob = byteArrayOf(0, 1, 2, -1)
        val file = writePayload(
            conversationObjectKey(conversationId),
            listOf(
                DbTableSchema("conversationentity", listOf("id", "title", "is_pinned", "sort_order")),
                DbTableSchema("message_node", listOf("id", "conversation_id", "messages")),
            ),
        ) { writer ->
            writer.row("conversationentity", listOf(
                dbText(conversationId), dbText("标题 \"quoted\"\nline"), dbLong(1), dbNull(),
            ))
            writer.row("message_node", listOf(dbText(nodeId), dbText(conversationId), dbBlob(blob)))
        }
        val header = readDbObjectHeader(file)
        assertEquals(conversationObjectKey(conversationId), header.objectKey)
        assertEquals(listOf("conversationentity", "message_node"), header.tables.keys.toList())
        assertEquals(listOf("id", "title", "is_pinned", "sort_order"), header.tables.getValue("conversationentity"))

        val payload = readDbObjectPayload(file)
        assertEquals(header.objectKey, payload.objectKey)
        val conversation = payload.tables.getValue("conversationentity")
        assertEquals(1, conversation.rows.size)
        assertEquals(JsonPrimitive(conversationId), conversation.rows[0][0])
        assertEquals(JsonPrimitive("标题 \"quoted\"\nline"), conversation.rows[0][1])
        assertEquals(JsonPrimitive(1L), conversation.rows[0][2])
        assertEquals(JsonNull, conversation.rows[0][3])
        val node = payload.tables.getValue("message_node").rows.single()
        assertEquals(nodeId, node[0].jsonPrimitive.content)
        assertEquals(blob.toList(), dbBlobBytes(node[2].jsonObject)!!.toList())
    }

    @Test fun realValuesIncludingSpecialValuesRoundTrip() {
        val file = writePayload(tableObjectKey("usage_record"), listOf(DbTableSchema("usage_record", listOf("id", "value")))) { writer ->
            writer.row("usage_record", listOf(dbLong(1), dbReal(1.5)))
            writer.row("usage_record", listOf(dbLong(2), dbReal(Double.POSITIVE_INFINITY)))
        }
        val payload = readDbObjectPayload(file)
        val rows = payload.tables.getValue("usage_record").rows
        assertEquals(1.5, rows[0][1].jsonPrimitive.content.toDouble(), 0.0)
        assertTrue(dbRealValue(rows[1][1].jsonObject)!!.isInfinite())
    }

    @Test fun invalidObjectKeysAreRejected() {
        listOf("conversation:", "conversation:not-a-uuid", "table:message_fts", "table:sqlite_sequence", "table:bad-name", "unknown:x")
            .forEach { key -> assertThrows(IllegalArgumentException::class.java) { validateDbObjectKey(key) } }
    }

    @Test fun excludedTablesAreNotBackedUp() {
        listOf(
            "message_fts", "message_fts_data", "message_fts_rowid", "room_master_table",
            "android_metadata", "sqlite_sequence", "memory_review_cursor",
        ).forEach { assertTrue("$it should be excluded", isDbObjectTableExcluded(it)) }
        // memory_consolidation 挂会话 CASCADE 外键，必须随快照整表替换，否则合并后 FK 校验失败。
        listOf("conversationentity", "message_node", "compression_checkpoint", "memory", "folderentity", "memory_consolidation")
            .forEach { assertFalse("$it should be included", isDbObjectTableExcluded(it)) }
    }

    @Test fun writerRejectsExcludedTableAndReaderRejectsBadRows() {
        assertThrows(IllegalArgumentException::class.java) {
            DbObjectPayloadWriter(temp.newFile(), tableObjectKey("folderentity")).use {
                it.start(listOf(DbTableSchema("message_fts", listOf("text"))))
            }
        }
        val badWidth = temp.newFile().apply {
            writeText(
                "{\"v\":1,\"objectKey\":\"table:folderentity\",\"tables\":{\"folderentity\":[\"id\",\"name\"]}}\n" +
                    "{\"t\":\"folderentity\",\"r\":[\"only-one\"]}\n"
            )
        }
        assertThrows(IllegalArgumentException::class.java) { readDbObjectPayload(badWidth) }
        val unknownTable = temp.newFile().apply {
            writeText(
                "{\"v\":1,\"objectKey\":\"table:folderentity\",\"tables\":{\"folderentity\":[\"id\"]}}\n" +
                    "{\"t\":\"other\",\"r\":[\"x\"]}\n"
            )
        }
        assertThrows(IllegalArgumentException::class.java) { readDbObjectPayload(unknownTable) }
    }

    @Test fun headerOnlyPayloadIsReadableAndMeansEmptyTable() {
        val file = writePayload(tableObjectKey("favoriteentity"), listOf(DbTableSchema("favoriteentity", listOf("id", "content")))) { }
        val payload = readDbObjectPayload(file)
        assertEquals(1, payload.tables.size)
        assertTrue(payload.tables.values.single().rows.isEmpty())
        val lines = file.readLines()
        assertEquals(1, lines.size)
        val header = Json.parseToJsonElement(lines.single()).jsonObject
        assertEquals(1, header["v"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, header["tables"]!!.jsonObject.size)
    }
}
