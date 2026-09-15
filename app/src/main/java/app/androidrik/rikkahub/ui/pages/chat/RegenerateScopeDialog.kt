package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.service.ChatService

/**
 * 重生成的 scope 选择（**不按消息角色区分**——用户编辑后既可能想"只修正这一段"，
 * 也可能想"从这里重做"；见 `docs/design-regenerate-scope.md` §一/§三）。
 *
 * - **仅本条与对应回复**（[ChatService.RegenerateScope.THIS_ONLY]）：非破坏，直接执行。
 * - **含后续所有对话**（[ChatService.RegenerateScope.INCLUDE_FOLLOWING]）：破坏性，
 *   故此处承担确认职责，并提供「导出后继续」（先留存再截断——导出是"留存"不是"回退"，
 *   见设计稿 §六）。
 *
 * @param followingCount 下游消息条数（用于文案显示"将移除 N 条"）。为 0 时 scope B
 *   无实际破坏性，可省去确认直接执行（调用方判断）。
 */
@Composable
fun RegenerateScopeDialog(
    followingCount: Int,
    onDismiss: () -> Unit,
    onExportThenContinue: () -> Unit,
    onConfirm: (scope: ChatService.RegenerateScope) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.regenerate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    // 文案判据是「是否编辑过」而非角色（编辑就已让下游处于旧语境）
                    text = stringResource(R.string.regenerate_scope_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (followingCount > 0) {
                    Text(
                        text = stringResource(R.string.regenerate_scope_following_count, followingCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 两个 scope 各是一个动作按钮（对话框内不设"确认按钮"，避免"选 A 却点了确认"）
                TextButton(
                    onClick = { onConfirm(ChatService.RegenerateScope.THIS_ONLY) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.regenerate_scope_this_only))
                }
                TextButton(
                    onClick = { onConfirm(ChatService.RegenerateScope.INCLUDE_FOLLOWING) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.regenerate_scope_include_following),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (followingCount > 0) {
                    // 导出是「留存」不是「回退」——先留一份副本再截断
                    TextButton(
                        onClick = onExportThenContinue,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.regenerate_scope_export_then_continue))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
