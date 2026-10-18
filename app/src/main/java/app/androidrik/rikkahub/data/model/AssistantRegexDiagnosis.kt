package app.androidrik.rikkahub.data.model

/**
 * 正则替换规则的**编辑期体检**（纯函数，只出提示，不改运行时行为）。
 *
 * 背景（实测链路，不是猜测）：助手的「正则替换」有两条互不相同的落地路径——
 *
 * - `visualOnly = true`：只在**显示层**生效（`ChatMessage` / `ChatMessageReasoning` 用
 *   `visual = true` 渲染时现算），每次渲染都从**落库原文**重算 ⇒ 无论渲染多少次都不累积。
 * - `visualOnly = false`：`RegexOutputTransformer.visualTransform` 算出来的结果会
 *   **回填进消息本身并落库**；而 `GenerationHandler` 在每一步工具循环结束时
 *   `messages = messages.visualTransforms(...)`，下一步又拿这份**已被替换过**的消息去请求
 *   ⇒ 同一份文本会被替换多次。若规则的「替换结果」自己又能被这条正则命中，
 *   每多一步就多长一截，而且这个增长会随会话历史一起被写进数据库。
 *
 * 因此这里只报「**会累积**」的那一类，不报 `visualOnly = true` 的同类规则——后者是安全的，
 * 报它只会让用户去改一个本来就没问题的配置。判据与守卫见 `AssistantRegexDiagnosisTest`。
 */
enum class RegexDiagnosisIssue {
    /**
     * 正则本身编译不过。`String.replaceRegexes` 里 `compileRegexCached` 失败会**静默跳过**
     * 这条规则（`Assistant.kt`）⇒ 用户以为配了、实际什么都没发生。这是最常见的一种
     * 「配了等于没配」，而且只靠看正则文本看不出来。
     */
    INVALID_PATTERN,

    /**
     * 没勾任何作用域（`affectingScope` 为空）。`replaceRegexes` 的过滤条件要求 scope 命中，
     * 空集永远不命中 ⇒ 规则永远不会被应用。新建规则时的默认状态正好是这个。
     */
    NO_SCOPE,

    /**
     * 替换结果自身还能被这条正则命中 ⇒ 每多应用一次就多长一截（见类注释的累积链路）。
     *
     * 只看 `visualOnly = false` 且作用域含 `ASSISTANT` 的规则：`USER` 侧的非视觉规则在
     * `ChatService.preprocessUserInputParts` 里**只在用户提交那一刻应用一次**，文本落库后
     * 不再重算，不存在累积。
     */
    AMPLIFIES_OUTPUT,
}

/**
 * 体检一条规则，返回命中的问题（空集 = 没发现问题）。
 *
 * 空正则直接返回空集：新建的空白规则不该在用户还没动手时就报错。
 *
 * **判据是「双重应用」而不是「替换串里能否匹配」**：把替换串本身当作样例文本，再应用一次
 * 这条规则，结果变了才算会累积。这样 `^` → 空串（真正幂等）不会被误报，而
 * `^(?=\S)` → `【AI】`（样本）会被报出来。
 *
 * **已知局限**：替换串里带 `$1` 这类分组引用、且分组内容只在真实文本里出现时，单靠替换串
 * 测不出来（`(foo)` → `**$1**` 不会命中 `**$1**`）。宁可漏报也不误报：误报会让用户去
 * 改一个正确的规则。替换串本身非法（引用了不存在的分组）时 `replace` 会抛异常，
 * 这里按「没发现问题」处理——那条规则在运行时本来就会被 `replaceRegexes` 吞掉。
 */
fun diagnoseRegexRule(regex: AssistantRegex): Set<RegexDiagnosisIssue> {
    if (regex.findRegex.isBlank()) return emptySet()
    val issues = mutableSetOf<RegexDiagnosisIssue>()
    val compiled = runCatching { Regex(regex.findRegex) }.getOrNull()
    if (compiled == null) {
        issues += RegexDiagnosisIssue.INVALID_PATTERN
        // 编译不过时下面两项都无法判定：给出最根本的那一条就够，别一次报三条
        return issues
    }
    if (regex.affectingScope.isEmpty()) issues += RegexDiagnosisIssue.NO_SCOPE
    if (!regex.visualOnly &&
        AssistantAffectScope.ASSISTANT in regex.affectingScope &&
        amplifiesOutput(compiled, regex.replaceString)
    ) {
        issues += RegexDiagnosisIssue.AMPLIFIES_OUTPUT
    }
    return issues
}

/** 把 [replaceString] 当样例再应用一次，结果变了就说明「多应用一次会变样」。 */
private fun amplifiesOutput(compiled: Regex, replaceString: String): Boolean =
    runCatching { replaceString.replace(compiled, replaceString) != replaceString }.getOrDefault(false)
