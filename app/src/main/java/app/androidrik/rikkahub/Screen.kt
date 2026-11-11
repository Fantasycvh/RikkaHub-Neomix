package app.androidrik.rikkahub

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null,
        /**
         * 仅用于兼容旧版小窗交接保存的导航状态；小窗功能已移除，不再写入或读取。
         * 保留字段可避免升级后恢复旧返回栈时因未知字段反序列化失败。
         */
        val appendDraft: Boolean = false,
    ) : Screen

    @Serializable
    data class ShareHandler(
        val text: String,
        val streamUri: String? = null, // Legacy saved navigation state.
        val streamUris: List<String> = emptyList(),
    ) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    /** 生图（上游 #2123）：单独一类——它既不是记忆也不是本地工具 */
    data class AssistantImage(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    /** 备份页（二级列表）里的各功能详情页（三级）。 */
    @Serializable
    data object BackupImportExport : Screen

    @Serializable
    data object BackupWebDav : Screen

    @Serializable
    data object BackupS3 : Screen

    @Serializable
    data object BackupReminder : Screen

    @Serializable
    data object BackupSnapshot : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data object MediaCreationSessions : Screen

    @Serializable
    data class MediaCreation(val sessionId: String) : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesAppIdentity : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    /** 设置页全局搜索（跨所有设置页搜具体设置项） */
    @Serializable
    data object SettingGlobalSearch : Screen

    /** 知识库管理（RAG-as-tool）：列表 + 详情（编辑/文档/索引） */
    @Serializable
    data object SettingKnowledgeBase : Screen

    @Serializable
    data class SettingKnowledgeBaseDetail(val kbId: String) : Screen

    /** 助手绑定知识库（决定 search_knowledge_base 工具检索哪些库） */
    @Serializable
    data class AssistantKnowledgeBase(val assistantId: String) : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingScheduledTasks : Screen

    /** 记忆整理触发方式（全局配置，docs/design/design-memory-consolidation-trigger.md） */
    @Serializable
    data object SettingMemoryTrigger : Screen

    @Serializable
    data class SettingScheduledTaskDetail(val taskId: String) : Screen

    /** 任务运行历史二级页（P3）：详情页的「运行历史」入口进这里 */
    @Serializable
    data class SettingScheduledTaskRuns(val taskId: String) : Screen

    @Serializable
    data class SettingScheduledTaskCreate(val conversationId: String? = null) : Screen

    @Serializable
    data object SettingMedia : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingStorage : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    /** 拼装后提示词预览（只读，见 `docs/peer-apps-benchmark.md` §6.7 ③）；带会话 id 故为 data class。 */
    @Serializable
    data class PromptInspect(val conversationId: String) : Screen

    @Serializable
    data object Extensions : Screen

    /** 插件中心(内置插件列表 + 启用开关 + 能力声明)。 */
    @Serializable
    data object Plugins : Screen

    /** 插件详情;插件自带配置界面在页内内嵌,不新增设置页路由。 */
    @Serializable
    data class PluginDetail(val pluginId: String) : Screen

    /** JS 插件修订编辑器(仅 debug 构建的通道入口可达,见设计稿 §5.2)。 */
    @Serializable
    data class PluginEditor(val pluginId: String) : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    /**
     * Tier 4：从拼装预览的某个注入段跳到**产出它的条目**编辑处。
     *
     * 为什么单独一条路由而不是给 [Prompts] 加可空参数：`Screen` 是 Navigation 的类型化路由，
     * 给既有 route 加参数会改变其签名（影响深链与返回栈恢复）；独立路由让「带目标的跳转」
     * 与「普通进入」互不干扰，语义也更清楚。
     */
    @Serializable
    data class PromptEntryTarget(
        val containerId: String,
        val entryId: String,
        val lorebook: Boolean,
    ) : Screen

    @Serializable
    data object Worldbooks : Screen

    @Serializable
    data object Skills : Screen

    /** 无头 agent run 的账本列表（设计见 docs/design/design-subagent-context.md §9.3/9.4）。 */
    @Serializable
    data object AgentRuns : Screen

    /** 任务中心（P0）：agent run / 后台命令 / 定时任务三段合一，见 design-agent-task-runtime §5.5。 */
    @Serializable
    data object TaskCenter : Screen

    /** 单个 run 的详情（状态 + 轨迹回放）。 */
    @Serializable
    data class AgentRunDetail(val runId: String) : Screen

    /** 子代理预设管理（「按名称调度的模型 + 提示词」组合，设计 §十）。 */
    @Serializable
    data object SubagentPresets : Screen


    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceSshEditor(val id: String? = null) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}
