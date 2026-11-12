package app.androidrik.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import app.androidrik.rikkahub.data.datastore.ProxyType
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.hooks.HiddenTextTransformation
import app.androidrik.rikkahub.ui.hooks.rememberPersistedTextFieldState
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 设置 → 偏好 → 网络：全局代理（两层代理设计的「全局兜底层」，见 `docs/design/design-proxy-two-layer.md` §五）。
 *
 * 说明：provider 未自配代理时（默认「跟随全局」）走这里；**只有共享 client 的链路受益**——
 * 搜索与 ASR 会跟随，TTS/MCP/S3/WebDAV 不受影响（各用自建 client，见设计 §四）。
 */
@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var proxy by remember(settings) { mutableStateOf(settings.globalProxy) }

    // 非文本控件：本地镜像先反馈，落盘走字段级 transform（不读 settings 快照）。
    fun update(transform: (GlobalProxySetting) -> GlobalProxySetting) {
        proxy = transform(proxy)
        vm.updateSettings { it.copy(globalProxy = transform(it.globalProxy)) }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_preferences_network)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.setting_network_proxy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.setting_network_proxy_title),
                            style = MaterialTheme.typography.titleSmall,
                        )

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val types = ProxyType.entries
                            types.forEachIndexed { index, type ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index, types.size),
                                    label = { Text(proxyTypeLabel(type)) },
                                    selected = proxy.type == type,
                                    onClick = { update { it.copy(type = type) } },
                                )
                            }
                        }

                        if (proxy.type != ProxyType.NONE) {
                            // 本地权威编辑态 + 去抖单向写出（AGENTS「TextField Host Rules」第 4 条；
                            // 范本 1808d418f）。旧写法 `value = proxy.host` 绑 settingsFlow 的本地
                            // 镜像，每次落盘回声都可能把旧值回灌活跃编辑框 ⇒ 逐字符抖动/丢字。
                            // resetKey = settings.init：首帧占位 Settings（init = true）加载完成后
                            // 只重建一次本地态，不把占位值钉死、也不在加载完成时把占位值写回去。
                            val hostState = rememberPersistedTextFieldState(
                                externalValue = proxy.host,
                                resetKey = settings.init,
                                onCommit = { text ->
                                    proxy = proxy.copy(host = text)
                                    vm.updateSettings { it.copy(globalProxy = it.globalProxy.copy(host = text)) }
                                },
                            )
                            OutlinedTextField(
                                state = hostState,
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_host)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // 端口：本地权威 + 250ms 去抖写 + 离页补写（同 host/username/password）。
                            // 解析只发生在 onCommit，不把 Int 投影回填活跃编辑框；落盘走字段级
                            // transform（旧写法逐键 `settings.copy(globalProxy = ...)` 整份快照写）。
                            val portState = rememberPersistedTextFieldState(
                                externalValue = proxy.port.takeIf { it > 0 }?.toString() ?: "",
                                resetKey = settings.init,
                                onCommit = { text ->
                                    val port = text.trim().toIntOrNull() ?: 0
                                    proxy = proxy.copy(port = port)
                                    vm.updateSettings { it.copy(globalProxy = it.globalProxy.copy(port = port)) }
                                },
                            )
                            OutlinedTextField(
                                state = portState,
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_port)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            val usernameState = rememberPersistedTextFieldState(
                                externalValue = proxy.username,
                                resetKey = settings.init,
                                onCommit = { text ->
                                    proxy = proxy.copy(username = text)
                                    vm.updateSettings { it.copy(globalProxy = it.globalProxy.copy(username = text)) }
                                },
                            )
                            OutlinedTextField(
                                state = usernameState,
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_username)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            val passwordState = rememberPersistedTextFieldState(
                                externalValue = proxy.password,
                                resetKey = settings.init,
                                onCommit = { text ->
                                    proxy = proxy.copy(password = text)
                                    vm.updateSettings { it.copy(globalProxy = it.globalProxy.copy(password = text)) }
                                },
                            )
                            OutlinedTextField(
                                state = passwordState,
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_password)) },
                                modifier = Modifier.fillMaxWidth(),
                                // 状态版 OutlinedTextField 没有 visualTransformation，用等长
                                // OutputTransformation 掩码（长度不变，光标/选区映射保持原样）。
                                outputTransformation = HiddenTextTransformation,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun proxyTypeLabel(type: ProxyType): String = when (type) {
    ProxyType.NONE -> stringResource(R.string.setting_provider_page_proxy_none)
    ProxyType.HTTP -> stringResource(R.string.setting_provider_page_proxy_http)
    ProxyType.SOCKS5 -> stringResource(R.string.setting_provider_page_proxy_socks5)
}
