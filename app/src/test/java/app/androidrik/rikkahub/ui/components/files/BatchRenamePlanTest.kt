package app.androidrik.rikkahub.ui.components.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchRenamePlanTest {
    @Test fun `single filename is used verbatim`() {
        assertEquals(mapOf("id" to "new.txt"), buildRenamePlan(listOf(RenameItem("id", "old.jpg")), "new.txt"))
    }

    @Test fun `batch keeps extensions and respects listing order`() {
        val items = listOf(RenameItem("z", "photo.JPG"), RenameItem("a", "notes.txt"), RenameItem("d", "dir.name", true))
        assertEquals(
            linkedMapOf("z" to "batch_3.JPG", "a" to "batch_4.txt", "d" to "batch_5"),
            buildRenamePlan(items, "batch", 3),
        )
    }

    @Test fun `hidden files and extensionless files do not get spurious extensions`() {
        val items = listOf(RenameItem("1", ".env"), RenameItem("2", "README"))
        assertEquals(listOf("new_1", "new_2"), buildRenamePlan(items, "new").values.toList())
    }

    @Test fun `extension preservation can be disabled`() {
        val items = listOf(RenameItem("1", "a.jpg"), RenameItem("2", "b.png"))
        assertEquals(listOf("new_1", "new_2"), buildRenamePlan(items, "new", keepExtension = false).values.toList())
    }

    @Test fun `unsafe names are rejected`() {
        for (name in listOf("", " ", ".", "..", "../escape", "a/b", "a\\b", "a\n", "a\u0000", " leading", "trailing ", "中".repeat(86))) {
            assertThrows("must reject $name", IllegalArgumentException::class.java) { validateRenameName(name) }
        }
    }

    @Test fun `sequence overflow and negative index are rejected`() {
        val items = listOf(RenameItem("1", "a"), RenameItem("2", "b"))
        assertThrows(IllegalArgumentException::class.java) { buildRenamePlan(items, "x", -1) }
        assertThrows(IllegalArgumentException::class.java) { buildRenamePlan(items, "x", Int.MAX_VALUE) }
    }

    @Test fun `empty and duplicate selections are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { buildRenamePlan(emptyList(), "x") }
        assertThrows(IllegalArgumentException::class.java) { buildRenamePlan(listOf(RenameItem("id", "a"), RenameItem("id", "b")), "x") }
    }
}
