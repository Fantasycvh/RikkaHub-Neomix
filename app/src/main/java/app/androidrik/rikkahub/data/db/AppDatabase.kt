package app.androidrik.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import app.androidrik.ai.core.TokenUsage
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.dao.CompressionCheckpointDAO
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.FavoriteDAO
import app.androidrik.rikkahub.data.db.dao.FolderDAO
import app.androidrik.rikkahub.data.db.dao.GenMediaDAO
import app.androidrik.rikkahub.data.db.dao.KnowledgeBaseDAO
import app.androidrik.rikkahub.data.db.dao.MemoryConsolidationDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.dao.MemoryReviewCursorDAO
import app.androidrik.rikkahub.data.db.dao.ManagedFileDAO
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.dao.WorkspaceDAO
import app.androidrik.rikkahub.data.db.entity.CompressionCheckpointEntity
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.db.entity.FavoriteEntity
import app.androidrik.rikkahub.data.db.entity.FolderEntity
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeEmbeddingEntity
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.db.migrations.Migration_16_17
import app.androidrik.rikkahub.data.db.migrations.Migration_22_23
import app.androidrik.rikkahub.data.db.migrations.Migration_27_28
import app.androidrik.rikkahub.data.db.migrations.Migration_28_29
import app.androidrik.rikkahub.data.db.migrations.Migration_8_9
import app.androidrik.rikkahub.utils.JsonInstant

@Database(
    entities = [
        ConversationEntity::class,
        MemoryEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        CompressionCheckpointEntity::class,
        AgentRunEntity::class,
        KnowledgeBaseEntity::class,
        KnowledgeBaseDocumentEntity::class,
        KnowledgeEmbeddingEntity::class,
        MemoryConsolidationEntity::class,
        MemoryProposalEntity::class,
        MemoryReviewCursorEntity::class,
        MemoryHistoryEntity::class,
        MemoryTrashEntity::class,
    ],
    version = 42,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 29, to = 30),
        // 30→31：agent_run 加 parent_conversation_id（可空纯列，无外键）
        AutoMigration(from = 30, to = 31),
        // 31→32：知识库三表（纯新增，RAG-as-tool）
        AutoMigration(from = 31, to = 32),
        // 会话列表自定义顺序 + 行内「末尾消息时间」（见 docs/design/design-conversation-manual-order.md）：
        // 两个**可空**新列 ⇒ AutoMigration 无需 spec（无 notNull 默认值问题）
        AutoMigration(from = 32, to = 33),
        // 记忆渐进披露 V2 + 会话级「不使用记忆」开关（见 docs/design/design-memory-discipline.md §4）：
        // memoryentity 加 description/read_count/last_read_at、conversations 加 memory_disabled，
        // 全部 NOT NULL + DEFAULT（description 不回填——空串=模型没写过，渲染时用内容截断兜底）
        AutoMigration(from = 33, to = 34),
        // 记忆做梦（V3）的整理进度表：纯新增表（CASCADE 外键挂会话），无回填
        // （没有进度行 = 用做梦开关的开启时刻当基线，见 docs/design/design-memory-consolidation.md §7）
        AutoMigration(from = 34, to = 35),
        // 工作区新增「单次读取上限」（max_read_file_bytes，默认 8MB = 迁移前的硬编码值）
        AutoMigration(from = 35, to = 36),
        // 36→37：记忆 L1 锚点（is_anchor，NOT NULL + DEFAULT 0，存量全部为 false ⇒ 行为不变）
        // 见 docs/design/design-memory-injection-tiering.md
        AutoMigration(from = 36, to = 37),
        // 37→38：会话级锚点冻结快照（可空纯列 ⇒ 无需 spec）。
        // 存量会话为 null = 未冻结（用实时锚点），行为与改动前一致。
        // 见 docs/design/design-memory-injection-tiering.md
        AutoMigration(from = 37, to = 38),
        // 38→39：记忆整理**建议**落库（纯新增表，无回填、无外键——理由见实体 kdoc）。
        // 修的是「默认配置下自动整理烧了模型调用，建议却既无应用路径、重启即失」。
        // 见 docs/design/design-memory-retrieval-and-consolidation.md §5
        AutoMigration(from = 38, to = 39),
        // 39→40：记忆库「全量复核」游标（纯新增表）。没有它，整理侧的快照截断 +
        // 每轮同一选取位置 ⇒ 约 3/4 的记忆**永远不进模型视野**，「存量记忆本身的整理」无从谈起。
        // 见 docs/design/design-memory-retrieval-and-consolidation.md §7
        AutoMigration(from = 39, to = 40),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun memoryHistoryDao(): MemoryHistoryDAO

    abstract fun memoryTrashDao(): MemoryTrashDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun compressionCheckpointDao(): CompressionCheckpointDAO

    abstract fun agentRunDao(): AgentRunDAO

    abstract fun knowledgeBaseDao(): KnowledgeBaseDAO

    abstract fun memoryConsolidationDao(): MemoryConsolidationDAO

    abstract fun memoryProposalDao(): MemoryProposalDAO

    abstract fun memoryReviewCursorDao(): MemoryReviewCursorDAO
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
