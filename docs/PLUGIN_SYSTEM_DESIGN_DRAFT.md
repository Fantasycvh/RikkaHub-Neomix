# RikkaHub 插件系统设计(借鉴 BiliPai)—— 草稿

> 状态:**草稿,未提交**。日期:2026-10-22。
> 调研对象:BiliPai(`F:\build\projects\BiliPai`)插件体系 × RikkaHub-Neomix 可扩展性现状。

---

## 一、BiliPai 插件体系核心设计(值得借鉴)

### 1.1 五层插件形态(按门槛递进)

| 形态 | 难度 | 执行方式 |
| --- | --- | --- |
| JSON 规则插件(`.json`/`.bp`) | ⭐ | 宿主 RuleEngine 解释执行,URL 导入 |
| JS 单文件插件(`.bilipai.js`) | ⭐⭐ | WebView 沙箱,每次调用新建、跑完即毁、15s 超时 |
| 皮肤包(`.bpskin`) | ⭐ | 纯数据资源包,不执行代码 |
| 外部 Kotlin 包(`.bpplugin`) | ⭐⭐ | 仅解析+签名校验+授权记录,**宿主不执行外部 Dex**(预览阶段) |
| 源码级原生插件 | ⭐⭐⭐ | 编译进 APK,Application 中注册 |

### 1.2 统一插件内核

- `Plugin` 基础接口:仅元数据(id/name/version/author/icon)+ `onEnable()/onDisable()` + `@Composable SettingsContent()` 配置页 + `unavailable/unavailableReason` 灰度字段。
  - 位置:`app/src/main/java/com/android/purebilibili/core/plugin/Plugin.kt`
- `PluginManager` 单例:StateFlow 注册表(`pluginsFlow`),`getEnabledPlugins<T>()` 按类型分发;启用状态经 `PluginStore`(DataStore)持久化;处理"未注册先切换开关"的 pending override。
  - 位置:`core/plugin/PluginManager.kt`

### 1.3 能力声明与授权(最值得抄的部分)

- `PluginCapabilityManifest`:pluginId / displayName / version / apiVersion / entryClassName / capabilities 集合。
- 授权计算:`granted = manifest.capabilities ∩ userApprovedCapabilities`,敏感能力即使可信签名也要用户显式批准。
- **授权记录绑定 `pluginId + 包 SHA-256`**,包内容一变即要求重新授权。
- 能力最小申请原则写入 SDK 文档。
  - 位置:`core/plugin/PluginCapabilityManifest.kt`、`plugins/sdk/README.md`

### 1.4 授权三层实现(补充细节)

- **安装期**:`ExternalPluginInstallPolicy` 校验 apiVersion 白名单、SHA-256 非空、entryClassName 非空,返回 `RequiresUserApproval`(含敏感能力交集);`resolvePluginCapabilityGrants` 取 `声明 ∩ 用户批准`。
- **存储期**:`ExternalKotlinPluginInstallStore.installPreview` 再次校验哈希一致,授权记录绑定 `pluginId + packageSha256`。
- **运行期**:如 `BiliPaiJsRuntime.loadDanmuComments` 显式检查 `DANMAKU_STREAM in grantedCapabilities`,否则抛 SecurityException。
- 能力共 12 种:`PLAYER_STATE/CONTROL`、`DANMAKU_STREAM/MUTATION`、`PLAYBACK_CDN`、`RECOMMENDATION_CANDIDATES`、`LOCAL_HISTORY_READ`、`LOCAL_FEEDBACK_READ`、`NETWORK`、`PLUGIN_STORAGE`、`EXTERNAL_MEDIA_PLAYBACK`、`FEED_SOURCE`。

### 1.5 安全实现细节(可复用)

- **包解析防护**:`ExternalKotlinPluginPackageReader` 限 256 条目、manifest 64KB、总解压 16MB,防重复路径与 zip bomb;皮肤包限 32MB。
- **JS 沙箱**:禁 DOM storage / file / content access / 地理定位;无 fetch/XHR/localStorage,网络强制走宿主 HttpBridge;存储桥按 pluginId 隔离目录且文件名 sanitize;base URL 为虚构域名;单次执行限时销毁。
- **签名只影响来源可信展示,绝不跳过授权** —— 这一点设计正确,必须沿用。
- **Package 限额**:`.bpplugin` 为 ZIP(manifest + 可选签名 + 可选载荷)。

### 1.6 其他设计决策

- **hook 面窄而清晰**:PlayerPlugin(onVideoLoad/onPositionUpdate 每 500ms→SkipAction)、FeedPlugin(shouldShowItem)、DanmakuPlugin(filter/style,变更触发 `danmakuPluginUpdateToken` 热刷新正在播放的弹幕)。
- **聚合门面**:`filterFeedItems()` 把 FeedPlugin.all{shouldShowItem} 与 FeedTransformPlugin.fold 管道合并,宿主只调一个入口。
- **SDK 模块硬隔离**:`plugin-sdk` 只暴露数据结构与接口,不暴露 Compose、Android Context、账号凭证、宿主仓库、内部单例。
- **干跑测试**:JSON 插件有 `testPluginRules` 可在插件中心试跑到真实数据上验证。

### 1.7 已知缺陷(借鉴时**必须规避**)

| 缺陷 | 说明 | 我们怎么避 |
| --- | --- | --- |
| **五套管理体系割裂** | PluginManager / JsonPluginManager / JS 安装存储 / Kotlin 包存储 / 皮肤存储各自为政,插件中心要逐个适配 | 只做**一个**注册表 + 一个存储,所有形态收敛到同一 `PluginInfo` |
| **JSON 插件无能力授权** | 导入即用,任何 URL 都可导 feed/danmaku 规则 | 第二期规则插件**必须**走能力声明 + 用户批准,不能因为"是数据"就免审 |
| **RuleEngine 字段硬编码** | `getVideoFieldValue` 用 when 白名单,模型加字段须同步改引擎 | 用路径表达式(如 `owner.name`)而非硬编码 when;字段白名单以数据配置 |
| **JS 每次 new WebView** | 主线程 `Main.immediate` 开销大,无进程池/复用;15s 超时对慢源偏紧 | 若做第三期,改成复用池 + 可配超时 |
| **HttpBridge 无出站限制** | JavascriptInterface 线程同步 `execute()`,无并发限制、无域名/协议黑名单(可访问内网,有 SSRF 面) | JS 网络桥必须:异步 + 并发上限 + 协议仅 https + 私网地址黑名单 |
| **运行期能力校验不足** | 仅 DANMAKU_STREAM 一处强制校验,`NETWORK`/`EXTERNAL_MEDIA_PLAYBACK` 实际未门禁 | 每个能力都在**调用点**校验,不只靠安装期 |
| **register 异步导致早查漏插件** | `PluginManager.register` 是 `launch`,初始化早期查询可能为空 | 提供 `awaitReady()` 语义,或初始化完成前阻塞查询 |
| **外部 Dex 永不执行** | SDK 接口无真实运行验证,API 稳定性存疑 | 我们第一期就走"真跑"的原生插件,不留空壳接口 |

---

## 二、RikkaHub-Neomix 可扩展性现状

> 注:本 fork 包名为 `app.androidrik.*`。项目**无任何通用插件/动态扩展机制**,但多处为"面向接口 + 集中注册"写法,改造性价比分层如下。

### 2.1 天然插件式(只需改装配为注册表/Koin getAll)

| 扩展点 | 现状 | 位置 |
| --- | --- | --- |
| Message Transformer 管线 | 接口完整(Input/Output,Output 支持 `visualTransform()` 流式渲染与 `onGenerationFinish()`),15+ object 实现;装配在 ChatService 约 L1207/L1417 用 `buildList` 手工拼接 | `data/ai/transformers/Transformer.kt`、`service/ChatService.kt` |
| 工具 UI | `ToolUIRegistry`(object,renderers map 硬编码 listOf)+ `ToolUIRenderer` 接口,未注册工具走默认渲染 fallback | `ui/components/message/tools/ToolUI.kt` |
| 工具装配 | `ChatToolFactory` 是唯一装配点,按 ToolSegment 分段(memory/search/local/workspace/skill/subagent/mcp),注释明说"新增装配方只需改这里" | `data/ai/tools/ChatToolFactory.kt` |
| MCP | 已开放的外部工具通道,最现实的第三方扩展先例 | `data/ai/mcp/McpManager.kt` |
| JS 搜索先例 | `CustomJsSearchService` 即"用户用 JS 定义搜索 provider"的准插件先例 | search 模块 |

### 2.2 半插件式(有注册 Map 但入口硬编码)

- AI Provider:`ProviderManager` 有 `registerProvider()` 注册 Map,但 init 硬编码注册 openai/google/claude;`getProviderByType` 仍为 when 密封类分发。
  - 位置:`ai/src/main/java/app/androidrik/ai/provider/ProviderManager.kt`

### 2.3 需较大改造(when 密封类分发遍布)

- **search**:`SearchService.kt` 单文件集中了 getService when 分发、Options 密封类、TYPES 显示名 map、withEnabled/withNextKey 两个逐子类 copy 的 when(19 个实现)。方向:注册表(id→factory)+ Options 泛型化。
- **speech(最硬编码)**:`TTSManager` 直接 new 出 11 个 provider 字段,generateSpeech / getPromptGuidance 两处 when 分发;ASR 侧 ASRController 类似。

### 2.4 插件化难点(需提前规避)

- **设置页 UI**:`Screen` 密封路由 + `SettingPage.kt` 硬编码入口清单,无声明式页面机制 → 插件配置页短期走"插件详情页内嵌 Composable"(BiliPai `SettingsContent()` 模式),不动路由。
- **Room**:AppDatabase version 47,23 个 entity 编译期注册 + AutoMigration 链,插件不可能动态建表 → 第三方数据短期走 workspace 沙箱文件系统,或"单 key JSON 序列化进 DataStore"模式(项目已大量使用,见 `PreferencesStore.kt` 集中 Keys)。

---

## 三、借鉴方案(三期)

### 第一期:插件内核 + 原生插件(收益最快)

1. **新建 `plugin-api` 模块**(对标 BiliPai plugin-sdk):
   - `RikkaPlugin` 接口:id / name / version / author / icon / `onEnable()` / `onDisable()` / `@Composable SettingsContent()` / `unavailable` / `unavailableReason` / `capabilityManifest`。
   - `PluginCapabilityManifest` + 能力枚举(AI 助手语境):`NETWORK`、`HISTORY_READ`、`CONVERSATION_MUTATE`、`TOOL_REGISTER`、`PROMPT_TRANSFORM`、`OUTPUT_TRANSFORM`、`STORAGE`。
     (比初稿多一个 `OUTPUT_TRANSFORM`:输出管线与输入管线是两套接口,合并成一个能力会让授权粒度失真。)
   - 硬约束:不暴露 Context、凭证、宿主仓库、内部单例。
2. **新建 `plugin-manager`**:StateFlow 注册表 + Koin 装配,`getEnabledPlugins<T>()`;启用状态持久化(单 key JSON 进现有 PreferencesStore)。
3. **Transformer 管线接入**:ChatService 两处 buildList → 内置 transformer + `pluginManager.getEnabledPlugins()` 合并输出。
4. **ToolUIRegistry 开放注册**:renderers map 支持插件追加(保留默认渲染 fallback)。
5. **ChatToolFactory 新增 ToolSegment.Plugin**:插件注册工具须经能力校验(`TOOL_REGISTER`)。

### 第二期:声明式规则插件(对标 BiliPai JSON 插件)

- 面向非程序员:JSON 规则 = prompt 改写 / 输出过滤 / 快捷指令。
- Schema 参考 BiliPai RuleEngine:field/op/value + and/or 嵌套 + hide/replace/highlight 动作;URL 导入 + 安装时能力声明展示。
- BiliPai 的 JSON 插件文档:`docs/PLUGIN_DEVELOPMENT.md`;示例:`plugins/samples/*.json`。

### 第三期:JS / 外部包(缓行或缩水)

- BiliPai JS 沙箱设计成熟(每次新建 WebView、15s 超时、无 fetch/XHR 只走宿主 `http` API、单文件、无模块级状态),若做"自定义 AI 内容源/工具源"可整套借用。
- 外部 Dex 执行:BiliPai 自己也只做到"解析+授权+不执行"——我们不碰。

### 关键教训(直接采纳)

1. 能力授权绑定包哈希;最小申请原则写进 SDK 文档;**每个能力在调用点校验**,不能只靠安装期一次。
2. **单一注册表**:不要学 BiliPai 五套管理并存,所有插件形态收敛到一个 `PluginInfo` + 一种安装存储。
3. hook 面从窄开始(第一期只做 Transformer + 工具注册两个),不铺大面。
4. SDK 模块化硬隔离,不暴露宿主内部。
5. 保留 `unavailable`/`unavailableReason` 灰度字段。
6. **只做会真跑的东西**:不做"能解析能授权但不执行"的空壳形态(BiliPai 的外部 Dex 至今无运行验证)。
7. 提供初始化就绪语义(`awaitReady()`),不让宿主在注册完成前查到空列表。

### BiliPai 的 JSON 插件 Schema(第二期直接参考)

条件树递归:`Simple(field/op/value)` / `And` / `Or` 可任意嵌套;11 种操作符 eq/ne/lt/le/gt/ge/contains/startsWith/endsWith/regex/in;动作 hide/highlight(highlight 带 style:color/bold/scale)。
> 但他们**没有 replace 动作**;我们做 AI 输出处理时需要补 `replace`(正则替换文本),这是 RikkaHub 场景比 BiliPai 更需要的能力。

---

## 四、第一期文件级落地清单

| # | 改动 | 类型 | 量级 |
| --- | --- | --- | --- |
| 1 | `plugin-api/`(RikkaPlugin + CapabilityManifest) | 新建模块 | 中 |
| 2 | `plugin-manager/`(注册表 + Koin + 持久化) | 新建模块 | 中 |
| 3 | ChatService transformer 装配改为 内置+插件合并 | 修改 | 小 |
| 4 | ToolUIRegistry 支持插件注册 renderer | 修改 | 小 |
| 5 | ChatToolFactory 增加 Plugin segment + 能力校验 | 修改 | 中 |
| 6 | 插件中心设置页(复用 SettingProviderPage 布局模式,插件配置页内嵌详情页) | 新增 UI | 中 |

search/TTS 的 when 密封类改造、设置页声明式机制 → 放二期之后,不阻塞内核落地。

---

## 五、第一期实现状态(2026-10-22,已落地)

> 状态:**代码已实现,编译与单测通过;草稿,未提交**。

### 5.1 新增模块

| 模块 | 内容 |
| --- | --- |
| `plugin-api/` | `RikkaPlugin`(元数据 + `enabledByDefault` + `capabilities` + `onEnable/onDisable` + `SettingsContent()`)、`PluginCapability`(7 种)、`PluginCapabilityManifest`、`PLUGIN_API_VERSION` |
| `plugin-manager/` | `PluginManager`(StateFlow 注册表 + `awaitReady` 解决注册/加载竞态 + 能力授予交集 + 生命周期观察者)、`PluginState`/`PluginStateStore`(持久化接口)、`PluginInfo`、7 个 JVM 单测 |

### 5.2 app 侧接入

| 接入点 | 改动 |
| --- | --- |
| 契约 | `data/plugin/PluginContracts.kt`:`PluginInputTransformer` / `PluginOutputTransformer` / `PluginToolProvider` / `PluginToolUIRendererProvider` |
| 持久化 | `data/plugin/DataStorePluginStateStore.kt`:独立 `plugin_states` DataStore,单 key JSON(不污染主设置) |
| 内置插件 | `data/plugin/BuiltInPlugins.kt`:清单 + 安装函数 + 示例插件 `WhitespaceTrimPlugin`(默认关闭,验证输出管线) |
| 转换管线 | `ChatService.buildInputTransformerChain()`(内置→模板→工作区提醒→**插件**→工具配平);`outputTransformers + pluginOutputTransformers()` |
| 工具装配 | `ChatToolFactory.createPluginToolSegments()`:段名 `plugin:<id>`,门槛 = 启用 + `TOOL_REGISTER` |
| 工具 UI | `ToolUIRegistry.register/unregister` + 版本号;`ChatMessageTools` 以版本号为 remember key |
| 插件中心 | `ui/pages/plugins/`:列表(开关 + 能力摘要)、详情(元信息 + 能力授权状态 + 内嵌 `SettingsContent()`);路由 `Screen.Plugins` / `Screen.PluginDetail`;入口在扩展页 |
| 启动 | `RikkaHubApp.onCreate`:`manager.start()` + `installBuiltInPlugins`;Koin 单例在 `AppModule` |

### 5.3 与初稿的偏差(有意)

1. **能力多一个 `OUTPUT_TRANSFORM`**:输入/输出是两套接口,授权应分开。
2. **插件状态用独立 DataStore**,不进 `PreferencesStore`:插件状态坏掉不应影响主设置;也不去动那份 1500 行文件的 Keys。
3. **新增 `enabledByDefault`**:示例插件默认关闭,避免"装上就改变行为"。
4. **内置插件默认识别全部声明能力**(源码级、编译期可信),用户可撤销;有状态记录后按 `声明 ∩ 批准`。

### 5.4 尚未覆盖(二期已完成,保留原始清单作对照)

- ~~JSON 规则插件(含 `replace` 动作与能力审批)~~ → 二期已实现,见 §六;
- 插件能力的 UI 撤销入口(当前只展示授权状态);
- 外部包/JS 沙箱(三期,且必须带出站限制);
- search/TTS 的 when 密封类改造。

### 5.5 真机验证记录(2026-10-22,PJX110 / debug 包)

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| 插件中心渲染 | 设置 → 扩展管理 → 插件 | 列表、能力标签「改写输出」、默认关闭均正确 |
| 开关持久化 | 开 → `force-stop` → 冷启动 | 仍为开;`plugin_states.preferences_pb` 中 `"enabled":true` |
| 反向持久化 | 关 → 冷启动 | 仍为关;文件 `"enabled":false` |
| 详情页 | 点卡片 | 版本/作者/id、启用状态、能力「已授权」正确 |
| **输出管线生效** | 开启插件,提示模型"每行末尾保留两个空格" | logcat:`whitespace trim: messages=2 removed=6 chars`;数据库消息文本**无行尾空格**(3 行 × 2 空格 = 6) |
| **关闭即失效** | 关闭插件,同样提示再发一次 | 无 trim 日志;数据库消息文本**保留行尾空格**(`'Line one ends with two spaces  '`) |
| 逻辑单测 | `WhitespaceTrimPluginTest` | 4 个用例覆盖逐行 trim / 非 assistant 不动 / 计数 / 多 part |

> 上表最后两行是"插件确实接在输出管线上"的**正反双向证据**:同一模型、同一提示,插件开→裁剪,插件关→保留。

### 5.6 已知偏差补充

- `WhitespaceTrimPlugin` 增加了一行 `Log.d`(生成结束时的裁剪计数),用于真机可观测性;量级为每次生成 1 行。

---

## 六、第二期实现状态:JSON 规则插件(2026-10-22,已落地)

### 6.1 目标

对标 BiliPai 的 JSON 规则插件(纯数据、可分享),但补掉它的三个缺陷:
无授权、多套管理器、字段硬编码。让非 Kotlin 用户也能写"输出替换 / 追加"类插件。

### 6.2 文件级落地

| 文件 | 职责 |
| --- | --- |
| `app/.../data/plugin/rules/JsonRuleDefinition.kt` | 定义数据模型 + 安装前校验(含 `validate()`) |
| `app/.../data/plugin/rules/RuleEngine.kt` | 纯函数求值:`matches()` 条件树 / `apply()` 动作 |
| `app/.../data/plugin/rules/JsonRulePlugin.kt` | 物化:定义 → `RikkaPlugin` + 输入/输出转换器 |
| `app/.../data/plugin/rules/JsonRulePluginStore.kt` | 定义持久化(独立 `plugin_rules` DataStore) |
| `app/.../data/plugin/rules/RulePluginInstaller.kt` | URL 抓取 / 解析 / 注册 / 卸载 / 启动恢复 |
| `plugin-manager/.../PluginManager.kt` | 新增 `unregister()`(动态插件卸载) |
| `ui/pages/plugins/PluginsPage.kt` | 规则插件导入入口 + URL/粘贴对话框 + 能力确认对话框 |
| `ui/pages/plugins/PluginDetailPage.kt` | 规则列表、删除插件入口 |
| `docs/samples/plugin-rules/` | 3 个样例 JSON + 格式说明 |
| `app/src/test/.../RuleEngineTest.kt` | 9 个 JVM 用例(条件树/操作符/动作/scope/校验) |

### 6.3 与 BiliPai JSON 插件的差异(有意的)

| 点 | BiliPai | 本实现 |
| --- | --- | --- |
| 授权 | JSON 插件免授权 | 导入时**必须**确认能力(`PROMPT_TRANSFORM`/`OUTPUT_TRANSFORM`),批准集合落盘 |
| 存储 | 独立 `JsonPluginManager` + 独立 SharedPreferences | 物化为标准 `RikkaPlugin`,复用同一 `PluginManager` 与状态存储 |
| 字段 | `when(field)` 硬编码 | 引擎按**路径键**求值,宿主摊平上下文;新增字段不改引擎 |
| 动作 | hide/replace/highlight | `replace`(正则+分组)/ `append` / `prepend` |
| 作用范围 | 全列表过滤 | `scope: last`(默认)/ `all`,避免 `append` 在历史消息上反复叠加 |
| id | 无约束 | 小写+长度校验,禁用 `builtin.` 保留前缀 |
| 容错 | — | 坏正则原样返回;坏定义跳过不拖垮列表 |

### 6.4 真机验证记录(2026-10-22,同机同包)

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| URL 导入 | `adb reverse` + 本地静态服务,插件中心 → 导入 → 填链接 → 解析 | 拉取成功并弹出能力确认(作用范围/规则数/申请能力) |
| 安装即授权 | 批准并安装 | `plugin_states` 出现 `"approvedCapabilities":["OUTPUT_TRANSFORM"]`;定义写入 `plugin_rules` DataStore |
| 冷启动恢复 | `am force-stop` + 重启 | 规则插件自动物化回列表(与内置插件同一套开关) |
| **输出侧生效** | 插件规则"删除含 `AD:` 的整行",提示模型输出四行(第二行为 `AD: ...`) | logcat:`rule plugin tmp... matched=1`;UI 与**数据库持久化文本**都只剩 3 行,`AD:` 行消失 |
| **输入侧生效** | 插件规则"追加提醒",提示模型逐字回显 | logcat:`matched=1`(仅最新一条);模型回显里带出追加文本,证明文本在**发往模型前**被改写 |
| scope 修正 | 首版对全部用户消息追加(命中 2),历史会被反复追加 | 增加 `scope` 字段,默认 `last`;复测 `matched=1` |
| 删除插件 | 详情页 → 删除 → 确认 | logcat `uninstalled rule plugin`;`plugin_rules` 清空;返回列表 |
| 删除取消缺陷 | 首版用 Compose 作用域卸载,注销后页面重组导致持久化被取消(定义未删) | 改用应用作用域 + "先落盘后注册/注销"顺序,复测通过 |
| 单测 | `RuleEngineTest` 9 用例 | 条件树 / 数值比较 / regex / in / 三动作 / scope / 校验 全绿 |

### 6.5 已知限制(二期)

- 规则插件只有单层"目标 + 规则列表",没有"快捷指令 / 触发器"这类交互式形态;
- 能力只有查看,没有 UI 撤销入口(与第一期同);
- URL 导入只支持 http/https 直连,不支持鉴权头/重定向白名单;
- 规则只作用于文本 part,不处理图片/文档 part;
- 规则在**流式展示**上不生效(仅 `onGenerationFinish` 生效),避免正则逐 token 抖动——与内置插件行为一致。

### 6.6 下一期候选

1. 插件能力撤销 UI + 安装详情的"来源"标注(区分内置/规则/未来外部包);
2. 声明式"工具插件"(规则型 `tool` 定义 → 自动装配 `ChatToolFactory`),复用 `TOOL_REGISTER`;
3. 规则插件支持 `set`(替换整个文本)/ `drop`(整条消息)与导入时的 dry-run 预览;
4. 外部包/JS 沙箱前,先把出站请求收口到统一 `NetworkGateway`(次数/域名/超时)。

---

## 七、第三期实现状态:JS 沙箱插件 + 受控出站(2026-10-22,已落地)

### 7.1 目标与取舍

对标 BiliPai 的 `.bilipai.js`(单文件、WebView 沙箱、无 fetch/XHR),但**不照搬**它的安全缺口:
- 插件出站只能走宿主 `rikka.http`,宿主侧做**域名白名单 + SSRF 拦截**;
- 网络能力在**安装时逐项勾选**(可只装不用网络),并在**每次调用点**校验,撤销后立即失效;
- 沙箱每次求值新建 WebView,超时直接销毁(不学 BiliPai 只靠超时后继续复用状态);
- 外部 Dex/"只解析不执行"的形态**不做**(遵循"只做会真跑的东西")。

### 7.2 文件级落地

| 文件 | 职责 |
| --- | --- |
| `data/plugin/js/JsPluginDefinition.kt` | `.js` 清单头解析(`@rikka-plugin`) + 校验 + 能力推导 |
| `data/plugin/js/JsPluginRuntime.kt` | WebView 沙箱:外壳 HTML、`RikkaHost` 桥、超时销毁、JS↔Kotlin 异步桥 |
| `data/plugin/js/JsPlugin.kt` | 物化为标准 `RikkaPlugin`(输入/输出转换,错误保留原文) |
| `data/plugin/js/JsPluginStore.kt` | 定义持久化(独立 `js_plugins` DataStore) |
| `data/plugin/js/JsPluginInstaller.kt` | URL 抓取 / 解析 / 注册 / 卸载 / 冷启动恢复 |
| `data/plugin/net/PluginHttpGateway.kt` | 受控出站:scheme/方法/端口白名单、DNS 复核、手动重定向、体积/超时上限 |
| `data/plugin/net/UrlPolicy.kt` | 主机白名单(精确/`*.` 通配)+ 内网/保留地址黑名单 |
| `data/plugin/net/PluginHttpModels.kt` | `PluginHttpRequest` / `PluginHttpResponse` |
| `ui/pages/plugins/PluginsPage.kt` | JS 导入入口 + **能力逐项勾选**(网络可选)对话框 |
| `ui/pages/plugins/PluginDetailPage.kt` | 清单信息、源码预览、删除 |
| `docs/samples/js-plugins/` | 3 个样例 + 运行时契约文档 |
| 单测 | `JsPluginManifestTest`(7)、`UrlPolicyTest`(20)、`PluginHttpGatewayTest`(18) |

### 7.3 沙箱契约(给插件作者)

- 钩子:`onInput({ text, ctx })` / `onOutput({ text, ctx })`,只处理**最新一条**目标角色消息;
- 返回字符串或 `{ text }`;`null` = 不改动;抛错/超时 = 保留原文(绝不打断对话);
- `rikka.log(msg)`、`rikka.http(req)`;`rikka.http` 返回 `{ ok, status, body, headers }`(`ok` = HTTP 2xx);
- 沙箱内**没有** `fetch`/`XHR`/`WebSocket`/`localStorage`,不能导航、不能读 file/content URI;
- 每次求值重建 WebView ⇒ **状态不跨调用**,需要状态就每次自己算。

### 7.4 真机验证记录(2026-10-22,同机同包)

| 场景 | 操作 | 结果 |
| --- | --- | --- |
| URL 导入 + 解析 | 导入 `uppercase-output.js` | 清单解析出"作用范围: 模型输出 · 钩子: onOutput";能力框只列"改写输出" |
| 输出改写 | 提示模型"说三个词" | UI 与**数据库持久化文本**均为 `HELLO WORLD!`;logcat `applied 13->13 chars`(大写不改变长度) |
| 能力勾选 | 含 `networkHosts` 的插件 | 能力框出现"网络"且**可取消勾选**;变换类能力锁定不可取消 |
| 网络放行 | 勾选网络,`api.github.com/zen` | logcat `--> GET https://api.github.com/zen` `<-- 200`;输出末尾追加 Zen 文本(`applied 3->47 chars`) |
| **网络拦截(未授权)** | 安装时不勾选网络,再调用 `rikka.http` | 运行期立即拒绝:`[http:{"ok":false,"error":"未授权 NETWORK 能力"}]`,logcat 记录 denied |
| **SSRF 端口拦截** | 声明 `127.0.0.1`,请求 `:8080` | `端口 8080 不允许(只允许 80/443/8443)` |
| **SSRF 主机拦截** | 声明 `127.0.0.1`,请求 `:80` | `host '127.0.0.1' 指向内网/保留地址,已拒绝` |
| **超时中止** | 死循环脚本,`timeoutMs: 300` | logcat `hook onOutput timed out`;原文保留;后续插件继续执行、对话正常完成、界面无 ANR |
| 冷启动恢复 | `force-stop` + 重启 | JS 插件自动物化(`js_plugins` DataStore → 注册表) |
| 删除插件 | 详情页删除 | `uninstalled js plugin`,定义从 `js_plugins` 消失,返回列表 |
| 单测 | 7 + 20 + 18 例 | 清单校验 / URL 策略 / 网关(离线伪造响应与重定向)全绿 |

> 关键结论:同一份 JS,能力"给/不给"决定了 `rikka.http` 的可用性;SSRF 的端口与主机两道拦截都在真机日志里可见。

### 7.5 实测踩到并修掉的问题

1. **能力勾选反了**:首版把 `NETWORK` 设为不可勾选,导致"可选网络"实际无法取消 → 改为"变换能力锁定、网络可勾选";
2. **安装校验过严**:首版要求批准全部声明能力,导致"不勾网络"直接装不上 → 改为"必需能力必须批准、`NETWORK` 可选"(与运行期校验配合);
3. **`ok` 语义含糊**:首版只要传输成功就 `ok:true`,415 也被当成成功 → 改为 `ok = HTTP 2xx`,非 2xx 同时带回 `status`/`body`/`error`;
4. **清单头把编译器骗了**:KDoc 里写 `/*` 会触发 Kotlin 块注释嵌套,导致"Unclosed comment" → 文档改为不含注释定界符的写法;
5. **超时与加载混算**:首版把 WebView 冷启动算进 1.5s 脚本超时,首次求值易误杀 → 加载单独 5s,超时只罩脚本执行;
6. **Koin 类型擦除**:`PluginHttpGateway(callFactory = get())` 会按 `Call.Factory` 查找而项目只注册了 `OkHttpClient`(编译期发现不了)→ 改为 `get<OkHttpClient>()`。

### 7.6 已知限制(三期)

- 三期初始只有文本改写钩子；工具注册已在第九节补齐，内容源形态仍未提供;
- 能力撤销入口已在第三期后补齐，见第八节；运行中的请求不会被追溯取消;
- 网络请求不允许自定义 `Host`/`Cookie`/`Authorization`(避免把宿主凭证带出去);
- 沙箱状态不跨调用(有意),每次冷启动 WebView 有几十~几百 ms 开销;
- 异步桥超时依赖 `destroy()` 回收渲染进程:极端情况下(设备主线程被长任务占满)回收会延迟,期间该插件表现为"本次未生效";
- 流式展示不应用 JS 改写(仅生成结束),避免逐 token 执行脚本。

### 7.7 后续候选

1. 插件能力撤销 UI 已补齐（见第八节，覆盖内置/规则/JS）;
2. JSON 规则插件支持 `set`/`drop` 与导入 dry-run 预览;
3. `rikka.store` 已补齐（见第九节，插件隔离 KV + `STORAGE` 调用点检查）;
4. 把网关的域名白名单做成可查看的"网络访问记录"(次数/域名),让用户能审计;
5. JS 单文件清单声明式工具已补齐（见第九节）；独立 JSON 工具执行语言仍为后续候选。

## 八、第三期后补齐：能力授权与撤销 UI（2026-10-22）

- 插件详情页统一展示声明能力开关，覆盖内置、JSON 规则和 JS 插件。
- 撤销直接保存；重新授权需确认，JS 网络授权确认框展示声明的域名白名单。
- 授权与插件启用状态独立：关闭时也可管理能力，全部撤销不卸载插件，重新启用不会恢复授权。
- 列表展示「全部能力已撤销」或已撤销项数，不再显示空白摘要。
- 单项更新在管理器锁内基于当前有效 grants 原子增删，避免旧 UI 快照覆盖其它能力。
- 授权写盘成功后才发布新状态；失败提示错误并保留旧状态，协程取消正常传播。
- 生效边界：输入/输出转换和工具在下一次请求装配时读取授权；JS 网络在下一次 `rikka.http` 调用时读取授权。已经完成的改写或已发出的网络请求不追溯撤回，不承诺中断正在运行的插件代码。

### 验证

- `:app:assembleDebug :app:testDebugUnitTest :plugin-manager:testDebugUnitTest` 通过。
- Manager 共 14 项测试，新增覆盖：默认信任转显式撤销、全部撤销与重启空集、重新授权、关闭状态不变、并发单项更新、写盘失败及取消不发布也不污染后续更新。
- 真机内置插件：撤销改写输出后持久化为 `approvedCapabilities:[]`，`enabled:true` 不变，列表显示「全部能力已撤销」。
- `force-stop` 后重启，详情页仍显示「已撤销」；重新授权点取消不改变状态，确认后持久化为 `[OUTPUT_TRANSFORM]`。
- 设备最终恢复内置插件已启用且已授权；本轮没有导入或清除其他插件/会话数据。
- 本轮真机交互验证针对内置插件；规则与 JS 复用相同详情页及 manager 授权路径，未重复执行模型/网络实发测试。

## 九、JS 隔离存储与声明式工具（2026-10-22）

### 9.1 存储契约

清单添加 `"storage": true` 即声明 `STORAGE`。安装时可以拒绝此项，安装后可随时撤销；每次桥调用重新检查插件启用状态及授权，无权时 Promise reject，不允许访问其它插件空间。

```javascript
await rikka.store.set("settings", { theme: "dark" });
const value = await rikka.store.get("settings");
const keys = await rikka.store.keys();
await rikka.store.remove("settings");
await rikka.store.clear();
```

- 值仅限 JSON；`get` 缺项和保存的 JSON null 在 JS 侧均返回 null，可用 `keys` 区分。`undefined` 不是有效 set 值。
- key 非空且 UTF-8 不超过 128 bytes；单值序列化不超过 64 KiB；每插件最多 128 项、完整 JSON object 不超过 256 KiB。
- `filesDir/plugin_kv` 下 SHA-256(pluginId) 文件名；共享 Mutex 防止并发覆盖；临时文件同步后原子替换，失败保留原文件。坏文件报错，不静默重置。
- 模块内变量仍不跨调用；KV 跨 WebView、进程重启保留。撤销/禁用/卸载均不删除 KV，同 id 重装可恢复；插件可调用 clear 显式清除。卸载后按授权状态同样保留数据，当前尚无独立宿主清理按钮。
- 每次执行最多 64 次网络/存储桥调用；执行结束取消未完成的桥任务，已完成写入不回滚；并发多个 get+set 不构成事务。
- 示例：[持久化计数器](<samples/js-plugins/persistent-counter.js>)。

### 9.2 声明式工具

同一个 `.js` 文件的 JSON 清单添加 `tools`，纯工具用 `"target": "none"`，也可与 input/output/both 混合。无需另建注册表：`JsPlugin` 实现现有 `PluginToolProvider`，由 `ChatToolFactory` 自动装配。

```json
{
  "id": "sample.echo",
  "name": "Echo 工具",
  "target": "none",
  "tools": [{
    "name": "echo",
    "description": "回显用户要求提供的文本",
    "parameters": {
      "type": "object",
      "properties": {"text": {"type": "string"}},
      "required": ["text"],
      "additionalProperties": false
    }
  }]
}
```

```javascript
async function onTool({ name, arguments: args, ctx }) {
  if (name !== "echo") throw new Error("Unknown tool");
  return { text: args.text, pluginId: ctx.pluginId };
}
```

- 工具名为 `[a-z][a-z0-9_]{0,31}`，每插件最多 16 个、不重复；说明非空且不超过 1000 字符。
- Provider 名自动变成 `rikka_<pluginId SHA-256 前16 hex>_<toolName>`，稳定、插件隔离且小于 64 字符。
- `TOOL_REGISTER` 安装时必需、之后可撤销；装配时过滤，执行已装配工具前再次检查，防止审批等待期间撤权后仍执行。工具调用始终要求宿主审批，清单不能关闭审批。
- 参数是严格 JSON Schema 子集：每层显式单 type（object/array/string/number/integer/boolean/null）；object 必须 properties，支持 required、布尔 additionalProperties；array 必须 items；支持 enum/title/description。不支持 `$ref`、组合 schema、format 或数值/长度 bounds 等，导入时拒绝未知关键字。
- Schema 上限 16 KiB，JSON 深度 32、节点数 16384；执行前检查参数类型、必填项、enum、数组项和额外属性。
- `onTool` 返回任意 JSON，undefined 转 null，宿主包装为文本工具结果。错误/超时抛给宿主的标准工具错误处理，不伪造成功。
- 工具与文本钩子共用沙箱；每次新 WebView、同实例执行串行、单次脚本 timeoutMs 仍为 300–5000ms，加载单独 5s。网络/存储仍需各自能力，工具能力不隐式授予它们。
- 示例：[便笺工具](<samples/js-plugins/notebook-tool.js>)。本阶段实现 JS 文件内 JSON 清单 + JS 执行器；未给 JSON 规则格式增加另一套执行语言。

### 9.3 验证范围

- `:app:assembleDebug :app:testDebugUnitTest :plugin-manager:testDebugUnitTest` 构建与回归通过。
- 存储 11 项 JVM 测试；清单/工具 schema 18 项；工具构建/撤权/参数/审批/隔离 8 项。
- 真机 `JsPluginRuntimeInstrumentedTest` 4 项通过：连续调用中新 WebView 仍持久化、插件隔离；存储撤权失败且保留旧数据；onTool JSON 返回及所有 KV 操作；安全嵌入含 `</script>` 源码及 `<` 运算符。
- 已在真机导入持久化计数器，确认清单与存储能力 UI。未执行依赖外部模型的对话工具选取/审批端到端测试；工具审批接入使用现有 Tool.needsApproval 链路。
