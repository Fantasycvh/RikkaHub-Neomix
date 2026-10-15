package app.androidrik.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.repository.LightConversationEntity

/**
 * 会话列表的**唯一**排序子句（自定义顺序设计稿 §三.4）。
 *
 * 为什么必须是常量而不是各查询各写一遍：这里曾有 **11 处**硬编码
 * `ORDER BY $CONVERSATION_ORDER_BY`——同类「约束散落多处」正是 2026-10-04
 * 备份打包前缀事故的成因。用 `const val` 拼接进 `@Query`（编译期常量折叠，Room 认）
 * 后，改顺序只需改这一处；配 `ConversationOrderingGuardTest` 钉住"没有旁路"。
 *
 * 语义：①置顶档在前 ②**已排序的在前**（`(sort_order IS NULL) ASC`：0 = 有值 → 先）
 * ③档内按位置值升序（值小者靠上，新会话分配 `min - 1000` 即"顶部最前"）
 * ④同值/未排序时退回 `update_at DESC`（并发同值的降级行为，见设计稿 §三.1）
 * ⑤`id ASC` 兜底保证**确定性**——分页 LIMIT/OFFSET 下顺序不稳会导致跨页重复/漏项。
 */
const val CONVERSATION_ORDER_BY =
    "is_pinned DESC, (sort_order IS NULL) ASC, sort_order ASC, update_at DESC, id ASC"

/**
 * **按时间**的排序子句：置顶档在前、档内按 `update_at DESC`，**完全忽略 `sort_order`**。
 *
 * 为什么需要第二条子句（用户 2026-10-04 拍板）：自定义顺序只定义「列表长什么样」，
 * 有两处**按时间才有意义**、必须不受它影响：
 * - **AI「最近对话」工具**（`getRecentConversationsOfAssistant`）——设计稿 §一.8：模型问"最近聊了什么"，
 *   答案必须是最近的，而不是用户摆过的位置；
 * - **搜索结果**（`searchConversations*`）——搜索是"按相关性/时间找东西"，跟随自定义顺序会让
 *   同一关键词的结果顺序随拖拽变化，反而更难找。
 *
 * 语义与改造前**逐字一致**（`is_pinned DESC, update_at DESC`），只多一个 `id ASC` 兜底
 * 保证分页 LIMIT/OFFSET 的确定性（与 [CONVERSATION_ORDER_BY] 同理）。
 */
const val CONVERSATION_ORDER_BY_TIME = "is_pinned DESC, update_at DESC, id ASC"

/** 「档内 id → 位置值」投影（自定义顺序的拖拽提交用，见 ConversationOrdering）。 */
data class TierOrderRow(
    val id: String,
    val sortOrder: Long,
)

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY $CONVERSATION_ORDER_BY")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY $CONVERSATION_ORDER_BY")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY $CONVERSATION_ORDER_BY")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt FROM conversationentity WHERE assistant_id = :assistantId ORDER BY $CONVERSATION_ORDER_BY")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = '' ORDER BY $CONVERSATION_ORDER_BY")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt FROM conversationentity WHERE folder_id = :folderId ORDER BY $CONVERSATION_ORDER_BY")
    fun getConversationsOfFolderPaging(folderId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY $CONVERSATION_ORDER_BY_TIME LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY $CONVERSATION_ORDER_BY_TIME")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt FROM conversationentity WHERE title LIKE '%' || :searchText || '%' ORDER BY $CONVERSATION_ORDER_BY_TIME")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY $CONVERSATION_ORDER_BY_TIME")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt FROM conversationentity WHERE assistant_id = :assistantId AND title LIKE '%' || :searchText || '%' ORDER BY $CONVERSATION_ORDER_BY_TIME")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT id FROM conversationentity")
    suspend fun getAllIds(): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    /**
     * 保存会话的**元信息列**（自定义顺序设计稿 §三.1：`sort_order` 的写入点必须唯一）。
     *
     * 为什么不用 `@Update`（全行覆盖）：那会把 `sort_order` 一并写回**内存态里的值**，
     * 而顺序是**只写库**的（拖拽提交走 [updateSortOrder]，不经过内存态）⇒ 一次普通保存
     * 就能把刚拖好的顺序悄悄改回去。这里改成显式列清单，**结构上碰不到 `sort_order`**；
     * 将来新增列若忘了加进来，`ConversationSaveColumnCoverageTest` 会失败（源码扫描护栏）。
     *
     * `last_message_at` 用 `COALESCE(:lastMessageAt, last_message_at)`：元信息保存
     * （置顶/改标题/换文件夹）传 null（无消息可算）时**保留原值**，不会把行内时间抹掉。
     *
     * `frozen_anchors` 与 `sort_order` 同款**不在本清单内**（见 [updateFrozenAnchors]）：
     * 它由会话开始冻结 / 记忆页固化两处专用语句写，而那两处都可能发生在调用方手里的
     * 内存快照之后 ⇒ 放进这里就有「拿旧快照把刚冻结的值写回 null」的静默回退。
     */
    @Query(
        """
        UPDATE conversationentity SET
            assistant_id = :assistantId,
            title = :title,
            create_at = :createAt,
            update_at = :updateAt,
            suggestions = :chatSuggestions,
            is_pinned = :isPinned,
            custom_system_prompt = :customSystemPrompt,
            mode_injection_ids = :modeInjectionIds,
            lorebook_ids = :lorebookIds,
            workspace_cwd = :workspaceCwd,
            folder_id = :folderId,
            memory_disabled = :memoryDisabled,
            last_message_at = COALESCE(:lastMessageAt, last_message_at)
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: String,
        assistantId: String,
        title: String,
        createAt: Long,
        updateAt: Long,
        chatSuggestions: String,
        isPinned: Boolean,
        customSystemPrompt: String,
        modeInjectionIds: String,
        lorebookIds: String,
        workspaceCwd: String,
        folderId: String,
        lastMessageAt: Long?,
        /**
         * 会话级「不使用记忆」（隐身对话）。**这里用直接赋值**（不像 last_message_at 那样 COALESCE）
         * ——它是用户开关，false 本身就是有效值，不能让「没传」把它吞掉。
         */
        memoryDisabled: Boolean,
    )

    /**
     * 会话级「不使用记忆」的唯一写入点（与 [updatePinStatus] 同款：调用方算好新值、
     * 并负责同步内存态，两边用同一个值以免分叉）。
     */
    @Query("UPDATE conversationentity SET memory_disabled = :disabled WHERE id = :id")
    suspend fun updateMemoryDisabled(id: String, disabled: Boolean)

    /**
     * **L1 锚点快照**（`docs/design/design-memory-injection-tiering.md` §5）的两个专用写入点。
     *
     * 为什么单列写、不走 [updateMetadata]（与 `sort_order` 同一条教训）：
     * 快照在**会话开始那一刻**（`ChatService` 首次生成前）与**用户点「固化为锚点」那一刻**
     * 写入，而这两处之后调用方手里的 `Conversation` 可能还是写之前的旧副本；一旦允许
     * 整对象/元信息保存带回该列，就会把刚冻好的值**静默写回 null** ⇒ 锚点每轮重算、
     * L1 的缓存收益归零。
     *
     * 三态语义（与 `Conversation.frozenAnchors` 一致）：
     * - `null` = 未冻结（下次生成时冻结）；
     * - `""`  = 冻结，且当时没有锚点 ⇒ **不产生 system 块**（不变式：无锚点 ⇒ 无块）；
     * - 其他 = 冻结的锚点文本，逐字节稳定。
     *
     * **不用 COALESCE**：`null` 是有效取值，不能被「没传」吞掉。
     */
    @Query("UPDATE conversationentity SET frozen_anchors = :frozen WHERE id = :id")
    suspend fun updateFrozenAnchors(id: String, frozen: String?)

    /**
     * 一次性重写**某助手全部会话**的锚点快照 —— 记忆页「固化为锚点」的手动刷新点。
     *
     * 为什么会是「全部会话」：锚点挂在助手的记忆库上，而记忆页没有「当前会话」这个上下文；
     * 用户固化一条锚点时的意图是「以后都算数」，因此所有会话一起换档。
     */
    @Query("UPDATE conversationentity SET frozen_anchors = :frozen WHERE assistant_id = :assistantId")
    suspend fun updateFrozenAnchorsOfAssistant(assistantId: String, frozen: String?)

    /** 自定义顺序：**唯一**的位置值写入点（见 [CONVERSATION_ORDER_BY] 与设计稿 §三.1）。 */
    @Query("UPDATE conversationentity SET sort_order = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Long?)

    /** 恢复默认顺序：清空某档的全部位置值（回到按 `update_at` 的自动模式）。 */
    @Query(
        "UPDATE conversationentity SET sort_order = NULL " +
            "WHERE assistant_id = :assistantId AND is_pinned = :isPinned"
    )
    suspend fun clearSortOrder(assistantId: String, isPinned: Boolean)

    /** 某档位置值（升序，跳过 null）——分配"档位最前"/归一化时读现状。 */
    @Query(
        "SELECT sort_order FROM conversationentity " +
            "WHERE assistant_id = :assistantId AND is_pinned = :isPinned AND sort_order IS NOT NULL " +
            "ORDER BY sort_order ASC" // guard-allow: order-helper（读位置值现状，不是列表排序）
    )
    suspend fun getSortOrders(assistantId: String, isPinned: Boolean): List<Long>

    /** 单个会话的位置值（档位变动决策用）。 */
    @Query("SELECT sort_order FROM conversationentity WHERE id = :id")
    suspend fun getSortOrder(id: String): Long?

    /** 会话所属助手（档位编号空间按「助手 + 置顶」分组，见 CONVERSATION_ORDER_BY 注释）。 */
    @Query("SELECT assistant_id FROM conversationentity WHERE id = :id")
    suspend fun getAssistantIdOf(id: String): String?

    /** 某档的会话 id（按当前显示顺序）——首次物化/归一化时按它生成位置值。 */
    @Query(
        "SELECT id FROM conversationentity WHERE assistant_id = :assistantId AND is_pinned = :isPinned " +
            "ORDER BY $CONVERSATION_ORDER_BY"
    )
    suspend fun getTierIds(assistantId: String, isPinned: Boolean): List<String>

    /**
     * 「编辑顺序」模式的非分页轻量列表（不含消息；拖拽需要完整顺序，见仓库同名方法注释）。
     *
     * `folderId` 传空串 = 未归类视图（与列表查询的语义一致）——编辑模式要**镜像当前视图**，
     * 否则用户在文件夹里点「编辑顺序」会看到另一批会话。
     */
    @Query(
        "SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, " +
            "update_at as updateAt, folder_id as folderId, last_message_at as lastMessageAt " +
            "FROM conversationentity WHERE assistant_id = :assistantId AND folder_id = :folderId " +
            "ORDER BY $CONVERSATION_ORDER_BY"
    )
    fun getLightConversationsForOrdering(
        assistantId: String,
        folderId: String,
    ): Flow<List<LightConversationEntity>>

    /** 某档的 id → 位置值（拖拽提交时算中值用；只含已物化的行）。 */
    @Query(
        "SELECT id, sort_order AS sortOrder FROM conversationentity " +
            "WHERE assistant_id = :assistantId AND is_pinned = :isPinned AND sort_order IS NOT NULL"
    )
    suspend fun getTierOrderRows(assistantId: String, isPinned: Boolean): List<TierOrderRow>

    /** 观察某档是否已进入自定义顺序（UI 据此决定是否隐藏日期分组头、显示「恢复默认顺序」）。 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM conversationentity " +
            "WHERE assistant_id = :assistantId AND is_pinned = :isPinned AND sort_order IS NOT NULL)"
    )
    fun observeTierMaterialized(assistantId: String, isPinned: Boolean): Flow<Boolean>

    /** 该档是否已进入自定义顺序（存在位置值 = 已物化）。 */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM conversationentity " +
            "WHERE assistant_id = :assistantId AND is_pinned = :isPinned AND sort_order IS NOT NULL)"
    )
    suspend fun isSortOrderMaterialized(assistantId: String, isPinned: Boolean): Boolean

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET nodes = '[]' WHERE id = :id")
    suspend fun resetConversationNodes(id: String)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()


    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY $CONVERSATION_ORDER_BY")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    /**
     * 换助手时**一并清空 folder_id**：文件夹是助手内分组，换了助手后原文件夹在新助手下不可见，
     * 留着归属会让会话在列表里「消失」。两条列一起更新，避免中间态。
     */
    @Query("UPDATE conversationentity SET assistant_id = :assistantId, folder_id = '' WHERE id = :id")
    suspend fun updateAssistantId(id: String, assistantId: String)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET folder_id = '' WHERE folder_id = :folderId")
    suspend fun clearFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun countAll(): Int

    /**
     * 按助手分组统计对话数（数据恢复页用，见 `DebugPage` 的 Recovery）。
     *
     * 用途：找出「聊天记录里引用了、但设置里已不存在」的助手 id —— 这些对话在按助手
     * 过滤的列表（`getConversationsOfAssistant`）里**完全不可见**（无对应助手可选中），
     * 需要为其补一个助手才能重新看到。
     */
    @Query("SELECT assistant_id AS assistantId, COUNT(*) AS count FROM conversationentity GROUP BY assistant_id")
    suspend fun countByAssistant(): List<AssistantConversationCount>

    @Query(
        "SELECT strftime('%Y-%m-%d', create_at/1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM conversationentity " +
            "WHERE create_at >= :startMillis " +
            "GROUP BY day"
    )
    suspend fun getConversationCountPerDay(startMillis: Long): List<ConversationDayCount>
}

data class ConversationDayCount(val day: String, val count: Int)

data class AssistantConversationCount(val assistantId: String, val count: Int)
