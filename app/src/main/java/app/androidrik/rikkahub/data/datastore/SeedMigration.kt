package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptPreset
import kotlin.uuid.Uuid

/**
 * 内置种子条目的**历史版本指纹**：内容 + 位置都一致 = 用户拿到后没动过 ⇒ 可安全更新到新版。
 *
 * 对应 SoLab「世界书 seed 三重迁移」的三重机制之一——本仓按**固定 entryId** 定位条目
 * （种子 UUID 恒定，改名/移动预设容器都不影响识别），故无需 sha256 内容表；
 * 指纹直接比对全文（种子文本量级 KB 以下，无需哈希间接层）。
 */
data class SeedEntryFingerprint(
    val content: String,
    val position: InjectionPosition,
)

/**
 * 内置预设条目种子定义。
 *
 * [staleFingerprints] 收录**所有已发布过的历史版本**的（内容, 位置）——每发布一个新版，
 * 把旧版的指纹追加进来；用户手里任何一版未改动的旧副本都会被自动升级到 [version] 最新形态。
 */
data class SeedPresetEntry(
    val entryId: Uuid,
    val name: String,
    val version: Int,
    val content: String,
    val position: InjectionPosition,
    val staleFingerprints: List<SeedEntryFingerprint> = emptyList(),
)

/**
 * 内置预设条目种子注册表（当前仅 1 条：Learning Mode）。
 *
 * v2（现行）：BOTTOM_OF_CHAT——#1542 复核 Tier A 缓存止血（AFTER_SYSTEM_PROMPT 会改写
 * system 本体、废掉前缀缓存）。v1 指纹 = 同内容 + AFTER_SYSTEM_PROMPT：彼时只改了
 * DEFAULT_MODE_INJECTIONS 的位置、内容未变，故存量未改动副本的形态就是「同内容+旧位置」。
 *
 * **设计取舍**：条目被用户删掉**不回填**（删除是用户表达，复活等于删不掉——与
 * `refillDefaultAssistants` 的墓碑哲学一致；本机制只负责「升级用户手里的旧副本」）。
 * 未来要给世界书（lorebooks）加种子时，把 `migrateSeedPresetEntries` 泛化出对
 * `Lorebook.entries` 的同构版本即可，机制不变。
 */
val SEED_PRESET_ENTRIES: List<SeedPresetEntry> = listOf(
    SeedPresetEntry(
        entryId = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        name = "Learning Mode",
        version = 2,
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.BOTTOM_OF_CHAT,
        staleFingerprints = listOf(
            // v1：#1542 之前的位置（内容与现行相同）
            SeedEntryFingerprint(LEARNING_MODE_PROMPT, InjectionPosition.AFTER_SYSTEM_PROMPT),
        ),
    ),
)

/**
 * 种子条目自动迁移（peer ⑤①「三重迁移」）：把用户手里**未改动的旧种子副本**升级到最新版，
 * 只更新 name/content/position，**启停（enabled）、优先级、所属预设容器一律保留**。
 *
 * - 用户改过内容或位置 → 指纹不命中 → 原样保留（不覆盖用户表达）。
 * - 条目不存在（被删除）→ 不回填。
 * - 无任何命中 → 返回 null（调用方跳过写回，幂等）。
 *
 * **纯函数**，供 PreferencesStore 读路径调用（读路径不落盘；迁移结果随下次任意
 * Settings.update 自然持久化）。
 */
fun migrateSeedPresetEntries(
    presets: List<PromptPreset>,
    seeds: List<SeedPresetEntry> = SEED_PRESET_ENTRIES,
): List<PromptPreset>? {
    if (seeds.isEmpty()) return null
    var changed = false
    val migrated = presets.map { preset ->
        if (preset.entries.none { entry -> seeds.any { it.entryId == entry.id } }) {
            preset
        } else {
            preset.copy(
                entries = preset.entries.map { entry ->
                    val seed = seeds.firstOrNull { it.entryId == entry.id } ?: return@map entry
                    val isStaleCopy = seed.staleFingerprints.any { fingerprint ->
                        fingerprint.content == entry.content && fingerprint.position == entry.position
                    }
                    if (!isStaleCopy) return@map entry
                    changed = true
                    entry.copy(name = seed.name, content = seed.content, position = seed.position)
                },
            )
        }
    }
    return if (changed) migrated else null
}
