package app.androidrik.ai.provider

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * 提供商的额度状态，由 `Provider.getQuota` 返回（对齐上游 #2186）。
 *
 * 按**语义**而不是按提供商分类型，界面按类型分别展示；新的提供商优先复用已有类型。
 * P2 先落地类型体系，`getQuota` 接线与余额链路替换在后续增量完成。
 */
sealed interface ProviderQuota {
    /** 余额：用户自定义查询（[QuotaQuery]）的结果、以及 API Key 类提供商按额度接口取到的值都归到这里 */
    data class Balance(val value: String) : ProviderQuota

    /** 订阅制的用量限制：按时间窗口统计已用比例 */
    data class UsageLimits(
        val windows: List<UsageWindow>,
        /** 已经触发限额，暂时不能继续使用 */
        val limitReached: Boolean,
    ) : ProviderQuota
}

data class UsageWindow(
    /** 0~100 */
    val usedPercent: Double,
    /** 窗口长度（分钟），未知时为 null */
    val windowMinutes: Int?,
    val resetsAt: Instant?,
)

/**
 * 用户自定义的额度查询，只有 API Key 类提供商有。
 *
 * 账号类的查询写死在各自的 Provider 里、不让用户配置，账号凭据就不会被发往自定义地址。
 *
 * [url] 和 [headers] 的值可以引用 [VARIABLES] 里的变量，写作 `{{apiKey}}`；
 * 查询结果按 [resultPath] 从响应 JSON 里取值。
 *
 * **[sources] / [expression]** 是本仓对上游的扩展（对齐上游 #2186 的同时保住本仓
 * `BalanceOption` 的多接口能力）：`sources` 非空时按多接口模式逐个抓取、把各自
 * [QuotaSource.resultPath] 的值以 [QuotaSource.name] 为变量名喂给 [expression]，
 * 用 `evaluateJsonExpr` 求值；为空时走上游的单接口模式（[url] + [resultPath]）。
 * 旧 `BalanceOption` 迁移时 `sources`/`expression` 原样搬入，单接口配置映射为 `url`/`resultPath`。
 */
@Serializable
data class QuotaQuery(
    val enabled: Boolean = false,
    val url: String = "{{baseUrl}}/credits",
    /** 鉴权头也在这里配置，默认值按提供商类型给 */
    val headers: List<CustomHeader> = listOf(CustomHeader("Authorization", "Bearer {{apiKey}}")),
    val resultPath: String = "data.total_usage",
    /** 多接口模式：各数据源（本仓扩展，默认空 = 单接口模式） */
    val sources: List<QuotaSource> = emptyList(),
    /** 多接口模式：最终表达式，如 `quota - usage`（本仓扩展，默认空） */
    val expression: String = "",
) {
    companion object {
        const val VARIABLE_BASE_URL = "baseUrl"

        /** 填了多个 key 时取其中一个，和对话请求一样轮换 */
        const val VARIABLE_API_KEY = "apiKey"

        val VARIABLES = listOf(VARIABLE_BASE_URL, VARIABLE_API_KEY)
    }
}

/**
 * 多接口模式下的单个数据源（本仓扩展，字段与旧 `BalanceSource` 一一对应以支持无损迁移）。
 */
@Serializable
data class QuotaSource(
    /** 变量名，供 [QuotaQuery.expression] 引用（如 `quota`、`usage`） */
    val name: String,
    /** API 路径（如 `/api/user/self`），拼接在提供商 `baseUrl` 之后 */
    val apiPath: String,
    /** JSON 取值路径（如 `quota`） */
    val resultPath: String,
)

private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

/**
 * 替换额度查询模板里的 `{{name}}`，引用了不存在的变量时报错。
 *
 * 与 [QuotaQuery] 的变量约定配套；未知变量必须显式失败，否则会带着字面量 `{{...}}`
 * 去请求一个错误地址，远端返回的 404/空 JSON 很难排查。
 */
fun renderQuotaTemplate(template: String, variable: (String) -> String?): String =
    TEMPLATE_VARIABLE.replace(template) { match ->
        val name = match.groupValues[1]
        variable(name) ?: throw IllegalArgumentException("Unknown variable {{$name}}")
    }
