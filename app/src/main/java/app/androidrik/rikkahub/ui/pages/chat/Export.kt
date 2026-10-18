package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import java.io.File
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyUIMessage
import app.androidrik.ai.util.encodeBase64
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.message.ChatMessageNarrationStep
import app.androidrik.rikkahub.ui.components.message.ChatMessageServerToolStep
import app.androidrik.rikkahub.ui.components.message.MessagePartBlock
import app.androidrik.rikkahub.ui.components.message.ThinkingStep
import app.androidrik.rikkahub.ui.components.message.groupMessageParts
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.components.richtext.generateMarkdownHtml
import app.androidrik.rikkahub.tikz.MupdfSvgBridge
import app.androidrik.rikkahub.tikz.TectonicNative
import app.androidrik.rikkahub.tikz.TikzSvgCache
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.BitmapComposer
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.components.charts.ChartCard
import app.androidrik.rikkahub.ui.components.charts.ChartSpec
import app.androidrik.rikkahub.ui.components.ui.ChainOfThoughtScope
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import com.dokar.sonner.rememberToasterState
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.data.export.AttachmentPlan
import app.androidrik.rikkahub.data.export.ConversationSerializer
import app.androidrik.rikkahub.data.export.packConversationArchive
import app.androidrik.rikkahub.data.export.planAttachments
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.koin.java.KoinJavaComponent.getKoin
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.utils.fileProviderUri
import app.androidrik.rikkahub.utils.shareFile
import app.androidrik.rikkahub.utils.shareFiles
import java.io.ByteArrayOutputStream
import android.util.Log
import app.androidrik.rikkahub.utils.exportImage
import kotlin.math.roundToInt
import app.androidrik.rikkahub.utils.getActivity
import app.androidrik.rikkahub.utils.JsonInstantPretty
import app.androidrik.rikkahub.utils.escapeHtml
import app.androidrik.rikkahub.utils.jsonPrimitiveOrNull
import app.androidrik.rikkahub.utils.toLocalString
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** 本文件日志 TAG（导出失败要留痕：原先只有 printStackTrace，排查时看不到） */
private const val TAG = "ChatExport"

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>,
    /**
     * 导出达成后回调（用于「先导出再继续」流程）。
     *
     * **语义（2026-09-16 修正）**：此前的含义是"分享面板弹出来了"，而分享面板**没有结果回调**，
     * 用户随手取消也照样触发 → 会在"没保存任何东西"时放行破坏性操作（见
     * `docs/design/design-export-retention.md` §1.2 ①）。现在 [saveToDocument] = true 时，
     * 只有**用户真正选定位置且写盘成功**才回调；取消另存**不**回调（= 不继续移除）。
     */
    onExported: (() -> Unit)? = null,
    /**
     * 是否让用户**另存到指定位置**（SAF `CreateDocument`）= 真落地。
     *
     * - `false`（默认，普通导出）：写留存区 → 分享面板（"交给别的 app"语义）。
     * - `true`（「先导出再继续」）：写留存区 → 弹另存对话框；**只有真的存下才回调**
     *   [onExported]。留存区那份始终存在，故取消另存也不会丢内容。
     */
    saveToDocument: Boolean = false,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }
    var includeAllVersions by remember { mutableStateOf(false) }
    // 待另存的文件（非空 = 等用户选位置）。见 saveToDocument。
    var pendingSave by remember { mutableStateOf<ExportedFile?>(null) }
    // 附件合计超软上限时的待确认计划（非空 = 显示确认弹窗）。确认后原样重跑导出。
    var oversizePlan by remember { mutableStateOf<AttachmentPlan?>(null) }
    var oversizeConfirmed by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = pendingSave
        pendingSave = null
        if (uri == null || target == null) {
            // **取消另存 = 不续作破坏性动作**（内容已躺在留存区，用户随时可在
            // 「设置 → 文件管理 → 导出」找回）。见设计稿 §二 决策 1。
            toaster.show(
                message = context.getString(R.string.chat_page_export_save_cancelled),
                type = ToastType.Info,
            )
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    target.file.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot open output stream")
            }.onSuccess {
                toaster.show(context.getString(R.string.chat_page_export_saved), type = ToastType.Success)
                // **顺序关键**：onExported 必须先于 onDismissRequest。
                // ChatPage 的 onDismissRequest 会清掉 exportThenRegenerateFor，
                // 而 onExported 正是读它来续作 scope B —— 反了则续作静默失效。
                onExported?.invoke()
                onDismissRequest()
            }.onFailure {
                toaster.show(
                    message = context.getString(R.string.chat_page_export_save_failed, it.message ?: ""),
                    type = ToastType.Error,
                )
            }
        }
    }

    /**
     * 导出达成后的统一分流：写留存区 → 分享面板 / 另存对话框。
     *
     * **分享面板弹不算"成功"**（无结果回调）；「另存」才有可验证的结果。
     */
    fun deliver(exported: ExportedFile) {
        if (saveToDocument) {
            pendingSave = exported
            saveLauncher.launch(exported.displayName)
        } else {
            toaster.show(
                message = context.getString(R.string.chat_page_export_saved_to_retention),
                type = ToastType.Success,
            )
            context.shareFile(context.fileProviderUri(exported.file), exported.mimeType)
            onExported?.invoke()
            onDismissRequest()
        }
    }

    /** 多文件（PNG 分页）分流：分享整批；另存只存第一页（PNG 已进相册，见设计稿 §六.5）。 */
    fun deliverAll(files: List<File>, mimeType: String) {
        if (saveToDocument) {
            files.firstOrNull()?.let {
                pendingSave = ExportedFile(it, it.name, mimeType)
                saveLauncher.launch(it.name)
            }
        } else {
            toaster.show(
                message = context.getString(R.string.chat_page_export_saved_to_retention),
                type = ToastType.Success,
            )
            val uris = files.map { context.fileProviderUri(it) }
            if (uris.size == 1) context.shareFile(uris[0], mimeType)
            else context.shareFiles(uris, mimeType)
            onExported?.invoke()
            onDismissRequest()
        }
    }
    // 导出的消息：默认当前选中版本（多选流程传筛选后消息）；开启后含分支/编辑历史的全部版本
    val exportMessages = remember(includeAllVersions, conversation, selectedMessages) {
        if (includeAllVersions) conversation.messageNodes.flatMap { it.messages }
        else selectedMessages
    }

    // 附件体积软上限确认（确认后置 oversizeConfirmed 并重跑导出）
    oversizePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { oversizePlan = null },
            title = { Text(stringResource(R.string.chat_page_export_oversize_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.chat_page_export_oversize_message,
                        (plan.totalBytes / 1024 / 1024).toString(),
                        plan.count.toString(),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        oversizePlan = null
                        oversizeConfirmed = true
                        // 重跑导出：第二次不会再拦（oversizeConfirmed = true）
                        scope.launch {
                            runCatching {
                                exportToConversationArchive(context, conversation)
                            }.onSuccess { deliver(it) }
                                .onFailure {
                                    toaster.show(
                                        message = it.message ?: "Export failed",
                                        type = ToastType.Error,
                                    )
                                }
                            oversizeConfirmed = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { oversizePlan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.chat_page_export_include_all_versions))
                    },
                    trailingContent = {
                        Switch(
                            checked = includeAllVersions,
                            onCheckedChange = { includeAllVersions = it }
                        )
                    }
                )

                HorizontalDivider()

                // JSON（可导入）：自家格式往返——Markdown/HTML/PNG 三种只出不能回填，
                // 这个格式可被「备份页 → 导入」读回（见 docs/design/design-conversation-import-export.md）。
                // **有意忽略「包含全部版本」开关**：往返格式必须原样保留分支/编辑历史，
                // 否则导入回来的会话会缺候选（该开关只影响 Markdown/HTML 等只读导出）。
                OutlinedCard(
                    onClick = {
                        scope.launch {
                            // 软上限：合计体积超阈值先提示确认（**不硬拒**）。只 stat 不读内容，
                            // 避免"读一遍大文件才发现超限"。
                            val plan = runCatching {
                                conversationAttachmentPlan(context, conversation)
                            }.getOrNull()
                            if (plan != null && plan.exceedsSoftLimit && !oversizeConfirmed) {
                                oversizePlan = plan
                                return@launch
                            }
                            runCatching {
                                exportToConversationArchive(context, conversation)
                            }.onSuccess { deliver(it) }
                                .onFailure {
                                    // 写盘失败**不上报成功**（原先会报「已导出」+ 触发续作）
                                    toaster.show(
                                        message = it.message ?: "Export failed",
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.chat_page_export_json))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.chat_page_export_json_desc))
                        },
                    )
                }

                HorizontalDivider()

                OutlinedCard(
                    onClick = {
                        scope.launch {
                            // 原先无 runCatching 且函数内部吞异常 → 写盘失败也报成功。
                            // 现在失败会冒泡，这里统一报错、不上报成功。
                            runCatching {
                                exportToMarkdown(context, conversation, exportMessages)
                            }.onSuccess { deliver(it) }
                                .onFailure {
                                    toaster.show(
                                        message = it.message ?: "Export failed",
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        }
                    )
                }

                OutlinedCard(
                    onClick = {
                        // TikZ 导出走 native 编译（阻塞数秒/图），协程化避免卡 UI
                        scope.launch {
                            runCatching {
                                exportToHtml(context, conversation, exportMessages)
                            }.onSuccess { deliver(it) }
                                .onFailure {
                                    toaster.show(
                                        message = it.message ?: "Export failed",
                                        type = ToastType.Error,
                                    )
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_html))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_html_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.Earth, contentDescription = null)
                        }
                    )
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                Switch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = exportMessages,
                                                settings = settings,
                                                options = imageExportOptions
                                            )
                                        }.onSuccess { deliverAll(it, "image/png") }
                                            .onFailure {
                                                // 取消必须向上传播，否则关闭 sheet 会被误报为导出失败
                                                if (it is CancellationException) throw it
                                                toaster.show(
                                                    message = "Failed to export image: ${it.message}",
                                                    type = ToastType.Error
                                                )
                                            }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导出为**可导入的自家归档**（zip：`conversation.json` + `attachments/`）。
 *
 * 与 Markdown/HTML/PNG 不同：那三种只出不能回填。**始终 zip**（阶段 2 决策——本功能未发布，
 * 无裸 JSON 的历史包袱），见 `docs/design/design-conversation-import-export.md` §六 阶段 2。
 *
 * 落点 = **留存区 `files/exports`**（原子写入），随后由调用方分享或另存。
 * 文件名清洗非法字符（用字符列表而非正则，避免转义歧义）。
 *
 * 缺失附件**跳过并计数**（导出照常成功）——挡死导出会让人连文字部分都导不出。
 */
private suspend fun exportToConversationArchive(
    context: Context,
    conversation: Conversation,
): ExportedFile {
    val packed = packConversationArchive(context, conversation, ConversationSerializer.export(conversation))
    if (packed.missingUrls.isNotEmpty() || packed.oversizedUrls.isNotEmpty()) {
        Log.i(
            TAG,
            "exportConversationArchive: skipped ${packed.missingUrls.size} missing, " +
                "${packed.oversizedUrls.size} oversized attachment(s)",
        )
    }
    return writeExportBytes(
        displayName = "${sanitizeExportFileName(conversation.title)}.zip",
        bytes = packed.bytes,
        mimeType = "application/zip",
    )
}

/** 供 UI 在打包前决定是否提示软上限（只 stat，不读内容；内部切 IO 线程）。 */
private suspend fun conversationAttachmentPlan(
    context: Context,
    conversation: Conversation,
): AttachmentPlan = planAttachments(context, conversation)

/** 文件系统非法字符（导出文件名清洗用）。 */
private val ILLEGAL_FILENAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/** 清洗导出文件名（去非法字符、限长；空标题回退 `conversation`）。 */
private fun sanitizeExportFileName(title: String): String = title
    .ifBlank { "conversation" }
    .map { ch -> if (ch in ILLEGAL_FILENAME_CHARS || ch.code < 0x20) '_' else ch }
    .joinToString("")
    .trim()
    .take(60)
    .ifBlank { "conversation" }

/** 一次导出在留存区的落点。 */
private data class ExportedFile(
    val file: File,
    val displayName: String,
    val mimeType: String,
)

/**
 * 把导出内容**原子地写进留存区**（`files/exports`）。
 *
 * **为什么不是 `cache/temp`**：那里由 [RikkaHubApp.deleteTempFiles] 在每次冷启动整目录清空，
 * 留存物活不过一次重启——而「先导出再继续」的语义是"留一份"，必须跨重启。
 *
 * **为什么不用 `saveManagedFromBytes`**：后者直接写最终名；导出会复用同名（JSON 按标题命名），
 * "先删旧再写新"在中途失败时会毁掉前一份。[FilesManager.saveExportAtomically] 改为
 * "写临时文件 + rename"，故最终名始终完好。见 `docs/design/design-export-retention.md` §4.2。
 */
private suspend fun writeExportBytes(
    displayName: String,
    bytes: ByteArray,
    mimeType: String,
): ExportedFile {
    val filesManager = getKoin().get<FilesManager>()
    val entity = filesManager.saveExportAtomically(
        bytes = bytes,
        displayName = displayName,
        mimeType = mimeType,
    )
    return ExportedFile(
        file = filesManager.getFile(entity),
        displayName = displayName,
        mimeType = mimeType,
    )
}

private suspend fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
): ExportedFile {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"

    val sb = buildAnnotatedString {
        append("# ${conversation.title}\n\n")
        append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

        messages.forEach { message ->
            val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
            append("$role:\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        append(part.text)
                        appendLine()
                    }

                    is UIMessagePart.Image -> {
                        append("![Image](${part.encodeBase64().getOrNull()?.base64})")
                        appendLine()
                    }

                    is UIMessagePart.Reasoning -> {
                        part.reasoning.lines()
                            .filter { it.isNotBlank() }
                            .map { "> $it" }
                            .forEach {
                                append(it)
                            }
                        appendLine()
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        append("**Tool**: `${part.toolName}`")
                        appendLine()
                        if (part.toolCallId.isNotBlank()) {
                            append("- Call ID: `${part.toolCallId}`")
                            appendLine()
                        }

                        append("Input:")
                        appendLine()
                        append("```json")
                        appendLine()
                        append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                        appendLine()
                        append("```")
                        appendLine()

                        if (part.output.isNotEmpty()) {
                            append("Output:")
                            appendLine()
                            part.output.forEach { outputPart ->
                                when (outputPart) {
                                    is UIMessagePart.Text -> {
                                        append("```text")
                                        appendLine()
                                        append(outputPart.text)
                                        appendLine()
                                        append("```")
                                        appendLine()
                                    }

                                    is UIMessagePart.Reasoning -> {
                                        outputPart.reasoning.lines()
                                            .filter { it.isNotBlank() }
                                            .forEach {
                                                append("> $it")
                                                appendLine()
                                            }
                                    }

                                    is UIMessagePart.Image -> {
                                        append("![Tool Image](${outputPart.encodeBase64().getOrNull()?.base64})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Document -> {
                                        append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Video -> {
                                        append("[Video](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Audio -> {
                                        append("[Audio](${outputPart.url})")
                                        appendLine()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        appendLine()
                    }

                    else -> {}
                }
            }
            appendLine()
            append("---")
            appendLine()
        }
    }

    // **不再吞异常**（原实现 catch + printStackTrace 导致卡片误报「已导出」，设计稿 §1.2 ①）；
    // 也**不再自己分享**——统一由调用方按用途分流（分享面板 / 另存对话框）
    return writeExportBytes(
        displayName = filename,
        bytes = sb.toString().toByteArray(Charsets.UTF_8),
        mimeType = "text/markdown",
    )
}

/** 匹配 ```tikz / ```tikzcd 等围栏代码块 */
private val TIKZ_FENCE_REGEX = Regex("""```(?:tikz|tikzpicture|tikzcd|tikztiming)[^\n]*\n([\s\S]*?)```""")

/** $$...$$ 块内嵌的 TikZ 图表特征（与 MathBlock.isDiagramLatex 一致） */
private val DIAGRAM_LATEX_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),
    Regex("""\\begin\{axis\}"""),
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

private val DOLLAR_TIKZ_REGEX = Regex("""\$\$([\s\S]*?)\$\$""")

/**
 * native 编译 TikZ → 内嵌 <svg>（缓存优先；与聊天渲染同链路 mupdf）。
 * 导出与 HTML/PNG 共用：PNG 导出前依赖它把图预编译落入缓存（一次性捕获拿不到异步结果）。
 */
private suspend fun compileTikzToSvg(context: Context, code: String): String? =
    withContext(Dispatchers.IO) {
        val trimmed = code.trim()
        val cached = TikzSvgCache.get(context, trimmed)
        if (cached != null) {
            cached
        } else if (MupdfSvgBridge.available) {
            // mupdf 链优先：tectonic PDF（内嵌字体）→ <text> 保留型 SVG（与聊天同链路）
            TectonicNative.compileIsolated(context, trimmed)
                ?.let { MupdfSvgBridge.pdfToSvg(it) }
                ?.also { TikzSvgCache.put(context, trimmed, it) }
        } else {
            null
        }
    }

/** PNG 导出分页捕获参数：单页高度（每页一张 PNG，一次分享多张；不受单 Bitmap 高度上限约束） */
private val PAGE_CAPTURE_HEIGHT_PX = 15_000

/** 页数上限（防异常输入导致页数爆炸；15000px/页 ≈ 300 万 px 内容） */
private const val MAX_EXPORT_PAGES = 200

/** 整页是否纯背景色：逐行精确比较（真空白页每行同色；含任何内容必有色差行） */
private fun isBlankPage(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return true
    val row = IntArray(w)
    val ref = IntArray(w)
    bitmap.getPixels(ref, 0, w, 0, 0, w, 1)
    for (y in 1 until h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        if (!row.contentEquals(ref)) return false
    }
    return true
}

/** 收集消息文本中的 TikZ 代码块（$$ 内嵌图表 + ```tikz 围栏，与 renderMarkdownWithTikz 同口径） */
private fun collectTikzCodeBlocks(messages: List<UIMessage>): List<String> =
    messages.flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
            val result = mutableListOf<String>()
            DOLLAR_TIKZ_REGEX.findAll(part.text).forEach { m ->
                val latex = m.groupValues[1]
                if (DIAGRAM_LATEX_PATTERNS.any { it.containsMatchIn(latex) }) {
                    result.add(latex.trim())
                }
            }
            TIKZ_FENCE_REGEX.findAll(part.text).forEach { m ->
                result.add(m.groupValues[1].trim())
            }
            result
        }
    }

/**
 * 将 markdown 中的 TikZ（代码围栏 + $$ 内嵌图表）渲染为内嵌 <svg>（矢量），
 * 其余 markdown 交给 generateMarkdownHtml。用占位符绕过 flexmark 对原始 HTML 的转义。
 *
 * TikZ 走 native 引擎（与聊天渲染同链路：XDV→dvisvgm SVG），须在 IO 线程
 * 调用（内部阻塞等待隔离进程编译，~3-6s/图）。
 */
private suspend fun renderMarkdownWithTikz(context: Context, content: String): String {
    // replace 回调非 suspend：先收集待编译 TikZ 源码，占位标记后统一走 native
    val pendingRaw = mutableListOf<String>()

    // 1. 处理 $$...$$ 块内嵌的 TikZ 图表（\begin{tikzpicture}/\begin{tikzcd} 等）
    val afterDollar = DOLLAR_TIKZ_REGEX.replace(content) { m ->
        val latex = m.groupValues[1]
        if (DIAGRAM_LATEX_PATTERNS.any { it.containsMatchIn(latex) }) {
            val placeholder = "%%TIKZ_PH_${pendingRaw.size}%%"
            pendingRaw.add(latex)
            placeholder
        } else {
            m.value // 普通数学公式，保留
        }
    }

    // 2. 处理 ```tikz 围栏
    val afterFence = TIKZ_FENCE_REGEX.replace(afterDollar) { m ->
        val placeholder = "%%TIKZ_PH_${pendingRaw.size}%%"
        pendingRaw.add(m.groupValues[1])
        placeholder
    }

    // 3. 统一 native 编译（XDV→dvisvgm SVG，与聊天渲染同链路；IO 线程阻塞 ~3-6s/图）
    //    先查内容寻址缓存（聊天里渲染过的图直接复用，免编译）
    val html = generateMarkdownHtml(afterFence)
    var result = html
    pendingRaw.forEachIndexed { i, code ->
        val trimmed = code.trim()
        val svg = compileTikzToSvg(context, code)
        if (svg != null) {
            result = result.replace("%%TIKZ_PH_${i}%%", svg)
        } else {
            // native 失败：占位符还原为原始围栏（导出可见源码，不残留占位文本）
            result = result.replace("%%TIKZ_PH_${i}%%", "```tikz\n$trimmed\n```")
        }
    }
    return result
}

private suspend fun exportToHtml(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
): ExportedFile {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.html"

    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<style>\n")
    sb.append(
        """
        body { font-family: -apple-system, 'Segoe UI', Roboto, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background: #f5f6f8; color: #1a1a1a; line-height: 1.6; }
        h1 { font-size: 1.5em; margin-bottom: 4px; }
        .meta { color: #888; font-size: 0.85em; margin-bottom: 24px; }
        .message { border-radius: 10px; padding: 12px 16px; margin: 12px 0; }
        .message.user { background: #e8f0fe; border-left: 4px solid #4285f4; }
        .message.assistant { background: #ffffff; border-left: 4px solid #b0b0b0; box-shadow: 0 1px 2px rgba(0,0,0,0.05); }
        .role { font-weight: 700; margin-bottom: 6px; font-size: 0.9em; }
        .message.user .role { color: #1a5dc7; }
        .message.assistant .role { color: #555; }
        .tool { background: #fafafa; border: 1px solid #e5e5e5; border-radius: 6px; padding: 8px 12px; margin: 8px 0; }
        .tool-name { font-weight: 600; font-family: ui-monospace, monospace; font-size: 0.9em; }
        details { margin: 8px 0; background: #fff8e1; border-radius: 6px; padding: 8px 12px; }
        summary { cursor: pointer; font-weight: 600; color: #b26a00; }
        img { max-width: 100%; border-radius: 6px; }
        pre { background: #f0f0f0; padding: 10px; border-radius: 6px; overflow-x: auto; }
        code { background: #f0f0f0; padding: 2px 4px; border-radius: 4px; font-family: ui-monospace, monospace; font-size: 0.9em; }
        pre code { background: transparent; padding: 0; }
        """.trimIndent()
    )
    sb.append("\n</style>\n</head>\n<body>\n")

    sb.append("<h1>").append(conversation.title.escapeHtml()).append("</h1>\n")
    sb.append("<p class=\"meta\">Exported on ").append(LocalDateTime.now().toLocalString().escapeHtml()).append("</p>\n")

    messages.forEach { message ->
        val isUser = message.role == MessageRole.USER
        val roleClass = if (isUser) "user" else "assistant"
        val roleLabel = if (isUser) "User" else "Assistant"
        sb.append("<div class=\"message ").append(roleClass).append("\">\n")
        sb.append("<div class=\"role\">").append(roleLabel).append("</div>\n")
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    sb.append(renderMarkdownWithTikz(context, part.text))
                    sb.append('\n')
                }

                is UIMessagePart.Image -> {
                    part.encodeBase64().getOrNull()?.let { enc ->
                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"image\" />\n")
                    }
                }

                is UIMessagePart.Reasoning -> {
                    sb.append("<details>\n<summary>").append("思考过程".escapeHtml()).append("</summary>\n")
                    sb.append(generateMarkdownHtml(part.reasoning))
                    sb.append("\n</details>\n")
                }

                is UIMessagePart.Tool -> {
                    sb.append("<div class=\"tool\">\n")
                    sb.append("<div class=\"tool-name\">").append(part.toolName.escapeHtml()).append("</div>\n")
                    if (part.toolCallId.isNotBlank()) {
                        sb.append("<div style=\"color:#888;font-size:0.8em;\">Call ID: ").append(part.toolCallId.escapeHtml()).append("</div>\n")
                    }
                    sb.append("<div><strong>Input</strong></div>\n")
                    sb.append("<pre>").append(JsonInstantPretty.encodeToString(part.inputAsJson()).escapeHtml()).append("</pre>\n")
                    if (part.output.isNotEmpty()) {
                        sb.append("<div><strong>Output</strong></div>\n")
                        part.output.forEach { outputPart ->
                            when (outputPart) {
                                is UIMessagePart.Text -> {
                                    sb.append("<pre>").append(outputPart.text.escapeHtml()).append("</pre>\n")
                                }

                                is UIMessagePart.Reasoning -> {
                                    sb.append(generateMarkdownHtml(outputPart.reasoning)).append('\n')
                                }

                                is UIMessagePart.Image -> {
                                    outputPart.encodeBase64().getOrNull()?.let { enc ->
                                        sb.append("<img src=\"").append(enc.base64).append("\" alt=\"tool image\" />\n")
                                    }
                                }

                                is UIMessagePart.Document -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Document: ").append(outputPart.fileName.escapeHtml()).append("</a>\n")
                                }

                                is UIMessagePart.Video -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Video</a>\n")
                                }

                                is UIMessagePart.Audio -> {
                                    sb.append("<a href=\"").append(outputPart.url.escapeHtml()).append("\">Audio</a>\n")
                                }

                                else -> {}
                            }
                        }
                    }
                    sb.append("</div>\n")
                }

                else -> {}
            }
        }
        sb.append("</div>\n")
    }
    sb.append("</body>\n</html>")

    // 同 Markdown：不吞异常、不自行分享
    return writeExportBytes(
        displayName = filename,
        bytes = sb.toString().toByteArray(Charsets.UTF_8),
        mimeType = "text/html",
    )
}

private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions()
): List<File> {
    val timestamp = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
        // 原先此处只弹 Toast 后 **静默 return** —— 调用方随即报「已导出」并触发续作，
        // 又一处假成功。改为抛错，由调用方统一走失败分支。
        ?: error("Failed to get activity for image export")

    // PNG 是逐页捕获：TikZ 图表须预编译落入缓存（TikzJax 同步读缓存出图），
    // 否则异步渲染在捕获瞬间未就绪 → 该页缺图（HTML 走同步管线不受影响）
    collectTikzCodeBlocks(messages).forEach { code ->
        compileTikzToSvg(context, code)
    }

    val exportContent: @Composable () -> Unit = {
        CompositionLocalProvider(LocalSettings provides settings) {
            ExportedChatImage(
                conversation = conversation,
                messages = messages,
                options = options
            )
        }
    }
    val widthDp = 540.dp

    // 长对话高度可能超过单 Bitmap 硬件上限（16384~32768px）——分页每页一张 PNG，
    // 一次分享全部（ACTION_SEND_MULTIPLE）。UNSPECIFIED 测量（无高度约束）得到内容
    // 真实全高，按页高切页；最后一页取剩余高度（自然不产空白页）。
    val totalHeightPx = composer.measureContentHeight(
        activity = activity,
        width = widthDp,
        screenDensity = density,
        content = exportContent,
    )
    val pageCount = if (totalHeightPx > 0) {
        ((totalHeightPx + PAGE_CAPTURE_HEIGHT_PX - 1) / PAGE_CAPTURE_HEIGHT_PX)
            .coerceAtMost(MAX_EXPORT_PAGES)
    } else {
        1
    }

    val files = mutableListOf<File>()
    try {
        for (pageIndex in 0 until pageCount) {
            val offsetPx = pageIndex * PAGE_CAPTURE_HEIGHT_PX
            val pageHeightPx = minOf(PAGE_CAPTURE_HEIGHT_PX, totalHeightPx - offsetPx).coerceAtLeast(1)
            val pageBitmap = composer.composableToBitmapPage(
                activity = activity,
                width = widthDp,
                pageHeight = density.run { pageHeightPx.toDp() },
                offset = density.run { offsetPx.toDp() },
                screenDensity = density,
                content = exportContent,
            )
            val name = "$timestamp-p${pageIndex + 1}.png"
            files += writeBitmapToRetention(context, pageBitmap, name)
            // 每页分别存入相册（独立文件名）——相册是 PNG 的主留存路径（见设计稿 §六.5）
            context.exportImage(activity, pageBitmap, name)
            pageBitmap.recycle()
        }

        // 兜底补页：图片/Mermaid 等异步内容就绪后实际高度可能高于量测值 → 量测偏小会截掉尾部。
        // 继续逐页捕获，直到出现整页纯背景为止（逐行精确判定，误判率为零）
        var overflowOffsetPx = pageCount * PAGE_CAPTURE_HEIGHT_PX
        while (files.size < MAX_EXPORT_PAGES) {
            val overflow = composer.composableToBitmapPage(
                activity = activity,
                width = widthDp,
                pageHeight = density.run { PAGE_CAPTURE_HEIGHT_PX.toDp() },
                offset = density.run { overflowOffsetPx.toDp() },
                screenDensity = density,
                content = exportContent,
            )
            if (isBlankPage(overflow)) {
                overflow.recycle()
                break
            }
            val name = "$timestamp-p${files.size + 1}.png"
            files += writeBitmapToRetention(context, overflow, name)
            context.exportImage(activity, overflow, name)
            overflow.recycle()
            overflowOffsetPx += PAGE_CAPTURE_HEIGHT_PX
        }

        if (files.isEmpty()) error("No pages rendered")

        return files
    } catch (e: CancellationException) {
        // 取消必须向上传播（调用方据此区分"用户返回"与"导出失败"）
        throw e
    } catch (e: Exception) {
        // **不再吞异常**：写盘/渲染失败必须冒泡，否则卡片会误报「已导出」（设计稿 §1.2 ①）
        Log.e(TAG, "exportToImage failed", e)
        throw e
    }
}

/** 把一页 PNG 原子写入留存区（返回落点文件），供后续分享。 */
private suspend fun writeBitmapToRetention(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
): File {
    val bytes = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        out.toByteArray()
    }
    return writeExportBytes(
        displayName = displayName,
        bytes = bytes,
        mimeType = "image/png",
    ).file
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ImageExportOptions = ImageExportOptions()
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = LocalDateTime.now().toLocalString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEach { message ->
                        ExportedChatMessage(
                            message = message,
                            options = options,
                            prevMessage = messages.getOrNull(messages.indexOf(message) - 1)
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    prevMessage: UIMessage? = null,
    options: ImageExportOptions = ImageExportOptions()
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) }
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    // 导出是静态图：解说也进思考链一起展示（export 内 collapse 关掉，全部步骤都渲染），
    // 角色守卫照旧生效，用户消息里掺的工具调用不会被折进链里。
    val groupedParts = remember(message.parts, message.role) {
        message.parts.groupMessageParts(role = message.role, wholeTurnFolding = true)
    }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }

                                    // ServerTool 已从正文常驻状态条移进思考链（本仓无 isFinished/FAILED）
                                    is ThinkingStep.ServerToolStep -> {
                                        ChatMessageServerToolStep(tool = step.tool)
                                    }

                                    // 过程解说：导出图里也是一行可读的说明
                                    is ThinkingStep.NarrationStep -> {
                                        ChatMessageNarrationStep(text = step.text)
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ChartBlock -> {
                        val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                        spec?.let { ChartCard(spec = it) }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.role == MessageRole.USER) {
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(part.url)
                                        .allowHardware(false)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelIcon) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AutoAIIcon(
                name = iconLabel,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = iconLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> HugeIcons.Book04
                        "delete" -> HugeIcons.Book02
                        else -> HugeIcons.Wrench01
                    }

                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}

/** 多文件一起分享：长对话分页导出多张 PNG 时一次带全部 */
private fun shareFiles(context: Context, uris: List<Uri>, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
        type = mimeType
        putParcelableArrayListExtra(
            android.content.Intent.EXTRA_STREAM,
            ArrayList(uris)
        )
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}
