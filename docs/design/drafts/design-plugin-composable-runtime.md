# 可组合插件运行时设计：借鉴 DeepSeek Harness

> 状态：**设计草稿 rev2，尚未实施**。本次授权范围仍为形成设计稿并本地提交，不代表批准实现或启动重构。
> 日期：2026-11-03（初稿同日早间；rev2 为晚间复核）。代码基线：初稿 `50ae353cc`，rev2 `7f46f4238`。
> 推荐方向（rev2）：**原生 Android 宿主内的「契约化装配」优先；「动态运行时」降级为 P1 决策门后的
> 条件选项**，外部扩展继续走受控 JS / MCP。rev2 依据见 §2.3，修订摘要见 §十。
> 与既有设计的关系：演进现有插件系统；保留 Agent 任务控制面的执行器划分与已确认行为；
> 不重写已完成的记忆、压缩、标题与生成循环（原 P3 已退出主线，见 §六）。
> 增补：2026-11-03，§九按固定提交复核 `dsh-superagent-prototype`，分析其业务插件边界与可迁移机制。

## 一、目标与范围

目标是让能力的新增、替换和组合主要通过公共契约与组合配置完成，减少对 `ChatService`、
`GenerationHandler`、`ChatToolFactory` 中央分支的修改。衡量标准是扩展成本与行为可解释性，
不是插件数量、模块数量或中央类行数。

“一切皆插件”在本设计中表示：产品能力具有可替换契约，内置能力也通过契约参与组装。
**可替换不等于可由第三方任意替换，也不等于必须支持运行中热卸载。**
Android 生命周期、系统权限、凭据保管、授权校验和恢复入口由宿主掌握；
安全相关实现可以通过受信任接口替换，但外部插件不得绕过宿主强制约束。

rev2 把目标拆成两层，只有第一层进入建议投入范围：

1. **契约化装配（第一层，P0/P1/P2）**：能力有明确契约，内置能力也通过契约参与组装；
   装配在编译期与启动期确定（注册表 + 组合定义），不引入可热变更的服务图。
2. **动态运行时（第二层，P1 决策门后按需）**：服务依赖解析、作用域、配置修订、运行中更新、
   同 id 重装隔离等能力。没有明确需求时不建设（判据见 §六 决策门与 §八）。

首轮范围（rev2）：工具、输入/输出转换、模型目录等只读观测契约；搜索已具备服务形态，不再是试点。
助手能力组合、Provider 注册开放与 UI 插槽后移（见 §六）。
后续范围：记忆、上下文、压缩、模型适配、生成循环和 UI 插槽；rev2 明确**不把记忆注入、
压缩、标题服务化**列为目标，三项保持内置实现（依据见 §2.3、§5.3）。
首轮不迁移 Room 数据库，不改写会话树，不引入完整事件溯源，不承诺第三方 Kotlin 二进制兼容，
不直接嵌入 Cordis / Node，也不建设插件市场，不新增通用 effect 队列（§九 B）。

## 二、借鉴依据与本地事实

### 2.1 DeepSeek Harness 的机制

本节依据本次讨论中查阅的官方资料，记录日期为 2026-11-03；上游持续变化，实施前应固定参考版本。

| 官方机制 | 本项目采用方式 |
|---|---|
| 插件通过服务契约协作，声明服务依赖 | 类型化服务键、依赖解析、唯一提供者校验 |
| 注册属于可撤销 effect，卸载时清理 | 注册句柄、作用域所有权、启动失败回滚 |
| Agent 级作用域和能力组合 | 助手配置派生运行时组合，实际可变状态按会话/Agent 隔离 |
| profile / preset 配置组装 | 有版本的组合定义、解析后的不可变修订 |
| Agent 接口与默认循环实现分开 | 先以适配器包住现有循环，再逐步拆策略 |

业务组合的具体案例见 §九：`dsh-superagent-prototype` 在 Harness 上组装来源、授权、工单和执行能力。
其领域核心内部并未把每个对象都改成动态插件，这为本设计的插件粒度提供了参考。

参考：[Cordis Primer](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cordis-primer.md)、
[Architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md)、
[Core packages](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/README.md)、
[Presets](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/preset/README.md)。
借鉴服务、作用域和生命周期语义，不假定其 TypeScript 运行时与本项目直接兼容。

### 2.2 现有代码基础

下列路径相对仓库根目录，事实以代码基线为准；行数仅用于描述当前职责集中程度。

| 位置 | 当前事实 | 与目标之间的缺口 |
|---|---|---|
| `plugin-api/.../RikkaPlugin.kt` | 元数据、能力声明、启停、manifest 和 Compose 设置内容；能力在调用点校验 | 运行时 API 与 UI 依赖尚未分离 |
| `plugin-manager/.../PluginManager.kt` | 单一注册表、持久化、能力授权、生命周期观察者和 `awaitReady`，启停幂等 | 无服务依赖图、分层作用域、注册句柄/代次；`unregister` 后旧回调无实例隔离 |
| `app/.../data/plugin/PluginContracts.kt` | 输入/输出转换、工具、工具 UI 四个窄契约（多接口组合） | 契约在 app 中并依赖宿主内部类型；无服务选择与依赖声明 |
| `app/.../data/plugin/js/` | 单文件 JS 清单、工具、存储、受控 HTTP；每次求值新建并销毁 WebView | 每次调用创建 WebView，只适合有限钩子与工具 |
| `app/.../data/plugin/rules/`、`net/` | JSON 规则插件（RuleEngine + 安装器）与出站网关（`UrlPolicy`/`PluginHttpGateway`） | 规则与网络授权已独立实现，未纳入统一服务契约 |
| `app/.../service/ChatService.kt` | 约 3529 行；会话管理与管线、记忆、压缩等装配 | 需要逐步分离产品装配与会话服务 |
| `app/.../data/ai/GenerationHandler.kt` | 约 2068 行；模型循环；聊天、子代理与定时任务三条路径共用 | 需要稳定循环接口及可替换策略；三条路径语义必须同时保持 |
| `app/.../data/ai/tools/ChatToolFactory.kt` | 约 542 行；`createToolSegments` 有 14 个来源段，插件工具在同一条 `ToolSegment` 路径（末尾段），段名已成对外契约 | 内置与外部能力的差别只剩来源标识与审批策略未统一；段数增长使装配点继续膨胀 |
| `ai/.../provider/ProviderManager.kt`（69 行）、`Provider.kt` | `init` 内硬编码注册 5 个实现；`getProviderByType` 对密封 `ProviderSetting` 做穷尽 `when`。接口以默认方法承载可选能力：`fetchModelCatalog`（2026-11-03，四态结果）、`getQuota`、`generateEmbedding/Image` 等 | 注册仍非开放集合：新增类型须同改 `init`、`when`、`ProviderSetting` 与设置页；目录契约靠「接口默认方法 + 5 个实现逐一适配」扩张，是否纳入运行时尚无消费者证据 |
| `app/.../data/ai/catalog/` | 统一模型目录仓库（`ProviderModelCatalogRepository` 245 行、`CatalogSnapshotStore` 62 行）与凭据作用域指纹 | 新能力仍是 app 内 repository，未成为可替换服务；快照缓存在 `noBackupFilesDir`，不进备份 |
| `app/.../data/datastore/`、`data/sync/` | 主设置单份 JSON（DataStore）+ V1–V6 迁移 + `SettingsJsonMigrator`；`BackupFolderRegistry` 收敛备份目录单一真源 | 新增插件绑定必须沿用该迁移/备份机制，不能另立真相源 |

JS 工具已有执行时 `TOOL_REGISTER` 检查并强制宿主审批；网络、存储也有调用点授权。
这些是应保留的行为。现有 `TransformerContext` 暴露 Android Context、完整 Settings / Assistant，
不能原样成为外部插件公共 API。内置 Kotlin 代码运行在宿主进程内，窄接口是架构边界，
不是针对恶意原生代码的安全沙箱。

插件启停与能力授权存于独立 `plugin_states` DataStore（全局态，不在主设置内，也不进当前
备份的 `settings.json`）：这是「插件状态不污染核心设置」的正确隔离，但换机/恢复后授权状态
与启用选择会丢失，属于 §5.2 要一并解决的既有缺口。

旧稿：[插件系统设计](../../PLUGIN_SYSTEM_DESIGN_DRAFT.md)。该稿记录早期方向，
与当前实现不完全等同；本文只演进相关运行时架构，不覆盖其全部历史建议。

### 2.3 初稿之后落地工作对结论的影响（rev2 依据）

初稿（09:19 与 09:31 两个文档提交）之后，当日又落地下列与扩展性相关的工作（复核基线 `7f46f4238`）：

| 工作 | 提交 / 位置 | 与本文的关系 | rev2 采纳 |
|---|---|---|---|
| 统一模型目录刷新（A/B/C/D 已实施并验证） | `9290a611b`…`d05c8712a`；`Provider.fetchModelCatalog`、`app/.../data/ai/catalog/`；设计稿 [`design-provider-model-catalog-refresh.md`](design-provider-model-catalog-refresh.md) | 新增一个真实窄契约（完整性分级 + 凭据 scope 指纹 + 独立快照缓存），未引入运行时 | 证明「窄契约 + 宿主装配」可行；P4 复用该契约，不另造 Provider 注册服务；快照属缓存、不进备份 |
| 默认助手去重合并 | `acc78b92a`；`DefaultAssistantMigration.kt`、`db/MergeDefaultAssistant.kt` | 助手配置在单 key `assistants` JSON；迁移走「事务性迁移函数 + 引用搬迁 + 拆除旧种子」 | §5.2 的绑定字段沿用同一迁移/回写机制；独立载体的优先级必须先定 |
| 增量备份设计 + P0 | `c33cc02ae`、`7f46f4238`；[`design-incremental-backup.md`](design-incremental-backup.md)、`BackupFolderRegistry`、`BackupCoverageTest` | 备份目录/恢复分派收敛为单一真源，格式将演进（内容寻址对象） | 新增插件持久化目录必须登记 registry；绑定持久化排期避开备份格式变动 |
| 富文本渲染迁移 BetterText | `06267b5a0`；`BetterMarkdown.kt`，删除 `:highlight` 模块 | 渲染栈整体更换 | §5.4 的 UI 扩展若引入必须绑定该栈；首轮不动 UI |
| 分享接收、SSH 跳板、模型收藏去重等 | `f15fd6b44`、`9f5f2692b`、`800f9bbcf` 等 | 与本文无直接关系 | 不改变结论 |

任务控制面 P4a/P4b 早于初稿基线（`4f5561052` 等为 `50ae353cc` 祖先），不属于「初稿之后」，
§5.3 只把原文的「既有决议」改写为已落地事实。

由此形成 rev2 的三条判断：

1. **契约先行已被验证。** 模型目录契约（四态结果 + 凭据 scope）、任务控制面三 adapter、
   循环内规则提醒（`data/ai/RuleEngine.kt`，内置 Kotlin 表）都在没有动态运行时的前提下落地；
   本仓的真实模式是「窄契约 + 宿主装配 + 调用点校验」。但这不能反推「不需要插件运行时」：
   JSON 规则插件与 JS 插件本来就在跑，只是运行在现有静态注册表上；结论是「没有证据要求先建第二层」。
2. **真正缺的是服务所有权与注册清理语义，不是依赖图或热更新。** 现有 `PluginManager` 已有
   注册表、能力授权与观察者，但缺少实例代次；这可以在现有结构上小步补齐，不必先建运行时。
3. **中央装配仍在膨胀，第一层仍有价值。** 当日新增的目录仓库仍是 app 内 repository
   （245 行 + 快照存储），说明「新能力进 app」的惯性未变；但试点应挑只读观测、可替换实现的
   窄口（转换器链、目录），而不是核心上下文策略（记忆/压缩/标题）。

## 三、目标分层与契约

```text
Android 宿主：启动、生命周期、系统权限、凭据、授权、恢复入口
    │
    ├─ 第一层：公共契约：消息、工具、模型调用、上下文贡献、服务键
    │       ├─ 内置能力：搜索、模型目录、Skills、MCP、记忆/压缩（内置实现）
    │       └─ 外部桥：JS、JSON 规则、MCP（受控）
    ├─ 第二层（条件）：插件运行时：依赖解析、作用域、修订、生命周期、诊断
    └─ UI 契约与宿主：设置、工具结果、消息附件、页面入口
```

rev2 只建议投入第一层。第二层在 P1 决策门通过后按需启用；其对象（服务目录、修订、热更新）
不是首轮承诺，也不为它们预付模块拆分成本。

### 3.1 依赖方向

建议逐步形成 `runtime-api`、`plugin-runtime`、`plugin-ui-api` 和 app 宿主适配层。
这些是职责名称，具体 Gradle 拆分在试点中确定，避免一次性拆出大量空模块。
rev2：第一层只需要 `runtime-api` 的契约草图与 app 内适配层；`plugin-runtime` 模块在决策门通过前不创建。

- 公共契约不依赖 app、Room、DataStore、Koin、Android Context 或 Compose。
- 插件依赖公共契约；宿主适配器将已有服务映射为契约。
- UI API 单独依赖 Compose；现有 RikkaPlugin 通过兼容层保留设置页。
- `ai` 现有消息、工具类型逐项审计后复用或提取，避免复制一套长期并存的消息模型。
- Koin 继续装配宿主长期服务；插件运行时只管理动态贡献、服务选择与作用域，二者不竞争所有权。

### 3.2 三种扩展机制

| 机制 | 用途 | 示例 |
|---|---|---|
| 服务接口 | 请求一个明确能力 | ModelGateway、SearchService、MemoryService、WorkspaceAccess |
| 有序管线 | 对输入或决策进行受控变换 | 请求组装、输出转换、工具执行策略 |
| 类型化事件 | 通知已发生的事实 | 回合结束、任务状态改变、诊断事件 |

单实现服务必须显式选择提供者；多实现服务使用有标识的集合，禁止“最后注册者覆盖”。
管线声明阶段及 `before` / `after` 约束，使用稳定拓扑排序；同阶段无约束项按稳定 id 排序。
依赖或排序成环时拒绝启用并报告完整路径。状态事件不承担隐藏的控制流。
同一作用域内重复工具名直接报冲突；不同来源通过命名空间消歧。

工具结果配平等协议要求属于强制收尾阶段，不能被外部插件移到任意位置。
权限、审批、超时和预算检查在宿主执行边界实施，插件管线不能短路绕过。

### 3.3 概念接口

以下为语义草图，不是已经存在或可直接编译的 API：

```kotlin
interface RuntimePlugin {
    val descriptor: PluginDescriptor
    suspend fun mount(context: PluginContext): PluginHandle
}

interface PluginContext {
    val scope: RuntimeScope
    fun <T : Any> require(key: ServiceKey<T>): T
    fun <T : Any> provide(key: ServiceKey<T>, service: T): Registration
    fun contribute(extension: Extension): Registration
}

interface Registration {
    suspend fun dispose()
}
```

清单至少包含稳定插件 id、插件版本、宿主 API 兼容区间、配置 schema 版本、
所需/提供的服务、可选依赖、作用域和能力声明。运行时在 mount 前验证兼容性和依赖。
可选依赖缺失时仅关闭相应扩展；必需依赖缺失时阻止激活，不进入半启用状态。
公开 API 的破坏性变更升级版本，配置迁移独立版本化；首轮只保证仓内源码级兼容。

## 四、生命周期、隔离和配置修订

rev2 把本章拆成两截：作用域/清理语义（最小版可在现有 `PluginManager` 上落地，P1 交付）；
依赖解析、修订、运行中更新与审批修订绑定（属第二层动态运行时，P1 决策门通过后才实施）。
以下机制一旦启用仍是强制要求。

### 4.1 作用域与所有权

| 作用域 | 拥有内容 | 清理时点 |
|---|---|---|
| Host | 共享 HTTP 设施、服务目录、插件清单、长期存储适配器 | 应用宿主关闭 |
| Agent / 会话 | 该运行实例的工具贡献、上下文状态、监听器 | 实例释放且无未完成运行 |
| Run | 本轮配置快照、临时资源、取消与预算上下文 | 本轮结束或取消 |

Assistant 是可复用的组合模板，不是可变运行状态容器。同一助手的两个会话不共享临时状态；
子代理拥有独立作用域，按现有规则继承允许的配置和权限上限。
插件自有持久化数据按插件 id 与明确业务归属分区，避免不同助手间的隐式数据串用。

所有注册、监听器、协程和资源句柄都绑定所属作用域。清理幂等，子作用域先于父作用域关闭，
插件按依赖逆序退出；单项清理失败仍继续其他清理，并形成可诊断错误。
启动中途失败撤销已完成的注册，不能发布“已启用”状态。
构建候选修订期间只做无外部副作用的校验/装配；长期任务在激活后启动，失败要停止并清理。
外部写入无法靠 dispose 撤销，因此不得作为候选修订的探测动作。

### 4.2 启用、禁用与运行中更新

状态至少区分：未激活、等待依赖、激活中、活跃、排空中、失败；用户“希望启用”与实际运行状态分别记录。
禁用服务前检查反向依赖：受影响组合必须有有效替代者，否则阻止产生新运行并说明缺失能力。
必需服务不能在仍被引用时直接销毁。

普通配置变更生成不可变 `CompositionRevision`。新运行使用新修订，进行中的运行持有旧修订引用；
旧修订在引用释放后清理。停用默认阻止新调用，并排空既有调用；用户明确取消或安全撤权时走取消路径。
强制撤权立即在执行边界生效，并尽力取消在途网络/脚本；已发送到远端的动作不能保证撤销，
界面需区分“已撤权”和“远端动作已撤回”。

审批暂停恢复仍引用原修订；每次执行重新计算实时授权。若进程死亡后原插件版本或修订不可恢复，
将运行标记为需处理，禁止静默改用新实现执行旧审批。审批绑定工具来源、参数与配置修订；
相关变更需要重新审批。

### 4.3 生效配置与授权分离

组合配置采用固定层次：内置默认 → 助手绑定 → 会话允许的覆盖 → Run 临时覆盖。
每层只能修改其 schema 允许的字段；安全策略和权限不能通过高层覆盖放宽。
首轮采用**按插件配置对象整体替换**，不做隐式递归合并；编辑器保存完整对象，并提供最终配置预览。
不支持删除必需默认服务，除非同一候选修订提供替代实现。

有效权限由宿主上限、用户实时授权、助手/会话限制与插件声明共同取交集；组合配置不能授予权限。
凭据以宿主保管的引用传递，外部插件只获得必要的受控调用能力；导出配置不包含密钥。
Provider 替换仍需校验目标主机、账号适用范围与凭据绑定，不能沿用旧凭据向任意新地址发送。

## 五、功能迁移设计

### 5.1 转换器链与服务契约试点（rev2 调整对象）

原 P1 的「搜索迁移」不再作为试点：`search` 模块已是 `SearchService` + 19 个实现 + 选项多态，
模型侧搜索工具也已按段装配，迁移没有消费者（2026-11-03 核实）。P1 改为两件确有缺口的事：

1. **输入/输出转换链注册化**：`ChatService` 目前以手写 `buildList` 拼装内置输入/输出转换器与
   插件转换器（`ChatService.kt:510-516`）。把内置与插件统一为有序管线注册，仍区分存储变换、
   视觉变换与结束处理，保留 token 计数与易变区段语义。
2. **注册句柄与实例代次**：在现有 `PluginManager` 上补「注册属于实例」语义——旧实例的延迟
   回调/清理句柄不能修改新实例的注册（§九 A）。这是第一层唯一需要新增的运行时机制。

时间提醒继续作为输入转换试点：迁移阶段与顺序显式声明，预览/预热不执行有网络或写存储副作用的钩子。
模型目录契约（§2.3）作为「窄服务契约」的对照评审，不迁移、不新增运行时。
插件故障默认使本次必需请求失败并报告来源；可选纯显示扩展可降级。
禁止静默丢弃安全、协议或上下文必需变换后继续请求。

### 5.2 Assistant 与存储兼容

新增版本化插件绑定：插件 id、配置对象、配置 schema 版本、启用选择与可恢复的组合修订信息。
人格提示词、模型参数、显示设置继续保留；`enableMemory`（`Assistant.kt:67`）、`mcpServers`（`:160`）、
`localTools`（`:161`）、`enabledSkills`（`:177`）继续由现有装配点直接读取——`ChatToolFactory`
按这些字段决定段的有无，`McpManager` 按 `assistant.mcpServers` 过滤服务器。所谓「单向映射」
指新绑定**只读**旧字段、不反向写回，不指先造一层适配器类；当前不存在第二条装配路径，
全仓也没有 `LegacyAssistantAdapter` / `PluginBinding` / `CompositionRevision`（2026-11-03 核实）。

载体已核清，不再留待 P0：设置是单个 Preferences DataStore，版本键 `data_version`
（`PreferencesStore.kt:271`），迁移链 V1–V6（`:149-154`），`assistants` 是单 JSON 数组值（`:329`）。
V6（`PreferenceStoreV6Migration.kt`，2026-11-03）刚为「合并内置示例助手」新增一次性迁移，并落
`removedAssistantIds` / `legacyDefaultAssistantMerged` 显式标记（`DefaultAssistantMigration.kt`）；
插件绑定应作为 **V7** 加入同一条迁移链，而不是新载体。首轮仅保存新路径的 opt-in 配置，既有字段不删除。

两条兼容路径必须一起改：备份 JSON 迁移 `SettingsJsonMigrator`（V1–V7 与主设置迁移重复实现）
与读路径悬空引用过滤（按现存 id 集清理 mcpServers / 注入 / lorebook）。回滚沿用「显式标记 +
保留引用」写法；`JsonInstant` 忽略未知字段且写路径整份回写，因此把新字段写进 Assistant 时，
降级后再升级会静默剥离——这正是需要显式版本字段与迁移探测的原因。

插件启停与授权在独立 `plugin_states` DataStore，不在当前备份的 `settings.json` 内；
换机/恢复会丢失启用选择与授权撤销记录，需与备份项（§2.3 的目录 registry）一起显式处理。

插件缺失时保留配置与历史消息，标记能力不可用；不删除助手、工具结果或附件。
历史结果显示使用稳定类型及文本兜底：`ToolUIRegistry.resolve` 缺失即回退通用渲染器，
`PluginManager.unregister` 保留持久化的启用与授权状态。
现有 JSON / JS 插件已与内置插件共用同一启用与授权注册表（规则定义物化为 `JsonRulePlugin`
后即走 `PluginManager`，启动时由 `RulePluginInstaller` / `JsPluginInstaller` 恢复），
不需要「经由适配器进入新运行时」。需修正的是注册表边界：启用/授权只有一份（`plugin_states`），
**定义**仍分三处 DataStore（`plugin_states` / `plugin_rules` / `js_plugins`）；定义属安装数据、
不是授权真相源，可保留；若日后要求「一插件一记录」，应合并定义存储，而不是再造注册表。

### 5.3 生成循环与任务控制面（rev2 改为「已落地事实」）

任务控制面 P0/P0.5/P1a/P1b/P3 与 P4（P4a + P4b）已落地（2026-11-02，`692ed04ad`…`4f5561052`，
见[任务控制面设计](../in-progress/design-agent-task-runtime.md) 状态行与附录 D）：
三执行器保留各自语义（`SubagentRunner`、`WorkspaceTaskRegistry`、`ScheduledTaskExecutor` +
`ScheduledTaskRunner`，后者在会话注入与 P4b headless 间二选一并自持定时账本）；
控制面统一 `TaskKey` / `TaskCard` / `needsPerson` 与取消入口；`ConcurrentRunGate` 只裁决
子代理/无头 run（满则拒绝不排队），shell 与定时注入各用 workspace 槽位 / DB 认领。
D3（嵌套**未实现**：全仓无 nesting 开关，派发工具在 run 信封中恒被剔除）与 D6
（不自动续跑父生成）保持有效，P2 未启动。**不要因插件化抽出一个承载三种语义的
通用 Runner**（原决议不变）。

rev2 调整两处措辞：原「先用默认 AgentDriver 适配器包装 GenerationHandler」保留为 P5 条件项，
但口径改为「只包 `generateText`」——它是单次调用内的步进循环（`GenerationHandler.kt:563`），
审批续跑、超时、账本与 notice 在调用方（`SubagentRunner.executeRun`、`ChatService`）；
P4b 已把定时 headless 的 run 级所有权移到 `SubagentRunner`，因此接口不得声称拥有整个生成循环。
抽接口前须逐条列出手动 / 对话 / 后台 / 定时 headless 四个 run 入口与定时注入路径，
证明账本、notice、取消级联、归属与压缩回写语义不变。预算硬上限和权限约束由宿主监督执行；
默认循环可替换不等于可绕过这些约束。

上下文贡献、压缩策略、续跑策略不服务化（原 P3 取消）：这些能力已有独立设计稿与实现
（[`design-auto-compaction.md`](../completed/design-auto-compaction.md)、`design-memory-*.md` 等），
在 P1 决策门通过、并有明确消费者前，只允许新增**只读**上下文贡献者（如规则提醒），不允许替换核心策略。

### 5.4 扩展形态与 UI

| 形态 | 适合内容 | 边界 |
|---|---|---|
| 内置 Kotlin 源码插件 | 循环、高频管线、平台能力、原生 UI | 随 APK 发布，宿主进程内受信任代码 |
| 受控 JS 插件 | 工具、规则、有限输入/结束钩子 | 保留授权、审批、网络与存储网关、资源限制 |
| MCP / 远程能力 | 外部工具、重任务、远端集成 | 通过宿主工具执行与授权入口 |

现有 WebView 按调用重建，不适合直接承担 token 级钩子或常驻服务。只有性能证据与需求成立时，
再比较 JS 引擎或独立进程方案；超时和 destroy 不能视为对所有宿主主线程阻塞的硬隔离保证。
首轮不下载执行第三方 Kotlin / DEX；分发限制及平台风险参考
[Android 动态代码加载说明](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)。

UI 首先开放设置、工具结果、附件渲染和受控入口；复杂原生页面仅支持随 APK 编译的插件。
外部脚本首选声明式表单，不能直接提交任意 Compose 实现。
配置表单遵循仓库 IME 稳定宿主规范；多块 supportingContent 使用 Column 包装。
rev2：渲染栈已于 2026-11-03 迁移到 BetterText（`06267b5a0`），首轮不开放 UI 插槽；
若将来开放，插件提供的渲染内容必须绑定该栈，不引入第二套 Markdown / 公式渲染。

## 六、分期、验收与回滚

以下为建议阶段，未代表实施授权。每期必须完成自身退出条件，才扩大范围。
rev2 重排：P1 试点对象改为转换器链 + 注册句柄；新增 P1 决策门；原 P3 取消；P2/P4 收窄；
P5 暂缓。逐条依据见 §2.3 与 §5.x。

| 阶段 | 交付 | 退出条件 | 回滚方式 |
|---|---|---|---|
| P0 基线与契约 | 盘点依赖、行为样例、契约草图与兼容规则；**把服务归属表与配置/备份迁移结论写进本文** | 消息/工具类型归属明确；载体结论落地（Settings V7 链 + `SettingsJsonMigrator` + 备份 registry）；服务归属表无空白 | 仅文档与测试设施，无产品行为切换 |
| P1 纵向试点 | 注册句柄/实例代次；输入/输出转换链注册化（内置与插件同链）；时间提醒试点；模型目录契约对照评审 | 新增/替换转换器不改 `ChatService` 手写链；停用后无残留注册；旧插件行为兼容；对照评审有「目录契约是否够用」的记录 | 受控开关选择旧链 |
| **P1 决策门** | 书面结论：继续第二层（动态运行时）或停在第一层 | 给出继续/停止的判据与证据（§八 开放问题 4）；停止则删除试点开关与未用接口 | 无行为变化 |
| P2 工具统一（收窄） | 插件工具与内置工具的**来源标识、审批策略、诊断、历史展示**统一；段装配保持宿主强制（门控/预算/LoopGate） | 工具 schema、审批、来源与历史展示兼容；MCP/Skills/工作区仍按现状（内置能力，不迁移） | 按能力切回旧适配器 |
| P4 组合与模型（条件） | 助手组合编辑、插件绑定 V7、凭据路由；Provider 扩展复用已落地的目录契约，不新增 Provider 注册服务 | 配置往返、未知插件兜底、旧助手迁移、备份往返和凭据路由可验证 | 保留旧配置及兼容映射；遇新路径独有配置阻止降级 |
| P5 循环与 UI（暂缓） | AgentDriver 替换点（只包 `generateText`）、有限 UI 插槽 | 出现第二个受信任实现，且四个 run 入口与定时注入路径的账本、notice、取消级联、归属回归通过；恢复入口指插件安全模式，任务侧只保证 INTERRUPTED 可见与 notice 补投，不承诺恢复生成 | 恢复默认 Driver 与内置 UI |

原 P3（记忆注入 / 压缩 / 标题服务化）**取消**：三项均有独立设计稿并在持续演进，插件化没有消费者；
若将来出现真实需求，另立设计，不沿用本表。
P5 暂缓的理由：当前没有第二个受信任循环实现，也没有 UI 插槽消费者；先不造接口。

迁移期一次运行只能选一条装配路径；观察对比仅比较纯数据结果，不重复调用模型、工具、网络或存储写入。
旧路径在其替代路径完成验收、回滚窗口结束后删除，避免永久双轨。

关键验收场景：

1. 同一助手打开两个会话，配置、取消、临时上下文与工具注册互不污染。
2. （第二层启用后适用）缺依赖、循环依赖、重名工具、版本不匹配、mount 中途失败均有确定结果，
   且无残留注册；第一层只需保证重名工具与版本不匹配的确定失败。
3. （第二层启用后适用）运行/审批暂停期间更新配置，新运行使用新修订；旧运行恢复不偷换实现。
4. 工具列出后撤销权限，实际执行被阻止；网络/存储网关同样校验，撤权不依赖刷新页面。
5. 取消、超时、流式错误、进程被杀后，旧回合和任务结果仍能说明是否完成；外部副作用不盲目重试。
6. 旧助手、JSON / JS 插件、历史工具结果和备份往返可读取；插件缺失有兜底展示；
   插件启停与授权状态在备份往返后保留（当前缺口，随 P4 修）。
7. 输入顺序、工具 schema、模型请求与压缩回写保持基线语义，预览/预热不产生额外副作用。
8. 禁用插件后协程、监听器、WebView 和服务引用按约定释放；必需依赖不会被提前销毁。
9. 同 id 插件卸载再启用后，旧实例的延迟回调和清理句柄不能修改或撤销新实例的注册。
10. 转换器链注册化后，新增/替换一个转换器只改注册表，不改 `ChatService` 链序；停用后链序与
    token 计数恢复，流式视觉变换与结束处理不串位。
11. 配置迁移：插件绑定 V7 迁移 + 旧备份导入 + 旧版本降级三方行为确定，无静默丢字段。
12. P1 结束时留下书面继续/停止结论；停止时仓库没有半成品运行时模块。

验证按阶段执行：公共运行时使用 JVM 合约测试；WebView 与生命周期使用 instrumented 测试；
工具审批、IME、后台恢复进行真机检查。记录首 token 延迟、装配耗时、内存、WebView 创建次数和构建成本。
P0 固定样例与设备、记录基线并明确可接受波动，后续以同条件比较，不预先虚构性能收益。
本设计稿的本地提交只验证文档结构、链接、差异与提交范围，不表示上述实现验收已完成。

## 七、可观测性与故障恢复

诊断入口展示：用户意图与实际启用状态、依赖图、阻塞原因、生效修订、管线顺序、
工具来源、实时授权、最近故障和清理结果。每个请求/工具执行关联插件 id、版本、修订与 Run id。
日志默认不记录密钥或完整会话内容；配置导出只包含可分享字段。

插件持续启动失败时提供只加载内置默认组合的恢复入口，允许修复或停用故障插件。
恢复模式不自动重跑失败工具或审批，不清空配置和业务数据。
故障策略区分：必需服务失败阻止相关运行；可选显示扩展失败可降级；授权失败一律拒绝操作。

## 八、投入、风险与待确定事项

以下为单名熟悉项目的开发者主导、保留现有功能前提下的量级估算，包含验证但不含持续新需求，
不是排期承诺；P1 完成后重新估算。rev2 把范围拆成三层，只有前两层进入建议投入：

| 范围 | 估算 | 主要不确定性 |
|---|---|---|
| 现有插件系统增强 + P1 纵向试点（注册句柄、转换器链注册化、对照评审） | 2–4 周 | 契约提取、注册所有权、与现有 `PluginManager` 兼容 |
| 第一层扩展（P2 工具来源/审批统一 + P4 助手组合与绑定 V7） | 约 1.5–3 个月 | 工具来源兼容、配置迁移与备份往返、助手组合编辑 |
| 第二层动态运行时（依赖解析、作用域、修订、热更新、循环/UI 替换） | 约 2–4 个月，条件触发 | 迁移回归、审批/恢复语义、公共 API 冻结风险 |
| 全面动态平台与第三方生态 | 约 6–12 个月或更长，**不在当前方向** | 分发、版本生态、运行时隔离 |

主要风险是迁移回归、组合冲突、主线程开销和公共 API 过早冻结；rev2 新增两条：
① 与增量备份 P1/P2、Settings 迁移并行可能让配置格式反复；② 把契约层做成事实上的运行时
（隐性动态化：注册表带依赖图、处处可热替换），成本回升且行为不再可解释。
控制方式是纵向试点、确定性装配、失败可定位、按能力回滚与仓内契约先行；
配置/迁移类改动与备份格式变更排同一批次，避免双轨。

P0 需要落实的实施决策：

- 公共消息与工具类型从 ai 提取的最小集合，以及 Gradle 实际拆分方式。
- 插件绑定与组合修订的持久化：确定为 Settings V7（沿用 V1–V6 + `SettingsJsonMigrator`），
  还是独立载体；若独立，先定义与 Settings 的写入优先级与原子性。
- 各服务是单实例还是命名多实例、允许在哪一级覆盖的完整表。
- **P1 决策门判据**：什么证据支持进入第二层（第三方扩展必须不随 APK 发布 / 同能力需运行时切换 /
  热修复需求）？没有证据则停在第一层，不再为运行时对象预付成本。
- 模型目录 / Provider 契约的归属：继续留在 app 内 repository，还是升为运行时服务？以 P4 对照为准。
- JS 性能与隔离是否需要升级；默认继续使用现有受控运行时。
- 第三方 API 稳定承诺、分发渠道和 UI 开放程度；试点不为这些未确定目标预付完整平台成本。

建议先按 P0/P1 验证「新增/替换一个转换器不修改 `ChatService` 手写链，且停用无残留」，
再按决策门决定是否继续；「替换搜索实现」不再是验证目标（搜索已是服务形态，见 §5.1）。
MCP host 模式（导出本仓工具给外部客户端）仍是独立未立项项，本文不并入；若启动，其工具
schema / 审批 / 网关应与 P2 复用同一契约。
本文尚未启动任何阶段，也未改变既有插件或任务运行行为。

## 九、增补：`dsh-superagent-prototype` 源码分析

### 9.1 复核范围与定位

复核日期：2026-11-03。远端 HEAD 与现有本地只读副本一致：
`6337fc823ce20251c8de8085948165c81aaa701e`，下列源码链接均固定到该提交。
分析覆盖 bundle、插件入口、Source 注册、领域核心、执行适配、授权、可靠投递及相关测试源码；
未安装依赖、未启动监听、未执行其部署流程或测试，不把仓内测试存在视为生产验证通过。

[项目 README](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/README.md)
明确将其定位为研究原型，公开版本与内部版本、文章描述不完全一致。
代码实际面向单个 GitHub 仓库，支持 GitHub 与可选飞书来源，依赖 DSH 服务运行。
它是**使用 Harness 能力构建的业务 bundle**，不是另一个通用模型循环实现，也不能作为 Android 后端直接移植。

当前架构是主 Session 承担评估/路由，工作 Session 承担工单执行；工作 Session 还可通过 preset 调用子代理。
本次检查没有发现需要本项目照搬的通用对等 Agent 通信网络。主要扩展点是来源接入、领域端口和角色能力组合。

### 9.2 从插件组装到业务执行

```text
cordis.patch.yml
    ├─ dsh-superagent：构建 Runtime，提供 orchestrator 服务
    ├─ github-watch / feishu-watch：注册 SourceKind，启动受控监听
    └─ router / worker presets：组合角色工具与模型运行能力

来源事件 → Trigger 去重与接收范围过滤 → Eval / Policy → Decision
                                                        │ accept
                                                        ▼
                                               Mandate + Ticket
                                                        │
                                                Assignment / Input
                                                        │
                                             DshExecutor → 工作 Session
                                                        │
                                                Report / Attention
                                                        │
                              人员处理面板 / 主 Session / 来源渠道回复
```

| 代码边界 | 已核实的实现 | 对本设计的意义 |
|---|---|---|
| `cordis.patch.yml`、`src/index.ts` | bundle 插入主插件、来源插件、角色 presets；主插件声明 DSH 服务依赖并绑定停止清理 | 产品形态可以由少量高内聚插件组合，无需每个类都成为插件 |
| `src/app/runtime.ts` | 构造 Store、Engine、Sessions、Executor 等，负责初始化、目录锁与关闭 | 组合根负责装配与资源所有权，业务规则另有归属 |
| `src/core/engine.ts` | 单一事务写入口，内部直接构造 Intake、Evals、Tickets、Assignments、Replies | 领域不变量集中维护；这些内部类并不是可随意热替换的 Cordis 插件 |
| `src/core/ports.ts` | 核心依赖 Repositories、Policy、SourceScope、Assessor、Executor、Outbox 等窄端口 | 将外部实现与业务规则分开；不应把所有方法都改成事件监听 |
| `src/sources/kind.ts`、`registry.ts` | 来源同时声明 schema、表单字段、健康项、渠道、上下文读取与策略扩展 | 一个扩展点要覆盖配置、运行、诊断和展示，不能只留下执行函数 |
| `src/app/sessions.ts`、`src/executor/dsh.ts` | 按角色选模型/preset，工作目录与执行、输入交付、恢复由适配层负责 | 运行职责与业务身份分开，保留本仓不同执行器的语义 |

**粒度结论：** 对外稳定边界采用插件/端口；内部共同维护同一事务不变量的规则保持内聚。
因此不建议把任务状态的每个转换、每种审批检查或每个数据库操作独立插件化。
否则一次业务命令需要跨大量插件协调，反而难以保证一致性和定位失败。

### 9.3 可直接吸收的运行时机制

**A. 业务扩展完整描述与注册所有权。**

SourceKind 将配置 schema、声明式字段、健康状态和实际行为放在同一个扩展描述里。
SourceRegistry 拒绝重复 kind 和健康项占用，注册返回清理函数；卸载后配置仍保留，缺失插件可展示为未安装。
健康回调持有原注册的句柄，旧监听器卸载后的迟到上报不会污染重新注册的实例。
来源监听的 AbortController 与等待退出也通过 `ctx.effect` 绑定生命周期。

本项目 P1 应采用同样的“注册属于实例”语义：句柄绑定 owner / generation，清理只作用于该实例；
旧回调、旧 disposer 不能仅凭 plugin id 修改新注册。配置缺失插件的保留和兜底按 §5.2 执行。
外部表单沿用现有宿主组件渲染，业务流程不向用户暴露服务键、依赖图等实现细节。

SourceContext 是受限的工程接口，但仍暴露 commands、状态访问及业务查询。
这些类型约束不是对恶意插件的权限沙箱；本项目外部 JS 只能获得宿主代理后的允许能力，
来源身份、接收范围、可读取数据必须在调用时校验。

**B. 领域命令与外部副作用分离。**

Engine 同步执行命令，在仓储事务里改变事实，通过端口登记后续工作；真正异步模型调用、
会话交付、渠道发信在事务提交后由适配器执行。不要把“调用了外部服务”当成数据库原子事务的一部分。

这里有两种不同的 effect，必须区分：

| 名称 | 实际作用 | 本项目归属 |
|---|---|---|
| Cordis `ctx.effect()` | 注册资源与清理函数，管理插件生命周期 | P1 的注册句柄与作用域 |
| `app/Effects` | SQLite 持久化待执行业务动作，支持重试和重启恢复 | 有真实业务需求时再建设的交付基础设施 |

二者不能合成一个“万能 effect API”。卸载插件可以撤销注册，却不能抹掉已承诺的业务结果或假装撤回远端动作。
持久化动作归宿主业务账本所有，插件卸载时按动作规则暂停、取消或等待替代处理器，不能随实例内存一起丢失。

**C. 重试、版本栅栏与交付回执。**

Effects 使用 `(kind, key)` 唯一行合并待执行动作；更新时增加 generation，完成时匹配 seq / generation，
防止旧尝试完成后删除后来更新的载荷。无可用槽位抛出 Later，只延期，不增加真实故障次数；
普通错误采用指数退避，部分有序类别会被队首失败阻塞。

Outbox 将回执与 reply effect 原子登记，渠道关闭或不再允许接收时记为 dropped，停用/未安装时等待。
AgentDelivery 则结合确定性消息 id、会话历史与待处理 inbox 去重，flush 后才确认交付；
来源标识是插件来源，不能因底层使用 user 角色就将其视为真人授权。
GitHub 发送端用评论标记查重，飞书发送端传递幂等键。

这些机制支持可恢复重试，**不能据此宣称任意外部副作用 exactly-once**。
远端成功而本地确认前崩溃的窗口仍需发送端幂等或对账处理；普通 shell 命令不能因排进队列就安全重放。
本仓目前继续使用 notice 的单用途投递泵。只有引入第二类需要持久化恢复的副作用，
并确认其幂等、撤权与取消语义后，才讨论共用交付队列；插件数量增加本身不是触发条件。

### 9.4 授权、恢复和资源策略的差异

**准入授权不等于工具权限。** Policy 先区分允许发起者、明确请求与推断候选，
主动模式包含 off / observe / auto；observe 可以记录“本会接受”而不实际受理。
Mandate 保留业务范围、发起依据与策略版本，恢复已结束工单前重新校验。
策略变化会重新处理评估，并向受影响的活跃工单添加 policy-changed 输入。

但这不是每次工具执行的强制撤权机制：`Sessions.ensure()` 明确设置 `danger-full-access`，
router preset 虽隐藏文件写工具，仍有 shell；不能据此认定其拥有系统级只读权限。
本项目保留的是已落地的装配期信封交集（含父信封与工作区）与执行期审批；§4.3 的实时权限交集
仍是目标、尚未实现，不能写成既有约束。不沿用其全权限执行默认值。
来源插件提供的 human / addressed 信息应由可信适配器从来源身份推导，不能相信外部正文自行声明。

**自动恢复是该原型的产品选择。** Runtime 启动会调用 executor.resume；
对未结清执行，Assignments 会根据已有报告与已交付输入决定重启，附上先检查工作树和远端状态的恢复提示。
提示词减少重复操作风险，但不构成外部副作用去重保证。
本仓保证中断状态可见（启动对账标 INTERRUPTED 并留 notice，`cancelReason` 明示不可恢复）
与后台结果不自动续跑父生成；但没有生成恢复入口，「恢复」只能由用户新发一条消息。
不因借鉴原型就扩大已批准的自主性或开启嵌套。

**资源预留需要适配设备上限。** WorkerSlots 在 await 前同步预留容量，并允许真人后续消息唤醒使用额外一个槽。
这是额外的 worker 容量，不是人员实际操作槽，也不是抢占式调度。
本仓保留设备分池和硬预算；可借鉴准入原子性与真人请求优先响应，但不能直接把模型并发上限加一。

**轮询时钟不适合照搬到 Android。** 原型依赖常驻 Node、CLI 和周期 pass；
需要长期监听时，应通过符合 Android 生命周期的调度适配器或远端服务承载。
WorkManager、系统后台约束与桌面守护进程不是等价执行环境，不把秒级后台常驻列为插件运行时承诺。

### 9.5 工单、报告与“需要人”的派生视图

Ticket 表示持续的业务工作，Assignment 表示其中一次执行尝试；Report 表达工作者报告，
Attention 表达需要人回答的问题。`ticketStage()` 按优先级从这些事实派生展示阶段，
它是**纯分类函数，不是驱动全部状态转换的通用工作流引擎**。
ready 报告也不总等于工单完成：关联 PR 等线程时，还需要看线程是否关闭及是否仍有后续工作。

可借鉴的是区分“模型停止生成”“产物可审阅”“业务已完成”。本仓已有 TaskKey / TaskCard 与 needsPerson，
保留其命名与各 adapter 状态映射；只有引入跨多次 run 的持续目标时，才新增独立业务工作身份，
不能把每个会话直接改名为 Ticket，也不将 shell 退出误判为用户目标达成。

原型强调只有需要决定或有可用结果时才通知，面板优先显示待人处理项。
本仓已有任务中心人优先分段，不重新实现一套面板。后续可在读模型中增加原因、唯一可解锁者、
所需操作与产物引用；普通进展保留在任务详情，通知按有意义的变化去重，避免逐步广播。
当前 `needsPerson` 只由待审批与定时 `TARGET_GONE` / `ASSISTANT_GONE` 派生；
增加原因与「谁可解锁」属于读模型扩展，不改变执行器语义。

### 9.6 对本设计的增量决议建议

| 建议 | 时机与范围 | 验收 |
|---|---|---|
| 保持内聚的领域规则，插件化外部能力边界 | P0 明确服务归属；现有循环先适配 | 新来源/提供者无需修改领域内部事务规则 |
| 注册句柄绑定实例代次，区分真实故障与容量等待 | P1 生命周期与诊断 | 旧回调/旧清理不影响新实例；满槽不误报插件故障 |
| 补齐配置、诊断与展示的扩展描述 | P1 试点，UI 随后演进 | 一个能力可完成安装配置、使用、停用与故障定位 |
| 准入与执行权限分开；机器输入保留来源 | P2/P4 需要来源能力时 | 伪造身份、取消订阅、撤权后均不能获得额外执行权限 |
| 外部来源可通过后续事件适配器扩展 | 有明确用户需求后另立范围 | 来源去重与可靠接收、准入、任务关联、回复均走宿主边界 |
| 持久化交付队列仅在多类副作用需求成立后评估 | 不增加为 P1 前置条件 | 崩溃窗口、幂等、重试、取消、卸载和回执逐项可验证 |

这里的“事件适配器”是未来职责描述，不直接使用本仓已存在的 `TaskSource` 枚举名称作为新接口。
不照搬 Ticket 全对象模型、自动恢复、full-access 默认值、秒级轮询或通用队列。
因此此次增补**不扩大 P0/P1 实施范围，不改变原投入估算**；后续若新增外部事件和自主工作流，单独估算与授权。
rev2 重排后，本表「时机」列按以下对应关系阅读：P1 = 注册句柄与转换器链试点；
P2 = 工具来源/审批统一（已收窄）；P4 = 助手组合与绑定（条件）；P3 已取消、P5 暂缓。
本表其余结论不变。单用途 notice 投递泵（`delivery_state` + `NoticeDelivery`）已落地，
不要扩展为通用 effect 队列。

扩展验收样例：同来源重复事件不重复受理；候选可见但未获准时不启动工作；
插件替换后旧回调失效；入队动作更新期间旧尝试完成不覆盖新载荷；容量等待不计为故障；
远端动作成功、本地回执未写时重启能查证结果；等待人处理与业务完成分别展示。
其中 P1 只实现与注册生命周期有关的样例，其余随相应能力启用再落实。

### 9.7 固定版本证据索引

下列是本次实际阅读的关键源码。相关 `registry.spec.ts`、`queue.spec.ts` 等仅用于理解其验证意图，未运行。

| 主题 | 固定提交链接 |
|---|---|
| bundle 与角色能力 | [cordis.patch.yml](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/cordis.patch.yml) |
| 插件入口与停用清理 | [src/index.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/index.ts) |
| Source 契约与注册所有权 | [kind.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/sources/kind.ts)、[registry.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/sources/registry.ts)、[source-context.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/source-context.ts) |
| 事务核心与外部端口 | [engine.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/core/engine.ts)、[ports.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/core/ports.ts) |
| 持久化动作与回执 | [effects.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/effects.ts)、[outbox.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/outbox.ts) |
| 会话输入去重 | [delivery.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/delivery.ts) |
| 渠道幂等实现 | [github-channel.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/sources/github-channel.ts)、[feishu-channel.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/sources/feishu-channel.ts) |
| 授权与配置变化 | [policy.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/policy.ts)、[tickets.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/core/tickets.ts) |
| 执行权限、容量与恢复 | [sessions.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/sessions.ts)、[dsh.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/executor/dsh.ts)、[assignments.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/core/assignments.ts) |
| 生命周期与业务阶段 | [runtime.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/app/runtime.ts)、[stage.ts](https://github.com/deepseek-ai/dsh-superagent-prototype/blob/6337fc823ce20251c8de8085948165c81aaa701e/src/core/stage.ts) |

任务控制面已有[附录 E](../in-progress/design-agent-task-runtime.md)的领域对照继续有效；
本节增补插件架构与运行时语义，并澄清 stage 分类、worker 预留容量和自动恢复的实际含义。

## 十、rev2 修订记录（2026-11-03 晚）

复核基线 `7f46f4238`。本次只改结论与分期，不扩大 §九 增补结论，也未启动任何实施。

| 位置 | 修订 |
|---|---|
| 状态行 | 标 rev2；补复核基线、两层目标与修订入口 |
| §一 | 目标拆为「契约化装配（投入）」与「动态运行时（条件）」；首轮去掉助手组合，明确记忆/压缩/标题不服务化 |
| §2.2 | 更新插件/Provider/工具装配事实（`PluginManager` 能力、14 段装配、Provider 默认方法契约、catalog、V1–V6 迁移、备份 registry）；补「插件状态独立 DataStore 且不进备份」的缺口 |
| §2.3 | 新增：初稿后落地工作（模型目录、默认助手合并、备份 P0、BetterText）与 rev2 三条判断 |
| §三/§四 | 分层图标注第一层与条件第二层；生命周期章拆为可落地最小语义与条件运行时语义 |
| §5.1 | P1 试点对象从「搜索迁移」改为转换器链注册化 + 注册句柄；模型目录契约作对照评审 |
| §5.2 | 按核实事实重写：V7 迁移链、两条兼容路径、`plugin_states` 备份缺口、JSON/JS 定义三处存储 |
| §5.3 | 任务控制面由「沿用决议」改为已落地事实（P0–P4b）；AgentDriver 包装降为 P5 条件项 |
| §5.4 | 补 BetterText 渲染栈约束 |
| §六 | 重排阶段：P1 决策门、P2 收窄、P3 取消、P4 条件、P5 暂缓；验收场景 2/3 标注第二层适用，新增 10–12 |
| §八 | 估算拆三层并给出继续/停止判据；风险补「并行迁移」与「隐性动态化」；待决策事项更新 |
| §5.3/§六/§9.4/§9.5 | 按代码复核收紧：嵌套写「未实现」、gate/信封/恢复边界、AgentDriver 只包 `generateText`、`needsPerson` 派生来源；§9.4 两条「既有约束」改为「目标 / 现状」 |
| §九.6 | 补阶段名对应关系与单用途 notice 泵说明；§九 的源码分析结论未改 |

未改动：§2.1 借鉴依据、§三.2 三种扩展机制、§三.3 概念接口草图、§四 具体清理/审批语义、
§七 可观测性、§九 的固定版本证据索引。
后续若启动 P0，先把「服务归属表 + 配置/备份迁移结论」写进本文，再改代码。
本地提交只验证文档结构、链接与差异，不代表批准实施。
