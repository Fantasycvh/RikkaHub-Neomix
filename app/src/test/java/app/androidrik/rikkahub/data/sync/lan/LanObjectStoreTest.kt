package app.androidrik.rikkahub.data.sync.lan

import app.androidrik.rikkahub.data.sync.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanObjectStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `resumes an interrupted object without exposing partial bytes`() = runBlocking {
        val bytes = "fixture".toByteArray()
        val hash = sha256Hex(bytes)
        val store = LanObjectStore(folder.newFolder())
        assertEquals(3L, store.append(hash, 0, 7, 3, bytes.copyOfRange(0, 3).inputStream()))
        assertNull(store.completed(hash))
        assertTrue(runCatching { store.append(hash, 3, 7, 4, badInput()) }.isFailure)
        assertEquals(3L, store.offset(hash))
        assertEquals(3L, store.append(hash, 0, 7, 3, bytes.copyOfRange(0, 3).inputStream()))
        assertEquals(7L, store.append(hash, 3, 7, 4, bytes.copyOfRange(3, 7).inputStream()))
        assertArrayEquals(bytes, store.completed(hash)?.readBytes())
    }

    @Test
    fun `drops an invalid attachment so the next session starts from zero`() = runBlocking {
        val store = LanObjectStore(folder.newFolder())
        val hash = sha256Hex(byteArrayOf(1, 2, 3))
        assertTrue(runCatching { store.append(hash, 0, 3, 3, byteArrayOf(3, 2, 1).inputStream()) }.isFailure)
        assertEquals(0L, store.offset(hash))
        assertNull(store.completed(hash))
    }

    private fun badInput() = byteArrayOf(1).inputStream()
}
