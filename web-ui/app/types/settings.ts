/**
 * Web 端设置类型 —— `/api/events` 的 `settings` 事件载荷（后端白名单 DTO）。
 *
 * @see app/src/main/java/app/androidrik/rikkahub/web/dto/WebSettingsDto.kt - WebSettingsDto
 * @see app/src/main/java/app/androidrik/rikkahub/data/datastore/PreferencesStore.kt - Settings
 *
 * [SECURITY] 这里刻意只声明 **web-ui 真正渲染所需的最小字段集**：后端 `WebSettingsDto`
 * 同样只序列化这些字段（顶层恰好 14 个 key，无 `init`、不再是全量 Settings），**不会**把
 * API key / baseUrl / 系统提示词 / 温度 / 正则 / MCP 地址与 OAuth token / 各类 prompt 等
 * 推给浏览器。
 *
 * 字段名与 Kotlin `WebSettingsDto` 属性名逐一对应（camelCase，Uuid → String）。
 * 新增前端要读的字段时：先改 Kotlin 白名单 DTO，再同步本文件；只改 TS 会在运行期读到
 * `undefined`，只改 Kotlin 则是无用的暴露面。
 */

/** 用户头像 / 助手头像（Kotlin `Avatar`，序列化后带 `type` 判别字段）。 */
export interface AssistantAvatar {
  /** 判别值，固定短名 `"dummy" | "emoji" | "image"`。 */
  type: string;
  /** 仅 emoji 头像有内容；其它 kind 为 null。 */
  content?: string | null;
  /** 仅图片头像有 URL（`file://`，前端经 `resolveFileUrl` 转换）；其它 kind 为 null。 */
  url?: string | null;
}

/** 显示相关设置（`DisplaySetting` 的展示子集，恰好 13 个字段）。 */
export interface DisplaySetting {
  userNickname: string;
  userAvatar?: AssistantAvatar;
  showUserAvatar: boolean;
  showModelIcon: boolean;
  showModelName: boolean;
  showTokenUsage: boolean;
  showThinkingContent: boolean;
  autoCloseThinking: boolean;
  codeBlockAutoWrap: boolean;
  showLineNumbers: boolean;
  sendOnEnter: boolean;
  pasteLongTextAsFile: boolean;
  pasteLongTextThreshold: number;
}

/** 助手标签（`Tag`，同时用于对话列表按标签筛选）。 */
export interface AssistantTag {
  id: string;
  name: string;
}

/** 快捷消息（助手 quickMessageIds 引用它）。 */
export interface QuickMessage {
  id: string;
  title: string;
  content: string;
}

/**
 * 模式注入（`PromptInjection.ModeInjection`）的**展示子集**。
 * 注意：不含 `content` / `priority` / `position` / `role` / `injectDepth` —— 注入正文不下发浏览器。
 */
export interface ModeInjectionProfile {
  id: string;
  name: string;
  enabled: boolean;
}

/**
 * 世界书（`Lorebook`）的**展示子集**。
 * 注意：不含 `entries`（正则注入正文不下发浏览器）。
 */
export interface LorebookProfile {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
}

/**
 * 助手（`Assistant` 的白名单子集，不含 systemPrompt / 采样参数 / 角色卡字段等）。
 *
 * `reasoningLevel` 取值：`"off" | "auto" | "low" | "medium" | "high" | "xhigh" | "max"`。
 */
export interface AssistantProfile {
  id: string;
  name: string;
  avatar: AssistantAvatar;
  useAssistantAvatar: boolean;
  /** 标签 id 列表（配合 `assistantTags` 解析名字）。 */
  tags: string[];
  chatModelId?: string | null;
  fallbackChatModelId?: string | null;
  reasoningLevel: string;
  enableWebSearch: boolean;
  mcpServers: string[];
  modeInjectionIds: string[];
  lorebookIds: string[];
  quickMessageIds: string[];
  allowConversationPromptInjection: boolean;
}

/** MCP 工具项的展示信息。 */
export interface McpToolOption {
  enable: boolean;
  name: string;
  description?: string | null;
  needsApproval: boolean;
}

/** MCP Server 的通用选项（不含 URL / headers / OAuth / 证书）。 */
export interface McpCommonOptions {
  enable: boolean;
  name: string;
  tools: McpToolOption[];
}

/**
 * MCP Server（仅 id + 展示用通用选项）。
 * 注意：白名单**不含** transport `type`（sse/streamable_http）、url、headers、oauth、证书。
 */
export interface McpServerConfig {
  id: string;
  commonOptions: McpCommonOptions;
}

export type ModelType = "CHAT" | "IMAGE" | "EMBEDDING";
export type ModelModality = "TEXT" | "IMAGE";
export type ModelAbility = "TOOL" | "REASONING";

/** 模型内置工具（前端只用判别值 `type` 判断「内置搜索」；取值如 `search`/`url_context`/`image_generation`）。 */
export interface BuiltInTool {
  type?: string;
}

/** 模型（`Model` 的白名单子集）。 */
export interface ProviderModel {
  id: string;
  modelId: string;
  displayName: string;
  type: ModelType;
  abilities: ModelAbility[];
  inputModalities: ModelModality[];
  outputModalities: ModelModality[];
  tools: BuiltInTool[];
}

/** 提供商（不含 apiKey / baseUrl / proxy / 自定义请求头与 body）。 */
export interface ProviderProfile {
  id: string;
  enabled: boolean;
  name: string;
  models: ProviderModel[];
}

/** 搜索服务（不含 apiKey；`type` 是 sealed class 的 @SerialName 判别值，用于显示名映射）。 */
export interface SearchServiceOption {
  id: string;
  type: string;
  enabled: boolean;
}

/**
 * Web 设置载荷（白名单）——`settings` SSE 事件 + 首连快照的完整类型（顶层恰好 14 个 key）。
 *
 * 可空字段按后端契约用 `string | null` 表示（assistants[].chatModelId / fallbackChatModelId
 * 未绑定时为 JSON null，不是省略）。
 */
export interface WebSettings {
  displaySetting: DisplaySetting;
  favoriteModels: string[];
  chatModelId: string;
  assistantId: string;
  providers: ProviderProfile[];
  assistants: AssistantProfile[];
  assistantTags: AssistantTag[];
  modeInjections: ModeInjectionProfile[];
  lorebooks: LorebookProfile[];
  quickMessages: QuickMessage[];
  mcpServers: McpServerConfig[];
  searchServices: SearchServiceOption[];
  searchServiceSelected: number;
  /** 鉴权已恒开；前端仅用于显示「清除 Web 会话」按钮。 */
  webServerJwtEnabled: boolean;
}

/** 兼容别名：全仓库按 `Settings` 引用设置对象。 */
export type Settings = WebSettings;
