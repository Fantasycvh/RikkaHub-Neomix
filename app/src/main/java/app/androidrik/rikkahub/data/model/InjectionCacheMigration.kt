package app.androidrik.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * 注入位置批量迁移（#1542 复核 Tier A 及其反向）。
 *
 * **双向工具，不做强制**：位置是用户的表达——放系统区（`AFTER_SYSTEM_PROMPT`）获得
 * 系统级强调、宁可破坏缓存，或放对话末尾（`BOTTOM_OF_CHAT`）缓存友好，都由用户选择；
 * 这里的迁移只是**批量重排工具**，手动单条编辑仍可自由选任何位置。
 *
 * - `TO_BOTTOM_OF_CHAT`（正向，缓存友好）：系统区条目 → 对话末尾
 * - `TO_AFTER_SYSTEM_PROMPT`（反向，系统区强调）：对话末尾条目 → 系统提示词后
 *   （覆盖 Tier A 正向迁移的产物与 Learning Mode 种子的现位置）
 *
 * 迁移范围：预设条目与世界书条目两个数据源都算。**纯函数**：返回新列表，
 * 由调用方写回 Settings；无匹配条目时返回原列表（幂等）。
 */
enum class InjectionMigrationDirection {
    /** 正向：系统区 → 对话末尾（缓存友好） */
    TO_BOTTOM_OF_CHAT,

    /** 反向：对话末尾 → 系统提示词后（系统区强调，接受缓存代价） */
    TO_AFTER_SYSTEM_PROMPT,
}

/** 会改写 system 消息本体、破坏前缀缓存的位置（正向迁移的来源集）。 */
private val SYSTEM_REWRITING_POSITIONS =
    setOf(InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT)

/** 各方向的（来源位置集 → 目标位置）。 */
private val MIGRATION_RULES = mapOf(
    InjectionMigrationDirection.TO_BOTTOM_OF_CHAT to
        (SYSTEM_REWRITING_POSITIONS to InjectionPosition.BOTTOM_OF_CHAT),
    InjectionMigrationDirection.TO_AFTER_SYSTEM_PROMPT to
        (setOf(InjectionPosition.BOTTOM_OF_CHAT) to InjectionPosition.AFTER_SYSTEM_PROMPT),
)

/** 指定方向上可迁移的条目数（提示卡显示用）。 */
fun countMigratableInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
    direction: InjectionMigrationDirection,
): Int = presets.sumOf { preset -> preset.entries.count { it.position in sourcesOf(direction) } } +
    lorebooks.sumOf { book -> book.entries.count { it.position in sourcesOf(direction) } }

/** 是否存在指定方向上可迁移的条目（提示卡据此显示对应行）。 */
fun hasMigratableInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
    direction: InjectionMigrationDirection,
): Boolean = countMigratableInjections(presets, lorebooks, direction) > 0

/**
 * 执行迁移：来源位置集内的条目改为目标位置。
 *
 * 只改 `position`，内容/顺序/启停/优先级一律不动；无匹配条目时返回原列表
 * （调用方可据此判断是否真的写盘）。
 */
fun migrateInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
    direction: InjectionMigrationDirection,
): Pair<List<PromptPreset>, List<Lorebook>> {
    val (sources, target) = MIGRATION_RULES.getValue(direction)
    var changed = false
    val newPresets = presets.map { preset ->
        if (preset.entries.none { it.position in sources }) {
            preset
        } else {
            changed = true
            preset.copy(
                entries = preset.entries.map { entry ->
                    if (entry.position in sources) entry.copy(position = target) else entry
                }
            )
        }
    }
    val newLorebooks = lorebooks.map { book ->
        if (book.entries.none { it.position in sources }) {
            book
        } else {
            changed = true
            book.copy(
                entries = book.entries.map { entry ->
                    if (entry.position in sources) entry.copy(position = target) else entry
                }
            )
        }
    }
    return if (changed) newPresets to newLorebooks else presets to lorebooks
}

private fun sourcesOf(direction: InjectionMigrationDirection): Set<InjectionPosition> =
    MIGRATION_RULES.getValue(direction).first
