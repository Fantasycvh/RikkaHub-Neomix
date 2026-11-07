# 插件开发者修订通道（运行时切换 / 热生效）设计草案

> 状态：**draft rev1（2026-11-07）**。第二层设计批次首稿，立项依据见
> [design-plugin-composable-runtime.md](design-plugin-composable-runtime.md) §16.6（决策门放行：
> 「运行时切换 / 热修复——更灵活即时的看到效果和调试」）。四项拍板（2026-11-07）：
> ①修订对象=**仅插件资产**；②修订来源=**仅本地通道**；③生效时机=**代次切换**；④失败语义=**预检拦截**。
> 本文未实施、未提交。

## 一、目标与范围

给插件开发者（当前即设备主人本人）提供**不重装 APK 的迭代闭环**：

```text
编辑/导入修订 → 预检（不过即拦，last-good 继续服务）→ 激活（新代次）
   → 运行中回合保持旧实现 → 下一次装配用新修订 → 可随时切回 last-good
```

### 1.1 拍板边界（不得在后续批次悄悄放宽）

| 拍板 | 决议 | 越界即违宪 |
|---|---|---|
| 修订对象 | **仅插件资产**：JS 插件定义、JSON 规则插件及其 manifest/配置 | 内置转换器/工具/服务的实现替换不进本通道（「内置能力不迁移」红线不动） |
| 修订来源 | **仅本地**：设备内编辑、文件导入、ADB push；通道默认仅 debug 构建激活 | 无远端拉取、无市场分发（§11.5 既定决议）；release 构建不编译通道入口 |
| 生效时机 | **代次切换**：激活产生新代次，运行中回合/子代理持旧代次至自然结束，下一次装配用新修订 | 不偷换运行中实现（主设计稿验收场景 3 语义） |
| 失败语义 | **预检拦截**：激活前预检不过即拒绝并报错，last-good 不受影响；激活后的运行期失败不自动回退（手动切回） | 不做自动回滚（可能掩盖 bug 且失败判定难准） |

### 1.2 非目标

- 内置实现（转换器/工具/服务）的运行时替换、白名单窄口——如将来出现真实需求另立议题。
- 远端修订通道、签名/信任链、市场/审核（§11.5 决议：市场不并入运行时）。
- 修订的备份格式演进（修订历史是否入备份，见 §六.存储，初版从简：**不入备份**，重装即丢历史，
  当前定义本体仍走既有插件持久化）。
- 运行中实现偷换、跨代次的执行结果合并。

## 二、现状盘点（2026-11-07 基线 `66f761c5b`）

| 资产 | 位置 | 与本设计的关系 |
|---|---|---|
| JS 插件定义存储 | `JsPluginStore`：DataStore `js_plugins` 单 key JSON 列表（manifest+source） | 修订的对象；当前「重新导入=覆盖同 id 定义」，无版本、无预检闭环 |
| 插件状态 | `PluginManager` + `DataStorePluginStateStore`：启停/授权，`enabledByDefault` | 激活=复用其启停/重载语义；授权状态不随修订变化 |
| 实例代次 | P1a：注册句柄绑定 owner/generation，旧实例回调不能污染新注册 | 代次切换的地基，直接复用 |
| 重名闸门 | `acceptPluginToolSegments`（P2a） | 预检项之一：修订若引入工具，dry-run 报告重名结果 |
| 审批基线 | `ApprovalBaselines`（P2b） | 预检报告展示修订后工具的审批/超时画像 |
| JS 工具名 | `pluginToolName`：`rikka_<hash>_<name>`，声明名严格正则 | 结构性无跨源重名；预检仍校验段内唯一 |
| JS 运行时 | `JsPluginRuntime`（WebView 求值，状态不跨调用） | 预检的语法检查可复用（compile-no-execute），见 §四.3 |
| 插件 UI | 插件页 + `ToolUIRegistry`（代次绑定渲染） | 修订入口/版本切换 UI 挂在此 |

## 三、修订与版本模型

### 3.1 数据模型

```kotlin
/** 一个插件的一次修订；revisionNo 在插件 id 内单调递增。 */
data class PluginRevision(
    val pluginId: String,
    val revisionNo: Long,            // 从 1 起；激活成功才占用号，预检失败不占
    val payload: PluginPayload,      // JS: (manifest, source)；规则: 规则 JSON
    val origin: RevisionOrigin,      // EDITOR / IMPORT / ADB
    val createdAt: Long,
    val precheck: PrecheckReport,    // 激活时的预检结果快照（通过才有 revisionNo）
)

data class PluginRevisionState(
    val pluginId: String,
    val activeRevision: Long?,       // 当前生效（= PluginManager 中激活实例对应的修订）
    val lastGoodRevision: Long?,     // 上一个成功服务的修订；一键回退目标
    val revisions: List<PluginRevision>,  // 上限 N=10，滚动丢弃最旧
)
```

- **active / last-good 双指针**：激活成功即 `lastGood = 旧 active; active = 新`；预检失败不改任何指针。
- 修订历史存 `files/plugin-revisions/<pluginId>.json`（上限 10 条滚动），**登记 BackupFolderRegistry**
  （§2.3 表既有约束），但**不加入备份导出范围**（初版从简，见 §1.2）。
- 当前生效的插件定义**仍写回既有存储**（`js_plugins` / 规则 DataStore）——PluginManager 与
  既有加载路径零改动，修订通道只是「写入前多一道预检 + 多留一份历史」。

### 3.2 状态机

```text
draft(编辑中/已导入) --预检通过--> active(占用 revisionNo, 旧 active 降为 last-good)
    --激活失败(预检不过)--> draft(报错, 指针不动)
active --手动回退--> active(lastGood 复活为新 active, 旧 active 降入历史)
```

运行期失败（钩子抛错、工具报错）**不**触发状态迁移——记录到修订的失败计数供人工判断，
回退永远手动（拍板 ④）。

## 四、预检（激活闸门）

预检全绿才允许激活；任一失败 → 修订留在 draft 并给出**逐项报告**：

| 项 | 内容 | 实现依托 |
|---|---|---|
| 1. manifest 校验 | id/名称/tools/schema/网络域/超时范围 | `JsPluginManifest.validate()`（既有） |
| 2. JS 语法检查 | 源码可编译（不执行） | WebView `new Function(source)`：编译失败=语法错误；不执行任何插件代码 |
| 3. 工具闸门 dry-run | 修订后的插件段过 `acceptPluginToolSegments` | P2a 纯函数；JS 路径结构性无跨源重名，此处防段内重复与未来形态 |
| 4. 钩子 smoke | `target:none+tools` 插件跳过；有钩子的插件在预检 WebView 里执行一次纯样例输入 | 现有 JS 运行时隔离与超时机制；网络/存储能力在预检中**不授权**（无 networkHosts 白名单时宿主代理直接拒） |
| 5. 画像报告 | 修订后各段的审批/超时画像与重名结果 | `approvalDiagnostic` / `timeoutSummary`（P2b），写进 PrecheckReport |

规则插件的预检 = manifest 校验 + 正则可编译性检查，无 2/4。

## 五、本地通道与门控

### 5.1 三个入口

| 入口 | 路径 | 说明 |
|---|---|---|
| 应用内编辑 | 插件详情页「编辑修订」→ **整页编辑器** | 大文本编辑遵守 TextField 宿主规范（AGENTS.md）：整页 + 纵向滚动，禁止内联在 Lazy item；保存即提交预检 |
| 文件导入 | 现有插件导入流程 | 导入同 id 定义 = 提交一次修订（origin=IMPORT），过预检才替换 |
| ADB push | debug 构建监听 `files/plugin-revisions/inbox/`，前台轮询/下次启动扫描 | 开发者快速通道：PC 上改完 `adb push`，下次装配生效；inbox 文件处理后即删 |

### 5.2 激活门控

- 通道入口（编辑按钮、inbox 扫描）**仅在 debug 构建编译进包**（`BuildConfig.DEBUG` 编译期排除，
  release 连代码都不进）——零运行时权限面，无需设置开关。
- JSON 导出/分享修订历史：不需要，砍掉。

### 5.3 生效语义（代次切换）

1. 激活 = `PluginManager.setEnabled(id, false) → 换定义 → setEnabled(id, true)`（或等价的
   重载入口），产生**新 Activation**；P1a 代次保证旧实例句柄/回调全部失效于其自身作用域。
2. 运行中回合：装配发生在回合开始，途中工具/转换器闭包引用旧实例——按主设计稿场景 3，
   **旧运行不偷换实现**；新回合装配即见新修订。
3. 工具调用进行到一半时的激活：执行中的调用仍跑旧闭包至返回/超时，结果照常回填（不做取消级联，
   与停用插件的行为一致——停用也不掐正在执行的调用）。

## 六、存储与备份

- 修订历史：`files/plugin-revisions/<pluginId>.json`（§三.1），目录登记 `BackupFolderRegistry`；
  **导出范围初版排除**，避免备份格式联动（§1.2）。inbox 目录不登记、不入任何导出。
- 生效定义本体：沿用既有 `js_plugins` / 规则 DataStore——备份/迁移/降级行为与现状完全一致。

## 七、分期

| 期 | 交付 | 退出条件 |
|---|---|---|
| R1 | 预检器（§四 1/2/3/5）+ 代次切换 + 文件导入接修订（origin=IMPORT）+ 历史存储 | 同 id 导入新修订：预检报告可见；预检失败旧定义继续服务；激活后新回合用新修订；运行中回合不偷换 |
| R2 | 应用内编辑器入口（整页）+ 版本历史/一键回退 UI | 编辑→保存→预检→激活全链路真机走查；回退后行为恢复 last-good |
| R3 | ADB inbox 通道 + 规则插件接入 | push→扫描→激活闭环；规则插件预检/热生效同口径 |

## 八、验收场景（承主设计稿关键验收场景 2/3）

1. 编辑引入语法错误 → 预检失败报告指明行/原因，旧修订继续服务，插件列表状态无抖动。
2. 激活新修订瞬间有一个运行中回合 → 该回合全程旧实现且结果正常；其后新回合用新修订。
3. 新修订声明工具 → 预检报告含重名闸门结果与审批/超时画像；模型侧可见名与上一版同规则
   （`pluginToolName` 不变则名不变）。
4. 一键回退 → last-good 复活为新 active，历史保留，可再切回。
5. release 构建：通道入口无编译产物、inbox 扫描代码不存在，只有既有导入流程（仍过预检——
   预检器本身在 release 保留，因为它保护的是既有导入路径）。
6. 卸载插件 → 修订历史文件保留（配置缺失插件兜底按主设计稿 §5.2）；重装同 id → 历史重新可用。

## 九、开放问题（实施前需拍板）

1. **应用内编辑器的形态**：纯文本编辑 vs 带高亮的代码编辑器组件——现有栈（BetterMarkdown/无
   highlight 模块）下建议先纯文本 + 等宽字体，R2 真机手感再定。
2. **修订历史容量**：10 条够否；JS 源码可达数十 KB，历史文件体积要不要夹在 DataStore 外
   （已决定放 files/，无 DataStore 体积问题）。
3. **预检 smoke 的输入样例**：`target` 有钩子的插件用什么输入做 smoke（建议：空输入 + 各钩子
   的最简 payload；有副作用的钩子如 OUTPUT 改写只验证不抛错不外呼）。
4. **ADB inbox 的轮询时机**：前台 `onResume` 扫一次（简单、足够开发者用）vs 常驻监听——建议前者。
