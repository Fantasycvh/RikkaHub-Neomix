# Codex 订阅登录（ChatGPT 账号）设计稿（Draft）

> 状态：**历史存档：S1 曾实施（174946239，2026-11-01），整条设备码链路后被 OAuth 移植路线取代并删除（7b81a9ded）；现行方案见 design/completed/design-upstream-oauth-port.md**。协议可用性已于 2026-11-01 用 curl 实测（见第三节）。
> 路线：设备授权登录（device authorization grant）+ 复用现有 `ProviderSetting.OpenAI`，不新增 provider 类型、不引入 `oauth` 模块。
> 血统：上游 Draft PR [#1752](https://github.com/rikkahub/rikkahub/pull/1752)（`Ayuilos`，已关闭未合并，作者确认「可以直接移植，没有已知问题」）→ 二次修改版 [`Lin-chpin/rikkahub-Jude`](https://github.com/Lin-chpin/rikkahub-Jude)（96★，ADR 参照）→ 本稿按本仓架构重写。
> 关联：`docs/upstream-analysis.md` 中 `318cffef6`（#2186）条目——本稿与 #2186 是两条不同路线，#2186 的请求体/模型解析语义作为正确性参考。
> 参考实现（只读调研）：`PR #1752`、`issue #1753`、`Lin-chpin/rikkahub-Jude`、`Inonvation/rikkahub`、`Ayuilos/Miffan`、`ExTV/rikkahub-agent` 系（多账号/额度）、`openai/codex` 的 `codex-rs/login/src/device_code_auth.rs`。

## 一、结论

1. **登录方式用设备授权（device code）**，不用 #2186 的 loopback PKCE：
   - 官方 Codex CLI 本身就是这个流程（`openai/codex/codex-rs/login/src/device_code_auth.rs` 仍在 main），Android 侧只需 OkHttp + 浏览器 Intent；
   - 不需要本地回调服务器、不需要前台服务、不需要 Manifest 深度链接、不需要新增模块；
   - 设备码端点已实测可用（第三节）。
2. **认证方式塞进现有 OpenAI Provider**（`ProviderSetting.OpenAI` 加 2 个可默认字段），不新增 sealed 子类型：
   - 旧版本解码时 `ignoreUnknownKeys` 忽略新字段 → **降级安装不崩**；
   - 不需要 DataStore 迁移、不需要动 ProviderManager 的多态分发、UI 只加一段。
3. **凭据加密落盘**（复用本仓 `SecretCipher`），并明确备份/日志/导入导出边界——这是 PR #1752 与 Jude 版**都没做**、而本仓必须补的部分。
4. 预期改动量：**约 1.5k 行（含测试）/ ~20 个文件 / 5–7 人日**；对比 #2186 全量复刻的 6.3k 行、9–13 人日。

| 阶段 | 内容 | 验收 | 人日 |
|---|---|---|---|
| S1 | ai 层：认证器、capabilities 扩展、Codex 分支请求体、SSE 兼容、models 解析 | JVM 单测 + 编译 | 1.5–2 |
| S2 | app 层：`OpenAICodexAuthService`（设备流/刷新/加密落盘）+ DI | JVM 单测 + 真机登录 | 1–1.5 |
| S3 | UI：认证方式切换、登录卡片、设备码对话框 | 真机走通全流程 | 1–1.5 |
| S4 | 安全收口：日志/备份/导入、错误文案、3 locale 字符串 | 单测 + 人工核对 | 0.5–1 |
| S5 | 真机联调：多轮、工具、刷新、杀进程、代理 | 真机清单全过 | 1–2 |

## 二、为什么不是 #2186 路线

| 维度 | #2186（上游已合并） | 本稿（#1752 血统） |
|---|---|---|
| 登录 | loopback PKCE：本地起 127.0.0.1:1455 服务器 + 前台服务 + CustomTabs | 设备码：浏览器输入一次性码，无本地服务器 |
| provider 模型 | 新增 `ProviderSetting.ChatGPT` / `Grok` 多态子类型 | 现有 `ProviderSetting.OpenAI` 加 2 字段 |
| 依赖 ai 层 | 需要 `StreamChunk`/`StreamChunkHandler`/`ResponsesEndpoint`/`ProviderQuota` | 用现有 `MessageChunk` + `ResponseAPI` |
| 新增模块 | `oauth` 模块（17 文件/1158 行） | 无 |
| 降级安装 | 旧版 sealed 解码直接抛异常（无法打开设置） | 旧版忽略未知字段，可用 |
| 落地量 | 70 文件 +4587/−586 | 23 文件 +1389/−94（上游 PR 数据） |
| 评估人日 | 9–13（ChatGPT-only） | 5–7 |

**但 #2186 仍有不可替代的价值**：它的 `CODEX_CAPABILITIES`（developer 角色、禁采样、加密推理、`prompt_cache_key`）、`parseCodexModels`、`parseCodexUsage` 是当前后端语义的权威参考，本稿直接借用（第六节）。设备码路线不能解决的是「额度展示」——`/wham/usage` 解析在 #2186 与 agent 系都有，本稿列为 P2。

## 三、协议规格（含实测）

### 3.1 2026-11-01 实测记录

| 请求 | 网络 | 结果 |
|---|---|---|
| `POST https://auth.openai.com/api/accounts/deviceauth/usercode`，body `{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}` | 本机直连（出境被判定为不支持地区） | `403 {"error":{"code":"unsupported_country_region_territory"}}`，连乱造路径同样 403（Cloudflare 边缘拦截） |
| 同一请求 | 本机 clash（`127.0.0.1:8888`，出口新加坡 `173.234.14.230`） | **`200`**：`{"device_auth_id":"deviceauth_…","user_code":"TJ8L-4ABRL","interval":"5","expires_at":"…"}` |
| `POST https://auth.openai.com/api/accounts/deviceauth/token`，body `{"device_auth_id":…,"user_code":…}` | 同上 | `403 {"error":{"code":"deviceauth_authorization_pending","message":"Device authorization is pending. Please try again."}}` |

结论：**端点对第三方公开 client 开放，pending 语义与 PR #1752/Jude 的判定逻辑一致**。登录只需在有 ChatGPT 账号、能访问 `auth.openai.com` 的环境完成（与 provider 请求同一套代理）。

### 3.2 端点表

| 用途 | 方法/URL | 请求 | 响应关键字段 |
|---|---|---|---|
| 申请设备码 | `POST https://auth.openai.com/api/accounts/deviceauth/usercode` | `{"client_id":"app_EMoamEEZ73f0CkXaXp7hrann"}` | `device_auth_id`、`user_code`、`interval`（秒，字符串或数字）、`expires_at` |
| 用户授权页 | `https://auth.openai.com/codex/device` | 浏览器输入 `user_code` | — |
| 轮询授权 | `POST https://auth.openai.com/api/accounts/deviceauth/token` | `{"device_auth_id","user_code"}` | 202/403 pending；成功后 `authorization_code`、`code_verifier` |
| 换取令牌 | `POST https://auth.openai.com/oauth/token` | form: `grant_type=authorization_code`、`code`、`redirect_uri=https://auth.openai.com/deviceauth/callback`、`client_id`、`code_verifier` | `access_token`/`refresh_token`/`id_token`/`expires_in`/`account_id` |
| 刷新 | `POST https://auth.openai.com/oauth/token` | `grant_type=refresh_token`、`refresh_token`、`client_id`（编码见 3.5） | 同上（可能不带 id_token/refresh_token，需沿用旧值） |

`client_id` 是 Codex CLI 的公开 client id（非机密），与上游 #2186 一致。

### 3.3 登录流程（App 侧状态机）

```
Idle → 申请码(200) → 展示码/复制/打开浏览器 → 轮询(pending…, ≤15min)
     → 拿到 authorization_code → 兑换令牌 → JWT 解析账号 → 加密写入 Settings → Success
任何一步失败 → Error(可读文案)；用户取消 → 关闭协程 + 服务端会话自然过期
```

- 轮询间隔取响应的 `interval`（默认 5s），总超时 15 分钟；`slow_down`/`authorization_pending` 都是继续轮询，不是错误。
- 设备码只展示一次、只在内存，不写日志、不进设置。

### 3.4 账号信息解析

优先级：令牌 JSON 的 `account_id` → `id_token`/`access_token` 的 `https://api.openai.com/auth` claim 里 `chatgpt_account_id` → 两者都没有则报错（该账号不可用）。
同时取 `email`（JWT `email`）、`chatgpt_plan_type`（展示 Plus/Pro）、`expires_in` 或 JWT `exp` 计算过期时间；刷新后若不返回 `id_token`/`refresh_token`，沿用旧值（与上游 `ChatGPTAuth.toCredential` 同口径）。

### 3.5 刷新编码（开放问题，S2 实测）

上游 #2186 的注释明确「ChatGPT 的刷新接口只接受 JSON 请求体」，而 PR #1752/Jude 用 form 也自测通过（2026-08 窗口）。本稿实现：**默认 JSON**（`{"client_id","grant_type":"refresh_token","refresh_token"}`），若返回 400/415 且错误体提示编码问题，则回退 form 重试一次；两条路径都写单测。上线前必须真机验证一次真实刷新（S5 清单第 4 项）。

### 3.6 请求侧要求（Codex 后端，取自 #2186 `CODEX_CAPABILITIES`，已由合并版验证）

| 项 | 要求 |
|---|---|
| baseUrl | `https://chatgpt.com/backend-api/codex`（订阅模式由代码常量决定，**不覆写** `ProviderSetting.baseUrl`，见 6.1） |
| 路径 | `POST /responses`（只支持流式；`generateText` 用流式结果聚合）；`GET /models?client_version=0.163.0` |
| 头 | `Authorization: Bearer <access_token>`、`ChatGPT-Account-ID`、`originator: codex_cli_rs`（上游 #2186 口径） |
| system 提示 | 以 `developer` 角色消息放 `input` 开头，**不使用 `instructions` 字段**（#2186 口径） |
| 采样参数 | 不发送 `temperature`/`top_p`/`max_output_tokens` |
| 推理 | `store=false`；推理块 `{"summary":"auto","effort":<level>}`（`AUTO` 时省略 effort）；`include:["reasoning.encrypted_content"]` 且**多轮必须回传**加密推理内容 |
| 缓存 | `prompt_cache_key`（v1 用稳定键，见 6.5） |
| 工具 | 扁平 tools 数组，函数工具带 `"strict": false` |

> 若真机出现 `400 Instructions are required`（poci 在 2026-06 遇到过），回退方案：订阅模式改发 `instructions`（取首条 system，缺省 "You are a helpful assistant."），这是 PR #1752/Jude 已实测可用的写法；两条写法后端在不同时期都接受过，S5 用真实后端定稿。

### 3.7 错误码映射

| 上游错误 | 处理 |
|---|---|
| `unsupported_country_region_territory`（直连必现） | 提示「当前网络无法访问 OpenAI 授权服务，请配置代理后重试」；请求走共享 client 的全局代理（本仓已有 `SettingsProxySelector`），不需要用户手填代理 |
| `deviceauth_authorization_pending` / `slow_down` | 继续轮询 |
| `expired_token` / `authorization_code` 过期 | 重新申请设备码（提示重新登录） |
| `invalid_grant`、`refresh_token_reused`/`expired`/`invalidated` | 清除本地凭据，标记需重新登录（对齐上游 `classifyRefreshTokenFailure`） |
| `401` 且错误体含 `token_expired` | 触发一次刷新后重试请求 |
| 其他 4xx/5xx | 保留 HTTP 码 + 截断后的服务端 message（≤500 字符，避免把 token 打进 UI/日志） |

## 四、本仓现状与设计约束

| 现状 | 对设计的影响 |
|---|---|
| ai 层用 `MessageChunk` / `Provider<ProviderSetting>`，无 `StreamChunk`、无 `ProviderQuota`、无 `ResponsesEndpoint` | Codex 分支必须写在现有 `ResponseAPI.buildRequestBody` 内，不能照搬 #2186 的 endpoint 抽象 |
| `ResponseAPI` 已有自定义 `ResponseProviderCapabilities(supportsReasoningSummary, supportEncryptedContent)` + `resolveResponseProviderCapabilities(host)` | 扩展 4 个字段（systemMessageRole/采样/alwaysEncrypted/effort），按「是否 Codex 端点」解析 |
| 9 处 `keyRoulette.next(...)` 手写鉴权（OpenAIProvider 5、ChatCompletionsAPI 2、ResponseAPI 2；全仓 13 处，其余属 Claude/Google） | 统一抽 `OpenAIRequestAuthenticator`，避免漏改 |
| `ResponseAPI` 流式直接用 `EventSources.createFactory(client.configureClientWithProxy(proxy))` | SSE Content-Type 用**专用 network interceptor** 修，不改 `ai/util/SSE.kt`（见 6.8） |
| `TextGenerationParams` 无 `sessionId` | v1 用稳定 `prompt_cache_key`；会话级 sessionId 列 P2 |
| app 共享 `OkHttpClient` 已装全局 `SettingsProxySelector` | 认证服务直接用共享 client，天然走代理 |
| `SecretCipher`（Keystore AES-GCM，`v1:` 前缀）已有 | 令牌加密落盘的现成方案 |
| `BackupArchiveEngine` 固定导出 `settings.json`（含全部 provider 设置） | 凭据必须是密文；恢复后解不开则引导重新登录 |
| `RequestLoggingInterceptor` 已用 `redacted()` 脱敏头 | 新 header 复核；禁止打印 token 请求/响应体 |
| Web 设置 DTO 是白名单（id/name/enabled/models） | web 层零改动 |
| 无 ShareSheet / provider 分享 | 少一个泄漏面；导入器构造新实例，未知字段自然丢弃 |
| `JsonInstant`：`ignoreUnknownKeys=true` + `coerceInputValues=true` | 新增可默认字段 → 降级安全 |

## 五、总体设计

```
ProviderConfigure (Compose)
   │ ① 点「登录」 ② 显示设备码 ③ 打开浏览器
   ▼
OpenAICodexAuthService (app/data/ai/openai)      ── OkHttp(共享 client，走全局代理)
   │ 设备流 / 刷新 / JWT 解析 / 错误映射
   │ ④ 写 Settings：codexAccount（access/refresh 用 SecretCipher 加密）
   ▼
Settings.providers[OpenAI].codexAccount  ── 备份导出的是密文
   │ ⑤ 生成请求时按需解密
   ▼
OpenAICodexTokenProvider (ai 接口，app 实现注入)
   ▼
OpenAIRequestAuthenticator (ai)
   ▼
OpenAIProvider / ChatCompletionsAPI / ResponseAPI  →  chatgpt.com/backend-api/codex
```

单向依赖：ai 模块只认 `OpenAICodexTokenProvider` 接口与明文 `CodexToken`，不依赖 app 的 Settings/Cipher——与现有 `AccountCredentialProvider` 方向一致。

## 六、ai 模块设计

### 6.1 `ProviderSetting.kt`

```kotlin
@Serializable
enum class OpenAIAuthType {
    @SerialName("api_key") API_KEY,
    @SerialName("chatgpt_subscription") CHATGPT_SUBSCRIPTION,
}

/** 只存账号元数据 + 密文；明文令牌由 app 层解密后经 OpenAICodexTokenProvider 提供。 */
@Serializable
data class CodexSubscriptionAccount(
    val accountId: String = "",
    val email: String? = null,
    val planType: String? = null,
    val expiresAt: Long = 0L,               // access token 过期时间（毫秒）
    val accessTokenEncrypted: String = "",  // SecretCipher 密文，v1: 前缀
    val refreshTokenEncrypted: String = "",
)

const val OPENAI_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
```

`ProviderSetting.OpenAI` 末尾追加：

```kotlin
var authType: OpenAIAuthType = OpenAIAuthType.API_KEY,
var codexAccount: CodexSubscriptionAccount? = null,
```

- 只加可默认字段，`copyProvider` 等抽象成员不动；`apiKeys`/`proxy`/`retryOption`/`reasoningOverride` 语义不变。
- **不覆写 `baseUrl`/`useResponseApi`**（PR #1752 的做法）：订阅模式在请求侧用常量拼 URL，切回 API Key 时配置原样恢复，避免「切一次把用户 baseUrl 冲掉」。

### 6.2 新增 `ai/.../provider/providers/openai/OpenAICodexTokenProvider.kt`

```kotlin
data class CodexToken(
    val accessToken: String,
    val accountId: String,
)

fun interface OpenAICodexTokenProvider {
    /** 返回可用令牌；内部负责过期前刷新。禁止返回空串。 */
    suspend fun getToken(providerSetting: ProviderSetting.OpenAI): CodexToken
}
```

### 6.3 新增 `OpenAIRequestAuthenticator.kt`

`internal class OpenAIRequestAuthenticator(keyRoulette, codexTokenProvider)`：

- `API_KEY`：`Authorization: Bearer <next key>`（沿用 `KeyRoulette` 顺序/多 key 行为，路径不变）。
- `CHATGPT_SUBSCRIPTION`：
  - 守卫：仅当请求域名为 `chatgpt.com` 且路径以 `/backend-api/codex` 开头时放行，否则抛「订阅凭据只允许发往官方 Codex 端点」（防止用户把 baseUrl 改成第三方后凭据外泄）；
  - 置 `Authorization` + `ChatGPT-Account-ID` + `originator: codex_cli_rs`（与上游 #2186 一致）。
- 统一入口替换 `OpenAIProvider`(5 处调用)、`ChatCompletionsAPI`(2)、`ResponseAPI`(2) 的鉴权段落。

### 6.4 `ResponseAPI.kt`：capabilities 扩展与 Codex 解析

```kotlin
internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    val systemMessageRole: String? = null,          // 非 null：system 走 input 的该角色
    val supportsSamplingParams: Boolean = true,
    val alwaysIncludeEncryptedContent: Boolean = false,
    val reasoningEffort: (ReasoningLevel, String) -> String? =
        { level, _ -> level.effort.takeIf { level != ReasoningLevel.AUTO } },
)

internal val CODEX_CAPABILITIES = ResponseProviderCapabilities(
    systemMessageRole = "developer",
    supportsSamplingParams = false,
    alwaysIncludeEncryptedContent = true,
)

internal fun resolveResponseProviderCapabilities(setting: ProviderSetting.OpenAI): ResponseProviderCapabilities =
    if (isCodexEndpoint(setting)) CODEX_CAPABILITIES else resolveResponseProviderCapabilities(host, path)
```

`isCodexEndpoint` 判定：`baseUrl` 的 host == `chatgpt.com` && path 以 `/backend-api/codex` 开头（订阅模式 + 用户手动配同域都算）。现有按 host 的解析保留给 `ark.cn-beijing…` 等分支。

> 注意本仓 `buildRequestBody` 目前把首条 system 写进 `instructions`、其余 system 降级为 user。Codex 分支需要走「developer 消息 + 后续 system 仍按本仓既有降级逻辑处理」：给 `buildMessages` 增加 `systemRole: String? = null` 参数，非 null 时先把首条 system 作为该角色 item 加在最前（其余逻辑不变），避免与现有首条跳过逻辑打架。

### 6.5 Codex 分支的请求体

在 `buildRequestBody` 开头按 `capabilities` 分支（对齐 #2186）：

- `supportsSamplingParams=false` → 不写 `temperature`/`top_p`/`max_output_tokens`；
- `systemMessageRole != null` → 不写 `instructions`，system 作为 developer 消息（见 6.4）；
- reasoning 模型：`{"summary":"auto","effort":…}`；`include=["reasoning.encrypted_content"]` 当 `supportEncryptedContent && (推理模型 || alwaysIncludeEncryptedContent)`；
- `prompt_cache_key`：v1 取 `params.customHeaders` 里名为 `session-id` 的值；没有则 `"codex-${setting.id}-${params.model.modelId}"` 截断 64 字符（Jude 同口径；会话级接入 P2）；
- 流式请求补 `Accept: text/event-stream`（#2186 同款，配合 6.8）。

### 6.6 `OpenAIProvider.kt`

- 构造加 `codexTokenProvider: OpenAICodexTokenProvider? = null`，透传给 `ChatCompletionsAPI`/`ResponseAPI`/`ProviderManager`；
- `listModels`：订阅模式改 GET `$OPENAI_CODEX_BASE_URL/models?client_version=0.163.0` + 认证头，解析 `models[]`：`visibility=="list"` 过滤（为空则全量）、`slug`→modelId、`display_name`、`input_modalities`、`supported_reasoning_levels` 非空→`ModelAbility.REASONING`、`context_window`；否则返回 `emptyList()`（不阻塞登录）；
- `getBalance`：订阅模式直接返回空串（`BalanceOption` 是给 API Key 余额用的，Codex 额度 P2）。

### 6.7 `ChatCompletionsAPI.kt`

订阅模式不支持 `/chat/completions`：入口处 `require(authType == API_KEY)` 或抛清晰错误（UI 已限制只能选 Responses）。流式/非流式统一走 `ResponseAPI`。

### 6.8 SSE Content-Type 兼容

官方 Codex 后端的流式响应可能不带 `Content-Type: text/event-stream`，而本仓 `ResponseAPI` 直接用 okhttp `EventSources`（`SSE.kt` 的 `SSEEventSource` 未被 Responses 使用），且 `isEventStream()` 对 null 直接 false。

方案：新增 `CodexSseContentTypeInterceptor`（`ai/.../providers/openai/`，约 40 行，照 `Inonvation/rikkahub` 的纯函数版本，便于单测）：仅当 `host==chatgpt.com && path.startsWith("/backend-api/codex")` 且响应无 Content-Type 时，包装 body 为 `text/event-stream`；其余请求原样放行。挂在**只给 Codex 用的 client 副本**上：

```kotlin
private fun codexClient(setting) =
    client.newBuilder().addNetworkInterceptor(CodexSseContentTypeInterceptor()).build()
        .configureClientWithProxy(setting.proxy)
```

不改全局 client、不改 `SSE.kt`，其他 provider 的严格校验不受影响。

### 6.9 `ProviderManager.kt`

构造签名改为 `ProviderManager(client, context, codexTokenProvider)`；DI 传入 app 层实现。已有 `getProviderByType` 的 `when` 不需要新分支（没有新类型）。

## 七、app 模块设计

### 7.1 新增 `app/.../data/ai/openai/OpenAICodexAuthService.kt`（约 350 行）

| 方法 | 行为 |
|---|---|
| `startLogin(providerId): Flow<LoginEvent>`/`suspend fun signIn(onDeviceCode)` | 申请码 → 回调展示 → 轮询（`interval`，15min 上限）→ 兑换 → 解析 → 加密写入 Settings（`authType=CHATGPT_SUBSCRIPTION` 同一事务） |
| `logout(providerId)` | 清 `codexAccount` + `authType=API_KEY`；不动 baseUrl |
| `getToken(setting)`（实现 `OpenAICodexTokenProvider`） | 内存缓存命中直接返回；过期前 5 分钟或收到 401 时刷新；per-provider `Mutex` 串行化，防并发刷新把 refresh token 轮换掉 |
| `refresh(account)` | JSON 优先，失败回退 form（3.5）；`invalid_grant` 类错误 → 清凭据并抛出「需重新登录」 |
| 内部 | `parseTokenResponse`/`decodeJwtPayload`/`resolveAccountId`/`mapError`；`SecretCipher.encrypt/decrypt`；等 |

- 单例（Koin `single`），持有 `SettingsStore`、共享 `OkHttpClient`、`JsonInstant`。
- 不使用前台服务、不注册 Activity：登录期间 Activity 在后台，协程挂在 `AppScope`（与 MCP OAuth 协调器同款生命周期）；用户离开页面可继续完成授权。
- 区域封锁/网络错误统一映射成 3.7 的文案。

### 7.2 DI（`DataSourceModule.kt`）

```
single { OpenAICodexAuthService(settingsStore = get(), client = get(), json = get()) }
single { ProviderManager(client = get(), context = get(), codexTokenProvider = get()) }
```

不新增依赖、不新增模块。

### 7.3 UI（`ProviderConfigure.kt`，679 行）

- 仅对 `ProviderSetting.OpenAI` 且 `baseUrl` host ∈ {`api.openai.com`, `chatgpt.com`} 显示「认证方式」分段按钮：`API Key` / `ChatGPT 订阅`（Jude 的 `supportsChatGPTSubscription()` 判定）；
- 订阅模式：隐藏 API Key/BaseUrl/余额相关输入，显示账号卡片（email/planType/过期时间）、「登录」「退出」；
- 登录态：设备码对话框 —— 显示 `user_code`（大字+复制）、自动复制、按钮「打开浏览器」复用本仓 `launchOAuthAuthorization(context, url)`（`McpOAuthCallback.kt` 已有），下方显示轮询状态/错误；
- 切换回 API Key 时保留原 baseUrl/apiKey（6.1 决定）；
- 教程弹窗（Jude 的 `CodexLoginTutorialDialog`）**v1 不做**，用 3 行文字说明代替；P2 再补。
- `SettingProviderDetailPage` 无需改动（认证段在 provider 配置页内部）。

### 7.4 字符串

`values/strings.xml`、`values-zh/`、`values-zh-rTW/` 各约 20 个 key（前缀 `setting_provider_page_`），走 `locale-tui-localization` skill。文案包含区域封锁提示、设备码提示、刷新失败提示、重新登录提示。

### 7.5 Web / 导入导出

- `WebSettingsDto` 白名单不含凭据，**零改动**；若后续要在 web 显示认证方式，只加 `authType` 一个枚举字段。
- 导入器（`SettingsJsonMigrator` 等）构造新 `ProviderSetting.OpenAI` 实例，`codexAccount` 默认 null → 导入的配置**天然不带他人凭据**；同时确认导入路径不会把未知字段透传到 settings（现有实现是按类字段赋值，天然安全）。

### 7.6 订阅对话的提供商锁定（S3 补丁，2026-11-01）

- **规则**：对话历史里已有订阅模型生成的消息 ⇒ 这条对话只能继续用**同一提供商**的模型（同 provider 换模型照旧）；模型选择器只列该提供商、顶部一行说明，发送入口再兜底拦截（助手模型在别处被改成别家时提示切回）。
- **判定用历史（`UIMessage.modelId`）而不是"当前选中模型"**：模型选择是助手级的（`assistant.chatModelId`），按当前模型判定会把这个助手所有历史对话一起锁死，连切回去的路都没有（真机复现过）。
- 代码：`app/.../data/ai/openai/SubscriptionProviderLock.kt`（`resolveSubscriptionProviderLock` / `allows` / `allowsSendingWith`，纯函数 + JVM 单测 12 例）；`ChatPage` 解析锁 → `ChatInput` 过滤列表、显示说明、拦截发送；`ModelList` 增加可选 `notice` 槽位，收藏区同样按传入 providers 过滤（否则锁定后收藏区仍能点别家模型）。

### 7.7 调试入口：强制刷新令牌（仅 debug 包）

- 动机：access token 1h 有效，靠等自然过期验证刷新链路不现实；改设备时间又会污染其它逻辑。
- 实现：`OpenAICodexAuthService.forceRefresh(providerId)` 忽略本地有效期与缓存，直接走一次 refresh token 换新并落盘，返回 `CodexForceRefreshResult(encoding, account)`，其中 `encoding` 是服务端**实际接受的编码**（`JSON` / `FORM`）；`refreshTokens` 改为返回 `CodexRefreshPayload(tokens, encoding)` 并在两条分支各打一行日志（`JSON accepted/rejected`、`FORM accepted`）。
- UI：设置 → 提供商 → OpenAI → 订阅态账号卡片下方，`BuildConfig.DEBUG` 才渲染「强制刷新令牌（调试）」按钮，结果以 toast 显示编码。

## 八、安全设计

| 面 | 设计 |
|---|---|
| 落盘 | access/refresh token 用 `SecretCipher` 加密（`v1:` 前缀）存 `codexAccount`；`accountId/email/planType/expiresAt` 明文（用于展示）。明文令牌只在内存与请求头中出现 |
| 换机/重装 | Keystore 密钥不可导出 → 备份恢复后解密失败：捕获后清空 `codexAccount`，UI 提示「订阅登录已失效，请重新登录」（与 SSH 口令同策略） |
| 备份 | `BackupArchiveEngine` 导出 settings.json 时凭据已是密文；为减体积可选择导出时把 `codexAccount.accessTokenEncrypted` 置空、保留 refresh（P2 可选项，v1 原样导出） |
| 日志 | `RequestLoggingInterceptor` 已 `redacted()` 头；`HttpLoggingInterceptor` 只打 HEADERS 且 debug-only；新增 header 复核；**禁止**打印 token 请求/响应体与设备码 |
| Web DTO | 白名单，无凭据 |
| 第三方端点 | 认证器守卫：订阅凭据只发往 `chatgpt.com/backend-api/codex`（6.3），防改 baseUrl 外泄 |
| 降级 | 旧版忽略新字段；旧版会用 `apiKey` 模式请求 codex baseUrl（若用户切过 baseUrl）——本稿不覆写 baseUrl，降级后行为与升级前一致 |
| 设备码 | 一次性、内存态、不落盘、不写日志 |

## 九、测试计划

JVM 单测（`:ai:testDebugUnitTest` / `:app:testDebugUnitTest`）：

1. usercode/token/refresh 响应解析（含 interval 字符串、"pending"、缺字段、JWT account id 三级回退）；
2. `OpenAIRequestAuthenticator`：API Key / 订阅 / 域名守卫 / 401 后刷新；
3. `buildRequestBody` Codex 快照：无采样参数、developer 消息、reasoning+encrypted、prompt_cache_key；
4. `parseCodexModels`：visibility 过滤、slug/abilities/context；
5. `CodexSseContentTypeInterceptor`：有无 Content-Type、非 Codex 域名不动；
6. 刷新失败分类（invalid_grant 清凭据）与并发刷新只发一次。

真机清单（Android 15 真机 + 代理）：直连 403 → 文案；配置代理后登录成功；多轮对话（验证加密推理回传）；工具调用；杀进程重启后免登录；令牌过期自动刷新；退出后回到 API Key；备份恢复后提示重新登录；日志中无 token。

构建：`:app:assembleDebug` + 两个模块单测全绿。

## 十、分阶段落地

见第一节表格。S1/S2 完成即可用「临时调试入口」真机打通协议，S3 再做正式 UI；S4 安全收口不可与 S5 合并（避免最后才发现备份/日志泄漏）。

## 十一、明确不做 / 后续

- ❌ 多账号轮询（agent 系有，P2；需要账号列表 + 调度，且与「单一凭证字段」设计冲突，届时再评估）
- ❌ 额度/用量展示（`GET /wham/usage`，`parseCodexUsage` 可从 #2186 直接借；P2，约 +0.5 人日）
- ❌ Grok / Kimi / Gemini OAuth（独立议题）
- ❌ `oauth` 模块、前台服务、回调服务器
- ❌ Codex CLI `auth.json` 导入（`Inonvation` 有，P2 兜底）
- ⏳ 会话级 `sessionId`（`TextGenerationParams` 加字段 + ChatRequestFactory 透传，P2）
- ⏳ 登录教程弹窗（Jude 的 330 行带图；P2）

## 十二、风险与开放问题

1. **刷新编码**：JSON vs form 未在 2026-11 之后实测（PR #1752 是 8 月验证的），S2 必须打真实刷新，两条路径都写测试（3.5）。
2. **设备端点长期可用性**：目前 200（实测）+ Codex CLI main 仍在用，但属第三方 client 借用官方 id，存在被限流/收紧的可能；兜底方案是 P2 的 auth.json 导入，或回落到 #2186 的 loopback（届时只需替换登录环节）。
3. **instructions vs developer 角色**：两种写法后端不同时期都接受过；本稿按 #2186 走 developer，S5 若遇 400 再切 instructions（3.6）。
4. **模型清单随 `client_version` 变**：0.163.0 是 #2186 落点，后续后端升级需跟进（模型列表是服务端过滤，不影响登录）。
5. **prompt cache 未接会话 id**：v1 用稳定键，缓存命中率略低于上游；P2 接 `sessionId`。
6. **区域封锁**：无代理用户登录必失败，需要清晰文案（不能只说 "network error"）；且订阅请求本身也需代理，与普通 API Key 用户的使用前提一致。
7. **Codex 后端请求约束可能再变**（加密推理必须回传、`store=false`、只支持流式）——请求体集中在 6.5 一处，后续改动成本低。

## 十三、参考

- 上游 Draft PR：https://github.com/rikkahub/rikkahub/pull/1752 （+1389/−94，23 文件，真机验证，关闭原因是「不要提交功能性 PR」）
- 需求 issue：https://github.com/rikkahub/rikkahub/issues/1753
- 设备码血统参照：https://github.com/Lin-chpin/rikkahub-Jude （96★，经原作者同意移植）
- SSE/导入变体：https://github.com/Inonvation/rikkahub
- agent 系（多账号/Keystore/额度）：https://github.com/ExTV/rikkahub-agent 、`AAAelina/rikkahub`
- 官方协议源：`openai/codex` → `codex-rs/login/src/device_code_auth.rs`（`deviceauth/usercode`、`deviceauth/token`、15 分钟超时）
- 上游已合并路线（请求体语义参考）：PR https://github.com/rikkahub/rikkahub/pull/2186 ，commit `318cffef6`

## 十四、实施记录与真机验证（2026-11-01）

### 已落地（提交）

| 阶段 | 提交 | 内容 |
| --- | --- | --- |
| S1 | `174946239` | ai 层：请求体语义（developer 角色 / 加密推理 / `prompt_cache_key`）、Codex 鉴权、`/models` 解析、SSE Content-Type 兜底；单测 18 例 |
| S2 | `3f4c8252d` | app 层 `OpenAICodexAuthService`：设备码申请/轮询/兑换、JSON 优先 + form 回退刷新、`SecretCipher` 加密落盘、per-provider Mutex、失效清凭据；DI 接入；单测 9 例（纯解析/错误分类） |
| S3 | `397593ee2` | 设置页 UI：认证方式切换、账号/套餐卡片、设备码对话框（自动复制 + 打开浏览器）、错误按 `CodexAuthErrorCode` 本地化；23 条字符串（en/zh/zh-rTW） |
| S4 | `e3c33743c` | 日志脱敏：`SENSITIVE_HEADERS` + debug `HttpLoggingInterceptor` 增加 `chatgpt-account-id` |
| 修复 | `5d343e2b6` | 真机启动崩溃：Koin 裸 `get()` 按接口类型解析不到实现，改 `get<OpenAICodexAuthService>()`（编译期无感知，仅运行期暴露） |

### 真机验证（PJX110 / Android 15，`app.androidrik.rikkahub.debug`）

环境：App 内全局代理指向 host clash（`adb reverse tcp:8888`），验证后已恢复为「无」并复位系统 `http_proxy`。

- 设备码申请 200 → 自动打开 `auth.openai.com/codex/device` → 轮询 403 `deviceauth_authorization_pending`（约 5s 间隔）符合预期
- 完成授权后：`deviceauth/token` 200 → `oauth/token` 200 → 设置页显示账号 `jiushixiuqi@gmail.com`、套餐 `plus`；登录后自动拉取 8 个 Codex 模型（`GPT-6.1-Sol` 等，500K 上下文）
- 落盘校验（`settings.preferences_pb`）：含 `chatgpt_subscription`、`accessTokenEncrypted`（`v1:` 密文）、账号邮箱；**无明文 JWT（`eyJ` 零命中）**
- 对话：`POST https://chatgpt.com/backend-api/codex/responses` 200；首轮 "OK"，次轮 "SECOND"（多轮 + 历史加密推理回传可用），token 统计/速度正常
- 杀进程重启后继续对话仍 200（解密 + `ChatGPT-Account-ID` 链路可用，无需重新登录）
- 工具调用、退出登录：2026-11-01 用户真机验证通过（退出后凭据清除、工具往返正常）
- 设备级代理：App 内代理为「无」时会回落系统代理选择器（`SettingsProxySelector` 的 system 兜底），设备配好代理后 Codex 请求 200
- **刷新链路**：2026-11-01 用调试入口「强制刷新令牌」验证——`POST https://auth.openai.com/oauth/token` **200，服务端接受 JSON 编码**（日志 `refreshTokens: JSON accepted`，未触发 form 回退）；刷新后继续对话 `/responses` 200，说明 refresh token 轮换后的新 access token 落盘/解密正确，无需重新登录
- **release 构建（R8 + lintVital）**：2026-11-01 `:app:assembleRelease` 通过（`neomix.jks` 签名，arm64/universal/x86_64 三个包）；把 arm64 包装到 PJX110 复验——设备码登录成功 → `/models` 解析出 Codex 模型列表 → 选 `GPT-6.1-Sol` 发消息收到回复（`/responses` SSE 流式可用），确认 R8 未裁掉 kotlinx.serialization/Tink/SSE 相关路径；debug 专属的「强制刷新令牌」按钮在 release 包中正确隐藏

### 尚未覆盖（后续补充）

- **form 回退分支**：只有 JSON 被拒（400/415）时才会走到，正常账号触发不到；代码按上游 PR #1752 形态实现，保持为安全网
- 备份恢复跨设备后的重新登录引导（`INVALID_GRANT` → 提示重新登录）
- 长对话触发压缩后，Codex 加密推理块被清理/回传的行为
- 区域封锁（`REGION_BLOCKED`）文案在无代理时的真机表现
- 订阅模式下图片/嵌入输入的拦截提示
- `client_version` 漂移与后端约束变化（见十二）
