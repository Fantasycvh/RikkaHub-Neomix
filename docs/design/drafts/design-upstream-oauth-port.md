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
