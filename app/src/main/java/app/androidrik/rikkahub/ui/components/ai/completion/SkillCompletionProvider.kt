package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import app.androidrik.rikkahub.data.files.SkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SkillCompletionProvider(
    private val skillManager: SkillManager,
    private val enabledSkills: Set<String>,
) : ChatCompletionProvider {
    override val id = "skill"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = findSkillCommand(context) ?: return null
        val query = context.text.substring(range.start - context.textOffset + 1, context.cursor)
        val items = withContext(Dispatchers.IO) {
            skillManager.listSkills().mapNotNull { skill ->
                if (skill.name !in enabledSkills || !skill.name.contains(query, ignoreCase = true)) {
                    return@mapNotNull null
                }
                ChatCompletionItem(
                    label = "/${skill.name}",
                    insertText = "",
                    detail = skill.description,
                    sortScore = when {
                        skill.name.equals(query, ignoreCase = true) -> 2
                        skill.name.startsWith(query, ignoreCase = true) -> 1
                        else -> 0
                    },
                    skillName = skill.name,
                )
            }
        }
        return ChatCompletionList(id, range, items)
    }
}

internal fun findSkillCommand(context: ChatCompletionContext): TextRange? {
    if (context.hasSelection || context.cursor !in 1..context.text.length) return null
    val text = context.text
    val cursor = context.cursor
    var start = cursor - 1
    while (start >= 0 && text[start].isSkillQueryCharacter()) start--
    if (start < 0 || text[start] != '/') return null
    if (start == 0 && context.textOffset > 0) return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    return context.documentRange(TextRange(start, cursor))
}

internal fun Char.isSkillQueryCharacter(): Boolean = isLetterOrDigit() || this in "-_."
