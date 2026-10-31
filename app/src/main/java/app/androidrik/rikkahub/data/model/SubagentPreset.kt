package app.androidrik.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.androidrik.rikkahub.data.ai.prompts.SUBAGENT_PROMPT_DIGEST
import app.androidrik.rikkahub.data.ai.prompts.SUBAGENT_PROMPT_RESEARCH
import app.androidrik.rikkahub.data.ai.prompts.SUBAGENT_PROMPT_REVIEW
import kotlin.uuid.Uuid

/**
 * 子代理预设 —— 一个**「按名称调度的模型 + 提示词」组合**（设计见
 * `docs/design/design-subagent-context.md` §十）。
 *
 * 对话里的模型通过 `dispatch_subagent(name = "…", goal = "…")` 按 [name] 调度其中之一；
 * 被调度的一方在**自己的上下文**里跑（独立 `messages` + 自己的 system prompt + 受限工具集），
 * 只把最终答案回给父对话（见 §1.2/§1.3：独立上下文在本仓是「用不同入参调同一个组装函数」）。
 *
 * **为什么是独立实体而不是复用 [Assistant]**：预设要回答的问题只有一个——
 * 「这次派发用哪个模型、带哪段提示词、允许碰什么」。复用助手会把它拖进助手的元数据丛林
 * （头像/标签/快速消息/角色卡零件/正则/注入绑定…），而这些对子代理**全部无意义且有害**
 * ——尤其注入绑定与正则，那会让子代理的上下文被父对话的注入污染，正好推翻「独立上下文」。
 * 手工派发路径（运行页）仍可用助手当 profile，两者互补。
 *
 * **零迁移**：作为 [app.androidrik.rikkahub.data.datastore.Settings] 的新字段，缺省即内置预设
 * （`JsonInstant` 开了 `ignoreUnknownKeys`，旧数据读入时自动取默认值）。
 */
@Serializable
data class SubagentPreset(
    val id: Uuid = Uuid.random(),
    /**
     * 调度名：模型调用时传入的名字。同一份预设列表内唯一（大小写不敏感，见
     * [isSubagentNameAvailable]），允许中文（模型按原样回传即可）。
     */
    val name: String = "",
    /**
     * 用途说明：**同时**用于管理页列表（给人看）和派发工具的清单（给模型看，决定挑哪一个）。
     * 所以写得越清楚，模型选得越对。
     */
    val description: String = "",
    /** 该子代理使用的模型 id；**null = 跟随当前对话的模型**（开箱可用，不必逐个指定）。 */
    val modelId: Uuid? = null,
    /** 子代理自己的 system prompt（父对话的 system prompt 不会带过去）。 */
    val systemPrompt: String = "",
    /** 能力开关①：装配联网搜索工具（`search_*` / `scrape_*`）。 */
    val enableWebSearch: Boolean = false,
    /** 能力开关②：绑定的工作区（装配 `workspace_*` 文件工具）；null = 不碰文件。 */
    val workspaceId: Uuid? = null,
    /**
     * 工具信封：**进一步**收窄允许装配的工具名。
     *
     * - `null` = 按能力开关装配出的工具**全部**允许（默认，配置最少）；
     * - 空集 = **不给任何工具**（纯推理型子代理，如「第二意见」「要点提炼」）；
     * - 非空 = 只允许集合内的（与装配结果的交集）。
     *
     * 三种取值都由 [app.androidrik.rikkahub.data.ai.tools.resolveSubagentEnvelope] 统一解释，
     * 派发工具、运行器、管理页预览三处共用同一份逻辑——**所见即所得**。
     */
    val allowedTools: Set<String>? = null,
    /**
     * 派发前是否需要用户批准（「要不要现在派这个子代理」）。
     * 默认关：信封已经把工具面收窄到预设作者自己配的范围，逐次打断的收益低于打扰。
     */
    val requireApproval: Boolean = false,
    /**
     * **工具级**审批策略（「子代理跑起来后，撞上需要审批的工具怎么办」）——设计 §十一。
     *
     * 这个是解锁**写入类子代理**的开关：`ASK` 时 run 会**暂停**并弹通知/详情页卡片等人批准，
     * 批准后按真结果继续跑；`DENY`（默认）保持 Phase 1/2 的形态——直接策略拒绝、模型自行绕开。
     * 默认取 `DENY` 是"不改变既有行为"的选择；真正要用写入类工具的人会明确打开它。
     */
    val toolApproval: SubagentToolApproval = SubagentToolApproval.DENY,
    /** 单次运行超时（秒）；见 [SUBAGENT_TIMEOUT_SECONDS_RANGE]。 */
    val timeoutSeconds: Int = DEFAULT_SUBAGENT_TIMEOUT_SECONDS,
    /** 停用后不出现在派发工具的清单里（历史 run 不受影响）。 */
    val enabled: Boolean = true,
    /**
     * 交付物契约（purrcat A3，`docs/design/design-deliverable-gate.md` §二）：相对 workspace
     * 根的路径列表。非空 ⇒ run 自然收束时逐个校验「存在且非空」，缺失则打回继续补
     * （上限 [app.androidrik.rikkahub.data.ai.LOOP_GATE_MAX_RETRIES]，达上限强制放行）；
     * null = 不设闸（默认，旧数据行为不变——零迁移，本字段在 Settings JSON 里）。
     * 声明了 deliverables 但预设没绑 workspace ⇒ 降级为自检（不硬失败，§二.3.5）。
     */
    val deliverables: List<String>? = null,
)

/**
 * 工具级审批策略（见 [SubagentPreset.toolApproval]）。
 *
 * 刻意只有两档，不做"记住本次会话内同意"之类的中间态：那类状态一旦引入，
 * 就要回答"记多久、跨不跨 run、撤销入口在哪"，而手机上一次 run 本来就是一次性的动作单元。
 */
@Serializable
enum class SubagentToolApproval {
    /** 需审批的工具**直接策略拒绝**（Phase 1/2 的既有行为，默认）。 */
    @SerialName("deny")
    DENY,

    /** **暂停并请我批准**：通知栏两个按钮 + 运行详情页卡片，超时按拒绝处理并继续跑。 */
    @SerialName("ask")
    ASK,
}

/** 预设数量上限（与压缩档同款：防选择器/清单过长）。 */
const val MAX_SUBAGENT_PRESETS: Int = 20

/** 调度名长度上限（进 schema 描述，太长会把工具说明撑爆）。 */
const val SUBAGENT_NAME_MAX_CHARS: Int = 40

/**
 * 单 run 超时默认 10 分钟。
 *
 * 比手工派发路径的 30 分钟短：对话内派发是**阻塞**的——用户在等这一轮回答，
 * 一个不肯收尾的子代理不该把对话按住半小时（护栏管不住「模型慢慢磨」，只有截止时间能）。
 */
const val DEFAULT_SUBAGENT_TIMEOUT_SECONDS: Int = 600

/** 超时钳制范围：30 秒（再短基本跑不完一轮工具调用）～30 分钟（与手工路径的硬上限对齐）。 */
val SUBAGENT_TIMEOUT_SECONDS_RANGE: IntRange = 30..7200

/**
 * 内置预设的 **id 是固定的**（不是 `Uuid.random()`）。
 *
 * 理由：内置预设是 `Settings.subagentPresets` 的**默认值**。若用随机 id，在「用户从未
 * 写过设置」的设备上每次启动都会得到一批新 id，管理页列表的 key 随之漂移
 * （列表项重建、动画错乱），且无法在文档/测试里引用它们。
 */
private val BUILT_IN_RESEARCH_ID = Uuid.parse("69a2cec5-8df2-4e44-8f2d-bd20ec44adee")
private val BUILT_IN_REVIEW_ID = Uuid.parse("0fe3269b-a7d0-4c5c-b500-b60a2d70e574")
private val BUILT_IN_DIGEST_ID = Uuid.parse("a896c995-3c1b-42c4-a337-e39452696c94")

/**
 * 内置预设（三个开箱可用的组合）。
 *
 * **取舍**：内置的只放「不配任何东西也不会出错」的组合——模型留空（跟随当前对话）、
 * 不绑工作区。所以：①检索型只开联网搜索；②③纯推理型把信封设成**空集**（明确不给工具，
 * 而不是靠"没配就是没工具"的隐式语义）。
 *
 * **它们就存在用户列表里**（不像内置提供商那样另有墓碑机制）：用户可以直接改提示词、
 * 改模型、删掉。代价是删掉后不会自动回填——这是有意的，否则用户删了又被恢复会显得像 bug。
 */
val DEFAULT_SUBAGENT_PRESETS: List<SubagentPreset> = listOf(
    SubagentPreset(
        id = BUILT_IN_RESEARCH_ID,
        name = "research",
        description = "深度检索：多轮搜索后给出带来源链接的结论，适合需要翻资料、对来源有要求的问题。",
        systemPrompt = SUBAGENT_PROMPT_RESEARCH,
        enableWebSearch = true,
    ),
    SubagentPreset(
        id = BUILT_IN_REVIEW_ID,
        name = "review",
        description = "第二意见：以独立审稿人视角挑错（反例、漏洞、未说明的前提），不给工具、纯推理。",
        systemPrompt = SUBAGENT_PROMPT_REVIEW,
        allowedTools = emptySet(),
    ),
    SubagentPreset(
        id = BUILT_IN_DIGEST_ID,
        name = "digest",
        description = "要点提炼：把贴进任务里的长材料压成忠实摘要，保留数字与前提，不给工具、纯推理。",
        systemPrompt = SUBAGENT_PROMPT_DIGEST,
        allowedTools = emptySet(),
    ),
)

/**
 * 合成本次派发用的 **profile**（[Assistant]）。
 *
 * 组装函数（`buildInternalMessages`）要的是 `Assistant`，而预设只有「模型 + 提示词 + 能力」
 * ——这里把预设投影成一份**刻意最小**的助手副本，凡是「聊天助手才有意义」的字段一律清空：
 *
 * - `enableMemory=false`：记忆注入在 `generateInternal` 内部叠加，信封过滤覆盖不到它
 *   （Phase 1 run 已踩过），必须在源头关掉；
 * - `regexes` / `modeInjectionIds` / `promptPresetIds` / `lorebookIds` 空：**父对话的注入与
 *   正则不得渗进子代理**——否则「独立上下文」在提示词层面就不成立了；
 * - `localTools` 空、`enabledSkills` 空、`enableRecentChatsReference=false`：子代理不碰
 *   本机剪贴板/日历，也不查「我的最近对话」（那是父对话助手的作用域，没有意义）；
 * - `contextMessageLimit=0`、`temperature=null`：run 的轨迹本来就是线性的、从头跑起，
 *   不需要按条数截断，也不覆盖 provider 默认采样参数。
 *
 * **子代理不再派发子代理**：这里不设开关（`dispatch_subagent` 是否装配由全局设置决定），
 * 禁嵌套由信封侧恒定剔除该工具名保证（见 `resolveSubagentEnvelope`）+ 串行闸兜底。
 */
fun SubagentPreset.toRunProfile(): Assistant = Assistant(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    chatModelId = modelId,
    enableWebSearch = enableWebSearch,
    workspaceId = workspaceId,
    enableMemory = false,
    useGlobalMemory = false,
    localTools = emptyList(),
    enableRecentChatsReference = false,
    enabledSkills = emptySet(),
    modeInjectionIds = emptySet(),
    promptPresetIds = emptySet(),
    lorebookIds = emptySet(),
    regexes = emptyList(),
    presetMessages = emptyList(),
    quickMessageIds = emptySet(),
    customHeaders = emptyList(),
    customBodies = emptyList(),
    fallbackChatModelId = null,
    enableTimeReminder = false,
)

/**
 * 能力标签：给派发工具的清单用，让父模型知道「这个子代理能干什么」。
 *
 * 只反映**能力开关**（联网搜索 / 工作区），不列具体工具名——列工具名要在提示词组装期做一次
 * 工具装配（`Tool.systemPrompt` 是同步 lambda，装配是 suspend），而清单只需要「能不能搜索、
 * 能不能读文件」这个粒度就够模型挑了。
 */
fun SubagentPreset.capabilityLabels(): List<String> = buildList {
    if (enableWebSearch) add("web search")
    if (workspaceId != null) add("workspace files")
}

/**
 * 调度名是否可用：非空、不超长、且不与**其他**预设重名（大小写不敏感）。
 *
 * @param excludeIndex 编辑既有预设时传入其下标（允许保留原名）；新建传 null。
 */
fun isSubagentNameAvailable(
    name: String,
    presets: List<SubagentPreset>,
    excludeIndex: Int? = null,
): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > SUBAGENT_NAME_MAX_CHARS) return false
    return presets.withIndex().none { (index, preset) ->
        index != excludeIndex && preset.name.trim().equals(trimmed, ignoreCase = true)
    }
}

/** 写入设置前钳制越界值（超时秒数）。 */
fun SubagentPreset.clamped(): SubagentPreset = copy(
    timeoutSeconds = timeoutSeconds.coerceIn(SUBAGENT_TIMEOUT_SECONDS_RANGE),
    name = name.trim(),
)
