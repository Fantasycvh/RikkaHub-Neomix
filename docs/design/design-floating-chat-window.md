# 可自由拉伸的悬浮窗（小窗）模式设计

> 状态：**设计草案，未实现**。2026-10-20。
> 需求来源：用户「考虑设计一个可自由拉伸的悬浮窗(小窗)模式」。
> 上游（rikkahub）**没有**该功能（只有同款 TTS 浮窗组件），本设计属 fork 自有方向。

## 1. 需求拆解：「可自由拉伸」意味着什么

一句话目标：**聊天界面脱离主窗口，悬在桌面/其他应用上，宽高和位置由用户随手调**。

这句话隐含三个硬要求，缺一个就不成立：

| 要求 | 含义 | 排除了什么 |
|---|---|---|
| **任意宽高** | 8 向拉伸（4 角 + 4 边），带最小尺寸，拖多少是多少 | 固定几档尺寸、只能整体缩放 |
| **任意位置** | 可拖到屏幕任意处、松手贴边、跨旋转保持 | 固定角落的小挂件 |
| **可输入** | 窗口内有输入框，能打字、能发、能停 | 画中画（PiP 不能输入）、只看不说的「速览窗」 |

再加一个隐含的手机端约束：**要能盖在别的 app 上**（边看视频/边查资料边聊），否则「小窗」只是在自家 app 里开个小方块，价值有限。

## 2. 现状盘点：地基有一半，且已坏了一半

| 项 | 现状 | 对本设计的影响 |
|---|---|---|
| 悬浮窗库 | `FloatingX 2.3.7`（`floatingx` + `floatingx-compose`）已在依赖；`ui/components/ui/FloatingWindow.kt` 用于 TTS 朗读浮窗 | 只支持**拖拽 + 边缘吸附**，**没有 resize**；自由拉伸要自己写 `WindowManager` 层 |
| **权限** | **`AndroidManifest.xml` 里没有 `SYSTEM_ALERT_WINDOW`**；真机 `appops` 查询 = `default` 且带 `rejectTime`（今天 11:00 左右一次被系统拒绝的 addView） | **现有 TTS 朗读浮窗事实上是不可用的**（`Settings.canDrawOverlays` 拿不到授权 → `FloatingWindow` 静默降级）。任何系统悬浮窗都必须先补这个声明 + 授权引导 |
| 前台服务 | 已有 4 个（WebServer / TexCompile / KeepAlive / MediaCreationForeground），`specialUse` 与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 声明齐全 | 浮窗服务照抄这个模式即可，无需新概念 |
| 生成能力 | `ChatService` 是**单例、按 `conversationId` 驱动**：`sendMessage` / `continueMessage` / `regenerateAtMessage` / `translateMessage` / `handleToolApproval` 全都不依赖 Activity | **生成可以在浮窗里跑；关掉浮窗也不影响生成**（根因是生成本来就在 AppScope 里，与界面解耦） |
| 消息渲染 | `ChatMessage` 系列是纯 Compose 组件 | 可以在 overlay 里复用；但 `ChatPage` 里相机/相册/文档选择器用 `rememberLauncherForActivityResult` |
| **Activity 依赖** | `ChatPage` 绑 Scaffold/insets/导航/抽屉/ActivityResult；**没有 Activity 就弹不出系统选择器** | 浮窗 P0 **不能做附件/相机/文档选择**。这是架构约束，不是懒 |
| 主题 | `RikkahubTheme` + `LocalSettings`（CompositionLocal）可从 `SettingsStore` 提供 | overlay 里能保持一致外观与字体缩放 |
| 深链 | `RouteActivity` 已支持 `intent.getStringExtra("conversationId")` → `Screen.Chat(id)` | 浮窗里「展开到主界面」可零成本复用 |

## 3. 方案对比

| 方案 | 自由拉伸 | 盖在其他 app 上 | 能输入 | 成本 | 结论 |
|---|---|---|---|---|---|
| **A. 系统悬浮窗**（`WindowManager` + `TYPE_APPLICATION_OVERLAY`） | ✅ 自绘 8 向热区 | ✅ | ✅（需自建焦点与 IME 处理） | 中高 | **推荐主方案** |
| **B. 应用内浮窗**（`RouteActivity` 内 Compose 叠层） | ✅ | ❌ 只在 app 内 | ✅ | 低（复用同一 pane） | P0.5 / 退路 / 组件地基 |
| C. 系统分屏 / 桌面 freeform | ✅（系统给手柄） | 部分 | ✅ | 低（只做自适应布局） | **不能由 app 发起**，只能声明 `resizeableActivity` 并适配任意比例；做补充不做主实现 |
| D. 画中画 PiP | ❌ 系统锁比例、只有几个按钮 | ✅ | ❌ | 中 | 只能做「瞥一眼」模式，**不满足本需求**；可作 P2 补充 |

**推荐：A 为主，B 复用同一套 `FloatingChatPane`（宿主不同、界面相同）**。这样「应用内小窗」几乎是白送的退路，也方便在 overlay 权限被 ROM 卡住时降级。

## 4. 推荐架构

```
FloatingWindowService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner
│  foregroundServiceType=specialUse + 通知（与 KeepAliveService 互斥/合并）
├── OverlayWindowController      WindowManager 增删窗口、LayoutParams、insets、焦点
│    ├── 拖拽 / 8 向拉伸 / 吸附 / 最大化还原 / 收起气泡
│    └── FloatingWindowGeometry  纯函数：clamp、resizeByHandle、snapToEdge、旋转恢复  ← JVM 单测
├── FloatingComposeHost         自建三类 owner，把 ComposeView 挂进 overlay
│    └── FloatingChatPane()     精简会话界面（复用 ChatMessage 只读渲染 + 自建输入栏）
└── FloatingChatVM              会话 flow、输入态、send/stop/regenerate（内部调 ChatService）
```

三条设计原则：

1. **不复用整页 `ChatPage`**。它绑定 Scaffold/insets/导航/抽屉/ActivityResult，硬搬进 overlay 要伪造太多宿主，最终得到一个「能跑但处处不对」的窗口。抽一个 `FloatingChatPane`（目标 ≤600 行），复用消息渲染子集，其余重写。
2. **几何与手势做成纯函数**（`clampGeometry` / `resizeByHandle` / `snapToEdge` / `restoreAfterRotation`），overlay 层只负责副作用。这类逻辑在真机上最难调、最容易回归，必须能被 JVM 单测钉住。
3. **窗口只有一个**。重复「在小窗中打开」= 切换到该会话并聚焦已有窗口，不做多窗口并存（P2 再议）。

## 5. 交互规格（P0）

| 项 | 规格 |
|---|---|
| 入口 | 抽屉底部动作区「悬浮小窗」+ 会话长按「在小窗中打开」+ 设置页开关 |
| 初始几何 | `min(360dp, 屏宽×0.9) × min(560dp, 屏高×0.72)`，居中偏右，不压输入法 |
| 拖拽 | 标题栏 + 窗口内缘 8dp；跟手移动，松手距左右边 <16dp 自动贴边 |
| 拉伸 | 4 角 + 4 边共 8 个热区（命中 16dp，视觉 2dp 高亮）；最小 `280×320dp`，最大 `屏宽×0.95 / 屏高×0.9`，越界 clamp |
| 标题栏 | 会话名 + 切换会话（最近列表 / 新建）+ 最大化↔还原 + 收起为 56dp 气泡 + 关闭 |
| 输入 | 文本 + 发送 / 停止 / 继续生成；IME 弹起时**手动**上移或缩高（overlay 没有 `adjustResize`，监听 `WindowInsets.ime` 自己调） |
| 返回键 | overlay 没有系统返回；窗口内提供返回/关闭按钮。Android 13+ 可在窗口获焦时注册 `OnBackInvokedCallback`（可选） |
| 关闭语义 | **关闭 = 隐藏窗口 + 停服务，不停止生成**（生成由 ChatService 跑，回主界面可见）。「关闭并停止生成」放在长按菜单里 |
| 旋转/折叠屏 | `onConfigurationChanged` 重新 clamp，保持相对位置与尺寸比例，越界拉回屏幕内 |

## 6. 权限与授权（本设计的前置修复项）

- manifest 增补：`<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />`
- 授权流程：说明页（为什么需要、会看到什么）→ `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` → 回前台复查 `canDrawOverlays` → 仍失败给 ROM 帮助条目（MIUI「后台弹出界面」、ColorOS / Flyme / EMUI 各自路径）
- **顺手修掉现有 TTS 浮窗**：同一处声明 + 同一个引导页，让 TTS 朗读的播放控制浮窗真正可用（现在是坏的）
- Android 12+ 后台启动 FGS 限制 ⇒ 只能从前台 Activity 启动浮窗服务；Android 13+ 通知权限被拒时 FGS 通知不可见、有被回收风险，需提示
- 与 `KeepAliveService` 的关系：两者都在时**合并成一条通知**，避免双前台服务通知
- **隐私**：overlay 对全屏可见，聊天内容可能被别人看到 ⇒ 提供「小窗内隐藏消息正文」（只显示「新消息」占位）开关

## 7. 与工具审批 / 复杂交互的边界

浮窗 P0 **只读展示**待审批工具（显示「需在主界面处理」+ 一键展开），不在小窗内做审批交互。

理由：审批 UI 依赖审批详情卡与队列交互，全搬进来等于做第二个 ChatPage；而「展开主界面」已有深链，成本近乎零。同理 **附件 / 相机 / 文档 / 画板 / 分享 / 压缩确认** 在浮窗内一律「点击 → 展开主界面并预填」。

## 8. 状态持久化

单独一个 DataStore（`floating_window`），**不进设置备份**（几何与屏幕/设备绑定）：

- `lastGeometry`：dp 值 + 屏宽高比例（双保险，旋转或换机后越界时能 clamp 回来）
- `lastConversationId`
- `minimized`：是否常驻气泡（默认 false，**不自动恢复窗口**——overlay 弹出必须用户知情）

## 9. 测试与验收

- **JVM 单测**：几何纯函数（clamp 越界、8 向 resize、最小尺寸、吸附、旋转恢复）≥10 例
- **仪器测试**：服务启动/关闭后窗口与 View 正确增删；无权限时不崩（对应现在 TTS 的静默降级）；权限被撤销时自动关窗
- **真机清单**（至少 2 个 ROM）：授权/拒绝/撤销、拖拽与 8 向拉伸边界、IME 弹起、旋转、深色、分屏、后台生成继续、关闭不中断生成、气泡展开
- **回归**：现有 TTS 浮窗在授权后可用；未授权时不崩

## 10. 分期与工作量

| 期 | 内容 | 估时 |
|---|---|---|
| **P0** | 权限（含修 TTS）+ 前台服务 + 窗口（拖拽/8 向拉伸/吸附/最大化最小化）+ 精简会话（文本流式、发送、停止、切换会话）+ 展开主界面 + 几何持久化 | 4–6 天 |
| **P1** | 收起气泡、双击最大化、语音输入、浮窗内审批、透明度/尺寸预设、跟随字体缩放与深浅色 | 2–3 天 |
| **P2** | 应用内浮窗（复用 pane）、PiP 速览、freeform/桌面模式适配、多窗口 | 2–4 天 |

## 11. 已知风险

1. **ROM 差异**：MIUI / ColorOS 的「后台弹出界面」是独立权限，无法统一自动化引导，只能给帮助文案。
2. **overlay 中的 IME** 各版本行为不同（尤其 Android 11 前后的全屏输入法模式），必须真机调。
3. **系统保护页面**（支付、输入密码）会强制隐藏 overlay，这是系统行为，不是 bug，需在文案里说明。
4. **Compose 在 Service 里自建 owner 是非官方路径**，升级 compose 时需要回归测试；这是本设计最大的技术债点。
5. **双前台服务通知 / 电池优化**：需要与 KeepAliveService 协调，避免用户看到两条常驻通知。
6. 自由拉伸与 `LazyColumn` 消息高度计算、热区与滚动的手势竞争，需要仔细处理（拖动 vs 滚动）。

## 12. 待拍板的决策点

1. **宿主形态**：只做系统悬浮窗（A）／同时做应用内浮窗（B）／先只做 B？——推荐 A 为主、组件按 B 可复用设计。
2. **P0 边界**：审批只读 + 附件跳主界面，可接受吗？还是浮窗内必须有审批与选图？
3. **是否连带修 TTS 浮窗权限**（它现在因缺声明而不可用）？
4. **隐私遮挡**：小窗内容要不要默认模糊/隐藏，点一下才显示？

## 关联

[design-ui-yuihub-inspired.md](design-ui-yuihub-inspired.md)（抽屉与入口）· `service/KeepAliveService.kt`（前台服务与通知模式）· `ui/components/ui/FloatingWindow.kt`（现有 FloatingX 用法）
