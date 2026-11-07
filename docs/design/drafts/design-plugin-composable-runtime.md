# 可组合插件运行时设计：借鉴 DeepSeek Harness

> 状态：**rev2，P1 整期验收完成（2026-11-07）；P2a/P2b 已实施（§十七/§十八，2026-11-07；P2a 已提交 `98752ef66`，P2b 未提交待授权）**。
> P1 决策门：**放行第二层（动态运行时）**，见 §16.6——P2（工具统一）仍按 §16.3 先行，
> 第二层在 P2 完成后另立设计批次。2026-11-06 P2 四项设计拍板见 §16.4，拆分见 §16.3。
> 2026-11-04 进展见 §十二至§十五；P1c 获「本地提交」授权，实施与证据一并归档，推送未授权。
> 日期：2026-11-03（初稿同日早间；rev2 为晚间复核）。代码基线：初稿 `50ae353cc`，rev2 `7f46f4238`。
> 推荐方向（rev2）：**原生 Android 宿主内的「契约化装配」优先；「动态运行时」降级为 P1 决策门后的
> 条件选项**，外部扩展继续走受控 JS / MCP。rev2 依据见 §2.3，修订摘要见 §十。
> 与既有设计的关系：演进现有插件系统；保留 Agent 任务控制面的执行器划分与已确认行为；
> 不重写已完成的记忆、压缩、标题与生成循环（原 P3 已退出主线，见 §六）。
> 增补：2026-11-03，§九按固定提交复核 `dsh-superagent-prototype`，分析其业务插件边界与可迁移机制；
> §十一按固定提交复核 `ccch1mneyyy/dsh-Go` 的声明式原生插件协议，§8.1 增列 JS 运行时候选基准。

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
- JS 性能与隔离是否需要升级：候选与判据见 §8.1；默认继续使用现有受控运行时，出现证据再评估。
- 第三方 API 稳定承诺、分发渠道和 UI 开放程度；试点不为这些未确定目标预付完整平台成本。

建议先按 P0/P1 验证「新增/替换一个转换器不修改 `ChatService` 手写链，且停用无残留」，
再按决策门决定是否继续；「替换搜索实现」不再是验证目标（搜索已是服务形态，见 §5.1）。
MCP host 模式（导出本仓工具给外部客户端）仍是独立未立项项，本文不并入；若启动，其工具
schema / 审批 / 网关应与 P2 复用同一契约。
P0 启动准备及基线见 §十二；P1a 生命周期实现见 §十三，P1b 注册装配切换及语义验证见 §十四。

### 8.1 JS 运行时候选基准（rev2 增补；不改变结论）

现行 `JsPluginRuntime` 每次求值新建并销毁 WebView（§2.2），这是基线，不是候选。
只有出现「token 级高频钩子」或「WebView 内存 / 主线程事故」的实测证据，才按本节做基准实测；
本轮不迁移、不引依赖、不改现有 JS 插件契约。

| 候选 | 集成形态 | 已知事实 | 主要风险 | 位置 |
|---|---|---|---|---|
| WebView（现状） | 系统组件、进程内；每次求值重建 | `JsPluginRuntime` 466 行；调用点能力校验、审批、网络/存储桥齐备 | 主线程创建开销、内存抖动、无硬隔离 | 基线 |
| QuickJS | 进程内 JNI（社区绑定） | 引擎体积小（dsh-Go 评估估约 1–2MB/ABI）；Android 绑定维护不一致（据其 2026-10-25 评估：`flutter_js` 停在 2021，`quickjs_engine`/`flutter_qjs_next` 单作者） | 无 Web/Node API，要自建桥；绑定维护与调试 | 若必须嵌入，首个评估对象 |
| Hermes | 进程内引擎（RN 生态 AAR） | 成熟度高、经大厂验证；仅 JS 引擎，无 Web/Node 内建 | 需自建运行时与 fetch/storage/审批桥；bytecode 构建链更重 | 备选 |
| winterjs2 | CLI 二进制、无 lib API；需子进程 | Bun-like `node:` 兼容（fs/net/http2/child_process/worker_threads…）、`--allow-*` opt-in 沙箱、MPL-2.0、纯 Rust + mozjs；发布资产 39–61MB/平台；Android 交叉编译已有但无运行冒烟 | 嵌入需 spawn + 绕 Android exec 限制；体积；项目自述实验性（12★） | 列入候选、优先级最低 |

统一判据（实测时逐项）：单次调用冷/热延迟、驻留内存、APK 增量、超时/取消的硬中断能力、
宿主桥与权限门、现有插件脚本兼容性、真机稳定性、许可与维护活跃度。

结论：**rev2 不升级**。四项只作为将来的评估清单；winterjs2 因「node 兼容 + 权限沙箱」
入选第 4 项，但 CLI-only / 体积 / 未冒烟使其优先级最低。§十一 的 dsh-Go 也以其自身理由
放弃内嵌 JS 引擎（安全边界、绑定维护、自建沙箱），可作交叉印证。

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

复核基线 `7f46f4238`。本次改结论与分期，并补两处外部参照（§8.1、§十一）；
不扩大 §九 增补结论，也未启动任何实施。

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
| §8.1 | 新增 JS 运行时候选基准（WebView / QuickJS / Hermes / winterjs2）；明确不升级、不改结论 |
| §5.3/§六/§9.4/§9.5 | 按代码复核收紧：嵌套写「未实现」、gate/信封/恢复边界、AgentDriver 只包 `generateText`、`needsPerson` 派生来源；§9.4 两条「既有约束」改为「目标 / 现状」 |
| §九.6 | 补阶段名对应关系与单用途 notice 泵说明；§九 的源码分析结论未改 |
| §十一 | 新增 dsh-Go 声明式原生插件协议参照（固定提交 `8e36cdbf3`）；P5 暂缓、市场不并入 |

未改动：§2.1 借鉴依据、§三.2 三种扩展机制、§三.3 概念接口草图、§四 具体清理/审批语义、
§七 可观测性、§九 的固定版本证据索引。
P0 启动准备已在 §十二补入「服务归属表 + 配置/备份迁移结论」；验收证据尚未齐备，不能据此宣布 P0 完成。
本地提交只验证文档结构、链接与差异，不代表批准实施。

## 十一、外部参照：dsh-Go 的声明式原生插件协议

### 11.1 复核范围与定位

复核日期：2026-11-03 晚。固定提交：`8e36cdbf3b151ea819a53ecd88f7be91b497254a`（`main`，2026-11-03），
仓库 [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)（README 写作 `dsh-go`）。复核时
6.8k star / 333 fork；主体 AGPL-3.0，`sdk/` 与 `examples/` 为 MIT，名称与吉祥物素材不在开源授权内。
下列链接均固定到该提交。

定位：**Flutter App + Kotlin 平台层 + PRoot 里运行的真实 DSH 引擎**（`dsh web`），
不是同类客户端架构，插件形态也不能直接移植。本文只取它的**原生插件协议**——在 Android 上，
「App 不执行外来代码、插件声明视图、宿主渲染」最完整的公开样本；关系集中在 §5.4 与 P5 条件项。

范围：README、`docs/architecture.md`、`docs/plugins/*`、`docs/design/native-plugins*.md`、
`docs/design/plugin-market.md`、`market/*`、`sdk/plugin-kit/*`；**未安装依赖、未运行 App、未执行其测试**。
其协议 v1 于 2026-10-25 冻结，v1.1/v1.2/v1.3 均为「只加可选能力」的增量。

结论先行：它证明「声明式协议 + 宿主渲染」在 Android 可用且能撑起市场，也摊开了成本——
协议冻结、SDK、检查/预览工具、市场审核、主机矩阵、版本兼容，合计是一个独立产品的工作量。
这支持 rev2 的「P5 暂缓、不建市场」，并为 P5 若激活提供首选形态。

### 11.2 协议分层与关键机制

```text
插件包（npm，DSH 插件；逻辑跑在引擎的 Node 里）
 ├─ package.json "dshGo" 清单（≤ 16 KiB）：4 个插槽 + wants + minApp
 ├─ 宿主半侧 ctx.dshGo（可选）：on / strip / panel / refresh；emit 数据
 └─ 工具 output.presentationMeta → { "dsh-go/view": View }
        │
   dsh-go-bridge（引擎侧 HTTP/WS：contributions / views / stream / invoke / assets）
        │
   App：固定组件目录渲染（JSON 视图）+ 动作白名单 + 署名 + 用户覆盖
```

| 维度 | 规格（固定提交） |
|---|---|
| 插槽 | `composer.actions` ≤2、`message.actions` ≤2、`composer.strip` ≤1、`panels` ≤4；label 上限 10/12/24/16 字；图标 162 个 |
| 清单 | `dshGo` 序列化 ≤16 KiB；协议 `v`、`minApp`、`wants`（draft/clipboard/message，各 ≤16 KiB） |
| 视图 | JSON 组件树 ≤32 KiB、节点 ≤120、深度 ≤8；视图格式 v1/v2 并存；未知组件画灰色占位 |
| 数据 | 每次 emit 整份，桥接算 Merge Patch；patch ≤16 KiB 否则整份 ≤32 KiB；每源 ≤4 Hz |
| 交互 | invoke 3 秒窗口；效果白名单（8 种）；v1.1 支持延后效果 |
| 降级 | 单项错误只丢该项；连续 5 次违规本次运行降级网页版；只有装了桥接的主机才有插槽 |
| 工具卡 | `presentationMeta` ≤8 KiB；随对话记录持久化，跨主机、重放历史；只增不删 |
| 版本 | NA v1 只加可选字段；v1.1 延后效果 / v1.2 可选能力协商 / v1.3 选择文件；view v2 独立分支；`caps` + `adaptView` |
| 署名 | 每个插槽显示插件身份；工具卡在工具行；「插件内容」标记不可覆盖 |
| 市场 | 独立目录 + worker：只索引、只做静态检查（OSI 许可、精确版本、压缩与展开均 ≤50 MiB、SHA-512、启发式风险标记），不托管、不执行插件代码 |

关键设计取舍（其 `native-plugins.md` §2.1/§2.6）：Flutter release 没有 Dart 解释器，不能加载外来
Flutter/Dart；于是在「网页岛 / ui.json / 服务端驱动原生组件 / 内嵌 JS 引擎」四条路线中选第三条，
并明确放弃内嵌 JS 引擎——理由不是做不到，而是「插件最终仍输出同一棵组件树，只多离线逻辑与零往返；
代价是主机下发的代码进入 App 进程、社区绑定维护、自建沙箱/超时/内存上限」。其自估投入：
第 0 期 3–4 人日、第一期 15–19、第二期 16–22，且它已有约 5.3k 行 `dsh-ui` 渲染器。

### 11.3 可吸收的协议原则（只取原则，不引代码）

1. **固定组件目录 + 有界样式**：宿主只认一套组件；透明度/渐变/任意尺寸/圆角/定位/动画/外链图/SVG/文字内链接/HTML/自定义组件一律不开放，改用枚举样式、语义色与按钮动作。
2. **能力协商 + 显式降级**：`caps` 告知 App 能画什么、有哪些插槽、什么语言；`adaptView` 降级；未知组件占位不崩；旧 App 忽略新字段。
3. **协议只做加法**：v1 内只加可选字段；破坏性变更才升主版本；视图格式独立版本；`minApp` 与 `engines.dsh` 双向约束。
4. **硬上限 + 违规降级**：清单/视图/数据/效果都有字节与频率上限；超限记问题并降级，不整页崩溃；连续违规降级到兼容形态。
5. **署名与归属不可覆盖**：每个插槽显示插件身份，工具卡在工具行；「插件内容」标记由宿主控制。
6. **持久化视图永久兼容**：工具卡写进对话记录，只增不删、只用稳定组件、不放临时链接——历史重放不依赖插件当前安装。
7. **主机矩阵显式声明**：工具卡全主机（随记录走），插槽仅装有桥接的主机；中转主机明确不支持。
8. **作者工具链是一等公民**：SDK 类型 + 组件构造器 + 测试宿主 + `check` 命令（清单/视图/文字长度/未知组件/中英文各量）+ 机器可读 schema + 本地预览；零代码起步（工具卡只加一个 `presentationMeta`）。
9. **市场只做索引与静态检查**：许可/版本/体积/哈希/启发式风险标记；明确「风险标记不等于安全证明、不解析依赖树」——运行时强制边界仍在宿主。

### 11.4 不照搬的边界

- **没有引擎宿主**：dsh-Go 的插件是 npm/DSH 插件，逻辑跑在引擎的 Node 里，桥接只是渲染与事件通道；
  本仓外部能力是 JS（WebView）/MCP/JSON 规则，没有 npm 插件宿主。等价物只能落在「JS 插件 + 声明式视图 + Compose 渲染器」。
- **不建市场**：其市场是独立仓库 + worker + 审核策略；本文 §一 与 §六 仍明确不建设，也不把市场并入运行时。
- **成本量级**：它已有渲染器仍自估 3.5–4.5 人周；本仓若做 UI 插槽需自建渲染器、协议、SDK、检查/预览器与文档，量级只多不少——这是 P5 暂缓的直接依据。
- **许可与素材**：其主体 AGPL-3.0（与本仓相同，但本文不复制代码）；名称、标识与吉祥物受限，任何引用只做协议对照。
- **它自己也明确不做**：内嵌 JS 引擎、替换官方界面/主题、任意绘制与动画——与本仓「首轮不开放 UI 插槽、不替换核心策略」同向。

### 11.5 对本设计的增量决议

| 建议 | 时机与范围 | 验收 |
|---|---|---|
| P5 保持暂缓；若激活，首选「声明式原生插件协议」形态 | P5 条件项 | 先出工具卡最小视图，再谈插槽 |
| 若做 UI 插槽，采用「固定组件目录 + 有界样式 + caps 协商 + 加法版本」 | P5 | 未知组件占位不崩；旧版本仍能画旧视图 |
| 工具结果优先走「随记录持久化的声明式视图」，而不是运行时代码 | P2/P5 交接处 | 历史重放、跨主机、模型不可见 |
| 市场/审核不并入运行时；将来需要则独立立项 | 不排期 | 只做索引与静态检查，不执行插件代码 |
| JS 运行时升级维持 §8.1 结论 | 不排期 | 出现证据再实测；其放弃内嵌引擎的论证可交叉印证 |

本节不扩大 P0/P1 范围，不改 rev2 的结论与 §八 估算（与 §九 的约束相同）。

### 11.6 固定版本证据索引

| 主题 | 固定提交链接 |
|---|---|
| 项目定位与运行方式 | [README](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/README.md)、[architecture](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/architecture.md) |
| 插件作者文档 | [plugins README](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/README.md)、[slots](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/slots.md)、[versions](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/versions.md)、[hosts](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/hosts.md)、[design-rules](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/design-rules.md)、[components](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/components.md)、[actions](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/plugins/actions.md) |
| 协议与设计取舍 | [native-plugins](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/design/native-plugins.md)、[native-plugins-protocol](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/design/native-plugins-protocol.md)、[native-plugins-views](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/design/native-plugins-views.md) |
| 市场 | [plugin-market](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/docs/design/plugin-market.md)、[market README](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/market/README.md)、[policy.json](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/market/policy.json) |
| 作者工具链 | [sdk/plugin-kit README](https://github.com/ccch1mneyyy/dsh-Go/blob/8e36cdbf3b151ea819a53ecd88f7be91b497254a/sdk/plugin-kit/README.md) |
| 桥接与 App 渲染 | [dsh-go-bridge](https://github.com/ccch1mneyyy/dsh-Go/tree/8e36cdbf3b151ea819a53ecd88f7be91b497254a/runtime/bridge/dsh-go-bridge)、[app/lib/plugins](https://github.com/ccch1mneyyy/dsh-Go/tree/8e36cdbf3b151ea819a53ecd88f7be91b497254a/app/lib/plugins) |

相关 schema 与 `check` 实现在 `sdk/plugin-kit/schema`、`sdk/plugin-kit/bin/check.mjs`，
本次未逐行阅读、未运行；市场检查规则的边界与误报说明以 market README 为准。

## 十二、P0 启动准备（2026-11-04）

本节以本地 HEAD `113772825` 为代码基线，工作区开始时干净。用户要求「准备推进插件化重构」，
本轮落实代码盘点、边界决策与实施拆分；文件保持本地草稿，产品行为未切换。§一至§十一的
外部项目资料沿用历史记录，本轮未重新核验上游，不据此新增依赖或平台承诺。

### 12.1 首批范围与类型归属

继续采用 rev2 第一层。P1 先沿用现有模块，在 app 内抽出转换链装配器；公共契约先形成源码草图，
有实际独立消费者后再决定 Gradle 提取，不先创建 `runtime-api` / `plugin-runtime` 空模块。

| 类型或能力 | P0/P1 归属与实例范围 | 选择、覆盖与兼容规则 |
|---|---|---|
| `UIMessage` / `UIMessagePart` / 消息角色 | 继续归 `ai`；运行内传递现有消息值 | 不复制消息模型、不改历史序列化类型；提取纯 Kotlin 模块另行评估传递依赖 |
| `Tool` / `InputSchema` | 继续归 `ai/core`，宿主按 Run 装配 | `Tool` 含执行闭包、模型和消息依赖，并非可直接外发的纯 schema；P2 再分离外部描述与宿主执行 |
| `RikkaPlugin` / 能力清单 | 继续归 `plugin-api`，Host 注册 | 保留 Compose 设置入口；该模块与 `ai` 都依赖 Android/Compose，不能宣称公共 API 已平台无关 |
| `PluginManager` / 状态与授权 | `plugin-manager` 的 Host 单例 | 唯一插件注册表；P1 增实例代次与清理所有权，保持当前启停及授权入口 |
| 输入/输出转换器、`TransformerContext` | app 内受信任契约；每次执行构造上下文 | 多实现有序集合；Context、Settings、Assistant 不对外暴露；外部 JS 继续使用既有受控桥 |
| 转换链装配器 | app 宿主长期服务，按调用构建链快照 | 显式阶段与稳定贡献 id；旧插件段先保留注册顺序，不能直接改成 id 排序导致语义变化 |
| 时间、模板、工作区提醒与协议配平 | 宿主提供贡献；时间提醒作首个注册化样例 | 先完整保持链序；工具结果配平固定为宿主末阶段，插件不能覆盖或跨越 |
| 工具 UI 注册与通用兜底 | app UI，Host 注册、插件实例拥有贡献 | 清理句柄必须绑定 owner/generation；缺插件沿用通用渲染，不改历史工具名 |
| 搜索、MCP、Skills、工作区 | 各现有模块/宿主服务，权限按现有执行边界 | 保留现有选择和配置；首批不允许插件替换宿主网关或审批策略 |
| 模型目录与 Provider 路由 | `ai` Provider 契约 + app 目录仓库/缓存 | 仍按 ProviderSetting/凭据作用域选择；本期只作对照评审，不开动态 Provider 注册 |
| 记忆、压缩、标题、生成循环与任务控制 | 现有 app 服务；会话/Run 隔离沿用现状 | 保持内置实现与现有参数，不增加覆盖层或新的状态真相源 |

P1 契约草图：贡献描述至少有 `id`、输入/输出方向、阶段、稳定顺序、预热适用性和所有者；
注册返回可幂等释放的句柄，句柄捕获实例代次。装配器返回现有 transformer 列表，
继续调用 `transformsCollectingVolatileSpans` / `visualTransforms` / `onGenerationFinish`。
这只是仓内装配接口，不是第三方稳定 SDK；依赖图、修订持有、热更新不进入本批。

### 12.2 必须固定的行为样例

源码锚点：`ChatService.kt` 的 `inputTransformers`、`outputTransformers`、
`buildInputTransformerChain`、`buildInputTransformerChainWithoutPlugins`、`assemblePipeline`；
`Transformer.kt` 的三个执行入口；`BuiltInPlugins.kt` 的 `PluginToolUIObserver`。

| 场景 | 当前事实 / P1 验收要求 |
|---|---|
| 实发输入顺序 | TimeReminder → PromptInjection → TavernMacro → Placeholder → DocumentAsPrompt → Ocr → Quote → Template → WorkspaceReminder → 插件注册顺序 → ToolResultBalancing；迁移前后相同 |
| 输出顺序 | ThinkTag → Base64ImageToLocalFile → RegexOutput → 插件注册顺序；存储转换、视觉转换、生成结束分别验证，不能合并钩子 |
| 预热与预览 | 预热传 `includePluginTransformers=false`；`assemblePipeline` 默认 true，预览与实发共链可能执行插件。先固定两种基线；安全预览要另列行为变更，不能作为重构顺带改变 |
| 预览安全的待决策点 | §5.1 的「预览/预热不执行副作用钩子」是目标，当前尚未完全满足；P1 需定义可执行钩子标记及预览不完整提示，再启用新预览策略 |
| token 与易变区段 | 保持消息 id 对齐、UTF-16 范围重定位和 token 计数；保留子代理 notice 恢复与 `messagesForRequest` 收尾 |
| 授权撤销 | 输入/输出构链时保留 enabled + grants 过滤；工具、HTTP、存储继续执行时检查，不把注册时授权当作长期许可 |
| 同 id 重装 | 旧 onEnable/onDisable、延迟观察者与旧 dispose 都不能登记或清除新实例的贡献；测试用挂起屏障构造交错，不靠延时碰运气 |
| 启动失败与取消 | 插件 onEnable 或贡献安装中途失败不得留下已发布的可用贡献；协程取消原样传播；失败清理后可再次安装 |

本轮确认的两个实施风险：`PluginManager` 的类注释称回调在锁内，实际 register/setEnabled/
unregister 在释放锁后调用生命周期与观察者；不能按注释推导并发安全。工具 UI 观察者用
`unregister(toolName)` 清理，尚无实例归属；仅给 manager 加代次而不改贡献清理仍不能闭环。
P1a 必须同时覆盖生产者回调、消费者登记和清理，避免只修一个 Map。

### 12.3 配置、迁移与备份结论

1. P1 不增持久化字段；插件启用/授权继续只有 `plugin_states` 一份真相源，规则/JS 定义继续各自保存。
2. P4 的助手绑定沿主 Settings 迁移链扩展，同时实现备份导入的 `data/datastore/migration/SettingsJsonMigrator.kt`。
   当前是 V1–V6，V7 仅为当前候选号；实施时重新检查是否被其他功能占用，不把版本号当作预留锁。
3. 插件定义、启停/授权和插件持久化存储需要显式备份覆盖与恢复语义。`BackupFolderRegistry`
   管理 `FileFolders` 目录，不能靠给它加一行就声称独立 Preferences DataStore 已覆盖；
   需要备份引擎的逻辑条目/导出导入及往返测试。P1 不借此改备份格式。
4. 模型目录快照仍是可重建缓存，不进备份；新增持久化文件目录登记 registry 并补覆盖测试。
5. `ignoreUnknownKeys` 只能保证读取，不保证旧版回写保留新绑定。降级探测、缺插件保留配置、
   旧备份无授权记录时的恢复策略须在 P4 明确，未定之前不声称配置降级无损或备份可恢复。

### 12.4 第一批实施拆分与退出条件

| 批次 | 具体交付 | 合入前证据 |
|---|---|---|
| P0 剩余基线 | 固定上述样例、现有合约单测与同条件设备性能采样 | 记录命令、结果、设备/样本和阈值；源码盘点不能替代运行证据 |
| P1a 注册所有权 | manager 实例代次/句柄、生命周期失败清理、工具 UI 按归属释放；修正线程模型注释 | 同 id 重装交错、旧回调/清理、重复 dispose、失败重试、取消与既有授权测试通过 |
| P1b 链装配抽取 | 内置/插件统一贡献描述与装配器；TimeReminder 试点；ChatService 只消费链结果 | 旧链与新链纯数据样例一致，新增转换器只改注册定义；配平末位不可被插件重排 |
| P1c 行为验收 | 输出三类钩子、token/notice、预热、预览策略与旧 JSON/JS 插件兼容 | 不重复执行有副作用的旧/新路径；WebView/生命周期实机验证，设备性能与 P0 同条件对照 |
| P1 决策记录 | 记录目录契约对照结论和是否需要第二层 | 无新的运行时消费者证据则停在第一层，验收回滚窗口后移除旧链和试点开关 |

P1a 的注册身份由 manager 生成，释放时比较身份而非只比较插件 id/工具名；待实现时选定
串行生命周期或受代次保护的状态机，不能通过持有全局 Mutex 调用任意插件回调造成反向调用死锁。
P1b 起每次调用只选一条装配路径，保留临时宿主开关以便回滚；该开关不授予插件额外权限。

### 12.5 本轮验证与未完成项

- 已复核上述本地源码和模块构建文件；本轮改动仅为本设计草稿。
- 尝试命令：`./gradlew.bat :plugin-manager:testDebugUnitTest --console=plain`。
  首次失败于 Gradle 启动阶段，自动下载 JBR 21.0.10 的 HEAD 请求失败。
  本机实际已有 PATH 中的 Temurin 21.0.12，以及 Android Studio 自带的 JetBrains JDK 21.0.8；
  仓库固定 `toolchainVendor=JETBRAINS`，首次未发现本机 JBR，不能将下载失败等同于未安装 JDK 21。
  本轮不修改仓库 toolchain 要求，通过当前命令进程的环境与显式发现路径复用本机工具链：

  ```powershell
  $env:JAVA_HOME='D:\android-studio-2025.2.2.8-windows\jbr'
  $env:GRADLE_USER_HOME='D:\A-projects-relaygradle'
  .\gradlew.bat '-Dorg.gradle.java.installations.paths=D:\android-studio-2025.2.2.8-windows\jbr' :plugin-manager:testDebugUnitTest --console=plain
  ```

- 重试结果：`BUILD SUCCESSFUL`（1m 3s）；`PluginManagerTest` 实际执行 14 项，失败 0、错误 0、跳过 0。
  JDK 发现问题已解决，后续验证沿用上述本机环境。此结果只覆盖插件管理器现有单测。
- 尚需补齐：转换链等其余相关单测基线、P0 行为样例的执行证据及设备性能基线。
  因此本轮完成的是 P0 启动准备，P0 验收与 P1 实现均未完成。

## 十三、P1a 实施记录（2026-11-04）

用户明确要求「开始实施」后进入本批；§十二保留启动准备当时的结论。本批先完成注册所有权，
不以本批替代 P0 的转换链/性能基线，也不宣称 P1 全部验收完成。

### 13.1 实现边界

- `PluginManager.register` 返回注册实例句柄，重复 id 仍为 no-op；旧句柄释放时比较 Entry 身份，
  不能卸载同 id 的新实例。每次重新启用创建独立 Activation 资源容器，身份对象承担代次语义。
- 全局 Mutex 只保护状态；生命周期回调按 Entry 串行、在全局锁外执行。启动完成后才发布
  `enabled=true`，停用或卸载先撤销贡献资格，再等待已有回调退出。回调可操作其他插件和授权，
  递归等待自身或祖先生命周期会显式失败，不依赖全局锁避免竞态。
- 新宿主观察者使用 `PluginResources.register` 安装短小同步贡献。关闭后延迟回调不能安装；
  清理逆序、幂等且逐项尝试，聚合异常继续上抛。旧观察者签名保留作源码兼容，新增贡献必须走资源容器。
- 启动/观察者失败或取消会撤回本次资源并尝试 onDisable；保留失败插件的不可调用视图及
  `PluginInfo.failure`，支持重新启用。启停写盘成功后才改变内存意图；失败后仍可明确禁用并持久化。
- 工具 UI 返回单次注册句柄，关闭旧句柄不会删除新贡献；覆盖项释放后恢复仍有效的上一项或内置渲染器。
  旧宿主按名注销入口保留，但插件观察者已不再使用它。
- JS 的网络/工具/存储授权闭包同时核对注册的插件对象身份，阻止旧对象借同 id 新实例获得授权。

本批仍是受信任宿主契约，不是原生恶意代码沙箱。停用会等待挂起的生命周期回调收尾，
未引入强杀原生回调、热更新依赖图或配置修订。UI 实机验收及后续转换链试点仍需分别记录。

### 13.2 验证记录

沿用 §12.5 的本机 JBR 与 Gradle 缓存，最终命令：

```powershell
.\gradlew.bat '-Dorg.gradle.java.installations.paths=D:\android-studio-2025.2.2.8-windows\jbr' :plugin-manager:testDebugUnitTest :app:testDebugUnitTest --tests 'app.androidrik.rikkahub.data.plugin.*' --console=plain
```

结果：`BUILD SUCCESSFUL`；管理器 27 项（既有 14 + 新增生命周期/资源测试 13），app 插件相关
99 项（含新增工具 UI 注册测试 2），共 **126 项，失败 0、错误 0、跳过 0**。
app Kotlin/Java 编译通过；`git diff --check` 通过。未安装 APK、未执行 WebView instrumented
或 UI 实机验收，不能据此声明 Android 生命周期整体验收完成。

新增测试用 `CompletableDeferred` 屏障覆盖同 id 重装、挂起观察者、启动/安装失败与重试、
启用取消、停用取消、启停交错、清理失败继续释放、写盘失败、旧句柄幂等及嵌套生命周期等待环；
工具 UI 验证同一 renderer 对象重复注册时的旧清理隔离与覆盖项释放后的回退。
下一批为 P1b 转换链装配抽取，仍需先固定对应行为基线；本批没有引入运行时模块或更改持久化 schema。

## 十四、P1b 转换链装配抽取（2026-11-04）

按「提交后继续 P1b」要求，先将 P1a 代码、测试和启动准备/实施记录提交为 `83064c441`，
随后在此基线上实施本批；本批本地提交在「提交后继续 P1c」中获授权，未推送。

### 14.1 装配与兼容

- 新增 app 内 `TransformerChainRegistry`：贡献具备稳定 id、类型化输入/输出方向、宿主阶段、
  预热适用性。同阶段保持声明顺序；插件保持 manager 注册顺序，不按 id 重排。
- 内置定义集中于 `defaultTransformerChainRegistry`，时间提醒成为普通 CORE 贡献；
  新增/替换受信任内置转换器只改注册定义，`ChatService` 不再维护手写转换器链。
- 插件只能进入 PLUGIN 阶段。工具结果配平由宿主固定追加到 FINALIZER；
  重复/空 id 或占用保留阶段明确失败。本期使用固定阶段和稳定顺序，不提前实现依赖图及 before/after 拓扑排序。
- 普通生成一次读取插件状态，同时装配输入/输出列表；预览仍包含插件，预热仍排除插件。
  装配只返回原 transformer 对象，不执行钩子、不调用网络/存储、不创建 WebView。
- `GenerationHandler` 及三个执行入口保持原实现；易变区段、notice 保护和 token 计数路径不变。
  子代理沿用只含内置的输出链，没有隐式继承聊天插件；定时注入继续复用聊天路径。
- `USE_REGISTERED_TRANSFORMER_ASSEMBLY` 是临时宿主顺序回退开关：true 按阶段稳定排序，false
  按原有声明顺序拼链，授权筛选与协议末环两条路径共用；它不回退整个 PluginManager 或执行器。
  每次调用只装配并执行选定路径；验收比较仅使用纯数据样例，不重复执行有副作用的旧/新链。
  P1 验收和回滚窗口结束后删除该开关及旧序分支。

### 14.2 验证与后续

沿用 §12.5 环境，执行：

```powershell
.\gradlew.bat '-Dorg.gradle.java.installations.paths=D:\android-studio-2025.2.2.8-windows\jbr' :app:testDebugUnitTest --tests 'app.androidrik.rikkahub.data.ai.transformers.*' --tests 'app.androidrik.rikkahub.data.plugin.*' --tests 'app.androidrik.rikkahub.utils.*Token*Test' --tests 'app.androidrik.rikkahub.service.PromptInspection*Test' --console=plain --quiet
```

最终复核 **264 项，失败 0、错误 0、跳过 0**，包括新增装配测试 8 项；覆盖旧链对象顺序、插件次序、
授权撤销/停用/卸载、旧快照稳定性、预热与预览差异、保留阶段、重复 id、装配无副作用、
时间提醒实际执行和易变标注、内置与插件输出存储/视觉/结束钩子的独立顺序。app 编译及
`git diff --check` 通过；未进行设备性能测量或 WebView/流式 UI 真机验收。

P1c 尚需处理：预览副作用策略（本批刻意保持旧行为）、JSON/JS 的实机链路、同条件性能基线与
模型目录契约对照评审；P1 动态运行时决策门仍未通过，不据本批新增运行时模块。

## 十五、P1c 预览策略与验收（2026-11-04）

按「提交后继续 P1c」先提交 P1b 为 `124fa4c94`，本批随后获「本地提交」授权；详细证据见
[`plugin-p1c-2026-11-04.md`](../verification/plugin-p1c-2026-11-04.md)。本节记录当前行为，
§十四的「预览保留插件」属于 P1b 当时基线，现由下述显式策略替代。

- 输入贡献新增 `allowPreview`，默认 false。PREVIEW 只执行宿主审核的只读贡献，WARMUP 另需
  `allowWarmup`；真实 REQUEST 仍使用完整原链，两种排序装配模式都不能绕过预览筛选。
- 外部 JS 不具备宿主 `PreviewSafePluginInputTransformer` 标记；纯 JSON 规则由原生适配器实现该标记，
  预览仍可执行，预热仍不运行任何插件。标记不表示可对恶意原生代码提供安全隔离。
- 内置 OCR 会发模型请求，工作区提醒可能建立 SSH 连接/执行探测，故两者在预览与预热中省略。
  实发保留；文档/模板等只读转换仍执行。这里约束的是转换钩子，未声称整个工具装配体系没有网络行为。
- 省略项随 `PromptInspection` 返回，在拼装页与容量面板提示「实际请求与 token 数可能不同」，
  精确 BPE 回填不会移除该提示。纯文本/视觉模型请求不报缺少 OCR；未绑定工作区不报缺少环境提醒。
- 模型目录对照结论：现有 `Provider.fetchModelCatalog` 四态结果与 app 仓库的凭据 scope、刷新任务
  所有权和独立缓存已满足当前消费者；本批保留其归属，不新增动态目录服务或 Provider 注册图。

自动化验证：app JVM **312 项**、ai 目录协议 JVM **16 项**、PJX110 真机 instrumentation **8 项**，
均失败 0；编译、debug APK 与 test APK 构建通过。真机验证真实 WebView 的输入/结束钩子、存储隔离、
撤权、取消后新求值、关闭后不能复活，及 JSON 规则预览兼容。测试使用独立 Application 和临时目录。

同设备装配微基准（25 插件、200 次预热、500 样本、交替采样）：旧序分支 median/p95
0.048177/0.058750 ms，注册分支 0.068177/0.081615 ms。新增排序/筛选存在小幅开销，未宣称加速；
通过预先固定的 `p95 <= max(4 × 旧序 p95, 2ms)` 护栏。这是同一代码下两种装配算法的比较，
不是重构前后二进制的端到端性能对比。

整期仍待：人工流式 UI/后台恢复验收、首 token/内存/WebView 创建次数的完整 P0 对照以及
P1 决策门记录。微基准不能替代这些证据；暂保留顺序回退开关，不创建第二层运行时。

## 十六、P2 工具统一：启动准备（2026-11-06）

本节以本地 HEAD `d68a6bb9c` 为代码基线，工作区干净；仅做 P2（工具统一，§六 收窄版）的实施准备，
本轮未修改任何产品代码。P1a/P1b/P1c 的本地提交（`83064c441`、`124fa4c94`、`3add4f081`）未推送。

### 16.1 实施前置门槛（P1 收尾，先于 P2）

P2 动工前必须先关闭三项 P1 遗留（§十五 验收边界），顺序不可调换：

| 门槛 | 内容 | 完成判据 |
|---|---|---|
| P1 人工验收 | 流式聊天 UI、后台恢复的真机走查（PJX110） | **已通过（2026-11-06，用户人工确认）**。流式聊天 UI 与后台恢复真机走查无异常 |
| P1 完整性能对照 | 首 token 延迟、装配耗时、内存、WebView 创建次数与 P0 基线同条件对照 | **已完成（2026-11-06/07，PJX110 真机，智谱 glm-4.7）**：装配稳态 median 54.5→98.5us（+44us，与 §十五 微基准同向同量级）；首 token 分布高度重叠（median 3555/4323ms，服务端方差主导，无应用侧回归信号）；WebView 创建均为 0；PSS 终点 292/288MB；FATAL 0。详见 [`plugin-p1-perf-2026-11-06.md`](../verification/plugin-p1-perf-2026-11-06.md) |
| P1 决策门记录 | 书面结论：停在第几层（§六 判据：第三方扩展不随 APK / 同能力运行时切换 / 热修复，无证据则停第一层） | **已记录（2026-11-07）：放行第二层**，判据与边界见 §16.6；P2 顺序不变，第二层另立批次 |

决策门结论无论停在哪一层，**都不改变 P2 的范围**——P2 是第一层内部工作；若决策门放行第二层，
P2 仍按本节拆分实施，第二层另立批次。

### 16.2 现状盘点（四个统一维度的事实）

锚点：`app/.../data/ai/tools/ChatToolFactory.kt`（`createToolSegments`，`:153`）；
`ai/.../core/Tool.kt`；`app/.../data/plugin/PluginContracts.kt`（`PluginToolProvider`，`:29`）。

**① 来源标识。** 来源只存在于 `ToolSegment.source` 字符串（`ChatToolFactory.kt:542`），`Tool` 本身
无来源字段。现有段名：`memory`/`search`/`local`/`image`/`task_manage`/`conversation`/`workspace`/
`skill-install`/`skills`/`knowledge`/`subagent`/`mcp:<server>`/`mcp-dynamic`/`plugin:<id>`。
段名是**对外契约**：子代理信封按段名判断来源开关（`:150-152`），折叠工具按名提升（`:372`），
段装配改动必须保持这些语义。插件段在末尾（`:458`、`:476`），能力在装配点校验。

**② 审批策略。** 唯一机制是 `Tool.needsApproval` 闭包（`ai/core/Tool.kt:20`，默认不放行拦截），
按来源各自为政：记忆工具按动作分档（`memoryToolNeedsApproval`，`:60-67`）；MCP 原生档透传
`tool.needsApproval`（`:351`），折叠桥跟随被调工具（`:426`，折叠不得成为审批绕行）；其余内置工具
基本默认 `{ false }`；插件 `tools()` 直接返回 `ai` 的 `Tool`，审批闭包由受信任插件自定；外部 JS
另有执行时宿主审批（TOOL_REGISTER 检查），与本机制叠加。**没有一个统一的「来源 → 审批基线」表。**

**③ 诊断。** 仅有 `logSchemaBreakdown`（`:488`）按段打印工具数与 schema 估算 token，走 logcat；
来源、审批分档、超时上限（`Tool.executionTimeoutMillis`）不在诊断输出里。自管超时工具
（`workspace_shell` 615s、`dispatch_subagent` null）靠注释约定，无机器可读清单。

**④ 历史展示。** 插件工具 UI 经 `ToolUIRegistry.resolve`，缺失回退通用渲染器（P1a 已把注册句柄
绑定实例代次）；内置工具的渲染散在 `ChatMessageTools` 等处，与插件 UI 机制不同源。历史工具结果
按工具名寻址渲染，工具名即隐性的历史契约（MCP 净化名、`get_tool_map`/`mcp_tool_call` 均不得随意更改）。

**跨段工具名冲突未检测**：唯一去重在 MCP 净化名（`usedNames`，`:339`）；`plugin:<id>` 段与内置段、
MCP 段之间无重名检查，§三.2「同一作用域内重复工具名直接报冲突」尚未落实。

**命名现状盘点（2026-11-06 初稿，2026-11-07 真机走查后修正）**：内置工具 21 个，全部 snake_case
且多数带域前缀（`workspace_*` 9 个、`*_skill` 3 个、`memory_tool`、`search_web`、`task_manage`、
`get_tool_map`、`mcp_tool_call` 等）；MCP 净化名仅在段内 `usedNames` 去重。~~JS 插件工具为裸短名、
冲突风险真实~~（**修正**：初稿只读了 manifest，漏查转换链——`pluginToolName`
（`JsToolDefinition.kt:39`）把 JS 工具名命名空间化为 `rikka_<sha256(pluginId) 前 8 位>_<name>`
且声明名受严格正则约束，**JS 路径结构性不可能与内置重名**；重名闸门的真实暴露面是**受信任
Kotlin `PluginToolProvider`**——它直接返回任意 `Tool` 名、无前缀。闸门保留，价值面据此修正，
实测见 §18.5。）

### 16.3 P2 拆分草案与退出条件（2026-11-06 拍板后修订）

| 批次 | 交付 | 退出条件 | 回滚 |
|---|---|---|---|
| P2a 来源标识与冲突 | 来源标识类型化（**internal enum，见 §16.4 拍板 3**；序列化回段名字符串，字符串对外契约不动，`Tool` 侧不新增字段给模型可见面）；跨段重名检测（**拍板 1：报错拒绝装配**，模型可见名一律不变）；诊断补来源/审批/超时维度 | 段名信封语义不变；重名有确定行为（拒绝装配+用户可见报错）；logcat 诊断可回答「这个工具从哪来、要不要审批、超时多少」 | 枚举迁移按「序列化值=原字符串」逐点替换，删除检测与诊断行即回滚；枚举回退为常量表成本可控 |
| P2b 审批与展示统一 | 固化「来源 → 审批基线」表（含自管超时清单）；内置与插件审批语义对齐（插件闭包仍受信任，但宿主执行边界与超时上限不变）；历史展示兜底路径与内置渲染对齐审查 | 表入文档；任何工具调用前审批决策可解释；MCP 折叠桥不绕行、配平/门控/预算仍宿主强制；历史展示回归通过 | 审批表默认值等价现状，无行为差异回滚 |

红线（沿用 §六 P2 行）：MCP / Skills / 工作区保持内置能力不迁移；工具 schema 与模型请求面不变；
段装配与协议收尾保持宿主强制，插件不能重排或跨越。

### 16.4 拍板记录（2026-11-06，用户四项已定）

1. **重名策略：报错拒绝装配。** 跨段重名时该插件工具段拒绝装配并向用户报错，模型可见名一律不变；
   不做命名空间前缀改名（隐性改名会牵动历史按名寻址，复杂度更高）。代价：内置新增同名工具时旧插件
   装配失败——失败可见、可修，优于静默冲突。
2. **审批基线默认值：维持现状。** 统一机制与文档化，默认值与当前完全等价（`workspace_shell` 不借机
   改为默认拦截）。零行为变更，符合「重构不顺带改行为」原则；引入分档将来若要做，走独立授权。
3. **来源标识形态：引入类型化枚举（internal）。** 以 enum 为单一真源，序列化值 = 现有段名字符串，
   信封判断、诊断、审批表均引用 enum，消除字符串散落；迁移点为全部段名字符串引用处。红线：对外
   序列化字符串逐字符不变，信封语义由单测锚定。
4. **诊断出口：logcat 维持现状。** P2 只补强 logcat 维度（来源/审批档/超时），不做 UI 页面；
   §七 诊断入口留待独立批次。

### 16.5 本轮验证与范围声明

已复核：`ChatToolFactory.kt` 全文、`ai/core/Tool.kt`、`PluginContracts.kt`、
`TransformerChainRegistry.kt` 开关现状、`ToolUIRegistry` 引用面。2026-11-06 二次复核：§16.2 全部
行号锚点与 HEAD `d68a6bb9c` 逐条比对一致（注意包名已为 `app.androidrik.rikkahub`，本文用缩写路径），
并完成 JS 插件命名盘点（见 §16.2）。未运行测试、未构建、未动产品代码；
本轮改动仅为本节文档。P2 估算按 §八「第一层扩展」口径，待 P2a 完成后重新校准。

### 16.6 P1 决策门记录（2026-11-07）

**结论：放行第二层（动态运行时）——作为后续批次立项依据，非立即实施授权。**

- **判据与证据**：§八 开放问题 4 三判据中，用户于 2026-11-07 明确给出两条的需求陈述：
  「运行时切换 / 热修复——这可以更灵活即时的看到效果和调试」。即需要（a）同能力实现可切换、
  （b）修复不随 APK 发版即时生效，以支撑插件体系的日常开发调试与迭代。属产品需求证据，
  非实测数据；与 rev2「降级为条件选项，条件出现再启用」的预设一致，条件已出现。
- **第三判据（第三方扩展不随 APK）**：本次未主张；第二层批次立项时若仍无此需求，
  动态加载的外部面继续收敛在受控 JS / MCP（rev2 安全边界不变）。
- **范围与顺序不变**：P2（工具统一）仍按 §16.3 先行——第二层依赖第一层的来源标识、审批表
  与冲突检测；第二层（运行时切换/热修复机制：实现注册、代次切换、修订热生效、失败回退）
  在 P2 完成后另立设计批次，先出设计稿再实施。
- **边界重申**：放行第二层不改变 §六 红线（宿主强制段装配、协议收尾、门控/预算/LoopGate）；
  热修复通道的权限模型（谁能推修订、修订是否需签名/审批）是第二层设计稿必须回答的第一题。
- **试点开关**：`USE_REGISTERED_TRANSFORMER_ASSEMBLY` 旧序分支的删除不受本结论影响——
  按 §六 迁移规则「替代路径完成验收、回滚窗口结束后删除」执行（性能对照已完成，回滚窗口
  起算日 2026-11-06）；继续第二层更无理由保留永久双轨。

## 十七、P2a 实施记录：来源标识、重名闸门与诊断（2026-11-07）

按 §16.3 拍板后拆分实施本批；本轮未提交（待授权）。代码基线 `144c3fd57`。

### 17.1 交付内容

- **来源标识类型化（拍板 3）**：新增 `data/ai/tools/ToolSource.kt`——sealed 类型枚举作单一真源，
  固定来源 12 个为 data object，参数化来源（`McpServer(serverName)` / `Plugin(pluginId)`）为
  data class（enum 常量无法携带运行时参数，此为「类型化枚举」的 Kotlin 等价落地）。
  [ToolSource.segmentName] 序列化值与历史段名字符串**逐字符相同**，由单测
  `tool source segment names are frozen contracts` 逐项锚定。消费点全部切换：
  [ToolSegment.source] 类型改 ToolSource、信封 `CAPABILITY_SOURCE_SEGMENTS`/workspace 剔除、
  派发上下文、schema 诊断均引用同一处，消除散落字面量。
- **跨段重名闸门（拍板 1）**：纯函数 `acceptPluginToolSegments`——插件工具与内置/MCP 段、
  先前已接受插件段同名，或插件段**内部**重名 ⇒ **该插件段整体拒绝装配**（不做前缀改名，
  模型可见名一律不变）。ChatService 主装配路径经 `onPluginToolsRejected` 回调落用户可见
  错误（标题「插件工具名称冲突，该插件的工具本轮未启用」+ 冲突明细）；预览/子代理路径
  传 null 只记日志。`createTools`/`createToolSegments` 收 internal（仅 app 模块消费）。
- **诊断补维度（拍板 4）**：`logSchemaBreakdown` 每段追加 `appr=`（按**引用**识别默认闭包
  ——ai 模块新增规范实例 `TOOL_NO_APPROVAL` 作为 `Tool.needsApproval` 默认值；字面量
  `{ false }` 宁可多报 dyn 不漏报）与 `to=`（段内最大外层上限秒数 + `+Nself` 自管超时计数）。
  logcat 一行可回答三问：从哪来（段名）、要不要审批（appr）、超时多少（to）。

### 17.2 验证与边界

- 测试：tools 包 **229 项 0 失败**（新增 7 项：段名锚定 ×1、闸门行为 ×3、审批/超时画像 ×2、
  既有迁移）；关联套件（transformers/plugin/Token/PromptInspection + ai 模块）合计
  **778 项 0 失败 0 错误**；app+ai 编译通过，`git diff --check` 干净。
- 未做真机验证（闸门无既有触发者：当前样例插件工具名 `note` 无冲突；真机路径留待
  P2a+P2b 一起走查）。行为面：无插件冲突时与改动前**逐字节等价**（段名、顺序、审批闭包、
  超时全部不变）。
- 回滚：删除 ToolSource.kt、`acceptPluginToolSegments`/画像函数与两处回调接线即回滚；
  `TOOL_NO_APPROVAL` 保留无害。

## 十八、P2b 实施记录：审批基线表与展示对齐（2026-11-07）

本批未提交（待授权）；代码基线 `98752ef66`（P2a）。

### 18.1 「来源 → 审批基线」表（代码内单一真源 `ApprovalBaselines.kt`，exhaustive when 全覆盖）

| 来源 | 策略 | 依据 |
|---|---|---|
| memory | dynamic | 按 action 分档：create 独立开关；edit/delete/complete/drop 跟随 modify；read/search 恒不拦 |
| search | none | 搜索只读 |
| local | dynamic | calendar/ask_user 恒拦，其余（时间/剪贴板/TTS/截屏）不拦 |
| image | dynamic | 跟随 assistant.imageGenApproval |
| task_manage | dynamic | 按动作分档 taskManageNeedsApproval |
| conversation | none | 会话检索/读取只读 |
| workspace | dynamic | 按工具名分档；workspace_task_status 只读不拦，workspace_shell 恒拦 |
| skill-install | none | 现状不拦（SkillInstaller 自身校验）；改默认拦截属行为变更，走独立授权 |
| skills | none | 技能内容只读 |
| knowledge | none | 知识库检索只读 |
| subagent | dynamic | 按预设 requireApproval 分档 |
| mcp-dynamic | follows-target | mcp_tool_call 跟随被调工具（折叠不得成为审批绕行） |
| mcp:* | provider | 透传 MCP server 的 tool.needsApproval |
| plugin:* | plugin-defined | 受信任插件自定闭包；宿主执行边界/超时不放宽 |

**自管超时清单**（`to=` 维度机器可读，历史靠注释约定）：`workspace_shell` 615s（命令上限
600s+缓冲）、`dispatch_subagent` null（runner 看门狗自管，预设上限 7200s）；MCP 全部 180s
（非自管）；其余默认 60s（`DEFAULT_TOOL_EXECUTION_TIMEOUT_MS`）。

### 18.2 审批执行点与宿主边界审查

- 判定路径不变：`GenerationHandler.markPendingApprovals` 在每次工具调用前对 `needsApproval`
  逐次求值（恒开审批诊断行），插件闭包仍受信任；宿主外层超时（`withTimeout` + 错误输出）、
  工具结果配平、门控/预算/LoopGate 保持宿主强制，插件审批闭包**不放宽**任何宿主边界。
- 诊断新增 `approvalDiagnostic`：基线为 `none` 的段实测到动态闭包 → `none(Ndyn!)` 异常信号
  （抓「不该有审批的来源配了闭包」与「误用字面量 `{ false }` 而非规范实例」两种漂移）。
- 本批**零行为变更**：基线表只描述现状；如要把某来源改成默认拦截（如 skill-install），
  属拍板 2 明示的独立授权事项。

### 18.3 历史展示兜底对齐审查（结论：已同源，无需改动）

§16.2 ④ 曾记「内置工具渲染散在 ChatMessageTools 等处，与插件 UI 机制不同源」——本次复核
**推翻该判断**：内置渲染器（MemoryToolUI、SearchWebToolUI、workspace 全套等 20+ 个）与插件
动态注册的渲染器同走 `ToolUIRegistry.renderers`/`contributions`，`resolve(toolName)` 缺失时
统一落 `DefaultToolUIRenderer`（通用标题/图标 + JSON 详情，同一 `ToolUIRenderer` 接口）；
读取方按 `tool.toolName` + 注册表 version 重组（P1a 的实例代次绑定防跨代泄漏）。历史工具
结果按名寻址的契约由 P2a 的段名/工具名锚定测试间接钉住。**兜底路径与内置渲染已对齐，
本批不改 UI。**

### 18.4 验证

- 全量 JVM 套件（app + ai）：**3609 项 0 失败 0 错误**；本批新增测试：基线策略全覆盖 ×1、
  诊断语义（含 `!` 异常标记）×1。
- 回滚：删除 `ApprovalBaselines.kt` 并把诊断行退回纯实测计数即回滚，无数据/配置迁移。

### 18.5 真机走查（2026-11-07，PJX110）

- **装机**：master（`e19052bc6`）构建两包装机——`.debug` v320 覆盖安装（用户主包，数据不动，
  供人工日常验证）；`.perfp1` 隔离包（构建期临时改后缀、装后立即还原工作树）用于自动化走查。
- **注入**：`js_plugins` DataStore 注入两个 JS 插件——冲突探针（`sample.conflict_tool`，
  声明与内置同名的 `install_skill`）与对照样例（`sample.notebook_tool`，`note`）。
- **实测结果**：
  - 诊断行实机样貌：`tool schema total=5 tools ≈557 tokens (shape=OpenAI) → local 1/58t
    appr=dynamic(0/1dyn) to=60s | skill-install 2/324t appr=none to=60s |
    plugin:sample.conflict_tool 1/49t appr=plugin-defined(1/1dyn) to=9s |
    plugin:sample.notebook_tool 1/126t appr=plugin-defined(1/1dyn) to=9s`
    ——三问（来源/审批/超时）一行可答，基线表与实测一致（skill-install 基线 none 实测 none ✓）。
  - **闸门零误报**：探针插件未被拒绝——因 JS 路径工具名被 `pluginToolName` 命名空间化
    （见 §16.2 修正），结构上无重名可言；据此把 §16.2 初稿的「冲突风险真实」判断修正为
    「暴露面 = 受信任 Kotlin Provider」。闸门的整段拒绝+用户可见报错路径由 JVM 单测锚定
    （现有仓库无受信任 Provider 实现，真机触发无入口）。
  - 生成全链路正常（GLM-4.7 流式回复），无 FATAL、无假阳性错误卡片；走查后测试插件已清除。
