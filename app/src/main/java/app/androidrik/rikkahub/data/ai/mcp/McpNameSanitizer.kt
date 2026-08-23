package app.androidrik.rikkahub.data.ai.mcp

/**
 * MCP 名称净化。
 *
 * 背景：MCP 服务器名/工具名允许用户自由命名（含中文、emoji 等非 ASCII 字符），
 * 但发给模型的 function name 受 provider 限制——OpenAI / Claude 均要求匹配
 * `^[a-zA-Z0-9_-]{1,64}$`。因此显示层保留原名，只在拼接 function name 时净化。
 *
 * 净化策略：
 * - 合法字符（字母/数字/`-`/`_`）原样保留；
 * - 其余字符（中文、空格、标点、emoji…）整段折叠为单个 `_`；
 * - 若净化后为空（如纯中文名被全部替换），回退为码点十六进制串，保证：
 *   ①非空 ②同名同结果（稳定）③不同名不同结果（避免多服务器撞名）。
 *
 * 注意：`mcp__{server}__{tool}` 前缀在代码中**不做反向解析**，工具执行依赖闭包捕获的
 * serverId / 原始 toolName，因此净化不会破坏调用链。
 */
object McpNameSanitizer {

    /** function name 的整体长度上限（OpenAI / Claude 一致）。 */
    const val MAX_FUNCTION_NAME_LENGTH = 64

    private const val FALLBACK_PREFIX = "x"

    /**
     * 将任意名称净化为仅含 `[a-zA-Z0-9_-]` 的片段。
     *
     * 空输入返回空串（由调用方决定是否补位）；非空输入保证返回非空。
     */
    fun sanitizeSegment(name: String): String {
        if (name.isEmpty()) return ""

        val sb = StringBuilder(name.length)
        var pendingSeparator = false
        for (ch in name) {
            if (ch.isAsciiNameChar()) {
                if (pendingSeparator && sb.isNotEmpty()) sb.append('_')
                pendingSeparator = false
                sb.append(ch)
            } else {
                // 连续非法字符折叠为一个 '_'，且不产生前导下划线
                pendingSeparator = true
            }
        }

        val sanitized = sb.toString()
        // 全部字符都非法（例如纯中文名）→ 用码点十六进制保证唯一且稳定
        return sanitized.ifEmpty { name.toStableHex() }
    }

    /**
     * 组装发给模型的 function name：`mcp__{server}__{tool}`。
     *
     * 两段分别净化后拼接，超长时截断 —— 优先保留工具名（更能体现语义），
     * 服务器名段被压缩，但仍保留可辨识前缀。
     */
    fun buildFunctionName(serverName: String, toolName: String): String {
        val server = sanitizeSegment(serverName).ifEmpty { "server" }
        val tool = sanitizeSegment(toolName).ifEmpty { "tool" }

        val full = "mcp__${server}__$tool"
        if (full.length <= MAX_FUNCTION_NAME_LENGTH) return full

        // 超长：固定开销 = "mcp__" + "__" = 7
        val budget = MAX_FUNCTION_NAME_LENGTH - 7
        val toolPart = tool.take(budget - 1).ifEmpty { "t" }
        val serverBudget = (budget - toolPart.length).coerceAtLeast(1)
        val serverPart = server.take(serverBudget)
        return "mcp__${serverPart}__$toolPart".take(MAX_FUNCTION_NAME_LENGTH)
    }

    /**
     * 在 [buildFunctionName] 基础上保证唯一：若名字已被占用，追加 `_2`、`_3`… 直到不冲突。
     *
     * 不同原名净化后可能相同（如「工具 A」与「工具-A」），而 provider 要求同一请求内
     * function name 不重复，否则模型无法区分、甚至请求被拒。
     *
     * 调用方需复用同一个 [used] 集合；本方法会把最终名字写入其中。
     */
    fun buildUniqueFunctionName(
        serverName: String,
        toolName: String,
        used: MutableSet<String>,
    ): String {
        val base = buildFunctionName(serverName, toolName)
        if (used.add(base)) return base

        var suffixIndex = 2
        while (true) {
            val suffix = "_$suffixIndex"
            // 追加后缀后仍需满足长度上限，必要时截断基名尾部
            val trimmedBase = base.take(MAX_FUNCTION_NAME_LENGTH - suffix.length)
            val candidate = trimmedBase + suffix
            if (used.add(candidate)) return candidate
            suffixIndex++
        }
    }

    private fun Char.isAsciiNameChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'

    /** 纯非法字符名的稳定回退表示，如 "工具" → "x5de55177". */
    private fun String.toStableHex(): String = buildString {
        append(FALLBACK_PREFIX)
        this@toStableHex.forEach { append(it.code.toString(16)) }
    }.take(MAX_FUNCTION_NAME_LENGTH)
}
