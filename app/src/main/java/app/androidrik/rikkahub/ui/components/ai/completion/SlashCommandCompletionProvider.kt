package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.ListChecks
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Network
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.RotateCw
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Shrink
import com.composables.icons.lucide.X

internal data class SlashCommandAvailability(
    val hasMessages: Boolean = false,
    val canContinue: Boolean = false,
    val isGenerating: Boolean = false,
    val isCompressing: Boolean = false,
    val hasGoal: Boolean = false,
)

internal class SlashCommandCompletionProvider(
    private val availability: SlashCommandAvailability,
) : ChatCompletionProvider {
    override val id: String = "slash_command"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection || context.cursor !in 1..context.text.length) return null
        // 内置客户端命令只在整条输入开头触发；若已被窗口截断（textOffset > 0）说明光标远离开头。
        if (context.textOffset > 0) return null

        val text = context.text
        val cursor = context.cursor
        val firstNonWs = (0 until cursor).firstOrNull { !text[it].isWhitespace() } ?: return null
        if (text[firstNonWs] != '/') return null

        val afterSlash = text.substring(firstNonWs + 1, cursor)
        if (afterSlash.startsWith("skill:", ignoreCase = true)) return null

        val range = context.documentRange(TextRange(firstNonWs, cursor))
        val wsIndex = afterSlash.indexOfFirst { it.isWhitespace() }

        val items = if (wsIndex < 0) {
            if (afterSlash.contains('/')) return null
            buildHeadCandidates(afterSlash)
        } else {
            val head = afterSlash.substring(0, wsIndex)
            val arg = afterSlash.substring(wsIndex + 1).trim()
            buildArgumentCandidates(head, arg)
        }

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = range,
            items = items,
        )
    }

    private fun buildHeadCandidates(query: String): List<ChatCompletionItem> {
        return BuiltinSlashCommand.entries.mapNotNull { cmd ->
            if (!cmd.isVisibleFor(availability)) return@mapNotNull null
            val matchScore = cmd.matchScore(query) ?: return@mapNotNull null
            val boost = if (cmd == BuiltinSlashCommand.STOP && (availability.isGenerating || availability.isCompressing)) {
                100
            } else {
                0
            }
            val effect = if (cmd.requiresArgument) {
                ChatCompletionEffect.InsertText("/${cmd.commandName} ")
            } else {
                ChatCompletionEffect.ExecuteCommand(SlashCommandCall(cmd))
            }
            ChatCompletionItem(
                label = "/${cmd.commandName}",
                insertText = if (cmd.requiresArgument) "/${cmd.commandName} " else "",
                detail = cmd.description,
                icon = cmd.icon(),
                sortScore = matchScore + boost,
                key = "$id:${cmd.commandName}",
                group = ChatCompletionGroup.COMMAND,
                badge = cmd.argumentHint,
                effect = effect,
            )
        }
    }

    private fun buildArgumentCandidates(head: String, arg: String): List<ChatCompletionItem> {
        val cmd = BuiltinSlashCommand.fromToken(head) ?: return emptyList()
        if (!cmd.acceptsArgument) return emptyList()
        return when (cmd) {
            BuiltinSlashCommand.COMPRESS -> listOf(
                ChatCompletionItem(
                    label = if (arg.isBlank()) "/compress" else "/compress $arg",
                    insertText = "",
                    detail = if (arg.isBlank()) {
                        "打开上下文压缩参数框（继续输入要求则直接后台压缩）"
                    } else {
                        "按当前默认配置直接后台压缩"
                    },
                    icon = cmd.icon(),
                    sortScore = 100,
                    key = "$id:compress:exec",
                    group = ChatCompletionGroup.COMMAND,
                    badge = if (arg.isBlank()) "弹窗" else "直压",
                    effect = ChatCompletionEffect.ExecuteCommand(SlashCommandCall(cmd, arg)),
                ),
            )

            BuiltinSlashCommand.GOAL -> buildList {
                if (availability.hasGoal && (arg.isBlank() || "clear".startsWith(arg, ignoreCase = true))) {
                    add(
                        ChatCompletionItem(
                            label = "/goal clear",
                            insertText = "",
                            detail = "清除当前会话的长期目标",
                            icon = cmd.icon(),
                            sortScore = 110,
                            key = "$id:goal:clear",
                            group = ChatCompletionGroup.COMMAND,
                            badge = "清除",
                            effect = ChatCompletionEffect.ExecuteCommand(SlashCommandCall(cmd, "clear")),
                        ),
                    )
                }
                if (arg.isNotBlank() && !arg.equals("clear", ignoreCase = true)) {
                    add(
                        ChatCompletionItem(
                            label = "/goal $arg",
                            insertText = "",
                            detail = "设为当前会话长期目标并持久化",
                            icon = cmd.icon(),
                            sortScore = 100,
                            key = "$id:goal:set",
                            group = ChatCompletionGroup.COMMAND,
                            badge = "保存",
                            effect = ChatCompletionEffect.ExecuteCommand(SlashCommandCall(cmd, arg)),
                        ),
                    )
                }
            }

            BuiltinSlashCommand.NOTE -> if (arg.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ChatCompletionItem(
                        label = "/note $arg",
                        insertText = "",
                        detail = "仅追加到对话历史，不触发模型回复",
                        icon = cmd.icon(),
                        sortScore = 100,
                        key = "$id:note:send",
                        group = ChatCompletionGroup.COMMAND,
                        badge = "不回复",
                        effect = ChatCompletionEffect.ExecuteCommand(SlashCommandCall(cmd, arg)),
                    ),
                )
            }

            else -> emptyList()
        }
    }
}

private fun BuiltinSlashCommand.isVisibleFor(availability: SlashCommandAvailability): Boolean = when (this) {
    BuiltinSlashCommand.RETRY,
    BuiltinSlashCommand.FORK -> availability.hasMessages
    BuiltinSlashCommand.CONTINUE -> availability.canContinue
    BuiltinSlashCommand.STOP -> availability.isGenerating || availability.isCompressing
    else -> true
}

private fun BuiltinSlashCommand.matchScore(query: String): Int? {
    if (query.isEmpty()) return 10 - ordinal.coerceAtMost(9)
    val q = query.lowercase()
    return when {
        commandName == q -> 50
        commandName.startsWith(q) -> 40
        commandName.contains(q) -> 30
        keywords.any { it.startsWith(q, ignoreCase = true) } -> 25
        keywords.any { it.contains(q, ignoreCase = true) } -> 20
        else -> null
    }
}

private fun BuiltinSlashCommand.icon(): ImageVector = when (this) {
    BuiltinSlashCommand.NEW -> Lucide.Plus
    BuiltinSlashCommand.COMPRESS -> Lucide.Shrink
    BuiltinSlashCommand.RETRY -> Lucide.RefreshCw
    BuiltinSlashCommand.CONTINUE -> Lucide.RotateCw
    BuiltinSlashCommand.FORK -> Lucide.Network
    BuiltinSlashCommand.STOP -> Lucide.X
    BuiltinSlashCommand.NOTE -> Lucide.FilePen
    BuiltinSlashCommand.TREE -> Lucide.ListTree
    BuiltinSlashCommand.EXPORT -> Lucide.Share2
    BuiltinSlashCommand.INSPECT -> Lucide.Eye
    BuiltinSlashCommand.GOAL -> Lucide.ListChecks
}
