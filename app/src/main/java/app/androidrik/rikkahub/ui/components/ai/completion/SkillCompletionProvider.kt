package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import app.androidrik.rikkahub.data.files.SkillManager
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.WandSparkles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SkillCompletionMode {
    /** 主聊天框：点选挂载为本轮 Chip 并抹除输入的 /xxx */
    ATTACH_CHIP,
    /** 定时任务提示词等持久化文本框：点选原地插入 "/skill-name " */
    INSERT_TEXT,
}

internal data class SkillCommandMatch(
    val range: TextRange,
    val query: String,
    val explicitNamespace: Boolean,
)

internal class SkillCompletionProvider(
    private val skillManager: SkillManager,
    private val enabledSkills: Set<String>,
    private val excludedSkills: Set<String> = emptySet(),
    private val mode: SkillCompletionMode = SkillCompletionMode.ATTACH_CHIP,
) : ChatCompletionProvider {
    override val id = "skill"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val match = findSkillCommandMatch(context) ?: return null
        val query = match.query
        val items = withContext(Dispatchers.IO) {
            skillManager.listSkills().mapNotNull { skill ->
                if (skill.name in excludedSkills) return@mapNotNull null
                val nameScore = when {
                    query.isEmpty() -> 1
                    skill.name.equals(query, ignoreCase = true) -> 40
                    skill.name.startsWith(query, ignoreCase = true) -> 30
                    skill.name.contains(query, ignoreCase = true) -> 20
                    skill.description.contains(query, ignoreCase = true) -> 10
                    else -> return@mapNotNull null
                }
                val isEnabled = skill.name in enabledSkills
                val tierBoost = if (isEnabled) 100 else 0
                val insertText = when (mode) {
                    SkillCompletionMode.ATTACH_CHIP -> ""
                    SkillCompletionMode.INSERT_TEXT -> "/${skill.name} "
                }
                val effect = when (mode) {
                    SkillCompletionMode.ATTACH_CHIP -> ChatCompletionEffect.AttachSkill(skill.name)
                    SkillCompletionMode.INSERT_TEXT -> ChatCompletionEffect.InsertText(insertText)
                }
                ChatCompletionItem(
                    label = "/${skill.name}",
                    insertText = insertText,
                    detail = skill.description,
                    icon = Lucide.WandSparkles,
                    sortScore = tierBoost + nameScore,
                    key = "$id:${skill.name}",
                    group = ChatCompletionGroup.SKILL,
                    badge = if (isEnabled) null else "临时",
                    effect = effect,
                )
            }
        }
        return ChatCompletionList(id, match.range, items)
    }
}

internal fun findSkillCommand(context: ChatCompletionContext): TextRange? =
    findSkillCommandMatch(context)?.range

internal fun findSkillCommandMatch(context: ChatCompletionContext): SkillCommandMatch? {
    if (context.hasSelection || context.cursor !in 1..context.text.length) return null
    val text = context.text
    val cursor = context.cursor
    var start = cursor - 1
    while (start >= 0 && text[start].isSkillQueryCharacter()) start--

    val query = text.substring(start + 1, cursor)

    // 显式命名空间：/skill:<query>
    if (start >= EXPLICIT_SKILL_PREFIX.length - 1 && text[start] == ':') {
        val slashPos = start - (EXPLICIT_SKILL_PREFIX.length - 1)
        if (text.substring(slashPos, start + 1).equals(EXPLICIT_SKILL_PREFIX, ignoreCase = true)) {
            if (slashPos == 0 && context.textOffset > 0) return null
            if (slashPos > 0 && !text[slashPos - 1].isWhitespace()) return null
            return SkillCommandMatch(
                range = context.documentRange(TextRange(slashPos, cursor)),
                query = query,
                explicitNamespace = true,
            )
        }
    }

    if (start < 0 || text[start] != '/') return null
    if (start == 0 && context.textOffset > 0) return null
    if (start > 0 && !text[start - 1].isWhitespace()) return null
    return SkillCommandMatch(
        range = context.documentRange(TextRange(start, cursor)),
        query = query,
        explicitNamespace = false,
    )
}

internal fun Char.isSkillQueryCharacter(): Boolean = isLetterOrDigit() || this in "-_."
