package app.androidrik.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.scale
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.ClaudePromptCacheTtl
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.supportsQuota
import app.androidrik.rikkahub.data.ai.account.AccountLoginStatus
import app.androidrik.rikkahub.data.ai.account.ChatGPTAuth
import app.androidrik.rikkahub.data.ai.account.ProviderAccountManager
import app.androidrik.rikkahub.data.ai.mcp.launchOAuthAuthorization
import app.androidrik.rikkahub.data.ai.openai.CodexAuthErrorCode
import app.androidrik.rikkahub.data.ai.openai.CodexAuthException
import app.androidrik.rikkahub.data.ai.openai.CodexDeviceCode
import app.androidrik.rikkahub.data.ai.openai.OpenAICodexAuthService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.utils.DragOrderState
import app.androidrik.rikkahub.utils.reorderForDrag
import app.androidrik.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.DragDropVertical
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import app.androidrik.rikkahub.ui.context.LocalToaster
import androidx.compose.ui.platform.LocalResources
import app.androidrik.rikkahub.ui.components.ai.displayPercent
import app.androidrik.rikkahub.ui.components.ai.label
import app.androidrik.rikkahub.ui.components.ai.longLabel
import app.androidrik.rikkahub.ui.components.ai.rememberProviderQuota
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.components.ui.CardGroup
import app.androidrik.rikkahub.ui.components.ui.CardGroupIcon
import app.androidrik.rikkahub.ui.theme.JetbrainsMono
import app.androidrik.rikkahub.utils.toMessageTimeString
import app.androidrik.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DashboardSpeed02
import me.rerere.hugeicons.stroke.Login03
import me.rerere.hugeicons.stroke.Logout03
import me.rerere.hugeicons.stroke.UserCircle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlin.time.toJavaInstant

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        // 账号类提供商（ChatGPT / Grok）的 API 格式由登录方式决定，不参与类型切换
        if (!provider.builtIn && provider !is ProviderSetting.Account) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderSetting.Types.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        // 用显式展示名而非 `KClass.simpleName`：后者在 release 下是 R8
                        // 混淆后的短名（it9/ft9/bt9），只有正式版会暴露——见 ProviderSetting.Types
                        label = { Text(option.displayName) },
                        selected = provider::class == option.type,
                        onClick = { onEdit(provider.convertTo(option.type)) }
                    )
                }
            }
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
            is ProviderSetting.ChatGPT -> ProviderConfigureAccount(
                provider = provider,
                onEdit = onEdit,
                serviceName = "ChatGPT",
                plan = provider.credential?.plan?.let { plan ->
                    "ChatGPT ${ChatGPTAuth.planDisplayName(plan)}"
                },
                disclaimer = stringResource(R.string.setting_provider_page_chatgpt_disclaimer),
            )

            is ProviderSetting.Grok -> ProviderConfigureAccount(
                provider = provider,
                onEdit = onEdit,
                serviceName = "Grok",
                plan = null,
                disclaimer = stringResource(R.string.setting_provider_page_grok_disclaimer),
            )
        }

        // 思考强度映射的提供商级兜底：模型级覆盖优先于它，它优先于内置映射（见 ReasoningOverride）
        ReasoningOverrideSettings(
            title = stringResource(R.string.setting_provider_page_reasoning_override_provider),
            description = stringResource(R.string.setting_provider_page_reasoning_override_provider_desc),
            override = provider.reasoningOverride,
            onUpdate = { onEdit(provider.copyProvider(reasoningOverride = it)) }
        )
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    // convertTo 只在 API Key 类之间切换；账号类没有这些字段，调用方也不应走到这里
    val apiKeySetting = this as? ProviderSetting.ApiKey ?: return this
    val apiKey = when (apiKeySetting) {
        is ProviderSetting.OpenAI -> apiKeySetting.apiKey
        is ProviderSetting.Google -> apiKeySetting.apiKey
        is ProviderSetting.Claude -> apiKeySetting.apiKey
    }
    val apiKeys = when (apiKeySetting) {
        is ProviderSetting.OpenAI -> apiKeySetting.apiKeys
        is ProviderSetting.Google -> apiKeySetting.apiKeys
        is ProviderSetting.Claude -> apiKeySetting.apiKeys
    }
    val sourceBaseUrl = when (apiKeySetting) {
        is ProviderSetting.OpenAI -> apiKeySetting.baseUrl
        is ProviderSetting.Google -> apiKeySetting.baseUrl
        is ProviderSetting.Claude -> apiKeySetting.baseUrl
    }
    val quotaQuery = apiKeySetting.quotaQuery
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            proxy = this.proxy, customHeaders = this.customHeaders,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, baseUrl = convertedBaseUrl,
            quotaQuery = quotaQuery, reasoningOverride = this.reasoningOverride
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            proxy = this.proxy, customHeaders = this.customHeaders,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, baseUrl = convertedBaseUrl,
            quotaQuery = quotaQuery, reasoningOverride = this.reasoningOverride
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            proxy = this.proxy, customHeaders = this.customHeaders,
            builtIn = this.builtIn, description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, apiKeys = apiKeys, baseUrl = convertedBaseUrl,
            quotaQuery = quotaQuery, reasoningOverride = this.reasoningOverride
        )
        else -> error("Unsupported provider type: $type")
    }
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
            is ProviderSetting.Account -> return ""
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
        is ProviderSetting.Account -> ""
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Account -> this
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.Account -> return true
    }
    return baseUrl == defaultBaseUrlForReset()
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val codexAuthService: OpenAICodexAuthService = koinInject()
    val providerManager: ProviderManager = koinInject()

    var deviceCode by remember { mutableStateOf<CodexDeviceCode?>(null) }
    var loginError by remember { mutableStateOf<CodexAuthException?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    // 调试用：强制刷新中的状态（避免连点 / 与登录互斥）
    var refreshing by remember { mutableStateOf(false) }

    val copiedMessage = stringResource(R.string.setting_provider_page_codex_copied)
    val successMessage = stringResource(R.string.setting_provider_page_codex_login_success)

    fun copyDeviceCode(code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ChatGPT device code", code))
        toaster.show(message = copiedMessage, type = ToastType.Success)
    }

    fun startLogin() {
        if (loginJob != null) return
        loginError = null
        loginJob = scope.launch {
            try {
                val account = codexAuthService.startLogin(provider.id) { code ->
                    deviceCode = code
                    // 自动复制设备码并打开授权页：用户回浏览器粘贴即可，不必手动抄写
                    copyDeviceCode(code.userCode)
                    runCatching { launchOAuthAuthorization(context, code.verificationUrl) }
                }
                val updated = provider.copy(
                    authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                    codexAccount = account,
                    includeHistoryReasoning = true,
                )
                // 登录后拉一次 Codex 模型列表；失败不影响登录结果（用户可稍后手动刷新）
                val models = runCatching {
                    providerManager.getProviderByType(updated).listModels(updated)
                }.getOrDefault(emptyList())
                onEdit(if (models.isEmpty()) updated else updated.copy(models = models))
                toaster.show(message = successMessage, type = ToastType.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CodexAuthException) {
                loginError = e
            } catch (e: Exception) {
                loginError = CodexAuthException(
                    CodexAuthErrorCode.UNKNOWN,
                    e.message ?: "Unknown error",
                )
            } finally {
                deviceCode = null
                loginJob = null
            }
        }
    }

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    // 认证方式：API Key（默认，旧行为） / ChatGPT 订阅（设备码登录 + 官方 Codex 后端）
    if (provider.supportsChatGPTSubscription()) {
        Text(
            text = stringResource(R.string.setting_provider_page_auth_method),
            style = MaterialTheme.typography.labelLarge,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            OpenAIAuthType.entries.forEachIndexed { index, authType ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = OpenAIAuthType.entries.size,
                    ),
                    label = {
                        Text(
                            stringResource(
                                if (authType == OpenAIAuthType.API_KEY) {
                                    R.string.setting_provider_page_auth_api_key
                                } else {
                                    R.string.setting_provider_page_auth_chatgpt
                                }
                            )
                        )
                    },
                    selected = provider.authType == authType,
                    onClick = {
                        loginError = null
                        onEdit(
                            provider.copy(
                                authType = authType,
                                // Codex 多轮推理依赖回传历史推理内容，订阅态强制打开
                                includeHistoryReasoning = if (authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
                                    true
                                } else {
                                    provider.includeHistoryReasoning
                                },
                            )
                        )
                    },
                )
            }
        }
    }

    if (provider.authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
        Text(
            text = stringResource(R.string.setting_provider_page_codex_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val account = provider.codexAccount
        if (account != null) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_codex_account) + ": " +
                        (account.email ?: account.accountId),
                )
                account.planType?.takeIf { it.isNotBlank() }?.let { plan ->
                    Text(
                        text = stringResource(R.string.setting_provider_page_codex_plan) + ": " + plan,
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        codexAuthService.logout(provider.id)
                        onEdit(
                            provider.copy(
                                authType = OpenAIAuthType.API_KEY,
                                codexAccount = null,
                            )
                        )
                    }
                },
                enabled = loginJob == null,
            ) {
                Text(stringResource(R.string.setting_provider_page_codex_logout))
            }
            // 调试入口：access token 1h 有效，等自然过期太久；强制刷新一次即可验证
            // 刷新链路与「JSON 优先、form 回退」实际命中哪种编码（结果从 toast 读）。
            if (BuildConfig.DEBUG) {
                OutlinedButton(
                    onClick = {
                        if (refreshing) return@OutlinedButton
                        scope.launch {
                            refreshing = true
                            try {
                                val result = codexAuthService.forceRefresh(provider.id)
                                onEdit(provider.copy(codexAccount = result.account))
                                toaster.show(
                                    message = resources.getString(
                                        R.string.setting_provider_page_codex_debug_refresh_ok,
                                        result.encoding.name,
                                    ),
                                    type = ToastType.Success,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: CodexAuthException) {
                                loginError = e
                            } finally {
                                refreshing = false
                            }
                        }
                    },
                    enabled = loginJob == null && !refreshing,
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.setting_provider_page_codex_debug_force_refresh))
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { startLogin() },
                enabled = loginJob == null,
            ) {
                if (loginJob != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.setting_provider_page_codex_login))
                }
            }
        }

        loginError?.let { error ->
            Text(
                text = codexErrorMessage(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.setting_provider_page_codex_usage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        // 多 key 编辑器：每 key 一框 + 启用开关（兼容旧单 key 字段展示）
        ProviderApiKeysEditor(
            keys = provider.apiKeys,
            legacyKey = provider.apiKey,
            onChange = { newKeys -> onEdit(provider.copy(apiKeys = newKeys)) },
        )

        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
        )

        // API 路径按协议切换：Response API 用 responsesPath，Chat Completions 用 chatCompletionsPath
        //（对齐上游 a61d116d：此前 Response API 路径固定 /responses，中转端点无法自定义）
        OutlinedTextField(
            value = if (provider.useResponseApi) provider.responsesPath else provider.chatCompletionsPath,
            onValueChange = {
                onEdit(
                    if (provider.useResponseApi) {
                        provider.copy(responsesPath = it.trim())
                    } else {
                        provider.copy(chatCompletionsPath = it.trim())
                    }
                )
            },
            label = { Text(stringResource(R.string.setting_provider_page_api_path)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !provider.builtIn,
        )

        val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_provider_page_response_api))
            Switch(
                checked = provider.useResponseApi,
                onCheckedChange = {
                    onEdit(provider.copy(useResponseApi = it))
                    if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                        toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                    }
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_provider_page_include_history_reasoning))
            Switch(
                checked = provider.includeHistoryReasoning,
                onCheckedChange = { onEdit(provider.copy(includeHistoryReasoning = it)) }
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    deviceCode?.let { code ->
        CodexDeviceCodeDialog(
            deviceCode = code,
            onCopy = { copyDeviceCode(code.userCode) },
            onOpenBrowser = {
                runCatching { launchOAuthAuthorization(context, code.verificationUrl) }
            },
            onCancel = {
                loginJob?.cancel()
                loginJob = null
                deviceCode = null
            },
        )
    }
}

private fun ProviderSetting.OpenAI.supportsChatGPTSubscription(): Boolean {
    val url = baseUrl.toHttpUrlOrNull() ?: return false
    return url.host == "api.openai.com" || url.host == "chatgpt.com"
}

@Composable
private fun CodexDeviceCodeDialog(
    deviceCode: CodexDeviceCode,
    onCopy: () -> Unit,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.setting_provider_page_codex_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.setting_provider_page_codex_device_desc))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = deviceCode.userCode,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = JetbrainsMono,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.setting_provider_page_codex_device_expires, 15),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.setting_provider_page_codex_waiting),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenBrowser) {
                Text(stringResource(R.string.setting_provider_page_codex_open_browser))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.setting_provider_page_codex_copy))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.setting_provider_page_codex_cancel))
                }
            }
        },
    )
}

@Composable
private fun codexErrorMessage(error: CodexAuthException): String = when (error.code) {
    CodexAuthErrorCode.REGION_BLOCKED -> stringResource(R.string.setting_provider_page_codex_error_region)
    CodexAuthErrorCode.NETWORK -> stringResource(R.string.setting_provider_page_codex_error_network)
    CodexAuthErrorCode.EXPIRED -> stringResource(R.string.setting_provider_page_codex_error_expired)
    CodexAuthErrorCode.INVALID_GRANT -> stringResource(R.string.setting_provider_page_codex_error_reauth)
    else -> stringResource(R.string.setting_provider_page_codex_error_failed, error.message.orEmpty())
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    // 多 key 编辑器：每 key 一框 + 启用开关（兼容旧单 key 字段展示）
    ProviderApiKeysEditor(
        keys = provider.apiKeys,
        legacyKey = provider.apiKey,
        onChange = { newKeys -> onEdit(provider.copy(apiKeys = newKeys)) },
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_caching))
        Switch(
            checked = provider.promptCaching,
            onCheckedChange = { onEdit(provider.copy(promptCaching = it)) }
        )
    }

    if (provider.promptCaching) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ClaudePromptCacheTtl.entries.forEachIndexed { index, ttl ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ClaudePromptCacheTtl.entries.size
                    ),
                    label = {
                        Text(
                            when (ttl) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                                ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                            }
                        )
                    },
                    selected = provider.promptCacheTtl == ttl,
                    onClick = { onEdit(provider.copy(promptCacheTtl = ttl)) }
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        // 多 key 编辑器：每 key 一框 + 启用开关（兼容旧单 key 字段展示）
        ProviderApiKeysEditor(
            keys = provider.apiKeys,
            legacyKey = provider.apiKey,
            onChange = { newKeys -> onEdit(provider.copy(apiKeys = newKeys)) },
        )
    }

    if (!provider.vertexAI) {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && (
                !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                ),
            supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
                { Text("The base URL usually ends with `/v1beta`") }
            } else null,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copy(enabled = it)) }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_vertex_ai))
        Switch(
            checked = provider.vertexAI,
            onCheckedChange = { onEdit(provider.copy(vertexAI = it)) }
        )
    }

    if (provider.vertexAI) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_provider_page_use_service_account))
            Switch(
                checked = provider.useServiceAccount,
                onCheckedChange = { onEdit(provider.copy(useServiceAccount = it)) }
            )
        }
    }

    if (provider.vertexAI && provider.useServiceAccount) {
        OutlinedButton(
            onClick = { serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setting_provider_page_import_service_account_json))
        }

        OutlinedTextField(
            value = provider.serviceAccountEmail,
            onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
            modifier = Modifier.fillMaxWidth(),
        )

        var privateKeyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.privateKey,
            onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_private_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
            minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { privateKeyVisible = !privateKeyVisible }) {
                    Icon(if (privateKeyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )

        OutlinedTextField(
            value = provider.location,
            onValueChange = { onEdit(provider.copy(location = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_location)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = provider.projectId,
            onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_project_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 账号类提供商（ChatGPT / Grok）的登录状态、订阅用量和说明。
 *
 * 没有 API Key / Base URL 可填：地址写死在各自的 Provider 里，凭据只能通过登录获得，
 * 这样 token 不会被配置发往第三方地址（见 `ProviderSetting.Account`）。
 */
@Composable
private fun ProviderConfigureAccount(
    provider: ProviderSetting.Account,
    onEdit: (provider: ProviderSetting) -> Unit,
    serviceName: String,
    plan: String?,
    disclaimer: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountManager: ProviderAccountManager = koinInject()
    val loginStatus by accountManager.loginStatus.collectAsStateWithLifecycle()
    val status = loginStatus[provider.id]
    val credential = provider.credential

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copyProvider(name = it)) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        modifier = Modifier.fillMaxWidth(),
    )

    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_account)) }) {
        if (credential != null) {
            item(
                leadingContent = { CardGroupIcon(HugeIcons.UserCircle) },
                // ListItem 的 supportingContent 只渲染第一个直接子节点：这里只有一段文本，安全
                supportingContent = plan?.let { text -> { Text(text) } },
                headlineContent = {
                    Text(credential.email ?: stringResource(R.string.setting_provider_page_signed_in))
                },
            )
            item(
                onClick = { scope.launch { accountManager.logout(provider.id) } },
                leadingContent = {
                    CardGroupIcon(
                        icon = HugeIcons.Logout03,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_sign_out)) },
            )
        } else if (status is AccountLoginStatus.DeviceCode) {
            // 设备码登录：验证页已经自动开过一次，点这一项可以重新打开（user_code 已带上）
            item(
                onClick = {
                    CustomTabsOAuthAuthorizationLauncher.launch(context, status.verificationUrl)
                },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                overlineContent = { Text(stringResource(R.string.setting_provider_page_device_code_hint)) },
                supportingContent = { Text(stringResource(R.string.setting_provider_page_device_code_reopen)) },
                headlineContent = {
                    Text(
                        text = status.userCode,
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                        fontFamily = JetbrainsMono,
                    )
                },
            )
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { CardGroupIcon(HugeIcons.Cancel01) },
                headlineContent = { Text(stringResource(R.string.cancel)) },
            )
        } else if (status == AccountLoginStatus.Authorizing) {
            item(
                onClick = { accountManager.cancelLogin(provider.id) },
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                supportingContent = {
                    Text(stringResource(R.string.setting_provider_page_waiting_authorization_desc))
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_waiting_authorization)) },
            )
        } else {
            item(
                onClick = { accountManager.startLogin(provider.id, context) },
                leadingContent = { CardGroupIcon(HugeIcons.Login03) },
                supportingContent = (status as? AccountLoginStatus.Failed)?.let { failed ->
                    { Text(failed.message, color = MaterialTheme.colorScheme.error) }
                },
                trailingContent = { Icon(HugeIcons.ArrowRight01, contentDescription = null) },
                headlineContent = {
                    Text(stringResource(R.string.setting_provider_page_sign_in_with, serviceName))
                },
            )
        }
    }

    if (provider.supportsQuota) {
        AccountUsage(provider)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onEdit(provider.copyProvider(enabled = it)) }
        )
    }

    Text(
        text = disclaimer,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 订阅用量：每个窗口一条进度和重置时间。
 *
 * 额度查询按 provider id + 登录账号缓存 2 分钟（见 [rememberProviderQuota]），
 * 进页面、换账号都会重新查；退出登录后 [supportsQuota] 为 false，整块不再渲染。
 */
@Composable
private fun AccountUsage(provider: ProviderSetting.Account) {
    val mode = LocalSettings.current.usageDisplayMode
    val modeLabel = mode.label()
    val result = rememberProviderQuota(provider)
    val limits = result?.getOrNull() as? ProviderQuota.UsageLimits
    CardGroup(title = { Text(stringResource(R.string.setting_provider_page_usage)) }) {
        when {
            result == null -> item(
                leadingContent = { ContainedLoadingIndicator(modifier = Modifier.size(40.dp)) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_loading)) },
            )

            limits == null -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                supportingContent = {
                    Text(
                        text = result.exceptionOrNull()?.message.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_failed)) },
            )

            limits.windows.isEmpty() -> item(
                leadingContent = { CardGroupIcon(HugeIcons.DashboardSpeed02) },
                headlineContent = { Text(stringResource(R.string.setting_provider_page_usage_none)) },
            )

            else -> limits.windows.forEach { window ->
                val reached = limits.limitReached || window.usedPercent >= 100
                val displayPercent = window.displayPercent(mode)
                formItem(
                    headlineContent = { Text(window.longLabel()) },
                    supportingContent = window.resetsAt?.let { resetsAt ->
                        {
                            val time = LocalDateTime.ofInstant(
                                resetsAt.toJavaInstant(),
                                ZoneId.systemDefault(),
                            )
                            Text(stringResource(R.string.setting_provider_page_usage_resets, time.toMessageTimeString()))
                        }
                    },
                    trailingContent = {
                        Text(
                            text = "$modeLabel $displayPercent%",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = if (reached) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                ) {
                    LinearWavyProgressIndicator(
                        progress = { (displayPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (reached) {
                            MaterialTheme.colorScheme.error
                        } else {
                            ProgressIndicatorDefaults.linearColor
                        },
                    )
                }
            }
        }
    }
}

/**
 * 多 key 编辑器：每 key 一框 + 启用开关 + 删除；底部「添加 key」。
 * 旧单 key 字段（apiKey）有值且列表为空时，首行展示旧值，写回时迁入列表。
 */
@Composable
fun ProviderApiKeysEditor(
    keys: List<ApiKeyEntry>,
    legacyKey: String,
    onChange: (List<ApiKeyEntry>) -> Unit,
) {
    // 展示列表：结构化列表优先；空则用旧单 key 字段兜底展示（不修改数据直到用户编辑）
    val entries = remember(keys, legacyKey) {
        if (keys.isNotEmpty()) keys else ApiKeyEntry.fromLegacy(legacyKey)
    }
    // 全局显隐切换：控制所有 key 行明文/遮蔽（对齐单 key 时代的眼睛按钮）
    var keyVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.setting_provider_page_api_key),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = if (keyVisible) {
                        stringResource(R.string.setting_provider_page_hide_keys)
                    } else {
                        stringResource(R.string.setting_provider_page_show_keys)
                    },
                )
            }
        }
        // 拖拽排序：列表顺序即优先级（首个=首选，失败按序切下一个）
        val lazyListState = rememberLazyListState()
        // 拖动只改本地顺序、松手才落盘：onChange 最终走 DataStore 异步回流会抽搐
        val keyDragOrder = remember { DragOrderState<ApiKeyEntry>() }
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            keyDragOrder.onMove(entries) { it.reorderForDrag(from.index, to.index) }
        }
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                // 有限高度约束：Editor 外层是普通 Column（FormItem content），
                // LazyColumn 无高度上限会崩（measure 无限约束）。条目少，固定上限足够。
                .heightIn(max = 240.dp),
        ) {
            itemsIndexed(
                items = keyDragOrder.pendingOrder ?: entries,
                // item key 必须用**稳定 id**（ApiKeyEntry.id）而非内容哈希：
                // 用 key.hashCode() 时每敲一个字符 key 就变 → 该行被销毁重建 →
                // 焦点丢失（IME 收起）+ animateItem 播放动画（抖动）——历史 bug
                key = { _, entry -> entry.id },
            ) { index, entry ->
                ReorderableItem(
                    state = reorderableState,
                    key = entry.id,
                ) { isDragging ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {},
                            modifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {},
                                onDragStopped = {
                                    keyDragOrder.onDragStopped { order -> onChange(order) }
                                },
                            ),
                        ) {
                            Icon(HugeIcons.DragDropVertical, contentDescription = stringResource(R.string.setting_provider_page_drag_to_reorder))
                        }
                        OutlinedTextField(
                            value = entry.key,
                            onValueChange = { newKey ->
                                val updated = entries.toMutableList().also { it[index] = entry.copy(key = newKey) }
                                onChange(updated)
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                            modifier = Modifier.weight(1f),
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        )
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { enabled ->
                                val updated = entries.toMutableList().also { it[index] = entry.copy(enabled = enabled) }
                                onChange(updated)
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(
                            onClick = {
                                val updated = entries.toMutableList().also { it.removeAt(index) }
                                onChange(updated)
                            },
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_provider_page_delete_key))
                        }
                    }
                }
            }
        }

        TextButton(
            onClick = {
                val updated = entries.toMutableList().also { it.add(ApiKeyEntry("")) }
                onChange(updated)
            },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Icon(HugeIcons.Add01, contentDescription = null)
            Text(
                text = stringResource(R.string.setting_provider_page_add_key),
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
