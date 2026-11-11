package app.androidrik.rikkahub.data.datastore

import app.androidrik.common.settings.refillBuiltIns
import app.androidrik.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.tts.provider.TTSProviderSetting
import app.androidrik.tts.provider.withEnabled
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.map

// —— 内置默认值与回填（批次 4 拆出）：pref key 与 JSON 结构契约注释逐字保留 ——

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
internal val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }


/**
 * 内置助手回填：缺省（首次安装）或用户删掉了某个内置助手时补回。
 *
 * **跳过墓碑**（[removedIds]）：用户显式删除过的内置助手不再回填——否则读路径每次
 * 都把删掉的加回来，等于删不掉（这正是此前内置助手无法删除的机制）。
 * 与提供商墓碑（`removedBuiltInProviderIds`）同款模式。**纯函数**便于单测。
 */
internal fun refillDefaultAssistants(
    existing: MutableList<Assistant>,
    removedIds: Set<Uuid>,
) {
    val refilled = refillBuiltIns(existing, DEFAULT_ASSISTANTS, removedIds) { it.copy() }
    existing.clear()
    existing.addAll(refilled)
}

/**
 * 内置 TTS 提供商回填（与 [refillDefaultAssistants] 同款模式）：
 * 缺了的内置项补回来，**但跳过墓碑**——用户显式删除过的内置项不再回填，
 * 否则读路径每次把删掉的加回来，等于删不掉。
 */
internal fun refillDefaultTtsProviders(
    existing: MutableList<TTSProviderSetting>,
    removedIds: Set<Uuid>,
) {
    val refilled = refillBuiltIns(existing, DEFAULT_TTS_PROVIDERS, removedIds) { it.copyProvider() }
    existing.clear()
    existing.addAll(refilled)
}

/** 该 id 是否属于内置 TTS 提供商（删除时需要记墓碑，否则读路径会补回来）。 */
internal fun isBuiltInTtsProvider(id: Uuid): Boolean =
    DEFAULT_TTS_PROVIDERS.any { it.id == id }

/**
 * 系统 TTS 兜底不变式（**纯函数**）：列表里**一个启用的都没有**时，把系统 TTS 拉回启用。
 *
 * 为什么单给它这个待遇：系统 TTS 走设备引擎、不需要 API key 与网络，是唯一「永远可用」的
 * 选项。允许用户把最后一个也停掉，会让朗读按钮与语音模式直接失效且无人解释原因。
 * 系统 TTS 自身已被删除（墓碑）时不做任何事——那种情况下确实没有兜底可用，
 * 由 `getSelectedTTSProvider()` 返回 null 让 UI 如实置「不可用」。
 */
internal fun List<TTSProviderSetting>.ensureFallbackTtsEnabled(): List<TTSProviderSetting> {
    if (any { it.enabled }) return this
    val fallbackIndex = indexOfFirst { it.id == DEFAULT_SYSTEM_TTS_ID }
    if (fallbackIndex < 0) return this
    return mapIndexed { index, provider ->
        if (index == fallbackIndex) provider.withEnabled(true) else provider
    }
}

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        // 缓存止血（#1542 复核 Tier A）：不用 AFTER_SYSTEM_PROMPT——该位置会**改写 system
        // 消息本体**（applyInjections 把 before+原文+after 拼回 system），而 system 在请求
        // 位置 0，一变整段前缀缓存全废。BOTTOM_OF_CHAT 插在对话尾部、前缀稳定。
        // 只影响新装用户；存量数据的该条目由管理页「一键迁移」覆盖。
        position = InjectionPosition.BOTTOM_OF_CHAT,
        name = "Learning Mode"
    )
)

/**
 * 备份内容默认值：**全部三项**。
 *
 * 旧版默认只有 DATABASE+FILES，但那是当时尚无工作区项的局限，并非用户偏好；
 * 默认全备更符合「防止数据丢失」的意图（嫌大可取消勾选）。
 */
val DEFAULT_BACKUP_ITEMS: List<BackupItem> = BackupItem.entries.toList()


// ---- 护栏与预算的默认值（与原写死常量一致；可配置化不改变默认行为） ----
const val DEFAULT_WORKSPACE_TURN_BUDGET_CHARS = 256 * 1024
const val DEFAULT_WORKSPACE_TURN_MAX_STEPS = 64
const val DEFAULT_PHONE_USE_TURN_MAX_STEPS = 40
const val DEFAULT_SUBAGENT_MAX_CONCURRENT_RUNS = 2
const val DEFAULT_WORKSPACE_TASK_MAX_DURATION_MINUTES = 30

/**
 * 上下文图片清单上限（借鉴 NMoi D248，台账 `docs/references/other projects/nmoi-agent.md` §13.4）。
 * 请求窗口里保留的最近图片张数；超出的在**请求组装时**换成占位文本（可被 view_image 取回）。
 * 0 = 关闭淘汰（历史行为）。
 */
const val DEFAULT_CONTEXT_IMAGE_LIMIT = 20

/** [DEFAULT_CONTEXT_IMAGE_LIMIT] 的合法范围（设置页钳制口径）。 */
val CONTEXT_IMAGE_LIMIT_RANGE: IntRange = 0..500

/** 默认预设主题 id（= ui 侧 SakuraThemePreset 的 id）。批次 11 起 data 层不再引用 ui.theme 的 PresetThemes 取默认值，预设清单仍是 ui 的展示概念；持久化默认值只需这个稳定字符串。 */
const val DEFAULT_THEME_ID = "sakura"
