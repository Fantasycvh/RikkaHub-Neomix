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
import app.androidrik.rikkahub.data.db.dao.ManagedFileDAO
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
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
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
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
    ],
    version = 33,
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
        // 会话列表自定义顺序 + 行内「末尾消息时间」（见 docs/design-conversation-manual-order.md）：
        // 两个**可空**新列 ⇒ AutoMigration 无需 spec（无 notNull 默认值问题）
        AutoMigration(from = 32, to = 33),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun compressionCheckpointDao(): CompressionCheckpointDAO

    abstract fun agentRunDao(): AgentRunDAO

    abstract fun knowledgeBaseDao(): KnowledgeBaseDAO
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
