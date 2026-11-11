# Phone Use：AI 通过无障碍服务操作手机（design-phone-use）

> 状态：**v1 已落地（2026-11-11）**，P2 前两项（60s 审批超时、用户可编辑黑名单）已完成；
> 每轮步数硬上限、元素列表差分压缩仍开放。
> 真机验证（2026-11-11，Example Assistant + glm-4.5-air）：无障碍服务绑定 →
> 悬浮窗开着 → AI 打开 ColorOS 便签（`com.coloros.note`）→ 新建并输入
> “Phone Use works.” → 保存成功；会话回报 4 项完成。
> 上游基线：`25b70392a`（工具/无障碍服务）、`ffb1c79e1`（按 action 的 tool UI）、
> `8a837b27e`（i18n），随 rikkahub 3.2.0 发布；上游仅 github 渠道 + Android 11+。
> 本仓差异：无 product flavor（只走自有分发），无障碍服务直接进 `main` 源集，用
> `Build.VERSION.SDK_INT >= 30` 与 `values-v30` bool 门控。
> 相关：`docs/design/completed/design-agent-image-tool.md`（审批开关同型）、
> `docs/design/completed/design-ask-user-interrupt.md`（审批/跳过语义）。

---

## 〇、结论先行

1. **做，但不原样搬。** 上游的能力层（元素树 + 手势 + 截图 + 归一化坐标）可以直接移植；
   审批、来源门控、并发隔离和截图隐私必须按本仓的运行时补。
2. **v1 只服务“用户在设备旁的交互回合”**：App 在前台或后台、悬浮窗开着都算；
   定时任务、子代理、Web/API 远程会话**不装配** `phone_use`。
3. **审批默认开，按“回合”授权**：本轮第一个需要审批的动作弹一次；批准后本轮后续动作不再问，
   拒绝则本轮后续变更动作都不再执行。`observe`/`list_apps`/`wait`/`press HOME` 免审批。
4. **截图按 S2**：属于敏感读取，和本轮第一个变更动作一起确认；不做“截图永远免确认”。
5. **不做**：无人值守模式、Web 远程操作、虚拟屏。用户可编辑黑名单已随 P2 落地。

---

## 一、上游做了什么

| 层 | 上游实现 | 本仓落点 |
|---|---|---|
| 工具 | `phone_use`，action = observe/screenshot/tap/long_press/swipe/scroll/type/press/open_app/list_apps/wait | `data/ai/tools/local/PhoneUseTool.kt` |
| 坐标 | 0–1000 归一化网格，截图缩放后仍对得上 | 原样保留 |
| 执行端 | `PhoneController` 接口 + `PhoneUseManager` + `PhoneUseAccessibilityService` | `phoneuse/` 包 |
| 手势反馈 | `GestureIndicator`，`TYPE_ACCESSIBILITY_OVERLAY` 不接收触摸 | 原样保留 |
| 悬浮窗 | 截图/手势时 alpha=0 + 不可触摸让位；本轮结束时自动展开 | 适配本仓 FloatingX/ tracker |
| 安全 | 本应用前台不操作；支付/银行包名黑名单；工具描述要求不可逆动作先问 | 保留并加固 |
| 分发 | github 源集 + Android 11+；Play 版无服务 | 本仓无 flavor，仅 SDK 门控 |

上游的关键设计（值得保留）：元素列表为主、截图按需；每个变更动作后 `SETTLE_MS=800ms`
再读屏并回带新元素列表；动作串行；悬浮窗让位不用 `hide()`（避免动画闪烁，且 Android 12
触摸穿透不透明窗会被系统丢弃）。

---

## 二、v1 边界

### 2.1 能力

动作集合与上游一致。`press HOME` 视为安全导航（AI 需要离开本应用），其余 `press` 归变更类。

### 2.2 审批

- **开关**：`Assistant.phoneUseApproval: Boolean = true`。关闭 = 不做任何确认（上游行为），
  助手页红字提示风险。与 `imageGenApproval` 同型。
- **回合**：一次 generation（从用户消息/重生成开始到本轮结束）为一个回合；跨工具审批续跑
  仍属同一回合。授权状态放 `ConversationSession`，`beginNewTurn()` 清空。
- **动作分级**：

| 动作 | 需要回合授权 |
|---|---|
| `observe`（dump）、`list_apps`、`wait` | 否 |
| `press HOME` | 否 |
| `screenshot`（S2） | **是** |
| `tap` / `long_press` / `swipe` / `scroll` | 是 |
| `type` / `press ENTER` / `press BACK / RECENTS / NOTIFICATIONS` | 是 |
| `open_app` | 是 |

- **第一次**：本轮第一个需要授权的动作触发审批卡。批准 = `phoneUseApprovedForTurn=true`，
  后续动作不再问；拒绝 = `phoneUseDeniedForTurn=true`，本轮后续 `phone_use` 不再装配/执行。
- **UI 文案**：审批卡按钮显示“允许本轮”，不是泛化的“批准”；悬浮窗与聊天页同一语义。
- **超时**：P2 已加 60s 看门狗（`ChatService.schedulePhoneUseApprovalTimeout`）：
  待批的 `phone_use` 到点仍 Pending 则按拒绝落地，并带
  `PHONE_USE_APPROVAL_TIMEOUT_REASON` 让模型别再重试；用户先批准/拒绝时计时到点
  发现已不再是 Pending 会自动作废。
- **关闭审批时**：`needsApproval` 直接返回 false，不产生授权状态。

### 2.3 来源门控

v1 只允许 **APP 交互回合**：

| 来源 | 处理 |
|---|---|
| App 前台/后台、悬浮窗开着 | 允许 |
| 定时任务（`sched:` run） | 装配期不提供 `phone_use` |
| 子代理（`SubagentRunner`） | 装配期不提供 |
| Web/API 会话 | 装配期不提供 |
| 语音（App 内） | 允许；注意本应用前台时 `ensureNotSelf` 会阻止实际操作 |

实现：`QueuedMessage.allowPhoneUse` 随队列项走；`ChatService` 把它带到本轮的
`ConversationSession.phoneUseAllowedForTurn`；`ChatToolFactory.createTools/createToolSegments`
接收 `allowPhoneUse`，为 false 时跳过 Phone Use 段。`SubagentRunner` 固定传 false。
Web 的发送/编辑入口传 false；App 默认 true。`PromptInspection` 只做预览，不执行。

### 2.4 隐私

- **只读应用窗口**：`targetRoots()` 只取 `TYPE_APPLICATION`（加活动窗口兜底），
  默认不读通知栏等 `TYPE_SYSTEM`，避免验证码/通知内容无审批进入上下文。
- **password 节点**：`AccessibilityNodeInfo.isPassword` 的文本/contentDescription 不进入
  label；节点仍以 `editable` 出现，显示 `password` 标记，允许向其中输入。
- **截图**：
  - Android 14+ 用 `takeScreenshotOfWindow` 只截最上层应用窗口；
  - Android 11–13 只能整屏，检测到当前有焦点的系统窗口时拒绝并提示先关闭通知栏；
  - `FLAG_SECURE` 返回明确错误（`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`），不返回黑图；
  - 截图落 `FileFolders.PHONE_USE`（新目录，**不进备份**），聊天内可显示，但不随备份带走。
- **黑名单**：内置支付/银行包名表始终生效，并检查所有可见窗口；P2 起支持用户
  追加包名（`DisplaySetting.phoneUseBlockedPackages`），工具侧与内置表合并判断，
  本地工具页「被禁应用」弹窗负责增删与格式校验。
  - 弹窗只允许 Hidden/Expanded 两态：半展开时键盘弹起会把输入框压在 IME 下面。
  - 真机验证（2026-11-11）：添加/删除即时生效并持久化，非法包名提示
    「请输入有效的包名」，输入框完整位于键盘上方。

### 2.5 并发与资源

- `PhoneUseManager` 提供**全局手机控制锁**：同一时刻只有一个会话能执行动作；
  第二个会话拿到“另一会话正在使用 Phone Use”的错误，不排队等屏幕。
- 每个工具调用会话保留自己的 `lastScreen`；全局锁的 epoch 变化时丢弃缓存，避免
  A 会话的元素编号被 B 会话的屏幕解释。
- `acting` 改引用计数，嵌套/并发时不会提前把悬浮窗恢复成可触摸。
- 工具串行：保留 `concurrentSafe=false`（默认），不与其他工具并行。

---

## 三、审批状态机

```
本轮开始 (beginNewTurn)
  phoneUseApprovedForTurn = false
  phoneUseDeniedForTurn   = false

phone_use(action):
  read-only? ──是──> 直接执行
        │否
        ├─ approval 开关关 ──> 直接执行
        ├─ approvedForTurn ──> 直接执行
        ├─ deniedForTurn   ──> 拒绝（不弹卡）
        └─ 否则 ──> Pending，弹审批卡
                      ├─ 允许本轮 -> Approved + approvedForTurn=true
                      ├─ 拒绝     -> Denied + deniedForTurn=true
                      └─ 60s 超时 -> Denied（按拒绝处理）
```

- 审批卡在悬浮窗里自动展开；聊天页同步显示。用户多半在别的应用里，聊天页的卡不够。
- `handleToolApproval(approved=true)` 对 `phone_use` 额外写回合授权；不新增持久化的
  `ToolApprovalState` 子类型，避免污染消息树和备份。
- 拒绝后本轮从工具装配里去掉 `phone_use`；模型不会再看到这个工具，避免反复重试。

---

## 四、关键实现点

1. **无障碍服务**：`flagRetrieveInteractiveWindows | flagReportViewIds`、
   `canPerformGestures | canRetrieveWindowContent | canTakeScreenshot`；
   `MAX_ELEMENTS=150`，超限标记 `truncated`；无文字可点容器用后代文字命名。
2. **手势**：`dispatchGesture`；点击用原地短路径；长按 700ms；滑动 350ms；截图/手势前
   通过 `PhoneUseManager.beginActing()` 让悬浮窗让位。
3. **输入**：`ACTION_SET_TEXT`；`submit` 用 `ACTION_IME_ENTER`（API 30+）；
   元素编号对应最近一次 `captureScreen` 的节点列表。
4. **打开应用**：`getLaunchIntentForPackage` + `NEW_TASK`；后台启动豁免需真机验证。
5. **Tool UI**：按 action 换标题/图标，带上元素编号、坐标、文本、应用名；
   避免一整轮都是 “Called tool phone_use”。
6. **悬浮窗让位**：只改 `WindowManager.LayoutParams.alpha` 与 `setWindowTouchable(false)`，
   不 `hide()`；展开的面板先 `collapse()`；恢复时单独提交一次透明度。
7. **自动展开**：本轮结束或出现 Phone Use 审批时展开面板；在既有 turns-based tracker 上
   重新挂点，不照搬上游 `JobsChanged`。
8. **启动竞态（真机实证 2026-11-11）**：`RikkaHubApp.boot` 在 IO 上异步执行
   `startKoin`，无障碍服务可能在它之前绑定；服务里 `by inject()` 会抛
   `NoDefinitionFound` 并触发安全模式。`PhoneUseManager` 用进程级静态单例，
   Koin 的 `single` 返回同一实例，服务不依赖 Koin。

---

## 五、分期

- **P0 底座**：`phoneuse/` 三文件、manifest/xml/bools、strings；工具可被 FakeController 单测。
- **P1 接线**：`LocalToolOption.PhoneUse`、`LocalTools`/DI、`Assistant.phoneUseApproval`、
  助手页开关与提示；`allowPhoneUse` 来源门控。
- **P2 审批**：回合授权/拒绝、`ConversationSession` 状态、`GenerationHandler` 判定、
  悬浮窗/聊天审批卡“允许本轮”。
- **P3 体验/隐私**：tool UI renderer、悬浮窗让位与自动展开、截图落独立目录、
  password/系统窗口过滤、FLAG_SECURE 错误。
- **P2 已落地（2026-11-11，`1c38e9512`）**：60s 审批超时自动拒绝、用户可编辑黑名单。
- **P2 剩余**：每轮步数硬上限、元素列表差分压缩。
- **P4 验证**：FakeController 单测、全量单测、debug 构建、真机验收。

---

## 六、验收

JVM：

- 网格换算、元素编号过期、scroll 方向、被禁应用、本应用前台、悬浮窗关闭；
- S2：screenshot/tap/type 触发 needsApproval，observe/list_apps/wait/HOME 不触发；
- 回合授权：批准后同轮不再问，拒绝后同轮不再装配/执行；
- 来源：`allowPhoneUse=false` 时工具集合里没有 `phone_use`。

真机：

1. 系统无障碍里开启 “Neomix Phone Use”；
2. 悬浮窗开着，AI `open_app` → `observe` → `tap` → `type` 一次完成；
3. 首个变更动作弹“允许本轮”，批准后后续动作不再弹；
4. 拒绝后本轮不再操作；
5. 截图在 Android 14+/13 各验一次（窗口/整屏、FLAG_SECURE）；
6. 悬浮窗在截图与手势期间不可见、不挡点击，手势动画可见；
7. 关掉悬浮窗或撤销权限 → 工具返回可读错误；
8. 定时任务/子代理/Web 会话看不到 `phone_use`。

---

## 七、非目标与风险

- **不做无人值守**：审批关闭 + 定时任务会形成静默操作，v1 用装配期门控堵死。
- **不做 Web 远程**：认证不等于本人在设备旁；如未来要做，必须设备端二次确认 + 独立总开关。
- **不做虚拟屏**：公开 SDK 的 `VirtualDisplay` 只能承载自有内容；`ActivityView` 不在公开 SDK，
  `dispatchGesture` 无 display 参数；最多做节点动作型研究，不进 v1。
- **步数硬上限**、**元素列表差分压缩**放 P2；当前依赖内置+自定义黑名单、S2 审批、
  60s 超时兜底、LoopGuard/上下文闸与用户停止。
