package app.androidrik.rikkahub.ui.pages.chat

import com.composables.icons.lucide.Import

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.DrawerDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.appSkinPanelBackground
import app.androidrik.rikkahub.ui.theme.shape
import app.androidrik.rikkahub.ui.theme.ink
import app.androidrik.rikkahub.ui.theme.border
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUpDown
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Folder02
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.TransactionHistory
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.Folder
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.ui.components.ai.AssistantPicker
import app.androidrik.rikkahub.ui.components.ui.BackupReminderCard
import app.androidrik.rikkahub.ui.components.ui.Greeting
import app.androidrik.rikkahub.ui.components.ui.Tooltip
import app.androidrik.rikkahub.ui.components.ui.UIAvatar
import androidx.compose.ui.draw.clip
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.context.Navigator
import com.dokar.sonner.ToastType
import app.androidrik.rikkahub.ui.hooks.EditStateContent
import app.androidrik.rikkahub.ui.hooks.readBooleanPreference
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.ui.modifier.onClick
import app.androidrik.rikkahub.ui.navigation.navigateToChatPage
import app.androidrik.rikkahub.utils.toDp
import app.androidrik.workspace.WorkspaceFileEntry
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val repo = koinInject<ConversationRepository>()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    // 自定义顺序是否生效（未置顶档已有位置值）——决定置顶提示文案与「恢复默认顺序」可用性
    val customOrderActive by drawerVm.customOrderActive.collectAsStateWithLifecycle()
    // 「编辑顺序」模式（设计稿 §一.4）：进入后长按即拖、退出即锁定；不持久化（每次进来都是浏览态）
    var editOrderMode by rememberSaveable { mutableStateOf(false) }
    var showResetOrderDialog by rememberSaveable { mutableStateOf(false) }
    val editableConversations by drawerVm.editableConversations.collectAsStateWithLifecycle(initialValue = emptyList())
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 抽屉面板：当前助手绑定工作区时提供「会话 / 文件」切换（文件段 = 工作区文件浏览）
    val boundWorkspaceId = remember(settings.assistantId) { drawerVm.boundWorkspaceId }
    var showFilesPanel by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(boundWorkspaceId) {
        if (boundWorkspaceId == null) showFilesPanel = false
    }

    // 文件面板状态（目录/搜索）
    val workspaceFiles by drawerVm.workspaceFiles.collectAsStateWithLifecycle()
    val filesPath by drawerVm.filesPath.collectAsStateWithLifecycle()
    val filesSearchQuery by drawerVm.filesSearchQuery.collectAsStateWithLifecycle()
    val filesSearchResults by drawerVm.filesSearchResults.collectAsStateWithLifecycle()
    var filesSearchInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(filesPath) { drawerVm.loadWorkspaceFiles() }
    // 每次进入文件面板都失效并重载：缓存只服务于「面板内目录导航秒开」，
    // 而 AI 随时可能刚往工作区写过文件，不刷新会让用户看到过期目录。
    LaunchedEffect(showFilesPanel, boundWorkspaceId) {
        if (showFilesPanel && boundWorkspaceId != null) {
            drawerVm.invalidateFilesCache()
            drawerVm.loadWorkspaceFiles()
        }
    }
    // 搜索防抖 250ms（避免每次按键都跑一遍 BFS 遍历）：
    // LaunchedEffect 在 key 变化时会取消上一个协程，delay 因此天然实现防抖。
    LaunchedEffect(filesSearchInput) {
        delay(SEARCH_DEBOUNCE_MS)
        drawerVm.searchWorkspaceFiles(filesSearchInput)
    }

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 对话分享/导出状态
    var showExportSheet by remember { mutableStateOf(false) }
    var conversationToShare by remember { mutableStateOf<Conversation?>(null) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }

    // Menu popup 状态


    // 矮屏（手表、横屏手机）上顶部固定会把会话列表挤没，改为随会话列表一起滚动
    // （吸收上游 9ce1ae1 / #2164）
    val windowHeight = LocalWindowInfo.current.containerDpSize.height
    val conversationPanelVisible = !(showFilesPanel && boundWorkspaceId != null)
    val headerInList = windowHeight < 640.dp && conversationPanelVisible && !editOrderMode

    // 抽屉顶部：备份提醒、用户信息、常用入口（历史 / 媒体创作 / 翻译）、面板切换
    val drawerHeader: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = "Edit",
                            modifier = Modifier
                                .onClick {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                                .size(LocalTextStyle.current.fontSize.toDp())
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            DrawerActions(navController = navController)

            // 助手绑定工作区时提供「会话 / 文件」面板切换
            if (boundWorkspaceId != null) {
                DrawerPanelSwitcher(
                    showFiles = showFilesPanel,
                    onSwitch = { showFilesPanel = it },
                )
            }

            if (conversationPanelVisible) {
                // 「排序」入口（设计稿 §四.1）：会话面板顶部，与文件夹条同层
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editOrderMode = !editOrderMode }) {
                        Icon(
                            imageVector = HugeIcons.ArrowUpDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.conversation_order_entry))
                    }
                }

                FolderBar(
                    folders = folders,
                    selectedFolderId = selectedFolderId,
                    onSelect = { drawerVm.selectFolder(it) },
                    onCreate = { showCreateFolderDialog = true },
                    onRename = { folderToRename = it },
                    onDelete = { folderToDelete = it },
                )
            }
        }
    }

    val drawerPanel = LocalAppSkin.current.skin.drawer.panel
    val drawerShape = drawerPanel.shape(DrawerDefaults.shape)
    val drawerInk = drawerPanel.ink(MaterialTheme.colorScheme.onSurface, DrawerDefaults.modalContainerColor)
    val drawerBorder = drawerPanel.border()
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp).appSkinPanelBackground(drawerPanel, DrawerDefaults.modalContainerColor, drawerInk, drawerShape)
            .then(if (drawerBorder != null) Modifier.border(drawerBorder, drawerShape) else Modifier),
        drawerShape = drawerShape,
        drawerContainerColor = if (drawerPanel.background == null) DrawerDefaults.modalContainerColor else Color.Transparent,
        drawerContentColor = drawerInk,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = (drawerPanel.paddingHorizontalDp ?: 8f).dp, vertical = (drawerPanel.paddingVerticalDp ?: 8f).dp),
            verticalArrangement = Arrangement.spacedBy((drawerPanel.gapDp ?: 8f).dp),
        ) {
            if (!headerInList) drawerHeader()

            if (showFilesPanel && boundWorkspaceId != null) {
                WorkspaceFilesPanel(
                    workspaceId = boundWorkspaceId.toString(),
                    files = workspaceFiles,
                    path = filesPath,
                    searchQuery = filesSearchInput,
                    searchResults = filesSearchResults,
                    onSearchChange = { filesSearchInput = it },
                    onEnterDir = drawerVm::enterFilesDir,
                    onUpDir = drawerVm::navigateFilesUp,
                    onChanged = drawerVm::invalidateFilesCache,
                    navController = navController,
                    modifier = Modifier.weight(1f),
                )
            } else {
                if (editOrderMode) {
                    EditConversationOrderList(
                        conversations = editableConversations,
                        onPersistMove = { movedId, pinned, beforeId, afterId ->
                            drawerVm.applyManualOrder(movedId, pinned, beforeId, afterId)
                        },
                        onExit = { editOrderMode = false },
                        onResetOrder = { showResetOrderDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else ConversationList(
                current = current,
                showLastActive = customOrderActive,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                header = if (headerInList) drawerHeader else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRegenerateTitle = { conversation ->
                    // #1975：标题模型没配好时服务会报错误卡（带「检查标题模型设置」跳转），
                    // 但错误卡渲染在聊天页、此刻被抽屉挡着 ⇒ 这里补一条即时 toast。
                    scope.launch {
                        if (!vm.regenerateTitle(conversation, force = true)) {
                            toaster.show(
                                resources.getString(R.string.error_title_model_unconfigured),
                                type = ToastType.Warning,
                            )
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController)
                        }
                    }
                },
                onPin = {
                    val willPin = !it.isPinned
                    vm.updatePinnedStatus(it)
                    // 置顶**保留原 sortOrder**（设计稿 §一.6）：用户既不惊讶于"置顶后没跑到
                    // 最前"，也知道取消置顶会回到原位置。只在自定义顺序生效时提示——
                    // 自动模式下置顶行为与历史一致，多一句反而干扰。
                    if (customOrderActive) {
                        toaster.show(
                            resources.getString(
                                if (willPin) R.string.conversation_pin_keeps_order
                                else R.string.conversation_unpin_restores_position
                            )
                        )
                    }
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                },
                onToggleMemory = {
                    val willDisable = !it.memoryDisabled
                    vm.setMemoryDisabled(it, willDisable)
                    // 菜单项关掉后看不出状态，故补一句即时反馈（与置顶提示同款）
                    toaster.show(
                        resources.getString(
                            if (willDisable) R.string.chat_memory_disabled_toast_on
                            else R.string.chat_memory_disabled_toast_off
                        )
                    )
                },
                onFreezeMemory = {
                    // 会话级固化：只让这一条会话采纳当前锚点集合（见 docs/design/
                    // design-memory-injection-tiering.md §5）。动作没有可见结果，故给作用面反馈。
                    scope.launch {
                        val count = vm.freezeMemoryAnchors(it)
                        toaster.show(
                            message = resources.getString(R.string.chat_memory_freeze_toast, count)
                        )
                    }
                },
                onShare = {
                    // 列表项是轻量摘要（messageNodes 为空），导出前加载完整 conversation
                    scope.launch {
                        val full = repo.getConversationById(it.id) ?: it
                        conversationToShare = full
                        showExportSheet = true
                    }
                }
            )
            } // else：会话面板（FolderBar + ConversationList）

            // 助手选择器
            AssistantPicker(
                settings = settings,
                onUpdateSettings = {
                    val updateJob = vm.updateSettings(it)
                    scope.launch {
                        updateJob.join()
                        val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                            Uuid.random()
                        } else {
                            repo.getConversationsOfAssistant(it.assistantId)
                                .first()
                                .firstOrNull()
                                ?.id ?: Uuid.random()
                        }
                        navigateToChatPage(navigator = navController, chatId = id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    val currentAssistantId = settings.assistantId
                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                }
            )

            DrawerBottomActions(navController = navController)
        }
    }

    // 「恢复默认顺序」确认（破坏性且不可撤销：清空该档手工顺序 ⇒ 回到按更新时间）
    if (showResetOrderDialog) {
        AlertDialog(
            onDismissRequest = { showResetOrderDialog = false },
            title = { Text(stringResource(R.string.conversation_order_reset_confirm_title)) },
            text = { Text(stringResource(R.string.conversation_order_reset_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    drawerVm.resetCustomOrder()
                    showResetOrderDialog = false
                }) {
                    Text(stringResource(R.string.conversation_order_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetOrderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text(stringResource(R.string.chat_page_create_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(resources.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 对话分享/导出 Bottom Sheet（列表页长按入口）
    if (showExportSheet && conversationToShare != null) {
        val shareConversation = conversationToShare!!
        ChatExportSheet(
            visible = showExportSheet,
            onDismissRequest = {
                showExportSheet = false
                conversationToShare = null
            },
            conversation = shareConversation,
            selectedMessages = shareConversation.currentMessages,
        )
    }
}


/** 抽屉「会话 / 文件」面板切换器（助手绑定工作区时显示）。 */
/** 文件面板搜索防抖窗口（避免每次按键触发一次 BFS 遍历）。 */
private const val SEARCH_DEBOUNCE_MS = 250L


private fun formatDrawerFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1fKB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
