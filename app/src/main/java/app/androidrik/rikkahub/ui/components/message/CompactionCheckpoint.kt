package app.androidrik.rikkahub.ui.components.message

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.compression.extractTraceSection
import app.androidrik.rikkahub.data.ai.compression.stripTraceSection
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField
import app.androidrik.rikkahub.ui.components.ui.AppTextButton
import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Archive02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Edit01
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 压缩检查点折叠行：显示「已压缩 N 条历史」，点击展开/收起被压缩的原文。
 *
 * design-auto-compaction §4.1 三边界对齐的界面侧：折叠行 = 压缩边界，
 * 默认收起（= AI 看到的窗口），展开可回看全文（= 存储的完整轨迹，对 Yuihub
 * 「CompactionRow 不可展开」的改进点）。
 *
 * [onEditClick] 非空时显示「编辑摘要」入口（native 检查点才有 DB 行可编辑；
 * 导入的检查点节点正文走行内展开，不走此入口）。
 */
@Composable
fun CompactionCheckpointRow(
    messageCount: Int,
    summaryPreview: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onEditClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(appSkinRoundedShape(12f))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Archive02,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.compaction_row_title, messageCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summaryPreview.isNotBlank()) {
                Text(
                    text = summaryPreview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onEditClick != null) {
            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = "编辑摘要",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Icon(
            imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            contentDescription = stringResource(
                if (expanded) R.string.compaction_row_collapse else R.string.compaction_row_expand
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

private val checkpointTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * 压缩摘要查看/编辑 sheet（「使压缩摘要对用户可见并允许编辑」）。
 *
 * - **可见**：native 检查点的完整摘要正文（折叠行只露首行预览，正文此前无处可看）
 * - **编辑**：只改可读正文；痕迹附录（机械清单，`attachTraceSection` 重压缩接力用）
 *   不参与编辑，保存时**原样保留**拼回——附录丢失会让下轮压缩丢掉去重上下文
 * - 保存走 `ChatService.updateCompressionSummaryContent`（DB 单列 + 内存态同步）；
 *   失败（检查点已被新压缩替换等）toast 提示
 *
 * 编辑态 [TextFieldState] 是编辑态唯一权威（AGENTS TextField 规则 #4）：初值快照一次，
 * 此后无任何程序化回灌路径，保存单向读出。
 */
@Composable
fun CompressionSummarySheet(
    summary: CompressionSummary,
    onDismissRequest: () -> Unit,
    onSave: (newContent: String, onResult: (Boolean) -> Unit) -> Unit,
) {
    val toaster = LocalToaster.current
    // 痕迹附录与正文拆分：展示/编辑只针对可读正文，附录保存时原样拼回
    val body = remember(summary.id, summary.content) { stripTraceSection(summary.content) }
    val traceSection = remember(summary.id, summary.content) { extractTraceSection(summary.content) }
    var editing by remember { mutableStateOf(false) }
    // 进入编辑态时快照一次正文作为 TextFieldState 初值；此后 state 是唯一权威（无回灌）
    var editTarget by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        sheetGesturesEnabled = !editing,
    ) {
        if (editing && editTarget != null) {
            EditSummaryContent(
                initialBody = editTarget.orEmpty(),
                onCancel = {
                    editing = false
                    editTarget = null
                },
                onSave = { editedBody ->
                    val newContent = buildString {
                        append(editedBody.trim())
                        if (traceSection != null) {
                            append("\n\n")
                            append(traceSection)
                        }
                    }
                    onSave(newContent) { ok ->
                        if (ok) {
                            editing = false
                            editTarget = null
                        } else {
                            toaster.show(message = "保存失败：摘要已被新的压缩替换，请重试")
                        }
                    }
                },
            )
        } else {
            ViewSummaryContent(
                summary = summary,
                body = body,
                hasTrace = traceSection != null,
                onEdit = {
                    editTarget = body
                    editing = true
                },
                onCopy = {
                    toaster.show(message = "已复制摘要正文")
                },
                onClose = onDismissRequest,
            )
        }
    }
}

@Composable
private fun ViewSummaryContent(
    summary: CompressionSummary,
    body: String,
    hasTrace: Boolean,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    // 60% 高度上限（用户拍板）：折叠行外的完整摘要不再占满全屏；正文区 weight(1f) + 竖向滚动
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "压缩摘要",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = "已压缩 ${summary.messageCount} 条 · " +
                checkpointTimeFormatter.format(summary.createdAt.atZone(ZoneId.systemDefault())) +
                " · 修改后下次对话生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SelectionContainer(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (hasTrace) {
            Text(
                text = "含压缩轨迹附录（重压缩接力用，不参与编辑与模型注入）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CopySummaryButton(body = body, onCopied = onCopy)
            AppTextButton(onClick = onEdit) {
                Icon(HugeIcons.Edit01, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("编辑", modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun CopySummaryButton(
    body: String,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AppTextButton(onClick = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", body)))
        }
        onCopied()
    }) {
        Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(16.dp))
        Text("复制", modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun EditSummaryContent(
    initialBody: String,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    // 编辑态唯一权威：初值快照一次，此后无任何程序化回灌（AGENTS TextField 规则 #4）
    val state = rememberTextFieldState(initialBody)
    val currentText = state.text.toString()
    val saveEnabled = currentText.isNotBlank() && currentText != initialBody

    // 编辑态同款 60%：TextField weight(1f) 占满余量，超出后由输入框内部滚动
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "编辑压缩摘要",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "摘要会随下次对话发送给模型；被压缩的原文与压缩范围不受影响",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AppOutlinedTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 12),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            AppTextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            AppTextButton(
                enabled = saveEnabled,
                onClick = { onSave(currentText) },
            ) {
                Text(stringResource(R.string.confirm))
            }
        }
    }
}
