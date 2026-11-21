package app.androidrik.rikkahub.ui.pages.chat

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.rikkahub.data.model.Conversation
import java.time.Instant
import java.time.LocalDate
import kotlin.uuid.Uuid

/** 日期分组在分页边界与置顶边界保持一致。 */
class ConversationLocateIndexTest {

    private val assistantId = Uuid.random()

    /** 2000-01-01 那天的 Instant，保证所有夹具同日/跨日可控。 */
    private fun dayInstant(day: Int) = Instant.parse("2000-01-0${day}T00:00:00Z")

    private fun conv(pinned: Boolean = false, day: Int = 1, id: Uuid = Uuid.random()) = Conversation(
        assistantId = assistantId,
        messageNodes = emptyList(),
        isPinned = pinned,
        updateAt = dayInstant(day),
        id = id,
    )

    private val noDateLabel: (LocalDate) -> String = { it.toString() }

    // ---------- listSeparatorBetween：规则回放 ----------

    @Test
    fun `首行置顶_两模式都插置顶头`() {
        val after = conv(pinned = true)
        for (wantDate in listOf(true, false)) {
            assertTrue(
                listSeparatorBetween(null, itemOf(after), wantDate, noDateLabel)
                    is ConversationListItem.PinnedHeader,
            )
        }
    }

    @Test
    fun `首行未置顶_自动模式插日期头_自定义模式不插`() {
        val after = conv(pinned = false)
        assertTrue(
            listSeparatorBetween(null, itemOf(after), wantDateHeader = true, noDateLabel)
                is ConversationListItem.DateHeader,
        )
        assertNull(
            listSeparatorBetween(null, itemOf(after), wantDateHeader = false, noDateLabel),
        )
    }

    @Test
    fun `档位边界_自动模式插日期头_自定义模式不插`() {
        val before = conv(pinned = true)
        val after = conv(pinned = false, day = 2)
        assertTrue(
            listSeparatorBetween(itemOf(before), itemOf(after), wantDateHeader = true, noDateLabel)
                is ConversationListItem.DateHeader,
        )
        assertNull(
            listSeparatorBetween(itemOf(before), itemOf(after), wantDateHeader = false, noDateLabel),
        )
    }

    @Test
    fun `未置顶档内_跨日插日期头_同日与置顶档内不插`() {
        val before = conv(pinned = false, day = 1)
        // 跨日 → 日期头
        assertTrue(
            listSeparatorBetween(itemOf(before), itemOf(conv(day = 2)), wantDateHeader = true, noDateLabel)
                is ConversationListItem.DateHeader,
        )
        // 同日 → 不插
        assertNull(
            listSeparatorBetween(itemOf(before), itemOf(conv(day = 1)), wantDateHeader = true, noDateLabel),
        )
        // 置顶档内跨日也不插日期头
        assertNull(
            listSeparatorBetween(
                itemOf(conv(pinned = true, day = 1)),
                itemOf(conv(pinned = true, day = 2)),
                wantDateHeader = true,
                noDateLabel,
            ),
        )
    }

    private fun itemOf(c: Conversation) = ConversationListItem.Item(c)
}
