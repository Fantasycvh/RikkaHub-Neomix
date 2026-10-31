package app.androidrik.rikkahub.ui.pages.setting.scheduled

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.service.KeepAliveService
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.NotificationUtil
import app.androidrik.rikkahub.Screen
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

@Composable
fun ScheduledTasksPage(vm: ScheduledTasksVM = koinViewModel()) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val conversationTitles by vm.conversationTitles.collectAsStateWithLifecycle()
    val assistantNames by vm.assistantNames.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // 删除是永久的（任务 + 排程），列表里点一下垃圾桶就删太容易误触，先弹确认
    var pendingDelete by remember { mutableStateOf<ScheduledTaskEntity?>(null) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.scheduled_task_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.SettingScheduledTaskCreate()) }) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.scheduled_task_create))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            // 曲面屏/新装机的常见坑：通知权限没给，任务跑了但用户完全无感
            val context = LocalContext.current
            if (!NotificationUtil.hasNotificationPermission(context)) {
                TaskPermissionHint(
                    text = stringResource(R.string.scheduled_task_notif_permission_hint),
                    action = stringResource(R.string.scheduled_task_notif_permission_action),
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
            // 准点性的两个前置：电池优化白名单 + 国产 ROM 的「应用启动管理」。
            // 真机实测（OnePlus/ColorOS 15）：即使闹钟精确投递，ROM 的 OplusAppStartupManager
            // 也会 `prevent start ... by broadcast system[alarmManger]`——必须让用户把本应用
            // 加进「允许自启动 / 允许后台活动」，App 侧无法代劳。
            if (!KeepAliveService.isIgnoringBatteryOptimizations(context)) {
                TaskPermissionHint(
                    text = stringResource(R.string.scheduled_task_battery_hint),
                    action = stringResource(R.string.scheduled_task_battery_action),
                    onClick = {
                        runCatching { context.startActivity(KeepAliveService.batteryOptimizationIntent(context)) }
                    },
                )
            }
            // 保活开关：这台 ROM 的「应用启动管理」会丢弃后台广播/启动（§6.2.1 实测
            // SKIPPED ... reason: oplus startup），而进程常驻时闹钟广播能正常投递——真机上
            // 「开着保活 + 熄屏」是唯一验证通过的组合，所以这里一定要提示。
            if (!KeepAliveService.isEnabled(context)) {
                TaskPermissionHint(
                    text = stringResource(R.string.scheduled_task_keepalive_hint),
                    action = stringResource(R.string.scheduled_task_keepalive_action),
                    onClick = {
                        KeepAliveService.setEnabled(context, true)
                        KeepAliveService.start(context)
                    },
                )
            }
            val romBrand = remember { (Build.MANUFACTURER + " " + Build.BRAND).lowercase() }
            val isOplus = remember(romBrand) {
                romBrand.contains("oppo") || romBrand.contains("oneplus") || romBrand.contains("realme")
            }
            val isAggressiveRom = remember(romBrand) {
                isOplus || listOf(
                    "xiaomi", "redmi", "vivo", "iqoo", "huawei", "honor", "meizu", "samsung",
                ).any { romBrand.contains(it) }
            }
            if (isAggressiveRom) {
                TaskPermissionHint(
                    text = stringResource(R.string.scheduled_task_rom_hint),
                    action = stringResource(R.string.scheduled_task_rom_action),
                    onClick = {
                        // ColorOS/realme UI 有专门的应用启动管理页；其它 ROM 退到应用详情页
                        val opened = isOplus && runCatching {
                            context.startActivity(
                                Intent(OPLUS_STARTUP_ACTION)
                                    .setComponent(ComponentName(OPLUS_STARTUP_PACKAGE, OPLUS_STARTUP_ACTIVITY))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            true
                        }.getOrDefault(false)
                        if (!opened) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                        .setData(Uri.parse("package:${context.packageName}")),
                                )
                            }
                        }
                    },
                )
            }
            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.scheduled_task_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                    )
                }
            }
            CardGroup(
                // 曲面屏两侧的曲率区会吃掉贴边内容，比其它设置页的 8dp 再宽一档
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                tasks.forEach { task ->
                    item(
                        onClick = {
                            navController.navigate(Screen.SettingScheduledTaskDetail(task.id))
                        },
                        headlineContent = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = targetDescription(task, conversationTitles, assistantNames),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = scheduleDescription(task),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                task.lastStatus?.let { status ->
                                    Text(
                                        text = statusLabel(status),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LocalContentColor.current.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = task.enabled,
                                    onCheckedChange = { vm.setEnabled(task.id, it) },
                                )
                                IconButton(onClick = { pendingDelete = task }) {
                                    Icon(
                                        HugeIcons.Delete01,
                                        contentDescription = stringResource(R.string.delete),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.scheduled_task_delete_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.scheduled_task_delete_confirm_message))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(task.id)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun scheduleDescription(task: ScheduledTaskEntity): String {
    val payload = SchedulePayload.decode(task.schedulePayload)
    val type = runCatching { ScheduleType.valueOf(task.scheduleType) }.getOrDefault(ScheduleType.DAILY)
    return when (type) {
        ScheduleType.ONCE ->
            stringResource(R.string.scheduled_task_type_once) + " · " + formatDateTime(payload.runAt)

        ScheduleType.DAILY ->
            stringResource(R.string.scheduled_task_type_daily) + " · " + formatTime(payload.hour, payload.minute)

        ScheduleType.WEEKLY ->
            stringResource(R.string.scheduled_task_type_weekly) + " · " +
                DayOfWeek.of(payload.dayOfWeek.coerceIn(1, 7)).getDisplayName(TextStyle.SHORT, Locale.getDefault()) +
                " " + formatTime(payload.hour, payload.minute)

        ScheduleType.INTERVAL ->
            stringResource(R.string.scheduled_task_type_interval, payload.intervalMinutes)
    }
}

private fun formatTime(hour: Int, minute: Int): String =
    String.format(Locale.getDefault(), "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

private fun formatDateTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

@Composable
internal fun statusLabel(status: String): String = when (status) {
    TaskRunStatus.OK -> stringResource(R.string.scheduled_task_status_ok)
    TaskRunStatus.FAILED -> stringResource(R.string.scheduled_task_status_failed)
    TaskRunStatus.TIMEOUT -> stringResource(R.string.scheduled_task_status_timeout)
    TaskRunStatus.SKIPPED_MISSED -> stringResource(R.string.scheduled_task_status_skipped_missed)
    TaskRunStatus.TARGET_GONE -> stringResource(R.string.scheduled_task_status_target_gone)
    TaskRunStatus.ASSISTANT_GONE -> stringResource(R.string.scheduled_task_status_assistant_gone)
    else -> status
}

/**
 * 定时任务前置权限/白名单缺口的提示条：文案 + 一个直达系统设置的按钮。
 *
 * 三种缺口（通知权限、电池优化白名单、闹钟和提醒授权）都只是**降级**而不是"不能用"，
 * 所以不用弹窗打断，进页面就能看到一条可点掉的提示即可。
 */
@Composable
private fun TaskPermissionHint(
    text: String,
    action: String,
    onClick: () -> Unit,
) {
    FormItem(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        },
    ) {
        TextButton(onClick = onClick) { Text(action) }
    }
}

/** ColorOS / realme UI 的「应用启动管理」入口（真机 PJX110 上实测存在，见 OplusAppStartupManager）。 */
private const val OPLUS_STARTUP_ACTION = "com.oplus.battery.permission.startup.StartupAppListActivity"
private const val OPLUS_STARTUP_PACKAGE = "com.oplus.battery"
private const val OPLUS_STARTUP_ACTIVITY = "com.oplus.startupapp.view.StartupAppListActivity"
