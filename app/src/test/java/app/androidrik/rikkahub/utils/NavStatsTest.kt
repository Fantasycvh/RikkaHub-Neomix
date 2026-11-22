package app.androidrik.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NavStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(): File = File(tmp.root, "nav_stats.json")

    @Test
    fun `record counts page enters and transitions`() {
        NavStats.init(newFile())
        NavStats.recordPageEnter("Chat")
        NavStats.recordPageEnter("Setting")
        NavStats.recordPageEnter("SettingProvider")
        NavStats.recordPageEnter("Setting") // 返回设置 = 新一次进入

        val stats = NavStats.snapshot()
        assertEquals(1L, stats.pageCounts["Chat"])
        assertEquals(2L, stats.pageCounts["Setting"])
        assertEquals(1L, stats.pageCounts["SettingProvider"])
        assertEquals(1L, stats.transitions["Chat>Setting"])
        assertEquals(1L, stats.transitions["Setting>SettingProvider"])
        assertEquals(1L, stats.transitions["SettingProvider>Setting"])
        assertEquals(3, stats.transitions.size)
    }

    @Test
    fun `same page consecutive enters are deduped`() {
        NavStats.init(newFile())
        NavStats.recordPageEnter("Chat")
        NavStats.recordPageEnter("Chat")
        NavStats.recordPageEnter("Chat")

        val stats = NavStats.snapshot()
        assertEquals(1L, stats.pageCounts["Chat"])
        assertTrue(stats.transitions.isEmpty())
    }

    @Test
    fun `null or blank page is ignored`() {
        NavStats.init(newFile())
        NavStats.recordPageEnter(null)
        NavStats.recordPageEnter("")

        assertTrue(NavStats.snapshot().pageCounts.isEmpty())
    }

    @Test
    fun `flush persists and init reloads`() {
        val file = newFile()
        NavStats.init(file)
        NavStats.recordPageEnter("Chat")
        NavStats.recordPageEnter("Setting")
        NavStats.flush()

        // 新实例语义：重新 init 等价于进程重启后的加载
        NavStats.init(file)
        NavStats.recordPageEnter("Setting") // 与上次会话最后一页同名：init 重置 lastPage 后仍计一次

        val stats = NavStats.snapshot()
        assertEquals(1L, stats.pageCounts["Chat"])
        assertEquals(2L, stats.pageCounts["Setting"])
        assertEquals(1L, stats.transitions["Chat>Setting"])
    }

    @Test
    fun `corrupt file is discarded on init`() {
        val file = newFile()
        file.writeText("not a json {{{")
        NavStats.init(file)
        NavStats.recordPageEnter("Chat")
        NavStats.flush()

        NavStats.init(file)
        val stats = NavStats.snapshot()
        assertEquals(1L, stats.pageCounts["Chat"])
        assertNull(stats.pageCounts["Setting"])
    }

    @Test
    fun `reset clears counts and transitions`() {
        NavStats.init(newFile())
        NavStats.recordPageEnter("Chat")
        NavStats.recordPageEnter("Setting")
        NavStats.reset()
        NavStats.recordPageEnter("Setting")

        val stats = NavStats.snapshot()
        assertEquals(1L, stats.pageCounts["Setting"])
        assertNull(stats.pageCounts["Chat"])
        assertTrue(stats.transitions.isEmpty())
    }
}
