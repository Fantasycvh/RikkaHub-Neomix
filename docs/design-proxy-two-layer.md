# 两层代理设计（per-provider 为主 + 可选全局兜底）

> 状态：**已实现**（2026-09-13，3 提交 `3e3d4031`/`b45f6a9d`/`a066df81`）——**待用户真机验证**（SOCKS5/HTTP 需真实代理服务器）。
> 背景：fork 现有 per-provider `ProviderProxy`（仅 HTTP）与上游全局代理页（986b9c39，HTTP/SOCKS5）对比后确定的融合方案——保留 per-provider 为主设计，补 SOCKS5 缺口，加全局兜底层覆盖非 chat 链路。上游全局代理页本身维持「已放弃」（与本设计不冲突：不吸收其 UI/模型，只借鉴 ProxySelector/Authenticator 机制）。
> 实现备注：`RikkaProxyAuthenticator` 用**全局单例 + 多源查表**（关键点是 JVM `Authenticator.setDefault` 只允许一个，provider 覆盖同名 key），映射表随 `settingsFlowRaw` 热更新；`configureClientWithProxy` 的 `None` 分支为 `proxy(Proxy.NO_PROXY)` 显式屏蔽，`Inherit` 分支返回原 client 实例（据此判别是否走全局）。

## 一、数据模型

**1. `ProviderProxy`（ai 模块）扩展两个变体**：

```kotlin
sealed class ProviderProxy {
    object Inherit : ProviderProxy()          // 新增：跟随全局（新默认值）
    object None : ProviderProxy()             // 语义收窄：显式直连（屏蔽全局）
    data class Http(address, port, username?, password?)   // 现有
    data class Socks5(address, port, username?, password?) // 新增：补 SOCKS5 缺口
}
```

- **关键语义决策**：`None` 从「无代理」收窄为「**显式直连**」（配了全局也不走）；「未配置/跟随」由新的 `Inherit` 表达并作为**新 provider 的默认值**。
- **向后兼容**：旧 JSON 里显式写的 `"none"` 反序列化仍为 `None`（直连）——已存在的 provider 行为零变化（不会因配了全局而突然走代理）；只有新建 provider 或手动改为「跟随全局」才继承。
- `Http` 认证启用条件顺带修正：username 非空即启用（对齐上游；现要求用户名密码都非空）。

**2. 全局设置（app 模块 `Settings` 顶层）**：

```kotlin
@Serializable data class GlobalProxySetting(
    val type: ProxyType = NONE,        // NONE / HTTP / SOCKS5
    val host: String = "", val port: Int = 0,
    val username: String = "", val password: String = "",
)
```

结构化字段（与 per-provider UI 组件可复用）。**必须补 PreferencesStore 三件套**（key/read/write——漏了 UI 能选重启即丢，见 settings-persistence-trio 教训）。

## 二、应用层（核心解耦：ai 模块零依赖 SettingsStore）

**共享 OkHttpClient 装「全局层」**（`DataSourceModule`，借鉴上游 `SettingsProxySelector` 模式）：
- `SettingsProxySelector`：全局 type≠NONE → 返回全局代理；否则回落系统 ProxySelector（显式尊重 Android 系统代理）；
- `SettingsProxyAuthenticator`（HTTP Basic）+ 设置变化监听热切换。

**`configureClientWithProxy` 语义矩阵**（关键——三层返回值天然解耦）：

| `provider.proxy` | 行为 | 实现 |
|---|---|---|
| `Inherit`（新默认） | **返回原 client**（不配置）→ DI 共享 client 的全局 ProxySelector 自然生效 | `this` |
| `None` | 显式直连（**屏蔽**全局） | `proxy(Proxy.NO_PROXY)` |
| `Http` / `Socks5` | 自身配置优先 | `newBuilder().proxy(...)` |

ai 模块完全不知道全局设置的存在（Inherit = 什么都不做），全局层只活在 app 的 DI client 上——不需要把 SettingsStore 传进 ai 模块，现有三 provider 的所有调用点（含流式 EventSources）一行都不用改。

## 三、SOCKS5 认证（唯一技术难点）

OkHttp 的 `proxyAuthenticator` 不覆盖 SOCKS（SOCKS 认证在 socket 层）——必须用 JVM `java.net.Authenticator`（全局单例）。要同时支持「全局 SOCKS5」+「若干 provider 各自的 SOCKS5」：

**方案**：app 启动时注册唯一一个 `RikkaProxyAuthenticator extends java.net.Authenticator`，内部维护 `host:port → credentials` 映射表（来源 = 全局设置 + 所有 provider 的 Socks5 配置），settings 变化时刷新映射；`getPasswordAuthentication()` 按 `requestingHost/requestingPort` 精确匹配返回（上游 `SettingsSocks5Authenticator` 的匹配逻辑扩展成多源查表）。

## 四、覆盖面

| 链路 | 代理来源 |
|---|---|
| AI chat（OpenAI×2/Claude/Google，含流式） | per-provider（Inherit→全局；None→直连；Http/Socks5→自身） |
| 余额查询（`OpenAIProvider.getBalance`，New API 管理接口） | per-provider（同 chat，走 `configureClientWithProxy` + 共享 client） |
| 搜索（`SearchService.init`）、ASR（`koinInject<OkHttpClient>`） | **只看全局层**（走 DI 共享 client） |
| TTS、MCP（`McpManager` 自建 OkHttpClient） | **不受影响**（各自 new OkHttpClient，不挂 DI 层） |
| S3 / WebDAV 备份 | **不受影响**（独立 Ktor client） |

> 2026-09-13 盘点结论（原「实现第一步盘点」已落地）：真正复用 DI 共享 client 的非 chat 链路只有**搜索**与 **ASR**，全局层对这两条生效；余额查询虽在非 chat 语义，但实现上仍走 `OpenAIProvider` 的 per-provider 路径（跟随该 provider 的设置）。TTS/MCP/S3/WebDAV 均自建 client，全局代理对其无效。UI 已就此加一句覆盖面说明文案，避免用户误解。

## 五、UI

1. **新「网络」设置页**（设置→偏好→网络）：全局代理（类型 NONE/HTTP/SOCKS5 + host/port/user/pass + 密码可见性）+ 可选「测试代理」按钮；
2. **提供商编辑页代理 tab**：选项扩为「**跟随全局（默认）**/无/HTTP/SOCKS5」，结构化字段组件复用；
3. 文案 6 语言。

## 六、行为矩阵（用户视角）

| provider 配置 | 全局=NONE | 全局=HTTP/SOCKS5 |
|---|---|---|
| 跟随全局（默认） | 直连 | 走全局 |
| 无 | 直连 | **直连**（显式屏蔽） |
| 自配 HTTP/SOCKS5 | 走自身 | 走自身（自身优先） |

非 chat 链路只看全局（全局 NONE → 现状不变）。

## 七、实现拆分（3 个提交）

1. ~~**ai 模块**：`ProviderProxy` +Inherit/Socks5 + Http 认证条件修正；`ProxyUtils` 矩阵化（Inherit→this / None→NO_PROXY）+ 单测（纯 JVM 可测）~~ → `3e3d4031`（8 项单测）
2. ~~**app 全局层**：`GlobalProxySetting` + PreferencesStore 三件套 + DI 共享 client（ProxySelector/Authenticator/热切换）+ JVM `RikkaProxyAuthenticator` 映射表~~ → `b45f6a9d`（`ProxyEndpoints`/`SettingsProxy` + 10 项单测）
3. ~~**UI + 文案**：网络设置页 + 提供商代理 tab 扩选项 + 6 语言~~ → `a066df81`（真机渲染已验证）

## 八、风险与核查项

- ~~① speech/MCP/搜索是否用 DI 共享 client~~ → **已盘点（见 §四）**：仅搜索/ASR 走共享 client；
- ② JVM Authenticator 单例与其它库冲突（低概率，注册时保留旧引用链）——实现时用 `runCatching` 包裹 `setDefault`，未保留旧引用链（如将来有第三库注册需补）；
- ③ SOCKS5 真机验证需真实 SOCKS5 服务器——**待用户真机验证**；
- ④ ProviderConnectionTester 入口可发现性差（纯图标无标签/contentDescription=null）——**未处理**，可后续在网络相关 UI 改造时顺手补。

**成本**：B 类中（约 1-2 小时/提交）。
