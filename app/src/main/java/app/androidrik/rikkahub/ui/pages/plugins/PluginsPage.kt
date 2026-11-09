package app.androidrik.rikkahub.ui.pages.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.plugin.js.JsPluginDefinition
import app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller

import app.androidrik.rikkahub.data.plugin.js.hookNames
import app.androidrik.rikkahub.data.plugin.rules.JsonRuleDefinition
import app.androidrik.rikkahub.data.plugin.rules.RulePluginInstaller
import app.androidrik.rikkahub.data.plugin.rules.RuleTarget
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
/** SAF 文件导入的大小上限（与 JsPluginInstaller 的 URL 抓取上限同口径）。 */
private const val MAX_IMPORT_FILE_BYTES = 512 * 1024


/**
 * 插件中心。
 *
 * 第一期:内置插件列表 + 启用开关 + 能力摘要。
 * 第二期:JSON 规则插件导入(URL / 粘贴),导入前必须确认能力声明。
 * 第三期:JS 沙箱插件导入,能力可逐项勾选(含可选的网络能力)。
 */
@Composable
fun PluginsPage() {
    val manager: PluginManager = koinInject()
    val ruleInstaller: RulePluginInstaller = koinInject()
    val jsInstaller: JsPluginInstaller = koinInject()
    val plugins by manager.plugins.collectAsStateWithLifecycle()
    // 变更操作挂在应用作用域:页面离开/重组不应把"已注册但未落盘"的中间态留在磁盘上
    val appScope: AppScope = koinInject()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current

    var showRuleImport by remember { mutableStateOf(false) }
    var showJsImport by remember { mutableStateOf(false) }
    var pendingRule by remember { mutableStateOf<JsonRuleDefinition?>(null) }
    var pendingJs by remember { mutableStateOf<JsPluginDefinition?>(null) }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.plugins_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("intro") {
                Text(
                    text = stringResource(R.string.plugins_page_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item("import_rule") {
                ImportCard(
                    title = stringResource(R.string.plugins_page_rule_plugin_title),
                    subtitle = stringResource(R.string.plugins_page_rule_plugin_subtitle),
                    busy = busy,
                    onImport = { showRuleImport = true },
                )
            }

            item("import_js") {
                ImportCard(
                    title = stringResource(R.string.plugins_page_js_plugin_title),
                    subtitle = stringResource(R.string.plugins_page_js_plugin_subtitle),
                    busy = busy,
                    onImport = { showJsImport = true },
                )
            }

            if (plugins.isEmpty()) {
                item("empty") {
                    Text(
                        text = stringResource(R.string.plugins_page_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(plugins, key = { it.plugin.id }) { info ->
                PluginRow(
                    info = info,
                    onToggle = { enabled ->
                        appScope.launch { manager.setEnabled(info.plugin.id, enabled) }
                    },
                    onClick = { navController.navigate(Screen.PluginDetail(info.plugin.id)) },
                )
            }
        }
    }

    if (showRuleImport) {
        ImportSourceDialog(
            title = stringResource(R.string.plugins_page_import_rule_title),
            urlLabel = stringResource(R.string.plugins_page_import_rule_url_label),
            bodyLabel = stringResource(R.string.plugins_page_import_rule_body_label),
            onDismiss = { showRuleImport = false },
            onParsed = { definition ->
                showRuleImport = false
                pendingRule = definition
            },
            parseText = ruleInstaller::parseText,
            fetch = ruleInstaller::fetch,
        )
    }

    if (showJsImport) {
        ImportSourceDialog(
            title = stringResource(R.string.plugins_page_import_js_title),
            urlLabel = stringResource(R.string.plugins_page_import_js_url_label),
            bodyLabel = stringResource(R.string.plugins_page_import_js_body_label),
            onDismiss = { showJsImport = false },
            onParsed = { definition ->
                showJsImport = false
                pendingJs = definition
            },
            parseText = jsInstaller::parseText,
            fetch = jsInstaller::fetch,
        )
    }

    pendingRule?.let { definition ->
        RuleApprovalDialog(
            definition = definition,
            onDismiss = { pendingRule = null },
            onConfirm = {
                pendingRule = null
                busy = true
                appScope.launch {
                    val result = ruleInstaller.install(
                        definition = definition,
                        approvedCapabilities = setOf(definition.requiredCapability),
                    )
                    busy = false
                    result
                        .onSuccess {
                            toaster.show(
                                message = context.getString(R.string.plugins_page_installed_toast, definition.name),
                                type = ToastType.Success,
                            )
                        }
                        .onFailure { error ->
                            toaster.show(
                                message = context.getString(R.string.plugins_page_install_failed_toast, error.message),
                                type = ToastType.Error,
                            )
                        }
                }
            },
        )
    }

    pendingJs?.let { definition ->
        JsApprovalDialog(
            definition = definition,
            onDismiss = { pendingJs = null },
            onConfirm = { approved ->
                pendingJs = null
                busy = true
                appScope.launch {
                    // 修订通道 R1：同 id 导入 = 提交一次修订（预检→代次切换→历史落盘）；
                    // 预检失败时旧定义继续服务，失败文本为逐项预检报告
                    val result = jsInstaller.installOrUpdate(definition, approved)
                    busy = false
                    result
                        .onSuccess {
                            toaster.show(
                                message = context.getString(R.string.plugins_page_installed_toast, definition.manifest.name),
                                type = ToastType.Success,
                            )
                        }
                        .onFailure { error ->
                            toaster.show(
                                message = context.getString(R.string.plugins_page_install_failed_toast, error.message),
                                type = ToastType.Error,
                            )
                        }
                }
            },
        )
    }
}

@Composable
private fun ImportCard(
    title: String,
    subtitle: String,
    busy: Boolean,
    onImport: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                TextButton(onClick = onImport) {
                    Text(stringResource(R.string.common_import))
                }
            }
        }
    }
}

@Composable
private fun <T> ImportSourceDialog(
    title: String,
    urlLabel: String,
    bodyLabel: String,
    onDismiss: () -> Unit,
    onParsed: (T) -> Unit,
    parseText: (String) -> Result<T>,
    fetch: suspend (String) -> Result<T>,
) {
    var url by remember { mutableStateOf("") }
    var bodyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // SAF 文件导入（R3 增补）：读设备内 json/js 文本填进正文框，复用既有解析→能力确认链路；
    // ACTION_OPEN_DOCUMENT 经 ContentResolver 读取，不需要任何存储权限
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true
            error = null
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > MAX_IMPORT_FILE_BYTES) {
                            error = "文件超过 ${MAX_IMPORT_FILE_BYTES / 1024} KB 上限"
                            null
                        } else {
                            String(bytes, Charsets.UTF_8)
                        }
                    }
                    if (text != null) {
                        bodyText = text
                        parseText(text)
                            .onSuccess(onParsed)
                            .onFailure { error = it.message }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "读取文件失败: ${e.message}"
                } finally {
                    loading = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(urlLabel) },
                    singleLine = true,
                    enabled = !loading,
                    // URL 键盘：Gboard 中文布局会把 ":" "/" 智能标点成全角，纯文本框必炸
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = {
                        bodyText = it
                        error = null
                    },
                    label = { Text(bodyLabel) },
                    minLines = 4,
                    maxLines = 8,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = !loading,
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                ) {
                    Text("从文件选择…")
                }
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = stringResource(R.string.plugins_page_import_capability_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && (url.isNotBlank() || bodyText.isNotBlank()),
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        val result = if (bodyText.isNotBlank()) {
                            parseText(bodyText)
                        } else {
                            fetch(url)
                        }
                        loading = false
                        result
                            .onSuccess(onParsed)
                            .onFailure { error = it.message ?: context.getString(R.string.plugins_page_parse_failed) }
                    }
                },
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text(stringResource(R.string.plugins_page_parse))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun RuleApprovalDialog(
    definition: JsonRuleDefinition,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_page_rule_approve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = definition.name, style = MaterialTheme.typography.titleMedium)
                if (definition.description.isNotBlank()) {
                    Text(
                        text = definition.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.plugin_target_scope,
                        if (definition.target == RuleTarget.INPUT) {
                            stringResource(R.string.plugin_target_input)
                        } else {
                            stringResource(R.string.plugin_target_output)
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.plugins_page_rule_count, definition.rules.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.plugins_page_requested_capability,
                        capabilityLabel(definition.requiredCapability),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.plugins_page_rule_approve_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.plugins_page_approve_and_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun JsApprovalDialog(
    definition: JsPluginDefinition,
    onDismiss: () -> Unit,
    onConfirm: (Set<PluginCapability>) -> Unit,
) {
    val manifest = definition.manifest
    val declared = manifest.capabilities
    var selected by remember(definition) { mutableStateOf(declared) }
    // 变换/工具能力安装时必需；网络和隔离存储可选，安装后均可撤销。
    val required = declared - setOf(PluginCapability.NETWORK, PluginCapability.STORAGE)
    val canInstall = required.all { it in selected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plugins_page_js_approve_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = manifest.name, style = MaterialTheme.typography.titleMedium)
                if (manifest.description.isNotBlank()) {
                    Text(
                        text = manifest.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.plugins_page_js_target_hooks,
                        stringResource(
                            when (manifest.target.name) {
                                "INPUT" -> R.string.plugin_target_input
                                "OUTPUT" -> R.string.plugin_target_output
                                "NONE" -> R.string.plugin_target_tool_only
                                else -> R.string.plugin_target_input_output
                            }
                        ),
                        definition.hookNames.joinToString(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.plugins_page_js_source_size_timeout,
                        definition.source.toByteArray().size / 1024,
                        manifest.timeoutMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                manifest.tools.forEach { tool ->
                    Text(
                        stringResource(R.string.plugin_tool_line, tool.name, tool.description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (manifest.networkHosts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.plugin_network_whitelist, manifest.networkHosts.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.plugins_page_capability_grant_header),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                declared.forEach { capability ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = capability in selected,
                            // 变换能力是插件存在意义,锁定勾选;网络是可选能力,允许撤销
                            enabled = capability in setOf(PluginCapability.NETWORK, PluginCapability.STORAGE),
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + capability else selected - capability
                            },
                        )
                        Column {
                            Text(capabilityLabel(capability), style = MaterialTheme.typography.bodyMedium)
                            if (capability == PluginCapability.NETWORK) {
                                Text(
                                    text = stringResource(R.string.plugins_page_network_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.plugins_page_js_approve_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canInstall,
                onClick = { onConfirm(selected) },
            ) {
                Text(stringResource(R.string.plugins_page_approve_and_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun PluginRow(
    info: PluginInfo,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            info.plugin.icon?.let { icon ->
                Icon(imageVector = icon, contentDescription = null)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (info.plugin.icon != null) 12.dp else 0.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = info.plugin.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = info.plugin.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.plugin.unavailable) {
                    Text(
                        text = info.plugin.unavailableReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CapabilitySummary(info)
            }
            Switch(
                checked = info.enabled,
                onCheckedChange = onToggle,
                enabled = !info.plugin.unavailable,
            )
        }
    }
}

/** 能力摘要:只展示已声明能力;已撤销的不显示。 */
@Composable
private fun CapabilitySummary(info: PluginInfo) {
    val declared = info.plugin.capabilities
    if (declared.isEmpty()) return
    val granted = declared.intersect(info.grants)
    Text(
        text = if (granted.isEmpty()) {
            stringResource(R.string.plugins_page_all_capabilities_revoked)
        } else {
            granted.map { capabilityLabel(it) }.joinToString(" · ") +
                if (granted.size < declared.size) {
                    stringResource(R.string.plugins_page_revoked_count_suffix, declared.size - granted.size)
                } else {
                    ""
                }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun capabilityLabel(capability: PluginCapability): String = when (capability) {
    PluginCapability.NETWORK -> stringResource(R.string.plugin_capability_network)
    PluginCapability.HISTORY_READ -> stringResource(R.string.plugin_capability_history_read)
    PluginCapability.CONVERSATION_MUTATE -> stringResource(R.string.plugin_capability_conversation_mutate)
    PluginCapability.TOOL_REGISTER -> stringResource(R.string.plugin_capability_tool_register)
    PluginCapability.PROMPT_TRANSFORM -> stringResource(R.string.plugin_capability_prompt_transform)
    PluginCapability.OUTPUT_TRANSFORM -> stringResource(R.string.plugin_capability_output_transform)
    PluginCapability.STORAGE -> stringResource(R.string.plugin_capability_storage)
}
