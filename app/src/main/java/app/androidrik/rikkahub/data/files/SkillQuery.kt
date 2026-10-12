package app.androidrik.rikkahub.data.files

/**
 * #1901：Skills 管理页的筛选条件（**纯数据**，UI 与单测共用）。
 *
 * 只有两个维度：搜索词（名称 + 描述）与单个分类。**不做多标签**——标签在 SKILL.md 里
 * 是手写字段、没有可放标签池的 DB 表，重命名标签就要批量改文件，收益不划算
 * （评估见 `docs/upstream-analysis.md` §#1901）。
 */
sealed interface SkillCategoryFilter {
    /** 不按分类过滤。 */
    data object All : SkillCategoryFilter

    /** 只看没有 `category` 的技能。 */
    data object Uncategorized : SkillCategoryFilter

    /** 只看指定分类。 */
    data class Named(val name: String) : SkillCategoryFilter
}

/**
 * 按 [filter] 过滤技能（名称/描述子串匹配，忽略大小写；分类精确匹配）。
 *
 * **纯函数**便于单测：搜索的匹配范围、空查询行为、分类与搜索的**与**关系都在这里钉死，
 * UI 只负责把状态喂进来。
 */
fun List<SkillMetadata>.filterSkills(
    query: String = "",
    category: SkillCategoryFilter = SkillCategoryFilter.All,
): List<SkillMetadata> {
    val trimmed = query.trim()
    return filter { skill ->
        val matchesQuery = trimmed.isEmpty() ||
            skill.name.contains(trimmed, ignoreCase = true) ||
            skill.description.contains(trimmed, ignoreCase = true)
        val matchesCategory = when (category) {
            SkillCategoryFilter.All -> true
            SkillCategoryFilter.Uncategorized -> skill.category.isNullOrBlank()
            is SkillCategoryFilter.Named -> skill.category?.trim() == category.name
        }
        matchesQuery && matchesCategory
    }
}

/** 当前技能集合里出现过的分类（去重 + 排序），供筛选 chips 使用。 */
fun List<SkillMetadata>.distinctCategories(): List<String> =
    mapNotNull { it.category?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
