package app.androidrik.rikkahub.ui.pages.setting

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Time04
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.db.entity.ManagedFileEntity
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.DEFAULT_EXPORT_RETENTION_DAYS
import app.androidrik.rikkahub.data.files.EXPORT_RETENTION_DAYS_MAX
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import app.androidrik.rikkahub.utils.fileProviderUri
import app.androidrik.rikkahub.utils.shareFile
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingFilesPage(
    filesManager: FilesManager = koinInject(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    // 导出留存区与附件一起管：它是「用户想留的产物」，同样需要可见/可删/可再分享
    // （见 docs/design-export-retention.md §4.4）
    val folders = remember { listOf(FileFolders.UPLOAD, FileFolders.EXPORTS) }

    // 预先获取字符串资源
    val deletedToast = stringResource(R.string.setting_files_page_deleted_toast)
    val deleteFailedToast = stringResource(R.string.setting_files_page_delete_failed_toast)
    val cleanedToast = stringResource(R.string.setting_files_page_cleaned_toast)
    val cleanFailedToast = stringResource(R.string.setting_files_page_clean_failed_toast)

    var selectedFolder by remember { mutableStateOf(FileFolders.UPLOAD) }
    var showRetentionDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ManagedFileEntity?>(null) }
    var showCleanDialog by remember { mutableStateOf(false) }
    val files by filesManager.observe(selectedFolder).collectAsState(initial = emptyList())

    if (pendingDelete != null) {
        val target = pendingDelete!!
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.setting_files_page_delete_file_title)) },
            text = { Text(target.displayName) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = filesManager.delete(target.id, deleteFromDisk = true)
                            if (ok) {
                                toaster.show(deletedToast)
                            } else {
                                toaster.show(deleteFailedToast)
                            }
                            pendingDelete = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showRetentionDialog) {
        var daysText by remember(settings.exportRetentionDays) {
            mutableStateOf(settings.exportRetentionDays.toString())
        }
        AlertDialog(
            onDismissRequest = { showRetentionDialog = false },
            title = { Text(stringResource(R.string.setting_page_export_retention)) },
            text = {
                Column {
                    Text(stringResource(R.string.setting_page_export_retention_desc))
                    OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it.filter(Char::isDigit).take(4) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val days = daysText.toIntOrNull() ?: DEFAULT_EXPORT_RETENTION_DAYS
                        scope.launch {
                            settingsStore.update {
                                it.copy(exportRetentionDays = days.coerceIn(0, EXPORT_RETENTION_DAYS_MAX))
                            }
                        }
                        showRetentionDialog = false
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_clean_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetentionDialog = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    if (showCleanDialog) {
        AlertDialog(
            onDismissRequest = { showCleanDialog = false },
            title = { Text(stringResource(R.string.setting_files_page_clean_title)) },
            text = { Text(stringResource(R.string.setting_files_page_clean_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCleanDialog = false
                        scope.launch {
                            val ok = filesManager.deleteAll(selectedFolder)
                            toaster.show(if (ok) cleanedToast else cleanFailedToast)
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_files_page_clean_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanDialog = false }) {
                    Text(stringResource(R.string.setting_files_page_cancel_action))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_files_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showRetentionDialog = true }) {
                        Icon(
                            imageVector = HugeIcons.Time04,
                            contentDescription = stringResource(R.string.setting_page_export_retention),
                        )
                    }
                    IconButton(
                        onClick = { showCleanDialog = true },
                        enabled = files.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Clean,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                )
        ) {
            FolderRow(
                folders = folders,
                selectedFolder = selectedFolder,
                onFolderSelected = { selectedFolder = it }
            )

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 导出区为空时顺带说明 TTL 行为（用户不易猜到"留存会过期"）
                    Text(
                        text = if (selectedFolder == FileFolders.EXPORTS) {
                            stringResource(R.string.setting_files_page_exports_empty_hint)
                        } else {
                            stringResource(R.string.setting_files_page_no_files)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp,
                    ),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(2)
                ) {
                    items(files, key = { it.id }) { file ->
                        FileItem(
                            file = file,
                            fileOnDisk = filesManager.getFile(file),
                            onDelete = { pendingDelete = file },
                            onShare = {
                                // 再分享：留存区的核心价值之一（"找得到、发得出"）
                                context.shareFile(context.fileProviderUri(filesManager.getFile(file)), file.mimeType)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.forEach { folder ->
            FilterChip(
                selected = selectedFolder == folder,
                onClick = { onFolderSelected(folder) },
                label = { Text(folderDisplayName(folder)) }
            )
        }
    }
}

@Composable
private fun folderDisplayName(folder: String): String = when (folder) {
    FileFolders.UPLOAD -> stringResource(R.string.setting_files_page_folder_upload)
    FileFolders.EXPORTS -> stringResource(R.string.setting_files_page_folder_exports)
    else -> folder
}

@Composable
private fun FileItem(
    file: ManagedFileEntity,
    fileOnDisk: File,
    onDelete: () -> Unit,
    onShare: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (file.mimeType.startsWith("image/")) {
                    AsyncImage(
                        model = fileOnDisk,
                        contentDescription = file.displayName,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(4f / 3f),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = HugeIcons.Image02,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    if (onShare != null) {
                        IconButton(onClick = onShare) {
                            Icon(
                                HugeIcons.Share08,
                                contentDescription = stringResource(R.string.setting_files_page_share_content_description)
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.setting_files_page_delete_content_description)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = file.mimeType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = file.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
