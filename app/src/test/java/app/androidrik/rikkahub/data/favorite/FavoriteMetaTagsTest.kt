package app.androidrik.rikkahub.data.favorite

import app.androidrik.rikkahub.data.db.entity.FavoriteEntity
import app.androidrik.rikkahub.data.model.FavoriteMeta
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 收藏标签的**编解码与兼容性**（`docs/design/design-favorite-management.md` §五）。
 *
 * 两个必须钉住的性质：
 * 1. **旧行无 `tags`/`tagIds` 字段要能解析**（零迁移的前提）；
 * 2. **重建 meta 时保留既有标签**（否则"取消收藏再收藏"会静默丢标签）。
 */
class FavoriteMetaTagsTest {

    private fun entity(metaJson: String?) = FavoriteEntity(
        id = "node:c:n",
        type = "node",
        refKey = "node:c:n",
        refJson = """{"conversationId":"${Uuid.random()}","nodeId":"${Uuid.random()}"}""",
        snapshotJson = "",
        metaJson = metaJson,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `legacy meta without tagIds decodes to an empty list`() {
        // 阶段前的存量数据（真实设备上就是这个形状）
        val legacy = """{"title":"爱好工作利弊","subtitle":"abc","previewText":"..."}"""
        val meta = NodeFavoriteAdapter.decodeMeta(entity(legacy))
        assertTrue("旧行必须能解析（零迁移前提）", meta != null)
        assertEquals(emptyList<Uuid>(), meta!!.tagIds)
    }

    @Test
    fun `meta with null json decodes to null`() {
        assertNull(NodeFavoriteAdapter.decodeMeta(entity(null)))
    }

    @Test
    fun `corrupt meta json degrades to null instead of throwing`() {
        // decodeMeta 用 runCatching：坏 JSON 不应让整个列表崩掉
        assertNull(NodeFavoriteAdapter.decodeMeta(entity("not json at all")))
    }

    @Test
    fun `tagIds survive a round trip`() {
        val ids = listOf(Uuid.random(), Uuid.random())
        val json = JsonInstant.encodeToString(
            FavoriteMeta(title = "t", subtitle = "s", previewText = "p", tagIds = ids)
        )
        val decoded = NodeFavoriteAdapter.decodeMeta(entity(json))
        assertEquals(ids, decoded!!.tagIds)
    }

    @Test
    fun `unknown fields in meta are ignored`() {
        // 前向兼容：将来 meta 加字段时，旧版读新数据不应崩
        val future = """{"title":"t","tagIds":[],"someFutureField":123}"""
        val meta = NodeFavoriteAdapter.decodeMeta(entity(future))
        assertTrue(meta != null)
    }

    @Test
    fun `decoding meta of a different type returns null`() {
        // MESSAGE 类型（遗留）不应被当作 node 解析
        val messageEntity = entity("""{"title":"t"}""").copy(type = "message")
        assertNull(NodeFavoriteAdapter.decodeMeta(messageEntity))
    }

    @Test
    fun `building a favorite keeps existing tags`() {
        // ⚠️ 关键回归：buildFavoriteEntity 每次从 target 重建 meta。
        // 若不显式带上 existing.tagIds，「取消收藏 → 重新收藏」会丢掉标签。
        val tagId = Uuid.random()
        val existing = entity(
            JsonInstant.encodeToString(FavoriteMeta(title = "old", tagIds = listOf(tagId)))
        )
        val conversationId = Uuid.random()
        val nodeId = Uuid.random()
        val rebuilt = NodeFavoriteAdapter.buildFavoriteEntity(
            target = app.androidrik.rikkahub.data.model.NodeFavoriteTarget(
                conversationId = conversationId,
                conversationTitle = "新标题",
                nodeId = nodeId,
                node = app.androidrik.rikkahub.data.model.MessageNode(
                    messages = listOf(
                        app.androidrik.ai.ui.UIMessage(
                            role = app.androidrik.ai.core.MessageRole.USER,
                            parts = listOf(app.androidrik.ai.ui.UIMessagePart.Text("hi")),
                        )
                    )
                ),
            ),
            existing = existing,
            now = 123L,
        )
        val meta = NodeFavoriteAdapter.decodeMeta(rebuilt)
        assertEquals("既有标签必须被保留", listOf(tagId), meta!!.tagIds)
        assertEquals("其余 meta 字段仍按 target 重建", "新标题", meta.title)
        assertEquals("createdAt 沿用既有值（取消再收藏不刷新时间）", 0L, rebuilt.createdAt)
    }

    @Test
    fun `building a favorite without existing has no tags`() {
        val conversationId = Uuid.random()
        val rebuilt = NodeFavoriteAdapter.buildFavoriteEntity(
            target = app.androidrik.rikkahub.data.model.NodeFavoriteTarget(
                conversationId = conversationId,
                conversationTitle = "t",
                nodeId = Uuid.random(),
                node = app.androidrik.rikkahub.data.model.MessageNode(
                    messages = listOf(
                        app.androidrik.ai.ui.UIMessage(
                            role = app.androidrik.ai.core.MessageRole.USER,
                            parts = listOf(app.androidrik.ai.ui.UIMessagePart.Text("hi")),
                        )
                    )
                ),
            ),
            existing = null,
            now = 1L,
        )
        assertEquals(emptyList<Uuid>(), NodeFavoriteAdapter.decodeMeta(rebuilt)!!.tagIds)
    }
}
