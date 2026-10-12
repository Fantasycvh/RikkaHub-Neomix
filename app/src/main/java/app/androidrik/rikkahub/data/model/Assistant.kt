package app.androidrik.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.androidrik.common.settings.Enableable
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
    override val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val fallbackChatModelId: Uuid? = null, // 主模型重试耗尽后切换的备用模型（跨 provider 轻量降级）
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    /**
     * 角色卡导入时保留的**分区零件**（SillyTavern 的 character sheet 字段）。
     *
     * **为什么保留**：此前导入会把这 4 个字段用 `buildString` **压成一条不可分的
     * `systemPrompt`**，槽位信息在导入那一步就永久丢失（无法单独查看/编辑/重排）。
     * 见 `docs/peer-apps-benchmark.md` §6.2 ④。
     *
     * **权威关系**：`systemPrompt` 仍是**唯一**被发送的文本（保持既有行为与可自由编辑）；
     * 这几个字段是「零件」，供查看/单独编辑，并经 [buildCardSystemPrompt] 一键重建
     * `systemPrompt`。刻意不让它们参与请求组装——否则会出现两个真相源，且用户手改
     * `systemPrompt` 后会被静默覆盖。
     *
     * 全为 null = 非角色卡导入（纯手工助手）。
     */
    val cardDescription: String? = null,
    val cardPersonality: String? = null,
    val cardScenario: String? = null,
    val cardExampleDialogue: String? = null,
    /** 角色卡里显式的 `system_prompt` 字段（与 [systemPrompt] 不同：那是卡片给出的，这是实际发送的）。 */
    val cardSystemPrompt: String? = null,
    /**
     * 角色卡各段的**拼装顺序**（对齐 ST `prompt_order` 里 charDescription/scenario/… 的槽位序）。
     *
     * **空 = 用 [CardSlot.defaultOrder]**（默认顺序与历史输出一致）。
     * 之所以用「空表示默认」而不是把默认列表写死在这里：将来新增槽位时，
     * 存量助手（空列表）能自动获得新槽位，而不必逐个迁移。
     */
    val cardSlotOrder: List<CardSlot> = emptyList(),
    /**
     * 被用户**关掉**的角色卡段（不参与 [buildCardSystemPrompt] 渲染）。
     *
     * 对应 ST 里逐槽位的开关；`null` 内容与「关掉」是两件事：前者会渲染成
     * `Empty` 占位符（历史行为），后者才是整段不出现。
     */
    val disabledCardSlots: Set<CardSlot> = emptySet(),
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
    /**
     * **空闲后整理记忆（做梦，V3）**：`0` = 关；`> 0` = 开启时刻（epoch millis）。
     *
     * 用一个时间戳而不是 `Boolean`：它**同时是基线**——做梦只对「开启之后的新轮次」负责。
     * 否则刚拨开开关就会把几百个历史会话一次性灌进整理队列（用户没要求的后台模型花费）；
     * 关了再开以新的时刻为准（关闭期间的内容不补算）。想要历史被整理，走**手动整理**
     * （不受基线与开关限制）。见 `docs/design/design-memory-consolidation.md` §7。
     *
     * **默认关**：做梦是额外的一次后台模型调用，属于「会在用户没看着的时候花钱」的自动行为，
     * 本仓对这类功能一贯要求显式开启。
     */
    val memoryConsolidationSince: Long = 0L,
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    /**
     * 合成内容（system 提示本体 / 提示词注入 / 时间与工作区提醒 / 压缩检查点）的**独立**模板。
     *
     * **默认空 = 不套用**（保持既有行为）：合成消息带 `isSynthetic` 标记，默认被
     * `TemplateTransformer` 跳过——这是上游 #1790 的取舍，因为 [messageTemplate] 会把
     * `{{time}}/{{date}}` 渲染进每条消息，而合成消息的 `createdAt` 就是**本次请求时间**，
     * 让它们参与渲染会让 system 前缀每次请求都变、prompt 缓存必破。
     *
     * 本字段是给「希望 system/注入内容在线上也与会话轮次形态一致」的用户的 opt-in：它的渲染
     * 上下文**刻意只提供 `{{ message }}` 与 `{{ role }}`，不提供 `{{time}}/{{date}}`** —— 由构造
     * 保证渲染结果与请求时刻无关，从而在拿到形态一致性时不牺牲缓存稳定性。模板缺 `{{ message }}`
     * 时按未设置处理（回退不套用），避免整段 system 提示被渲染成空。
     */
    val systemMessageTemplate: String = "",
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
    /**
     * 关联的**预设** id（整包启用）。
     *
     * 与 [modeInjectionIds] 并存而非取代：预设是「一次绑定一组」的便捷方式，
     * 而条目级绑定仍保留（可单独勾选预设外的条目，也可让同一预设在不同助手里
     * 再单独增删条目）。生效条目 = 两者并集（按 id 去重）。
     */
    val promptPresetIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    /**
     * 绑定的**知识库** id 集（RAG-as-tool）。非空时装配 `search_knowledge_base` 工具，
     * 模型按需检索——不进每轮 prompt（与注入式语义检索的本质区别）。
     */
    val enabledKnowledgeBaseIds: Set<String> = emptySet(),
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val timeReminderIntervalMinutes: Int = 60,          // 时间提醒间隔（分钟，至少 1 分钟）
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
    /**
     * 助手**归档**开关（false = 已停用）。
     *
     * 语义边界（与删除的本质区别）：停用只把助手从**选择器**里藏起来（新建会话选不到），
     * **既有会话照常可用**——`getCurrentAssistant()` / `getAssistantById` 的解析链不过滤此字段，
     * 会话绑定、记忆、备份全都不受影响。要彻底移除走删除（会连带删会话与记忆）。
     *
     * 场景：角色卡导入膨胀出的助手，不再常用但不想丢数据。`@Serializable` 子对象字段
     * 随 JSON 自动持久化，旧数据默认 true，无需迁移。
     */
    override val enabled: Boolean = true,
) : Enableable

/**
 * 助手选择器的**过滤纯函数**（UI 与单测共用）。
 *
 * 规则：已停用助手默认隐藏（[showDisabled] = 管理动作场景如导入目标/恢复模式仍显示）；
 * 标签过滤与其叠加（与关系，标签空集 = 不过滤）。
 *
 * 刻意**不**内联进 composable：过滤规则是「停用语义」的落地处，钉进单测
 * 防止日后有人把「停用」悄悄改成「连解析也挡掉」。
 */
fun List<Assistant>.filterForPicker(
    showDisabled: Boolean = false,
    selectedTagIds: Set<Uuid> = emptySet(),
): List<Assistant> = filter { assistant ->
    (showDisabled || assistant.enabled) &&
        (selectedTagIds.isEmpty() || assistant.tags.any { it in selectedTagIds })
}

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
 * [importance]/[updatedAt] 服务于**注入打分校准**（`docs/design/design-memory-scoring.md`）：
 * 打分 `importance × (0.55 + 0.45 × 时效衰减)` 决定「谁该进上下文」，
 * 而输出顺序仍由 [sortKey] 固定以保住前缀缓存。
 */
/**
 * 角色卡 system prompt 的**槽位**（对齐 SillyTavern `prompt_order` 里的槽位概念）。
 *
 * 导入时各段被保留为独立字段，因此可以逐段开关、重排——而不再是压平后不可分的一条字符串。
 * 见 `docs/peer-apps-benchmark.md` §6.2 ④ / §6.7 ⑤。
 */
@Serializable
enum class CardSlot {
    /** 开场白 `You are roleplaying as {name}.`（内容由姓名插值，不可编辑） */
    @SerialName("greeting")
    GREETING,

    /** 卡片自带 `system_prompt`（无标题；空白则整段不出现） */
    @SerialName("system_prompt")
    SYSTEM_PROMPT,

    @SerialName("description")
    DESCRIPTION,

    @SerialName("personality")
    PERSONALITY,

    @SerialName("scenario")
    SCENARIO,

    /** 示例对话（空白则整段不出现） */
    @SerialName("example")
    EXAMPLE;

    companion object {
        /**
         * 默认槽位顺序——**与历史（压平时代）的输出顺序完全一致**。
         *
         * 改它等于改变所有未显式排序的助手的请求内容，务必谨慎（有特征测试锁着）。
         */
        val defaultOrder: List<CardSlot> = listOf(
            GREETING,
            SYSTEM_PROMPT,
            DESCRIPTION,
            PERSONALITY,
            SCENARIO,
            EXAMPLE,
        )
    }
}

/**
 * 单个槽位渲染出的文本块；**null = 该槽位整段不出现**。
 *
 * 这是「哪些槽位可以不出现」的唯一真相源：
 * - [CardSlot.SYSTEM_PROMPT] / [CardSlot.EXAMPLE] 内容空白时整段不出现（历史行为）；
 * - 其余槽位即使内容为 null 也渲染 `Empty` 占位符（历史行为）。
 *
 * internal：拼装预览的 system 块化（`PromptAssembly.buildSystemBlocks`）对未漂移的
 * 角色卡助手逐槽渲染同一函数——发送内容与 `buildCardSystemPrompt` 逐字节一致。
 */
internal fun renderCardSlotBlock(slot: CardSlot, name: String, assistant: Assistant): String? =
    when (slot) {
        CardSlot.GREETING -> "You are roleplaying as $name."
        CardSlot.SYSTEM_PROMPT -> assistant.cardSystemPrompt?.takeIf { it.isNotBlank() }
        CardSlot.DESCRIPTION ->
            "## Description of the character\n${assistant.cardDescription ?: "Empty"}"
        CardSlot.PERSONALITY ->
            "## Personality of the character\n${assistant.cardPersonality ?: "Empty"}"
        CardSlot.SCENARIO ->
            "## Scenario\n${assistant.cardScenario ?: "Empty"}"
        CardSlot.EXAMPLE -> assistant.cardExampleDialogue
            ?.takeIf { it.isNotBlank() }
            ?.let { "## Example of the dialogue\n$it" }
    }

/**
 * 由角色卡零件渲染出实际发送的 system prompt（**按槽位顺序拼装，段间空行**）。
 *
 * **与压平时代的 `buildString` 逐字节等价**（含空行与 `Empty` 占位符）——这是刻意的：
 * 它是那次内联拼装的泛化（把「写死的顺序」换成 [CardSlot.defaultOrder]），不引入任何输出变化。
 * 有特征测试锁着这一点；若将来要改版式，改这里一处即可（导入侧与手动重建同步生效）。
 *
 * 注意：不读 [Assistant.systemPrompt]（那是输出目标而非输入）。
 */
fun buildCardSystemPrompt(
    name: String,
    assistant: Assistant,
): String {
    val order = assistant.cardSlotOrder.ifEmpty { CardSlot.defaultOrder }
    return order
        // 关掉的槽位不参与；同时过滤掉「空列表漏掉的槽位」——order 里没有的槽位不该出现
        .filter { it !in assistant.disabledCardSlots }
        .mapNotNull { renderCardSlotBlock(it, name, assistant) }
        .joinToString("\n\n")
}

/**
 * 便捷重载：直接由散字段渲染（供导入器使用，此时还没有 Assistant 实例）。
 *
 * 始终使用 [CardSlot.defaultOrder] 且不关闭任何槽位——导入那一刻的产物与历史完全一致。
 */
fun buildCardSystemPrompt(
    name: String,
    cardSystemPrompt: String?,
    description: String?,
    personality: String?,
    scenario: String?,
    exampleDialogue: String?,
): String = buildCardSystemPrompt(
    name = name,
    assistant = Assistant(
        name = name,
        cardSystemPrompt = cardSystemPrompt,
        cardDescription = description,
        cardPersonality = personality,
        cardScenario = scenario,
        cardExampleDialogue = exampleDialogue,
    ),
)

/**
 * 该助手是否来自角色卡导入（有任一分区零件）。
 *
 * 用于决定是否显示「角色卡分区」编辑区——手工创建的助手不该看到一排都是空的卡片字段。
 */
val Assistant.hasCardParts: Boolean
    get() = cardDescription != null ||
        cardPersonality != null ||
        cardScenario != null ||
        cardExampleDialogue != null ||
        cardSystemPrompt != null

/**
 * 由本助手的零件重建 `systemPrompt`（供「重新生成提示词」按钮调用）。
 *
 * **走 [buildCardSystemPrompt] 的单参数重载**，因此尊重当前的槽位顺序与开关
 * （不能退回散字段重载——那会用默认顺序、且无视被关掉的槽位，等于静默丢弃用户的排序）。
 *
 * 若没有零件则原样返回（手工助手不应因此被清空提示词）。
 */
fun Assistant.rebuildSystemPromptFromCardParts(): Assistant =
    if (!hasCardParts) {
        this
    } else {
        copy(systemPrompt = buildCardSystemPrompt(name = name, assistant = this))
    }

/**
 * 该段是否与当前 `systemPrompt` 一致（有零件才可判定）。
 *
 * 用途：槽位区显示「当前发送的提示词已偏离这些字段」——用户手改过 `systemPrompt` 后，
 * 字段编辑不会自动生效（权威是 `systemPrompt`），必须显式重建。不提示的话，
 * 用户改完字段发现请求没变会以为坏了。
 */
fun Assistant.cardPartsDivergeFromSystemPrompt(): Boolean =
    hasCardParts && systemPrompt != buildCardSystemPrompt(name = name, assistant = this)

/**
 * 当前生效的槽位顺序（空 = [CardSlot.defaultOrder]）。
 *
 * 供 UI 渲染与拖拽使用——UI 必须显示**生效后的**列表，否则空 order 时拖拽会从空列表开始。
 */
val Assistant.effectiveCardSlotOrder: List<CardSlot>
    get() = cardSlotOrder.ifEmpty { CardSlot.defaultOrder }

/**
 * 把 [fromIndex] 的槽位移动到 [toIndex]（纯函数，供拖拽回调与单测使用）。
 *
 * 越界索引原样返回（拖拽库偶尔会传入边界值，不该因此丢槽位）。
 */
fun Assistant.moveCardSlot(fromIndex: Int, toIndex: Int): Assistant {
    val current = effectiveCardSlotOrder.toMutableList()
    if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return this
    current.add(toIndex, current.removeAt(fromIndex))
    return copy(cardSlotOrder = current)
}

@Serializable
data class AssistantMemory(
    val id: String = "",
    val content: String = "",
    /**
     * 重要度 0.0–1.0（模型写入时给、用户可覆盖）。序列化——工具结果里回显这个值，
     * 模型可据此确认自己填了什么。
     */
    val importance: Float = 0.6f,
    /**
     * **这条记忆讲什么**（≤150 字符）。序列化：工具结果回显，模型能确认自己写的主题词。
     *
     * 它是**清单层**的主体（见 `docs/design/design-memory-discipline.md` §4.2）——全文注入预算外的
     * 记忆以这一行露出，模型据此决定是否 `memory_read` 取全文。空串 = 没写过。
     */
    val description: String = "",
    @Transient
    val sortKey: Int = 0,
    /** 最近修改时间（epoch millis），时效衰减基准。**不序列化**——对模型是噪声，仅内部打分用。 */
    @Transient
    val updatedAt: Long = 0L,
    /** 遥测：被 `memory_read` 取过几次。**不序列化**——对模型没用，也不该影响它的判断。 */
    @Transient
    val readCount: Int = 0,
    /** 遥测：最近一次被读取的时间（epoch millis；0 = 从未）。 */
    @Transient
    val lastReadAt: Long = 0L,
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

    @SerialName("end_of_chat")
    END_OF_CHAT,            // 本次请求末尾，以用户消息追加
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
 * 预设 —— 一组具名的提示词注入条目（对应 SillyTavern 的 Prompt Preset）。
 *
 * 为什么需要它：此前注入是**一个扁平的全局列表**，无法表达「多套方案共存/切换」
 * （导入第二份 ST 预设会与第一份混在一起，且分不出哪些条目属于谁）。
 * 见 `docs/peer-apps-benchmark.md` §6.2 ④。
 *
 * **与 [PromptInjection.ModeInjection] 的关系**：预设是**容器**（分组 + 名称 + 启用），
 * 条目仍是原本的 `ModeInjection`（位置/优先级/角色/内容都照旧）⇒ 拼装逻辑完全不变。
 */
@Serializable
data class PromptPreset(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.ModeInjection> = emptyList(),
)

/**
 * 把扁平条目列表包成一个预设（用于兼容旧数据/旧格式）。
 *
 * 存量只有一个扁平 `modeInjections` 列表、没有容器概念，读取时按此包装即可，
 * **无需数据迁移**（写入时才落到新结构）。
 */
fun List<PromptInjection.ModeInjection>.asSinglePreset(
    name: String = "",
): PromptPreset = PromptPreset(name = name, entries = this)

/**
 * 把「扁平条目列表」的编辑**归位**到各预设（[PromptPreset] 列表）。
 *
 * 用途：仍按扁平列表工作的编辑界面（如旧版注入 tab、未来若有的批量操作）改完之后，
 * 需要写回容器结构。规则：
 * - **已有条目**：按 id 归回原所属预设，且**预设内按新扁平列表的顺序重排**
 *   （否则拖拽调整的顺序不会生效——顺序是用户意图的一部分）；
 * - **被删条目**：自然消失（不在新列表中即丢弃）；
 * - **新增条目**（id 不属于任何预设）：追加到**第一个预设**；没有预设时新建一个；
 * - **跨预设重复 id**：先到先得（只保留第一次出现的那个预设），避免同一注入生效两次。
 *
 * 这样「编辑条目」与「保留分组」可以同时成立，不必为了容器化重写所有编辑界面。
 */
fun List<PromptPreset>.reconcileEntries(
    newEntries: List<PromptInjection.ModeInjection>,
): List<PromptPreset> {
    val byId = newEntries.associateBy { it.id }
    // 新的扁平顺序 = 用户调整后的顺序，用它给每个预设内部的条目排序
    val orderOf = newEntries.withIndex().associate { (index, entry) -> entry.id to index }
    val claimed = mutableSetOf<Uuid>()

    val rebased = map { preset ->
        preset.copy(
            entries = preset.entries
                .filter { byId.containsKey(it.id) }
                .sortedBy { orderOf.getValue(it.id) }
                .mapNotNull { byId[it.id] }
                // 跨预设去重：一旦被前面的预设认领，后面的就不再重复持有
                .filter { claimed.add(it.id) }
        )
    }

    // 未归属任何预设的（新增）条目 → 追加到第一个预设；无预设则新建一个
    val orphans = newEntries.filter { it.id !in claimed }
    if (orphans.isEmpty()) return rebased
    return if (rebased.isEmpty()) {
        listOf(PromptPreset(entries = orphans))
    } else {
        rebased.toMutableList().apply {
            this[0] = this[0].copy(entries = this[0].entries + orphans)
        }
    }
}

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

/**
 * 按 token 预算裁剪已命中的世界书条目（对齐 SillyTavern 的 `world_info_budget`）。
 *
 * 为什么需要：fork 此前**没有预算**，命中多少就注入多少——导入一个大型 ST 世界书（或
 * 关键词设得过宽）会把上下文塞满，且是**静默**的（用户看不出是哪些条目吃掉的）。
 * ST 默认取上下文的 25% 作为上限。
 *
 * 取舍（三处刻意的设计，与 `selectMemoriesForPrompt` 同款思路）：
 * - **按 priority 降序**取舍（高优先级的条目更该保住），其次按原顺序保持稳定。
 * - 超预算时**跳过本条并继续**，而不是 `break`：一条超长条目不该把后面的预算全堵死。
 * - 首条即使超预算也入选（`selected.isNotEmpty()` 守卫），避免「预算极小 → 一条都不注入」
 *   这种最坏结果——那会让用户以为功能坏了。
 *
 * @param budgetTokens token 上限；<= 0 表示**不限制**（保持旧行为）
 * @return 裁剪后的条目（仍按 priority 降序）
 */
fun List<PromptInjection.RegexInjection>.limitByTokenBudget(
    budgetTokens: Int,
    estimate: (String) -> Int,
): List<PromptInjection.RegexInjection> {
    if (budgetTokens <= 0) return this
    if (isEmpty()) return this

    val ranked = sortedWith(compareByDescending { it.priority })
    val selected = mutableListOf<PromptInjection.RegexInjection>()
    var used = 0
    for (entry in ranked) {
        // 常驻条目（constantActive）是用户显式声明的「永远要在」——不计预算、不淘汰。
        // 若把它们也纳入竞争，「设了预算就再也看不到常驻条目」会是反直觉的结果。
        if (entry.constantActive) {
            selected += entry
            continue
        }
        val cost = estimate(entry.content)
        if (used + cost > budgetTokens && selected.isNotEmpty()) continue
        selected += entry
        used += cost
    }
    return selected
}

/**
 * 按「视觉顺序」重写条目的 `priority`，使**列表顺序成为唯一真相**。
 *
 * 背景（`docs/peer-apps-benchmark.md` §6.2 ④）：拼装时 [PromptInjection] 是按
 * `priority` **降序**应用（`PromptInjectionTransformer` 的 `sortedByDescending { priority }`），
 * 列表顺序只在同优先级时作为 tiebreaker 生效 ⇒ 出现过「拖了半天却没变化」的现象
 * （用户给某条设过 priority 后，拖拽对那一条就失效了）。
 *
 * 解决方式（用户拍板）：**priority 是唯一来源，列表顺序只是它的可视化**。
 * 拖拽后调用本函数，把 top→bottom 依次映射为 `n-1, n-2, …, 0`，
 * 于是「显示顺序 == 生效顺序」恒成立。
 *
 * @param orderedEntries 用户拖拽后的**视觉顺序**（第一个 = 最靠前 = 优先级最高）
 * @return 重写 priority 后的列表（保持传入顺序，便于直接回写）
 */
fun normalizeEntryPriorities(
    orderedEntries: List<PromptInjection.ModeInjection>,
): List<PromptInjection.ModeInjection> {
    val top = orderedEntries.size - 1
    return orderedEntries.mapIndexed { index, entry ->
        entry.copy(priority = top - index)
    }
}

/**
 * 按 `priority` 降序排列（同优先级时保持原相对顺序，即稳定排序）。
 *
 * 供 UI 以「生效顺序」展示列表。**不要**用 `sortedByDescending` 之外的规则，
 * 否则显示顺序会与实际拼装顺序不一致。
 */
fun List<PromptInjection.ModeInjection>.sortedForDisplay(): List<PromptInjection.ModeInjection> =
    sortedByDescending { it.priority }
