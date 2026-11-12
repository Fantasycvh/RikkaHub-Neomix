package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.tools.local.LocalToolOption
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_BLOCKED_APPS
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.phoneuse.PhoneUseManager
import app.androidrik.rikkahub.phoneuse.isFloatingChatWindowOn
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionInfo
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.hasUsageStatsPermission
import app.androidrik.rikkahub.utils.openAccessibilitySettings
import app.androidrik.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val settingsStore: SettingsStore = koinInject()
    val phoneUseManager: PhoneUseManager = koinInject()
    val phoneUseServiceOn = phoneUseManager.controller.collectAsStateWithLifecycle().value != null
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var showPhoneUseBlockedApps by remember { mutableStateOf(false) }
    val phoneUseBlockedPackages = settings.displaySetting.phoneUseBlockedPackages
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)
    val floatingWindowRequiredText =
        stringResource(R.string.assistant_page_local_tools_phone_use_floating_window_required)
    val enablePhoneUseServiceText =
        stringResource(R.string.assistant_page_local_tools_phone_use_enable_service)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        // 没有悬浮窗就看不到 AI 在做什么、也不方便随时停下，先去把它打开，这次不开启工具
        if (enabled && option == LocalToolOption.PhoneUse &&
            !isFloatingChatWindowOn(context, settingsStore.settingsFlow.value)
        ) {
            toaster.show(message = floatingWindowRequiredText, type = ToastType.Warning)
            navController.navigate(Screen.SettingPreferencesNotification)
            return
        }
        // 无障碍服务还没开时带用户去系统设置，开关照常打开，开完服务就能用
        if (enabled && option == LocalToolOption.PhoneUse && !phoneUseServiceOn) {
            toaster.show(message = enablePhoneUseServiceText, type = ToastType.Warning)
            context.openAccessibilitySettings()
        }
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // imePadding 在 verticalScroll 之前（视口侧）：键盘弹起先收缩视口，
            // 内容不再随 IME insets 逐帧改高度（否则"滚动到光标"每帧改落点 ⇒ 整页抖动）。
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_javascript_engine_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.JavascriptEngine, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_time_info_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TimeInfo),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TimeInfo, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_clipboard_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Clipboard),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Clipboard, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_tts_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Tts),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Tts, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_ask_user_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.AskUser),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.AskUser, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_screen_time_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ScreenTime),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ScreenTime, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_calendar_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.Calendar),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.Calendar, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_chart_display_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_chart_display_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.ChartDisplay),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.ChartDisplay, it) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_todo_plan_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_local_tools_todo_plan_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.localTools.contains(LocalToolOption.TodoPlan),
                        onCheckedChange = { toggleLocalTool(LocalToolOption.TodoPlan, it) }
                    )
                }
            )
            // 只有 Android 11+ 才有；低版本打开过（比如导入备份）也不会装配
            if (PhoneUseManager.isSupported) {
                val phoneUseEnabled = assistant.localTools.contains(LocalToolOption.PhoneUse)
                item(
                    headlineContent = {
                        Text(stringResource(R.string.assistant_page_local_tools_phone_use_title))
                    },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_desc))
                            Text(
                                text = stringResource(R.string.assistant_page_local_tools_phone_use_interactive_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 开了工具但服务没开时模型调用会直接报错，在这里说明原因
                            if (phoneUseEnabled && !phoneUseServiceOn) {
                                Text(
                                    text = stringResource(R.string.assistant_page_local_tools_phone_use_service_off),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    trailingContent = {
                        Switch(
                            checked = phoneUseEnabled,
                            onCheckedChange = { toggleLocalTool(LocalToolOption.PhoneUse, it) }
                        )
                    }
                )
                if (phoneUseEnabled) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_approval_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_approval_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = assistant.phoneUseApproval,
                                onCheckedChange = { onUpdate(assistant.copy(phoneUseApproval = it)) }
                            )
                        }
                    )
                    item(
                        onClick = { showPhoneUseBlockedApps = true },
                        headlineContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_apps_desc))
                        },
                        trailingContent = {
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_local_tools_phone_use_blocked_apps_count,
                                    phoneUseBlockedPackages.size,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    )
                }
            }
        }

        // AI 管理任务（Yuihub 形态，design-proactive-task-management.md §三.2）：
        // 能力层总开关（工具面挂载）+ 动作层审批分档（默认全开）。
        // 审批开关在能力关闭时置灰——挂载都没有，审批无从谈起。
        CardGroup {
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.taskManageEnabled,
                        onCheckedChange = { onUpdate(assistant.copy(taskManageEnabled = it)) }
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_create_approval))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_create_approval_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.taskCreateApproval,
                        onCheckedChange = { onUpdate(assistant.copy(taskCreateApproval = it)) },
                        enabled = assistant.taskManageEnabled,
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_modify_approval))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_modify_approval_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.taskModifyApproval,
                        onCheckedChange = { onUpdate(assistant.copy(taskModifyApproval = it)) },
                        enabled = assistant.taskManageEnabled,
                    )
                }
            )
            item(
                headlineContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_delete_approval))
                },
                supportingContent = {
                    Text(stringResource(R.string.assistant_page_task_manage_delete_approval_desc))
                },
                trailingContent = {
                    Switch(
                        checked = assistant.taskDeleteApproval,
                        onCheckedChange = { onUpdate(assistant.copy(taskDeleteApproval = it)) },
                        enabled = assistant.taskManageEnabled,
                    )
                }
            )
        }
    }

    if (showPhoneUseBlockedApps) {
        PhoneUseBlockedAppsSheet(
            userBlocked = phoneUseBlockedPackages,
            onAdd = { packageName ->
                coroutineScope.launch {
                    settingsStore.update { current ->
                        current.copy(
                            displaySetting = current.displaySetting.copy(
                                phoneUseBlockedPackages = current.displaySetting.phoneUseBlockedPackages + packageName,
                            )
                        )
                    }
                }
            },
            onRemove = { packageName ->
                coroutineScope.launch {
                    settingsStore.update { current ->
                        current.copy(
                            displaySetting = current.displaySetting.copy(
                                phoneUseBlockedPackages = current.displaySetting.phoneUseBlockedPackages - packageName,
                            )
                        )
                    }
                }
            },
            onDismiss = { showPhoneUseBlockedApps = false },
        )
    }
}

private val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

@Composable
private fun PhoneUseBlockedAppsSheet(
    userBlocked: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    // 只允许完全展开：默认半展开 + 键盘弹起会把输入框压在 IME 下面
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_sheet_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppOutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        invalid = false
                    },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_placeholder))
                    },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                Button(
                    onClick = {
                        val packageName = input.trim()
                        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
                            invalid = true
                            return@Button
                        }
                        onAdd(packageName)
                        input = ""
                        invalid = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_local_tools_phone_use_blocked_add))
                }
            }
            if (invalid) {
                Text(
                    text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_builtin),
                style = MaterialTheme.typography.labelLarge,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PHONE_USE_BLOCKED_APPS.sorted().forEach { packageName ->
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_extra),
                style = MaterialTheme.typography.labelLarge,
            )
            if (userBlocked.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_page_local_tools_phone_use_blocked_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                userBlocked.sorted().forEach { packageName ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = packageName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        IconButton(onClick = { onRemove(packageName) }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = stringResource(
                                    R.string.assistant_page_local_tools_phone_use_blocked_remove
                                ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
