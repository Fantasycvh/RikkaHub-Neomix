package app.androidrik.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Tick01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import app.androidrik.rikkahub.ui.components.message.tools.DefaultToolPreview
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIContext
import app.androidrik.rikkahub.ui.components.message.tools.ToolUIRegistry
import app.androidrik.rikkahub.ui.components.richtext.ZoomableAsyncImage
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.components.ui.DotLoading
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.utils.JsonInstant

/**
 * 服务端内置工具（googleSearch / urlContext 等）的链内步骤。
 *
 * 服务端执行、不粘审批流（f7869e35 语义）。三态：`IN_PROGRESS` 转圈，`COMPLETED` 打勾，
 * `FAILED`（上游 3.1.0 备份可能带来，见 design-backup-legacy-import.md）显示失败态——
 * 只做展示，不参与执行逻辑。
 */
@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val running = tool.status == ServerToolStatus.IN_PROGRESS
    val failed = tool.status == ServerToolStatus.FAILED
    ChainOfThoughtStep(
        icon = {
            when {
                running -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )

                failed -> Icon(
                    imageVector = HugeIcons.AlertCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )

                else -> Icon(
                    imageVector = HugeIcons.CheckmarkCircle02,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        label = {
            Text(
                text = when {
                    running -> stringResource(R.string.chat_server_tool_running, tool.toolName)
                    failed -> stringResource(R.string.chat_server_tool_failed, tool.toolName)
                    else -> stringResource(R.string.chat_server_tool_done, tool.toolName)
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.shimmer(isLoading = running),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
    onApproveAll: (() -> Unit)? = null,
    workspaceId: String? = null,
    memoryStoreId: String? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer, onToolSkip = onToolSkip)
        return
    }

    // 版本号参与 key:插件运行时注册/注销渲染器后需要重新解析
    val renderer = remember(tool.toolName, ToolUIRegistry.version) {
        ToolUIRegistry.resolve(tool.toolName)
    }
    val outputJson = remember(tool) {
        if (tool.isExecuted) {
            runCatching {
                JsonInstant.parseToJsonElement(
                    tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                )
            }.getOrNull()
        } else {
            null
        }
    }
    // 输出不是 JSON (例如超长被截断后只剩文本预览) 时, 定制渲染器读不到任何字段, 详情改用默认渲染展示原文
    val outputUnparsable = tool.isExecuted && outputJson == null
    val context = remember(tool, loading, workspaceId, memoryStoreId) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = if (tool.isExecuted) outputJson ?: JsonObject(emptyMap()) else null,
            loading = loading,
            workspaceId = workspaceId,
            memoryStoreId = memoryStoreId,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()

    // 摘要由注册的渲染器决定; 图片输出与拒绝原因为所有工具通用
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showDenyDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    // 跳过（design-ask-user-interrupt §6）：工具结果直接写 status=skipped，
                    // 模型知道用户没等这一步——区别于拒绝（明确不要）与批准（执行）
                    if (onToolSkip != null) {
                        FilledTonalIconButton(
                            onClick = { onToolSkip(tool.toolCallId) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Forward02,
                                contentDescription = stringResource(R.string.chat_message_tool_skip),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { onToolApproval(tool.toolCallId, true, "") },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (onApproveAll != null) {
                        TextButton(
                            onClick = { onApproveAll() },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_approve_all),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        onClick = { showResult = true },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(tool.toolCallId, false, reason)
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                if (outputUnparsable) {
                    DefaultToolPreview(context = context)
                } else {
                    renderer.Preview(
                        context = context,
                        onDismissRequest = { showResult = false },
                    )
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }
    // Track free-text custom answers (for single/multi questions, covers "Other"-style answers)
    val customAnswers = remember { mutableStateMapOf<String, String>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips + optional custom free-text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = {
                                                        answers[q.id] = option
                                                        customAnswers.remove(q.id)
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Custom answer input (covers "Other"-style answers)
                                    OutlinedTextField(
                                        value = customAnswers[q.id] ?: "",
                                        onValueChange = { customAnswers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                        placeholder = {
                                            Text(stringResource(R.string.chat_message_tool_custom_answer_placeholder))
                                        },
                                    )
                                }
                                "multi" -> {
                                    // Multi select: chips + optional custom free-text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Custom answer input (covers "Other"-style answers)
                                    OutlinedTextField(
                                        value = customAnswers[q.id] ?: "",
                                        onValueChange = { customAnswers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                        placeholder = {
                                            Text(stringResource(R.string.chat_message_tool_custom_answer_placeholder))
                                        },
                                    )
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button（+ 跳过提问，design-ask-user-interrupt §6：不答也不断，
                // 结果写 skipped，模型继续推进）
                if (isPending && (onToolAnswer != null || onToolSkip != null)) {
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onToolAnswer != null) {
                            FilledTonalButton(
                                onClick = {
                                    val answerPayload = buildJsonObject {
                                        put("answers", buildJsonObject {
                                            questions.forEach { q ->
                                                when (q.selectionType) {
                                                    "multi" -> {
                                                        val custom = customAnswers[q.id]?.trim().orEmpty()
                                                        val selected = multiAnswers[q.id].orEmpty()
                                                        put(
                                                            q.id,
                                                            JsonPrimitive(
                                                                (if (custom.isNotEmpty()) selected + custom else selected)
                                                                    .joinToString(", ")
                                                            )
                                                        )
                                                    }
                                                    "single" -> {
                                                        val custom = customAnswers[q.id]?.trim().orEmpty()
                                                        put(
                                                            q.id,
                                                            JsonPrimitive(
                                                                if (custom.isNotEmpty()) custom else (answers[q.id] ?: "")
                                                            )
                                                        )
                                                    }
                                                    else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                                }
                                            }
                                        })
                                    }
                                    onToolAnswer(tool.toolCallId, answerPayload.toString())
                                },
                                enabled = questions.all { q ->
                                    when (q.selectionType) {
                                        "multi" -> !multiAnswers[q.id].isNullOrEmpty() || !customAnswers[q.id].isNullOrBlank()
                                        else -> !answers[q.id].isNullOrBlank() || !customAnswers[q.id].isNullOrBlank()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Tick01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_submit),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                        // 跳过提问：不答也不断（结果写 skipped），与提交按钮同行
                        if (onToolSkip != null) {
                            TextButton(
                                onClick = { onToolSkip(tool.toolCallId) },
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Forward02,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_skip),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
