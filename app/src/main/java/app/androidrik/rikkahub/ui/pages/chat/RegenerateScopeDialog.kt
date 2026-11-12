package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.service.ChatService

/**
 * 重生成的 scope 选择（**不按消息角色区分**——用户编辑后既可能想"只修正这一段"，
 * 也可能想"从这里重做"；见 `docs/design/design-regenerate-scope.md` §一/§三）。
 *
 * 树形分支 P1 起 scope B 是**非破坏分支**（旧时间线原样保留、新回复开新子树）：
 * 本对话框从「警告丢失」降级为「告知去向」，无确认警示色、无撤销（§四.3）。
 * 它保留一个变体——**先导出再继续**：先把旧时间线导出留一份再开新时间线（留存不是回退）。
 *
 * @param followingCount 下游消息条数（文案显示"将保留在旧时间线"）。
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    // 文案判据是「是否编辑过」而非角色（编辑就已让下游处于旧语境）
                    text = stringResource(R.string.regenerate_scope_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.regenerate_scope_following_count, followingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                // ---- A：候选（下游共享）----
                // 两个主 scope 各是一个动作按钮（不设"确认按钮"，避免"选 A 却点了确认"）
                TextButton(
                    onClick = { onConfirm(ChatService.RegenerateScope.THIS_ONLY) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.regenerate_scope_this_only))
                }

                // ---- B：新时间线（非破坏；旧时间线保留）----
                TextButton(
                    onClick = { onConfirm(ChatService.RegenerateScope.INCLUDE_FOLLOWING) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.regenerate_scope_include_following))
                }
                // B 的子选项：缩进 + 小字号，明确它属于 B（先留存一份旧时间线再开新线）
                TextButton(
                    onClick = onExportThenContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp),
                ) {
                    Text(
                        text = stringResource(R.string.regenerate_scope_export_then_continue),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
