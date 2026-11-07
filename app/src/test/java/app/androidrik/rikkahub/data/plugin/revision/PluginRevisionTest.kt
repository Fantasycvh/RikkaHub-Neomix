package app.androidrik.rikkahub.data.plugin.revision

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun revision(no: Long, pluginId: String = "sample.demo") = PluginRevision(
    pluginId = pluginId,
    revisionNo = no,
    kind = "js",
    source = "/* @rikka-plugin */",
    origin = RevisionOrigin.IMPORT,
    createdAt = no,
    precheck = PrecheckReport(passed = true, items = emptyList()),
)

class PluginRevisionTest {

    // ---- 双指针与修订号（纯函数，设计稿 §三.2 状态机） ----

    @Test
    fun `first revision becomes active with no last-good`() {
        val state = recordActivatedRevision(PluginRevisionState(pluginId = "p"), revision(1))
        assertEquals(1L, state.activeRevisionNo)
        assertNull(state.lastGoodRevisionNo)
    }

    @Test
    fun `second revision pushes active to last-good`() {
        var state = recordActivatedRevision(PluginRevisionState(pluginId = "p"), revision(1))
        state = recordActivatedRevision(state, revision(2))
        assertEquals(2L, state.activeRevisionNo)
        assertEquals(1L, state.lastGoodRevisionNo)
        assertEquals(2, state.revisions.size)
    }

    @Test
    fun `revision numbers allocate after history max`() {
        // 模拟回退后继续激活:历史 [1,2,3],active=2(lastGood=3 之后手动切回的场景)
        val state = PluginRevisionState(
            pluginId = "p",
            activeRevisionNo = 2,
            lastGoodRevisionNo = 3,
            revisions = listOf(revision(1), revision(2), revision(3)),
        )
        val next = recordActivatedRevision(state, revision(4))
        assertEquals(4L, next.activeRevisionNo)
        assertEquals(2L, next.lastGoodRevisionNo)
    }

    @Test
    fun `rolling drop keeps pinned revisions`() {
        var state = PluginRevisionState(pluginId = "p")
        repeat(12) { i -> state = recordActivatedRevision(state, revision((i + 1).toLong())) }
        assertEquals(MAX_REVISIONS_PER_PLUGIN, state.revisions.size)
        // active=12、lastGood=11 必须存活;最旧的 1、2 被丢弃
        assertEquals(12L, state.activeRevisionNo)
        assertEquals(11L, state.lastGoodRevisionNo)
        assertTrue(state.revisions.none { it.revisionNo == 1L })
        assertTrue(state.revisions.none { it.revisionNo == 2L })
        assertTrue(state.revisions.any { it.revisionNo == 3L })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-monotonic revision numbers are rejected`() {
        val state = PluginRevisionState(
            pluginId = "p",
            activeRevisionNo = 5,
            revisions = listOf(revision(5)),
        )
        recordActivatedRevision(state, revision(3))
    }

    // ---- 文件名净化 ----

    @Test
    fun `file name sanitizes non-word characters`() {
        assertEquals("sample.demo.json", revisionFileName("sample.demo"))
        assertEquals("a_b_c.json", revisionFileName("a/b c"))
    }
}

class PluginRevisionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun store() = PluginRevisionStore(tmp.root, json)

    private fun dir() = tmp.root

    @Test
    fun `roundtrip preserves state`() {
        val store = store()
        var state = PluginRevisionState(pluginId = "sample.demo")
        state = recordActivatedRevision(state, revision(1))
        state = recordActivatedRevision(state, revision(2, pluginId = "sample.demo"))
        store.save(state)

        val loaded = store.load("sample.demo")
        assertEquals(state, loaded)
    }

    @Test
    fun `missing file loads as empty state`() {
        val loaded = store().load("never.seen")
        assertEquals(PluginRevisionState(pluginId = "never.seen"), loaded)
    }

    @Test
    fun `corrupt file loads as empty state instead of throwing`() {
        val dir = java.io.File(dir(), PluginRevisionStore.FOLDER_NAME).apply { mkdirs() }
        java.io.File(dir, revisionFileName("broken.plugin")).writeText("{not json")
        val loaded = store().load("broken.plugin")
        assertNull(loaded.activeRevisionNo)
        assertTrue(loaded.revisions.isEmpty())
    }
}
