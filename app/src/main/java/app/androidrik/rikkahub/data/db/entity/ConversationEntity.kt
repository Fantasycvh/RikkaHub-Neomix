package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("assistant_id", defaultValue = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
    val assistantId: String,
    @ColumnInfo("title")
    val title: String,
    @ColumnInfo("nodes")
    val nodes: String,
    @ColumnInfo("create_at")
    val createAt: Long,
    @ColumnInfo("update_at")
    val updateAt: Long,
    @ColumnInfo("suggestions", defaultValue = "[]")
    val chatSuggestions: String,
    @ColumnInfo("is_pinned", defaultValue = "0")
    val isPinned: Boolean,
    /**
     * 自定义顺序的位置值（见 `docs/design/design-conversation-manual-order.md`）。
     *
     * **null = 该会话从未进入自定义顺序**。档位（置顶/非置顶）分两个独立编号空间：
     * 档内「全 null」= 自动模式（按 `update_at`，即历史行为）；一旦需要（首次拖拽 /
     * 新会话需要档位最前 / 档位变动）就**整档物化**为 1000 步长的整数序列，
     * 档内**永不混用** null 与非 null（混用会让局部顺序无法解释）。
     */
    @ColumnInfo("sort_order")
    val sortOrder: Long? = null,
    /**
     * **末尾消息的 `createdAt`**（毫秒），列表行内展示「最近活跃」用。
     *
     * 为什么另存一列而不是用 `update_at`：`update_at` 会被改标题/置顶/重新生成等
     * **非消息动作**刷新（上游 #1995 抱怨的"只是编辑一下就被顶到最前"），
     * 而列表分页用的是不含消息的轻量投影，SQL 里现算 JSON 代价不可接受。
     * 由保存路径的**单一映射点**（`conversationToConversationEntity`）维护；
     * 旧数据为 null 时 UI 回退显示 `update_at`（不做事后回填）。
     */
    @ColumnInfo("last_message_at")
    val lastMessageAt: Long? = null,
    @ColumnInfo("custom_system_prompt", defaultValue = "")
    val customSystemPrompt: String = "",
    /**
     * 会话长期目标（定时任务 P2，purrcat A4.2）。存 `""` = 未设置（与 custom_system_prompt
     * 同款编码：映射层 ifEmpty { null }）；NOT NULL + DEFAULT '' ⇒ AutoMigration 无需 spec（51→52）。
     */
    @ColumnInfo("goal", defaultValue = "")
    val goal: String = "",
    @ColumnInfo("mode_injection_ids", defaultValue = "[]")
    val modeInjectionIds: String = "[]",
    @ColumnInfo("lorebook_ids", defaultValue = "[]")
    val lorebookIds: String = "[]",
    @ColumnInfo("workspace_cwd", defaultValue = "")
    val workspaceCwd: String = "",
    @ColumnInfo("folder_id", defaultValue = "")
    val folderId: String = "",
    /**
     * **本对话不使用记忆**（隐身对话；见 `docs/design/design-memory-discipline.md` §4.4）。
     *
     * 默认 false = 现行为（缺字段反序列化也是 false ⇒ 无需数据回填）。
     */
    @ColumnInfo("memory_disabled", defaultValue = "0")
    val memoryDisabled: Boolean = false,
    /**
     * **隐藏会话**（定时任务 P3，`docs/design/design-proactive-task-management.md` §二）：
     * 任务会话的工作台属性——不出现在任何列表/搜索/统计里，只能从任务详情页与完成通知
     * 直达；会话菜单「移出隐藏 / 移入隐藏」手动切换（不自动收养）。
     *
     * **表现层属性**：只进查询面过滤，不进任何执行路径判断（设计稿 §五 留缝纪律 2）。
     * NOT NULL + DEFAULT 0 ⇒ AutoMigration 无需 spec（54→55）。
     */
    @ColumnInfo("hidden", defaultValue = "0")
    val hidden: Boolean = false,
    /**
     * **冻结的锚点快照**（L1，会话开始时一次 + 用户手动「固化记忆」时重写）。
     *
     * 为什么需要冻结：L1 在 **system** 层，而 system 是第一条消息、前缀缓存从位置 0 起
     * 连续匹配 —— 改它一次，其后**全部重算**（真机实测命中率 98% → 0%）。冻结把这件事
     * 变成**显式且可预期**：会话内不再因锚点集合/内容的变动而被动失效。
     *
     * 三态：
     * - `null` = **未冻结**（本次升级前的存量会话）⇒ 用实时锚点，行为与改动前一致；
     * - `""`   = 已冻结、且当时**没有锚点** ⇒ 不产生 Anchors 块（保持"无锚点 ⇒ 无块"不变式）；
     * - 其他   = 冻结的渲染文本。
     *
     * 见 `docs/design/design-memory-injection-tiering.md`。
     */
    @ColumnInfo("frozen_anchors")
    val frozenAnchors: String? = null,
    /** Upstream 3.1 frozen configuration; null keeps native conversations following the assistant. */
    @ColumnInfo("upstream_config")
    val upstreamConfig: String? = null,
)
