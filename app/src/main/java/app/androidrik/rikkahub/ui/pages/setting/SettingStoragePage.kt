package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.storage.ClearResult
import app.androidrik.rikkahub.data.storage.StorageCategory
import app.androidrik.rikkahub.data.storage.StorageCategoryId
import app.androidrik.rikkahub.data.storage.StorageDetail
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.RikkaConfirmDialog
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.fileSizeToString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Archive02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.GridTable
import me.rerere.hugeicons.stroke.Icon1stBracket
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Refresh
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingStoragePage(
    vm: StorageVM = koinViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingClear by remember { mutableStateOf<StorageCategoryId?>(null) }
    var failureReport by remember { mutableStateOf<ClearResult?>(null) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                StorageEvent.Busy -> toaster.show(context.getString(R.string.setting_storage_page_busy))
                is StorageEvent.Cleared -> {
                    val result = event.result
                    if (result.success) {
                        toaster.show(
                            context.getString(
                                R.string.setting_storage_page_cleared,
                                context.getString(result.id.titleRes()),
                                result.freedBytes.fileSizeToString(),
                            )
                        )
                    } else {
                        failureReport = result
                    }
                }
            }
        }
    }

    pendingClear?.let { id ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(
                R.string.setting_storage_page_clear_title,
                stringResource(id.titleRes()),
            ),
            confirmText = stringResource(R.string.setting_files_page_clean_action),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                pendingClear = null
                vm.clear(id)
            },
            onDismiss = { pendingClear = null },
        ) {
            Text(stringResource(R.string.setting_storage_page_clear_message))
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            title = { Text(stringResource(R.string.setting_storage_page_error_title)) },
            text = { Text(stringResource(R.string.setting_storage_page_error, message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissError()
                    vm.refresh()
                }) { Text(stringResource(R.string.setting_storage_page_retry)) }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissError) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    failureReport?.let { result ->
        AlertDialog(
            onDismissRequest = { failureReport = null },
            title = { Text(stringResource(R.string.setting_storage_page_clear_partial_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.setting_storage_page_clear_partial,
                            result.failures.size,
                            result.freedBytes.fileSizeToString(),
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    result.failures.take(10).forEach { name ->
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { failureReport = null }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_storage_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(enabled = !state.busy, onClick = vm::refresh) {
                        Icon(
                            HugeIcons.Refresh,
                            stringResource(R.string.setting_storage_page_refresh),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            item {
                StorageSummaryCard(
                    totalBytes = state.totalBytes,
                    categoryCount = state.categories.size,
                )
            }

            if (state.blocked) {
                item { BlockingOperationBanner() }
            }

            items(state.categories, key = { it.id }) { category ->
                StorageCategoryCard(
                    category = category,
                    expanded = category.id in state.expanded,
                    details = state.details[category.id].orEmpty(),
                    detailsLoading = state.detailsLoading == category.id,
                    enabled = !state.busy && !state.blocked,
                    clearing = state.clearingId == category.id,
                    onToggle = { vm.toggleExpand(category.id) },
                    onClear = { pendingClear = category.id },
                )
            }

            item {
                Text(
                    text = stringResource(R.string.setting_storage_page_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageSummaryCard(
    totalBytes: Long,
    categoryCount: Int,
) {
    Card(colors = CustomColors.cardColors) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(
                text = stringResource(R.string.setting_storage_page_summary),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = totalBytes.fileSizeToString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.setting_storage_page_summary_count, categoryCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BlockingOperationBanner() {
    Card(colors = CustomColors.cardColors) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.setting_storage_page_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StorageCategoryCard(
    category: StorageCategory,
    expanded: Boolean,
    details: List<StorageDetail>,
    detailsLoading: Boolean,
    enabled: Boolean,
    clearing: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
) {
    Card(colors = CustomColors.cardColors) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (category.canExpand) Modifier.clickable(onClick = onToggle) else Modifier
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = category.id.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(category.id.titleRes()),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(category.id.descriptionRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!category.readable) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.setting_storage_page_unreadable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = category.sizeBytes.fileSizeToString(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (category.itemCount > 0) {
                            stringResource(
                                R.string.setting_storage_page_files_count,
                                category.itemCount,
                            )
                        } else {
                            stringResource(R.string.setting_storage_page_no_files)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (category.canExpand) {
                    val rotation by animateFloatAsState(
                        targetValue = if (expanded) 180f else 0f,
                        label = "storageDetailsChevron",
                    )
                    Icon(
                        imageVector = HugeIcons.ArrowDown01,
                        contentDescription = stringResource(R.string.setting_storage_page_toggle_details),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(18.dp)
                            .rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (clearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(2.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        enabled = enabled && category.itemCount > 0,
                        onClick = onClear,
                    ) {
                        Icon(
                            imageVector = HugeIcons.Delete02,
                            contentDescription = stringResource(R.string.setting_files_page_clean_content_description),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (category.canExpand && expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 50.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        detailsLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.setting_storage_page_loading_details),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        details.isEmpty() -> Text(
                            text = stringResource(R.string.setting_storage_page_no_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> details.forEach { detail ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(
                                    text = detail.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = detail.sizeBytes.fileSizeToString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun StorageCategoryId.titleRes(): Int = when (this) {
    StorageCategoryId.IMAGE_CACHE -> R.string.setting_storage_page_category_image_cache
    StorageCategoryId.TEMP -> R.string.setting_storage_page_category_temp
    StorageCategoryId.WEBVIEW -> R.string.setting_storage_page_category_webview
    StorageCategoryId.THUMBS -> R.string.setting_storage_page_category_thumbs
    StorageCategoryId.TEX -> R.string.setting_storage_page_category_tex
    StorageCategoryId.LOGS -> R.string.setting_storage_page_category_logs
    StorageCategoryId.BACKUP_TEMP -> R.string.setting_storage_page_category_backup_temp
}

private fun StorageCategoryId.descriptionRes(): Int = when (this) {
    StorageCategoryId.IMAGE_CACHE -> R.string.setting_storage_page_category_image_cache_desc
    StorageCategoryId.TEMP -> R.string.setting_storage_page_category_temp_desc
    StorageCategoryId.WEBVIEW -> R.string.setting_storage_page_category_webview_desc
    StorageCategoryId.THUMBS -> R.string.setting_storage_page_category_thumbs_desc
    StorageCategoryId.TEX -> R.string.setting_storage_page_category_tex_desc
    StorageCategoryId.LOGS -> R.string.setting_storage_page_category_logs_desc
    StorageCategoryId.BACKUP_TEMP -> R.string.setting_storage_page_category_backup_temp_desc
}

private fun StorageCategoryId.icon(): ImageVector = when (this) {
    StorageCategoryId.IMAGE_CACHE -> HugeIcons.Image02
    StorageCategoryId.TEMP -> HugeIcons.Folder01
    StorageCategoryId.WEBVIEW -> HugeIcons.Earth
    StorageCategoryId.THUMBS -> HugeIcons.GridTable
    StorageCategoryId.TEX -> HugeIcons.Icon1stBracket
    StorageCategoryId.LOGS -> HugeIcons.File02
    StorageCategoryId.BACKUP_TEMP -> HugeIcons.Archive02
}
