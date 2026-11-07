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

其余 900~1100 行段（SettingThemePage、SettingPreferencesUIPage、SettingSpeechPage、ConversationRepository、WorkspaceRepository、WorkspaceTools、FilesManager、ModelList、ProviderConfigure、SettingSearchDetailPage、StatsPage、Assistant.kt、ChatMessage、PromptInspector、RikkaHubApp、GoogleSans、ImgGenPage、ModelRegistry、GoogleProvider、PromptInspectPage、IncrementalBackupEngine、WorkspaceToolUIs、BuiltinToolUIs、ResponseAPI、ChatCompletionsAPI、AssistantMemoryPage、AssistantPromptPage、SettingProviderModelPage 等）——本次未逐个深读，按本台账模式逐个评估后再动。

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
批次 1  Export.kt 六分                （耦合最小，练手验证流程）
批次 2  ChatServiceModels + SubagentRunner 三块 + GenerationHandler 纯函数三块
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

---

## 7. 未深入分析的文件（后续补充）

SettingMcpPage.kt（1331）、MediaCreationComposer.kt（1241）、TTSProviderConfigure.kt（1204）、AssistantMemoryPage.kt（1291）、AssistantPromptPage.kt（1182）、SettingThemePage.kt（1140）、SettingPreferencesUIPage/GeneralPage、SettingSpeechPage.kt（1096）、IncrementalBackupEngine.kt（1050）、WorkspaceToolUIs/BuiltinToolUIs（~1000）、WorkspaceRepository/WorkspaceTools（~1045）、FilesManager.kt（990）、ModelList.kt（937）、ProviderConfigure.kt（935）、StatsPage.kt（922）、Assistant.kt（915）、ChatMessage.kt（914）、PromptInspector.kt（910）、RikkaHubApp.kt（907）、GoogleSans.kt（883）、ModelRegistry.kt（872）、GoogleProvider.kt（844）、PromptInspectPage.kt（811）、ai 模块 ResponseAPI.kt（1068）/ChatCompletionsAPI.kt（997）。

评估模板：顶层声明清单 → 内聚子域 → 共享状态/契约 → 按第一梯队模式产出拆分表。
