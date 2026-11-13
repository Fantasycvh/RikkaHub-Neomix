package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message_node",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id", "node_index")]
)
data class MessageNodeEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    @ColumnInfo("node_index")
    val nodeIndex: Int,
    @ColumnInfo("messages")
    val messages: String,  // JSON serialized List<UIMessage>
    @ColumnInfo("select_index")
    val selectIndex: Int,
    /**
     * 树形分支 P0：父节点 id（null = 根 / 尚未回填的旧行）。
     * P0 为派生列（保存边界按列表序填充），读路径按 node_index（创建序）排序、不依赖它；
     * P1 翻转为权威（分支 = 兄弟节点）。见 design-conversation-tree-branching.md §五。
     */
    @ColumnInfo("parent_id")
    val parentId: String? = null,
)
