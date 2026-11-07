# 超长文件拆分重构台账（分析稿）

> 状态：drafts（仅分析，未动代码）
> 日期：2026-11-07
> 基线：e9041d57d（1.0.28-beta.18 回填后）
> 数据来源：全仓 800+ 行 Kotlin 文件盘点（54 个，排除 vendored material-color-utilities 与测试后约 40 个）+ 四路子代理对头部文件的内部分析 + 943 个历史 fix 提交的模式提炼（§6）

---

## 0. 总原则

- **门面保留 + 控制器下沉**：大文件（ChatService/SettingsStore）被几十至上百处引用，public API 一律不变，内部委托新类。Koin 注册表最小改动（优先在构造器内组合，不动 Koin）。
- **每文件独立提交，拆完即跑单测 + `python scripts/audit_ime_trap.py`**。
- **真机崩溃修复注释必须随代码走，不可当噪音删除**（见 §6-E）。
- 涉及 UI 的拆分批次，合入后需真机过一遍被拆页面（历史上一半 fix 是真机回归抓出来的，见 §6-A）。

---

## 1. 全景清单（≥800 行，排除 vendored/测试）

| 行数 | 文件 | 类型 | 梯队 |
|---|---|---|---|
| 3518 | `service/ChatService.kt` | 服务层编排 | 一（类外声明）→ 二（子域）→ 三（编排保留） |
| 2159 | `ui/pages/extensions/PromptPage.kt` | Compose 页面 | 一 |
| 2084 | `data/ai/GenerationHandler.kt` | 生成编排 | 一（纯函数）→ 三（runLoop） |
| 2026 | `ui/pages/setting/SettingProviderDetailPage.kt` | Compose 页面 | 一 |
| 1903 | `data/datastore/PreferencesStore.kt` | 偏好存储 | 一（非 store 部分）→ 三（store 本体） |
| 1713 | `ui/pages/chat/Export.kt` | 导出混杂 | 一（耦合最小，首选） |
| 1646 | `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | Compose 页面 | 一（tab 卡片）→ 二（导出管线） |
| 1370 | `ui/pages/chat/ChatPage.kt` | Compose 页面 | 一（子组件） |
| 1351 | `RouteActivity.kt` | 导航+闸门+深链 | 一（三刀切开） |
| 1331 | `ui/pages/setting/SettingMcpPage.kt` | 页面 | 未深入分析，按同模式套用 |
| 1304 | `ui/pages/chat/ChatDrawer.kt` | Compose 页面 | 一（三簇） |
| 1241 | `ui/pages/mediacreation/MediaCreationComposer.kt` | 页面 | 未深入分析 |
| 1204 | `ui/pages/setting/components/TTSProviderConfigure.kt` | 组件 | 未深入分析 |
| 1135 | `ui/components/ai/ChatInput.kt` | 输入栏 | 一（外围）→ **TextInputRow 硬约束不拆** |
| 1126 | `service/SubagentRunner.kt` | 子代理运行器 | 一（纯函数/通知/进度） |

其余 900~1100 行段中 28 个已于第二轮补齐分析（见 §7）；仍待评估的仅剩：ConversationRepository（§3.2 已有方案）、SettingSearchDetailPage.kt（926）、ImgGenPage.kt（888）、WorkspaceRepository 之外的少量零散文件——按本台账模板逐个套用即可。

已排除：`material3/material-color-utilities`（vendored 上游库，不拆）；`src/test` 下大文件（测试内聚是优点，不拆）。

值得肯定的现状：护栏家族（ToolCallLoopGuard / RuleEngine / ThinkingDegenerationGuard / WorkspaceTurnBudget / DenialGuard / ContextBudgetGuard / UsageRecorder）与 ChatService 的 ConversationSession / MessageQueue / AuxJobRegistry / ChatNotificationManager / InspectionSingleFlight 均已独立成文件——头部文件是「拆了一半剩下编排堆积」，不是完全未拆。

---

## 2. 第一梯队：纯机械搬运（几乎零风险）

### 2.1 Export.kt（1713 行）→ 六个文件 【首选，全仓耦合最小】

天然五层互不依赖：

| 新文件 | 内容 | 约行数 |
|---|---|---|
| `Export.kt`（保留） | ChatExportSheet UI + deliver/deliverAll + ImageExportOptions | ~560 |
| `ExportArchive.kt` | exportToConversationArchive + attachmentPlan + sanitize/writeExportBytes/ExportedFile 设施 | ~200 |
| `ExportMarkdown.kt` | exportToMarkdown | ~180 |
| `ExportHtml.kt` | exportToHtml + renderMarkdownWithTikz | ~220 |
| `ExportImage.kt` | exportToImage + 分页常量 + ExportedChatImage/Message/Step 渲染树 | ~560 |
| `ExportTikz.kt`（可选） | TikZ 正则与编译（HTML/PNG 共用） | ~90 |

- 三种导出器签名统一 `(context, conversation, messages, settings) -> ExportedFile`，互不调用。
- 注意：声明全是 private，需批量改 internal；调用点 3 处（ChatPage.kt:875、ChatDrawer.kt:781、ChatList.kt:703）import 同步更新。
- **契约必须写进文档注释**：`ChatExportSheet.onExported 必须先于 onDismissRequest`（Export.kt L246-250）。
- ExportedChatImage 是离屏 Compose 渲染（独立 theme + navigator），无 TextField，无 IME 风险。

### 2.2 RouteActivity.kt（1351 行）→ 三刀

| 新文件 | 内容 | 约行数 |
|---|---|---|
| `Screen.kt` | sealed interface Screen（~70 个 @Serializable 路由，L1077-1351） | ~280 |
| `AppNavHost.kt` | AppRoutes 的 NavDisplay + entryProvider（~80 个 entry，L648-982）+ 迁移遮罩/PerfOverlay | ~430 |
| `StartupGate.kt` | 启动闸门四件套 + GateTheme + ImageLoader 工厂（L290-437） | ~260 |
| `LauncherActivities.kt` | 4 个 Launcher 子类 + ShortcutEntryActivity | ~60 |
| `RouteActivity.kt`（保留） | Activity 本体：onCreate/onResume/dispatchKeyEvent/handleIntent/AppRoutes 环境装配 | ~350 |

- **Screen 放同包 `app.androidrik.rikkahub.Screen.kt`**：全仓 ~80 处 import 该类型，移包代价大；saved-state 反序列化兼容 → 路由类字段一个都不能动。
- entryProvider 与 NavDisplay 的 transitionSpec、NavKey 泛型推断耦合，整块搬不可截半。
- **留在 Activity 不动**：onCreate 启动 gate 分流、空栈自愈 repairIfEmpty、pendingIntents SideEffect flush、alias ON_STOP 切换——均为真机崩溃修复产物（含 9cb72482f 空 backstack 崩溃循环）。

### 2.3 ChatService.kt 类外顶层声明（L156-430，~280 行）→ `ChatServiceModels.kt`

枚举/异常/纯函数/错误模型整体搬走：InterruptReason（public，L168-170 注释说明签名暴露约束）、mergeCompressedConversation、forkConversationTitle/createForkConversation（有 JVM 单测）、ChatError/ChatErrorSolution、updateMessageReasoningInNodes、护栏异常、withPromptInspectionDispatcher。单测 import 同步改，零行为风险。

### 2.4 PreferencesStore.kt（1903 行）→ 拆非 store 部分

| 新文件 | 内容 | 约行数 |
|---|---|---|
| `Settings.kt` | data class Settings（200+ 字段，L1169-1440） | ~280 |
| `DisplaySettings.kt` | ChatFontFamily/BackgroundEffectType/LiquidGlassReadabilityMode/AvatarFrame*/ChainOfThought*/DisplaySetting（90 字段）/WebDavConfig/BackupReminderConfig（L1442-1665） | ~230 |
| `SettingsExtensions.kt` | findModelById/getCurrentChatModel/resolveCompressionModel/getCurrentAssistant/awaitLoaded 等（L1691-1787） | ~100 |
| `SettingsDefaults.kt` | DEFAULT_ASSISTANT(S)/DEFAULT_TTS_PROVIDERS/refill*/DEFAULT_MODE_INJECTIONS/DEFAULT_BACKUP_ITEMS（L1790-1903） | ~115 |

- 类型搬家编译器全程护航，~800 行移出。
- **硬约束**：`Settings` 是 @Serializable 单体（WebDAV/Upstream 备份按整对象序列化），字段与形状不能动；`SettingsStore` 类本体第三梯队再说。

### 2.5 GenerationHandler.kt（2084 行）→ 抽纯函数三块

| 新文件 | 内容 | 约行数 |
|---|---|---|
| `GenerationErrorPolicy.kt` | 错误分类 + Key 轮换纯函数群（L1926-2067） | ~150 |
| `GenerationEvents.kt` | GenerationChunk/GenerationNotice sealed 族（L270-336） | ~70 |
| `ToolOutputContextGate.kt` | 上下文闸常量+纯函数核（L83-163，**ToolOutputContextGateTest 已直接测此核**） | ~90 |

减约 400 行，无行为风险。护栏实例「由调用方传入、跨审批续跑累积」契约不动（L396-414 注释）。

### 2.6 SubagentRunner.kt（1126 行）→ 三块

| 新文件 | 内容 | 约行数 |
|---|---|---|
| `SubagentRunOutcome.kt` | RunProgress/RunOutcome/evaluateCompletion/snapshotProgress（L1040-1126，纯） | ~90 |
| `SubagentApprovalNotifications.kt` | 审批通知 helper（L858-951，仅依赖 Context+NotificationIds） | ~120 |
| `RunProgressTracker.kt` | activeJobs + _progress 进度注册表（L155-191） | ~70 |

### 2.7 页面级组件搬出

| 来源 | 搬出内容 | 新文件 | 约行数 |
|---|---|---|---|
| SettingProviderDetailPage（2026） | Model tab 整条链：ModelList/ModelCard/AddModelButton/ModelPicker（对外仅 providerSetting + onUpdateProvider 两参数） | `ModelListPage.kt` | ~700 |
| 同上 | ModelSettingsForm + 3 选择器 + BuiltInToolsSettings + ProviderOverrideSettings | `ModelSettingsForm.kt` | ~500 |
| PromptPage（2159） | 骨架+PresetList/PresetCard/PresetEditDialog | `PresetPage.kt` | ~620 |
| 同上 | PresetDetailScreen + ModeInjectionCard/EditSheet + 选择器 | `PresetDetailPage.kt` | ~800 |
| 同上 | LorebookTab + Detail + RegexInjection 系列 | `LorebookPage.kt` | ~900 |
| 同上 | rememberHighlightedEntry/KeywordEditor/label 辅助 | `PromptShared.kt` | ~180 |
| WorkspaceDetailPage（1646） | WorkspaceBasicPage + 5 张卡 + InfoRow/RootfsProgress（纯参数组件） | `WorkspaceBasicTab.kt` | ~700 |
| ChatPage（1370） | TopBar + ContextUsageReadout | `ChatTopBar.kt` | ~200 |
| 同上 | ChatFilesPickerSheet（5 个 launcher 全套） | `ChatFilesPickerSheet.kt` | ~220 |
| 同上 | 容量 sheet 外壳 + HiddenSessionBanner | `ChatContextSheetHost.kt` | ~90 |
| ChatDrawer（1304） | DrawerActions/BottomActions/Entry/Action/PanelSwitcher | `DrawerChrome.kt` | ~230 |
| 同上 | FolderBar/FolderChip/AssistantItem | `DrawerFolderUi.kt` | ~170 |
| 同上 | WorkspaceFilesPanel（顺手清理全限定名 + 死代码 formatDrawerFileSize） | `DrawerFilesPanel.kt` | ~160 |
| ChatInput（1135） | CompletionPopup + applyCompletion + QuickMessageButton | `ChatInputCompletions.kt` | ~180 |
| 同上 | FullScreenEditor | `ChatInputFullScreenEditor.kt` | ~80 |
| 同上 | ContextUsage + ContextUsageRingButton | `ContextUsageRingButton.kt` | ~110 |

搬移红线（每条都有历史事故背书，详见 §6）：
- `ChatInput.TextInputRow`（L665-844）**原地保留、宿主层级不变**——TextField 宿主四连坑（§6-B）。
- CompletionPopup 的 item key（`label:insertText`）不得改成内容哈希（4d2938da6）。
- PromptPage 三处 `rememberUpdatedState` 防 importer 捕获旧列表的血泪注释（L323-329、L632-634）必须跟组件走。
- `RegexInjectionEditDialog` 已带 verticalScroll+imePadding（合规但接近该升级为 sheet 的边缘）——升级属行为变更，单独批次 + IME 真机验证。
- `InstallRootfsDialog` 有 verticalScroll 无 imePadding，拆分时顺手补上（低风险改进）。
- `ModalAbilitySelector` 是唯一非 private 顶层函数，搬移前先 grep 引用方；`statusLabel`/`toShellStatusLabel` 是 internal 且被 WorkspaceListPage 引用。
- ChatPage 的 contextUsage `produceState` 节流管线（L198-240）是 ANR 修复产物，线程/节流语义原样保留。
- 拖拽排序 + editState + importer 三件套必须整屏整体搬，不能拆半。

---

## 3. 第二梯队：先抽中间层

### 3.1 ChatService 子域下沉（门面保留，构造器内组合，不动 Koin）

按依赖顺序：

| 新类 | 来源 | 约行数 | 风险 |
|---|---|---|---|
| `ChatErrorReporter.kt` | L468-513 + ChatError 模型 | ~130 | 低 |
| `SubagentNoticeDeliveryController.kt` | L524-582 | ~80 | 低 |
| `PromptInspectionController.kt` | L1429-1742（自包含：单飞+预热缓存+AssemblyState） | ~330 | 低 |
| `ConversationMutationService.kt` | L2629-2980（updateConversation* 系列；**updateConversation/updateConversationState 是 session.state 唯一写路径**） | ~360 | 中 |
| `MessageOpsService.kt` | L2999-3338（翻译/编辑/分叉/选分支/删除） | ~350 | 中 |
| `AuxGenerationService.kt` | L2263-2628（标题/建议/压缩，全走 usageRecorder+auxJobs） | ~370 | 中 |
| `SessionRegistry.kt` | L583-737 + sessions/_sessionsVersion/引用计数 | ~220 | **中高** |
| `GenerationFlowController.kt` | 发送/队列/重生成/审批 + handleMessageComplete + stopGeneration | ~1300 | **高：建议最后或不拆** |

关键结构阻力：
- `sessions: ConcurrentHashMap` + `_sessionsVersion.value++` 非原子 RMW **必须在 Main 线程**（L419-421 注释）——抽 SessionRegistry 时写进契约。
- **三方回调环**：session 回调 → dispatchNextQueuedMessage → sendQueuedMessage → handleMessageComplete → 会话状态 + noticeRequests。用事件流/接口打破后再拆。
- 取消安全语义：stopGeneration / persistStoppedGeneration（NonCancellable 落库）与 lastCommittedMessages 回填顺序有真机事故背景（L3367-3405），**移动即最高行为回归风险**。

### 3.2 ConversationRepository.kt（1090 行）

1. 先抽 `ConversationEntityMapper`（映射 + common-prefix diff 写回，纯转换，L620-698/923-1067，~350 行）和 `CompressionCheckpointStore`（L699-738）。
2. tier 排序域（isTierMaterialized/applyManualOrder/decideTierMove/materializeTier/resetTierOrder，与 DAO sort-order 物化强耦合）自成 `ConversationOrderingManager`。
3. **两套分页并存**（Pager Flow + 手动 offset page）是历史包袱，重构时是统一时机，但 AgentRunsVM/ChatDrawerVM 等调用方都要过一遍。

### 3.3 GenerationHandler 内部

工具执行分支抽 `ToolExecutionEngine`（`commitToolResult` 的 NonCancellable 同步回调时序不得变）；`translateText` 抽 `TranslationGenerator`；`withGenerationRetry` 抽 `GenerationRetryPolicy`。**runLoop（~670 行）拆分前置条件**：先把 20+ 个共享 `var` 局部状态收敛成 `LoopState` 值对象，否则半拆留下悬空状态。

### 3.4 WorkspaceDetailPage 导出管线

3 个 SAF launcher + pendingExport/pendingLaunch/largeExportConfirm/exportProgress 等 8 个互相咬合的状态 → `rememberWorkspaceExportState(...)` 状态持有类（launcher 必须留在组合层，rememberLauncherForActivityResult 不能进普通类）。SAF 时序坑注释（L172、216-219、281-285）原样保留。建议分两步提交。

---

## 4. 第三梯队：最后做或不做

1. **handleMessageComplete（~420 行）+ stopGeneration**：触碰一切依赖 + 取消安全真机事故背景。保留原地。
2. **SettingsStore 域拆分**：290 处引用/100 个文件/`.settingsFlow` 153 处。只能门面不变、内部委托 section（仿 companion L280-466 的 key 分域注释）。**硬约束**：`updateMutex` 整体保留（L478 注释：勿把 Mutex 插进 flow 链——曾导致 205 个编译错误）；Settings 聚合根 @Serializable 形状不动。
3. **ChatInput 主 `ChatInput()` 40+ 参数契约面**：不拆参数本身。
4. **AppRoutes 的 CompositionLocalProvider 装配 / Activity 字段耦合段**：不搬。

---

## 5. 建议执行顺序

```
批次 1  Export.kt 六分                （✅ 2026-11-07 完成：393e3a791 拆分 + 2e4012bd2 死代码清理；单测绿、IME 审计 A=0；真机待过导出四格式）
批次 2  ChatServiceModels + SubagentRunner 三块 + GenerationHandler 纯函数三块
        （✅ 2026-11-07 完成：1bf982141 ChatServiceModels（源码守护测试改读新文件）、
        5c67a5192 SubagentRunOutcome、a5846b877 SubagentApprovalNotifications、
        c1527cb9e RunProgressTracker、75d63304e GenerationHandler 三块；
        全量 3117 单测绿。发现的守护测试族：PromptInspectionWiringSourceGuard/
        ThinkingDegenerationWiring/P0RollbackSwitchGuard 均按源文件 grep 断言，
        拆分后须同步改读迁移文件——守护意图不变）
批次 3  RouteActivity 三刀（Screen 同包）
批次 3  RouteActivity 三刀（Screen 同包）
批次 4  PreferencesStore 非 store 部分
批次 5  SettingProviderDetailPage Model tab
批次 6  PromptPage 四分 + WorkspaceDetailPage Basic tab
批次 7  ChatPage/ChatDrawer/ChatInput 子组件
批次 8  ChatService 控制器下沉（ErrorReporter → PromptInspection → Mutation/MessageOps/Aux → SessionRegistry）
批次 9  ConversationRepository Mapper/Checkpoint/Ordering
批次 10 GenerationHandler 内部（LoopState 先行）→ 视收益决定是否继续
```

每批次：单测 → audit_ime_trap.py → 编译 → 真机过被拆页面 → 独立提交（先 build 再 commit，防 6421e9b81/5fb0b6031 式漏提交编译不过）。

---

## 6. 历史提交常犯错误清单（重构特别版）

以下从 943 个 fix 提交 + 项目记忆提炼，按「在拆分/重构场景下会不会再犯」组织。

### A. 机制级：拆分本身最容易触发的

1. **TextField 宿主四连坑**（AGENTS.md 已规范；4d2938da6 / d50d7aeaf / 9bc2d58f4 / compress 反复返工）。拆分时的具体变体：把原本在 Column/AlertDialog/sheet 里的 TextField 搬进 Lazy item；把 sheet 内容截半搬到 item。回归审计：`python scripts/audit_ime_trap.py`。
2. **ListItem supportingContent 槽位只渲染第一个直接子节点**（d3212c913，头像框/思维链编辑入口静默消失）。搬 PromptPage/ChatDrawer 的条件行时用 `Column { }` 包一层，条件行不得与主控件平级挂槽位。
3. **item key 用内容哈希**（4d2938da6）：搬家时禁止「顺手优化」key；CompletionPopup 的 `label:insertText` 保持原样。
4. **rememberUpdatedState 时序注释丢失**：importer/回调捕获旧列表的修复散布在 PromptPage/WorkspaceDetailPage，搬走组件时注释必须跟随。
5. **回调链漏传**（83c32627f onToolSkip 两处漏传；83c32627f 同源模式反复出现）：搬 40+ 参数的宽接口组件时，逐参数核对调用点；拆 ChatPage bottomBar 段（40+ 回调）时尤其如此。**对策：搬完立即编译 + grep 被搬函数名的全部调用点**。
6. **「我上一提交引入，真机抓到」**（de4680309）：机械搬移 ≠ 零风险，每个 UI 批次都要真机过一遍被拆页面。
7. **漏提交导致 HEAD 编译不过**（6421e9b81 / 5fb0b6031 / cacd37d5e）：先 build 再 commit；一次拆分批次一个提交，不跨提交留半截状态。
8. **类名被 R8 混淆**（299ac7824：API 格式名取自类 simpleName，release 显示成 it9/ft9/bt9）：拆分时**不得引入任何依赖类 simpleName 的逻辑**；现混淆规则无按类名的 keep，但改名/移包后若未来加 keep 规则要对齐新包名。

### B. 语义级：搬代码时不能动的隐性契约

9. **取消安全落点**：`commitToolResult`（NonCancellable 同步回调）、`persistStoppedGeneration`（NonCancellable 落库）、lastCommittedMessages 回填顺序——移动即回归（c3932f7e5、b1273a72c 停止保半截内容、0bd36c6c8 停止丢结果同源三 bug）。
10. **线程约束**：`_sessionsVersion.value++` 必须 Main；contextUsage 节流管线（ANR 修复）；主线程 token 计数 ANR（8ed3b5808）。抽控制器时把线程契约写进 KDoc。
11. **AppScope 吞协程异常**：哨兵类逻辑必须裸协程走 CrashHandler（80759b447）；拆 ChatService 时 auxJobs 内的异常不得被静默吞掉。
12. **竞态家族**：boot 异步竞态（c50154453）、FGS stopSelf(startId)（fcbb05ebd）、onResume 早期解析、Koin gate 阻塞屏提前解析（ecbd77c7f）、恢复流双关闭（1a026886f）、Stream Closed。搬初始化/生命周期相关代码时先问「这段在什么时机被谁调用」。
13. **顺序/合并契约**：onExported 先于 onDismissRequest；请求头「提供商级最低、请求级覆盖」（92074f156）；导出成功才续作 regenerate（14524fab5）；启动闸门 SafeMode 分流顺序。
14. **静默失效模式**（本项目最高频 bug 族）：过滤条件让降级分支成死代码（b5b57c41e 三 provider 丢后续 system 消息）、输入处理链产物被丢弃（c57b 系列）、visualOnly 旧推定被证伪（6aa412e4b）、工作区 budget 静默清零。**搬 transformer/管线代码时，先跑 PromptAssemblyTest / PromptInjectionTransformerTest 锁行为再动**。
15. **快照 vs live 引用**（0dbad6908 发送窗口每步对 live messages 重算）：把「传列表快照」改成「传 live flow」或反之都是行为变更，拆分时保持原语义。

### C. 数据级：碰模型/存储时的红线

16. **Conversation 加字段必须六处同步**（含 DAO.updateMetadata SQL）——见项目记忆 conversation-column-storage-pitfall；重构 ConversationRepository 时尤其注意。
17. **整对象写回 vs 单列写**（61a07847c pin/title/injections 改单列元数据写，修「生成中切助手覆盖在途内容」）：搬写路径时不得把单列写改回整对象写。
18. **@Serializable 形状稳定**：Settings 聚合根、Screen 路由字段、备份 schema——一个字段都不能动（saved-state/备份反序列化兼容）。
19. **JsonInstant 已开 coerceInputValues**（4e06945ee 旧 blob stepCount:null 不再炸会话）：新拆文件里建 Json 实例必须复用既有 JsonInstant，不得 new 严格实例解析旧 blob。
20. **枚举导入大小写**（withCaseInsensitiveEnums 已根治，e3deedf29）：拆导入/迁移代码时保留该配置。
21. **UUID 类型混用**（8fef8d60c）：跨层传递 id 时保持类型一致。
22. **Locale 硬编码**（9f9e0ac7a 余额求值固定 Locale.ROOT）：格式化代码搬家时保留 Locale 参数。

### D. DI / 构建 / 工具链

23. **Koin 显式类→接口绑定**（dd1b8a37b 装机首启崩溃）：新控制器若进 Koin，必须显式声明绑定；组合根方式（构造器内 new）可绕开，第一梯队/二梯队优先用后者。
24. **Room 2.8.4 无运行时版本反射来源；SCHEMA_VERSION 编译期对账**（07c012ec4/80759b447）：不碰 DB 版本。
25. **google-services.json 跟随分支包名**：跨分支动构建文件时注意。
26. **debug 包 applicationId 后缀**：涉及 ComponentName/通知 PendingIntent 的代码搬家时用真实全类名（见项目记忆）。

### E. 流程级

27. **真机验证工作流**（PJX110，见项目记忆）：拉库用 exec-out、uiautomator 驱动、input text 丢字符不可信、整对象写丢更新须 transform。
28. **上游选择性吸收**：本台账为自主重构，与 docs/upstream-analysis.md 台账并行，吸收上游同类改动前先查是否冲突。
29. **推送/发版必须用户单独授权**（AGENTS.md 强制约束）：本台账各批次只做本地提交。
30. **文案/lint**：`%1$d` 缺实参崩溃已有守卫测试（83c955b13）；`stringResource` 混用处是将来 i18n 漏网点（搬代码时不要顺手把硬编码中文改成 stringResource 或反之——保持原样，避免批次 diff 失焦）。
31. **日志脱敏**：0706147f2/e3c33743c/c1c40a54c5——搬错误上报/日志路径时脱敏逻辑跟代码走。

### F. 第二轮盘点（§7）新增发现

32. **死代码先清点再动手**：GoogleSans.kt（883 行）是**运行时死代码**（唯一引用在 Type.kt:17-78 的注释块里）；ResponseAPI.kt:91/94 两个 private 常量无引用；ChatDrawer `formatDrawerFileSize` 无调用者。清理与搬移**分开提交**，勿混入拆分 PR。
33. **注释串位/Kdoc 错挂**（BuiltinToolUIs 321-328 两段注释归属错位）：搬移时「顺手修正」要谨慎，先确认归属再动。
34. **LazyColumn item 顺序即契约**：PromptInspectPage `segmentOffset = 4` 依赖前 4 个 `item{}` 的固定顺序（L111-113 注释），重排 item 会**静默破坏目录跳转**（同源历史：361b9d7e4 拖拽下标错位）。
35. **R8 keep 规则与拆分同步**：ModelRegistry 若拆，proguard 规则须同一 PR（299ac7824 类名混淆前科）。
36. **AppScope 吞异常有时是特性**：FilesManager `trackManagedFile`/`deleteChatFiles` 的 best-effort 登记（L808 注释）——「统一 scope」式抽取会把特性变成缺陷。
37. **settingsFlowRaw.first() vs settingsFlow.value**：DataStore 未加载时后者是 dummy 设置（RikkaHubApp L637-641、L795-797 两处注释）——搬初始化代码时不得换用 `.value`。
38. **源码守护测试族（批次 1-2 实证）**：`PromptInspectionWiringSourceGuardTest`/`ThinkingDegenerationWiringTest`/`P0RollbackSwitchGuardTest` 等直接读源文件文本 grep 断言——被拆符号迁移后**测试必须同步改读新文件**（守护意图不变），否则单测红；且这类失败只在 testDebugUnitTest 出现、编译不报。**每个子块提交前必须跑全量单测，不能只看编译绿**。

---

## 7. 补充分析（28 个文件，第二轮）

> 评估模板与第一轮相同。结论速览：**值得拆 15 个、轻拆/只拆外围 6 个、明确不拆 7 个**。所有拆分均遵守 §0/§2/§6 红线（private→internal、真机注释随行、TextField 宿主不变）。

### 7.1 设置页面群

| 文件 | 现行数 | 拆后主文件 | 拆分表 | 风险 |
|---|---|---|---|---|
| TTSProviderConfigure.kt | 1204 | ~250 | →`TTSProviderConfigureOpenAICompatible.kt`（OpenAI/Gemini/Groq/xAI/MiMo/MiniMax ~450）+ `TTSProviderConfigureOther.kt`（Qwen/ElevenLabs/FishAudio/Step ~560） | 极低：11 个 provider 块零耦合，纯机械；`onValueChange` 回传完整 copy 的约定各块一致；R8 类名兜底注释（L69）留入口 |
| SettingSpeechPage.kt | 1096 | ~250 | →`TTSProviderListPanel.kt`（List+Speed+Add+Item ~450）+ `ASRProviderListPanel.kt`（~390） | 低：兜底不变式（ensureFallbackTtsEnabled L408-416）、删除墓碑（L448-453）、拖拽三件套（L356）整块随迁；TTS/ASR sheet 同构可后续抽公共 ProviderEditSheet（第二步，先纯搬运） |
| SettingMcpPage.kt | 1331 | ~380 | →`components/McpServerConfigSheet.kt`（Modal+表单+isValidMcpName ~590）+ `McpToolsConfigure.kt`（~165）+ `McpImportSheet.kt`（纯函数+Modal ~100） | 低：全显式参数接缝最干净；headers 表单是 ModalBottomSheet 宿主整块走，不得拆成单字段塞回 Lazy item |
| SettingThemePage.kt | 1140 | ~330 | →`ColorPickerRow.kt` 公共组件（**internal 且被 SettingPreferencesThemePage.kt:159 消费**，+3 个 HSL 纯函数 ~165）+ `CustomThemeEditSheet.kt`（~340）+ `ThemeRoleOverridesEditor.kt`（角色覆写整域 ~255） | 中低：**transform 语义 onChange 契约**（L681-683 注释，整对象写丢更新的真机教训 L277-280）必须原样保留签名与注释；RoleSlotRow 的 commit-only-valid 模式（L882-885）整块走 |
| SettingPreferencesUIPage.kt | 1097 | ~750 | →`utils/ChatFontManager.kt`（3 个字体 IO 函数+常量 ~110）+ `components/DisplaySchemePreviews.kt`（Preview+DeleteDialog+label ~130） | 低（削边角）：主痛点是 835 行单函数；若再拆 A/B/C 三组须先把 `displaySetting`/`updateDisplaySetting` 闭包参数化（~60 处引用），单独立项 |
| SettingPreferencesGeneralPage.kt | 890 | ~520 | →`components/GuardrailsSettingGroup.kt`（~240）+ `components/KeepAliveSettingGroup.kt`（~75） | 极低：两组已自包含（自带 VM/SharedPreferences），改 private→internal 即可；B 组重试的「仅合法值落盘」文本态（L361-368）作为 Group 整体走 |

### 7.2 助手/媒体/统计页面群

| 文件 | 现行数 | 拆后主文件 | 拆分表 | 风险 |
|---|---|---|---|---|
| AssistantPromptPage.kt | 1182 | ~380 | →`AssistantPromptTemplateCard.kt`（模板+预览 ~210）+ `AssistantRegexEditor.kt`（~350）+ `AssistantCardPartsCard.kt`（4 组件 ~250） | 低：全 props 通信无咬合；宿主是整页 verticalScroll Column，拆出后仍渲染同一 Column 即合规 |
| MediaCreationComposer.kt | 1241 | ~460 | →`MediaCreationParamsSheet.kt`（9 组件+**ComposerSheet 宿主同行** ~420）+ `MediaCreationModelSheet.kt`（~110）+ `MediaCreationAssetPickerSheet.kt`（~250） | 低：**L147-169 launcher+importRole 状态块不可动**（L153 注释：系统相册返回时面板已关的竞态修复）；ParamsSheet 的 TextField 宿主是 ComposerSheet，必须同行搬 |
| AssistantMemoryPage.kt | 1291 | ~400 | →`AssistantMemoryDialogs.kt`（4 个 AlertDialog+DueDateEditor ~450）+ `AssistantMemoryConsolidation.kt`（做梦/整理域+**先抽 ConsolidationUiState 状态类** ~350） | 中：唯一真咬合——selectedProposals↔confirmProposals↔勾选行↔弹窗双向咬合，且 CardGroup DSL 非 composable 作用域（L257 注释）；L867 真机注释（建议被截断）随勾选行走；`memoryKindLabel`（internal，被 MemoryRecoveryDialog 引用）移入 MemoryKindFilter.kt |
| StatsPage.kt | 922 | ~420（或不拆） | →`StatsHeatmap.kt`（~215）+ `StatsUsageChart.kt`（~290）；排行卡可留 | 低：零咬合零真机注释，但领域统一、紧迫性最低——「顺手可做」级 |
| PromptInspectPage.kt | 811 | ~400 | →`PromptInspectDiagnosticCards.kt`（三诊断卡 ~200）+ `PromptInspectSegmentCard.kt`（~215） | 低：**segmentOffset=4 是壳与目录跳转的下标算术契约，item 顺序不可重排**（L111-113）；L419-420 `%1$d` 崩溃注释（StringFormatArgsGuardTest 钉住）随卡走；SegmentCard 折叠双源持久化不可拆散 |

### 7.3 数据/服务层

| 文件 | 现行数 | 结论 | 拆分表 | 风险 |
|---|---|---|---|---|
| WorkspaceTools.kt | 1045 | **值得拆** | →`WorkspaceRootfsIo.kt`（rootfs shell IO+路径助手+requireNotBinary ~240）+ `WorkspaceBackgroundTools.kt`（shell/后台任务 4 工具 ~330）+ `WorkspaceFileTools.kt`（读/写/改/展示 4 工具 ~430）+ 入口保留 ~110 | 低：无共享可变状态；**read/write/edit 三兄弟必须同文件**（mtime 闭环 + binary 守卫共用）；`statEntryCommand` NUL 分隔 wire 协议与解码端整体随迁 |
| IncrementalBackupEngine.kt | 1050 | **值得拆** | →`IncrementalBackupGc.kt`（GC+拆包+改写修复+租约四函数 ~430）+ `IncrementalBackupSnapshotReader.kt`（读侧 ~200）+ 保留 plan/commit/assemble ~420 | 低：类几乎无实例状态（仅 cache/json/progress）；**NonCancellable 释放租约的 finally 随 GC 主体走**（L667）；fail-stop 契约（list 宽容 vs snapshotsStrict 严格，L554-559）同层可见 |
| PromptInspector.kt | 910 | 值得拆（中收益） | →`PromptSegmentModel.kt`（模型域 ~240）+ `PromptSegmentProjection.kt`（投影纯函数+分类 ~330）+ 保留 inspectPrompt/refineTokens ~340 | 低：全部纯函数 JVM 单测友好；**与实发同参契约**（L331-339，MemoryAnchorWiringTest 守卫）不可借机改签名；`require(messageSegments.isNotEmpty())` 投影无损守卫随迁 |
| FilesManager.kt | 990 | 只搬顶层模型 | →`FileFolders.kt`（object+SyncResult+ManagedFileOperationResult+runManagedFileOperations+saveUpload* ~180，被 25+ 文件引用）；可选 `ImageExportShare.kt`（~200，FileProvider 范围注释随迁）；**类本体不拆** | 低/中：**AppScope 吞异常是特性**（trackManagedFile best-effort 登记，L808）——拆类换 scope 语义即变契约；孤儿防护（落库失败删文件）、#2127 目录语义长注释随 FileFolders 走 |
| WorkspaceRepository.kt | 1046 | **整类不拆** | 只抽 →`WorkspaceSshConfigCodec.kt`（toSettings+toConnectionConfig+decryptSecret ~130，凭据加密契约自成一体）；可选 `WorkspaceExportGateway.kt`（~170，中风险） | —：`backendOf/readFromBackend/withBackend` 是全文件骨架（可中断读 vs 不可中断写的成对设计，L933-944），拆类=复制骨架或加 facade，反而增加咬合面；installRootfs 的 NonCancellable 状态回滚（L219-228）不动 |
| RikkaHubApp.kt | 907 | **基本不拆** | 只搬 →`NotificationChannels.kt`（7 个渠道常量+createNotificationChannel ~100）；可选 AppScope.kt（~15） | —：postReadyInit 明确不拆——「故意裸协程哨兵」「故意 settingsFlowRaw」「故意同步」三种反直觉契约全是注释级资产，抽 Initializer 类必丢；boot 重入闸/awaitBootReady/ensureKoinStarted 唯一入口不动 |

### 7.4 UI 组件群

| 文件 | 现行数 | 结论 | 拆分表 | 风险 |
|---|---|---|---|---|
| WorkspaceToolUIs.kt | 1049 | **值得拆（最干净）** | →`WorkspaceFileToolUIs.kt`（Edit/Read/Write+摘要预览 ~620）+ `WorkspaceShellToolUIs.kt`（~180）+ `WorkspacePresentFileToolUI.kt`（~195）+ `WorkspaceBackgroundTaskToolUIs.kt`（~175）；JSON 扩展提公共 `ToolUIJsonExt.kt`（~15） | 极低：object 按 toolName 注册进 ToolUIRegistry，搬移不影响注册；`ExistingFileState` 被 Edit/Write 共用随文件域走；真机注释（断行防撑爆 L608、TIMED_OUT L1043、cutsChain L699）随各自 object 走 |
| BuiltinToolUIs.kt | 999 | **值得拆** | →`MemoryToolUI.kt`（~130，CAS revision key 注释随行）+ `WebSearchToolUIs.kt`（+两个 Preview ~300）+ `DeviceToolUIs.kt`（屏幕时间/日历/时间 ~280）+ `MiscToolUIs.kt`（轻摘要 7 件 ~230） | 极低：各 object 独立零共享；顺带修复 321-328 Kdoc 错挂（§6-F.33）；SearchWebPreview 位置 key 不顺手「补」key |
| ModelList.kt（选择器） | 937 | 只拆外围 | →`ModelTags.kt`（3 个 Tag，public 对外 API，~90）+ `ModelListState.kt`（~85）+ `ModelItem.kt`（~95）；**420 行列表主体整块保留** | 低：主体内「位置三表一致性」（selectedModelPosition/providerPositions/items 读同一份 visibleModelsByProvider，L397-398 注释）不可拆；与 SettingProviderDetailPage 内私有 ModelList 是**同名不同物**（管理列表 vs 选择器 sheet），勿混淆合并 |
| ProviderConfigure.kt | 935 | **值得拆（有单测红利）** | →`ProviderUrlConversion.kt`（convertTo+reset+URL 纯函数 ~135，可上 JVM 单测）+ `ProviderApiKeysEditor.kt`（~130 整块）+ `ProviderAccountSection.kt`（~195）；保留入口+三家表单 ~470 | 低：ApiKeysEditor 是全文件约束最密处——item key 用 ApiKeyEntry.id 的历史 bug 注释（L861-866）、heightIn 防无限高度（L857-859）、拖拽本地顺序（L846-849）全部随迁；Account 区 supportingContent 槽位注释（L649-650）随块走；同包搬移调用方零改动 |
| ChatMessage.kt | 914 | 保守拆两刀 | →`ChatMessageBanners.kt`（两条横幅 ~75）+ `ChatMessageMediaParts.kt`（Video/Audio/Image/Document 四分支 ~150，顺带收敛三处 FileProvider intent 重复）；主体保留 ~690；**第一步先做同文件函数化**（文本气泡三分支+引文列表，降 ~120 行） | 低：MessagePartsBlock 不整体搬——ProvideTextStyle 契约（fontSizeRatio L164-170）、整轮折叠 key 语义（L508 注释）、#1929 remember 而非 rememberSaveable（L173-175，TransactionTooLargeException）咬合紧；已是 13 个伴生文件的前轮拆分产物 |
| GoogleSans.kt | 883 | **不拆（死代码）** | 无拆分表。真实问题：30 个 FontFamily val 是同一模板复制且**全仓无运行时引用**（唯一消费在 Type.kt 注释块） | —：处置三选一（需用户拍板）——① 删除文件+`R.font.google_sans_flex` 资源（省 APK 体积）② 私有 builder 函数压缩 883→~150 ③ 保持现状。均与拆分无关 |

### 7.5 ai 模块 + Assistant 模型

| 文件 | 现行数 | 结论 | 拆分表 | 风险 |
|---|---|---|---|---|
| Assistant.kt | 915 | **最值得拆（第二轮冠军）** | 同包五分（import 零变更、序列化形状不变）：`AssistantCard.kt`（CardSlot+buildCardSystemPrompt 等 ~180）+ `AssistantMemory.kt`（~80）+ `AssistantRegex.kt`（regexCache+replaceRegexes 同走 ~70）+ `PromptInjection.kt`（sealed 族+Lorebook/Preset ~230）+ `WorldInfoTrigger.kt`（触发/预算纯函数 ~170）；保留主类 ~230 | 低：唯一纪律——逐字节保留字段声明/@Transient/@SerialName，不借机「清理」契约注释（AssistantMemory 遥测字段 @Transient 的「绝不能让模型看到」语义）；CardSlot defaultOrder 有特征测试锁顺序 |
| ResponseAPI.kt | 1068 | 值得拆（中收益） | →`ResponseCapabilities.kt`（能力+服务端工具映射 ~130）+ `ResponseRequestBuilder.kt`（请求体+消息转换 ~460，**原类留同签名委托**保住 7 个测试文件）+ `ResponseParser.kt`（~150）；保留 Endpoint+传输壳 ~330 | 中（C 块）：addAssistantItems（c7ff737e4 reasoning 回填 400）与 buildMessages（b5b57c41e system 降级）是修复现场，**原样搬家不改逻辑**；streamText 的「失败先发 usage / onClosed 不补 Finish」三条跨文件语义契约整块不动；死常量清理独立 PR |
| GoogleProvider.kt | 844 | 值得拆（三家同模式） | →`google/GoogleRequestBuilder.kt`（~220）+ `google/GoogleContentsConverter.kt`（~200）+ `google/GoogleResponseParser.kt`（~140）；保留传输/鉴权/catalog 壳 ~290 | 中低：buildContents 的 system 降级（b5b57c41e 修复点 L637-640）与 $ref 媒体挂载协议不动；**建议与 ResponseAPI/ChatCompletionsAPI 采用同一「transport 壳/request builder/parser」模式，一次 PR 机械搬移**，避免三家结构不对称 |
| ChatCompletionsAPI.kt | 997 | 只拆两小块 | →`ChatCompletionReasoningDialects.kt`（12 家 host reasoning 方言 when ~160）+ `ChatCompletionsResponseParser.kt`（parseMessage 等 ~150，类上留 internal 委托）；保留 ~690 | —：**streamText 明确不拆**——自学习重发（learn 先于重算的顺序即契约，L168-172）+ 连接跟踪闭包状态是两次历史修复的现场；解码器/回填/连接管理此前已拆出，剩余是拼接层 |
| ModelRegistry.kt | 872 | **倾向不拆** | 若强拆：厂商数据文件 4-5 个（各 defineModel 改 internal）+ 引擎/聚合/访问器保留 ~210，proguard 同 PR | —：纯数据表导航负担低；三重脆弱点——R8 keep 前科（299ac7824）、ALL_MODELS 聚合顺序参与 matchScore 平局、GEMINI_4「冻结声明」注释（L170-181「别当遗漏去修」）；ModelRegistryTest/CatalogTest 可钉回归但收益仍低于风险 |

### 7.6 第二轮总排序（按可行性/收益比）

1. **Assistant.kt** 五分 —— 同包零 import 变更，纯机械
2. **WorkspaceToolUIs / BuiltinToolUIs** 四分 —— object 注册机制免疫搬移
3. **TTSProviderConfigure** 两分 —— 11 块零耦合，全场最容易
4. **WorkspaceTools** 三分 —— 按工具族分文件，契约全是常量/纯函数形态
5. **AssistantPromptPage / ProviderConfigure / SettingMcpPage / SettingSpeechPage** —— 接缝干净
6. **PromptInspector / IncrementalBackupEngine** —— 无实例状态，NonCancellable/契约注释随迁
7. **MediaCreationComposer / SettingThemePage / AssistantMemoryPage** —— 各有一个不可动状态块或需先抽状态类
8. **外围轻拆**：ModelList（三件）、ChatMessage（两刀）、SettingPreferencesUIPage/GeneralPage、StatsPage、PromptInspectPage
9. **明确不拆**：ChatCompletionsAPI.streamText、ModelRegistry、RikkaHubApp.postReadyInit、WorkspaceRepository 整类、FilesManager 类本体、GoogleSans、MessagePartsBlock 整体

---

## 8. 架构级补遗（第三轮，2026-11-07）

> 结论：**文件级分析到此为止，不再追加逐文件轮次**（收益递减 + 行号随演进漂移，行动方案以批次开工时的实时代码为准）。但架构级补一小轮有价值，以下三点已核实。

### 8.1 分层倒挂清单（data → ui 反向依赖，5 处实证）

| 位置 | 依赖内容 | 严重度 | 处置 |
|---|---|---|---|
| `data/datastore/PreferencesStore.kt:94-96` | `ui.theme.CustomTheme/PresetThemes/ThemeRoleOverrides` | **高** | 主题数据模型住在 ui 层被 data 层反向引用——与批次 4（PreferencesStore 拆分）联动：把主题模型移到 `data/model/` 或独立 `theme/model` 包，ui 层只留渲染 |
| `data/plugin/PluginContracts.kt:7` | `ui.components.message.tools.ToolUIRenderer` | **高** | 插件契约接口依赖 UI 组件包——接口应下沉到 plugin 或 ai 层（与插件运行时线协调，避免冲突） |
| `data/plugin/BuiltInPlugins.kt:13` | `ToolUIRegistry` | 中 | 同上，随 PluginContracts 一起整理 |
| `data/ai/tools/ChatToolFactory.kt:4` | `ui.components.message.tools.getStringContent` | 中 | 工具函数从 UI 包下沉到 data/ai 层 |
| `data/datastore/DefaultProviders.kt:14` | `ui.components.richtext.MarkdownBlock` | 低（待查） | data 层 import Compose 组件本身可疑，开工时先确认是否预览专用残留 |

utils 层反向依赖 1 处：`utils/ContextCapacity.kt` → data.ai/data.repository——ContextCapacity 实为业务组件而非 utils，可在批次 7 顺带归位（搬去 `data/ai/` 或组件层）。

### 8.2 ViewModel 层健康（无需专门轮次）

Top：MediaCreationVM 720 / ChatVM 694 / BackupVM 670 / WorkspaceDetailVM 585。无千行怪物，体量与职责匹配（备份/聊天本来就是重业务）。唯一建议：批次 7 拆 MediaCreationComposer 时顺手看 MediaCreationVM 是否有可下沉到 data 层的逻辑，不单独立项。

### 8.3 跨文件重复模式（批次顺带项，不单独立项）

- **分享 Intent（ACTION_SEND）8 处散布**：ChatMessageEditedFiles / WorkspaceToolUIs / ImagePreviewDialog / Export / WorkspaceDetailPage / LogPage / MediaCreationCommon / ShortcutHandlerActivity——可收敛为 utils 分享封装（Export 拆分批次已有 shareFile/shareFiles，可顺势上提复用）
- **DragOrderState 拖拽三件套 9 处使用**：防抽搐模式（先本地后落盘）已统一，但样板代码仍每处手写——收敛成 rememberDragOrderState 封装属可选优化，非必需
- **FileProvider intent 三连**（ChatMessage 媒体四分支内）：批次 7 拆 MediaParts 时顺带收敛（台账 §7.4 已记）

### 8.4 最终判断

到此**分析完备**，不再有第三轮逐文件盘点。剩余工作全部转入执行态：按 §5 批次推进，每批次开工时重新核对行号与引用面（本台账行号基于 e9041d57d 基线，随演进失效属正常，架构结论与契约红线长期有效）。分层倒挂（§8.1）建议插在批次 4 与批次 7 之间或作为独立小批次处理。
