package app.androidrik.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.StopCircle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.Stop
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.service.WebServerService
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionLocalNetwork
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionNotification
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import app.androidrik.rikkahub.web.WebServerManager
import org.koin.compose.koinInject

/** 端口 / 口令的本地输入去抖（提交设置时用；避免逐键 DataStore 写入与回写竞态）。 */
private const val PORT_SAVE_DEBOUNCE_MS = 400L
private const val PASSWORD_SAVE_DEBOUNCE_MS = 400L

private const val PASSWORD_REQUIRED_TOAST =
    "Set a non-empty access password first / 请先设置非空访问口令"
private const val START_SERVER_FAILED_TOAST =
    "Unable to start web server / 无法启动 Web 服务"
private const val SETTINGS_LOADING_TOAST =
    "Settings are still loading, please retry / 设置仍在加载，请稍后重试"

@Composable
fun SettingWebPage() {
    val webServerManager: WebServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    // 页面可能在 Settings 从 DataStore 加载完成前就被组合；用 AppScope 兜底「退出页面时补写草稿」，
    // 避免 400ms 去抖窗口内返回导致输入丢失（组合作用域会被取消）。
    val appScope: AppScope = koinInject()
    val settings = LocalSettings.current
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    // 本地输入态**不**以 settings 值作 remember key：旧写法在每次设置回写（逐键 update 或
    // 去抖提交）时会把正在输入的文本重置成已提交的前缀，表现为丢字。
    // 但必须随 `settings.init` 重置一次：首屏可能在加载完成前组合，dummy Settings 的默认值
    // 不能当成用户输入去覆盖真实设置。
    var portText by remember(settings.init) { mutableStateOf(settings.webServerPort.toString()) }
    var accessPasswordText by remember(settings.init) { mutableStateOf(settings.webServerAccessPassword) }
    // 只有用户真的改过才在退出时补写，避免把「加载后未编辑的旧草稿」反写回设置。
    var portDirty by remember(settings.init) { mutableStateOf(false) }
    var passwordDirty by remember(settings.init) { mutableStateOf(false) }
    var passwordVisible by remember {
        mutableStateOf(false)
    }

    // 去抖提交：旧实现每次按键都 `scope.launch { settingsStore.update }`，高频 DataStore 写
    // 与回写会和输入态互相踩。这里只在停止输入 400ms 后提交一次。
    LaunchedEffect(portText) {
        if (settings.init) return@LaunchedEffect
        val parsed = portText.toIntOrNull()
        if (parsed != null && parsed in 1024..65535 && parsed != settings.webServerPort) {
            delay(PORT_SAVE_DEBOUNCE_MS)
            settingsStore.update { it.copy(webServerPort = parsed) }
        }
    }
    LaunchedEffect(accessPasswordText) {
        if (settings.init) return@LaunchedEffect
        if (accessPasswordText != settings.webServerAccessPassword) {
            delay(PASSWORD_SAVE_DEBOUNCE_MS)
            // 安全策略：只写口令。**绝不**在这里把 webServerJwtEnabled 置 false——
            // 「清空口令」不关闭鉴权，口令为空时由 WebServerManager 拒绝启动。
            settingsStore.update { it.copy(webServerAccessPassword = accessPasswordText) }
        }
    }

    // 去抖窗口内退出页面（返回/切页）时补写草稿：LaunchedEffect 会随组合销毁而被取消，
    // 若不补写，刚输入的口令（甚至清空操作）会丢失。
    // key 用 `settings.init`：加载完成时草稿/脏标记会随 remember(settings.init) 换成新 state，
    // 若仍用 Unit，onDispose 闭包会读到**旧 state 对象**（还是 dummy 值）。
    DisposableEffect(settings.init) {
        onDispose {
            val current = settingsStore.settingsFlow.value
            // 页面关闭时 Settings 仍未加载完成：dummy 值不可信，宁可不写。
            if (current.init) return@onDispose
            val draftPort = portText.toIntOrNull()?.takeIf { it in 1024..65535 }
            val draftPassword = accessPasswordText
            val shouldSavePort = portDirty && draftPort != null && draftPort != current.webServerPort
            val shouldSavePassword = passwordDirty && draftPassword != current.webServerAccessPassword
            if (!shouldSavePort && !shouldSavePassword) return@onDispose
            appScope.launch {
                settingsStore.update {
                    it.copy(
                        webServerPort = if (shouldSavePort) draftPort ?: it.webServerPort else it.webServerPort,
                        webServerAccessPassword = if (shouldSavePassword) draftPassword else it.webServerAccessPassword,
                    )
                }
            }
        }
    }

    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
            if (Build.VERSION.SDK_INT >= 37 && !settings.webServerLocalhostOnly) {
                add(PermissionLocalNetwork)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    /** 启动前把去抖窗口内的端口/口令**同步**落盘，避免服务读到旧值（尤其口令为空）。 */
    suspend fun commitDraftSettings() {
        val current = settingsStore.settingsFlow.value
        // dummy Settings 不可信也不可写（SettingsStore.update 会丢弃，但别拿它的值去拼回写）。
        if (current.init) return
        val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: current.webServerPort
        settingsStore.update {
            it.copy(
                webServerPort = port,
                webServerAccessPassword = accessPasswordText,
            )
        }
        portDirty = false
        passwordDirty = false
    }

    fun startWebServer() {
        val latest = settingsStore.settingsFlow.value
        // 双保险：空口令不允许启动（WebServerManager 也会拒绝，这里给即时反馈）。
        if (accessPasswordText.isBlank() || latest.webServerAccessPassword.isBlank()) {
            toaster.show(PASSWORD_REQUIRED_TOAST)
            return
        }
        val intent = Intent(context, WebServerService::class.java).apply {
            action = WebServerService.ACTION_START
            putExtra(WebServerService.EXTRA_PORT, latest.webServerPort)
            putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, latest.webServerLocalhostOnly)
        }
        // 只有 FGS 真正拉起来才把 enabled 置 true；否则失败后会在下次启动继续自动拉起，
        // 反复出现「启动失败→通知驻留→下次再失败」的循环。
        val started = runCatching { context.startForegroundService(intent) }
            .onFailure { toaster.show("$START_SERVER_FAILED_TOAST: ${it.message}") }
            .isSuccess
        if (!started) return
        scope.launch {
            settingsStore.update { it.copy(webServerEnabled = true) }
        }
    }

    /** 点击「启动」：先校验口令，权限齐全则提交草稿后启动，否则请求权限（授权后自动续跑）。 */
    fun requestServerStart() {
        // 首屏 Settings 可能仍是 dummy：此时读写会污染真实设置，先提示等待。
        if (settingsStore.settingsFlow.value.init) {
            toaster.show(SETTINGS_LOADING_TOAST)
            return
        }
        if (accessPasswordText.isBlank()) {
            toaster.show(PASSWORD_REQUIRED_TOAST)
            return
        }
        if (permissionState.allPermissionsGranted) {
            scope.launch {
                commitDraftSettings()
                startWebServer()
            }
        } else {
            pendingStart = true
            permissionState.requestPermissions()
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {
            pendingStart = false
            commitDraftSettings()
            startWebServer()
        }
    }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        toaster.show(copiedText)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        requestServerStart()
                    } else {
                        val intent = Intent(context, WebServerService::class.java).apply {
                            action = WebServerService.ACTION_STOP
                        }
                        context.startService(intent)
                        scope.launch {
                            settingsStore.update { it.copy(webServerEnabled = false) }
                        }
                    }
                },
                icon = {
                    if (serverState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (serverState.isRunning) {
                            stringResource(R.string.setting_page_web_server_stop)
                        } else {
                            stringResource(R.string.setting_page_web_server_start)
                        }
                    )
                },
                containerColor = if (serverState.isRunning) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = portText,
                                // 只更新本地态；落盘交给上面的去抖 LaunchedEffect（避免逐键写 + 回写竞态）。
                                onValueChange = { value ->
                                    portText = value.filter { it.isDigit() }
                                    portDirty = true
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                modifier = Modifier.width(100.dp),
                                // 未加载完成 / 运行中都不允许编辑（dummy Settings 的默认值不可信）。
                                enabled = !serverState.isRunning && !settings.init,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.webServerLocalhostOnly,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(webServerLocalhostOnly = checked)
                                        }
                                    }
                                },
                                // 运行中不允许切换 需重启服务生效；加载完成前也不允许写 dummy 设置。
                                enabled = !serverState.isRunning && !settings.init,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable_desc)) },
                        trailingContent = {
                            // 安全策略：**不再提供「不鉴权」模式**。该开关恒为开且不可操作；
                            // 存量设置里的 false 由 PreferencesStore 读路径强制纠正，
                            // WebServerManager 启动前还会再校验一次口令与鉴权开关。
                            Switch(
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_password)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_password_desc)) },
                        trailingContent = {
                            TextField(
                                value = accessPasswordText,
                                // 只更新本地态；去抖后单独提交口令。**不再**连带写
                                // webServerJwtEnabled（清空口令不关闭鉴权；空口令由 Manager 拒绝启动）。
                                onValueChange = { value ->
                                    accessPasswordText = value
                                    passwordDirty = true
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = null
                                        )
                                    }
                                },
                                singleLine = true,
                                // 口令是强制的：为空即标错（启动按钮/Manager 也会拒绝）。
                                isError = accessPasswordText.isBlank(),
                                enabled = !settings.init,
                                modifier = Modifier.width(180.dp),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            // 取不到 WiFi IP（如移动数据下）时不显示可点击的假地址——那时
                            // address 为 null，旧写法会 `?: "localhost"` 把「本机地址」冒充成
                            // 「局域网地址」，误导用户复制到一个外部设备打不开的 URL。
                            val address = serverState.address
                            if (address != null) {
                                val lanUrl = "http://$address:$port"
                                item(
                                    onClick = { copyUrl(lanUrl) },
                                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_lan_address)) },
                                    supportingContent = { Text(lanUrl) },
                                )
                            } else {
                                item(
                                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_lan_address)) },
                                    supportingContent = {
                                        Text(
                                            text = stringResource(R.string.setting_page_web_server_lan_unavailable),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }

                            if (serverState.hostname != null) {
                                val mdnsUrl = "http://${serverState.hostname}:$port"
                                item(
                                    onClick = { copyUrl(mdnsUrl) },
                                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_mdns_address)) },
                                    supportingContent = { Text(mdnsUrl) },
                                )
                            }
                        }

                        val localUrl = "http://localhost:$port"
                        item(
                            onClick = { copyUrl(localUrl) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server_local_address)) },
                            supportingContent = { Text(localUrl) },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (serverState.error != null) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_web_server_error),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = serverState.error ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
