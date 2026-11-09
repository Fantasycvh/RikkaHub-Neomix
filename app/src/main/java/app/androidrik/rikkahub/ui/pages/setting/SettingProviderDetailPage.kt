package app.androidrik.rikkahub.ui.pages.setting

// 批次 5 拆出，来源 SettingProviderDetailPage

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import app.androidrik.rikkahub.ui.components.ui.AppButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.account.preserveCredential
import app.androidrik.rikkahub.ui.components.ai.ProviderQuotaText
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.components.ui.AutoAIIcon
import app.androidrik.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.assistant.detail.CustomHeaders
import app.androidrik.rikkahub.ui.pages.setting.components.ProviderConfigure
import app.androidrik.rikkahub.ui.pages.setting.components.ProviderConnectionTester
import app.androidrik.rikkahub.ui.pages.setting.components.ProviderRetryOption
import app.androidrik.rikkahub.ui.pages.setting.components.SettingProviderQuotaQuery
import app.androidrik.rikkahub.ui.pages.setting.components.isUsingDefaultBaseUrl
import app.androidrik.rikkahub.ui.pages.setting.components.resetBaseUrlToDefault
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun SettingProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val resources = LocalResources.current

    val onEdit = { newProvider: ProviderSetting ->
        val newSettings = settings.copy(
            providers = settings.providers.map {
                if (newProvider.id == it.id) {
                    // 凭据只由 ProviderAccountManager 写：编辑期间后台可能刷新过 token，
                    // 整份写回会把轮换后的 refresh token 覆盖掉（见 preserveCredential）
                    newProvider.preserveCredential(it)
                } else {
                    it
                }
            }
        )
        vm.updateSettings(newSettings)
    }
    // 使用该提供商模型作为主/备用模型的助手（删除后会悬空，删除前必须提示）
    val affectedAssistants = settings.assistants.filter { assistant ->
        provider.models.any { model ->
            assistant.chatModelId == model.id || assistant.fallbackChatModelId == model.id
        }
    }
    val onDelete = {
        val newSettings = settings.copy(
            providers = settings.providers - provider,
            // 内置提供商删除 = 墓碑（默认回填逻辑跳过）；非内置直接移除
            removedBuiltInProviderIds = if (provider.builtIn) {
                settings.removedBuiltInProviderIds + provider.id
            } else {
                settings.removedBuiltInProviderIds
            }
        )
        vm.updateSettings(newSettings)
        navController.popBackStack()
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AutoAIIcon(provider.name, modifier = Modifier.size(22.dp))
                        Text(text = provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CustomColors.cardColorsOnSurfaceContainer.containerColor
            ) {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    label = { Text(stringResource(id = R.string.setting_provider_page_configuration)) },
                    icon = { Icon(HugeIcons.Tools, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(0)
                        }
                    }
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1,
                    label = { Text(stringResource(id = R.string.setting_provider_page_models)) },
                    icon = { Icon(HugeIcons.Package01, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(1)
                        }
                    }
                )
                NavigationBarItem(
                    selected = pager.currentPage == 2,
                    label = { Text(stringResource(id = R.string.setting_provider_page_network_proxy)) },
                    icon = { Icon(HugeIcons.Earth, null) },
                    onClick = {
                        scope.launch {
                            pager.animateScrollToPage(2)
                        }
                    }
                )
            }
        }
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .padding(it)
                .consumeWindowInsets(it)
        ) { page ->
            when (page) {
                0 -> {
                    SettingProviderConfigPage(
                        provider = provider,
                        onEdit = {
                            onEdit(it)
                            toaster.show(
                                resources.getString(R.string.setting_provider_page_save_success),
                                type = ToastType.Success
                            )
                        },
                        onDelete = {
                            onDelete()
                        },
                        affectedAssistantCount = affectedAssistants.size
                    )
                }

                1 -> {
                    SettingProviderModelPage(
                        provider = provider,
                        onEdit = onEdit
                    )
                }

                2 -> {
                    SettingProviderProxyPage(
                        provider = provider,
                        onEdit = onEdit
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingProviderConfigPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onDelete: () -> Unit,
    affectedAssistantCount: Int = 0
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProviderConfigure(
            provider = internalProvider,
            onEdit = {
                internalProvider = it
            }
        )

        CustomHeaders(
            headers = internalProvider.customHeaders,
            onUpdate = { headers ->
                internalProvider = internalProvider.copyProvider(customHeaders = headers)
            }
        )

        // per-provider 自动重试覆盖（null = 跟随全局默认）
        ProviderRetryOption(
            provider = internalProvider,
            onEdit = { internalProvider = it },
        )

        // 委托属性上的智能转换不成立，先取局部不可变引用
        val apiKeyProvider = internalProvider as? ProviderSetting.ApiKey
        if (apiKeyProvider != null) {
            SettingProviderQuotaQuery(
                provider = apiKeyProvider,
                quotaQuery = apiKeyProvider.quotaQuery,
                onEdit = { internalProvider = apiKeyProvider.copyQuotaQuery(it) }
            )
            ProviderQuotaText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProviderConnectionTester(
                internalProvider = internalProvider,
            )

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = {
                    showDeleteDialog = true
                },
            ) {
                Icon(HugeIcons.Delete01, null)
            }

            IconButton(
                onClick = {
                    internalProvider = internalProvider.resetBaseUrlToDefault()
                },
                enabled = !internalProvider.isUsingDefaultBaseUrl(),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh03,
                    contentDescription = stringResource(R.string.setting_model_page_reset_to_default)
                )
            }

            Button(
                onClick = {
                    onEdit(internalProvider.copyProvider(name = internalProvider.name.trim()))
                }
            ) {
                Text(stringResource(R.string.setting_provider_page_save))
            }
        }

        // 硅基流动图标
        if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
            SiliconFlowPowerByIcon(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp)
            )
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(stringResource(R.string.confirm_delete))
            },
            text = {
                // 内置提供商被助手引用时给更强警告（三件套第二件：悬空引用保护）
                Text(
                    if (affectedAssistantCount > 0) {
                        stringResource(
                            R.string.setting_provider_page_delete_dialog_affected,
                            affectedAssistantCount,
                        )
                    } else {
                        stringResource(R.string.setting_provider_page_delete_dialog_text)
                    }
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        )
    }
}

@Composable
private fun SettingProviderProxyPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var editingProxy by remember(provider.proxy) {
        mutableStateOf(provider.proxy)
    }

    // 四档（两层代理设计 §五）：跟随全局（默认）/ 无（显式直连）/ HTTP / SOCKS5
    val options = listOf(
        ProxyOption.Inherit,
        ProxyOption.None,
        ProxyOption.Http,
        ProxyOption.Socks5,
    )
    val selected = when (editingProxy) {
        is ProviderProxy.Inherit -> ProxyOption.Inherit
        is ProviderProxy.None -> ProxyOption.None
        is ProviderProxy.Http -> ProxyOption.Http
        is ProviderProxy.Socks5 -> ProxyOption.Socks5
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(stringResource(option.labelRes)) },
                    selected = selected == option,
                    onClick = { editingProxy = option.toDefaultProxy() }
                )
            }
        }

        when (val current = editingProxy) {
            is ProviderProxy.Inherit -> {
                Text(
                    text = stringResource(R.string.setting_provider_page_proxy_inherit_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is ProviderProxy.None -> {}

            is ProviderProxy.Http -> ProxyFields(
                address = current.address,
                port = current.port,
                username = current.username.orEmpty(),
                password = current.password.orEmpty(),
                isSocks = false,
                onChange = { editingProxy = it },
            )

            is ProviderProxy.Socks5 -> ProxyFields(
                address = current.address,
                port = current.port,
                username = current.username.orEmpty(),
                password = current.password.orEmpty(),
                isSocks = true,
                onChange = { editingProxy = it },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    onEdit(provider.copyProvider(proxy = editingProxy))
                    toaster.show(
                        resources.getString(R.string.setting_provider_page_save_success),
                        type = ToastType.Success
                    )
                }
            ) {
                Text(stringResource(id = R.string.setting_provider_page_save))
            }
        }
    }
}

private enum class ProxyOption(val labelRes: Int) {
    Inherit(R.string.setting_provider_page_proxy_inherit),
    None(R.string.setting_provider_page_proxy_none),
    Http(R.string.setting_provider_page_proxy_http),
    Socks5(R.string.setting_provider_page_proxy_socks5);

    fun toDefaultProxy(): ProviderProxy = when (this) {
        Inherit -> ProviderProxy.Inherit
        None -> ProviderProxy.None
        Http -> ProviderProxy.Http(address = "", port = 8080)
        Socks5 -> ProviderProxy.Socks5(address = "", port = 1080)
    }
}

/** HTTP / SOCKS5 共用的地址·端口·认证输入（[onChange] 回写同类型的新对象）。 */
@Composable
private fun ProxyFields(
    address: String,
    port: Int,
    username: String,
    password: String,
    isSocks: Boolean,
    onChange: (ProviderProxy) -> Unit,
) {
    fun build(addr: String = address, p: Int = port, user: String = username, pass: String = password) =
        if (isSocks) ProviderProxy.Socks5(addr, p, user.ifBlank { null }, pass.ifBlank { null })
        else ProviderProxy.Http(addr, p, user.ifBlank { null }, pass.ifBlank { null })

    OutlinedTextField(
        value = address,
        onValueChange = { onChange(build(addr = it)) },
        label = { Text(stringResource(id = R.string.setting_provider_page_proxy_host)) },
        modifier = Modifier.fillMaxWidth()
    )
    var portStr by remember { mutableStateOf(port.toString()) }
    OutlinedTextField(
        value = portStr,
        onValueChange = {
            portStr = it
            it.toIntOrNull()?.let { p -> onChange(build(p = p)) }
        },
        label = { Text(stringResource(id = R.string.setting_provider_page_proxy_port)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
    OutlinedTextField(
        value = username,
        onValueChange = { onChange(build(user = it)) },
        label = { Text(stringResource(id = R.string.setting_provider_page_proxy_username)) },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = password,
        onValueChange = { onChange(build(pass = it)) },
        label = { Text(stringResource(id = R.string.setting_provider_page_proxy_password)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
