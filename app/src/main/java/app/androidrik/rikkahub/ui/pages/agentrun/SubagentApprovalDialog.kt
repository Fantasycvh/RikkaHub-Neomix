package app.androidrik.rikkahub.ui.pages.agentrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue

/** 全局前台入口；通知、详情页和弹窗共享队列，答复或超时后自动移除。 */
@Composable
fun SubagentApprovalDialog() {
    val requests by SubagentApprovalQueue.requests.collectAsStateWithLifecycle()
    val request = requests.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.subagent_approval_title, request.label)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.agent_run_approval_hint))
                Text(request.tool.toolName, style = MaterialTheme.typography.labelLarge)
                Text(request.tool.input.ifBlank { "{}" }, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                SubagentApprovalQueue.resolve(request.tool.toolCallId, approved = true)
            }) {
                Text(stringResource(R.string.subagent_approval_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                SubagentApprovalQueue.resolve(request.tool.toolCallId, approved = false)
            }) {
                Text(
                    stringResource(R.string.subagent_approval_deny),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
