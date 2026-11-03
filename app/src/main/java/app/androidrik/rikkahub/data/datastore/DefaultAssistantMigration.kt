package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

internal val LEGACY_EXAMPLE_ASSISTANT_ID = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0")

// Published seed prompts, including the fork's cache-friendly version. Exact matching is intentional.
internal val LEGACY_EXAMPLE_PROMPTS: Set<String> = buildSet {
    val base = """
        You are a helpful assistant, called {{char}}, based on model {{model_name}}.

        ## Info
        - Locale: {{locale}}
        - Timezone: {{timezone}}
        - Device Info: {{device_info}}
        - System Version: {{system_version}}
        - User Nickname: {{user}}

        ## Hint
        - If the user does not specify a language, reply in the user's primary language.
    """.trimIndent()
    val markdown = "\n- Remember to use Markdown syntax for formatting, and use latex for mathematical expressions."
    for (date in listOf("", "- Time: {{cur_datetime}}\n", "- Date: {{cur_date}}\n")) {
        val prompt = base.replace("## Info\n", "## Info\n$date")
        add(prompt)
        add(prompt + markdown)
    }
    add("你是{model_name}, 一个人工智能助手，乐意为用户提供准确，有益的帮助。现在时间是{cur_datetime}，用户设备语言为\"{locale}\"，时区为{timezone}，用户正在使用{device_info}，版本{system_version}。如果用户没有明确说明，请使用用户设备语言和用户对话。")
}

internal fun Assistant.isUnmodifiedExample(): Boolean =
    id == LEGACY_EXAMPLE_ASSISTANT_ID &&
        name in setOf("", "示例助手") &&
        systemPrompt in LEGACY_EXAMPLE_PROMPTS &&
        temperature in listOf(null, 0.6f) &&
        this == Assistant(id = id, name = name, systemPrompt = systemPrompt, temperature = temperature)

/** Move references before removing the seed. The callback must be transactional and idempotent. */
internal suspend fun Settings.mergeLegacyDefaultAssistant(moveReferences: suspend () -> Unit): Settings {
    val examples = assistants.filter { it.id == LEGACY_EXAMPLE_ASSISTANT_ID }
    if (examples.isEmpty()) return this
    // Respect explicit deletion of the destination, and never discard any customized duplicate.
    val merge = DEFAULT_ASSISTANT_ID !in removedAssistantIds && examples.all { it.isUnmodifiedExample() }
    if (!merge) {
        return copy(
            assistants = assistants.map {
                if (it.id == LEGACY_EXAMPLE_ASSISTANT_ID && it.name.isEmpty()) it.copy(name = "Example Assistant") else it
            },
            legacyDefaultAssistantMerged = false,
        )
    }
    moveReferences()
    val remaining = assistants.filterNot { it.id == LEGACY_EXAMPLE_ASSISTANT_ID }
    return copy(
        assistants = if (remaining.any { it.id == DEFAULT_ASSISTANT_ID }) remaining else remaining + DEFAULT_ASSISTANTS.single(),
        assistantId = if (assistantId == LEGACY_EXAMPLE_ASSISTANT_ID) DEFAULT_ASSISTANT_ID else assistantId,
        removedAssistantIds = removedAssistantIds + LEGACY_EXAMPLE_ASSISTANT_ID,
        legacyDefaultAssistantMerged = true,
    )
}
