package app.androidrik.rikkahub.ui.pages.plugins

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.AppScope
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
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
                title = { Text("插件") },
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
                    text = "插件通过窄接口接入消息转换管线、工具装配与消息渲染。关闭后下一次请求即从管线移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item("import_rule") {
                ImportCard(
                    title = "规则插件",
                    subtitle = "JSON 规则定义的正则替换 / 追加动作,可对输入或输出文本生效",
                    busy = busy,
                    onImport = { showRuleImport = true },
                )
            }

            item("import_js") {
                ImportCard(
                    title = "JS 插件",
                    subtitle = ".js 单文件脚本,在 WebView 沙箱里改写输入/输出,可申请受控网络访问",
                    busy = busy,
                    onImport = { showJsImport = true },
                )
            }

            if (plugins.isEmpty()) {
                item("empty") {
                    Text(
                        text = "暂无已注册插件",
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
            title = "导入规则插件",
            urlLabel = "JSON 链接(https)",
            bodyLabel = "或粘贴 JSON 内容",
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
            title = "导入 JS 插件",
            urlLabel = "JS 链接(https)",
            bodyLabel = "或粘贴 .js 源码",
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
                            toaster.show(message = "已安装: ${definition.name}", type = ToastType.Success)
                        }
                        .onFailure { error ->
                            toaster.show(
                                message = "安装失败: ${error.message}",
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
                    val result = jsInstaller.install(definition, approved)
                    busy = false
                    result
                        .onSuccess {
                            toaster.show(
                                message = "已安装: ${definition.manifest.name}",
                                type = ToastType.Success,
                            )
                        }
                        .onFailure { error ->
                            toaster.show(
                                message = "安装失败: ${error.message}",
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
                    Text("导入")
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
                error?.let {
                    Text(text = it, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    text = "导入后需确认插件声明的能力才可启用。",
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
                            .onFailure { error = it.message ?: "解析失败" }
                    }
                },
            ) {
                if (loading) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("解析")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        title = { Text("确认插件能力") },
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
                    text = "作用范围: ${if (definition.target == RuleTarget.INPUT) "用户输入" else "模型输出"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "规则数量: ${definition.rules.size}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "申请能力: ${capabilityLabel(definition.requiredCapability)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "插件只会获得上述能力;未批准的能力不会生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("批准并安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
    // 变换类能力是插件的存在意义,取消勾选则无法安装;NETWORK 可选
    val required = declared - PluginCapability.NETWORK
    val canInstall = required.all { it in selected }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认 JS 插件能力") },
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
                    text = "作用范围: ${when (manifest.target.name) {
                        "INPUT" -> "用户输入"
                        "OUTPUT" -> "模型输出"
                        else -> "输入 + 输出"
                    }} · 钩子: ${definition.hookNames.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "源码大小: ${definition.source.toByteArray().size / 1024} KB · 超时: ${manifest.timeoutMs}ms",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (manifest.networkHosts.isNotEmpty()) {
                    Text(
                        text = "网络白名单: ${manifest.networkHosts.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "能力授权(可取消网络)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
                declared.forEach { capability ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = capability in selected,
                            // 变换能力是插件存在意义,锁定勾选;网络是可选能力,允许撤销
                            enabled = capability == PluginCapability.NETWORK,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + capability else selected - capability
                            },
                        )
                        Column {
                            Text(capabilityLabel(capability), style = MaterialTheme.typography.bodyMedium)
                            if (capability == PluginCapability.NETWORK) {
                                Text(
                                    text = "仅能访问上面声明的域名,内网地址一律拒绝",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Text(
                    text = "未勾选的能力不会生效;授权随时可在详情页查看。",
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
                Text("批准并安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
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
        text = granted.joinToString(" · ") { capabilityLabel(it) },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun capabilityLabel(capability: PluginCapability): String = when (capability) {
    PluginCapability.NETWORK -> "网络"
    PluginCapability.HISTORY_READ -> "读取历史"
    PluginCapability.CONVERSATION_MUTATE -> "修改会话"
    PluginCapability.TOOL_REGISTER -> "注册工具"
    PluginCapability.PROMPT_TRANSFORM -> "改写输入"
    PluginCapability.OUTPUT_TRANSFORM -> "改写输出"
    PluginCapability.STORAGE -> "本地存储"
}
