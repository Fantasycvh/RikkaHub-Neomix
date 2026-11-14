package app.androidrik.rikkahub.ui.pages.backup

import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.pages.backup.components.RestoreRestartSignal
import app.androidrik.rikkahub.ui.pages.backup.tabs.ImportExportTab
import app.androidrik.rikkahub.ui.pages.backup.tabs.ReminderTab
import app.androidrik.rikkahub.ui.pages.backup.tabs.S3Tab
import app.androidrik.rikkahub.ui.pages.backup.tabs.SnapshotBackupTab
import app.androidrik.rikkahub.ui.pages.backup.tabs.WebDavTab
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import app.androidrik.rikkahub.ui.context.LocalSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import org.koin.androidx.compose.koinViewModel

/**
 * 备份设置的三级详情页：独立顶栏（返回 + 大标题）包住原来的 tab 内容组件。
 *
 * 每个页面各自 `koinViewModel()`（导航库按 entry 分配 ViewModelStore），长任务状态来自
 * 进程级 coordinator，所以离开/返回页面不会丢进度；列表与设置仍由 SettingsStore 共享。
 */
@Composable
private fun BackupDetailScaffold(
    title: String,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    GlassTopBarScaffold(displaySetting = LocalSettings.current.displaySetting,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = pageGlassTopBarColors(CustomColors.topBarColors)
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        // 内容组件自己负责滚动（LazyColumn / verticalScroll），这里只让出顶栏高度。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

/** 本地导出/导入（原「本地」tab）。 */
@Composable
fun BackupImportExportPage(vm: BackupVM = koinViewModel()) {
    BackupDetailScaffold(stringResource(R.string.backup_page_import_export_title)) {
        ImportExportTab(vm = vm, onShowRestartDialog = { RestoreRestartSignal.requestRestart() })
    }
}

/** WebDAV 全量备份（原「WebDAV备份」tab）。 */
@Composable
fun BackupWebDavPage(vm: BackupVM = koinViewModel()) {
    BackupDetailScaffold(stringResource(R.string.backup_page_webdav_backup)) {
        WebDavTab(vm = vm, onShowRestartDialog = { RestoreRestartSignal.requestRestart() })
    }
}

/** S3 全量备份（原「S3备份」tab）。 */
@Composable
fun BackupS3Page(vm: BackupVM = koinViewModel()) {
    BackupDetailScaffold(stringResource(R.string.backup_page_s3_backup)) {
        S3Tab(vm = vm, onShowRestartDialog = { RestoreRestartSignal.requestRestart() })
    }
}

/** 备份提醒（原「提醒」tab）。 */
@Composable
fun BackupReminderPage(vm: BackupVM = koinViewModel()) {
    BackupDetailScaffold(stringResource(R.string.backup_page_reminder_title)) {
        ReminderTab(vm = vm)
    }
}

/** 增量备份（实验，原「增量备份」tab）。 */
@Composable
fun BackupSnapshotPage(vm: BackupVM = koinViewModel()) {
    BackupDetailScaffold(stringResource(R.string.backup_page_snapshot_title)) {
        SnapshotBackupTab(vm = vm)
    }
}
