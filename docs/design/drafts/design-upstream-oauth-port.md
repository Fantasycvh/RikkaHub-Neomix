# 上游 OAuth 订阅登录全量移植评估（路线 A）

> 状态：评估稿（2026-11-01）
> 上游快照：`upstream/master` = `5ad19405c`（2026-11-01，`git fetch` 于评估当日）
> 本仓基线：`2c9806427`（与上游的 merge-base），本仓已落地设备码方案（见 `design-codex-subscription-login.md`）
> 目标：把本仓「设备码登录 + 复用 `ProviderSetting.OpenAI`」替换为上游 #2186 的架构（`oauth` 模块 loopback PKCE + `ChatGPT`/`Grok` 账号态 Provider + `ProviderAccountManager` + `ProviderQuota` + 新 SSE 解码层）

---

## 一、结论（先行）

1. **难度：高。** 单人 **21–28 人日**，其中 **7–9 人日**花在与 oauth 无关的前置条件上——上游 #2186 建立在 ai 流式层重构 #1648（85 文件 +7981/−1016）之上，而本仓完全没有这层。
2. **它不是「移植一个登录模块」**，而是同时动四块：`oauth` 模块、`ProviderSetting` sealed 层级重构（本仓 295 处引用 / 71 文件）、账号与额度子系统、SSE 解码层。
3. **最大风险不是写代码，而是设置兼容**：`ProviderSetting` 从扁平三类型改成 `ApiKey` / `Account` 两级多态，设置 JSON 形状变化 → 必须做 V4/V5 迁移，且**旧版本无法读取新设置**（我们当初选设备码方案就是为了避开这个坑）。
4. **收益**：额度展示、Grok 账号态、多账号抽象、后续可持续同步上游 oauth。**不具备的收益**：现有设备码方案已经能完成登录、多轮、工具、刷新、release 运行——移植换的是架构与增量能力，不是「能用/不能用」。
5. **建议**：按 P0–P5 六阶段推进，每阶段独立可验收/可回滚；**P0 已执行**（见第十节）：Grok 设备码端点实测可用，#1648 试合并的冲突面已量化，可以投入 P1。

---

## 二、上游快照与依赖拓扑

| 项 | 值 |
| --- | --- |
| 上游 HEAD | `5ad19405c feat(model): 模型选择面板支持多关键词搜索`（2026-11-01） |
| 本仓 merge-base | `2c9806427`（`Release WebView resources on disposal`） |
| 上游自基线以来提交数 | 405 |
| 本仓自基线以来提交数 | 1493（含包名 `me.rerere.*` → `app.androidrik.*` 改名与大量自定义功能） |

关键提交链（**顺序即依赖**）：

| 提交 | 日期 | 规模 | 作用 |
| --- | --- | --- | --- |
| `4b9ff1a99` #1648 | 2026-08-12 | 85 文件 **+7981/−1016** | ai 模块流式层重构：`StreamChunk`/`StreamChunkHandler`/`StreamChunkDecoder` 取代本仓现用的 `MessageChunk` + `EventSources` |
| `b6df5f044` | 2026-08-28 | 抽包 + Manifest 调整 | 把 OAuth 从 app 抽成 `oauth` 模块（loopback 回调服务器、前台服务、CustomTabs 启动器），同时改动 `app/src/main/AndroidManifest.xml` |
| `318cffef6` #2186 | 2026-10-19 | 70 文件 **+4587/−586**（产品 3016 / 测试 1571） | ChatGPT + Grok 账号态登录、`ProviderAccountManager`、`ProviderQuota`/`QuotaQueryRunner`、额度 UI、V5 设置迁移、7 语言字符串 |

依赖关系：

```
#1648 流式重构（本仓缺失）
   └── #2186 订阅登录（需要 StreamChunk / ResponseApiStreamDecoder）
          ├── oauth 模块（b6df5f044 抽包而来，本仓缺失）
          ├── ProviderSetting 层级重构（ApiKey / Account 两级多态）
          ├── 账号层：ProviderAccountManager(299) / ChatGPTAuth(96) / GrokAuth(55) / Jwt(30)
          └── 额度层：ProviderQuota(61) / QuotaQueryRunner(55) / ProviderQuotaText(153) / QuotaQueryOption(183)
```

---

## 三、上游组件清单（按本次移植范围）

### 3.1 `oauth` 模块（17 文件）

| 文件 | 行数 |
| --- | --- |
| `OAuthLoopbackCallbackServer.kt` | 453 |
| `OAuthHttpClient.kt` | 329 |
| `OAuthCallbackForegroundService.kt` | 187 |
| `OAuthAuthorizationLauncher.kt` | 21 |
| 测试（`OAuthHttpClientTest` / `OAuthLoopbackCallbackServerTest`） | 312 |
| 资源 + Manifest + build.gradle | — |
| 合计 | 主代码 990 / 测试 312 |

注意：`#2186` 只向该模块加了 343 行，模块主体来自 `b6df5f044` 的抽包重构；移植时**两者都要**。

### 3.2 provider 类型体系（`ProviderSetting.kt` 重构）

上游新结构（与本仓差异最大的一处）：

```kotlin
sealed class ProviderSetting {
    sealed class ApiKey : ProviderSetting() { data class OpenAI(...) / Google(...) / Claude(...) }
    sealed class Account : ProviderSetting() { data class ChatGPT(...) / Grok(...) }
}

data class OAuthCredential(          // 账号凭据（明文，落盘由 app 层加密）
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val accountId: String? = null,   // ChatGPT 请求要带
    val email: String? = null,
    val plan: String? = null,
)
```

`Account` 子类统一带 `credential: OAuthCredential?`，序列化名 `chatgpt` / `grok`；`ProviderSetting` 新增 `quotaQuery: QuotaQuery`。

### 3.3 账号与额度层

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `ai/provider/AccountCredentialProvider.kt` | 21 | ai 层接口（本仓已有同名概念 `OpenAICodexTokenProvider`） |
| `ai/provider/providers/AccountAuth.kt` | 63 | 账号态 provider 公共基类 |
| `ai/provider/ProviderQuota.kt` | 61 | 额度数据模型 |
| `ai/provider/QuotaQueryRunner.kt` | 55 | 额度查询调度 |
| `app/data/ai/account/ProviderAccountManager.kt` | 299 | 登录 / 刷新 / 凭据更新 / 额度缓存 |
| `app/data/ai/account/ChatGPTAuth.kt` | 96 | ChatGPT(ChatGPT) 授权流程 |
| `app/data/ai/account/GrokAuth.kt` | 55 | Grok 授权流程 |
| `app/data/ai/account/Jwt.kt` | 30 | JWT 解析（本仓已有 `decodeJwtPayload`） |
| `app/ui/components/ai/ProviderQuotaText.kt` | 153 | 额度展示 |
| `app/ui/pages/setting/components/QuotaQueryOption.kt` | 183 | 额度设置项 |

### 3.4 provider 实现与流式解码

| 文件 | 行数 | 说明 |
| --- | --- | --- |
| `ai/provider/providers/chatgpt/ChatGPTProvider.kt` | 189 | Codex 后端 provider |
| `ai/provider/providers/grok/GrokProvider.kt` | 247 | Grok 后端 provider |
| `ai/provider/providers/openai/ResponseApiStreamDecoder.kt` | 415 | 上游拆出的 Responses 解码器（本仓无此文件） |
| `common/http/sse/ServerSentEventReader.kt` | 184 | 新 SSE 读取器（替换本仓 `RealEventSource`） |
| `ai/ui/StreamChunk.kt` / `StreamChunkHandler.kt` / `stream/StreamChunkDecoder.kt` | 164 / 479 / 21 | #1648 的流式基础设施 |

---

## 四、本仓现状与差距

### 4.1 本仓已有

| 项 | 现状 |
| --- | --- |
| 订阅登录 | 设备码 + `OpenAICodexAuthService`（603 行）+ `SubscriptionProviderLock`（80 行），整批 27 文件 **+2370/−111**，已真机/release 验证 |
| 流式层 | `MessageChunk` + `ai/util/SSE.kt` + `EventSources`（与上游 #1648 前同源） |
| 凭据 | `CodexSubscriptionAccount`（`SecretCipher` 密文，`v1:` 前缀）挂在 `ProviderSetting.OpenAI.codexAccount` |
| 设置迁移 | 仅 V1–V3 |
| provider 扩展 | `ProviderProxy`（4 种）、`BalanceOption`（**将由上游 `QuotaQuery` 替换**，见决策 4）、`RetrySetting`、`ReasoningOverride`、`apiKeys: List<ApiKeyEntry>`（多 key 轮询）、`ProviderType` |
| 订阅相关自研 | 对话级「提供商锁定」（`SubscriptionProviderLock`）、debug 强制刷新入口、Codex SSE Content-Type 兼容、加密推理 metadata 通路 |

### 4.2 差距与迁移动作

| 维度 | 上游 | 本仓 | 移植动作 |
| --- | --- | --- | --- |
| 登录方式 | loopback PKCE + 前台服务 + 深链 | 设备码（无本地服务器） | 新增 `oauth` 模块并替换登录入口；设备码可留兜底 |
| provider 类型 | `ApiKey` / `Account` 两级，`ChatGPT`/`Grok` | 扁平 `OpenAI`/`Google`/`Claude` + `authType` 枚举 | **重构 sealed 层级**，本仓 295 处引用 / 71 文件随之改动 |
| 凭据载体 | `OAuthCredential`（明文模型，app 层加密） | `CodexSubscriptionAccount`（自带密文字段） | 解密迁移 + 重新加密落位 |
| 凭据作用域 | `ProviderAccountManager` 集中管理 | `OpenAICodexAuthService` 单 provider | 抽/换账号管理层 |
| 额度 | `ProviderQuota` + 查询调度 + UI | 无 | 新增；`/wham/usage` 语义可参考上游 |
| 流式 | `StreamChunk` 体系 | `MessageChunk` | 前置移植 #1648 |
| 设置版本 | V1–V5 | V1–V3 | 移植 V4/V5；本仓自有迁移链需重排 |
| 多语言 | 7 语言 × ~24 key | en/zh/zh-rTW | 按本仓支持范围补齐（其他语言可缺省回落） |
| Web / 导入导出 | 白名单 DTO 不变 | 同 | 核对 `WebSettingsDto` 与导入器，确保未知字段丢弃 |

### 4.3 冲突热点（本仓已深度改写的同名文件）

| 文件 | 本仓行数 | 上游行数 | 备注 |
| --- | --- | --- | --- |
| `ai/.../provider/ProviderSetting.kt` | 537 | 333 | 本仓含多 key、代理、重试、推理覆盖 |
| `ai/.../providers/openai/ResponseAPI.kt` | 1183 | 802 | 本仓含 Codex 分支与加密推理 |
| `ai/.../providers/openai/ChatCompletionsAPI.kt` | 1043 | 822 | 多 key 轮询 |
| `app/.../ui/pages/setting/components/ProviderConfigure.kt` | 993 | 663 | 本仓含订阅 UI + 设备码对话框 |
| `app/.../di/DataSourceModule.kt` | 458 | 319 | DI 图需并入账号管理 |
| `ai/.../providers/openai/OpenAIProvider.kt` | 568 | 413 | 鉴权器已抽出，需对齐 |

本仓对 `ProviderSetting.{OpenAI,Google,Claude}` 的引用：**295 处 / 71 文件**（OpenAI 205/65、Google 45/16、Claude 48/18）——这是 `ProviderSetting` 层级重构的真实修改面。

---

## 五、目标架构（移植后）

```
ProviderConfigure / ProviderDetail (Compose)
   │ 点「登录」
   ▼
oauth 模块：OAuthAuthorizationLauncher → CustomTabs → OAuthLoopbackCallbackServer(127.0.0.1)
   │                                        └─ OAuthCallbackForegroundService（前台服务 + 通知）
   ▼
ProviderAccountManager (app/data/ai/account)
   │ ChatGPTAuth / GrokAuth / Jwt
   │ 登录、刷新、额度查询（QuotaQueryRunner）
   ▼
Settings.providers: ProviderSetting.Account.ChatGPT(credential = OAuthCredential)
   │ （落盘前仍由本仓 SecretCipher 加密，保持备份/日志边界不变）
   ▼
AccountCredentialProvider (ai) → ChatGPTProvider / GrokProvider
   ▼
ResponseApiStreamDecoder + ServerSentEventReader（#1648/#2186 新流式层）
   ▼
chatgpt.com/backend-api/codex、grok 后端
```

本仓自定义字段的落位（必须在新层级上重建，否则功能回退）：

| 本仓字段 | 落位 |
| --- | --- |
| `ProviderProxy`（全局/无/HTTP/SOCKS5） | 基类 `ProviderSetting` 保留，`Account` 子类同样带 |
| `RetrySetting` | 同上 |
| `ReasoningOverride` | 同上 |
| `BalanceOption` / `ProviderType` | **已拍板：删除 `BalanceOption`，改用上游 `QuotaQuery`**（决策 4）；`ProviderType` 保留在本仓 `ProviderSetting` 外层概念里 |
| `apiKeys: List<ApiKeyEntry>`（多 key） | 仅 `ApiKey.OpenAI` 需要，`Account` 不需要 |
| `SubscriptionProviderLock`（对话锁定） | 改为匹配 `Account.ChatGPT` provider id，逻辑不变 |
| debug 强制刷新入口 | 改为调用 `ProviderAccountManager` 的刷新 |

---

## 六、分阶段落地

| 阶段 | 内容 | 关键交付 | 验收 | 人日 |
| --- | --- | --- | --- | --- |
| **P0** 探测 | 用 `git worktree`/临时分支试合并 `#1648`，统计冲突文件与不可自动合并块；确认 fork 自定义字段清单；Grok 端点 curl 实测 | 冲突清单 + 端点实测记录 | 冲突面与预估一致；端点可用性有结论 | 0.5–1 |
| **P1** 流式重构 | 移植 `#1648`：新增 `StreamChunk`/`StreamChunkHandler`/`StreamChunkDecoder`/`SseEvent` + 三个 `*StreamDecoder`；改造本仓全部 provider（OpenAI/ResponseAPI/ChatCompletions/Claude/Google/vertex）**以及 app 侧消费端**（`GenerationHandler` 1993 行、`ChatService` 3405 行、`ChatMessage`/`ChatMessageCot`/`Export` 等 8 个文件）；**行为不变** | 流式层替换 PR | 全 provider 单测绿 + 至少 3 家真机对话正常 + 本仓 codex 分支不回归 | 7–9 |
| **P2** 类型体系 + 余额替换 | `ProviderSetting` 拆 `ApiKey`/`Account`，新增 `OAuthCredential`/`QuotaQuery`；295 处引用改写；V4/V5 迁移 + 本仓迁移链重排；**删除本仓 `BalanceOption`/`getBalance`/`ProviderBalanceText`（11 文件 75 处引用）并把存量余额配置迁移成 `QuotaQuery`**；导入器/Web DTO/分享面板核对 | 设置模型重构 PR | 旧设置可迁移（含余额配置）、导入导出正常、降级策略明确 | 4–5.5 |
| **P3** 账号层 | `oauth` 模块（含前台服务/深链/Manifest）+ `ProviderAccountManager` + `ChatGPTAuth`/`GrokAuth`/`Jwt`；`OpenAICodexAuthService` 降级为**登录兜底**（只取 token，凭据/刷新统一走 Manager） | 登录/刷新/额度 PR | 主路径 + 兜底路径登录/刷新/退出/杀进程重启真机通过 | 3.5–5 |
| **P4** provider + UI | `ChatGPTProvider`/`GrokProvider`、额度 UI（`ProviderQuotaText`/`QuotaQueryOption` 替换 `ProviderBalanceText`/`BalanceOption`）、`ProviderConfigure` 拆分、订阅对话锁定适配、兜底登录入口、多语言、debug 入口迁移 | provider 与 UI PR | 真机：登录→模型列表→对话→用量窗口/余额显示；Grok 登录+对话 | 3–4.5 |
| **P5** 回归收尾 | 全 provider 回归矩阵（含主路径 + 设备码兜底路径）、备份恢复重登、降级安装策略、日志脱敏复核、**评估是否在下一个版本移除兜底**、文档/changelog | 回归报告 | 第八节清单全过 | 2.5–3.5 |
| 合计 | | | | **21–28** |

阶段间依赖严格：P1 → P2 → P3 → P4 → P5；P0 可与任意阶段并行但不能跳过。

---

## 七、风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 设置层级重构不可逆 | 降级安装旧版无法解析新 `provider` JSON；按本仓评估推演，旧版 sealed 解码会直接抛异常（连设置页都打不开） | **已拍板（2026-11-01）：接受「旧版不可用」**，发布说明中明确「本版起 provider 设置结构升级，降级安装需清除应用数据」；不做兼容读取层。同时迁移前自动备份一次设置（`BackupArchiveEngine` 已有能力）  |
| 聊天主链路全量回归（#1648） | 所有 provider 的流式/工具调用/推理可能回归 | P1 单独 PR、行为不变；按 provider 逐个真机验证；保留旧解码器开关一个版本 |
| 295 处引用改写遗漏 | 编译能过但语义错（如按 provider 类型分支漏了 `ChatGPT`） | 编译器驱动 + 全仓 `grep ProviderSetting.` 复核 + 真机遍历设置页 |
| 凭据迁移 | `codexAccount` 密文 → `OAuthCredential` 迁移失败 → 用户需重登 | 解密失败即走重登引导（现有 `INVALID_GRANT` 路径复用） |
| 余额配置迁移（决策 4） | `BalanceOption` 的多源/表达式无法 1:1 映射到 `QuotaQuery` → 部分用户余额查询能力下降或显示为空 | 迁移器按待决问题 1 的方案处理；迁移前自动备份；发布说明列出「余额查询改为额度查询」+ 需要重配的提示 |
| 上游持续漂移 | 移植完成即落后（基线以来 405 提交） | 锁定移植目标 commit；完成后按季度同步；不追求与 HEAD 对齐 |
| ~~Grok 端点未证~~ | **P0 已实测可用**（见第十节），form 编码 + RFC 8628 pending 语义均符合预期 | 风险关闭；实现时注意 device/code 必须 `application/x-www-form-urlencoded`（JSON 返回 415） |
| 双轨期复杂度 | 设备码与新账号层并存，状态源两份 | 明确弃用时间点；`OpenAICodexAuthService` 只保留「读旧数据→迁移→删除」 |
| Web/导入导出泄漏面 | 凭据经 web 或导入导出外泄 | 沿用白名单 DTO；新增字段默认不导出；日志脱敏复核 `chatgpt-account-id` 已做 |

---

## 八、验收清单（功能矩阵）

| 项 | 验收方式 |
| --- | --- |
| loopback PKCE 登录 / 设备码兜底 | 真机：授权后回到 app 自动完成 |
| token 刷新 | 强制过期或 debug 入口，JSON/form 编码记录 |
| 额度展示（账号） | 真机：`/wham/usage` 双窗口（5h/周）正确显示，`limitReached` 状态正确 |
| 余额展示（API Key） | 真机：迁移后的 `QuotaQuery` 配置能查出余额并显示；旧多源配置按迁移策略行为符合预期 |
| 登录兜底 | 真机：主路径（loopback）登录失败时，设备码入口可用 |
| 模型列表 | 登录后自动拉取；手动刷新 |
| 多轮 + 加密推理 | 连续两轮以上、历史推理回传 |
| 工具调用 | 本地工具往返 |
| Grok | 已实测端点（第十节），验收：登录 + 对话 |
| 设置迁移 | 旧版设置（V3）→ 新版启动，provider 完整 |
| 导入导出 / 备份恢复 | 导入后无凭据；恢复后引导重登 |
| 降级策略 | 旧版安装包打开新数据的行为符合发布说明 |
| 日志脱敏 | 抓包/日志无明文 token |
| 全 provider 回归 | OpenAI(API Key/Chat Completions/Responses)、Claude、Google、vertex 各一次对话 |

---

## 九、决策记录与待决问题

### 已拍板（2026-11-01）

| # | 问题 | 决定 |
| --- | --- | --- |
| 1 | Grok 是否同批 | **先 P0 实测端点再定** → 实测结论：可用（第十节），按 P4 一并实现（增量 2.5–3.5 人日） |
| 2 | 设备码是否留兜底 | **保留一版（P5 再评估是否移除）**：`OpenAICodexAuthService` 降级为登录兜底，只负责取 token，凭据/刷新/额度统一由 `ProviderAccountManager` 持有；UI 在主登录失败时提供「改用设备码」入口。增量 P3/P4/P5 合计 +1.5–2.5 人日 |
| 3 | 降级策略 | **旧版不可用 + 发布说明**；迁移前自动备份设置，不做兼容读取层 |
| 4 | 额度 UI 与 `BalanceOption` | **选 B：上游额度体系替换本仓余额**。删除本仓 `BalanceOption`/`BalanceSource`/`Provider.getBalance`/`ProviderBalanceText`/`BalanceOption.kt`（11 文件 75 处引用），统一用 `ProviderQuota`（账号=用量窗口，API Key=`QuotaQuery` 余额）；存量 `balanceOption` 配置迁移为 `QuotaQuery`。P2/P4 合计 +1–2 人日 |
| 4a | 多源/表达式余额配置迁移 | **给 `QuotaQuery` 扩展 `sources`/`expression` 两个可选字段，能力无损**（+1–1.5 人日，落在 P2）；迁移器把 `balanceOption` 的 sources/expression 原样搬到 `QuotaQuery`，单接口配置映射为 url/resultPath |
| 5 | 发布节奏 | **五个 PR 分批**：P1 流式层（行为不变）单独一个 PR 先跑通，之后 P2 类型体系、P3 账号层、P4 provider/UI、P5 回归收尾各一个 PR，每阶段可独立回滚 |

### 待决问题

（暂无。原「多源/表达式迁移」已拍板为方案 b（决策 4a），「发布节奏」已拍板为五 PR 分批（决策 5）。）

---

## 十、P0 探测结果（2026-11-01 实测）

### 11.1 Grok（xAI）设备码端点

客户端参数取自上游 `GrokAuth.kt`：`client_id=b1a00492-073a-47ea-816f-4c329264a828`，scope=`openid profile email offline_access grok-cli:access api:access`。全部经 host 代理实测（设备侧同样走系统代理）。

| # | 请求 | 结果 | 结论 |
| --- | --- | --- | --- |
| 1 | `POST https://auth.x.ai/oauth2/device/code`，**JSON** body | **415** `Form requests must have Content-Type: application/x-www-form-urlencoded` | 该端点只接受 form 编码 |
| 2 | 同上，**form** body | **200** `device_code` / `user_code=VHQC-SHX3` / `verification_uri=https://accounts.x.ai/oauth2/device` / `verification_uri_complete` / `expires_in=1800` / `interval=5` | ✅ 设备码可用 |
| 3 | `POST https://auth.x.ai/oauth2/token`，`grant_type=urn:ietf:params:oauth:grant-type:device_code` + `device_code` + `client_id` | **400** `authorization_pending`（`User has not yet authorized`） | ✅ 轮询语义符合 RFC 8628，可照搬 OpenAI 设备码状态机 |
| 4 | `GET https://auth.x.ai/oauth2/userinfo`（无 token） | **401** `invalid_token` | ✅ 端点存在，需 Bearer |
| 5 | `POST https://cli-chat-proxy.grok.com/v1/responses`（无 token） | **401** `Invalid or expired credentials (auth_kind=none…)` | ✅ Codex 类后端可达（区域/网络无阻断） |

**Grok 结论**：技术上可行，且比 ChatGPT 更简单（设备码而非 loopback），可与 ChatGPT 共用一套设备码状态机；实现注意点：device/token 请求必须 form 编码（与 ChatGPT 刷新接口「JSON 优先」相反，需在 `OAuthHttpClient` 里按端点区分）。Grok provider 后端基址为 `https://cli-chat-proxy.grok.com/v1`（Responses API 形态）。

### 11.2 `#1648` 试合并冲突面

在临时 worktree（`/f/build/projects/.tmp-p0/wt-1648`，从本仓 HEAD 建，**未动主干**，测完已移除）执行 `git cherry-pick 4b9ff1a99`：

| 指标 | 值 |
| --- | --- |
| 结果 | 冲突，`exit 1` |
| 冲突路径数 | **62**（`git diff --diff-filter=U`） |
| 状态分布 | `UU`(内容冲突) 8、`DU`/`UD`(改删) 8、`AU`/`UA`/`DD`(重命名/新增) 46 |
| 内容冲突块（工作区） | 66 |

其中 46 个是**本仓包名重命名**（`me/rerere/` → `app/androidrik/`）造成的路径错位，属于机械性冲突。

为排除重命名噪声，另做了「路径/包名归一后的三方合并模拟」（对 `#1648` 每个改动文件取 `HEAD`(ours) / `#1648^`(base) / `#1648`(theirs)，统一改名后跑 `git merge-file --diff3`）：

| 指标 | 值 |
| --- | --- |
| 可映射到本仓的文件 | 31 |
| 其中仍有语义冲突 | **15** |
| 语义冲突块合计 | **63** |
| 上游新增、本仓缺失的文件 | **52**（其中源码 8 个、测试/工具 44 个，含 `trace-cli/` 与 stream-traces 语料） |

语义冲突 top（冲突块 / 本仓行数 / 上游行数）：

| 文件 | 冲突块 | 本仓 | 上游 |
| --- | --- | --- | --- |
| `openai/ChatCompletionsAPI.kt` | 11 | 1043 | 805 |
| `data/ai/GenerationHandler.kt` | 9 | 1993 | 545 |
| `openai/ResponseAPI.kt` | 8 | 1183 | 723 |
| `GoogleProvider.kt` | 7 | 894 | 769 |
| `service/ChatService.kt` | 5 | 3405 | 1268 |
| `ChatCompletionsAPIMessageTest.kt` | 5 | 552 | 528 |
| `ClaudeProvider.kt` | 3 | 674 | 922 |
| `ai/ui/Message.kt` | 3 | 797 | 496 |
| `OpenAIProvider.kt` | 3 | 568 | 372 |
| `ui/components/message/ChatMessageCot.kt` | 3 | 265 | 68 |

本仓消费面（P1 必须同步改的调用方）：

- `MessageChunk`：**24 文件 / 120 处**（ai 16 文件、app 8 文件）
- `EventSources` / `RealEventSource`：7 文件
- app 侧最大三处：`ChatService.kt` 3405 行、`GenerationHandler.kt` 1993 行、`Export.kt` 1451 行

### 11.3 P0 结论

1. **可以进入 P1**：冲突面集中在 15 个文件、63 处，量级与 P1 预估（7–9 人日）一致，未出现「无法映射/无解」的结构性问题；代价是 app 侧消费端比预期更重（`ChatService` + `GenerationHandler` 共 5.4k 行）。
2. **重命名噪声可低成本消除**：P1 开工前先做一次纯机械的「上游路径/包名 → 本仓」映射脚本（本次已用 sed 验证可行），能把 46 个机械冲突降到 0。
3. **Grok 风险关闭**，按 P4 纳入；实现时注意 form 编码差异。
4. 上游 `#1648` 还带了一个 `trace-cli/`（Bun/TS 的流式 trace 录制回放工具）与 `ai/src/test/resources/stream-traces/` 语料——属于测试基建，**本仓可只移植 `ai/src/test` 下的 Java/Kotlin 部分**，`trace-cli` 与语料列为可选（不影响功能）。

---

## 十一、参考

- 上游提交：`4b9ff1a99`（#1648 ai 重构）、`b6df5f044`（oauth 抽包）、`318cffef6`（#2186 订阅登录）、`5ad19405c`（评估时 HEAD）
- 上游 PR：[#2186](https://github.com/rikkahub/rikkahub/pull/2186)、[#1752](https://github.com/rikkahub/rikkahub/pull/1752)（设备码血统，本仓现行方案参考）
- 本仓：`docs/design/drafts/design-codex-subscription-login.md`（现行方案设计 + 已落地记录）、`docs/upstream-analysis.md` §专项「OAuth 订阅登录（#2186）为何暂不吸收」

---

## 十二、P1 实施记录（进行中，分支 `feat/p1-stream-chunk`）

### 12.1 已落地（纯新增，尚未接线，旧 `MessageChunk` 链路原样保留 ⇒ 行为不变）

| 提交 | 内容 |
| --- | --- |
| `e7f2938db` | `StreamChunk`（18 类事件）、`SseEvent`、`StreamChunkDecoder`/`DecodeResult`、`StreamChunkHandler`（含 `finished` 标记供裸断流判定）、`ReasoningType` 枚举 + `Reasoning.reasoningType`（序列化缺省兼容旧数据）、`ServerToolProtocol`/`ServerToolMetadata`/`OpenRouterReasoningMetadata`、`ChatCompletionsStreamDecoder` |
| `96d091e33` | `ResponseApiStreamDecoder`、`ClaudeStreamDecoder`、`GoogleStreamDecoder` + `isOpenAIServerToolCall`/`toOpenAIServerTool`/`toServerToolStatus`、`isClaudeServerToolUseType`/`ResultType`/`Error` |

验证：`./gradlew :ai:compileDebugKotlin` 通过（三次增量均 0 错误）。

### 12.2 接口与接线（阶段 2，工作区增量）

**接口**
- `Provider.generateText` → `TextGenerationResult(id, model, message, finishReason, usage)`；`Provider.streamText` → `Flow<StreamChunk>`。
- `Message.kt` 新增 `List<UIMessage>.handleTextGenerationResult(result, model, providerName)`，内部复用 `handleMessageChunk` 的合并语义（同角色追加、Tool 按 id merge + 时间戳、ServerTool 状态更新、Reasoning 收尾），保证非流式与流式产物形态一致。

**四个 provider 全部改为 decoder 驱动**（本仓特性逐项保留）

| provider | 保留的本仓特性 | 新增/变化 |
| --- | --- | --- |
| `ResponseAPI` | codex 鉴权 + `Accept: text/event-stream` + `CodexSseContentTypeInterceptor`、proxy、`onFailure` 错误体解析、失败事件先送 `Usage` 再抛错、SSE `event:` 名与 payload `type` 双写归一 | 流式解析整体换 `ResponseApiStreamDecoder`；`parseResponseDelta`/`parseStreamFailure`/`functionCallOutputChunk` 删除；非流式解析升级为上游版（reasoning summary/content 两种 `reasoningType`、server tool、image gen），保留本仓 `function_call_output` 回填 |
| `ChatCompletionsAPI` | 400 结构校验「自学习重发带回填」（`armBackfillRetry` + `StreamingEventSourceTracker` + 防旧 source 迟到回调）、多 key 轮询、`"error": null` 防护（下沉到 decoder） | 逐行 JSON 解析换 `ChatCompletionsStreamDecoder`（decoder 内部按行拆包），`[DONE]` 由 decoder 产出 `Finish` |
| `ClaudeProvider` | thinking / `redacted_thinking`（`ClaudeReasoningMetadata.redactedData`）、server tool（ANTHROPIC_MESSAGES）、`stop_reason` 透出 | 换 `ClaudeStreamDecoder`；`message_stop` → `Finish`；event 名/payload type 双写归一 |
| `GoogleProvider` | thought parts、grounding annotations、`usageMetadata`（含 `thoughtsTokenCount`）、`promptFeedback.blockReason` → 抛错 | 换 `GoogleStreamDecoder`；**无独立终止事件** ⇒ 连接关闭时「有 `finishReason` 才补 `Finish`」，无 `finishReason` 保持裸断流语义 |

**app 消费端**
- `GenerationHandler`：每步一个 `StreamChunkHandler(model, providerName)`；裸断流判定 = `!chunkHandler.finished`（等价旧的 `explicitEnd`）；`chunk.toGuardDelta()` 继续喂思考退化护栏；usage 仍按「本步 merge → 全轮累计」口径；非流式改 `handleTextGenerationResult`；翻译流式按**每次重试**重建 handler。
- `ChatService`（标题/建议/压缩三处）、`OcrTransformer`、`ProviderConnectionTester`、`UsageRecorder`：改用 `result.message` / `StreamChunk.Usage`。

**语义决策（需在 PR 说明中写清）**
1. 成功路径的 `Finish` 一律由 decoder 在**显式终止事件**（`[DONE]` / `message_stop` / `response.completed|incomplete` / Google 带 `finishReason` 的末块）产出；`ChatCompletions`/`ResponseAPI`/`Claude` 的 `onClosed` **不补** `Finish`，否则裸断流会被误判为正常收尾。
2. `Responses` 非流式 `finishReason` 只透出非 `completed` 状态（避免 `"completed"` 泄进 UI/续写判定，对齐旧行为）。
3. `StreamChunkHandler` 在 `Finish` 上写 `finishedAt`/`finishReason`：相对旧路径「轮末统一写 `finishedAt`」提前到流结束瞬间；多步轮次开始时仍会清空，tok/s 卡片与 UI 终值行为不变。
4. 旧 `MessageChunk`/`UIMessageChoice`/`terminalChunk`/`handleMessageChunk` 暂时保留（被 `handleTextGenerationResult` 内部复用），下一个增量删除。

验证：`./gradlew :ai:compileDebugKotlin`、`:app:compileDebugKotlin` 均通过。

**阶段 2 实施中发现并已修复的回归**（主代码，均为「换解码器时丢掉本仓语义」或「新链路把协议细节丢给上层」）：

| 问题 | 根因 | 修复 |
| --- | --- | --- |
| Responses 流式 `function_call_output` 结果被丢弃（#2181 回归：服务端已执行的工具被客户端**重复执行**；且 `output_item.done` 缺 `item.id` 时崩溃） | 上游 `StreamChunk` 事件集没有「工具输出」通道 | 新增第 19 个事件 `StreamChunk.ToolCallOutput(id, output)`；`StreamChunkHandler` 按 id 合并进 Tool（复用 `Tool.merge` 的幂等去重，added/done 各带一份不会翻倍）；`ResponseApiStreamDecoder` 在 `output_item.added/done` **先于 id 解析**处理该类型，`call_id ?: item.id` 取 id，空输出也发（「已执行」信号本身） |
| toolCallId 兜底丢失（兼容网关在 arguments 事件里只给 `call_id`/`output_index` → `item_id not found` 抛错） | 换解码器时本仓 `ResponseToolCallIdResolver` 未接入 | 解码器内部改用 `ResponseToolCallIdResolver.registerItem/resolveArgumentsId`（`item.id` 与 `call_id` 归一、`item_id → call_id → output_index` 回退），该类不再是 dead code |
| `incomplete:<reason>` 前缀打穿截断判定（截断横幅/续写策略/content_filter 空响应路径全部失配） | 解码器按上游拼 `"incomplete:$reason"`，本仓 helper 只认裸值 | `Message.kt` 的 `isOutputLimitTruncation`/`isContentFilterTruncation` 统一走 `normalizeFinishReason()`（trim → 取 `:` 后最后一段 → lowercase），不改 decoder 输出、对其它 provider 无副作用 |
| Claude 异常中转省略 `content_block_start` 时失真（旧解析容忍，新解码器直接抛 `Unknown content block index`） | 解码器要求先 start 才建 block | `ClaudeStreamDecoder.inferBlock()` 按 delta 类型懒创建 block 并补对应 Start 事件；`content_block_stop` 缺 block 静默跳过 |
| Google 每条流都被判「正常收尾」（裸断流不可区分） | 上游 `GoogleStreamDecoder.accept()` 永不返回 `completed`，只能靠 provider `onClosed` 收尾 | `GoogleProvider.onClosed` 只在拿到过 `finishReason` 时补 `Finish`，否则保持裸断流语义；补 `GoogleStreamDecoderTest` 锁行为 |

### 12.3 后续步骤

1. **单测**（✅ 已完成）：ai 模块旧测试迁移到解码器 + 移植上游 `StreamChunkHandlerTest`（10 例）/`ResponseApiStreamDecoderTest`（9 例），新增 `GoogleStreamDecoderTest`（3 例）；`:ai:testDebugUnitTest` **421 例全绿、0 skip**。app 侧 5 个 `GenerationHandler` 相关测试迁移到 `StreamChunk` 脚本，`:app:testDebugUnitTest --tests 'app.androidrik.rikkahub.data.ai.*'` 1129 例全绿。
2. **清理 legacy**（✅ 已完成）：删除 `MessageChunk`/`UIMessageChoice`/`terminalChunk()`/`handleMessageChunk()`/`UIMessage.plus(MessageChunk)`；合并语义原样搬进 `UIMessage.mergeDelta(delta: UIMessage)`，`handleTextGenerationResult` 改为直接合并（异角色追加并写模型/提供商快照、同角色 `mergeDelta`）。`ResponseToolCallIdResolver` **保留**（已接入 `ResponseApiStreamDecoder`，不是 dead code）。`trace-cli`/`stream-traces` 语料仍为可选项。
3. **真机验证**（✅ 已完成，详见 12.5）：codex 订阅、API Key（ChatCompletions）、Claude、Google 四条路径均已在设备 `6c841f95` / debug 包跑通流式对话（含 reasoning 展示与 token/tok/s 统计，无崩溃）。工具调用 / Claude·Google 推理流 / 裸断流仍由解码器单测覆盖（`StreamChunkHandlerTest`、`GoogleStreamDecoderTest`、`ResponseAPIStreamFinishTest` 等）。
4. **提交**：P1 独立 PR（接口改造 + 4 provider 接线 + app 消费端 + 单测），PR 说明附本表与语义决策。

### 12.4 风险提示（实施中发现）

- 本仓 `UIMessagePart.Tool` 比上游多 `startedAt`/`finishedAt`（执行窗口统计），`StreamChunkHandler` 按上游语义不改这两个字段，app 侧执行工具时的赋值逻辑保持不变（已核对未被本次改动触碰）。
- `StreamChunk` 事件集**没有 Audio/Video 增量事件**：若某网关在 `/chat/completions` 流式下发 audio/video part，新链路会丢失（旧 `parseMessage` 有 Audio 分支）。上游同样不支持，按需在 P2 之后再补事件类型。
- `Google` 的裸断流判定依赖「末块带 `finishReason`」；若网关把最后一块（含 `finishReason`）丢掉，会正确判 incomplete（符合预期），但若网关把 `finishReason` 放在被截断前的块里则不判 incomplete——与旧实现同款局限。
- `Responses` 非流式解析现在会产出上游新增的 `Image`/`ServerTool`/`reasoningType` part：这些 part 在本仓 UI 已支持渲染，但**旧版本客户端**（如果存在跨版本同步/导入）可能不认识——本仓无跨版本消息互通，暂不处理。
- `StreamChunk.ToolCallOutput` 是本仓在 18 类上游事件之外新增的第 19 类（`@SerialName("tool_call_output")`）；后续 P2 若上游再改事件集，需在每次变基时确认该事件仍有承载位置（上游没有对应语义，预计长期保留）。
- `normalizeFinishReason` 取 `:` 后最后一段：若将来某 provider 的 reason 自身含 `:` 且最后一段恰为 `length`/`content_filter` 之类会误判；三家现行协议无此形状，单测已加负例（`completed`/`end_turn`/null 不命中）。

---

## 十三、P1 真机验收记录（2026-11-01，设备 `6c841f95` / Pix110 / debug 包）

### 13.1 结果

| 路径 | 提供方 | 结果 | 证据 |
| --- | --- | --- | --- |
| ChatGPT 订阅（Codex，Responses 流式） | 本机 codex 账号（plus） | ✅ | 两次：阶段 2 完成后 + legacy 清理重打包后；回复 "1+1=2"，token/tok/s 卡片正常，订阅会话 provider lock 横幅正常，无崩溃 |
| API Key（ChatCompletions 流式） | 鸭鸭AI平台 · `glm-4.5-air`（真实服务） | ✅ | 回复 `Hello! How can I help you today?`；**思考 0.7 秒**（reasoning part 正常渲染）；↑7.6K/↓61 tokens、74.6 tok/s、0.8s；`智谱AI开放平台 · glm-4.5-air` 角标正确 |
| Claude 协议（Anthropic Messages 流式） | mock SSE（`adb reverse` → 宿主机，`POST /claude/v1/messages`） | ✅ | 回复 `Hello from mock Claude (streaming OK)`；↑12/↓9 tokens；服务端日志 200；`MOCK Claude · MOCK-Claude` 角标正确 |
| Google 协议（Gemini `:streamGenerateContent?alt=sse`） | mock SSE（`adb reverse` → 宿主机） | ✅ | 回复 `Hello from mock Gemini (streaming OK)`；↑11/↓8 tokens；`finishReason: STOP` 收尾正常；服务端日志 200；`MOCK Gemini · MOCK-Gemini` 角标正确 |

Claude / Google 两条走的是**同一份 APK 的真实代码路径**（`ClaudeStreamDecoder` / `GoogleStreamDecoder` → `StreamChunkHandler` → UI 渲染 + usage + Finish 语义），仅服务端换成本地 mock SSE；验证方式：临时向 DataStore Preferences（protobuf）注入两个 MOCK 提供商（原 `settings.preferences_pb` 已备份），验证后完整还原（设备当前 settings 中 `MOCK Claude`/`MOCK Gemini`/`claude-mock` 计数均为 0），`adb reverse` 规则已移除、mock 服务已停。

### 13.2 真机未覆盖项（由单测兜底，后续有真实 key 时补验）

- **工具调用 / `ToolCallOutput`**：Claude、Google 真机未构造工具回合（ChatCompletions 与 Responses 的 `function_call_output` 由 `ResponseAPIFunctionCallOutputTest`、`ResponseAPIStreamToolCallIdTest` 覆盖）。
- **Claude / Google 推理流**：mock 未造 thinking / thought parts 事件（`ClaudeStreamDecoder`、`GoogleStreamDecoder` 单测覆盖）。
- **裸断流（bare-EOF）**：mock 只发正常终止事件；裸断流语义由 `StreamFinishSemanticsTest` / `GoogleStreamDecoderTest` / `ResponseAPIStreamFinishTest` 覆盖。
- **真实 Anthropic / Gemini 服务的字段差异**：真实 `stop_reason` 取值分布、真实 `usageMetadata`（含 `thoughtsTokenCount`）、真实 server tool 事件形状——待有 key 或 P4 账号 provider 落地后补。
- 多 key 轮询、代理、per-provider 重试等本仓特性在 Claude/Google 真机路径上未单独复验（ChatCompletions 路径已有真实服务覆盖）。

## 十四、P2 实施记录（分支 `feat/p2-quota-accounts`，基于 `master`）

P2 = 类型体系 + 余额替换：引入上游 #2186 的 `ProviderQuota`/`QuotaQuery` 语义，删除本仓 `BalanceOption` 余额链路。
P1（PR #1，`ecc290b46`）已合并，本分支已变基到该提交之上；`Provider.kt` 与 P1 无冲突（P1 改 `generateText/streamText`，P2 只增删 `getBalance/getQuota`）。

### 14.1 提交

| 提交 | 内容 |
| --- | --- |
| `729b20683` | `ProviderQuota`（Balance / UsageLimits + UsageWindow）、`QuotaQuery`（上游 `url`/`headers`/`resultPath` + 本仓扩展 `sources`/`expression`）、`QuotaSource`、`renderQuotaTemplate`；`QuotaQueryTest` 6 例 |
| `060c13535` | `ProviderSetting.ApiKey` 抽象基类（`apiKey`/`apiKeys`/`baseUrl`/`quotaQuery`），三个提供商改为继承；`Provider.getQuota()`；`QuotaQueryRunner`（单接口模板渲染 / 多接口并行 + 表达式求值 / 数字两位小数 / 头合并与覆盖）；三家 provider 实现 `getQuota`；`QuotaQueryRunnerTest` 7 例 |
| `1e00e71b7` | 删除 `BalanceOption`/`BalanceSource`/`getBalance`/`ProviderBalanceText`；`PreferenceStoreV4Migration`（数据版本 3→4）；`QuotaQueryOption` 编辑器替换余额编辑器；`ProviderQuotaText` 替换余额文本；内置供应商默认配置改为 `QuotaQuery`；测试与文案更新 |
| `fc6b974b5` | 备份恢复路径（`SettingsJsonMigrator` V6）复用同一迁移实现，避免旧备份的余额配置被静默丢弃 |

### 14.2 语义与兼容

- **类型**：`ProviderQuota.Balance(value)` 承载自定义查询结果与（后续）账号类余额；`UsageLimits(windows, limitReached)` 供 P4 的 ChatGPT/Grok 订阅用量使用。
- **ApiKey/Account 分层**：`ApiKey` 抽象子类已就位；`Account` 与 `OAuthCredential` 留到 P3（账号层 PR），避免在 P2 引入无人使用的类型。
- **多接口能力无损**：旧 `BalanceOption.sources/expression` 原样进 `QuotaQuery.sources/expression`；单接口 `apiPath` → `url = "{{baseUrl}}" + apiPath`；`apiPath` 本身是完整 URL 时不拼 baseUrl（旧余额实现同款分支，迁移与执行器两侧都有测试）。
- **鉴权**：`QuotaQuery.headers` 默认 `Authorization: Bearer {{apiKey}}`；提供商级自定义头原样下发、查询配置头模板渲染且同名覆盖；key 走各自多 key 轮询，且模板里**只取一个 key**（懒求值，不影响对话轮转）。
- **缓存/展示**：模型列表的额度文本按「提供商 id + 查询配置哈希」缓存 2 分钟，改配置自然失效；失败不缓存（显示 `Error: …` 下次重进重试）。
- **删掉的类型**：`BalanceOption`、`BalanceSource`、`ProviderSetting.balanceOption`、`Provider.getBalance`、`ProviderBalanceText`、`SettingsProviderBalanceOption` 共 89 处引用清零；`ignoreUnknownKeys` 保证旧数据/旧备份未迁移时也能解码（不崩、只是丢配置，因此迁移必须生效）。

### 14.3 迁移与真机验证（设备 `6c841f95`，debug 包）

- **DataStore V4**：设备升级启动无崩溃；`data_version` 3 → 4；37 个提供商 `balanceOption` 计数 0、全部带 `quotaQuery`，8 个原本开启余额的提供商迁移出可用配置（含 `sources`/`expression` 用例）。
- **额度链路真机**：真实服务「DeepSeek」显示 **25.81**（内置 `{{baseUrl}}/user/balance` + `balance_infos[0].total_balance`）；mock 端点（`adb reverse` 到宿主机 `/quota` 返回 `{"data":{"total_usage":12.5}}`）显示 **12.50**（验证两位小数格式化与 `getQuota` → UI 全链路）。验证后设备配置已还原、`adb reverse` 与 mock 服务已清理。
- **备份恢复**：`SettingsJsonMigrator` V6 单测覆盖（旧备份 `balanceOption` → `quotaQuery`、无 providers 时原样返回）。

### 14.4 P2 未覆盖 / 已知限制

- `QuotaQueryOption` 的**多接口模式**只在单测覆盖，真机只点验了单接口；多接口编辑（增删数据源/表达式）留待 P3 联调时一起过一遍。
- 额度查询的**请求头编辑器**未做（默认 `Authorization: Bearer {{apiKey}}` + 提供商自定义头已能覆盖绝大多数中转；需要任意头时在提供商「自定义 Headers」里配）。
- Chatbox 导入器按 Chatbox 自己的 JSON 结构构造 provider，不含 `balanceOption`，无需迁移；Web DTO / 分享串无余额字段，核对后无改动。
- `ProviderQuota.UsageLimits` 的展示（窗口占比、`limitReached`）已实现但 P2 无 provider 返回该类型，P4 接入 ChatGPT/Grok 后再真机验证。
- 降级策略（旧版读新数据）按 §10 第 3 条：旧版本不可用 + 发布说明 + 迁移前自动备份，P5 统一落地。

---

## 十五、P3 实施记录（分支 `feat/p3-account-layer`，基于已合并 P1+P2 的 `master`）

P3 = 账号层基础设施：`oauth` 模块（loopback PKCE / 设备码 / 前台服务）+ `Account` 类型体系 + `ProviderAccountManager`。
本阶段**不产生用户可见变化**（没有登录入口、没有 ChatGPT/Grok Provider 实现），等价 P1 的"先落基础、后接线"策略；设备码登录路径原样保留。

### 15.1 提交

| 提交 | 内容 |
| --- | --- |
| `fa02c9e4b` | 移植上游 `oauth/` 模块 17 文件（`OAuthHttpClient` 329 行、`OAuthLoopbackCallbackServer` 453 行、`OAuthCallbackForegroundService` 187 行、Launcher、Manifest、通知图标、7 语言文案、2 个单测），包名 `me.rerere.oauth` → `app.androidrik.oauth`；`settings.gradle.kts` 加 `:oauth`，app 加 `implementation(project(":oauth"))` |
| `2c529c456` | ai 账号类型：`OAuthCredential`、`AccountCredentialProvider`、`ProviderSetting.Account`（`ChatGPT`/`Grok`，`@SerialName("chatgpt")`/`("grok")`，保留 fork 的 proxy/retry/reasoningOverride 字段）、`providers/AccountAuth.kt`（账号鉴权头 + 401 刷新重试 Authenticator）；`effectiveApiKeys` 只对 ApiKey 生效；编译期穷尽 `when` 的适配（ProviderManager/ToolSchemaSize/GenerationHandler/PreferencesStore/导入器/ProviderConfigure）；`SettingsCompatDecoder` 不再丢弃上游 chatgpt/grok |
| `210481f6b` | app 账号层：`ProviderAccountManager`（登录/刷新/凭据写回/状态流）、`ChatGPTAuth`（codex-rs 同款 loopback PKCE + JSON 刷新）、`GrokAuth`（xAI 设备码）、`Jwt`、`preserveCredential`、DI 注册；测试：上游 `ChatGPTAuthTest`/`GrokAuthTest` + `CredentialCipherTest` |

### 15.2 架构与语义

- **oauth 模块**：与上游逐字节一致（唯一差异包名）。`OAuthHttpClient` 封装 PKCE 生成、授权 URL 拼装、code/device-code 换 token、refresh、device token 轮询；`OAuthLoopbackCallbackServer` 绑定 `127.0.0.1:1455` 接收回调并显示前台服务通知。模块 Manifest 带来 `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE(_SPECIAL_USE)` 权限（manifest merge 进 app）。
- **Account 分层**：`ProviderSetting.Account` 只有 `credential` 与 `withCredential`，没有 baseUrl/apiKey——token 不可能被配置发到第三方地址。`ProviderSetting.Types`（类型切换器）仍只有三种 API 格式：账号类 provider 由 P4 的登录入口创建，不参与"API 格式切换"。
- **401 刷新**：`AccountAuth.configureAccountAuth` 先写自定义头再写 Authorization（保证不被覆盖），并给请求打 tag；`AccountTokenAuthenticator` 只在 401 且未重试过时向 `AccountCredentialProvider` 要新凭据重试一次。
- **刷新语义**（对齐上游）：过期前 5 分钟或 token 刚被 401 拒绝就刷新；刷新在 `appScope.async(Dispatchers.IO)` 里跑，**调用方取消（停止生成/关界面）不会打断已发出的刷新**（refresh token 一次性轮换，丢了只能重登）；按 provider id 串行 + 拿锁后重读设置（并发请求只用一个 refresh token）；若刷新期间账号被登出/切换，`replaceCredential(expected=…)` 不写入并报错。
- **登录**：ChatGPT 走 loopback PKCE（Custom Tabs + 前台服务 + 5 分钟超时）；Grok 走设备码（`AccountLoginStatus.DeviceCode` 供 UI 展示 user_code），email 缺失时查 `userinfo`（失败不影响登录）。
- **设备码兜底**：旧 `OpenAICodexAuthService` 的订阅登录链路**完全未动**，P3 的账号层与它并存；P4 把新 ChatGPT Provider 接上后，旧链路降级为"主登录失败时的设备码兜底"，P5 再评估是否移除。

### 15.3 刻意偏离上游

| 项 | 上游 | 本仓 | 原因 |
| --- | --- | --- | --- |
| 凭据落盘 | settings JSON 明文 | `SecretCipher` 加密 access/refresh token（`v1:` 前缀），读取时解密；解不开按未登录处理 | 沿用旧 `CodexSubscriptionAccount` 的安全策略，避免把已有加密能力降级 |
| 加密实现 | — | `CredentialCipher` 抽象 + 默认 `SecretCipher` 实现（可注入） | Keystore 不可用于 JVM 单测；`CredentialCipherTest` 覆盖加密 roundtrip/幂等/明文透传/解不开置空 |
| Account 字段 | 无 proxy/retry/reasoningOverride | 保留 fork 额外字段 | 账号类 provider 同样需要代理与重试覆盖 |
| 备份兼容 | — | 明文凭据（上游格式）读取时原样透传；加密凭据解不开 → 空 token → 引导重登 | 跨设备恢复备份时 Keystore 密钥不同（与旧 codexAccount 同限制） |
| Manager 测试 | `ProviderAccountManagerTest`（并发刷新） | 未移植：fork `SettingsStore(context, AppScope)` 依赖 Android Context、无 Robolectric | P3 用纯函数测试覆盖（认证参数/JWT/cipher/preserveCredential），并发刷新语义留待 P4 真机验证 |

### 15.4 验证

- `:oauth:testDebugUnitTest` **9/9**（OAuthHttpClientTest 8 + OAuthLoopbackCallbackServerTest 1；`--rerun-tasks` 复跑确认，与上游 diff 仅包名）。
- `:ai:testDebugUnitTest` **443 例**（新增 `AccountTypesTest` 8 例：类型名持久化、多态 roundtrip、旧 JSON 兼容、withCredential/copyProvider、effectiveApiKeys、toString 脱敏）。
- `:app:testDebugUnitTest` **2707 例（2 skip）**：新增 `ChatGPTAuthTest` 12 例、`GrokAuthTest` 3 例、`CredentialCipherTest` 6 例；`SettingsCompatDecoderTest` 更新为「chatgpt/grok 已支持 + 真正未知类型仍降级丢弃」。
- `:app:assembleDebug` 通过（manifest merge 带入 oauth 前台服务）。
- 真机（设备 `6c841f95`，P3 APK 覆盖安装）：启动无崩溃；settings 仍是 37 个提供商、`codexAccount` 字段完整、`data_version=4`（Account 类型新增未影响旧数据解码）；模型列表渲染正常；订阅模型（GPT-6.1-Sol / OpenAI codex）发消息后请求确实打到 `https://chatgpt.com/backend-api/codex/responses`，但本机网络对该域名 **TLS handshake 被中途关闭**（`SSLHandshakeException: connection closed`），未拿到回复——属网络环境问题（P1 验收时同一路径成功），P3 无相关改动。

### 15.5 P3 未覆盖（P4 承接）

- `ChatGPTProvider` / `GrokProvider` 实现与注册、登录 UI（设置页账号区、设备码 user_code 展示、退出登录）、订阅额度 `ProviderQuota.UsageLimits`。
- 旧 `ProviderSetting.OpenAI.codexAccount` → `ProviderSetting.ChatGPT(credential)` 的**设置迁移**（需要新 Provider 能用；含 `SubscriptionProviderLock` 与对话模型绑定适配、`authType=CHATGPT_SUBSCRIPTION` 的 OpenAI 提供商去留）。
- `ProviderAccountManager` 的并发刷新/杀进程重启真机验证；`preserveCredential` 接入设置页保存路径。
- oauth 前台服务在真实登录流程中的通知权限申请（Android 13+）与 Custom Tabs 回跳体验。

---

## 十六、P4 实施记录（分支 `feat/p4-account-providers`，基于已合并 P1+P2 的 `master` + P3）

### 16.1 提交

| 提交 | 内容 |
| --- | --- |
| `25ad5bc94` | ai 侧账号类提供商：`ChatGPTProvider`（Codex 后端模型/额度/流式）、`GrokProvider`（CLI+api.x.ai 双目录、周/月 billing）、`ResponsesEndpoint`/`ResponseProviderCapabilities` 重构（含 endpoint 版 generateText/streamText、capabilities 版请求体、`prompt_cache_key = params.sessionId`）、`ProviderManager` 注册 chatgpt/grok、`ProviderSetting.supportsQuota`、`TextGenerationParams.sessionId`、`fetchBody`；测试 ChatGPTProviderTest 11 / CodexEventStreamTest 2 / GrokProviderTest 8 / OpenAIResponsesEndpointTest 4 + 既有 2 例扩充（`:ai` 443 → 470） |
| `30aeeedd7` | app 接线：`ProviderManager(accountCredentials = ProviderAccountManager)`；`GenerationHandler.sessionId` + `ChatService` 传 `conversationId`（Responses prompt_cache_key / ChatGPT `session-id` / Grok `x-grok-conv-id`）；3 处单测补 fake 凭据 |
| `144a95de2` | 设置页 UI：`ProviderConfigureAccount`（登录/等待/设备码大字+重开验证页/已登录邮箱套餐+退出）、ChatGPT/Grok 分支、`rememberProviderQuota` + Account 用量展示、新建模板 sheet（OpenAI/Google/Claude/ChatGPT/Grok）、保存走 `preserveCredential`、账号类隐藏 API 格式切换与模型级 provider 覆盖、22 条上游同 key 文案（values / values-zh / values-zh-rTW） |
| `623d4ee5a` | 本仓专有迁移：`PreferenceStoreV5Migration`（data_version 4→5，旧 `OpenAI(authType=chatgpt_subscription, codexAccount)` → `ProviderSetting.ChatGPT`，密文原样搬）、`SettingsJsonMigrator` V7（备份恢复同一变换）、`SubscriptionProviderLock` 扩展识别账号类 ChatGPT；测试 V5 17 例、备份 +7、订阅锁 +8 |

### 16.2 实施内容

- **ai 层**：账号类 Provider 的请求地址写死在各自 Provider 内（`chatgpt.com/backend-api/codex`、`cli-chat-proxy.grok.com`/`api.x.ai`），配置里没有 baseUrl/apiKey，token 不可能被发往第三方地址。ChatGPT 复用本仓 `CodexSupport.kt` 已有的 `CODEX_CLIENT_VERSION`/`CODEX_ORIGINATOR`/`parseCodexModels`（旧设备码链路仍在使用，避免两份）；Grok 复用 `xai-grok-cli` 通道头与 `grokCli` 目录优先级。
- **app 层接线**：账号凭据只在 provider 发请求时通过 `AccountCredentialProvider` 现取（401 时由 `AccountTokenAuthenticator` 刷新重试一次），设置页保存整份 provider 时用 `preserveCredential` 保留后台写回的最新凭据。
- **旧链路并存**：旧 `ProviderSetting.OpenAI.authType=chatgpt_subscription` + `OpenAICodexAuthService` 设备码链路**完全未删**；迁移只做一次性数据形态转换，P5 再决定是否移除旧 UI/服务。
- **能力判定**：`supportsQuota`（ApiKey → `quotaQuery.enabled`；Account → `credential != null`）统一决定设置页是否展示用量；`ProviderSetting.Types`（API 格式切换）仍只有 OpenAI/Google/Claude，账号类不参与互转。

### 16.3 版本与迁移

- `data_version` 4 → **5**（本仓 V1..V4；上游同语义迁移在 V4/V5，编号已错位，**不改名**）。
- 备份格式 `SettingsJsonMigrator` 最高 V6 → **V7**：旧备份恢复同样转换成 ChatGPT，不会以旧 OpenAI 形态落回；与新版本无关的中间产物（如 V6 的 `quotaQuery`）随迁移丢弃（Account 无该字段）。
- 迁移规则：保留 `id`（历史消息 modelId 与订阅锁依赖）、`enabled`/`name`/`models`/`proxy`/`customHeaders`/`retryOption`/`reasoningOverride`；`credential = OAuthCredential(accessToken=accessTokenEncrypted, refreshToken=refreshTokenEncrypted, expiresAt, accountId, email, plan=planType)`，密文逐字节原样搬；两个 token 都空 ⇒ `credential=null`（未登录）；显式 `authType=api_key` 的残留 `codexAccount` **不迁**（用户已切回 API Key，旧请求链路本就不读 codexAccount，迁走等于删掉可用配置）；幂等、畸形 JSON 原样返回。
- `SubscriptionProviderLock`：锁定候选 = 旧 OpenAI 订阅 ∪ 已登录且有 models 的 ChatGPT（P5 起加上 Grok，见 §十七）。

### 16.4 刻意偏离上游

| 项 | 上游 | 本仓 | 原因 |
| --- | --- | --- | --- |
| `TextGenerationParams.sessionId` 默认值 | 随机 UUID | `null`；由 app 层显式传 `conversationId` | 本仓旧 Codex 链路用 `codexPromptCacheKey` 稳定键，随机默认会破坏 P1 行为与既有单测 |
| `ResponsesEndpoint` 字段 | 只有 url/capabilities/configureRequest | 多 `reasoningOverride`/`proxy`/`forceEventStream` | 本仓 P2 提供商级思考强度兜底、P1 两层代理、Codex SSE 容错 |
| Codex SSE 容错 | 改 common `newEventSource` 全局宽容 | 沿用 `CodexSseContentTypeInterceptor`（只补 Codex 端点）+ 显式 `Accept: text/event-stream` | 其它网关保持 OkHttp 严格校验，不放大全局宽容面 |
| 旧 `buildRequestBody(ProviderSetting.OpenAI, …)` 重载 | 已删 | 保留 | 旧 Codex 设备码链路与 P1 既有单测仍在用；订阅态回填 `codexPromptCacheKey` 保证逐字不变 |
| server-tool 回传 / 全局 session 头（含 Chat Completions 的 `session_id`） | 有 | **已移植**（P6，见 §18） | 补齐 P1 起既有缺口；app 层已传 `sessionId = conversationId`，会话头有实际作用 |
| `ProviderAccountManager` 并发刷新测试 | 有 | 未移植（P3 已说明：无 Robolectric） | 并发/杀进程语义改为真机与代码审查覆盖 |
| Account 类型字段 | 无 proxy/retry/reasoningOverride | 保留本仓扩展字段 | 账号类同样需要代理与重试覆盖（P2/P3 已有语义） |

### 16.5 验证

**单测 / 构建**

- `:oauth:testDebugUnitTest` **9/9**；`:ai:testDebugUnitTest` **470 例全绿**（P3 443 + P4 新增 27）；`:app:testDebugUnitTest` **2739 例全绿（2 skip）**（P3 2707 + 新增 32）。
- `:app:compileDebugKotlin`、`:app:assembleDebug` 通过（arm64-v8a APK）。

**真机（设备 `6c841f95` / PJX110 / debug 包，覆盖安装）**

- **迁移（V5）**：启动无崩溃；设备上唯一一个旧订阅 provider（`OpenAI`，`authType=chatgpt_subscription`）原地变成 `{"type":"chatgpt","id":"bd407eef-…"}`：id 不变、8 个模型不变、`credential.email=jiushixiuqi@gmail.com`、`plan=plus`、`expiresAt` 不变、`accessToken/refreshToken` 仍是同一份 `v1:` 密文；全库 `codexAccount` 计数归 0；`data_version=5`。
- **账号 UI**：设置 → 提供商列表首个条目即迁移后的 ChatGPT（原名 OpenAI、8 个模型）；详情页「账户」区显示邮箱 + `ChatGPT Plus` + 退出登录，「用量」区显示真实额度（5 小时限额 0%、每周期限 2%、重置时间 2026-11-08 18:45:29），无 API Key/Base URL/额度查询配置；「模型」页 8 个模型齐全、可启停。
- **真实流式对话（核心验收）**：用迁移后的 provider（GPT-6.1-Sol）发一条消息，**成功收到真实回复**（"Hello, how can I help you today?"），usage 卡片显示 ↑6.3K tokens / ↓13 tokens / 36.1 tok/s / 0.4s，页脚 `OpenAI · GPT-6.1-Sol`。⇒ 迁移后的凭据解密、401 刷新链路、Codex 后端请求体/SSE 解码、`sessionId` 提示缓存键全链路工作。
- **Grok 登录**：新建 Grok provider → 「使用 Grok 登录」→ 设备码流程拉起 `accounts.x.ai` 自定义页签，用户在真机完成登录后设置页自动变为已登录（邮箱 + 退出登录），凭据以 `v1:` 密文落盘；用量/模型接口在**无 SuperGrok 订阅**的账号上被服务端拒绝（分别报 `connection closed` 与 `获取模型失败`），UI 正确显示可重试的错误态、不崩溃 —— 属账号权限/网络环境，不是代码缺陷。
- **环境备注**：设备设置了全局代理 `127.0.0.1:8888`（P1 遗留，指向宿主 clash），需要 `adb reverse tcp:8888 tcp:8888` 才能联网；验收中 adb server 重启导致该映射丢失，出现 `Failed to connect to /127.0.0.1:8888`，恢复映射后 ChatGPT 链路即恢复正常。测试创建的 Grok provider（已登录、无订阅、models 为空）留在设备上，可随时删除。

### 16.6 P4 未覆盖 / 已知限制（P5 承接）

- **ChatGPT loopback 登录**（Custom Tabs + 前台服务 + `127.0.0.1:1455` 回调）：P5 已由用户在新账号上真机点通（含 Android 13+ 通知权限），见 §17.1。
- **Grok 全链路**：登录成功但账号无 SuperGrok 订阅，模型目录/额度/对话均无法真机验证；需有订阅的账号补验（`GrokProviderTest` 8 例覆盖解析，未覆盖真实端点差异）。
- **降级/去留**：P5 已删除旧设备码链路，降级与发布说明见 §17.3 / §17.4。
- **Grok 订阅锁**：P5 已决策纳入 `SubscriptionProviderLock`（Grok Responses 端点同样回传自家加密推理），见 §17.2。
- **`ProviderAccountManager` 并发刷新 / 杀进程重启**：P5 已把刷新编排抽成 `AccountRefreshCoordinator` 并补 7 例 JVM 单测（并发只刷新一次 / 401 复用 / 失败不写回 / 刷新期间登出作废 / 缺 refresh token 等）；**杀进程**仍无自动化覆盖，需真机手测（步骤见 §17.6）。
- **server-tool 回传 / 全局 session 头**：属 P1 起既有缺口与后续增强，不在 P4 范围。

### 16.7 补充：用量展示口径（已用 / 剩余）与后端字段实测

**来源**：P4 验收后反馈「用量显示为剩余量而非已用量」，并接受「做成可选项切换」。

**先确认后端语义**（临时给 `parseCodexUsage` 加了一行 `Log.d` 打印原始 body，验完已删）：

```json
"rate_limit": {
  "allowed": true,
  "limit_reached": false,
  "primary_window":   { "used_percent": 0, "limit_window_seconds": 18000,  "reset_at": 1793567476 },
  "secondary_window": { "used_percent": 2, "limit_window_seconds": 604800, "reset_at": 1794134729 }
}
```

字段名就是 `used_percent`，且随用量**增长**：连着发 4 次真实 Codex 请求（每次 ↑6.3K tokens，其中 97% 命中缓存）后再查，5 小时窗口 `0 → 1`、每周窗口 `2 → 3`。所以 ChatGPT/Codex 后端给的是**已用量**，`used_percent` 不能当剩余量用；`limit_reached=false / allowed=true` 也印证 0% 是「刚重置、还没用」。

**改动**（`feat(app): 用量展示口径可切换（已用/剩余）`）：

- `Settings.usageDisplayMode`（新枚举 `UsageDisplayMode.USED / REMAINING`，默认 **USED**）：只是展示层偏好，DataStore 新 key `usage_display_mode`，旧设置无该键 → 默认 USED；**不升 `data_version`**（无迁移必要），备份 JSON 同样按缺省值解码。
- 展示：账号用量卡与 `ProviderQuotaText` 都按口径换算——已用 = 后端原值，剩余 = `100 - 已用`（`UsageWindow.displayPercent(mode)`，钳在 0..100）；百分比前**显式加「已用 / 剩余」前缀**，不再出现「0% 到底是用了还是剩了」的歧义；进度条跟着所选口径走。
- 入口：偏好设置 → 界面偏好设置 → 用量显示 → 百分比口径（已用 / 剩余两个 chip）。
- 测试：`UsageWindowDisplayTest` 3 例（已用/剩余换算、钳位）。

**真机验证**（同一账号、同一时刻）：

| 口径 | 5 小时限额 | 每周限额 |
| --- | --- | --- |
| 已用（默认） | 已用 1% | 已用 3% |
| 剩余 | 剩余 99% | 剩余 97% |

两种口径互相补足，切换后立即生效（设置页改完返回即刷新，无需重启）。

**顺带记录**：验证「用量是否随请求增长」时，有一次带工具调用（`eval_javascript`）的长回复报 `SSE stream failed without exception`（`ResponseAPI` 的流结束兜底：没收到 `response.completed` 就断开）。同批次另外 3 次短回复与随后 1 次长回复均正常，怀疑是代理链路中途掐断（本机经 `127.0.0.1:8888` 代理）。**P5 复测通过**：同一路径（带工具调用的长回复）重试已正常流式完成、收到 `response.completed`，判定当时是代理链路瞬时断流，非代码缺陷；「无 completed 的 EOF 降级为可恢复错误」作为可选增强留待后续，不在本次修复。

---

## 十七、P5 实施记录（分支 `feat/p5-cleanup`，基于已合并 P1–P4 的 `master`）

### 17.1 ChatGPT loopback 登录真机验收（用户操作，2026-11-02）

P4 唯一未点通的登录路径已在真机补验通过：**新账号**走 Custom Tabs → ChatGPT 授权页 → `127.0.0.1:1455` 回环回调（前台服务通知，Android 13+ 通知权限）→ 回到 App 显示已登录（邮箱 + 套餐），凭据以 `v1:` 密文落盘。至此 ChatGPT 的 loopback PKCE 与 Grok 的设备码两条登录路径都在真机走通，旧 Codex 设备码链路不再承担主登录职责。

### 17.2 Grok 纳入订阅锁定（`SubscriptionProviderLock`）

- **依据**：Grok 的 Responses 端点与 Codex 后端一样，会回传只有自家后端能解读的加密推理（`encrypted_content`）；把这类对话切到其它提供商 = 把解不开的加密块丢给外人，轻则静默丢弃、重则 400。
- **规则**（与 ChatGPT 一致）：`ProviderSetting.Grok` 满足 `enabled && credential != null && models.isNotEmpty()` 才参与锁定；未登录/停用/无模型的 Grok 不锁（否则锁上去只会让对话彻底没法继续）。
- **行为**：一旦对话历史里有 Grok 订阅模型生成的消息，后续只能在该 Grok 提供商内换模型；模型选择器的候选提供商同步只保留它。判定仍按**对话历史**而不是当前选中模型（避免助手级换模型把历史对话全部锁死）。
- **测试**：`SubscriptionProviderLockTest` 用 3 例钉住新契约（登录且有模型 ⇒ 锁定；未登录 ⇒ 不锁；停用 ⇒ 不锁），并保留「同提供商换模型允许 / 别家模型拒绝」断言。app 2742 → 2744（P5 删旧链路后为 2734）。
- **真机限制**：当前测试账号没有 SuperGrok 订阅，无法产生「Grok 订阅模型生成的历史消息」来做端到端锁定验证；锁定逻辑由纯函数单测覆盖，有订阅账号后按 P4 记的 Grok 全链路补验。

### 17.3 删除旧 ChatGPT 设备码订阅链路（loopback 登录通过后的清理）

P4 的两条登录路径都在真机走通（§17.1）后，旧 OpenAI 设备码订阅链路不再承担登录职责，按 P5 计划整体删除：

| 删除项 | 说明 |
| --- | --- |
| `app/.../data/ai/openai/OpenAICodexAuthService.kt`（603 行）+ 单测 | 设备码登录、token 刷新、`codexAccount` 写回设置 |
| `ai/.../providers/openai/OpenAICodexTokenProvider.kt`（`CodexToken`） | app 层向 ai 层喂明文令牌的接口 |
| `ProviderSetting.OpenAIAuthType`、`CodexSubscriptionAccount`、`OpenAI.authType`、`OpenAI.codexAccount` | 旧订阅的存储模型（`copyProvider` 同步去掉两个参数） |
| `OpenAIProvider` / `ChatCompletionsAPI` / `ResponseAPI` 的 `codexTokenProvider` 参数与订阅分支；`OpenAIRequestAuthenticator` 的订阅鉴权 + Codex 端点守卫 | 鉴权统一回落到 `KeyRoulette`（账号类走 `AccountAuth.kt`，P3 已就位） |
| `CodexSupport` 的 `isChatGPTSubscription` / `codexPromptCacheKey` / `CODEX_PROMPT_CACHE_KEY_MAX` 与订阅 URL 分支 | 保留 `CODEX_CAPABILITIES`、`CODEX_CLIENT_VERSION`、`CODEX_ORIGINATOR`、`parseCodexModels`（ChatGPTProvider 依赖） |
| `ProviderConfigureOpenAI` 的「认证方式」分段按钮、设备码对话框、登录/登出/强制刷新 UI；`DataSourceModule` 的 `OpenAICodexAuthService` 单例 | 设置页 API Key 类只剩名称 / 多 Key / BaseUrl / 路径 / Response API 开关等原有字段 |
| 22 条 `setting_provider_page_codex_*` 文案 + 3 条认证方式文案（en/zh/zh-TW 同步） | 无引用后清理，三语文案一致 |

**兼容性**：`Settings` 解码用 `JsonInstant`（`ignoreUnknownKeys = true`），旧 JSON 里残留的 `authType`/`codexAccount` 会被静默忽略；V5/V7 迁移仍按**原始 JSON** 解析旧字段（不依赖已删的数据类），所以「老用户升级」的迁移路径完全不变。单测验证：V5 迁移用例里「显式 `authType=api_key` 的残留 `codexAccount`」仍解码成可用的 API Key provider（旧字段被忽略、`apiKey` 保留）。

**验证**：`:ai` 467（−3：订阅鉴权 / 请求体相关用例随旧链路删除）、`:app` 2734（−10：旧锁 / 旧服务 / 旧 UI 用例）；`:app:assembleDebug` 通过；真机覆盖安装后启动正常，设置页 OpenAI 类 provider 只剩 API Key 字段，迁移后的 ChatGPT 账号页显示邮箱 + ChatGPT Plus + 剩余额度，Grok 账号页与用量正常加载。

### 17.4 降级策略与发布说明

**为什么不兼容**：V5 迁移后 `data_version = 5`、provider 列表里出现 `{"type":"chatgpt"}` / `{"type":"grok"}` 两种**旧版本不认识的类型**。降级回 P1/P2 时代的构建时，旧的兼容解码会把未知类型**丢弃**（provider 及其模型消失，历史消息的 `modelId` 找不到 provider）；更早版本可能整体解码失败。

**降级策略（写进发布说明）**：

1. **升级前先备份**：设置 → 备份 → 立即备份。备份文件是旧格式；恢复时会走 `SettingsJsonMigrator` V7 把旧订阅条目转成账号类。
2. **不建议降级**：若必须降级，就**卸载重装旧版本并恢复升级前的备份**（直接覆盖安装旧 APK 会读到已迁移数据，provider 丢失）。
3. **迁移前自动兜底**：P5 未新增自动备份文件；DataStore 的 `data_version` 保证迁移只跑一次，且 V5 转换是**纯 JSON 形态变换**（幂等、密文原样搬运），出错面很小。
4. 降级之后再升级回来：账号类 provider 会被 `SettingsCompatDecoder` 保留（P3 起支持 chatgpt/grok），无需重复迁移。

**发布说明草案（可直接贴 GitHub Release）**：

> **订阅登录重构（OpenAI OAuth 移植）**
>
> - **ChatGPT 订阅**：改用 ChatGPT 官方 OAuth 登录（Custom Tabs 授权 + 本地回环回调），旧的「设备码登录」已移除；升级时会自动把原订阅配置迁移成新的账号 provider（id / 名称 / 模型 / 代理 / 自定义头 / 重试 / 思考强度保留），无需重新登录。
> - **Grok 订阅**：新增 xAI 账号登录（设备码），支持 SuperGrok 订阅的模型目录与周/月额度，需要 SuperGrok 订阅才可用。
> - **额度展示**：ChatGPT / Grok 的用量支持「已用 / 剩余」两种口径切换（偏好设置 → 界面 → 用量显示）。
> - **流式重构**：四个 provider 统一 `StreamChunk` 解码路径；ChatGPT / Grok 订阅对话会锁定提供商（历史里有该 provider 生成的消息后，只能在同 provider 内换模型），避免加密推理跨提供商失效。
> - **注意**：本版升级后 **不支持降级**（数据格式 4→5）；升级前请先在「设置 → 备份」做一次备份，若需回退请卸载重装旧版并从该备份恢复。

### 17.5 收尾

- `ai/README.md` 补一节「移植说明」，记录本仓与上游 `#1648` / `#2186` 的对应关系、刻意偏离（凭据加密、`sessionId`、SSE 容错范围）与旧设备码链路已删除。
- `server-tool` 回传缺口（§16.6 最后一条）不属于本次 OAuth 移植范围，建议单开任务，不在 P5 修。

### 17.6 后续复测记录

- **SSE 断流（§16.7 遗留）**：用户于 2026-11-02 P5 期间复测「带工具调用的长回复」，**正常流式完成、收到 `response.completed`**。据此判定 P4 那次 `SSE stream failed without exception` 为代理链路（`127.0.0.1:8888`）瞬时断流，属环境问题，非代码缺陷；「无 `response.completed` 的 EOF 是否降级为可恢复错误」不作为本次修复项，留作可选增强。
- 仍待补验：有 SuperGrok 订阅账号的 Grok 全链路、`ProviderAccountManager` 并发刷新/杀进程压测（见 §16.6）。

### 17.7 并发刷新与杀进程的测试方式

**并发刷新（已自动化）**：`ProviderAccountManager` 原有的「串行 + 拿锁后重读设置」逻辑抽成
`AccountRefreshCoordinator`（`app/.../data/ai/account/AccountRefreshCoordinator.kt`），与设置存储/网络解耦，
用内存 fake 存储 + 可控 refresh lambda 做 JVM 测试（`AccountRefreshCoordinatorTest`，7 例）：

| 用例 | 断言 |
| --- | --- |
| 两路并发刷新 | 网络只请求 1 次，两路都拿到新 token（refresh token 一次性，重复刷新会打废会话） |
| token 仍有效 | 不触发刷新、不写回 |
| 401 拒绝后两路都带旧 token | 只刷新 1 次，第二路拿锁后重读设置直接复用 |
| 刷新失败（invalid_grant） | 包装成 `failed to refresh sign-in`，不写回设置 |
| 刷新期间登出 | `store` 的 expected 不匹配 ⇒ 拒绝写回并报 `sign-in changed during refresh` |
| 无 refresh token / 未登录 | 报 `session expired` / `not signed in` |

**真机并发压测（可选，验证端到端）**：

1. 让 access token 进入「快过期」窗口（≤5 分钟）或已过期：等自然过期，或 debug 包里改设置里
   ChatGPT provider 的 `credential.expiresAt`（`run-as app.androidrik.rikkahub.debug` 改 DataStore 文件较麻烦，
   更简单是断网重连/等 1 小时）。
2. 同时触发两路请求：开两个会话各发一条消息（或在同一对话里触发一次工具调用 + 主回复）。
3. 观察：两路请求都成功；logcat 里 `ProviderAccountManager` 的刷新只发生一次；若出现两次刷新，
   第二次 refresh 会 `invalid_grant`、UI 会提示重新登录（说明串行失效）。
4. 可选：临时在 `ChatGPTAuth.refreshRequest` 前后加 `Log.d`（不入库）核对只打了一条。

**杀进程（只能真机手测，无法单测）**：

1. 在刷新窗口内强制停止：`adb shell am force-stop app.androidrik.rikkahub.debug`（窗口只有几百 ms，
   若来不及，可临时 debug 版在刷新前 `delay(5s)`，验证完删掉）。
2. 重启 App 后再发消息，期望行为：
   - 若新 refresh token 已落盘（DataStore 原子写）⇒ 正常继续对话；
   - 若只完成了服务端轮换、本地还没写回 ⇒ 本地 refresh token 已失效 ⇒ 请求 `invalid_grant` ⇒ 设置页显示需重新登录，**不崩溃**、不把错误凭据继续用。
3. 检查 `data_version` 与 provider 的 `credential` 仍在（写回失败不应破坏原有数据）。

### 17.8 上游 OAuth 账号 provider 备份导入兼容性（已验证）

**结论：上游 RikkaHub 的备份可以直接导入本仓，其中的 `chatgpt` / `grok` 账号 provider 会正常落成账号类 provider**（新增 `UpstreamOAuthBackupImportTest` 4 例钉住，走的是恢复链路真实入口 `SettingsJsonMigrator.migrate` → `SettingsCompatDecoder.decode`）。

兼容依据：

| 项 | 情况 |
| --- | --- |
| 多态类型名 | 双方都是 `@SerialName("chatgpt")` / `("grok")`，`SettingsJsonMigrator` 还会把 `me.rerere.*` 根前缀改写成 `app.androidrik.*`（覆盖少见的全限定类名形态） |
| 凭据字段 | `OAuthCredential` 字段名完全一致（`accessToken`/`refreshToken`/`expiresAt`/`accountId`/`email`/`plan`） |
| 凭据加密 | 上游备份是**明文**；本仓读取时「没有 `v1:` 前缀按明文用」，因此导入后立即可用；之后任意一次刷新/登录/保存（`preserveCredential`）都会写成 `v1:` 密文 |
| 本仓额外字段 | 账号类在本仓多了 `proxy`/`retryOption`/`reasoningOverride`，上游 JSON 没有 ⇒ 取默认值（`Inherit`/`null`），不影响使用 |
| 未知字段/类型 | `ignoreUnknownKeys` 忽略字段；未知 provider 类型逐条丢弃并给出恢复警告（P3 起 `chatgpt`/`grok` 不再进丢弃名单） |
| 旧 `codexAccount` | V7 只认 `type=openai` 的旧订阅形态，上游账号类 provider 不会被误转换 |

**已知限制**：

- 导入后到首次凭据写回之前，token 以明文躺在本仓 DataStore（与上游一致，因为来源就是明文）；若要严格「落盘即加密」，可在恢复完成后统一补一次加密（当前未做，属可选增强）。
- **反向不兼容**：本仓备份 → 上游恢复时，`v1:` 密文会被上游当成不透明 token 直接发出去（必然 401），且 `data_version = 5` 的账号类数据旧版本也不认识 —— 跨仓迁移只保证「上游 → 本仓」这一方向。

### 18. 补齐 server-tool 回传与全局会话头（P6）

P5 文档 §16.6 留下的最后一条缺口，已按上游 #2186 移植：

**1）服务端工具 item 回传（`ResponseAPI`）**

- 本仓解码时早已把 Responses 的 `web_search_call` 等 item 存进 `ServerToolMetadata.call`，但发下一轮请求时 `addAssistantItems` 的 content 分支把 `UIMessagePart.ServerTool` 落进 `else -> {}` 丢弃 ⇒ Codex/ChatGPT 订阅后端（`store=false`）下模型看不到自己上一轮的检索结果，严格后端还可能 400。
- 现在 content 分支遇到 `ServerTool` 先 flush 文本、再 `addServerToolItem`：有原始 item（`protocol == OPENAI_RESPONSES`）就**原样回传**（`action`/未知字段不丢），没有原件（编辑/合成 part）就按 `type={toolName}_call` + `id/status` + `action`（web_search）或 `input` + `output` 重建，状态经 `toOpenAIStatus()` 映射为 `in_progress`/`completed`/`failed`。
- 非 Responses 协议（Claude/Google 侧的服务端工具）跳过，不污染 OpenAI 请求。
- 测试：`ResponseAPIServerToolEchoTest` 3 例（原样回传、无 metadata 重建、异协议不回传）。

**2）全局会话头（`ai/util/Request.kt` + 各 provider 调用点）**

- 新增 `configureSessionHeaders(url, sessionId)`：`X-Session-ID`；`opencode.ai` 额外 `x-opencode-session`；`sessionId == null`（无头 run）时请求逐字节不变。
- 应用到 ChatCompletions（两个 builder：generate/stream）、ResponseAPI（`toResponsesEndpoint` 的 `configureRequest`）、ClaudeProvider（generate/stream）、GoogleProvider（generate/stream）；顺序在自定义头之前，用户显式同名头仍可覆盖。
- Chat Completions 的 OpenRouter 专属 body 字段 `session_id` 一并补上。
- 测试：`SessionHeadersTest` 4 例（有/无 sessionId、opencode 私有头、ChatCompletions 真实请求 wire 断言）。

**决策与边界**

| 项 | 决策 |
| --- | --- |
| `TextGenerationParams.sessionId` 默认值 | 保持 `null`（无头 run 不变），聊天路径由 app 传 `conversationId`（`ChatService` 已有） |
| ChatGPT/Grok 私有头 | 沿用已有 `session-id` / `x-grok-conv-id`，不重复加 `X-Session-ID` |
| 真机验证 | 待做：ChatGPT 订阅账号 + 联网工具连问两轮，确认第二轮还能引用第一轮检索结果；期间观察 logcat 无 400 |

**验证**：`:oauth` 9/9、`:ai` 474（+7）、`:app` 2745（2 skipped）、`:app:assembleDebug` 通过。
