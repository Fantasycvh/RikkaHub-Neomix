package app.androidrik.rikkahub.ui.pages.plugins

import androidx.compose.foundation.layout.Arrangement
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = "能力声明", style = MaterialTheme.typography.titleSmall)
                    if (plugin.capabilities.isEmpty()) {
                        Text(
                            text = "该插件未声明能力",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        plugin.capabilities.forEach { capability ->
                            CapabilityRow(
                                capability = capability,
                                granted = info.hasCapability(capability),
                            )
                        }
                    }
                }
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
private fun CapabilityRow(capability: PluginCapability, granted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = capabilityLabel(capability),
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = if (granted) "已授权" else "未授权(插件不会获得该能力)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
