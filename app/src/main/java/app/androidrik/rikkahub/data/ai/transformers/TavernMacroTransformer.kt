package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/**
 * SillyTavern 动态宏（MineChat 借鉴，2026-09-28）。
 *
 * **本仓缺口**：[PlaceholderTransformer] 只做静态占位符（{{char}}/{{user}}/{{time}} 等
 * 固定 key 的查表替换）；ST 生态的**动态宏**——随机/掷骰/变量——没有对应物。含这些宏的
 * ST 卡片导入后会以字面量出现在 prompt 里。**输入侧正则不在此列**：`AssistantAffectScope.USER`
 * 已存在且在 `ChatService` 用户输入预处理（:607）消费——此前的评估有误，特此更正。
 *
 * **支持的宏**（大小写不敏感，语义对齐 ST / MineChat `core/tavern/macro`）：
 * - `{{random:a,b,c}}` / `{{random::a::b}}`——随机选一（分隔符 `,` 或 `::` 可混用）；
 *   `{{random:1..5}}` 整数区间（含两端）
 * - `{{roll:d6}}` / `{{roll:2d20+3}}`——掷骰，纯数字视为 `1dN`，count≤100、面数≤1000，返回总数
 * - `{{setvar::name::value}}`——设变量，输出空
 * - `{{getvar::name}}`——取变量，未设输出空
 * - `{{addvar::name::value}}`——变量累加（两侧均可数值则算术加，否则字符串拼接），输出空
 *
 * **变量作用域：按次装配**（一次 [transform] 调用一个 map，跨消息顺序传递——
 * setvar 之后的 getvar/addvar 在同一次组装里可见）。ST 本体是**按会话持久**；
 * 不持久是有意保守：跨请求持久变量会悄悄改变后续所有轮次的行为（本仓「静默副作用」
 * 类教训），先按无副作用语义落地，确有跨轮需求再加会话级存储。
 *
 * **解析顺序**：innermost-first——每轮找**不含嵌套 `{{`** 的第一个动态宏解析并替换后重扫
 * （`{{setvar::x::{{roll:d6}}}}` 先解内层 roll 再被 setvar 消费），迭代上限防自造宏死循环。
 * 非动态的 `{{...}}`（如 {{char}}）原样保留，交由 [PlaceholderTransformer] 处理。
 */
object TavernMacroSemantics {

    /** 迭代上限：嵌套/自造宏（如 setvar 值里再写入宏文本）的安全阀。 */
    internal const val MAX_ITERATIONS = 200

    internal val DYNAMIC_MACRO = Regex(
        // `::?` 同时吃掉 `:` 与 `::` 两种分隔（否则第二个冒号会漏进载荷，键名变成 ":x"）
        pattern = """\{\{\s*(setvar|getvar|addvar|random|roll)\s*::?\s*([^{}]*)\}\}""",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    fun resolve(text: String, vars: MutableMap<String, String>): String {
        var result = text
        repeat(MAX_ITERATIONS) {
            val match = DYNAMIC_MACRO.findAll(result).firstOrNull { entry ->
                // innermost-first：跳过载荷里还有嵌套 {{ 的（先解里层）
                "{{" !in entry.groupValues[2]
            } ?: return result
            val replacement = evaluate(match.groupValues[1].lowercase(), match.groupValues[2], vars)
                ?: match.value // 无效宏保留字面量（静默消失会让「哪里写错了」不可见）
            result = result.replaceRange(match.range, replacement)
        }
        // 迭代耗尽仍有动态宏（自造宏自喂）→ 保留字面量，不再尝试
        return result
    }

    /**
     * @return 替换文本；null = 宏本身无效（公式非法/选项为空）——**保留字面量**，
     * 让「哪里写错了」在 prompt 里可见而非静默消失。
     */
    private fun evaluate(name: String, rawPayload: String, vars: MutableMap<String, String>): String? {
        // setvar/addvar 的载荷用 :: 分隔（值本身可含逗号）；random 的用 :: 或 ,
        return when (name) {
            "setvar" -> {
                val (key, value) = splitNameValue(rawPayload) ?: return null
                vars[key] = value
                ""
            }

            "addvar" -> {
                val (key, value) = splitNameValue(rawPayload) ?: return null
                val current = vars[key] ?: ""
                vars[key] = addValues(current, value)
                ""
            }

            "getvar" -> vars[rawPayload.trim()] ?: ""

            "random" -> randomChoice(rawPayload)

            "roll" -> roll(rawPayload)

            else -> null
        }
    }

    /** `name::value` 拆分（值可含逗号；缺 name 或 value 视为无效宏，原样保留由外层返回空）。 */
    private fun splitNameValue(payload: String): Pair<String, String>? {
        val parts = payload.split("::")
        if (parts.size < 2) return null
        val key = parts[0].trim()
        if (key.isEmpty()) return null
        return key to parts.drop(1).joinToString("::").trim()
    }

    private fun addValues(current: String, value: String): String {
        val a = current.toDoubleOrNull()
        val b = value.toDoubleOrNull()
        // 两侧均可数值 → 算术加（整数结果不带小数点）；否则字符串拼接
        return if (a != null && b != null) {
            val sum = a + b
            if (sum == sum.toLong().toDouble()) sum.toLong().toString() else sum.toString()
        } else {
            current + value
        }
    }

    private fun randomChoice(payload: String): String? {
        // 载荷含 `::` 时它是唯一的分隔符（允许选项本身带逗号）；否则按 `,` 分隔
        val options = (if ("::" in payload) payload.split("::") else payload.split(","))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (options.isEmpty()) return null

        // 单参数 `a..b` = 整数区间（含两端）
        if (options.size == 1) {
            val range = options[0].split("..")
            if (range.size == 2) {
                val low = range[0].trim().toIntOrNull()
                val high = range[1].trim().toIntOrNull()
                if (low != null && high != null && low <= high) {
                    return (low..high).random().toString()
                }
            }
        }
        return options.random()
    }

    private fun roll(payload: String): String? {
        var formula = payload.trim()
        if (formula.isEmpty()) return null
        if (formula.all { it.isDigit() }) formula = "1d$formula"
        if (formula[0] in "dD") formula = "1$formula" // 裸 `d6` = `1d6`（ST 惯用写法）
        val parsed = Regex("^(\\d+)[dD](\\d+)([+-]\\d+)?$").matchEntire(formula) ?: return null
        val count = parsed.groupValues[1].toIntOrNull()?.coerceIn(1, 100) ?: return null
        val sides = parsed.groupValues[2].toIntOrNull()?.coerceIn(1, 1000) ?: return null
        val modifier = parsed.groupValues[3].toIntOrNull() ?: 0
        return ((1..count).sumOf { (1..sides).random() } + modifier).toString()
    }
}

/**
 * 把动态宏解析应用到一次装配的全部消息（Text part；顺序遍历 ⇒ 变量跨消息可见）。
 * **纯函数**（vars 由调用方持有），可直接 JVM 单测。
 */
internal fun resolveTavernMacrosInMessages(
    messages: List<UIMessage>,
    vars: MutableMap<String, String>,
): List<UIMessage> = messages.map { message ->
    message.copy(
        parts = message.parts.map { part ->
            if (part is UIMessagePart.Text) {
                part.copy(text = TavernMacroSemantics.resolve(part.text, vars))
            } else {
                part
            }
        }
    )
}

/**
 * 聊天链接入点：须排在 [PlaceholderTransformer] **之前**（动态宏先解，静态占位符后解——
 * setvar 的值里若含 {{char}} 也还能被后者处理）；且在 PromptInjectionTransformer 之后
 * （注入内容里的宏同样要被解析）。输入侧正则（USER scope）在 ChatService 用户输入
 * 预处理处已存在（先于本变换器发生——输入文本在发送前已被正则处理过）。
 */
object TavernMacroTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = resolveTavernMacrosInMessages(messages, mutableMapOf())
}
