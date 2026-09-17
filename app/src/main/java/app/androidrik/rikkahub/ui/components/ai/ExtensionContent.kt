package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.X
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Link01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.files.SkillCategoryFilter
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.rikkahub.data.files.distinctCategories
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.QuickMessage

/** Shared visibility for worldbook binding tabs and prompt management. */
internal const val SHOW_LOREBOOK_TABS = true

/**
 * 扩展面板的 tab。两处界面共用（扩展面板 `ExtensionSelector` 与助手详情 `AssistantExtensionsPage`）。
 *
 * 用枚举 + [visible] 而不是写死 4 个 `Tab`：隐藏某个 tab 时**页面索引自动跟着变**，
 * 不会出现「tab 少了但 pager 还停在旧下标」这类错位。
 */
internal enum class ExtensionTab(val labelRes: Int) {
    QUICK_MESSAGES(R.string.extension_selector_tab_quick_messages),
    MODE_INJECTIONS(R.string.extension_selector_tab_mode_injections),
    LOREBOOKS(R.string.extension_selector_tab_lorebooks),
    SKILLS(R.string.extension_selector_tab_skills);

    companion object {
        /** 当前实际渲染的 tab（按顺序） */
        val visible: List<ExtensionTab>
            get() = entries.filter { it != LOREBOOKS || SHOW_LOREBOOK_TABS }
    }
}

@Composable
fun ModeInjectionsContent(
    modeInjections: List<PromptInjection.ModeInjection>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(modeInjections) { injection ->
            ListItem(
                headlineContent = {
                    Text(injection.name.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(injection.id),
                        onCheckedChange = { checked -> onToggle(injection.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

@Composable
fun SkillsContent(
    skills: List<SkillMetadata>,
    enabledSkills: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (header != null) {
            item { header() }
        }
        items(skills, key = { it.skillDir.absolutePath }) { skill ->
            ListItem(
                headlineContent = { Text(skill.name) },
                supportingContent = if (skill.description.isNotBlank() || !skill.category.isNullOrBlank()) {
                    {
                        Column {
                            if (!skill.category.isNullOrBlank()) {
                                Text(
                                    text = skill.category!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (skill.description.isNotBlank()) {
                                Text(
                                    text = skill.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = enabledSkills.contains(skill.name),
                        onCheckedChange = { checked -> onToggle(skill.name, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (onManage != null) {
            item {
                ManageButton(onClick = onManage)
            }
        }
    }
}

/**
 * #1901：[SkillCategoryFilter] 的 `rememberSaveable` Saver。
 *
 * sealed interface 无法直接存进 Bundle，序列化成短字符串即可（分类名用前缀区分，
 * 避免「分类恰好叫 all」这类碰撞）。
 */
val SkillCategoryFilterSaver: Saver<SkillCategoryFilter, String> = Saver(
    save = { filter ->
        when (filter) {
            SkillCategoryFilter.All -> "all"
            SkillCategoryFilter.Uncategorized -> "uncat"
            is SkillCategoryFilter.Named -> "named:${filter.name}"
        }
    },
    restore = { saved ->
        when {
            saved == "uncat" -> SkillCategoryFilter.Uncategorized
            saved.startsWith("named:") -> SkillCategoryFilter.Named(saved.removePrefix("named:"))
            else -> SkillCategoryFilter.All
        }
    },
)

/**
 * #1901：Skills 的搜索框 + 单分类筛选条。
 *
 * 抽成共享组件是**有意为之**：技能有三个入口（管理页、聊天扩展面板、助手扩展页），
 * 如果每处各写一份筛选 UI，改一处漏两处是必然（本仓已有同类漂移教训）。
 *
 * 状态由调用方持有（`query`/`category` 传进传出），组件本身无状态——
 * 这样各入口可以各自决定是否显示、以及如何展示空结果。
 */
@Composable
fun SkillFilterBar(
    skills: List<SkillMetadata>,
    query: String,
    onQueryChange: (String) -> Unit,
    category: SkillCategoryFilter,
    onCategoryChange: (SkillCategoryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories = remember(skills) { skills.distinctCategories() }
    val hasUncategorized = remember(skills) { skills.any { it.category.isNullOrBlank() } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text(stringResource(R.string.skills_filter_search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Lucide.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Lucide.X,
                            contentDescription = stringResource(R.string.skills_filter_clear),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )
        // 分类 chips 只在真的有分类时才显示（全是未分类时它没有信息量）
        if (categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = category is SkillCategoryFilter.All,
                    onClick = { onCategoryChange(SkillCategoryFilter.All) },
                    label = { Text(stringResource(R.string.skills_filter_category_all)) },
                )
                categories.forEach { name ->
                    FilterChip(
                        selected = category == SkillCategoryFilter.Named(name),
                        onClick = { onCategoryChange(SkillCategoryFilter.Named(name)) },
                        label = { Text(name) },
                    )
                }
                if (hasUncategorized) {
                    FilterChip(
                        selected = category is SkillCategoryFilter.Uncategorized,
                        onClick = { onCategoryChange(SkillCategoryFilter.Uncategorized) },
                        label = { Text(stringResource(R.string.skills_filter_category_uncategorized)) },
                    )
                }
            }
        }
    }
}

@Composable
fun QuickMessagesContent(
    quickMessages: List<QuickMessage>,
    selectedIds: Set<kotlin.uuid.Uuid>,
    onToggle: (kotlin.uuid.Uuid, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (() -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(quickMessages, key = { it.id }) { quickMessage ->
            ListItem(
                headlineContent = {
                    Text(quickMessage.title.ifBlank { stringResource(R.string.extension_content_unnamed) })
                },
                supportingContent = if (quickMessage.content.isNotBlank()) {
                    {
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                        )
                    }
                } else null,
                trailingContent = {
                    Switch(
                        checked = selectedIds.contains(quickMessage.id),
                        onCheckedChange = { checked -> onToggle(quickMessage.id, checked) }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Icon(Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.extension_content_manage),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
fun ExtensionEmptyState(
    message: String,
    buttonText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (buttonText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Icon(HugeIcons.Link01, contentDescription = null)
                Text(buttonText)
            }
        }
    }
}
