package app.androidrik.rikkahub.data.ai.compression

import kotlinx.serialization.Serializable

/**
 * 压缩配置档：一组「压成什么样」的参数，供用户把常用方案固定下来。
 *
 * **只携带 2 个数值**（保留比例 + 摘要目标），**不含触发阈值**——阈值回答的是
 * 「**什么时候**压」（自动压缩专属的触发策略），而这两个值回答「**压成什么样**」
 * （方案本身，自动/手动两条路径通用）。手动压缩的触发本就是用户自己的判断
 * （觉得长/快满了才点），阈值在手动路径上根本不构成一个参数。
 * 见 `docs/design-compress-presets.md` §四。
 *
 * **档不是真相源**：真正决定压缩行为的是 `Settings.autoCompressRetainPercent` /
 * `autoCompressTargetTokens` 这两个数值，档只是「一键填入这两个值」的快捷方式。
 * 因此删除任何档都不会改变当前压缩行为（删除安全），也无须记录「当前选中的档」。
 */
@Serializable
data class CompressPreset(
    val name: String = "",
    val retainPercent: Int = DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT,
    val targetTokens: Int = DEFAULT_AUTO_COMPRESS_TARGET_TOKENS,
)

/** 自定义档数量上限：防档位选择器过长。 */
const val MAX_COMPRESS_PRESETS: Int = 20

/** 摘要目标 token 钳制范围：过低摘要无信息量，过高则压缩收益不足。 */
val COMPRESS_TARGET_TOKENS_RANGE: IntRange = 500..16_000

/**
 * 内置档（**不持久化、不可删除**，始终留合理回退）。
 *
 * 名称由 UI 层映射到字符串资源（`BuiltInCompressPreset -> R.string.…`），
 * 故本枚举不依赖 Android 资源，可纯 JVM 单测。
 */
enum class BuiltInCompressPreset(
    val retainPercent: Int,
    val targetTokens: Int,
) {
    /** 默认档：与 fork 一直以来的默认值一致（保留 40% / 摘要 2000）。 */
    DEFAULT(DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT, DEFAULT_AUTO_COMPRESS_TARGET_TOKENS),

    /** 精简档：压得更狠、省上下文（保留 20% / 摘要 1000）。 */
    COMPACT(20, 1000),

    /** 详细档：多留原文、少丢细节（保留 60% / 摘要 4000）。 */
    DETAILED(60, 4000),
}

/**
 * 当前数值对应的档位（**数值推导**，不存"选中档"字段）。
 *
 * 为什么不存 `selectedPresetId`：真相源是数值（见 [CompressPreset]），额外存一个选中态
 * 会与数值失同步。由数值反推则**改一个数字档位高亮自动变为 [Values]（自定义）**，
 * 语义正确且零维护。
 */
sealed interface CompressPresetMatch {
    /** 命中内置档。 */
    data class BuiltIn(val preset: BuiltInCompressPreset) : CompressPresetMatch

    /** 命中某个自定义档。 */
    data class Custom(val preset: CompressPreset) : CompressPresetMatch

    /** 不匹配任何档 = 用户自填数值。 */
    data object Values : CompressPresetMatch
}

/**
 * 由当前数值反推档位：先比内置档，再比自定义档，都不匹配则 [CompressPresetMatch.Values]。
 *
 * 数值重复时按**首个匹配**（内置优先）——允许重复，不做特殊处理（见设计稿 §六.2）。
 */
fun matchCompressPreset(
    retainPercent: Int,
    targetTokens: Int,
    customPresets: List<CompressPreset>,
): CompressPresetMatch {
    BuiltInCompressPreset.entries.firstOrNull {
        it.retainPercent == retainPercent && it.targetTokens == targetTokens
    }?.let { return CompressPresetMatch.BuiltIn(it) }

    customPresets.firstOrNull {
        it.retainPercent == retainPercent && it.targetTokens == targetTokens
    }?.let { return CompressPresetMatch.Custom(it) }

    return CompressPresetMatch.Values
}

/**
 * 档名是否可用：非空、不与内置档名冲突（内置档名由 UI 提供本地化文案，故由调用方传入）、
 * 且不与**其他**自定义档重名。
 *
 * @param excludeIndex 编辑既有档时传入其下标（允许保留原名）；新建传 null。
 */
fun isCompressPresetNameAvailable(
    name: String,
    customPresets: List<CompressPreset>,
    builtInNames: Collection<String>,
    excludeIndex: Int? = null,
): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    if (builtInNames.any { it.equals(trimmed, ignoreCase = true) }) return false
    return customPresets.withIndex().none { (index, preset) ->
        index != excludeIndex && preset.name.trim().equals(trimmed, ignoreCase = true)
    }
}

/** 把档的数值写入设置前做钳制（越界的档值不应写入）。 */
fun CompressPreset.clamped(): CompressPreset = copy(
    retainPercent = retainPercent.coerceIn(AUTO_COMPRESS_RETAIN_PERCENT_RANGE),
    targetTokens = targetTokens.coerceIn(COMPRESS_TARGET_TOKENS_RANGE),
)
