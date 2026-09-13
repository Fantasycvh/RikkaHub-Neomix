package app.androidrik.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.sync.BackupItem

/**
 * 备份内容选择器（S3 / WebDav 两个 tab 共用）。
 *
 * 此前两处各有一份完全相同（仅 config 类型不同）的选择器实现；统一到此处后，
 * 新增备份项只需在此加一个分支。
 */
@Composable
fun BackupItemsSelector(
    items: List<BackupItem>,
    onItemsChange: (List<BackupItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    MultiChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
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
}

/** 备份项的展示名（单一来源，避免两 tab 文案分支各写一份） */
@Composable
fun backupItemLabel(item: BackupItem): String = stringResource(
    when (item) {
        BackupItem.DATABASE -> R.string.backup_page_chat_records
        BackupItem.FILES -> R.string.backup_page_files
        BackupItem.WORKSPACE_FILES -> R.string.backup_page_workspace_files
    }
)
