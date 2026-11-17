package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.BackupCategory
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupScope
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Lucide

/**
 * 备份内容选择器（S3 / WebDav / 本地导入导出 tab 共用）。
 *
 * 此前两处各有一份完全相同（仅 config 类型不同）的选择器实现；统一到此处后，
 * 新增备份项只需在此加一个分支。
 *
 * 高频三档（数据库/文件/工作区）保留分段按钮；低频 settings 类别（助手/提供商/外观/其他）
 * 收进「高级」折叠组——被排除的类别**不写入备份的 settings.json**，恢复时保留本机值。
 *
 * @param scope 备份范围；为 null 时不展示高级组（增量快照等无 settings 细分的场景）
 */
@Composable
fun BackupItemsSelector(
    items: List<BackupItem>,
    onItemsChange: (List<BackupItem>) -> Unit,
    modifier: Modifier = Modifier,
    scope: BackupScope? = null,
    onScopeChange: ((BackupScope) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MultiChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BackupItem.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = BackupItem.entries.size,
                    ),
                    onCheckedChange = { checked ->
                        onItemsChange(if (checked) items + item else items - item)
                    },
                    checked = item in items,
                ) {
                    Text(text = backupItemLabel(item))
                }
            }
        }
        if (scope != null && onScopeChange != null) {
            BackupScopeAdvancedSection(scope = scope, onScopeChange = onScopeChange)
        }
    }
}

/**
 * 高级备份范围（settings 类别排除）折叠组。
 *
 * 折叠态显示当前排除的类别名作为提示（项目约定：高级折叠组的折叠状态下需显示当前配置值）。
 * 纯 Checkbox/文本交互，无 TextField，无 IME 风险；宿主是 CardGroup（Column 实现），常驻节点。
 */
@Composable
private fun BackupScopeAdvancedSection(
    scope: BackupScope,
    onScopeChange: (BackupScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.backup_page_scope_advanced))
                val excludedNames = BackupCategory.entries
                    .filter { it in scope.excluded && it.isSettingsCategory }
                    .map { backupCategoryLabel(it) }
                val summary = excludedNames.joinToString(", ").takeIf { it.isNotEmpty() }
                    ?: stringResource(R.string.backup_page_scope_full)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                    contentDescription = null,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                BackupCategory.entries.filter { it.isSettingsCategory }.forEach { category ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = scope.includes(category),
                            onCheckedChange = { checked ->
                                onScopeChange(scope.withCategory(category, checked))
                            },
                        )
                        Text(text = backupCategoryLabel(category))
                    }
                }
                Text(
                    text = stringResource(R.string.backup_page_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val BackupCategory.isSettingsCategory: Boolean
    get() = this == BackupCategory.ASSISTANTS ||
        this == BackupCategory.PROVIDERS ||
        this == BackupCategory.DISPLAY ||
        this == BackupCategory.ABOUT

/** settings 类别的展示名（scope 编辑 + 恢复提示共用单一来源） */
@Composable
fun backupCategoryLabel(category: BackupCategory): String = stringResource(
    when (category) {
        BackupCategory.ASSISTANTS -> R.string.backup_page_scope_assistants
        BackupCategory.PROVIDERS -> R.string.backup_page_scope_providers
        BackupCategory.DISPLAY -> R.string.backup_page_scope_display
        BackupCategory.ABOUT -> R.string.backup_page_scope_about
        // 归档三档不进高级组；标签仅用于恢复提示的完整性
        BackupCategory.DATABASE -> R.string.backup_page_chat_records
        BackupCategory.FILES -> R.string.backup_page_files
        BackupCategory.WORKSPACE_FILES -> R.string.backup_page_workspace_files
    }
)

/** 备份项的展示名（单一来源，避免两 tab 文案分支各写一份） */
@Composable
fun backupItemLabel(item: BackupItem): String = stringResource(
    when (item) {
        BackupItem.DATABASE -> R.string.backup_page_chat_records
        BackupItem.FILES -> R.string.backup_page_files
        BackupItem.WORKSPACE_FILES -> R.string.backup_page_workspace_files
    }
)
