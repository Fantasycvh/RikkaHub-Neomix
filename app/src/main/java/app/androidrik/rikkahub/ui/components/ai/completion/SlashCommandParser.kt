package app.androidrik.rikkahub.ui.components.ai.completion

enum class BuiltinSlashCommand(
    val commandName: String,
    val description: String,
    val argumentHint: String? = null,
    val requiresArgument: Boolean = false,
    val acceptsArgument: Boolean = false,
    val keywords: List<String> = emptyList(),
) {
    NEW(
        commandName = "new",
        description = "新建对话",
        keywords = listOf("chat", "新建", "新对话"),
    ),
    COMPRESS(
        commandName = "compress",
        description = "压缩上下文（带要求发送则按默认配置直接后台压缩）",
        argumentHint = "[要求]",
        acceptsArgument = true,
        keywords = listOf("compact", "summary", "压缩", "总结"),
    ),
    RETRY(
        commandName = "retry",
        description = "重新生成当前分支末条消息",
        keywords = listOf("regenerate", "重试", "重生成"),
    ),
    CONTINUE(
        commandName = "continue",
        description = "继续生成被截断的末条助手回复",
        keywords = listOf("resume", "续写", "继续"),
    ),
    FORK(
        commandName = "fork",
        description = "从末条消息分叉为新对话",
        keywords = listOf("branch", "分叉", "分支"),
    ),
    STOP(
        commandName = "stop",
        description = "停止当前生成或取消后台压缩",
        keywords = listOf("cancel", "abort", "停止", "取消"),
    ),
    NOTE(
        commandName = "note",
        description = "仅追加消息到历史，不触发模型回复",
        argumentHint = "<内容>",
        requiresArgument = true,
        acceptsArgument = true,
        keywords = listOf("memo", "silent", "记录", "备忘", "不回复"),
    ),
    TREE(
        commandName = "tree",
        description = "打开会话分支树视图",
        keywords = listOf("branches", "会话树", "分支树"),
    ),
    EXPORT(
        commandName = "export",
        description = "打开对话导出与分享面板",
        keywords = listOf("share", "markdown", "导出", "分享"),
    ),
    INSPECT(
        commandName = "inspect",
        description = "打开本次请求的提示词拼装预览",
        keywords = listOf("prompt", "preview", "提示词", "预览", "检视"),
    ),
    GOAL(
        commandName = "goal",
        description = "设置或清除当前会话的长期目标",
        argumentHint = "<目标|clear>",
        requiresArgument = true,
        acceptsArgument = true,
        keywords = listOf("target", "目标", "长期目标"),
    );

    companion object {
        private val byName: Map<String, BuiltinSlashCommand> =
            entries.associateBy { it.commandName }

        fun fromToken(name: String): BuiltinSlashCommand? = byName[name.lowercase()]
    }
}

data class SlashCommandCall(
    val command: BuiltinSlashCommand,
    val argument: String = "",
)

internal sealed interface SendSlashParseResult {
    data object Plain : SendSlashParseResult
    data class ClientCommand(val call: SlashCommandCall) : SendSlashParseResult
    data class SkillChain(val skillNames: List<String>, val remainingText: String) : SendSlashParseResult
    data class UnknownExplicitSkill(val skillName: String) : SendSlashParseResult
}

internal const val EXPLICIT_SKILL_PREFIX = "/skill:"

/**
 * 发送前统一斜杠命令解析（盲打、外接键盘、语音输入、定时任务共用）。
 *
 * 优先级：
 * 1. `/skill:<name>` 显式命名空间优先按技能解析（用于内置命令与同名技能消歧）；未知时返回 [SendSlashParseResult.UnknownExplicitSkill]；
 * 2. [allowBuiltinCommands] 为 true 时，首词精确匹配内置命令则返回 [SendSlashParseResult.ClientCommand]；
 * 3. 首词为已安装/内置技能名时，继续吸收连续前导技能链（支持 `/a /b 正文` 与 `/a /skill:b 正文`），返回 [SendSlashParseResult.SkillChain]；
 * 4. 其他任何以 `/` 开头的普通文本（如 `/etc/hosts`、`/2026 总结`）原样返回 [SendSlashParseResult.Plain]，绝不误拦。
 */
internal fun parseSendTimeSlashInput(
    rawText: String,
    allowBuiltinCommands: Boolean,
    skillExists: (String) -> Boolean,
): SendSlashParseResult {
    val trimmedStart = rawText.trimStart()
    if (!trimmedStart.startsWith("/")) return SendSlashParseResult.Plain

    val (firstToken, firstRest) = splitLeadingToken(trimmedStart)

    if (firstToken.startsWith(EXPLICIT_SKILL_PREFIX, ignoreCase = true)) {
        val explicitName = firstToken.substring(EXPLICIT_SKILL_PREFIX.length)
        if (!isValidSkillToken(explicitName) || !skillExists(explicitName)) {
            return SendSlashParseResult.UnknownExplicitSkill(explicitName)
        }
        return consumeSkillChain(explicitName, firstRest, skillExists)
    }

    val rawCommandName = firstToken.substring(1)
    if (allowBuiltinCommands) {
        val builtin = BuiltinSlashCommand.fromToken(rawCommandName)
        if (builtin != null) {
            return SendSlashParseResult.ClientCommand(
                SlashCommandCall(command = builtin, argument = firstRest.trim()),
            )
        }
    }

    if (isValidSkillToken(rawCommandName) && skillExists(rawCommandName)) {
        return consumeSkillChain(rawCommandName, firstRest, skillExists)
    }

    return SendSlashParseResult.Plain
}

private fun consumeSkillChain(
    firstSkill: String,
    initialRest: String,
    skillExists: (String) -> Boolean,
): SendSlashParseResult {
    val skills = LinkedHashSet<String>()
    skills.add(firstSkill)
    var current = initialRest

    while (current.startsWith("/")) {
        val (token, rest) = splitLeadingToken(current)
        if (token.startsWith(EXPLICIT_SKILL_PREFIX, ignoreCase = true)) {
            val explicitName = token.substring(EXPLICIT_SKILL_PREFIX.length)
            if (!isValidSkillToken(explicitName) || !skillExists(explicitName)) {
                return SendSlashParseResult.UnknownExplicitSkill(explicitName)
            }
            skills.add(explicitName)
            current = rest
            continue
        }
        val candidate = token.substring(1)
        if (isValidSkillToken(candidate) && skillExists(candidate)) {
            skills.add(candidate)
            current = rest
        } else {
            break
        }
    }

    return SendSlashParseResult.SkillChain(
        skillNames = skills.toList(),
        remainingText = current,
    )
}

private fun splitLeadingToken(text: String): Pair<String, String> {
    val wsIndex = text.indexOfFirst { it.isWhitespace() }
    if (wsIndex < 0) return text to ""
    val token = text.substring(0, wsIndex)
    val afterWs = text.substring(wsIndex)
    val strippedHorizontal = afterWs.dropWhile { it == ' ' || it == '\t' }
    val rest = if (strippedHorizontal.startsWith('\n') || strippedHorizontal.startsWith('\r')) {
        strippedHorizontal.trimStart('\r', '\n')
    } else {
        strippedHorizontal
    }
    return token to rest
}

private fun isValidSkillToken(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isSkillQueryCharacter() }
