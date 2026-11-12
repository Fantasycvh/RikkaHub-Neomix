# 设置类输入框「抖动 / 丢字」根治：验证记录

日期：2026-11-12。仅本地改动与本地验证，**未推送**。

用户报障：助手名称等输入框又抖了——「又是之前的问题」。本次按 AGENTS.md「TextField Host Rules」做全仓排查，
定位到**规则 4（状态流向单向）**缺失：设置类文本域把 `value =` 直接绑在**异步持久态投影**上，
又在 `onValueChange` 里逐字符写回整份 `Settings`，落盘后的旧值会回灌到**活跃编辑框**。

审计材料（被 `.gitignore` 忽略）：`.tmp/audit-ime/host-topology.md`（宿主拓扑，387 行）、
`.tmp/audit-ime/stateflow-audit.md`（状态流清单，430 行，48 处高风险 + 数值投影变体）、
`.tmp/audit-ime/device-verify/`（真机截图）。

## 根因

链路（助手「基础设定」名称框，修复前）：

1. `AssistantBasicPage` 名称框 `value = assistant.name` —— 值来自 `SettingsStore.settingsFlow`，
   它是 `settingsFlowRaw` 在 `Dispatchers.Default` 上整份解码后的 `MutableStateFlow` 投影。
2. 每敲一个字符 → `onUpdate(assistant.copy(name = it))` → `AssistantDetailVM.update` **锁外读**
   `settings.value` 再整份 `settingsStore.update(...)`（读-改-写快照，并发互相覆盖的来源）。
3. `PreferencesStore.updateLocked` 把**全部**偏好 key 重新序列化写盘（设备上 settings 文件
   789 KB / 772 K 行），完成后 `settingsFlow.value = 新 Settings` 广播。
4. Compose 的 `TextField(value: String)` 收到**外部 value 变化**会重置内部编辑态：光标移到串尾、
   IME 组合被打断。中文输入法下组合串被反复取消，就是用户看到的「每敲一下抖一下 / 吞字 / 光标跳」。

真机 A/B（同一设备、同一 120ms/字符注入节奏，PJX110 / Android 15 / debug 包）：

| 场景 | 编辑态权威 | 注入 | 结果 |
| --- | --- | --- | --- |
| 助手「基础设定」名称框（修复前） | 异步持久态投影（写穿回灌） | `a..j` 逐字符，120 ms | **`bcdefghija`**（应为 `abcdefghij`） |
| 同上，整串注入 | 同上 | `abcdefghijklmnopqrstuvwxy` 一次 | **`aabbxec`**（7 字符垃圾） |
| 「提示词」页系统提示框 | `rememberTextFieldState` + `snapshotFlow` | `a..j`，120 ms | `abcdefghij` ✅ |
| 新建助手 sheet 名称框 | `EditState` 本地态 | `x` ×10，120 ms | `xxxxxxxxxx` ✅ |

⇒ 写盘频率本身不会破坏输入（两个对照组都完整），**回灌**才会；但逐键整份写盘仍应消除（延迟/jank 与
「整对象写丢更新」风险）。

## 修复

### 1. 统一 hook：本地权威 + 单向去抖写出 + 离开页面补写

新增 `app/src/main/java/app/androidrik/rikkahub/ui/hooks/PersistedTextFieldState.kt`：

- `rememberPersistedTextFieldState(externalValue, onCommit, debounceMillis = 250, resetKey)`：
  - `rememberTextFieldState(externalValue)` 只做**首次**初始化（`resetKey` 变化才重建），此后 TextField
    是编辑态唯一权威，**绝不回灌**；
  - `snapshotFlow { text }.drop(1).collectLatest { delay(250); onCommit(it) }` 单向写出（去抖，
    避免逐键整份写盘）；
  - `DisposableEffect { onDispose { 若与已提交值不同则补写 } }`：去抖窗口内按返回/切页**不丢尾字**
    （`onCommit` 要求走取消安全的 scope，页面销毁后仍落盘）。
- 不采用「compare-then-setTextAndPlaceCursorAtEnd 回填」：文本域提交存在排队窗口，
  只和 `lastCommitted` 比较仍可能把**较旧的 echo** 回灌成当前值（比旧写法更隐蔽的吞字）。
  本 hook 只初始化一次，从根本上没有「回灌」这条路径。

### 2. 数据层：字段级 transform 写，AppScope 落盘

`AssistantDetailVM`（`viewModelScope` → 新增 `AppScope` 构造参数，Koin 注入）：

- `updateName(name)` / `updateMaxTokens(Int?)` → 私有 `updateAssistantField(transform)`，走
  `settingsStore.update { current -> current.copy(assistants = ...map { if (id) transform(it) }) }`：
  用 DataStore 的串行 `edit` 承担原子性，不再读 `settings.value` 整对象覆盖；
- 走 `AppScope` 而不是 `viewModelScope`：去抖窗口内按返回时 ViewModel 可能正在 `onCleared()`，
  `viewModelScope` 会被取消 ⇒ 最后几个字符静默丢失（`SettingWebPage` 的端口/口令去抖补写是
  仓内同款先例）。

### 3. 助手「基础设定」页

- 名称框：`value/onValueChange` → `state = rememberPersistedTextFieldState(assistant.name, vm::updateName,
  resetKey = assistant.id)`，`TextFieldLineLimits.SingleLine`（名称不允许换行长高）。
- 最大 token 框（数值投影变体）：同 hook；`onCommit` 只在「空串 → null」或「合法正整数」时落库，
  非法中间态留在框里让用户继续改；`supportingText` 读本地态，两分支都渲染 `Text`（高度恒定）。
- 容器 insets 顺序改正：`imePadding()` 移到 `verticalScroll()` **之前**（视口侧），与
  `ScheduledTaskDetailPage`/`ScheduledTaskCreatePage` 的既有真机结论一致。

### 4. 新建助手 sheet 宿主（规则 2/3）

`AssistantCreationSheet` 的裸 `Column` 补 `imePadding() + verticalScroll(rememberScrollState())`，
名称框加 `singleLine = true`——这是全仓 12 个 `ModalBottomSheet` 里唯一没有 IME 协调的一个，
键盘弹起时内容不收缩、名称框换行长高会把 sheet 重新锚定。

### 5. 助手详情页 insets 顺序

`AssistantBasicPage`、`AssistantPromptPage`、`AssistantRequestPage`、`AssistantLocalToolPage`
（整页 + 本地工具 sheet）统一改为 `imePadding()` 在 `verticalScroll()` 之前。

## 防回归

- `scripts/audit_ime_trap.py`：新增 **D 类**（同一 Modifier 链上 `verticalScroll` 先于 `imePadding`，
  仅告警）、**E 类**（`value =` 直连异步持久态根且 `onValueChange` 写回，仅告警）；修复 A' 盲区
  （`item {` 无括号写法，真命中 4 处存量）、CardGroup `item(...)` 豁免误判；A/A' 仍为阻断项。
- 新增 `scripts/ime_trap_exceptions.json`（与 `app_skin_coverage_exceptions.json` 同风格）：4 处
  **已复核**的规则 2 存量债（`SettingPreferencesNetworkPage` 代理卡、`SettingPreferencesGeneralPage`
  重试卡、`SettingModelPage` 上下文窗口、`SettingWebPage` 端口），带 count，超量新增仍阻断。
- 新增 `scripts/test_audit_ime_trap.py`（11 个用例）：A/A' 命中、CardGroup 豁免、`item {` 盲区、
  例外额度按文件生效且不串文件、D 正反例、E 正反例。
- `AGENTS.md` 规则 4 增补「2026-11-12 第五次定案」：写穿回灌机制、真机 A/B 数据、hook 用法与
  「新增违规禁止加豁免」的要求。

## 验证

| 检查 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | ✅（`app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/.../PersistedTextFieldStateKt.class` 已产出） |
| `:app:assembleDebug` | ✅ `BUILD SUCCESSFUL in 45s` |
| `:app:testDebugUnitTest` 全量 | ✅ 3449 项：3447 通过、2 跳过、**0 失败/错误** |
| `:ai:testDebugUnitTest` | ✅ 535 项，0 失败/错误 |
| `python scripts/audit_ime_trap.py` | ✅ `A=0  A'=0（豁免 4）  B=4  C=35  D=6  E=46` |
| `python -B scripts/test_audit_ime_trap.py` | ✅ 11/11 |
| 真机 PJX110（`6c841f95`，Android 15，debug 包） | ✅ 见下 |

### 真机验证（修复后，安装本次 `app-arm64-v8a-debug.apk`）

可复现命令（注入节奏与修复前基线一致）：

```bash
# 名称框：聚焦 → MOVE_END + 15×DEL 清空 → 120ms/字符注入 a..j
adb -s 6c841f95 shell input tap 600 1274
adb -s 6c841f95 shell input keyevent KEYCODE_MOVE_END
for i in $(seq 1 15); do adb -s 6c841f95 shell input keyevent KEYCODE_DEL; done
for c in a b c d e f g h i j; do adb -s 6c841f95 shell input text "$c"; sleep 0.12; done
# uiautomator dump 读 EditText → 'abcdefghij'
```

| 用例 | 修复前 | 修复后 |
| --- | --- | --- |
| 名称框 120 ms/字符 `a..j` | `bcdefghija` | **`abcdefghij`** ✅ |
| 名称框整串 25 字符 + **立即按返回**（测离页补写） | `aabbxec`（且清空会残留 `x`） | 一次注入后立刻返回，重新进入页面读到 **`abcdefghijklmnopqrstuvwxy`**（25/25）✅ |
| 最大 token 框 120 ms/字符 `4096` | 逐键被 Int? 投影回灌（旧值截断） | **`4096`**，辅助文案「限制：4096 个Token」✅ |
| 新建助手 sheet：聚焦名称框（键盘弹起） | 裸 Column，内容不收缩 | 内容整体上移/可滚动，字段全程可见 ✅ |
| 新建助手 sheet：`x` ×10，120 ms | `xxxxxxxxxx`（本来就对） | `xxxxxxxxxx` ✅（未回归） |
| 助手列表 → 删除临时助手 | — | 删除确认后列表不再出现该助手 ✅（测试数据已清） |

截图见 `.tmp/audit-ime/device-verify/`（`02-name-25chars-after-back.png` 为离页补写后的详情页，
`04-creation-sheet-typed.png` 为 sheet 输入态）。真机包为 `app.androidrik.rikkahub.debug`，
启动组件 `app.androidrik.rikkahub.RouteActivity`；验证只用临时助手「测试助手」，未改动任何既有
助手/会话/记忆，验证结束后已删除该临时助手。

## 未做 / 遗留

1. **尚未迁移的 48 处高风险**（清单见 `.tmp/audit-ime/stateflow-audit.md` 附录 C）：助手提示词页
   6 处、S3/WebDav 设置 tab、设置搜索详情页（15 处 Lazy item 内字段）、知识库详情页、模型设置表单、
   供应商配置等；均为同一规则 4 家族，按 P0→P2 排期迁移到本 hook。E 类告警 46 处即其当前快照。
2. **D 类存量 6 处**（`CompressContextDialog`、`DebugPage`、`LorebookPage`、`SettingMcpPage`、
   `PersonalizationEditors` ×2）：insets 顺序反了但不是本次报障现场，已进告警清单。
3. **规则 2 存量 4 处**（见 `scripts/ime_trap_exceptions.json`）：改 sheet/整页涉及产品交互，未动。
4. 未做中文输入法**组合态**的真机录像取证（adb 无法注入拼音）：用逐字符 ASCII 注入 + IME 组合
   被打断的既有结论作为代理；帧级抖动测量（无 ffmpeg）同样未做。
5. 本次只改了 assistant 设置页与共用 hook；`FormItem` 双重 modifier（8dp→16dp）是纯布局缺陷，
   另行处理。
