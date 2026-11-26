package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.haze.GlassTopBarScaffold
import app.androidrik.rikkahub.ui.components.haze.pageGlassTopBarColors
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppSkinHeroCard
import app.androidrik.rikkahub.ui.components.ui.AppSkinListItemIcon
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Clapping01
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.ImageUpload
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.Time02
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.isNotConfigured
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.permission.LocalNetworkPermissionCard
import app.androidrik.rikkahub.ui.components.ui.permission.NotificationPermissionCard
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberLocalNetworkPermissionState
import app.androidrik.rikkahub.ui.components.ui.permission.rememberNotificationPermissionState
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupValueChip
import app.androidrik.rikkahub.ui.components.ui.SegmentedPill
import app.androidrik.rikkahub.ui.pages.setting.search.SettingSearchBar
import app.androidrik.rikkahub.ui.components.ui.Select
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.hooks.rememberColorMode
import app.androidrik.rikkahub.ui.theme.AppSkinRegistry
import app.androidrik.rikkahub.ui.theme.ColorMode
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.ui.theme.LocalAppSkin
import app.androidrik.rikkahub.ui.theme.LocalDarkMode
import app.androidrik.rikkahub.utils.openUrl
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.web.WebServerManager
import app.androidrik.rikkahub.web.WebServerState
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val showFeatureShortcut = LocalAppSkin.current.skin.hero.gradientFor(LocalDarkMode.current) != null
    val filesManager: FilesManager = koinInject()
    val webServerManager: WebServerManager = koinInject()
    val webServerState by webServerManager.state.collectAsStateWithLifecycle()
    val skins by AppSkinRegistry.shared.skins.collectAsState()
    val activeSkinName = skins.firstOrNull { it.id == settings.displaySetting.appSkinId }?.name
    val summaries = remember(settings, webServerState, activeSkinName) {
        resolveSettingHomeSummaries(
            settings = settings,
            webServerState = webServerState,
            skinName = activeSkinName,
        )
    }
    val localNetworkPermission = rememberLocalNetworkPermissionState()
    localNetworkPermission?.let { PermissionManager(it) }
    val notificationPermission = rememberNotificationPermissionState()
    notificationPermission?.let { PermissionManager(it) }

    GlassTopBarScaffold(
        displaySetting = settings.displaySetting,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(text = stringResource(R.string.settings))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = pageGlassTopBarColors(CustomColors.topBarColors)
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("searchEntry") {
                SettingSearchBar(
                    onClick = { navController.navigate(Screen.SettingGlobalSearch) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            if (showFeatureShortcut) {
                item("featureShortcut") {
                    SettingFeatureShortcut(
                        taskCenterEnabled = settings.taskCenterEnabled,
                        onNavigate = { navController.navigate(it) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            if (localNetworkPermission?.allRequiredPermissionsGranted == false) {
                item("localNetworkPermission") {
                    LocalNetworkPermissionCard(
                        permissionState = localNetworkPermission,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            if (notificationPermission?.allRequiredPermissionsGranted == false) {
                item("notificationPermission") {
                    NotificationPermissionCard(
                        permissionState = notificationPermission,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }

            item("generalSettings") {
                var colorMode by rememberColorMode()
                val selectedColorModeText = when (colorMode) {
                    ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                    ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                    ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_general_settings)) },
                ) {
                    item(
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Sun01, null) },
                        trailingContent = {
                            SegmentedPill(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = { colorMode = it },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.width(162.dp)
                            )
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_color_mode)) },
                        supportingContent = { Text(selectedColorModeText) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingPreferences) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Settings03, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_preferences_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_preferences)) },
                        trailingValue = {
                            CardGroupValueChip(
                                summaries.skinName ?: stringResource(R.string.setting_page_summary_default_skin),
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Assistant) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.LookTop, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_assistant_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
                        trailingValue = {
                            CardGroupValueChip(
                                stringResource(R.string.setting_page_summary_count, summaries.assistantCount),
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Extensions) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_extensions_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_extensions)) },
                    )
                }
            }

            item("aiAndGeneration") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_ai_and_generation)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingModels) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.AiMagic, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_default_model_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_default_model)) },
                        trailingValue = {
                            CardGroupValueChip(
                                text = summaries.defaultModelName
                                    ?: stringResource(R.string.setting_page_summary_unset),
                                emphasized = summaries.defaultModelName != null,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingProvider) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Brain02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_providers_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
                        trailingValue = {
                            CardGroupValueChip(
                                text = if (summaries.enabledProviderCount > 0) {
                                    stringResource(
                                        R.string.setting_page_summary_enabled_count,
                                        summaries.enabledProviderCount,
                                    )
                                } else {
                                    stringResource(R.string.setting_page_summary_none)
                                },
                                emphasized = summaries.enabledProviderCount > 0,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingMedia) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Image02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_media_and_speech_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_media_and_speech)) },
                        trailingValue = {
                            CardGroupValueChip(
                                text = if (summaries.mediaSpeechProviderCount > 0) {
                                    stringResource(
                                        R.string.setting_page_summary_count,
                                        summaries.mediaSpeechProviderCount,
                                    )
                                } else {
                                    stringResource(R.string.setting_page_summary_none)
                                },
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingSearch) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.GlobalSearch, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_search_service_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
                        trailingValue = summaries.selectedSearchServiceName?.let { serviceName ->
                            { CardGroupValueChip(serviceName) }
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingKnowledgeBase) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Book02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_kb_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_kb)) },
                    )
                    // 任务中心开启后定时任务管理入口迁入任务中心（二级详情页），设置页不再重复
                    if (!settings.taskCenterEnabled) {
                        item(
                            onClick = { navController.navigate(Screen.SettingScheduledTasks) },
                            leadingContent = { AppSkinListItemIcon(HugeIcons.Time02, null) },
                            supportingContent = { Text(stringResource(R.string.setting_page_scheduled_tasks_desc)) },
                            headlineContent = { Text(stringResource(R.string.setting_page_scheduled_tasks)) },
                        )
                    }
                }
            }

            item("connectionAndData") {
                val scope = rememberCoroutineScope()
                var storageState by remember { mutableStateOf(-1 to 0L) }
                // 每次回到设置页重算：从文件管理/缓存管理返回后数字不会停留在旧值
                LifecycleResumeEffect(filesManager) {
                    scope.launch { storageState = filesManager.countChatFiles() }
                    onPauseOrDispose { }
                }
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_connection_and_data)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingMcp) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.McpServer, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_mcp_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
                        trailingValue = {
                            CardGroupValueChip(
                                text = if (summaries.enabledMcpCount > 0) {
                                    stringResource(
                                        R.string.setting_page_summary_enabled_count,
                                        summaries.enabledMcpCount,
                                    )
                                } else {
                                    stringResource(R.string.setting_page_summary_none)
                                },
                                emphasized = summaries.enabledMcpCount > 0,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingWeb) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.ServerStack01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server)) },
                        trailingValue = {
                            CardGroupValueChip(
                                text = if (summaries.webServerRunning) {
                                    stringResource(
                                        R.string.setting_page_summary_web_running,
                                        summaries.webServerPort,
                                    )
                                } else {
                                    stringResource(R.string.setting_page_summary_web_stopped)
                                },
                                emphasized = summaries.webServerRunning,
                            )
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Backup) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Database02, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_data_backup_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_data_backup)) },
                        trailingValue = {
                            val (labelRes, active) = when (summaries.backupMode) {
                                SettingBackupSummaryMode.AUTO_SNAPSHOT ->
                                    R.string.setting_page_summary_backup_auto_snapshot to true
                                SettingBackupSummaryMode.REMINDER ->
                                    R.string.setting_page_summary_backup_reminder to true
                                SettingBackupSummaryMode.MANUAL ->
                                    R.string.setting_page_summary_backup_manual to false
                            }
                            CardGroupValueChip(stringResource(labelRes), emphasized = active)
                        },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingFiles) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.ImageUpload, null) },
                        supportingContent = {
                            if (storageState.first == -1) {
                                Text(stringResource(R.string.calculating))
                            } else {
                                Text(
                                    stringResource(
                                        R.string.setting_page_chat_storage_desc,
                                        storageState.first,
                                        storageState.second / 1024 / 1024.0
                                    )
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_chat_storage)) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.SettingStorage) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Package, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_storage_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_storage)) },
                    )
                }
            }

            item("aboutSettings") {
                val context = LocalContext.current
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_about)) },
                ) {
                    item(
                        onClick = { navController.navigate(Screen.SettingAbout) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Clapping01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_about_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_about)) },
                        trailingValue = { CardGroupValueChip(BuildConfig.VERSION_NAME) },
                    )
                    item(
                        onClick = { navController.navigate(Screen.Log) },
                        leadingContent = { AppSkinListItemIcon(HugeIcons.Bookshelf01, null) },
                        supportingContent = { Text(stringResource(R.string.setting_page_request_logs_desc)) },
                        headlineContent = { Text(stringResource(R.string.setting_page_request_logs)) },
                    )
                }
            }
        }
    }
}

internal enum class SettingBackupSummaryMode {
    AUTO_SNAPSHOT,
    REMINDER,
    MANUAL,
}

internal data class SettingHomeSummaries(
    val skinName: String?,
    val assistantCount: Int,
    val defaultModelName: String?,
    val enabledProviderCount: Int,
    val mediaSpeechProviderCount: Int,
    val selectedSearchServiceName: String?,
    val enabledMcpCount: Int,
    val webServerRunning: Boolean,
    val webServerPort: Int,
    val backupMode: SettingBackupSummaryMode,
)

internal fun resolveSettingHomeSummaries(
    settings: Settings,
    webServerState: WebServerState,
    skinName: String?,
): SettingHomeSummaries {
    val defaultModel = settings.findModelById(settings.chatModelId)
    val selectedSearch = settings.searchServices.getOrNull(settings.searchServiceSelected)
        ?: settings.searchServices.firstOrNull { it.enabled }
    val backupMode = when {
        settings.localSnapshotConfig.enabled -> SettingBackupSummaryMode.AUTO_SNAPSHOT
        settings.backupReminderConfig.enabled -> SettingBackupSummaryMode.REMINDER
        else -> SettingBackupSummaryMode.MANUAL
    }
    return SettingHomeSummaries(
        skinName = skinName?.takeIf { it.isNotBlank() },
        assistantCount = settings.assistants.size,
        defaultModelName = defaultModel?.displayName?.takeIf { it.isNotBlank() }
            ?: defaultModel?.modelId?.takeIf { it.isNotBlank() },
        enabledProviderCount = settings.providers.count { it.enabled },
        mediaSpeechProviderCount = settings.mediaGenerationProviders.size +
            settings.ttsProviders.size +
            settings.asrProviders.size,
        selectedSearchServiceName = selectedSearch?.displayName?.takeIf { it.isNotBlank() },
        enabledMcpCount = settings.mcpServers.count { it.commonOptions.enable },
        webServerRunning = webServerState.isRunning,
        webServerPort = webServerState.port,
        backupMode = backupMode,
    )
}

@Composable
internal fun SettingFeatureShortcut(
    taskCenterEnabled: Boolean,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSkinHeroCard(
        title = stringResource(R.string.setting_page_feature_shortcut_title),
        description = stringResource(R.string.setting_page_feature_shortcut_desc),
        icon = HugeIcons.Time02,
        onClick = { onNavigate(if (taskCenterEnabled) Screen.TaskCenter else Screen.SettingScheduledTasks) },
        modifier = modifier,
    )
}

@Composable
private fun ProviderConfigWarningCard(navController: Navigator) {
    Card(
        modifier = Modifier.padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(HugeIcons.Alert01, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

