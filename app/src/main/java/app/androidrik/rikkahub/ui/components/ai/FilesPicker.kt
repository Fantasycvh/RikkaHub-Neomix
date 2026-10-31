package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.androidrik.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Video01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.ui.components.sketch.SketchDialog
import app.androidrik.rikkahub.ui.components.ui.ExtensionSelector
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionCamera
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.workspace.WorkspaceShellStatus
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    /** 请求打开压缩参数对话框（对话框本身由 ChatPage 渲染：容量面板也要用它）。 */
    onRequestCompress: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    /** 「为此会话创建定时任务」：由 ChatPage 提供（跳转前要先同步落库，见 ChatVM.saveConversationNow） */
    onCreateScheduledTask: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())

    // 新草图：画板确认后落成聊天附件，走和相册导入相同的 upload/managed_files 持久化路径
    val sketchSaveFailed = stringResource(R.string.sketch_save_failed)
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    var showSketch by remember { mutableStateOf(false) }
    if (showSketch) {
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result ->
                showSketch = false
                scope.launch {
                    val uri = filesManager.createChatFileBySketch(result)
                    if (uri == null) {
                        toaster.show(
                            message = sketchSaveFailed,
                            type = ToastType.Error,
                        )
                        return@launch
                    }
                    state.addImages(listOf(uri))
                    onDismiss()
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            SketchButton(onClick = { showSketch = true })

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        if (workspaces.isNotEmpty()) {
            WorkspacePickerListItem(
                assistant = assistant,
                conversation = conversation,
                workspaces = workspaces,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateConversation = onUpdateConversation,
                onNavigateToDetail = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(id))
                },
                onNavigateToTerminal = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceTerminal(id))
                },
                onNavigateToManage = {
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
            )
        }

        if (settings.mcpServers.isNotEmpty()) {
            McpPickerListItem(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
            )
        }

        // Extensions (Quick Messages + Prompt Injections + Skills)
        val modeAndLorebookCount =
            if (assistant.allowConversationPromptInjection) {
                conversation.modeInjectionIds.size + conversation.lorebookIds.size
            } else {
                assistant.modeInjectionIds.size + assistant.lorebookIds.size
            }
        val activeCount =
            assistant.quickMessageIds.size +
                modeAndLorebookCount +
                assistant.enabledSkills.size
        CompactPickerListItem(
            icon = HugeIcons.Package,
            text = stringResource(R.string.assistant_page_tab_extensions),
            onClick = { onShowInjectionSheetChange(true) },
        ) {
            if (activeCount > 0) {
                Text(
                    text = activeCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        // Compress History Button
        CompactPickerListItem(
            icon = HugeIcons.Package01,
            text = stringResource(R.string.chat_page_compress_context),
            onClick = { onRequestCompress() },
        ) {
            if (conversation.messageNodes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        // 拼装后提示词预览（只读）：显示这次请求实际会发什么、按来源分段。
        // 与真实请求共用同一条组装与 transformer 链，故不会漂移。
        CompactPickerListItem(
            icon = HugeIcons.Search01,
            text = stringResource(R.string.prompt_inspect_title),
            onClick = {
                navController.navigate(Screen.PromptInspect(conversation.id.toString()))
                onDismiss()
            },
        )

        // 为本会话创建定时任务（定时任务 P2）：入口从消息列表挪进「+」面板——它和
        // 压缩/提示词预览一样是「对这条会话发起的动作」，不是聊天内容的一部分
        CompactPickerListItem(
            icon = HugeIcons.Time02,
            text = stringResource(R.string.scheduled_task_create_for_conversation),
            onClick = {
                onDismiss()
                onCreateScheduledTask()
            },
        )

        // Workspace CWD
        val boundWorkspace = remember(workspaces, assistant.workspaceId) {
            workspaces.find { it.id == assistant.workspaceId?.toString() }
        }
        if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
            var showCwdSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showCwdSheet = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = conversation.workspaceCwd ?: boundWorkspace?.workspaceDir().orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCwdSheet) {
                WorkspaceCwdPickerSheet(
                    workspaceId = boundWorkspace.id,
                    workspaceDir = boundWorkspace.workspaceDir(),
                    currentCwd = conversation.workspaceCwd,
                    onSelectCwd = { newCwd ->
                        onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    },
                    onDismiss = { showCwdSheet = false },
                )
            }
        }
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
        )
    }

}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    CompactPickerListItem(
        icon = HugeIcons.Codesandbox,
        text = stringResource(R.string.assistant_page_workspace),
        supportingText = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
        onClick = { showSheet = true },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (boundWorkspace != null) {
                IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                    Icon(
                        imageVector = HugeIcons.Settings02,
                        contentDescription = stringResource(R.string.workspace_detail),
                    )
                }
                if (boundWorkspace.supportsTerminal()) {
                    IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.ComputerTerminal01,
                            contentDescription = stringResource(R.string.workspace_terminal),
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    val navigation = remember { ExtensionPickerNavigation() }
    val dismissForManagement = {
        onDismiss()
        onDismissAll()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Detail Back belongs to ExtensionSelector; scrim/swipe still dismiss the sheet.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !navigation.isDetail),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                modifier = Modifier.weight(1f),
                navigation = navigation,
                onNavigateToQuickMessages = {
                    dismissForManagement()
                    navController.navigate(Screen.QuickMessages)
                },
                onNavigateToPrompts = {
                    dismissForManagement()
                    navController.navigate(Screen.Prompts)
                },
                onNavigateToWorldbooks = {
                    dismissForManagement()
                    navController.navigate(Screen.Worldbooks)
                },
                onNavigateToSkills = {
                    dismissForManagement()
                    navController.navigate(Screen.Skills)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
private fun SketchButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.PaintBoard, null)
    }, text = {
        Text(stringResource(R.string.sketch))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

/**
 * 「+」面板的功能行：替代默认 ListItem（其固定最小高度偏大，行数多时面板被撑长）。
 * 行高收到 44dp、图标 20dp，视觉上与 [ListItem] 同款 surfaceContainer + large 圆角。
 */
@Composable
internal fun CompactPickerListItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    /** 覆盖默认图标（如 MCP 同步中显示进度圈） */
    leadingContent: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .padding(start = 12.dp, end = 8.dp)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
