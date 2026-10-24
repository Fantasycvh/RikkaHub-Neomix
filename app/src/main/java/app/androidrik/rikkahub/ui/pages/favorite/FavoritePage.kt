package app.androidrik.rikkahub.ui.pages.favorite

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.SortByDown01
import me.rerere.hugeicons.stroke.Tag01
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.navigateToChatPage
import app.androidrik.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import kotlin.uuid.Uuid

@Composable
fun FavoritePage(vm: FavoriteVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val allFavorites = vm.nodeFavorites.collectAsStateWithLifecycle().value
    val sort by vm.sort.collectAsStateWithLifecycle()
    val tagPool by vm.tagPool.collectAsStateWithLifecycle()
    val favoriteRemovedText = stringResource(R.string.favorite_page_removed)
    val undoText = stringResource(R.string.history_page_undo)

    // 标签筛选（多选 = 或关系：命中任一选中标签即显示）
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showTagManager by remember { mutableStateOf(false) }
    var tagEditingFor by remember { mutableStateOf<NodeFavoriteListItem?>(null) }

    val favorites = remember(allFavorites, selectedTagIds) {
        if (selectedTagIds.isEmpty()) allFavorites
        else allFavorites.filter { it.tagIds.any { id -> id in selectedTagIds } }
    }

    if (showTagManager) {
        FavoriteTagManagerDialog(
            tags = tagPool,
            onAdd = { scope.launch { vm.addTag(it) } },
            onRename = { id, name -> scope.launch { vm.renameTag(id, name) } },
            onDelete = { id -> scope.launch { vm.deleteTag(id) } },
            onDismiss = { showTagManager = false },
        )
    }

    // 给单个收藏打标签
    tagEditingFor?.let { item ->
        FavoriteTagPickerDialog(
            tags = tagPool,
            selected = item.tagIds.toSet(),
            onConfirm = { ids ->
                scope.launch { vm.setFavoriteTags(item.refKey, ids.toList()) }
                tagEditingFor = null
            },
            onManage = {
                tagEditingFor = null
                showTagManager = true
            },
            onDismiss = { tagEditingFor = null },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    Text(stringResource(R.string.favorite_page_title))
                },
                actions = {
                    if (tagPool.isNotEmpty() || allFavorites.isNotEmpty()) {
                        IconButton(onClick = { showTagManager = true }) {
                            Icon(
                                HugeIcons.Tag01,
                                contentDescription = stringResource(R.string.favorite_page_manage_tags),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                HugeIcons.SortByDown01,
                                contentDescription = stringResource(R.string.favorite_page_sort),
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.favorite_page_sort_by_time)) },
                                onClick = {
                                    vm.setSort(FavoriteSort.CREATED_DESC)
                                    showSortMenu = false
                                },
                                trailingIcon = if (sort == FavoriteSort.CREATED_DESC) {
                                    { Icon(HugeIcons.CheckmarkCircle02, null) }
                                } else null,
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.favorite_page_sort_by_title)) },
                                onClick = {
                                    vm.setSort(FavoriteSort.TITLE_ASC)
                                    showSortMenu = false
                                },
                                trailingIcon = if (sort == FavoriteSort.TITLE_ASC) {
                                    { Icon(HugeIcons.CheckmarkCircle02, null) }
                                } else null,
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 标签筛选 chips（仅在有标签时出现，避免空行占位）
            if (tagPool.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    items(tagPool, key = { it.id }) { tag ->
                        FilterChip(
                            selected = tag.id in selectedTagIds,
                            onClick = {
                                selectedTagIds = if (tag.id in selectedTagIds) {
                                    selectedTagIds - tag.id
                                } else {
                                    selectedTagIds + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }

            if (favorites.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(
                            if (allFavorites.isEmpty()) {
                                R.string.favorite_page_no_favorites
                            } else {
                                R.string.favorite_page_no_match
                            }
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(favorites, key = { it.id }) { item ->
                    // **key(item.id) 包裹**：让 dismissState 随 item 身份重建。
                    // 否则恢复的卡片会停在"已滑出"位置（LazyColumn 按 key 复用节点，
                    // dismissState.currentValue 仍是 EndToStart，LaunchedEffect 因值未变不再触发）
                    // —— 见设计稿 §1.4 缺陷 B。
                    key(item.id) {
                        val dismissState = rememberSwipeToDismissBoxState(
                            initialValue = SwipeToDismissBoxValue.Settled,
                        )
                        SwipeableFavoriteCard(
                            item = item,
                            dismissState = dismissState,
                            onClick = { navigateToChatPage(navController, item.conversationId, nodeId = item.nodeId) },
                            onDelete = {
                                scope.launch {
                                    // 顺序关键：**先删完再弹 snackbar**（removeFavorite 是 suspend）。
                                    // 删除的实体进 VM 撤销队列：连续删除时新 snackbar 会抢占旧的，被抢占
                                    // 条目的撤销靠「任意撤销动作恢复全部 pending」兜住（见 FavoriteVM）。
                                    val entity = vm.getEntityByRefKey(item.refKey) ?: return@launch
                                    vm.removeFavorite(entity)
                                    val result = snackbarHostState.showSnackbar(
                                        message = favoriteRemovedText,
                                        actionLabel = undoText,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        vm.undoFavoriteDeletes()
                                    }
                                }
                            },
                            onEditTags = { tagEditingFor = item },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableFavoriteCard(
    item: NodeFavoriteListItem,
    dismissState: androidx.compose.material3.SwipeToDismissBoxState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 滑出后触发删除（state 已由 key(item.id) 保证随 item 重建）
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            dismissState.reset()  // 立即归位，避免复用节点时停在滑出态
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
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.assistant_page_remove),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        // 两个方向都能滑出：只允许一个方向是"难以发现"的主因（见设计稿 §1.4 缺陷 C）
        enableDismissFromStartToEnd = true,
        modifier = modifier,
    ) {
        FavoriteCard(
            item = item,
            onClick = onClick,
            onDelete = onDelete,
            onEditTags = onEditTags,
        )
    }
}

@Composable
private fun FavoriteCard(
    item: NodeFavoriteListItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onEditTags: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.conversationTitle.ifBlank { stringResource(R.string.favorite_page_untitled_conversation) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // **可见的操作入口**：原先只能侧滑删除且无任何 affordance（缺陷 C）
                    IconButton(onClick = onEditTags) {
                        Icon(
                            HugeIcons.Tag01,
                            contentDescription = stringResource(R.string.favorite_page_edit_tags),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        // 测试定位用：滑动背景的删除图标与按钮共用 contentDescription，单靠描述会匹配两个节点
                        modifier = Modifier.testTag("favorite_card_delete"),
                    ) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.assistant_page_remove),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                val dateText = Instant.ofEpochMilli(item.createdAt).toLocalDateTime()
                Text(
                    text = item.preview,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = dateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 给单个收藏勾选标签。 */
@Composable
private fun FavoriteTagPickerDialog(
    tags: List<Tag>,
    selected: Set<Uuid>,
    onConfirm: (Set<Uuid>) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorite_page_edit_tags)) },
        text = {
            if (tags.isEmpty()) {
                Text(stringResource(R.string.favorite_page_no_tags_yet))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in working,
                            onClick = {
                                working = if (tag.id in working) working - tag.id else working + tag.id
                            },
                            label = { Text(tag.name) },
                            shape = RoundedCornerShape(50),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.favorite_page_manage_tags))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** 标签管理（增/改/删）。 */
@Composable
private fun FavoriteTagManagerDialog(
    tags: List<Tag>,
    onAdd: (String) -> Unit,
    onRename: (Uuid, String) -> Unit,
    onDelete: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Tag?>(null) }
    var renameText by remember { mutableStateOf("") }

    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.favorite_page_rename_tag)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renaming
                    if (t != null) onRename(t.id, renameText)
                    renaming = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.favorite_page_manage_tags)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.favorite_page_new_tag)) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            onAdd(newName)
                            newName = ""
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text(stringResource(R.string.favorite_page_add_tag)) }
                }
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.favorite_page_no_tags_yet))
                } else {
                    tags.forEach { tag ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(tag.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                renaming = tag
                                renameText = tag.name
                            }) { Text(stringResource(R.string.favorite_page_rename_tag_short)) }
                            IconButton(onClick = { onDelete(tag.id) }) {
                                Icon(
                                    HugeIcons.Delete01,
                                    contentDescription = stringResource(R.string.assistant_page_remove),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
        },
    )
}
