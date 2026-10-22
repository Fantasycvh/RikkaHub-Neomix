package app.androidrik.rikkahub.data.plugin.js

import java.io.File
import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginKeyValueStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun root(): File = temporaryFolder.newFolder("kv")

    @Test
    fun `plugins are isolated and values persist across instances`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        val value = Json.parseToJsonElement("""{"nested":[true,1,"text",null]}""")
        store.set("first", "shared", value)
        store.set("second", "shared", JsonPrimitive(42))
        val reopened = PluginKeyValueStore(root)
        assertEquals(value, reopened.get("first", "shared"))
        assertEquals(JsonPrimitive(42), reopened.get("second", "shared"))
        assertNull(reopened.get("third", "shared"))
        assertEquals(2, root.listFiles()!!.size)
    }

    @Test
    fun `remove clear and keys only affect the requested plugin`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        assertTrue(store.keys("first").isEmpty())
        store.remove("first", "missing")
        store.clear("first")
        assertTrue(root.listFiles()!!.isEmpty())
        store.set("first", "a", JsonPrimitive(1))
        store.set("first", "b", JsonPrimitive(2))
        store.set("second", "a", JsonPrimitive(3))
        assertEquals(setOf("a", "b"), store.keys("first").toSet())
        store.remove("first", "a")
        store.remove("first", "missing")
        assertNull(store.get("first", "a"))
        assertEquals(listOf("b"), store.keys("first"))
        store.clear("first")
        assertTrue(PluginKeyValueStore(root).keys("first").isEmpty())
        assertEquals(JsonPrimitive(3), store.get("second", "a"))
    }

    @Test
    fun `JSON null remains distinct from a missing key`() = runBlocking {
        val store = PluginKeyValueStore(root())
        store.set("p", "null", JsonNull)
        assertEquals(JsonNull, store.get("p", "null"))
        assertNull(store.get("p", "missing"))
        assertEquals(listOf("null"), store.keys("p"))
        store.remove("p", "null")
        assertNull(store.get("p", "null"))
    }

    @Test
    fun `keys must be nonempty and at most 128 UTF8 bytes for every keyed operation`() = runBlocking {
        val store = PluginKeyValueStore(root())
        val boundary = "é".repeat(64)
        store.set("p", boundary, JsonPrimitive(true))
        assertEquals(JsonPrimitive(true), store.get("p", boundary))
        for (key in listOf("", "a".repeat(129), "é".repeat(65))) {
            expectFailure<IllegalArgumentException> { store.get("p", key) }
            expectFailure<IllegalArgumentException> { store.set("p", key, JsonNull) }
            expectFailure<IllegalArgumentException> { store.remove("p", key) }
        }
        assertEquals(listOf(boundary), store.keys("p"))
    }

    @Test
    fun `value quota includes quotes escaping and UTF8 bytes and preserves old values`() = runBlocking {
        val store = PluginKeyValueStore(root())
        val boundary = JsonPrimitive("x".repeat(64 * 1024 - 2))
        store.set("p", "value", boundary)
        for (value in listOf(
            JsonPrimitive("x".repeat(64 * 1024 - 1)),
            JsonPrimitive("é".repeat(32 * 1024)),
            JsonPrimitive("\n".repeat(32 * 1024)),
        )) {
            expectFailure<IllegalArgumentException> { store.set("p", "value", value) }
            assertEquals(boundary, store.get("p", "value"))
        }
        store.set("p", "value", JsonPrimitive("é".repeat(32767)))
    }

    @Test
    fun `128 keys allow replacement but not a new key`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        repeat(128) { store.set("p", "k$it", JsonPrimitive(it)) }
        store.set("p", "k0", JsonNull)
        expectFailure<IllegalArgumentException> { store.set("p", "overflow", JsonNull) }
        assertEquals(128, PluginKeyValueStore(root).keys("p").size)
        assertEquals(JsonNull, store.get("p", "k0"))
        store.remove("p", "k1")
        store.set("p", "new", JsonNull)
        assertEquals(128, store.keys("p").size)
    }

    @Test
    fun `total quota includes full serialized object and allows exact boundary`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        val large = JsonPrimitive("x".repeat(64 * 1024 - 2))
        repeat(3) { store.set("p", "k$it", large) }
        // Four two-byte keys contribute 25 bytes of object syntax in addition to serialized values.
        val last = JsonPrimitive("x".repeat(64 * 1024 - 27))
        store.set("p", "k3", last)
        val file = root.listFiles()!!.single()
        assertEquals(256L * 1024, file.length())
        expectFailure<IllegalArgumentException> {
            store.set("p", "k3", JsonPrimitive("x".repeat(64 * 1024 - 26)))
        }
        expectFailure<IllegalArgumentException> { store.set("p", "extra", JsonNull) }
        assertEquals(last, store.get("p", "k3"))
        assertEquals(256L * 1024, file.length())
        store.set("other", "large", large)
        store.set("p", "k0", JsonNull)
        store.set("p", "extra", JsonNull)
    }

    @Test
    fun `corrupt files throw on every operation without changing the original`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        store.set("p", "key", JsonNull)
        val file = root.listFiles()!!.single()
        for (raw in listOf("{broken", "", "[]", "null", "{\"\":1}", "{" + " ".repeat(256 * 1024) + "}")) {
            file.writeText(raw)
            expectFailure<IOException> { store.get("p", "key") }
            expectFailure<IOException> { store.keys("p") }
            expectFailure<IOException> { store.set("p", "key", JsonPrimitive(1)) }
            expectFailure<IOException> { store.remove("p", "key") }
            expectFailure<IOException> { store.clear("p") }
            assertEquals(raw, file.readText())
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        expectFailure<IOException> { store.get("p", "key") }
    }

    @Test
    fun `plugin IDs cannot escape the root and hash to separate safe filenames`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        val ids = listOf("../../escaped", "..\\..\\escaped", "/absolute", "C:\\absolute", ".", "", "a/b", "a_b")
        ids.forEachIndexed { index, id -> store.set(id, "key", JsonPrimitive(index)) }
        ids.forEachIndexed { index, id -> assertEquals(JsonPrimitive(index), store.get(id, "key")) }
        val files = root.listFiles()!!
        assertEquals(ids.size, files.size)
        assertTrue(files.all { it.name.matches(Regex("[0-9a-f]{64}\\.json")) })
        assertTrue(files.all { it.canonicalFile.parentFile == root.canonicalFile })
        assertFalse(File(temporaryFolder.root, "escaped").exists())
    }

    @Test
    fun `failed writes preserve original bytes and do not leave temporary files`() = runBlocking {
        val root = root()
        val store = PluginKeyValueStore(root)
        store.set("p", "key", JsonPrimitive("original"))
        val original = root.listFiles()!!.single().readText()
        // Deterministic failure even on Windows or as root, without permission-dependent chmod.
        val failingRoot = object : File(root.path) {
            override fun isDirectory(): Boolean = false
            override fun mkdirs(): Boolean = false
        }
        val failingStore = PluginKeyValueStore(failingRoot)
        expectFailure<IOException> { failingStore.set("p", "key", JsonPrimitive("replacement")) }
        expectFailure<IOException> { failingStore.remove("p", "key") }
        expectFailure<IOException> { failingStore.clear("p") }
        assertEquals(original, root.listFiles()!!.single().readText())
        assertEquals(JsonPrimitive("original"), store.get("p", "key"))
    }

    @Test
    fun `concurrent updates across instances do not lose keys`() = runBlocking {
        val root = root()
        val stores = List(4) { PluginKeyValueStore(root) }
        coroutineScope {
            repeat(128) { index ->
                launch { stores[index % stores.size].set("p", "key$index", JsonPrimitive(index)) }
            }
        }
        assertEquals(128, stores.first().keys("p").size)
        repeat(128) { assertEquals(JsonPrimitive(it), stores.first().get("p", "key$it")) }
        assertTrue(root.listFiles()!!.all { it.extension == "json" })
    }

    private suspend inline fun <reified T : Throwable> expectFailure(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
