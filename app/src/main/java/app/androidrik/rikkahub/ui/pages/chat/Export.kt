package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import android.net.Uri
import java.io.File
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import app.androidrik.ai.ui.UIMessage
import com.dokar.sonner.ToastType
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.export.effectiveExportDisplayConfig
import app.androidrik.rikkahub.data.export.previewExportModelLabel
import app.androidrik.rikkahub.data.export.AttachmentPlan
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.ExportDisplaySchemeSheet
import app.androidrik.rikkahub.utils.fileProviderUri
import app.androidrik.rikkahub.utils.shareFile
import app.androidrik.rikkahub.utils.shareFiles
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

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
    /**
     * 本会话导出显示覆盖的写入回调（`null` = 恢复跟随全局）。
     *
     * 传 null 时不显示「本会话导出显示」入口（抽屉/列表的快捷导出没有会话上下文写入口，
     * 它们继续只读全局配置）。
     */
    onUpdateExportDisplayConfig: ((ExportDisplayConfig?) -> Unit)? = null,
    /**
     * 按消息覆盖导出显示的写入回调（整张 `messageId → config` map 替换；空 map = 全部清除）。
     *
     * 传 null 时同样不显示「按消息覆盖」入口（抽屉/列表的快捷导出没有会话上下文写入口）。
     */
    onUpdateExportDisplayOverrides: ((Map<String, ExportDisplayConfig>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
    // 本会话导出显示的编辑面板（不新建第二个 bottom sheet：默认值从会话覆盖/全局配置起步）
    var showSessionDisplayEditor by remember { mutableStateOf(false) }
    // 按消息覆盖的多选面板
    var showMessageOverrideSheet by remember { mutableStateOf(false) }
    // 生效配置：会话覆盖优先，否则全局（三处导出共用同一个决策点）
    val displayConfig = effectiveExportDisplayConfig(
        conversationOverride = conversation.exportDisplayConfig,
        global = settings.exportDisplayConfig,
    )
    // 行内摘要（按示例值预览；「不显示」档给固定文案，避免空白行）
    val displayConfigSummary = previewExportModelLabel(displayConfig)
        .ifBlank { stringResource(R.string.setting_display_page_export_model_preview_hidden) }
    // 会话所属助手名（按消息覆盖面板的 {assistant} 文案用；导出器内部各算一次，保持同名）
    val assistantName = remember(settings.assistants, conversation.assistantId) {
        settings.assistants
            .find { it.id == conversation.assistantId }
            ?.name
            ?.takeIf { it.isNotBlank() }
    }
    // 已覆盖的消息数：只数「当前会导出的消息」，悬空键（被删的分支）不算
    val overriddenMessageCount = remember(selectedMessages, includeAllVersions, conversation) {
        val messages = if (includeAllVersions) conversation.messageNodes.flatMap { it.messages }
        else selectedMessages
        messages.count { conversation.exportDisplayOverrides.containsKey(it.id.toString()) }
    }
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = pendingSave
        pendingSave = null
        if (uri == null || target == null) {
            // **取消另存 = 不续作破坏性动作**（内容已躺在留存区，用户随时可在
            // 「设置 → 文件管理 → 导出」找回）。见设计稿 §二 决策 1。
            toaster.show(
                message = resources.getString(R.string.chat_page_export_save_cancelled),
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
                toaster.show(resources.getString(R.string.chat_page_export_saved), type = ToastType.Success)
                // **顺序关键**：onExported 必须先于 onDismissRequest。
                // ChatPage 的 onDismissRequest 会清掉 exportThenRegenerateFor，
                // 而 onExported 正是读它来续作 scope B —— 反了则续作静默失效。
                onExported?.invoke()
                onDismissRequest()
            }.onFailure {
                toaster.show(
                    message = resources.getString(R.string.chat_page_export_save_failed, it.message ?: ""),
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
                message = resources.getString(R.string.chat_page_export_saved_to_retention),
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
                message = resources.getString(R.string.chat_page_export_saved_to_retention),
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
                    // 条目变多后（会话级导出显示入口）不再可能一屏放完：滚起来，
                    // 否则底部的「导出为图片」按钮会被挤出屏幕、无法点击
                    .verticalScroll(rememberScrollState())
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

                // 本会话导出显示覆盖（默认跟随全局；只影响本会话的 PNG/Markdown/HTML 渲染）。
                // 抽屉/列表的快捷导出不传回调 ⇒ 不显示本入口（那里没有写回会话的通道）。
                if (onUpdateExportDisplayConfig != null) {
                    OutlinedCard(
                        onClick = { showSessionDisplayEditor = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.chat_page_export_session_display_title))
                            },
                            supportingContent = {
                                // ListItem.supportingContent 只渲染第一个直接子节点 ⇒ Column 包一层
                                Column {
                                    Text(
                                        text = if (conversation.exportDisplayConfig == null) {
                                            stringResource(
                                                R.string.chat_page_export_session_display_follow_global,
                                                displayConfigSummary,
                                            )
                                        } else {
                                            stringResource(
                                                R.string.chat_page_export_session_display_override,
                                                displayConfigSummary,
                                            )
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_page_export_session_display_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                Text(stringResource(R.string.setting_display_page_export_model_edit))
                            },
                        )
                    }
                }

                // 按消息覆盖（多选）：同一个写通道才显示（抽屉/列表快捷导出没有会话上下文）
                if (onUpdateExportDisplayOverrides != null) {
                    OutlinedCard(
                        onClick = { showMessageOverrideSheet = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.chat_page_export_message_override_title))
                            },
                            supportingContent = {
                                Column {
                                    Text(
                                        text = if (overriddenMessageCount > 0) {
                                            stringResource(
                                                R.string.chat_page_export_message_override_count,
                                                overriddenMessageCount,
                                            )
                                        } else {
                                            stringResource(R.string.chat_page_export_message_override_none)
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_page_export_message_override_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                Text(stringResource(R.string.chat_page_export_message_override_manage))
                            },
                        )
                    }
                }

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
                                exportToMarkdown(context, conversation, exportMessages, settings)
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
                                exportToHtml(context, conversation, exportMessages, settings)
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

    // ---- 本会话导出显示的编辑面板 ----
    // 沿用设置页同一套编辑器（模式 / 内置预设 / 自定义模板 / 预览），但：
    // - 不提供「保存为方案」（方案是全局的，会话级覆盖没有这个语义）；
    // - 只有已有覆盖时才提供「恢复跟随全局」（null = 跟随全局）。
    val updateDisplayConfig = onUpdateExportDisplayConfig
    if (showSessionDisplayEditor && updateDisplayConfig != null) {
        ExportDisplaySchemeSheet(
            config = displayConfig,
            titleOverride = stringResource(R.string.chat_page_export_session_display_title),
            descriptionOverride = stringResource(R.string.chat_page_export_session_display_desc),
            onSave = { config ->
                updateDisplayConfig(config)
                showSessionDisplayEditor = false
            },
            onSaveScheme = null,
            onReset = if (conversation.exportDisplayConfig != null) {
                {
                    updateDisplayConfig(null)
                    showSessionDisplayEditor = false
                }
            } else {
                null
            },
            onDismiss = { showSessionDisplayEditor = false },
        )
    }

    // ---- 按消息覆盖（多选）面板 ----
    // 只有可写通道时才有内容可编辑；列表用默认导出消息集合（与三种导出默认口径一致）。
    val updateMessageOverrides = onUpdateExportDisplayOverrides
    if (updateMessageOverrides != null) {
        ExportMessageOverrideSheet(
            visible = showMessageOverrideSheet,
            conversation = conversation,
            messages = exportMessages,
            settings = settings,
            assistantName = assistantName,
            baseDisplayConfig = displayConfig,
            onOverridesChange = updateMessageOverrides,
            onDismissRequest = { showMessageOverrideSheet = false },
        )
    }
}
