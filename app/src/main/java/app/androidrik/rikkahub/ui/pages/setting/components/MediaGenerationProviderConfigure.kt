package app.androidrik.rikkahub.ui.pages.setting.components

import app.androidrik.rikkahub.ui.components.ui.appSkinRoundedShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import app.androidrik.common.http.ProviderProxy
import app.androidrik.rikkahub.ui.pages.assistant.detail.CustomBodies
import app.androidrik.rikkahub.ui.pages.assistant.detail.CustomHeaders
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.dokar.sonner.ToastType
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaKind
import app.androidrik.mediagen.provider.MediaGenerationManager
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.FormItem
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

val MediaGenerationProviderSetting.typeName: String
    @Composable get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> stringResource(R.string.media_provider_type_aliyun)
        is MediaGenerationProviderSetting.Volcengine -> stringResource(R.string.media_provider_type_volcengine)
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
        is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
        is MediaGenerationProviderSetting.SiliconFlow -> "SiliconFlow"
    }

val MediaKind.label: String
    @Composable get() = when (this) {
        MediaKind.IMAGE -> stringResource(R.string.media_kind_image)
        MediaKind.VIDEO -> stringResource(R.string.video)
    }

/**
 * 媒体生成服务商的配置表单。
 *
 * 与上游的差异：`CardGroupIcon` / `CardGroupValue` / `ConnectedChoiceButtons` 在本仓库不存在，
 * 这里用普通 `Text` / `FilterChip` 和 `FormItem` 做等价替代；本仓库的 `CardGroup.item` 没有内容槽位，
 * 表单字段因此放在一个普通 `Card` 里，而不是塞进 `ListItem`。
 */
@Composable
fun MediaGenerationProviderConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit,
) {
    val models = setting.models
    // 按枚举顺序排列，保证按钮组的顺序稳定
    val kinds = MediaKind.entries.filter { it in setting.supportedKinds }

    fun updateModels(models: List<MediaGenerationModel>) {
        onValueChange(setting.copyProvider(models = models))
    }

    val manager: MediaGenerationManager = koinInject()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    // 只有已接入、且接口能列出模型的供应商才显示「获取模型列表」
    val canFetchModels = manager.supportsModelList(setting)
    var fetching by remember { mutableStateOf(false) }
    // 非 null 时显示勾选对话框
    var candidates by remember { mutableStateOf<List<MediaGenerationModel>?>(null) }
    var selectedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 非 null 时显示该模型的高级参数 sheet（自定义头/body）
    var advancedModelId by remember { mutableStateOf<Uuid?>(null) }
    val advancedModel = models.firstOrNull { it.id == advancedModelId }

    fun fetchModels() {
        if (fetching) return
        fetching = true
        scope.launch {
            manager.listModels(setting)
                .onSuccess { fetched ->
                    val existingModelIds = models.map { it.modelId }.toSet()
                    val fresh = fetched
                        .filter { it.modelId.isNotBlank() && it.modelId !in existingModelIds }
                        .distinctBy { it.modelId }
                    if (fresh.isEmpty()) {
                        toaster.show(context.getString(R.string.setting_media_page_fetch_models_empty))
                    } else {
                        selectedModelIds = fresh.map { it.modelId }.toSet()
                        candidates = fresh
                    }
                }
                .onFailure { error ->
                    toaster.show(
                        context.getString(
                            R.string.setting_provider_page_fetch_models_error,
                            error.message ?: "Unknown",
                        ),
                        type = ToastType.Error,
                    )
                }
            fetching = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Card(
            shape = appSkinRoundedShape(20f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 类型在新建时选定，之后不能改
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_media_page_provider_type),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = setting.typeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.setting_media_page_provider_name)) },
                ) {
                    OutlinedTextField(
                        value = setting.name,
                        onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                if (setting is MediaGenerationProviderSetting.OpenAI) {
                    CredentialReferenceField(setting = setting, onValueChange = onValueChange)
                }

                // 引用旧 AI 供应商凭据时 Key/URL/代理/多 Key 都跟着对方走，本地显式配置不再展示
                if (!(setting is MediaGenerationProviderSetting.OpenAI && setting.credentialRef != null)) {
                    FormItem(label = { Text("API Key") }) {
                        OutlinedTextField(
                            value = setting.apiKey,
                            onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    // 多 key（M3a-1 parity）：非空时优先于上方单 Key，列表顺序即优先级
                    MediaApiKeysEditor(
                        keys = setting.apiKeys,
                        onChange = { onValueChange(setting.copyProvider(apiKeys = it)) },
                    )

                    FormItem(label = { Text("Base URL") }) {
                        OutlinedTextField(
                            value = setting.baseUrl,
                            onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    // 代理（M3a-1 parity）：四档与旧供应商编辑器同一语义
                    MediaProxyEditor(
                        proxy = setting.proxy,
                        onChange = { onValueChange(setting.copyProvider(proxy = it)) },
                    )
                }

                if (setting is MediaGenerationProviderSetting.Aliyun) {
                    FormItem(
                        label = { Text(stringResource(R.string.setting_media_page_workspace_id)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.setting_media_page_workspace_id_desc,
                                    MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER,
                                )
                            )
                        },
                    ) {
                        OutlinedTextField(
                            value = setting.workspaceId,
                            onValueChange = { onValueChange(setting.copy(workspaceId = it.trim())) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }
        }

        CardGroup(
            title = { Text(stringResource(R.string.setting_media_page_models)) },
        ) {
            models.forEach { model ->
                item(
                    headlineContent = {
                        ModelRow(
                            model = model,
                            kinds = kinds,
                            onModelIdChange = { value ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(modelId = value.trim()) else it
                                    }
                                )
                            },
                            onKindChange = { kind ->
                                updateModels(
                                    models.map {
                                        if (it.id == model.id) model.copy(kind = kind) else it
                                    }
                                )
                            },
                            onAdvanced = { advancedModelId = model.id },
                            onDelete = { updateModels(models.filter { it.id != model.id }) },
                        )
                    },
                )
            }

            if (canFetchModels) {
                item(
                    onClick = if (fetching) null else { { fetchModels() } },
                    headlineContent = {
                        Text(stringResource(R.string.setting_provider_page_fetch_models))
                    },
                    supportingContent = {
                        Text(
                            if (fetching) stringResource(R.string.setting_provider_page_fetch_models_loading)
                            else stringResource(R.string.setting_media_page_fetch_models_desc)
                        )
                    },
                    leadingContent = {
                        if (fetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(HugeIcons.Refresh03, contentDescription = null)
                        }
                    },
                )
            }

            item(
                onClick = if (kinds.isNotEmpty()) {
                    { updateModels(models + MediaGenerationModel(modelId = "", kind = kinds.first())) }
                } else null,
                headlineContent = { Text(stringResource(R.string.setting_media_page_add_model)) },
                supportingContent = { Text(stringResource(R.string.setting_media_page_models_desc)) },
                leadingContent = { Icon(HugeIcons.Add01, contentDescription = null) },
            )
        }
    }

    // 模型级高级参数 sheet（自定义头/body）：编辑器进 sheet 而非内联行（AGENTS.md TextField 宿主规则），
    // Column+verticalScroll 子项常驻；写回走 updateModels 与其它字段同一通路
    advancedModel?.let { model ->
        ModalBottomSheet(
            onDismissRequest = { advancedModelId = null },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "${model.modelId} · 高级参数",
                    style = MaterialTheme.typography.titleMedium,
                )
                CustomHeaders(
                    headers = model.customHeaders,
                    onUpdate = { headers ->
                        updateModels(models.map { if (it.id == model.id) model.copy(customHeaders = headers) else it })
                    },
                )
                CustomBodies(
                    customBodies = model.customBodies,
                    onUpdate = { bodies ->
                        updateModels(models.map { if (it.id == model.id) model.copy(customBodies = bodies) else it })
                    },
                )
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // 勾选对话框：候选来自接口，选中后才写进配置
    candidates?.let { fetchedModels ->
        AlertDialog(
            onDismissRequest = { candidates = null },
            title = { Text(stringResource(R.string.setting_provider_page_fetch_models)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(fetchedModels, key = { it.modelId }) { candidate ->
                        val checked = candidate.modelId in selectedModelIds
                        val toggle = {
                            if (checked) {
                                selectedModelIds = selectedModelIds - candidate.modelId
                            } else {
                                selectedModelIds = selectedModelIds + candidate.modelId
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Column(modifier = Modifier.weight(1f)) {
                                Text(candidate.modelId, style = MaterialTheme.typography.bodyMedium)
                                if (kinds.size > 1) {
                                    Text(
                                        text = candidate.kind.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedModelIds.isNotEmpty(),
                    onClick = {
                        val picked = fetchedModels.filter { it.modelId in selectedModelIds }
                        updateModels(models + picked)
                        candidates = null
                    },
                ) {
                    Text(
                        stringResource(
                            R.string.setting_media_page_fetch_models_add,
                            selectedModelIds.size,
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { candidates = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ModelRow(
    model: MediaGenerationModel,
    kinds: List<MediaKind>,
    onModelIdChange: (String) -> Unit,
    onKindChange: (MediaKind) -> Unit,
    onAdvanced: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = model.modelId,
                onValueChange = onModelIdChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.setting_media_page_model_id)) },
                singleLine = true,
            )
            IconButton(onClick = onAdvanced) {
                Icon(HugeIcons.Settings02, contentDescription = "高级参数")
            }
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, stringResource(R.string.delete))
            }
        }
        // 只支持一种类型的厂商不需要选择
        if (kinds.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { kind ->
                    FilterChip(
                        selected = model.kind == kind,
                        onClick = { onKindChange(kind) },
                        label = { Text(kind.label) },
                    )
                }
            }
        }
    }
}

/**
 * OpenAI 兼容媒体供应商的凭据来源：本地显式配置，或引用一个已有 AI 供应商（活引用，
 * 改 key / 端点自动跟随）。引用的供应商被删时模型失效并在此提示。
 */
@Composable
private fun CredentialReferenceField(
    setting: MediaGenerationProviderSetting.OpenAI,
    onValueChange: (MediaGenerationProviderSetting) -> Unit,
) {
    val candidates = LocalSettings.current.providers.filterIsInstance<ProviderSetting.OpenAI>()
    val referenced = setting.credentialRef?.let { ref -> candidates.firstOrNull { it.id == ref } }
    var picking by remember { mutableStateOf(false) }

    FormItem(
        label = { Text(stringResource(R.string.setting_media_page_credentials)) },
        description = {
            Text(
                when {
                    referenced != null -> stringResource(R.string.setting_media_page_credentials_referenced, referenced.name)
                    setting.credentialRef != null -> stringResource(R.string.setting_media_page_credentials_missing)
                    else -> stringResource(R.string.setting_media_page_credentials_manual)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        },
    ) {
        TextButton(
            onClick = { picking = true },
            enabled = setting.credentialRef != null || candidates.isNotEmpty(),
        ) {
            Text(
                when {
                    setting.credentialRef != null -> stringResource(R.string.setting_media_page_credentials_change)
                    else -> stringResource(R.string.setting_media_page_credentials_pick)
                }
            )
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.setting_media_page_credentials_pick)) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        TextButton(
                            onClick = {
                                onValueChange(setting.copy(credentialRef = null))
                                picking = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.setting_media_page_credentials_none))
                        }
                    }
                    items(candidates, key = { it.id }) { source ->
                        TextButton(
                            onClick = {
                                // 快照留在本地兜底（解除引用时回退为显式配置），生效值以引用为准
                                onValueChange(
                                    setting.copy(
                                        credentialRef = source.id,
                                        baseUrl = source.baseUrl,
                                        apiKey = source.effectiveApiKeys
                                            .split(Regex("[\\s,]+")).firstOrNull { it.isNotBlank() }.orEmpty(),
                                    )
                                )
                                picking = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${source.name} · ${source.baseUrl}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
/**
 * 多 Key 编辑器（M3a-1 parity）：列表非空时优先于单 Key 字段，顺序即优先级。
 * 用 Column+forEach 而非 LazyColumn——行数少，常驻节点无 item 销毁/焦点丢失风险
 * （AGENTS.md TextField 宿主规则；旧供应商编辑器的 Lazy 版是历史豁免形态，不新增）。
 */
@Composable
private fun MediaApiKeysEditor(
    keys: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var keyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "多 Key（可选，优先于上方 API Key）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = if (keyVisible) "隐藏" else "显示",
                )
            }
        }
        keys.forEachIndexed { index, key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { value ->
                        onChange(keys.toMutableList().also { it[index] = value })
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                )
                IconButton(
                    onClick = { onChange(keys.filterIndexed { i, _ -> i != index }) },
                ) {
                    Icon(HugeIcons.Delete01, contentDescription = "删除")
                }
            }
        }
        TextButton(
            onClick = { onChange(keys + "") },
        ) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Text("添加 Key", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * 代理编辑器（M3a-1 parity）：四档与旧供应商编辑器同一语义（跟随全局/显式直连/HTTP/SOCKS5）。
 * 即改即存（与页面其它字段一致），不做单独保存按钮。
 */
@Composable
private fun MediaProxyEditor(
    proxy: ProviderProxy,
    onChange: (ProviderProxy) -> Unit,
) {
    val options = listOf(
        ProxyOption.Inherit,
        ProxyOption.None,
        ProxyOption.Http,
        ProxyOption.Socks5,
    )
    val selected = when (proxy) {
        is ProviderProxy.Inherit -> ProxyOption.Inherit
        is ProviderProxy.None -> ProxyOption.None
        is ProviderProxy.Http -> ProxyOption.Http
        is ProviderProxy.Socks5 -> ProxyOption.Socks5
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "网络代理",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(option.label) },
                    selected = selected == option,
                    onClick = { onChange(option.toDefaultProxy()) },
                )
            }
        }
        when (val current = proxy) {
            is ProviderProxy.Http -> MediaProxyFields(
                address = current.address,
                port = current.port,
                username = current.username.orEmpty(),
                password = current.password.orEmpty(),
                isSocks = false,
                onChange = onChange,
            )
            is ProviderProxy.Socks5 -> MediaProxyFields(
                address = current.address,
                port = current.port,
                username = current.username.orEmpty(),
                password = current.password.orEmpty(),
                isSocks = true,
                onChange = onChange,
            )
            else -> {}
        }
    }
}

private enum class ProxyOption(val label: String) {
    Inherit("跟随全局"),
    None("直连"),
    Http("HTTP"),
    Socks5("SOCKS5");

    fun toDefaultProxy(): ProviderProxy = when (this) {
        Inherit -> ProviderProxy.Inherit
        None -> ProviderProxy.None
        Http -> ProviderProxy.Http(address = "", port = 8080)
        Socks5 -> ProviderProxy.Socks5(address = "", port = 1080)
    }
}

/** HTTP / SOCKS5 共用的地址·端口·认证输入（与旧供应商编辑器 [ProxyFields] 同款语义）。 */
@Composable
private fun MediaProxyFields(
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

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { onChange(build(addr = it.trim())) },
            label = { Text("地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        var portStr by remember(port) { mutableStateOf(port.toString()) }
        OutlinedTextField(
            value = portStr,
            onValueChange = {
                portStr = it
                it.toIntOrNull()?.let { p -> onChange(build(p = p)) }
            },
            label = { Text("端口") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = username,
            onValueChange = { onChange(build(user = it)) },
            label = { Text("用户名（可选）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { onChange(build(pass = it)) },
            label = { Text("密码（可选）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}
