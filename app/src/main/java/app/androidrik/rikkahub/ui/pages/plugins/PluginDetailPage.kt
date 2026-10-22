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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginInfo
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.AppScope
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
                title = { Text(info?.plugin?.displayName ?: "插件") },
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
                text = "插件不存在或已被移除",
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
                        text = "版本 ${plugin.version} · 作者 ${plugin.author} · ${plugin.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (info.enabled) "已启用" else "已关闭",
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
                            text = "规则 (${plugin.definition.rules.size})",
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
                        Text("脚本清单", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "作用范围: ${when (manifest.target.name) {
                                "INPUT" -> "用户输入"
                                "OUTPUT" -> "模型输出"
                                "NONE" -> "仅工具"
                                else -> "输入 + 输出"
                            }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "钩子: ${plugin.definition.hookNames.joinToString()} · 超时: ${manifest.timeoutMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "源码: ${plugin.definition.source.toByteArray().size / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "网络白名单: ${
                                manifest.networkHosts.ifEmpty { listOf("无") }.joinToString()
                            }",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (manifest.storage) Text("隔离存储: 已声明（每插件 256 KiB / 128 项）", style = MaterialTheme.typography.bodySmall)
                        manifest.tools.forEach { tool ->
                            Text("工具: ${tool.name} — ${tool.description}", style = MaterialTheme.typography.bodySmall)
                            Text("工具调用需逐次审批", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            text = "源码预览",
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
    // 应用作用域:详情页会在插件注销后立刻重组为"不存在",用页面作用域会把持久化那一步取消(实测踩到过)
    val appScope: AppScope = koinInject()

    TextButton(onClick = { confirm = true }) {
        Text("删除插件", color = MaterialTheme.colorScheme.error)
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("删除插件?") },
            text = { Text("将移除「$displayName」的定义并停止其生效。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    nav.popBackStack()
                    appScope.launch {
                        onUninstall()
                            .onSuccess {
                                toaster.show(message = "已删除: $displayName", type = ToastType.Success)
                            }
                            .onFailure {
                                toaster.show(message = "删除失败: ${it.message}", type = ToastType.Error)
                            }
                    }
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            },
        )
    }
}

private fun describeAction(action: RuleAction): String = when (action.type) {
    RuleActionType.REPLACE -> "替换 /${action.pattern}/ → ${action.replacement}"
    RuleActionType.APPEND -> "追加：${action.text}"
    RuleActionType.PREPEND -> "前置：${action.text}"
}

@Composable
private fun CapabilityPermissions(info: PluginInfo, manager: PluginManager) {
    val plugin = info.plugin
    val appScope: AppScope = koinInject()
    val toaster = LocalToaster.current
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
                toaster.show(message = "能力更新失败: ${e.message}", type = ToastType.Error)
            } finally {
                saving = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("能力授权", style = MaterialTheme.typography.titleSmall)
        Text(
            "可随时撤销或重新授权，不影响插件启用状态。输入/输出和工具在下一次请求装配时生效，网络和存储在下一次调用时校验；已完成的改写和已发出的请求不会被撤回。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plugin.capabilities.isEmpty()) {
            Text("该插件未声明能力", style = MaterialTheme.typography.bodySmall)
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
                        if (granted) "已授权" else "已撤销",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = granted, onCheckedChange = null, enabled = !saving)
            }
        }
        if (plugin.capabilities.isNotEmpty() && info.grants.isEmpty()) {
            Text(
                "全部能力已撤销，插件不会获得任何已声明能力。重新启用插件不会恢复授权。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (saving) Text("正在保存授权…", style = MaterialTheme.typography.labelSmall)
    }

    pendingGrant?.let { capability ->
        AlertDialog(
            onDismissRequest = { pendingGrant = null },
            title = { Text("授权「${capabilityLabel(capability)}」？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("允许「${plugin.displayName}」${capabilityDescription(capability)}")
                    if (capability == PluginCapability.NETWORK && plugin is JsPlugin) {
                        Text("网络白名单: ${plugin.definition.manifest.networkHosts.joinToString()}")
                    }
                    if (!info.enabled) Text("插件当前已关闭，授权不会自动启用插件。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingGrant = null
                    update(capability, true)
                }) { Text("授权") }
            },
            dismissButton = {
                TextButton(onClick = { pendingGrant = null }) { Text("取消") }
            },
        )
    }
}

private fun capabilityDescription(capability: PluginCapability): String = when (capability) {
    PluginCapability.NETWORK -> "通过宿主网关访问声明的网络域名"
    PluginCapability.HISTORY_READ -> "读取会话历史"
    PluginCapability.CONVERSATION_MUTATE -> "修改会话内容"
    PluginCapability.TOOL_REGISTER -> "向模型提供插件工具"
    PluginCapability.PROMPT_TRANSFORM -> "改写发送给模型的输入文本"
    PluginCapability.OUTPUT_TRANSFORM -> "改写模型生成结束后的输出文本"
    PluginCapability.STORAGE -> "使用插件本地存储"
}
