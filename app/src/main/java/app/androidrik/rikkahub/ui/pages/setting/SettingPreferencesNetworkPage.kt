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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import app.androidrik.rikkahub.data.datastore.ProxyType
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * 设置 → 偏好 → 网络：全局代理（两层代理设计的「全局兜底层」，见 `docs/design-proxy-two-layer.md` §五）。
 *
 * 说明：provider 未自配代理时（默认「跟随全局」）走这里；**只有共享 client 的链路受益**——
 * 搜索与 ASR 会跟随，TTS/MCP/S3/WebDAV 不受影响（各用自建 client，见设计 §四）。
 */
@Composable
fun SettingPreferencesNetworkPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var proxy by remember(settings) { mutableStateOf(settings.globalProxy) }

    fun update(updated: GlobalProxySetting) {
        proxy = updated
        vm.updateSettings(settings.copy(globalProxy = updated))
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
                                    onClick = { update(proxy.copy(type = type)) },
                                )
                            }
                        }

                        if (proxy.type != ProxyType.NONE) {
                            OutlinedTextField(
                                value = proxy.host,
                                onValueChange = { update(proxy.copy(host = it)) },
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_host)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            var portText by remember { mutableStateOf(proxy.port.takeIf { it > 0 }?.toString() ?: "") }
                            OutlinedTextField(
                                value = portText,
                                onValueChange = {
                                    portText = it
                                    update(proxy.copy(port = it.toIntOrNull() ?: 0))
                                },
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_port)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedTextField(
                                value = proxy.username,
                                onValueChange = { update(proxy.copy(username = it)) },
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_username)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = proxy.password,
                                onValueChange = { update(proxy.copy(password = it)) },
                                label = { Text(stringResource(R.string.setting_provider_page_proxy_password)) },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
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
