package app.androidrik.rikkahub.web.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.QuickMessage
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.search.SearchServiceOptions

/**
 * `/api/events` 的 `settings` 事件 DTO：**白名单**，只含 web-ui 真正消费的字段。
 *
 * **为什么不能直接序列化 [Settings]**：Settings 是本地全量配置，里面带
 * - 各 provider 的 `apiKey`/`apiKeys`/`customHeaders`（Authorization）/`proxy` 账号密码、
 * - `webServerAccessPassword`、WebDAV/S3 凭据、TTS/ASR 凭据、
 * - 助手的 `systemPrompt`/`card*` 人设全文、MCP 的 `headers`/`oauth` 令牌/客户端私钥。
 *
 * 任何一项都不该跨 HTTP 边界，哪怕是「同一个用户」——Web 端 access token 会落到浏览器
 * 存储（web-ui 已由 localStorage 改为 sessionStorage），浏览器扩展、XSS 与局域网嗅探都能看到。
 * 此前的实现直接 `encodeToString(settings)` 全量下发，属于凭据泄漏（本文件即修复）。
 *
 * **契约维护**：字段清单以 web-ui 的实际读取为准（`web-ui/app/components`、`app/hooks`、
 * `app/routes` 逐处核对）。新增字段前先确认前端确实读它；**永远不要把业务类型直接塞进来**，
 * 一律显式映射到下面的 DTO —— 这样业务模型将来加 `apiKey`/`token` 之类字段时，
 * 泄漏不会被「顺手」带出去。
 */
@Serializable
data class WebSettingsDto(
    val assistantId: String,
    val chatModelId: String,
    val favoriteModels: List<String>,
    val displaySetting: WebDisplaySettingDto,
    val providers: List<WebProviderDto>,
    val assistants: List<WebAssistantDto>,
    val assistantTags: List<WebTagDto>,
    val modeInjections: List<WebModeInjectionDto>,
    val lorebooks: List<WebLorebookDto>,
    val quickMessages: List<WebQuickMessageDto>,
    val mcpServers: List<WebMcpServerDto>,
    val searchServices: List<WebSearchServiceDto>,
    val searchServiceSelected: Int,
    val webServerJwtEnabled: Boolean,
)

/**
 * 显示设置白名单：web-ui 渲染消息/输入框实际读取的字段。
 *
 * 刻意**不含** `userAvatar` 之外的图片类字段、TTS/通知/字体等仅原生端使用的开关。
 */
@Serializable
data class WebDisplaySettingDto(
    val userNickname: String,
    val userAvatar: WebAvatarDto,
    val showUserAvatar: Boolean,
    val showModelIcon: Boolean,
    val showModelName: Boolean,
    val showTokenUsage: Boolean,
    val showThinkingContent: Boolean,
    val autoCloseThinking: Boolean,
    val codeBlockAutoWrap: Boolean,
    val showLineNumbers: Boolean,
    val sendOnEnter: Boolean,
    val pasteLongTextAsFile: Boolean,
    val pasteLongTextThreshold: Int,
)

/**
 * 头像的**展平白名单** `{type, content?, url?}`。
 *
 * 前端只读 `content`（emoji）与 `url`（图片），`type` 仅作 React 重挂载的 identity。
 * 不复用 [Avatar]：它是业务模型，将来若加上本地路径/裁剪参数之类字段不该自动外泄。
 */
@Serializable
data class WebAvatarDto(
    val type: String,
    val content: String? = null,
    val url: String? = null,
)

/** 提供商白名单：`apiKey`/`baseUrl`/`proxy`/`customHeaders`/`customBodies` 一律不出现。 */
@Serializable
data class WebProviderDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val models: List<WebProviderModelDto>,
)

/** 模型白名单：能力/模态/内置工具（前端据此判断联网搜索与推理档位）。 */
@Serializable
data class WebProviderModelDto(
    val id: String,
    val modelId: String,
    val displayName: String,
    val type: String,
    val abilities: List<String>,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val tools: List<WebBuiltInToolDto>,
)

/** 内置工具判别值（`search` / `url_context` / `image_generation`），前端用 `type` 判断。 */
@Serializable
data class WebBuiltInToolDto(
    val type: String,
)

/**
 * 助手白名单：**不含** `systemPrompt` / `card*` 人设全文 / `temperature` / `customHeaders` /
 * `presetMessages` / `background` / `regexes` —— 这些要么是私有内容，要么是原生端专属。
 */
@Serializable
data class WebAssistantDto(
    val id: String,
    val name: String,
    val avatar: WebAvatarDto,
    val useAssistantAvatar: Boolean,
    val tags: List<String>,
    val chatModelId: String? = null,
    val fallbackChatModelId: String? = null,
    val reasoningLevel: String,
    val enableWebSearch: Boolean,
    val mcpServers: List<String>,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val quickMessageIds: List<String>,
    val allowConversationPromptInjection: Boolean,
)

/** 注入条目白名单：只给「名字 + 开关态」，不给 `content`/正则/深度等正文。 */
@Serializable
data class WebModeInjectionDto(
    val id: String,
    val name: String,
    val enabled: Boolean,
)

/** 世界书白名单：不含 `entries`（世界书条目正文可能很长且属私有内容）。 */
@Serializable
data class WebLorebookDto(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean,
)

/** 快捷消息需要正文（前端点选后要插入输入框），属用户主动配置的短文本。 */
@Serializable
data class WebQuickMessageDto(
    val id: String,
    val title: String,
    val content: String,
)

/**
 * MCP Server 白名单：**只给 id + 公共选项（开关/名字/工具清单）**。
 *
 * 刻意不含 `url`（可能内嵌凭据）、`headers`（常带 `Authorization: Bearer …`）、
 * `oauth`（access/refresh token、client_secret）、`clientCertificate`（证书/私钥）。
 */
@Serializable
data class WebMcpServerDto(
    val id: String,
    val commonOptions: WebMcpCommonOptionsDto,
)

@Serializable
data class WebMcpCommonOptionsDto(
    val enable: Boolean,
    val name: String,
    val tools: List<WebMcpToolDto>,
)

@Serializable
data class WebMcpToolDto(
    val enable: Boolean,
    val name: String,
    val description: String? = null,
    val needsApproval: Boolean,
)

/** 搜索服务白名单：`id` + 判别值 `type`（前端据 `type` 映射显示名）。不含 `apiKey`。 */
@Serializable
data class WebSearchServiceDto(
    val id: String,
    val type: String,
    val enabled: Boolean,
)

/** 标签（`id` + `name`）：助手/会话的标签池，前端只做展示与筛选。 */
@Serializable
data class WebTagDto(
    val id: String,
    val name: String,
)

// ========== Conversion ==========

fun Settings.toWebSettingsDto(): WebSettingsDto = WebSettingsDto(
    assistantId = assistantId.toString(),
    chatModelId = chatModelId.toString(),
    favoriteModels = favoriteModels.map { it.toString() },
    displaySetting = displaySetting.toWebDto(),
    providers = providers.map { it.toWebDto() },
    assistants = assistants.map { it.toWebDto() },
    assistantTags = assistantTags.map { it.toWebDto() },
    modeInjections = modeInjections.map { it.toWebDto() },
    lorebooks = lorebooks.map { it.toWebDto() },
    quickMessages = quickMessages.map { it.toWebDto() },
    mcpServers = mcpServers.map { it.toWebDto() },
    searchServices = searchServices.map { it.toWebDto() },
    searchServiceSelected = searchServiceSelected,
    webServerJwtEnabled = webServerJwtEnabled,
)

private fun DisplaySetting.toWebDto(): WebDisplaySettingDto = WebDisplaySettingDto(
    userNickname = userNickname,
    userAvatar = userAvatar.toWebDto(),
    showUserAvatar = showUserAvatar,
    showModelIcon = showModelIcon,
    showModelName = showModelName,
    showTokenUsage = showTokenUsage,
    showThinkingContent = showThinkingContent,
    autoCloseThinking = autoCloseThinking,
    codeBlockAutoWrap = codeBlockAutoWrap,
    showLineNumbers = showLineNumbers,
    sendOnEnter = sendOnEnter,
    pasteLongTextAsFile = pasteLongTextAsFile,
    pasteLongTextThreshold = pasteLongTextThreshold,
)

private fun Avatar.toWebDto(): WebAvatarDto = when (this) {
    is Avatar.Dummy -> WebAvatarDto(type = "dummy")
    is Avatar.Emoji -> WebAvatarDto(type = "emoji", content = content)
    is Avatar.Image -> WebAvatarDto(type = "image", url = url)
}

private fun ProviderSetting.toWebDto(): WebProviderDto = WebProviderDto(
    id = id.toString(),
    name = name,
    enabled = enabled,
    models = models.map { it.toWebDto() },
)

private fun Model.toWebDto(): WebProviderModelDto = WebProviderModelDto(
    id = id.toString(),
    modelId = modelId,
    displayName = displayName,
    type = enumWireName(ModelType.serializer(), type),
    abilities = abilities.map { enumWireName(ModelAbility.serializer(), it) },
    inputModalities = inputModalities.map { enumWireName(Modality.serializer(), it) },
    outputModalities = outputModalities.map { enumWireName(Modality.serializer(), it) },
    tools = tools.map { it.toWebDto() },
)

/** 内置工具 → 判别值。显式 `when`（而非复用序列化器）：新增子类会**编译失败**，逼着同步契约。 */
private fun BuiltInTools.toWebDto(): WebBuiltInToolDto = WebBuiltInToolDto(
    type = when (this) {
        BuiltInTools.Search -> "search"
        BuiltInTools.UrlContext -> "url_context"
        BuiltInTools.ImageGeneration -> "image_generation"
    }
)

private fun Assistant.toWebDto(): WebAssistantDto = WebAssistantDto(
    id = id.toString(),
    name = name,
    avatar = avatar.toWebDto(),
    useAssistantAvatar = useAssistantAvatar,
    tags = tags.map { it.toString() },
    chatModelId = chatModelId?.toString(),
    fallbackChatModelId = fallbackChatModelId?.toString(),
    reasoningLevel = enumWireName(ReasoningLevel.serializer(), reasoningLevel),
    enableWebSearch = enableWebSearch,
    mcpServers = mcpServers.map { it.toString() },
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    quickMessageIds = quickMessageIds.map { it.toString() },
    allowConversationPromptInjection = allowConversationPromptInjection,
)

private fun PromptInjection.ModeInjection.toWebDto(): WebModeInjectionDto = WebModeInjectionDto(
    id = id.toString(),
    name = name,
    enabled = enabled,
)

private fun Lorebook.toWebDto(): WebLorebookDto = WebLorebookDto(
    id = id.toString(),
    name = name,
    description = description,
    enabled = enabled,
)

private fun QuickMessage.toWebDto(): WebQuickMessageDto = WebQuickMessageDto(
    id = id.toString(),
    title = title,
    content = content,
)

private fun Tag.toWebDto(): WebTagDto = WebTagDto(id = id.toString(), name = name)

private fun McpServerConfig.toWebDto(): WebMcpServerDto = WebMcpServerDto(
    id = id.toString(),
    commonOptions = WebMcpCommonOptionsDto(
        enable = commonOptions.enable,
        name = commonOptions.name,
        tools = commonOptions.tools.map {
            WebMcpToolDto(
                enable = it.enable,
                name = it.name,
                description = it.description,
                needsApproval = it.needsApproval,
            )
        },
    ),
)

private fun SearchServiceOptions.toWebDto(): WebSearchServiceDto = WebSearchServiceDto(
    id = id.toString(),
    type = sealedTypeName(SearchServiceOptions.serializer(), this),
    enabled = enabled,
)

/**
 * 取 sealed class 在 JSON 里的 `type` 判别值（与 `JsonInstant` 序列化 Settings 的 wire format 一致）。
 *
 * 搜索服务有 ~19 个子类、且序列化名就是前端契约（`exa`/`tavily`/…），手写 `when` 映射表
 * 容易在新增第 20 个子类时静默漂移，故直接复用序列化结果；只在内存里瞬时编码、只取判别值，
 * 不对外输出（`apiKey` 等字段不会离开进程）。
 */
private fun <T : Any> sealedTypeName(serializer: KSerializer<T>, value: T): String =
    (JsonInstant.encodeToJsonElement(serializer, value) as? JsonObject)
        ?.get("type")
        ?.jsonPrimitive
        ?.content
        ?: value::class.simpleName
        ?: "unknown"

/** 取枚举在 JSON 里的字面量（`@SerialName` 优先），避免手写映射与序列化结果不一致。 */
private fun <T> enumWireName(serializer: KSerializer<T>, value: T): String =
    JsonInstant.encodeToJsonElement(serializer, value).jsonPrimitive.content
