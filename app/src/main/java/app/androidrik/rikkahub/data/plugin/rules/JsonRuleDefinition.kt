package app.androidrik.rikkahub.data.plugin.rules

import app.androidrik.plugin.api.PluginCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON 规则插件定义(第二期核心数据模型,借鉴 BiliPai 的 JSON 规则插件)。
 *
 * 与 BiliPai 的差异(见 `docs/PLUGIN_SYSTEM_DESIGN_DRAFT.md` §1.7):
 * - **没有** `type: feed|danmaku`,只有 [RuleTarget],对应本 fork 的两条转换管线;
 * - **能力不可省略**:由 [target] 推导(输入 → `PROMPT_TRANSFORM`,输出 → `OUTPUT_TRANSFORM`),
 *   安装时必须经用户确认(不能像 BiliPai 那样"是数据就免授权");
 * - 条件树字段用**路径表达式**取值(见 [RuleEngine]),不做 `when` 硬编码白名单,
 *   新增字段只需在宿主侧多传一个键。
 */
@Serializable
data class JsonRuleDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "Unknown",
    val target: RuleTarget = RuleTarget.OUTPUT,
    /**
     * 作用范围:
     * - [RuleScope.LAST](默认):只处理**最新一条**目标角色消息。
     *   这是安全默认值——否则 `append` 类动作会在每次请求里给历史消息
     *   反复追加("尾注"越滚越多);
     * - [RuleScope.ALL]:处理全部目标角色消息(适合纯幂等的 `replace`)。
     */
    val scope: RuleScope = RuleScope.LAST,
    val rules: List<JsonRule> = emptyList(),
) {
    /** 本定义需要的能力:输入管线 / 输出管线二选一。 */
    val requiredCapability: PluginCapability
        get() = when (target) {
            RuleTarget.INPUT -> PluginCapability.PROMPT_TRANSFORM
            RuleTarget.OUTPUT -> PluginCapability.OUTPUT_TRANSFORM
        }

    /** 安装前校验;返回人类可读的错误列表(空 = 通过)。 */
    fun validate(): List<String> = buildList {
        if (!ID_REGEX.matches(id)) {
            add("id 必须匹配 ${ID_REGEX.pattern}(小写字母/数字/下划线/点/中划线,1-64 字符)")
        }
        if (name.isBlank()) add("name 不能为空")
        if (rules.isEmpty()) add("rules 不能为空")
        rules.forEachIndexed { index, rule ->
            rule.condition.validate()?.let { add("rules[$index].condition: $it") }
            rule.action.validate()?.let { add("rules[$index].action: $it") }
        }
    }

    companion object {
        val ID_REGEX = Regex("^[a-z0-9_.-]{1,64}$")
        /** 保留前缀:内置插件 / 宿主命名空间,规则插件不得占用。 */
        const val RESERVED_PREFIX = "builtin."
    }
}

@Serializable
enum class RuleTarget {
    /** 输入侧:作用于用户消息,发送给模型前执行。 */
    @SerialName("input")
    INPUT,

    /** 输出侧:作用于助手消息,生成结束时执行。 */
    @SerialName("output")
    OUTPUT,
}

@Serializable
enum class RuleScope {
    /** 只处理最新一条目标角色消息(默认)。 */
    @SerialName("last")
    LAST,

    /** 处理全部目标角色消息。 */
    @SerialName("all")
    ALL,
}

@Serializable
data class JsonRule(
    val condition: RuleCondition,
    val action: RuleAction,
)

/**
 * 条件节点。三种形态(与 BiliPai 的 JSON 形状兼容):
 * - 叶子:`{ "field": "text", "op": "contains", "value": "x" }`
 * - 与:`{ "and": [ ... ] }`
 * - 或:`{ "or": [ ... ] }`
 */
@Serializable
data class RuleCondition(
    val field: String? = null,
    val op: RuleOp? = null,
    val value: JsonElement? = null,
    val and: List<RuleCondition> = emptyList(),
    val or: List<RuleCondition> = emptyList(),
) {
    /** 返回 null 表示合法,否则返回错误说明。 */
    fun validate(): String? = when {
        and.isNotEmpty() && or.isNotEmpty() -> "and 与 or 不能同时出现"
        and.isNotEmpty() -> and.firstNotNullOfOrNull { it.validate() }
        or.isNotEmpty() -> or.firstNotNullOfOrNull { it.validate() }
        field.isNullOrBlank() -> "叶子条件缺少 field"
        op == null -> "叶子条件缺少 op"
        op == RuleOp.IN && value == null -> "op=in 需要 value 数组"
        else -> null
    }
}

@Serializable
enum class RuleOp {
    @SerialName("eq")
    EQ,

    @SerialName("ne")
    NE,

    @SerialName("lt")
    LT,

    @SerialName("le")
    LE,

    @SerialName("gt")
    GT,

    @SerialName("ge")
    GE,

    @SerialName("contains")
    CONTAINS,

    @SerialName("startsWith")
    STARTS_WITH,

    @SerialName("endsWith")
    ENDS_WITH,

    @SerialName("regex")
    REGEX,

    @SerialName("in")
    IN,
}

/**
 * 动作。AI 对话场景下 `highlight` 无意义,BiliPai 也**没有** `replace`;
 * 而本 fork 的输出处理最需要正则替换,因此这里提供 [RuleActionType.REPLACE]。
 */
@Serializable
data class RuleAction(
    val type: RuleActionType = RuleActionType.REPLACE,
    /** REPLACE 的正则(Java 语法,按多行模式匹配)。 */
    val pattern: String = "",
    /** REPLACE 的替换串(支持 `$1` 分组引用)。 */
    val replacement: String = "",
    /** APPEND / PREPEND 追加的文本。 */
    val text: String = "",
) {
    fun validate(): String? = when (type) {
        RuleActionType.REPLACE -> when {
            pattern.isBlank() -> "replace 动作缺少 pattern"
            else -> runCatching { Regex(pattern) }.exceptionOrNull()?.let { "pattern 不是合法正则: ${it.message}" }
        }

        RuleActionType.APPEND, RuleActionType.PREPEND -> if (text.isEmpty()) "append/prepend 动作缺少 text" else null
    }
}

@Serializable
enum class RuleActionType {
    @SerialName("replace")
    REPLACE,

    @SerialName("append")
    APPEND,

    @SerialName("prepend")
    PREPEND,
}
