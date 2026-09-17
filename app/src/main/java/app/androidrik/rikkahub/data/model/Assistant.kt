package app.androidrik.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption
import app.androidrik.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val fallbackChatModelId: Uuid? = null, // 主模型重试耗尽后切换的备用模型（跨 provider 轻量降级）
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    /**
     * 记忆修改审批：为 true 时，AI 通过 memory_tool **修改/删除**记忆需用户确认；为 false 时自动执行。
     *
     * 为什么按 action 分档而非一刀切：create 是记忆的常态写入，每次打断会因打扰致用户关闭记忆功能；
     * edit 覆盖已有内容、delete 数据丢失，才是「未经许可的修改/删除」的真正风险点。
     * 见 roadmap「一、中等价值 → 记忆工具审批」。
     */
    val memoryModifyApproval: Boolean = true,
    /**
     * 记忆创建审批：为 true 时，AI 通过 memory_tool **新增**记忆也需用户确认。
     *
     * **默认 false**——保持既有行为（新增直接记录，不打断），本开关是 opt-in：
     * 适用于担心模型过度记流水账、或希望逐条把关新增内容的用户。
     * 与 [memoryModifyApproval] 相互独立（可只拦新增、不拦修改）。
     */
    val memoryCreateApproval: Boolean = false,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
)

/**
 * 一条长期记忆。
 *
 * [id] 是对外的**稳定 opaque token**（非自增编号）——模型在 `memory_tool` 里用它定位
 * edit/delete 目标，UI 也显示它。之所以不用自增编号：删除空洞会让编号不连续（难看），
 * 且编号泄漏记忆条数（模型会当成总数）；随机 token 一起根治且永不需要重排。
 *
 * [sortKey] 是内部排序键（自增主键），**不参与序列化**——用于注入时的稳定输出顺序
 * （与记忆页展示一致），并保证行为不依赖调用方列表顺序。
 *
 * [importance]/[updatedAt] 服务于**注入打分校准**（`docs/design-memory-scoring.md`）：
 * 打分 `importance × (0.55 + 0.45 × 时效衰减)` 决定「谁该进上下文」，
 * 而输出顺序仍由 [sortKey] 固定以保住前缀缓存。
 */
@Serializable
data class AssistantMemory(
    val id: String = "",
    val content: String = "",
    /**
     * 重要度 0.0–1.0（模型写入时给、用户可覆盖）。序列化——工具结果里回显这个值，
     * 模型可据此确认自己填了什么。
     */
    val importance: Float = 0.6f,
    @Transient
    val sortKey: Int = 0,
    /** 最近修改时间（epoch millis），时效衰减基准。**不序列化**——对模型是噪声，仅内部打分用。 */
    @Transient
    val updatedAt: Long = 0L,
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

private fun compileRegexCached(pattern: String): Regex? {
    regexCache.getIfPresent(pattern)?.let { return it.getOrNull() }
    val result = runCatching { Regex(pattern) }.onFailure { it.printStackTrace() }
    regexCache.put(pattern, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            val compiled = compileRegexCached(regex.findRegex) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 次要关键词的匹配逻辑（对齐 SillyTavern `world_info_logic`，`world-info.js:33-38`）。
 *
 * 世界书的**过度触发**防护：主键命中后，再用次要键二次收窄。缺了它，ST 世界里
 * 大量「靠次要键抑制误触发」的条目会只凭主键就注入（见 `docs/peer-apps-benchmark.md` §6.2 ①）。
 */
@Serializable
enum class SelectiveLogic {
    /** 任一 次要键命中即通过（ST 默认，= ST 的 AND_ANY(0)） */
    @SerialName("and_any")
    AND_ANY,

    /** 任一 次要键**不**命中即通过（= ST 的 NOT_ALL(1)） */
    @SerialName("not_all")
    NOT_ALL,

    /** **全部** 次要键都不命中才通过（= ST 的 NOT_ANY(2)） */
    @SerialName("not_any")
    NOT_ANY,

    /** **全部** 次要键都命中才通过（= ST 的 AND_ALL(3)） */
    @SerialName("and_all")
    AND_ALL,
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
        /**
         * 次要关键词：**主键命中后**用它二次收窄（对齐 ST `keysecondary`）。
         *
         * 空 = 不启用二次判定（完全保持旧行为）。非空时按 [selectiveLogic] 判定。
         * 缺了它，ST 世界里靠次要键抑制误触发的条目会只凭主键就注入。
         */
        val secondaryKeywords: List<String> = emptyList(),
        /** 次要关键词的判定逻辑；仅当 [secondaryKeywords] 非空时生效。 */
        val selectiveLogic: SelectiveLogic = SelectiveLogic.AND_ANY,
        /**
         * 额外把**助手的人设文本**（`Assistant.systemPrompt`）纳入关键词扫描范围。
         *
         * 对齐 SillyTavern 的 6 个 `match*` 开关（`matchCharacterDescription` /
         * `matchCharacterPersonality` / `matchScenario` / `matchCharacterDepthPrompt` /
         * `matchCreatorNotes` / `matchPersonaDescription`，默认均为 false）。
         *
         * **为什么并成一个开关**：ST 把这些内容放在 6 个独立字段里，而 fork 在角色卡导入时
         * 已把它们**压成一条 `systemPrompt`**（`AssistantImporter.kt`）——字段边界已丢失，
         * 无法还原成 6 个开关。并成一个「扫人设」是**诚实**的近似：
         * 语义是「关键词出现在角色设定里也算命中」，正是那些 flag 的共同意图。
         * 代价：无法只扫其中某一段（fork 没有可区分的段）。
         *
         * 不开（默认）时行为与旧版一致——只扫最近 N 条对话消息。
         */
        val matchAssistantPrompt: Boolean = false,
    ) : PromptInjection()
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
)

/**
 * 单个关键词是否在 [context] 中命中（主键与次要键共用同一语义）。
 *
 * 正则编译失败按「不命中」处理（与旧实现一致，避免坏正则让条目恒激活）。
 */
private fun PromptInjection.RegexInjection.matchesKeyword(context: String, keyword: String): Boolean {
    return if (useRegex) {
        try {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            Regex(keyword, options).containsMatchIn(context)
        } catch (e: Exception) {
            false
        }
    } else {
        if (caseSensitive) {
            context.contains(keyword)
        } else {
            context.contains(keyword, ignoreCase = true)
        }
    }
}

/**
 * 检查 RegexInjection 是否被触发
 *
 * 判定分两步（对齐 SillyTavern `world-info.js:4920-4970`）：
 * 1. **主键**（[PromptInjection.RegexInjection.keywords]）任一命中；
 * 2. 若配了**次要键**（[PromptInjection.RegexInjection.secondaryKeywords]），
 *    再按 [PromptInjection.RegexInjection.selectiveLogic] 二次收窄。
 *
 * **次要键为空 = 跳过第 2 步**，行为与旧版完全一致（故存量条目零回归）。
 *
 * @param context 要扫描的上下文文本
 * @return 是否触发
 */
fun PromptInjection.RegexInjection.isTriggered(context: String): Boolean {
    if (!enabled) return false
    if (constantActive) return true
    if (keywords.isEmpty()) return false

    if (keywords.none { matchesKeyword(context, it) }) return false

    // 主键已命中：无次要键则直接通过（旧行为）
    if (secondaryKeywords.isEmpty()) return true

    val matches = secondaryKeywords.map { matchesKeyword(context, it) }
    return when (selectiveLogic) {
        // 任一次要键命中即通过（ST 默认）
        SelectiveLogic.AND_ANY -> matches.any { it }
        // 任一次要键「不」命中即通过
        SelectiveLogic.NOT_ALL -> matches.any { !it }
        // 全部次要键都不命中才通过
        SelectiveLogic.NOT_ANY -> matches.none { it }
        // 全部次要键都命中才通过
        SelectiveLogic.AND_ALL -> matches.all { it }
    }
}

/**
 * 从消息列表中提取用于匹配的上下文文本
 *
 * @param messages 消息列表
 * @param scanDepth 扫描深度（最近N条消息）
 * @param extraTexts 额外纳入扫描的文本（如助手人设，对齐 ST 的 `match*` 开关）。
 *   追加在消息文本之后——顺序不影响命中判定（匹配是「包含」语义），
 *   但保持稳定顺序便于预览/排查对照。
 */
fun extractContextForMatching(
    messages: List<UIMessage>,
    scanDepth: Int,
    extraTexts: List<String> = emptyList(),
): String {
    val messageText = messages
        .takeLast(scanDepth)
        .joinToString("\n") { it.toText() }
    if (extraTexts.isEmpty()) return messageText
    val extras = extraTexts.filter { it.isNotBlank() }
    if (extras.isEmpty()) return messageText
    return (listOf(messageText) + extras).joinToString("\n")
}

/**
 * 获取所有被触发的注入，按优先级排序
 *
 * @param injections 所有注入规则
 * @param context 上下文文本
 * @return 被触发的注入列表，按优先级降序排列
 */
fun getTriggeredInjections(
    injections: List<PromptInjection.RegexInjection>,
    context: String
): List<PromptInjection.RegexInjection> {
    return injections
        .filter { it.isTriggered(context) }
        .sortedByDescending { it.priority }
}
