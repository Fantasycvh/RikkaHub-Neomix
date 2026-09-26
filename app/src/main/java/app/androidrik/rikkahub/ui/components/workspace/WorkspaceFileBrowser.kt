package app.androidrik.rikkahub.ui.components.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.ListSelectableItem
import app.androidrik.rikkahub.ui.components.ui.Tooltip
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceStorageArea
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08

/**
 * 工作区文件浏览的**共享组件**（设计见 `docs/design-side-file-panel.md` §四、
 * `docs/design-workspace-multiselect-export.md` §六.1）。
 *
 * **为什么要抽**：详情页与（将来）聊天侧边栏会渲染同一份文件列表；复制一份 UI 两处必然漂移
 * （本仓同类教训：`BackupItem` 枚举统一、`BackupArchiveEngine` 抽取、`ChatToolFactory` 集中装配）。
 *
 * **边界规则（本次定死，多选与侧边栏都按它走）**：
 *
 * 1. **组件无状态**：`entries / path / area / loading / error` 由宿主给；组件不碰 repository、不持 VM。
 * 2. **选择态由宿主持有**：组件只接 `selecting / selectedPaths / onSelectToggle / onLongPress`，
 *    **自己不记选中集**——这样侧边栏将来要选择时不必把状态搬进组件。
 * 3. **宿主专属能力一律是可空回调**（`onShare` / `onExportToDir` 侧边栏可以不传），
 *    组件只在非空时渲染对应入口——**差异用"能力有无"表达，不用 `if (宿主A)` 分叉**。
 * 4. 布局差异只靠 [compact] 与 [showAreaSelector] 两个开关；**不给两侧各写一份**。
 * 5. **选择态下的点击语义由组件统一判定**（选中态点击 = 切换选中，不再进目录/打开文件）：
 *    这条规则若交给宿主，两个宿主迟早会写歪一个（"选择态下点进了目录、选择被清空"）。
 *
 * @param contentPadding 宿主自己决定列表内边距（详情页在 pager 里传空，侧边栏在 sheet 里传 sheet 内边距）
 * @param onSelectAll 全选：**由宿主实现**（只有宿主知道"全选"的范围对不对——例如要排除不可导出的项）
 */
@Composable
fun WorkspaceFileBrowser(
    entries: List<WorkspaceFileEntry>,
    path: String,
    area: WorkspaceStorageArea,
    loading: Boolean,
    error: String?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onGoUp: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    compact: Boolean = false,
    showAreaSelector: Boolean = true,
    onSelectArea: (WorkspaceStorageArea) -> Unit = {},
    onShare: ((WorkspaceFileEntry) -> Unit)? = null,
    onExportToDir: ((WorkspaceFileEntry, File, (File) -> Unit) -> Unit)? = null,
    selecting: Boolean = false,
    selectedPaths: Set<String> = emptySet(),
    onSelectToggle: (WorkspaceFileEntry) -> Unit = {},
    onLongPress: ((WorkspaceFileEntry) -> Unit)? = null,
) {
    val itemSpacing = if (compact) 8.dp else 12.dp
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
    ) {
        if (showAreaSelector) {
            item {
                WorkspaceAreaSelector(selected = area, onSelected = onSelectArea)
            }
        }

        item {
            WorkspacePathBar(
                path = path,
                canGoUp = path.isNotBlank(),
                onGoUp = onGoUp,
            )
        }

        error?.let { message ->
            item { ErrorCard(message) }
        }

        if (!loading && entries.isEmpty() && error == null) {
            item { EmptyDirectoryState(compact = compact) }
        }

        items(entries, key = { "${area.name}:${it.path}" }) { entry ->
            WorkspaceFileCard(
                entry = entry,
                compact = compact,
                selecting = selecting,
                selected = entry.path in selectedPaths,
                onSelectToggle = { onSelectToggle(entry) },
                onLongPress = onLongPress?.let { { it(entry) } },
                onOpen = {
                    // 选择态下"打开"退化为"切换选中"（边界规则 5）
                    if (selecting) onSelectToggle(entry) else onOpen(entry)
                },
                onDelete = { onDelete(entry) },
                onExport = { onExport(entry) },
                onShare = onShare?.let { { it(entry) } },
                onExportToDir = onExportToDir,
            )
        }
    }
}

/**
 * 选择态工具条（选择态 UI 的另一半；位置由宿主决定——详情页贴底、侧边栏贴 sheet 底）。
 *
 * 与聊天消息多选同形（`ChatList` 的底部浮动工具条：清空 / 全选 / 确认），
 * 只是这里一次给两个动作：**导出**与**删除**——多选的价值就在"一次处理一批"。
 */
@Composable
fun WorkspaceSelectionToolbar(
    selectedCount: Int,
    visible: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it * 2 }),
        exit = slideOutVertically(targetOffsetY = { it * 2 }),
    ) {
        HorizontalFloatingToolbar(expanded = true) {
            Tooltip(tooltip = { Text(stringResource(R.string.workspace_selection_cancel)) }) {
                IconButton(onClick = onCancel) {
                    Icon(HugeIcons.Cancel01, contentDescription = null)
                }
            }
            Tooltip(tooltip = { Text(stringResource(R.string.workspace_selection_select_all)) }) {
                IconButton(onClick = onSelectAll) {
                    Icon(HugeIcons.CursorPointer01, contentDescription = null)
                }
            }
            Tooltip(tooltip = { Text(stringResource(R.string.common_export)) }) {
                IconButton(onClick = onExport, enabled = selectedCount > 0) {
                    Icon(HugeIcons.FileImport, contentDescription = null)
                }
            }
            Tooltip(tooltip = { Text(stringResource(R.string.common_delete)) }) {
                IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (selectedCount > 0) {
                FilledIconButton(onClick = {}, enabled = false) {
                    Text(text = selectedCount.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    val areas = listOf(
        WorkspaceStorageArea.FILES to stringResource(R.string.workspace_detail_area_files),
        WorkspaceStorageArea.LINUX to stringResource(R.string.workspace_detail_area_rootfs),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        areas.forEachIndexed { index, (area, label) ->
            SegmentedButton(
                selected = selected == area,
                onClick = { onSelected(area) },
                shape = SegmentedButtonDefaults.itemShape(index, areas.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            enabled = canGoUp,
            onClick = onGoUp,
        ) {
            Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
        }
        Text(
            text = path.ifBlank { "/" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun WorkspaceFileCard(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onExportToDir: ((WorkspaceFileEntry, File, (File) -> Unit) -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // SVG 文件直接在列表内渲染缩略图（导出到 cache 后由 Coil 单例的 SvgDecoder 加载）。
    // onExportToDir 为空（侧边栏等宿主不提供该能力）时不渲染缩略图，回落成通用文件图标。
    val isSvg = !entry.isDirectory &&
        entry.name.substringAfterLast('.', "").equals("svg", ignoreCase = true) &&
        onExportToDir != null
    var svgThumbFile by remember(entry.path) { mutableStateOf<File?>(null) }
    if (isSvg && svgThumbFile == null) {
        LaunchedEffect(entry.path) {
            val thumbDir = File(context.cacheDir, "workspace_svg_thumb")
            onExportToDir?.invoke(entry, thumbDir) { file ->
                if (file.exists() && file.length() > 0) svgThumbFile = file
            }
        }
    }

    ListSelectableItem(
        key = entry.path,
        selectedKeys = if (selected) listOf(entry.path) else emptyList(),
        onSelectChange = { onSelectToggle() },
        enabled = selecting,
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = onLongPress,
                ),
            colors = CustomColors.cardColorsOnSurfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (compact) 12.dp else 16.dp,
                        top = if (compact) 8.dp else 12.dp,
                        bottom = if (compact) 8.dp else 12.dp,
                        end = 4.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSvg && svgThumbFile != null) {
                    AsyncImage(
                        model = svgThumbFile,
                        contentDescription = null,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (entry.isDirectory) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 长文件名不得把右侧的 ⋮ 挤出卡片（段卡真机踩过的坑：长标签 + 无 weight）
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (entry.isDirectory) {
                            entry.path
                        } else {
                            "${entry.path} · ${entry.sizeBytes.fileSizeToString()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 选择态下不显示 ⋮：那时每个点击都属于"选谁"，菜单会把人拉出选择流程
                if (!selecting) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(HugeIcons.MoreVertical, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            if (!entry.isDirectory) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_export)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = HugeIcons.FileImport,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onExport()
                                    },
                                )
                                onShare?.let { share ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.common_share)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = HugeIcons.Share08,
                                                contentDescription = null,
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            share()
                                        },
                                    )
                                }
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.common_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = HugeIcons.Delete01,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyDirectoryState(compact: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) 32.dp else 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = HugeIcons.Folder01,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.workspace_detail_empty_directory),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
