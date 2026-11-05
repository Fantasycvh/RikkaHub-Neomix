package app.androidrik.rikkahub.ui.pages.backup

import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Upload02
import app.androidrik.rikkahub.utils.plus

/**
 * 数据备份入口页（二级）：只列「有哪些备份方式」，点进去才是具体功能页（三级）。
 *
 * 此前这一页用顶栏 tab（本地 / WebDAV / S3 / 提醒 / 增量）承载全部内容，tab 横滑与
 * 大标题折叠互相抢手势、返回栈也看不出当前在哪一节；改成与设置页其他二级页一致的
 * CardGroup 列表 + 独立详情页后，每节可以有自己的标题、返回与滚动位置。
 */
@Composable
fun BackupPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("backupMethods") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.backup_page_section_methods)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.BackupImportExport) },
                        leadingContent = { Icon(HugeIcons.File01, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_import_export_title)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_import_export_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.BackupWebDav) },
                        leadingContent = { Icon(HugeIcons.Upload02, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_webdav_backup)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_webdav_desc)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.BackupS3) },
                        leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_s3_backup)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_s3_desc)) },
                    )
                }
            }

            item("backupAutomation") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.backup_page_section_automation)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.BackupReminder) },
                        leadingContent = { Icon(HugeIcons.Notification01, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_reminder_title)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_reminder_desc)) },
                    )
                }
            }

            item("backupExperimental") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.backup_page_section_experimental)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.BackupSnapshot) },
                        leadingContent = { Icon(HugeIcons.Database02, null) },
                        headlineContent = { Text(stringResource(R.string.backup_page_snapshot_title)) },
                        supportingContent = { Text(stringResource(R.string.backup_page_snapshot_desc)) },
                    )
                }
            }
        }
    }
}
