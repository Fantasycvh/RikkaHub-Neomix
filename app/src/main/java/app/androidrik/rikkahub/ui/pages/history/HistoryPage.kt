package app.androidrik.rikkahub.ui.pages.history;

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.theme.CustomColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.navigation.navigateToChatPage
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import me.rerere.hugeicons.stroke.Cancel01

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()

    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)

    // 多选模式（对齐 imggen 页交互）：长按进入，勾选后批量删除，复用 HistoryVM 撤销队列
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var deleting by remember { mutableStateOf(false) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
    }

    fun toggleSelect(id: String) {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    fun deleteSelected() {
        if (deleting || selectedIds.isEmpty()) return
        deleting = true
        scope.launch {
            val ids = selectedIds.toList()
            ids.forEach { id ->
                // 先取完整会话（含 messageNodes），供撤销恢复与附件清理
                val full = vm.getFullConversation(Uuid.parse(id)) ?: return@forEach
                vm.deleteConversation(full)
            }
            deleting = false
            exitSelectionMode()
            val result = snackbarHostState.showSnackbar(
                message = snackMessageDeleted,
                actionLabel = snackMessageUndo,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                // 撤销队列一次性恢复全部待撤销会话（含单条删除被抢占的旧条目）
                vm.undoConversationDeletes()
            }
        }
    }

    GlassTopBarScaffold(displaySetting = LocalSettings.current.displaySetting,
        topBar = {
            if (selectionMode) {
                // 多选模式顶栏：退出 / 已选计数 + 全选 / 删除所选
                TopAppBar(
                    colors = pageGlassTopBarColors(CustomColors.topBarColors),
                    title = {
                        Text(stringResource(R.string.conversation_multi_select_title, selectedIds.size))
                    },
                    navigationIcon = {
                        IconButton(onClick = { exitSelectionMode() }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.conversation_multi_select_exit)
                            )
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            selectedIds.clear()
                            selectedIds.addAll(conversations.map { it.id.toString() })
                        }) {
                            Text(stringResource(R.string.conversation_multi_select_select_all))
                        }
                        TextButton(
                            onClick = { deleteSelected() },
                            enabled = selectedIds.isNotEmpty() && !deleting,
                        ) {
                            Text(
                                text = stringResource(R.string.conversation_multi_select_delete_selected),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = pageGlassTopBarColors(CustomColors.topBarColors),
                    title = {
                        Text(stringResource(R.string.history_page_title))
                    },
                    navigationIcon = {
                        BackButton()
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                        }
                    }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                SwipeableConversationItem(
                    conversation = conversation,
                    selectionMode = selectionMode,
                    isSelected = conversation.id.toString() in selectedIds,
                    onClick = {
                        if (selectionMode) {
                            toggleSelect(conversation.id.toString())
                        } else {
                            navigateToChatPage(navController, conversation.id)
                        }
                    },
                    onLongClick = {
                        // 长按进入多选并选中当前条目（浏览态长按不弹菜单，与 imggen 同款）
                        if (!selectionMode) {
                            selectionMode = true
                            selectedIds.add(conversation.id.toString())
                        }
                    },
                    onDelete = {
                        scope.launch {
                            // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                            val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                            vm.deleteConversation(fullConversation)
                            val result = snackbarHostState.showSnackbar(
                                message = snackMessageDeleted,
                                actionLabel = snackMessageUndo,
                                withDismissAction = true,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                // 恢复队列里全部待撤销会话（含被新 snackbar 抢占的旧条目——原先它们
                                // 被静默丢弃且附件文件已被 Dismissed 分支删掉，恢复不完整）
                                vm.undoConversationDeletes()
                            }
                            // Dismissed（超时/被抢占）不再在此处删文件：到期清理由撤销队列的
                            // 定时任务兜住（undoRetentionMs，见 HistoryVM.deleteConversation）
                        }
                    },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    /** 多选模式：条目显示勾选框、点击改勾选、禁用滑动删除 */
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
                dismissState.reset()  // 立即归位，避免会话列表快速刷新时复用节点停在滑出态
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        appSkinRoundedShape(20f)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        // 多选模式下禁用滑动删除，避免手势与勾选点击冲突
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionMode,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            selectionMode = selectionMode,
            isSelected = isSelected,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Surface(
        // Surface 不带 onClick 时不注入自身 clickable，长按入口由此实现（多选模式进入）
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        tonalElevation = 2.dp,
        shape = appSkinRoundedShape(20f)
    ) {
        ListItem(
            leadingContent = if (selectionMode) {
                {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                }
            } else null,
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                // 多选模式下隐藏置顶按钮，避免与勾选语义冲突
                if (!selectionMode) {
                    IconButton(
                        onClick = onTogglePin
                    ) {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                                R.string.history_page_pin
                            )
                        )
                    }
                }
            }
        )
    }
}
