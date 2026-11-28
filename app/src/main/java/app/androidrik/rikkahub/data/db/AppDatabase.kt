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
import app.androidrik.rikkahub.data.db.dao.LanConversationTombstoneDAO
import app.androidrik.rikkahub.data.db.dao.LanConversationTombstoneEntity
import app.androidrik.rikkahub.data.db.dao.MemoryConsolidationDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.dao.MemoryReviewCursorDAO
import app.androidrik.rikkahub.data.db.dao.ManagedFileDAO
import app.androidrik.rikkahub.data.db.dao.MediaCreationDAO
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryFileDAO
import app.androidrik.rikkahub.data.db.dao.MemoryFileVersionDAO
import app.androidrik.rikkahub.data.db.dao.MemoryFileProgressDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.dao.MessageFileRefDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.dao.ScheduledTaskDAO
import app.androidrik.rikkahub.data.db.dao.TranslationHistoryDAO
import app.androidrik.rikkahub.data.db.dao.UsageRecordDAO
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
import app.androidrik.rikkahub.data.db.entity.MediaCreationNodeEntity
import app.androidrik.rikkahub.data.db.entity.MediaCreationRecordEntity
import app.androidrik.rikkahub.data.db.entity.MediaCreationSessionEntity
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileVersionEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileProgressEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity
import app.androidrik.rikkahub.data.db.entity.MessageFileRefEntity
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity
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
        MemoryFileEntity::class,
        MemoryFileVersionEntity::class,
        MemoryFileProgressEntity::class,
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        WorkspaceEntity::class,
        FolderEntity::class,
        CompressionCheckpointEntity::class,
        AgentRunEntity::class,
        app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity::class,
        KnowledgeBaseEntity::class,
        KnowledgeBaseDocumentEntity::class,
        KnowledgeEmbeddingEntity::class,
        MemoryConsolidationEntity::class,
        MemoryProposalEntity::class,
        MemoryReviewCursorEntity::class,
        MemoryHistoryEntity::class,
        MemoryTrashEntity::class,
        MediaCreationSessionEntity::class,
        MediaCreationNodeEntity::class,
        MediaCreationRecordEntity::class,
        UsageRecordEntity::class,
        TranslationHistoryEntity::class,
        ScheduledTaskEntity::class,
        ScheduledTaskRunEntity::class,
        MessageFileRefEntity::class,
        LanConversationTombstoneEntity::class,
    ],
    version = 82,
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
        // 43→44 不在这里：用量流水表带**回填**（用消息上的 usage 灌历史），
        // 由手写迁移 Migration_43_44 负责，见 docs/design/design-usage-stats.md
        AutoMigration(from = 42, to = 43),
        // 44→45：翻译历史表（纯新增，无回填、无外键）。表名/列名与原版 3.x 的
        // translation_history 同构，旧备份转换时按列名交集就能一并带过来。
        // 见 docs/design/design-translation-history.md
        AutoMigration(from = 44, to = 45),
        // Nullable upstream snapshot; existing native conversations keep following their assistant.
        AutoMigration(from = 45, to = 46),
        // 48→49：定时任务表（纯新增，无外键无回填，见 docs/design/design-scheduled-ai-tasks.md §四）。
        AutoMigration(from = 48, to = 49),
        // 49→50：定时任务支持三种执行目标（已有会话 / 专属新会话 / 每轮新建）。
        // 三个纯新增列：target_mode NOT NULL + DEFAULT 'EXISTING'（老行=老行为），
        // assistant_id / last_conversation_id 可空 ⇒ 无需 spec、无需回填。
        // 见 docs/design/design-scheduled-ai-tasks.md §4.1
        AutoMigration(from = 49, to = 50),
        // 50→51：定时任务「附带长期目标」开关（with_goal，NOT NULL + DEFAULT 0，存量=关闭，
        // 行为不变）。目标本体在 conversations 表（见 51→52）。见 §四/§六
        AutoMigration(from = 50, to = 51),
        // 51→52：会话长期目标（conversations.goal，NOT NULL + DEFAULT ''，空串=未设置，
        // 映射层 ifEmpty { null }，与 custom_system_prompt 同款编码）。定时任务 P2 目标语义。
        // 见 docs/design/design-scheduled-ai-tasks.md §四
        AutoMigration(from = 51, to = 52),
        // 52→53：定时任务运行账本（纯新增表，无外键无回填）。每次执行一行（含 SKIP/
        // TARGET_GONE），终态必达；诊断「后台为什么没跑成」不再只有任务表里的 last_status。
        // 见 docs/design/design-scheduled-ai-tasks.md §十（P2 运行账本）
        AutoMigration(from = 52, to = 53),
        // 53→54：NEW_EACH_RUN 自动清理（keep_last_conversations，NOT NULL + DEFAULT 0 = 关）。
        // 见 docs/design/design-scheduled-ai-tasks.md §4.2
        AutoMigration(from = 53, to = 54),
        // 54→55：收尾闸门（design-deliverable-gate.md）+ 隐形会话（design-proactive-task-management.md）
        // 合批——scheduled_tasks.deliverables（NULL=不设闸）、scheduled_task_runs.gate_retries
        // （NOT NULL DEFAULT 0）、conversationentity.hidden（NOT NULL DEFAULT 0）。全部免 spec。
        AutoMigration(from = 54, to = 55),
        // 55→56：AI 管理任务来源标记（design-proactive-task-management.md §三.4）
        // —— scheduled_tasks.created_by（NOT NULL DEFAULT 'user'）+ source_conversation_id。免 spec。
        AutoMigration(from = 55, to = 56),
        // 56→57：工作区「一键配置国内镜像源」摘要（PackageMirrorSetup 的 JSON，默认空对象）。
        // NOT NULL DEFAULT '{}'，免 spec。仅本地 rootfs 工作区使用（SSH 工作区不写）。
        AutoMigration(from = 56, to = 57),
        // 57→58：后台子代理的归属、投递模式与持久化投递状态。
        AutoMigration(from = 57, to = 58),
        // 58→59：会话级导出显示覆盖（conversations.export_display_config，**可空纯列** ⇒ 免 spec、
        // 无需回填：null = 跟随全局 `Settings.exportDisplayConfig`，行为与改动前一致）。
        // 见 ConversationEntity / Conversation.exportDisplayConfig 的 kdoc。
        AutoMigration(from = 58, to = 59),
        // 59→60：按消息覆盖导出显示（conversations.export_display_overrides，
        // `Map<messageId, ExportDisplayConfig>` 的 JSON；**可空纯列** ⇒ 免 spec、无需回填：
        // null/`{}` = 没有覆盖，行为与改动前一致）。见 Conversation.exportDisplayOverrides 的 kdoc。
        AutoMigration(from = 59, to = 60),
        AutoMigration(from = 60, to = 61),
        AutoMigration(from = 61, to = 62),
        AutoMigration(from = 62, to = 63),
        AutoMigration(from = 63, to = 64),
        // 64→65：「第 N 轮」触发标记（memory_consolidation.trigger_turn_fired，NOT NULL + DEFAULT 0
        // ⇒ 免 spec）。与 consolidated_at 同行提交，见 design-memory-consolidation-trigger.md §3.3。
        AutoMigration(from = 64, to = 65),
        // 65→66：树形分支 P0（design-conversation-tree-branching.md §五）——
        // message_node.parent_id + conversationentity.active_leaf_node_id，均**可空纯列** ⇒ 免 spec、
        // 无 SQL 回填：P0 读路径按 node_index（创建序）排序，树元数据由保存边界惰性填充。
        AutoMigration(from = 65, to = 66),
        AutoMigration(from = 66, to = 67),
        AutoMigration(from = 67, to = 68),
        // 68→69 不在这里：message_node 索引从 (conversation_id) 换成 (conversation_id, node_index)
        // （长会话分页读平方级，上游 c1c3c4d12 同款），由手写迁移 Migration_68_69 负责——只建索引删索引，
        // 不动表数据；索引非唯一，历史重复 node_index 不影响迁移。
        // 69→70 / 70→71 不在这里：usage_record.page_count（配料 03 §2.5）与工具计费三列
        // （配料 P2-1）——均由手写迁移 Migration_69_70 / Migration_70_71 负责（纯 ADD COLUMN，
        // NOT NULL DEFAULT 0；旧行全 0）。
        // 71→72 也不在这里：message_node.selected_message_id（懒加载阶段 11）需要 JSON1
        // **回填**旧行，由 Migration_71_72 负责。
        AutoMigration(from = 72, to = 73),
        AutoMigration(from = 73, to = 74),
        // 74→75：workspaces.storage_mounts（共享存储挂载列表，NOT NULL + DEFAULT '[]'，零回填）
        AutoMigration(from = 74, to = 75),
        AutoMigration(from = 75, to = 76),
        // 76→77：媒体创作记录加对账键（media_creation_record.request_id，**可空纯列** ⇒ 免 spec、
        // 无需回填：null = 提交前生成前中断或旧版本记录，对账时按不可对账的口径交由用户处置）。
        // 见 docs/design/in-progress/design-unified-media-generation.md「按请求 ID 对账」
        AutoMigration(from = 76, to = 77),
        // 77→78：用量流水加媒体计费单位（usage_record.image_count / video_seconds，
        // NOT NULL + DEFAULT 0 ⇒ 免 spec）。媒体生成按张/秒计费、多无 TokenUsage，
        // 与 OCR 按页行同款绕开零 token 闸门；金额估算挂账（刊例价因供应商×模型而异）。
        // 见 docs/design/drafts/design-interface-convergence-round2.md ①
        AutoMigration(from = 77, to = 78),
        // 78→79：compression_checkpoint 加 seq / batch_count（#2294 压缩摘要「#N · 本次 M 条」展示）。
        // 两列 NOT NULL + DEFAULT 0 ⇒ 免 spec；旧行全 0 = 无序号语义，UI 回退不带头文案。
        // seq 接力只看最新行（表经 trim 只留一行），无需回填。
        AutoMigration(from = 78, to = 79),
        // 79→80：文件→会话关联表 message_file_ref（design-file-conversation-link.md Phase B，
        // 纯新增表、无外键无回填——存量由运行时回填 Job 灌入，理由见实体 kdoc）。
        AutoMigration(from = 79, to = 80),
        AutoMigration(from = 80, to = 81),
        AutoMigration(from = 81, to = 82),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun lanConversationTombstoneDao(): LanConversationTombstoneDAO

    abstract fun memoryDao(): MemoryDAO
    abstract fun memoryFileDao(): MemoryFileDAO
    abstract fun memoryFileVersionDao(): MemoryFileVersionDAO
    abstract fun memoryFileProgressDao(): MemoryFileProgressDAO

    abstract fun memoryHistoryDao(): MemoryHistoryDAO

    abstract fun memoryTrashDao(): MemoryTrashDAO

    abstract fun genMediaDao(): GenMediaDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun messageFileRefDao(): MessageFileRefDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun workspaceDao(): WorkspaceDAO

    abstract fun folderDao(): FolderDAO

    abstract fun compressionCheckpointDao(): CompressionCheckpointDAO

    abstract fun externalNoticeDao(): app.androidrik.rikkahub.data.db.dao.ExternalNoticeDAO

    abstract fun agentRunDao(): AgentRunDAO

    abstract fun knowledgeBaseDao(): KnowledgeBaseDAO

    abstract fun memoryConsolidationDao(): MemoryConsolidationDAO

    abstract fun memoryProposalDao(): MemoryProposalDAO

    abstract fun memoryReviewCursorDao(): MemoryReviewCursorDAO

    abstract fun mediaCreationDao(): MediaCreationDAO

    abstract fun usageRecordDao(): UsageRecordDAO

    abstract fun translationHistoryDao(): TranslationHistoryDAO

    abstract fun scheduledTaskDao(): ScheduledTaskDAO


    companion object {
        /** 主库文件名。曾散置四处硬编码（RikkaHubApp/DataSourceModule/BackupArchiveEngine/隔离件），统一从这里取。 */
        const val NAME = "rikka_hub"

        /**
         * 必须与上方 `@Database(version = …)` 保持一致（启动期 staged 库校验、降级检测用）。
         * 曾漂移到 54 落后真实版本 56，导致 v55/56 的库过不了 staged 恢复校验（2026-10-31 根治）。
         * 双保险防再犯：SchemaVersionConsistencyTest 在编译期拿 schemas/ 最新 JSON 对账；
         * RikkaHubApp 启动哨兵在运行时拿 Room 真实开库版本兜底。
         */
        const val SCHEMA_VERSION = 82
    }
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
