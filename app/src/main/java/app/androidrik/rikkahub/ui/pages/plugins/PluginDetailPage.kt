package app.androidrik.rikkahub.ui.pages.plugins

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.plugin.js.JsPlugin
import app.androidrik.rikkahub.data.plugin.js.JsPluginInstaller
import app.androidrik.rikkahub.data.plugin.js.hookNames
import app.androidrik.rikkahub.data.plugin.rules.JsonRulePlugin
import app.androidrik.rikkahub.data.plugin.rules.RuleAction
import app.androidrik.rikkahub.data.plugin.rules.RuleActionType
import app.androidrik.rikkahub.data.plugin.rules.RulePluginInstaller
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 插件详情:元信息 + 能力声明 + 插件自带的配置界面。
 *
 * 插件配置界面直接内嵌 [app.androidrik.plugin.api.RikkaPlugin.SettingsContent],
 * 宿主不新增设置页路由——这是 BiliPai `SettingsContent()` 模式在本 fork 的落地。
 */
@Composable
fun PluginDetailPage(pluginId: String) {
    val manager: PluginManager = koinInject()
    val plugins by manager.plugins.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val info = plugins.firstOrNull { it.plugin.id == pluginId }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(info?.plugin?.displayName ?: stringResource(R.string.plugin_detail_title_fallback)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (info == null) {
            Text(
                text = stringResource(R.string.plugin_detail_not_found),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(innerPadding).padding(16.dp),
            )
            return@Scaffold
        }

        val plugin = info.plugin
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("meta") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = plugin.description, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(
                            R.string.plugin_detail_meta_line,
                            plugin.version,
                            plugin.author,
                            plugin.id,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (info.enabled) {
                            stringResource(R.string.plugin_detail_enabled)
                        } else {
                            stringResource(R.string.plugin_detail_disabled)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (info.enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            item("capabilities") {
                CapabilityPermissions(info = info, manager = manager)
            }

            item("settings") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    plugin.SettingsContent()
                }
            }

            // JSON 规则插件:展示规则内容与卸载入口(内置插件没有这些)
            if (plugin is JsonRulePlugin) {
                item("rules") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.plugin_detail_rules_header, plugin.definition.rules.size),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        plugin.definition.rules.forEachIndexed { index, rule ->
                            Text(
                                text = "${index + 1}. ${describeAction(rule.action)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                item("uninstall") {
                    val installer: RulePluginInstaller = koinInject()
                    UninstallButton(
                        displayName = plugin.displayName,
                        onUninstall = { installer.uninstall(plugin.id) },
                    )
                }
            }

            // JS 沙箱插件:清单信息 + 源码预览 + 卸载
            if (plugin is JsPlugin) {
                item("js_manifest") {
                    val manifest = plugin.definition.manifest
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.plugin_detail_manifest_header), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(
                                R.string.plugin_target_scope,
                                stringResource(
                                    when (manifest.target.name) {
                                        "INPUT" -> R.string.plugin_target_input
                                        "OUTPUT" -> R.string.plugin_target_output
                                        "NONE" -> R.string.plugin_target_tool_only
                                        else -> R.string.plugin_target_input_output
                                    }
                                ),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.plugin_detail_hooks_timeout,
                                plugin.definition.hookNames.joinToString(),
                                manifest.timeoutMs,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.plugin_detail_source_size,
                                plugin.definition.source.toByteArray().size / 1024,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(
                                R.string.plugin_network_whitelist,
                                manifest.networkHosts
                                    .ifEmpty { listOf(stringResource(R.string.plugin_detail_network_none)) }
                                    .joinToString(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (manifest.storage) {
                            Text(
                                stringResource(R.string.plugin_detail_storage_declared),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        manifest.tools.forEach { tool ->
                            Text(
                                stringResource(R.string.plugin_tool_line, tool.name, tool.description),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.plugin_detail_tool_approval_hint),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = stringResource(R.string.plugin_detail_source_preview),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            text = plugin.definition.source.lineSequence().take(12).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                item("uninstall") {
                    val installer: JsPluginInstaller = koinInject()
                    UninstallButton(
                        displayName = plugin.displayName,
                        onUninstall = { installer.uninstall(plugin.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UninstallButton(
    displayName: String,
    onUninstall: suspend () -> Result<Unit>,
) {
    var confirm by remember { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val nav = LocalNavController.current
    val context = LocalContext.current
    // 应用作用域:详情页会在插件注销后立刻重组为"不存在",用页面作用域会把持久化那一步取消(实测踩到过)
    val appScope: AppScope = koinInject()

    TextButton(onClick = { confirm = true }) {
        Text(stringResource(R.string.plugin_detail_delete_plugin), color = MaterialTheme.colorScheme.error)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.plugin_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.plugin_detail_delete_confirm_text, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    nav.popBackStack()
                    appScope.launch {
                        onUninstall()
                            .onSuccess {
                                toaster.show(
                                    message = context.getString(R.string.plugin_detail_deleted_toast, displayName),
                                    type = ToastType.Success,
                                )
                            }
                            .onFailure {
                                toaster.show(
                                    message = context.getString(R.string.plugin_detail_delete_failed_toast, it.message),
                                    type = ToastType.Error,
                                )
                            }
                    }
                }) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun describeAction(action: RuleAction): String = when (action.type) {
    RuleActionType.REPLACE -> stringResource(R.string.plugin_detail_action_replace, action.pattern, action.replacement)
    RuleActionType.APPEND -> stringResource(R.string.plugin_detail_action_append, action.text)
    RuleActionType.PREPEND -> stringResource(R.string.plugin_detail_action_prepend, action.text)
}

@Composable
private fun CapabilityPermissions(info: PluginInfo, manager: PluginManager) {
    val plugin = info.plugin
    val appScope: AppScope = koinInject()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var saving by remember(plugin.id) { mutableStateOf(false) }
    var pendingGrant by remember(plugin.id) { mutableStateOf<PluginCapability?>(null) }

    fun update(capability: PluginCapability, approved: Boolean) {
        if (saving) return
        saving = true
        // 应用作用域保证离开详情页也不会取消写盘;成功发布前保留旧开关状态。
        appScope.launch {
            try {
                manager.setCapabilityApproved(plugin.id, capability, approved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toaster.show(
                    message = context.getString(R.string.plugin_detail_capability_update_failed, e.message),
                    type = ToastType.Error,
                )
            } finally {
                saving = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.plugin_detail_capability_header), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.plugin_detail_capability_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plugin.capabilities.isEmpty()) {
            Text(stringResource(R.string.plugin_detail_no_capabilities), style = MaterialTheme.typography.bodySmall)
        }
        plugin.capabilities.forEach { capability ->
            val granted = info.hasCapability(capability)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .toggleable(
                        value = granted,
                        enabled = !saving,
                        role = Role.Switch,
                        onValueChange = { approved ->
                            if (approved) pendingGrant = capability else update(capability, false)
                        },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(capabilityLabel(capability), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        capabilityDescription(capability),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (granted) {
                            stringResource(R.string.plugin_detail_granted)
                        } else {
                            stringResource(R.string.plugin_detail_revoked)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = granted, onCheckedChange = null, enabled = !saving)
            }
        }
        if (plugin.capabilities.isNotEmpty() && info.grants.isEmpty()) {
            Text(
                stringResource(R.string.plugin_detail_all_revoked_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (saving) Text(stringResource(R.string.plugin_detail_saving), style = MaterialTheme.typography.labelSmall)
    }

    pendingGrant?.let { capability ->
        AlertDialog(
            onDismissRequest = { pendingGrant = null },
            title = { Text(stringResource(R.string.plugin_detail_grant_title, capabilityLabel(capability))) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.plugin_detail_grant_text,
                            plugin.displayName,
                            capabilityDescription(capability),
                        ),
                    )
                    if (capability == PluginCapability.NETWORK && plugin is JsPlugin) {
                        Text(
                            stringResource(
                                R.string.plugin_network_whitelist,
                                plugin.definition.manifest.networkHosts.joinToString(),
                            ),
                        )
                    }
                    if (!info.enabled) {
                        Text(stringResource(R.string.plugin_detail_grant_disabled_hint))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingGrant = null
                    update(capability, true)
                }) { Text(stringResource(R.string.plugin_detail_grant_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingGrant = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun capabilityDescription(capability: PluginCapability): String = when (capability) {
    PluginCapability.NETWORK -> stringResource(R.string.plugin_detail_cap_desc_network)
    PluginCapability.HISTORY_READ -> stringResource(R.string.plugin_detail_cap_desc_history_read)
    PluginCapability.CONVERSATION_MUTATE -> stringResource(R.string.plugin_detail_cap_desc_conversation_mutate)
    PluginCapability.TOOL_REGISTER -> stringResource(R.string.plugin_detail_cap_desc_tool_register)
    PluginCapability.PROMPT_TRANSFORM -> stringResource(R.string.plugin_detail_cap_desc_prompt_transform)
    PluginCapability.OUTPUT_TRANSFORM -> stringResource(R.string.plugin_detail_cap_desc_output_transform)
    PluginCapability.STORAGE -> stringResource(R.string.plugin_detail_cap_desc_storage)
}
