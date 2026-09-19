package app.androidrik.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * #1542 复核 Tier A 的**一键迁移**：把「改写 system 消息本体」的注入条目迁到对话尾部。
 *
 * 为什么迁移：`BEFORE/AFTER_SYSTEM_PROMPT` 在 `PromptInjectionTransformer.applyInjections`
 * 里会把 before + 原文 + after **拼回 system 消息**（请求位置 0）——任何一条此类注入变化，
 * 整段前缀缓存从第 0 token 起全废。`BOTTOM_OF_CHAT` 插在对话尾部、前缀稳定，且保留原 role，
 * 语义最接近「贴着 system 的补充指令、只是挪到末尾」。
 *
 * 迁移范围：预设条目（含未启用预设里的）与世界书条目都算——ST 导入的 position=1（after）
 * 条目常落在这里，是存量用户踩缓存坑的主要来源。
 *
 * **纯函数**：返回新列表，由调用方写回 Settings；幂等（迁移后再跑一次无变化）。
 */

/** 会改写 system 消息本体、破坏前缀缓存的位置。 */
private val SYSTEM_REWRITING_POSITIONS =
    setOf(InjectionPosition.BEFORE_SYSTEM_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT)

/** 迁移目标：对话尾部（保留原 role）。 */
val CACHE_MIGRATION_TARGET = InjectionPosition.BOTTOM_OF_CHAT

/** 是否存在需要迁移的条目（管理页据此决定是否显示迁移提示卡）。 */
fun hasSystemRewritingInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
): Boolean = presets.any { preset -> preset.entries.any { it.position in SYSTEM_REWRITING_POSITIONS } } ||
    lorebooks.any { book -> book.entries.any { it.position in SYSTEM_REWRITING_POSITIONS } }

/** 需要迁移的条目数（提示卡显示用）。 */
fun countSystemRewritingInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
): Int = presets.sumOf { preset -> preset.entries.count { it.position in SYSTEM_REWRITING_POSITIONS } } +
    lorebooks.sumOf { book -> book.entries.count { it.position in SYSTEM_REWRITING_POSITIONS } }

/**
 * 执行迁移：所有 `BEFORE/AFTER_SYSTEM_PROMPT` 条目改为 [CACHE_MIGRATION_TARGET]。
 *
 * 只改 `position`，内容/顺序/启停/优先级一律不动；无该类条目时返回原列表（调用方可据此
 * 判断是否真的写盘）。
 */
fun migrateSystemRewritingInjections(
    presets: List<PromptPreset>,
    lorebooks: List<Lorebook>,
): Pair<List<PromptPreset>, List<Lorebook>> {
    var changed = false
    val newPresets = presets.map { preset ->
        if (preset.entries.none { it.position in SYSTEM_REWRITING_POSITIONS }) {
            preset
        } else {
            changed = true
            preset.copy(
                entries = preset.entries.map { entry ->
                    if (entry.position in SYSTEM_REWRITING_POSITIONS) {
                        entry.copy(position = CACHE_MIGRATION_TARGET)
                    } else {
                        entry
                    }
                }
            )
        }
    }
    val newLorebooks = lorebooks.map { book ->
        if (book.entries.none { it.position in SYSTEM_REWRITING_POSITIONS }) {
            book
        } else {
            changed = true
            book.copy(
                entries = book.entries.map { entry ->
                    if (entry.position in SYSTEM_REWRITING_POSITIONS) {
                        entry.copy(position = CACHE_MIGRATION_TARGET)
                    } else {
                        entry
                    }
                }
            )
        }
    }
    return if (changed) newPresets to newLorebooks else presets to lorebooks
}
