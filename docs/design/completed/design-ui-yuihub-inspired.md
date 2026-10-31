# UI 重设计：以 Yuihub 为参考，并改进其缺陷

**参考对象**：`F:\build\projects\Yuihub`（`me.yui.yuihub`，RikkaHub 个人二改版，519 个 Kotlin 文件）
**落地对象**：本仓 `F:\build\projects\RikkaHub-Neomix`（`app.androidrik.rikkahub`，1.0.28-beta.5 / 307）
**核查时间**：2026-10-19
**方法**：两个仓库的静态代码对照。**未**编译运行 Yuihub，未做真机 A/B，未采集任何使用数据。

> 本文承接 [ui-simplification-proposal.md](ui-simplification-proposal.md)（已实测的 Neomix 复杂度基线）。那份文档给出"Neomix 哪里复杂"；本文回答"参考 Yuihub 该怎么改，以及 Yuihub 本身该怎么改"。

---

## 1. 可比性边界（先说清楚）

两者同源于 RikkaHub，**导航骨架相同**：抽屉（小屏）/ `PermanentNavigationDrawer`（大屏）+ Navigation3 类型化路由，都没有应用级底部导航栏。Neomix 的小屏抽屉原为 Material `ModalNavigationDrawer`，2026-10-19 起替换为自研 `ChatScalingDrawer`（见 §6 阶段 1）。

> 下表「Neomix」一列为 **2026-10-19 改造前**基线；括号内为改造后状态，逐项结果见 §7。

| 维度 | Yuihub | Neomix（改前 → 改后） |
|---|---|---|
| 路由规模 | 页面目录 57 个顶层文件 | 路由 **61** 条，页面 132 个文件 |
| 设置页 | **4 组 10 行** | **4 组 16 行 → 15 行** |
| 功能重心 | 对话/角色扮演（Lorebook、Mode Injection、定时任务、子代理角色） | 媒体创作、画板、知识库、工作区、MCP、日志 |
| 抽屉实现 | **自定义缩放式** `ChatScalingDrawer` | Material `ModalNavigationDrawer` → **自研 `ChatScalingDrawer`** |
| 抽屉底部 | **4 个等权动作栈，无二级菜单** | 5 个动作 + **「菜单▾」二级下拉** → **4 个动作（助手/收藏/统计/设置），无二级菜单**；聊天历史/媒体创作/翻译上移为顶部三条同级条目 |

**边界**：Yuihub 是"一个人自己用"的精简二改（README 明说无维护承诺），它的"简洁"部分来自**功能更少**（没有媒体创作、知识库、TTS、Web 服务器、聊天存储、请求日志）。所以不能直接照搬它的设置页行数，只能借鉴**组织方式**。反过来，Neomix 的功能广度决定了抽屉必须承载更多入口，这正是要改进的地方。

---

## 2. Yuihub 的设计决策（带证据）

### 2.1 缩放式抽屉（最有价值的借鉴）

`ChatScalingDrawer`（`ChatDrawer.kt:1729-1799`）：抽屉滑入时，**主页右移、以左边缘为轴缩小、加圆角与阴影**，背后露出与抽屉同色的底色。全屏可拖，松手按位移方向吸附。

设计 token 集中在一处（`ChatDrawer.kt:1693-1717`）：

| token | 值 | 作用 |
|---|---|---|
| `FRACTION` | 0.8f | 抽屉占屏宽比例 |
| `MAX_WIDTH` | 360.dp | 抽屉最宽 |
| `CONTENT_SCALE_END` | 0.95f | 主页缩到 95% |
| `CORNER_RADIUS_END` | 22.dp | 主页圆角 |
| `SHADOW_END` | 20.dp | 主页阴影 |
| `SCRIM_ALPHA` | 0.12f | 遮罩透明度 |
| `DRAG_SPAN_FRACTION` | 0.6f | 拖满全程所需位移 |
| `SETTLE_THRESHOLD` | 0.25f | 吸附阈值 |

组成件：`DrawerSlidePanel`（`:1803`，未展开时整体左移出屏）、`ScaledHomePanel`（`:1820`，`transformOrigin = (0f, 0.5f)` 以左缘为轴缩放、`clip=true`、tappable 关闭）。

**为什么值得学**：它把"抽屉打开了"这件事表达成空间关系（主页退后一层），而不是一层灰遮罩。对高频打开的会话列表，这比标准 M3 抽屉更不打断上下文。

### 2.2 底部动作：等权动作栈，无二级菜单

`DrawerActionBar`（`:787-837`）+ `DrawerActionStack`（`:841-868`）：一个 `surfaceBright` + `1dp outlineVariant@50%` + `shapes.large` 的**单一容器**，里面 4 个等宽"图标在上、labelSmall 在下"的堆：图像生成 / 收藏 / 统计 / 设置。KDoc 写得很直白：

> "「图标在上、小字在下」的堆：**靠小字自解释，不再依赖长按提示**"

这正是 Neomix 的对症解药：Neomix 改造前底部是 5 个 `DrawerAction` + 一个「Menu▾」`DropdownMenu`（`ChatDrawer.kt:487-521`），三个功能（翻译/图像生成/媒体创作）藏在二级菜单里，**用户必须先点一次才知道里面有什么**（✅ 已整改：菜单▾ 删除，翻译/媒体创作上移为顶部同级条目，见 §6）。

### 2.3 助手身份卡

`DrawerAssistantCard`（`:726-784`）：`surfaceBright` 卡片显示**当前助手**头像 + 名字 + 右侧切换按钮（打开 `AssistantPickerSheet`），整卡点击进助手设置。

对比 Neomix 抽屉顶部（`ChatDrawer.kt:222-277`）放的是**用户**头像 + 昵称 + 问候语。聊天场景里"当前在跟哪个助手说话"比"我是谁"更高频——Neomix 把这个信息藏在了下方 `AssistantPicker`（`:432`）。

### 2.4 SegmentedPill（设置页内联控件）

设置页颜色模式用胶囊分段（`SettingPage.kt:129-141`，宽 186dp）而非下拉 Select。Neomix 原先用 `Select`（`SettingPage.kt:126-145`，宽 150dp），多一次弹层交互；**2026-10-19 已改为自研 `SegmentedPill`（162dp，内边距收紧到水平 2dp / 垂直 8dp，外边距只留垂直 2dp，`ui/components/ui/SegmentedPill.kt`）**，控件本身带单选语义与 48dp 触控。宽度取 162dp 是实测下限：labelMedium 下「跟随系统」4 字排版宽 49.5dp，减掉内边距后每项内容区需 ≥174px（49.7dp），162dp 给到 50dp；同时给 `ListItem` 标题列留出 79dp，避免「颜色模式」折行（原先 186dp 时标题列只剩 55dp，会折成「颜色模 / 式」）。

### 2.5 其它

- 抽屉内子代理树（`:198`：数量/展开/子会话/运行中状态）
- 设置页 10 行（`SettingPage.kt:122-232`）：通用 4 / 模型服务 4 / 数据 2 / 关于 1；MCP 收进 Extensions（`ExtensionsPage.kt:79`）
- `ChatPageContent` 接收 `drawerOpen: Boolean` + `onOpenDrawer`（`ChatPage.kt:241-242`），而非 Material 的 `DrawerState`

---

## 3. 采纳判定

| # | Yuihub 设计 | 判定 | 理由 |
|---|---|---|---|
| 1 | 缩放式抽屉 | **采纳** | 直接改善"高频打开会话列表"的上下文连续性；Neomix 无同类组件，纯增量 |
| 2 | 动作栈（无二级菜单） | **采纳 + 扩展** | 直接消除 Neomix 的二级菜单问题；但 Neomix 入口更多，需从 4 槽扩到 5 槽 |
| 3 | 助手身份卡 | **采纳 + 改造** | 保留 Neomix 的用户可编辑入口，做双身份 |
| 4 | `SegmentedPill` | **采纳** | 少一次弹层；需处理 3 项以上选项（Neomix 有些开关是 2 项） |
| 5 | 抽屉内子代理树 | **暂缓** | 会增加列表结构与重组成本；Neomix 已有 AgentRuns 页，需先有"抽屉里找子代理"的真实诉求 |
| 6 | 设置页 10 行 | **部分采纳** | 不能直接减到 10（功能更多），但可 16 → 12，组织方式可借 |
| 7 | MCP 放 Extensions | **拒绝** | Neomix 的 MCP 是全局服务配置，且 Extensions 已 6 项；移过去会让"连接与协议"无处归类 |
| 8 | 「关于」行内嵌 QQ 群图标 | **拒绝** | 个人化设计，不适合产品仓 |

---

## 4. 对 Yuihub 本身的改进（这是"在此基础上改进"的核心）

### 4.1 性能缺陷：`progress.value` 在 composition 中读取

`ChatScalingDrawer` 在 composable body 里读动画值：

```kotlin
// Yuihub ChatDrawer.kt:1759
val p = progress.value
```

`p` 随后被用于 `DrawerSlidePanel` 的 `translationX`、`ScaledHomePanel` 的 `scale/corner/shadow`。**后果**：抽屉滑动的每一帧都会重组 `ChatScalingDrawer` 的作用域，而它的子内容是 `drawerContent()`（整个会话列表）与 `content()`（整个聊天页）。一帧一次整树重组，在低端机上是可感的卡顿源。

**改进（本项目采用）**：动画值只进 draw/placement 阶段，不进 composition：

- 主页缩放/圆角/阴影：在 `Modifier.graphicsLayer { }` **lambda 内**读 `progress.value`（lambda 只在 draw/placement 阶段执行，读 state 只触发重绘，不触发重组）
- 抽屉位移：`Modifier.offset { IntOffset(...) }`（lambda 版同样跳过重组）
- 遮罩：`Modifier.drawBehind { drawRect(...) }` 内读
- 唯一需要进 composition 的是"是否完全关闭"这种布尔量，用 `derivedStateOf` 收敛成阈值切换

预期：抽屉拖动期间重组次数从"每帧 × 全树"降到常数级。

### 4.2 手势冲突：全屏水平拖拽

Yuihub 在整个根 `Box` 上挂 `detectHorizontalDragGestures`（`:1771`），即**屏幕任意位置水平拖动都开抽屉**。风险：

1. 与页面内水平滚动竞争（`LazyRow`、代码块横向滚动、`HorizontalPager`）。Compose 的 Main pass 是子→父，子组件先消费则父取消，所以多数情况安全；但**空内容区域**（无子组件消费）的横向拖动会被吞成抽屉手势。
2. 与 Android 15 的预测性返回/侧滑返回手势区域重叠。

**改进**：
- **关闭态**：只在左缘 `24.dp` 区域内接受开启拖拽（接近系统返回手势的位置感，但由应用接管）；如需更宽的触发区，用 `systemGestureExclusion` 显式声明，而不是无差别全屏。
- **开启态**：全屏可拖回（此时主页已被 0.12 遮罩 + 缩放，不存在内容手势需求）。
- 拖动过程中不拦截纵向：`detectHorizontalDragGestures` 已按方向判定，但要确认 `orientationLock` 行为在长列表上不误判。

### 4.3 可发现性缺口：动作栈硬编码 4 项，且缺"创作"主入口

Yuihub 的 4 个槽是"图像生成/收藏/统计/设置"，**没有**媒体创作、没有搜索/历史（它把这些放别处或没有）。Neomix 的功能集需要至少：创作（媒体创作）、收藏、统计、设置、搜索对话。

**改进**：槽位 4 → 5，并把"图像生成"槽升级为**"创作"**——进入后统一落在 `MediaCreationSessions`，旧 `ImgGen` 收进其内部。这一步同时解决 [ui-simplification-proposal.md](ui-simplification-proposal.md) §5.1 记录的"生图双栈、两个并列入口"。

### 4.4 一致性缺口：抽屉顶部有两套视觉语言

Yuihub 抽屉里 `DrawerAssistantCard` / `DrawerActionBar` 用 `surfaceBright + 1dp border + shapes.large`，但 `FolderBar`/`DrawerPanelToggle` 与列表行没用同一套容器。→ 改进：把"卡片容器"提成一个 `DrawerSurfaceCard` 组件，抽屉内所有非列表区块统一用它，视觉节奏一致。

### 4.5 可访问性缺口

- 动作栈 `Icon(17.dp)` + `labelSmall`，点击区靠 `padding(vertical = 6.dp)`，**总高度可能 < 48dp 最小点击区**。改进：`Modifier.minimumInteractiveComponentSize()` + `semantics { role = Role.Button }`。
- 缩放抽屉未提供 TalkBack 的 dismiss 语义。改进：面板加 `semantics { paneTitle = ... }` 与自定义 dismiss action。

---

## 5. 目标设计规格（Neomix 落地版）

### 5.1 抽屉结构

```
┌─────────────────────────────────────────┐
│  [助手卡]  (头像) 助手名        (⇄)      │   ← DrawerAssistantCard，点击进助手设置
│  (小) 用户昵称 · 问候                    │   ← 用户身份条（唯一保留的"我"）
├─────────────────────────────────────────┤
│  🔍 搜索对话            🕘 历史          │   ← 紧凑双入口（原 DrawerActions）
├─────────────────────────────────────────┤
│  [全部] [工作] [研究] [＋]               │   ← FolderBar（已有，仅统一容器）
├─────────────────────────────────────────┤
│  会话 / 文件  ← 仅在绑定工作区时出现      │   ← DrawerPanelToggle（已有）
│  ─────────────────────────────────────  │
│  会话列表（可滚动，占满剩余高度）          │
│                                          │
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │  创作   收藏   统计   设置   搜索    │ │   ← DrawerActionBar：5 个等权动作栈
│ │  (🖼)   (♥)   (📊)  (⚙)   (🔍)      │ │      单一容器、图标+小字、无二级菜单
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

**关键变化**：
- 删除 `DropdownMenu`（`ChatDrawer.kt:490-520`）与「Menu▾」（`:478-521`）
- "创作"= `Screen.MediaCreationSessions`；旧 `Screen.ImageGen` 不再出现在抽屉，由创作页内提供"旧版图像生成"入口（兼容期）
- "翻译"从抽屉移出 → 归入创作页/输入框附近（低频且与"创作"不同轴）

### 5.2 设计 token（Neomix 版 `ChatDrawerSpec`）

沿用 Yuihub 的数值（已验证为一套自洽的动效参数），仅补 2 个本仓需要的：

| token | 值 | 备注 |
|---|---|---|
| `FRACTION` / `MAX_WIDTH` | 0.8f / 360.dp | 同 Yuihub |
| `CONTENT_SCALE_END` | 0.95f | 同 |
| `CORNER_RADIUS_END` | 22.dp | 同 |
| `SHADOW_END` | 20.dp | 同 |
| `SCRIM_ALPHA` | 0.12f | 同 |
| `DRAG_SPAN_FRACTION` | 0.6f | 同 |
| `SETTLE_THRESHOLD` | 0.25f | 同 |
| **`EDGE_OPEN_ZONE`** | **24.dp**（新增） | 关闭态开启手势的左缘区域 |
| **`SPEC_SNAP`** | **Spring(dampingRatio = 0.9f, stiffness = 500f)**（新增） | 吸附动画统一用 spring，避免不同设备手感差异 |

**降级**：系统「动画缩放 = 关闭」时，`CONTENT_SCALE_END` 退化为 1f（只保留位移），避免闪烁。

### 5.3 组件清单与 API

| 组件 | 来源 | 说明 |
|---|---|---|
| `ChatScalingDrawer(open, onOpenChange, drawerContent, content)` | 移植 + 改进 §4.1/§4.2 | 新文件 `ui/components/drawer/ChatScalingDrawer.kt` |
| `DrawerSurfaceCard(...)` | 新增 | 统一 `surfaceBright + 1dp border + shapes.large` |
| `DrawerActionBar(stacks)` / `DrawerActionStack(icon, label, onClick)` | 移植 + 改进 §4.3/§4.5 | 槽位由调用方给，不硬编码 |
| `DrawerAssistantCard(...)` | 移植 + 改造 §2.3 | 内含切换按钮 |
| `SegmentedPill(options, selected, onSelect)` | 移植 | 2-4 项内联分段；>4 项回退 `Select` |

### 5.4 设置页：16 → 15 行（实做值）

保留 4 组，重排语义（按"我改什么"而非"系统里有什么"）：

| 组 | 行 | 变化 |
|---|---|---|
| **常用** | 颜色模式（`SegmentedPill`）/ 偏好设置 / 助手 / 扩展 | 4 行不变，控件改内联 |
| **AI 与生成** | 默认模型 / 提供商 / 媒体与语音 / 搜索服务 / 知识库 | TTS 并入"媒体与语音"第 3 个 Tab → 原 8 行变 5 行 |
| **连接与数据** | MCP / Web 服务器 / 备份 / 聊天存储 | MCP+Web 从"模型与服务"移来 |
| **关于** | 关于 / 请求日志 | 不变 |

净变化：模型与服务组 8 → 5（TTS 合并、MCP/Web 移出），连接与数据 2 → 4。总计 **16 → 15**。

> **与初稿的差异**：初稿按"16 → 12/13"估算，但漏算了**保留全部功能就必须保留全部入口**——
> MCP、Web 服务器、备份、聊天存储各是一项独立配置，并入其它页只会把它们藏得更深（违反"简化
> 不等于隐藏"）。因此实做为 15 行：组内语义重排 + TTS 并入媒体页 + 颜色模式内联，行数只减 1，
> 但**分组认知负担**与**设置项可达深度**才是真实收益。要再降到 12 必须砍功能，不在本次范围。

---

## 6. 落地清单（Neomix 文件级）

> **实施状态（2026-10-19）**：阶段 1/2/3 已落地并通过 `:app:assembleDebug` 与真机
> （6c841f95）验证；抽屉顶部结构改为「聊天历史（行尾搜索）/ 媒体创作 / AI 翻译」三条同级
> `DrawerEntry`，底部动作行只保留助手 / 收藏 / 统计 / 设置。下表保留原始计划作对照。
>
> 落地提交：阶段 1 `538605ca7`（`ChatScalingDrawer` + `ChatPage` 状态化简 + 助手头像修复）、
> 阶段 2+3 `5e8e6f667`（抽屉顶部归一、矮屏 header、设置页整合、创作入口归一）。
> **与计划的偏差**：①设置页实做 **15 行**（非 12/13，理由见 §5.4）；②翻译**未外移**（应用内仅抽屉一个入口，外移会成死路，改为顶部同级条目，故目的地仍 10 个）。

### 阶段 1：抽屉框架（风险中，收益最大）

| 文件 | 改动 | ripple |
|---|---|---|
| **新增** `ui/components/drawer/ChatScalingDrawer.kt` | 移植 + §4.1/§4.2 改进 | 无 |
| `ui/pages/chat/ChatPage.kt` | 小屏分支 `ModalNavigationDrawer` → `ChatScalingDrawer`；`drawerState: DrawerState` → `drawerOpen: Boolean` + `onOpenDrawer: () -> Unit` | **大**：`ChatPageContent`（`:362`）、内部 `:451`、`:1109`、`:1137` 三处传参都要改；`BackHandler(drawerState.isOpen)`（`:214`、`:354`）改为 `BackHandler(drawerOpen)` |
| `ui/pages/chat/ChatDrawer.kt` | 底部 `Row(DrawerAction × 5 + 菜单▾)` → `DrawerActionBar`；顶部加助手卡 | 中 |

**必须先写的测试**（当前无抽屉测试）：
- 抽屉开/关的 Compose 测试（点遮罩关闭、返回键关闭、5 个动作各自导航到正确路由）
- 拖动语义测试：左缘拖动可开、内容区水平拖动不误开（用 `performTouchInput { swipeRight() }` 分区域）

### 阶段 2：设置页

| 文件 | 改动 |
|---|---|
| `ui/pages/setting/SettingPage.kt` | 分组重排（实做 16→15，见 §5.4）；颜色模式改 `SegmentedPill` |
| `ui/pages/setting/SettingMediaPage.kt` | 加第 3 个 Tab「语音」并入 TTS |
| `ui/components/ui/SegmentedPill.kt` | **新增** |

### 阶段 3：创作入口归一（与既有 P0-1 合流）

| 文件 | 改动 |
|---|---|
| `ui/pages/chat/ChatDrawer.kt` | "创作" → `MediaCreationSessions` |
| `ui/pages/mediacreation/MediaCreationSessionsPage.kt` | 顶部加"旧版图像生成"入口（兼容期） |
| `ui/pages/setting/SettingProviderDetailPage.kt` | 生图模型配置标注"仅旧版图像生成" |

---

## 7. 验收标准（含实做结果）

| 指标 | 改前 | 目标 | 实做 |
|---|---|---|---|
| 抽屉底部二级菜单层数 | 2（动作 + Menu▾） | **1** | **1**：Menu▾ 已删除，翻译改为顶部同级条目 |
| 抽屉跳转目的地 | 10 | 9（翻译外移） | **10**（未外移；翻译与媒体创作一起上移为「聊天历史」同级条目，避免断掉唯一入口） |
| "媒体创作"点击深度 | 3 | **2** | **2**：抽屉首屏直达 `MediaCreationSessions` |
| 生图入口并列数 | 2 | **1** | **1**：抽屉只留「媒体创作」；旧版生图收进媒体创作页顶部兼容入口 |
| 设置页行数 | 16 | 12-13 | **15**（见 §5.4 差异说明） |
| 抽屉拖动期间整树重组 | 无动画 | 避免逐帧整树重组 | 变换只在 `graphicsLayer` 读取进度；遮罩由 `derivedStateOf` 的边界布尔值控制。手动时钟测试验证动画推进时稳定内容槽不逐帧重组，不代表整个页面绝无重组。 |
| 动作栈点击区高度 | 40dp | **≥ 48dp** | **≥ 48dp**（`minimumInteractiveComponentSize`）；抽屉顶部条目行 10dp 内边距 + 18dp 图标 |
| 抽屉相关 Compose 测试 | 0 | ≥ 8 个用例 | **25**：13 个抽屉交互与动画测试 + 8 个真实入口导航测试 + 4 个矮屏列表 header 回归测试；另回归原有 4 个分段控件测试，真机共 29 项通过。 |

**其他已落地项**：
- 矮屏（高度 < 640dp，如手表/横屏手机）：抽屉顶部随会话列表一起滚动，避免固定区把列表挤没
  （吸收上游 `9ce1ae1` / #2164；`ConversationList` 新增 `header` 槽位并修正自动滚动的索引偏移）。
- 搜索入口并入「聊天历史」行尾（吸收上游 `660571449`），不再单独占一行。
- 媒体与语音设置合并为「媒体与语音」一页三 Tab（服务商 / 素材存储 / 语音），独立
  `Screen.SettingSpeech` 路由保留，`SettingSpeechPage(embedded = true)` 复用同一套 TTS/ASR 内容。

**2026-10-20 补测与 lint 修复**：
- `ChatScalingDrawerTest`：覆盖受控开关、24dp 左缘打开、内容区横拖不误开且事件到达子控件、短拖完整回弹、左拖关闭、遮罩点击、抽屉按钮、返回分发、无障碍 dismiss，以及手动时钟下稳定内容槽的重组计数。
- `ChatDrawerNavigationTest`：使用实际生产条目与 `Navigator` 校验历史、行尾搜索、媒体创作、翻译、助手、收藏、统计、设置这 8 个目的地；搜索只压入搜索路由，不连带触发历史入口。
- `ConversationListHeaderTest`：覆盖带/不带 header 的自动定位索引、大于视口的 header 可滚走，以及插入/移出 header 后保持当前会话可见。
- 修复测试暴露的左缘限制无效问题（原回调内 return 不会阻止后续拖动）；返回关闭由抽屉自身处理；header 变化时重新校验会话位置。
- 清除旧报告的 100 个 lint 错误：90 个非配置感知资源读取、3 个非可观察 Locale 读取、1 个 Activity 获取方式错误、4 个缺失翻译、2 个本机 properties 路径转义错误。本机配置不纳入版本控制；未新增 baseline 或整体禁用检查。
- 验证通过：仓库级 `./gradlew lintDebug`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`（1944 项，0 失败/跳过）；设备 `6c841f95` 运行上述 29 项仪器测试全部通过。应用 lint 报告为 0 errors、361 warnings、5 hints；本次只清除错误，不宣称警告清零。

**不做**：不在本仓引入 Yuihub 的抽屉内子代理树；不改大屏 `PermanentNavigationDrawer`（`ChatPage.kt:281` 分支保留）；不动 61 条路由结构。

---

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| `drawerState` → `drawerOpen` 的重构触及 ChatPage 主链路 | 抽屉打不开/返回键失效 | 阶段 1 单独提交 + 先补测试；`PermanentNavigationDrawer` 分支保持原样对照 |
| 自定义抽屉丢失 Material 抽屉的无障碍与预测性返回支持 | 可访问性回归 | 显式补 `paneTitle` + dismiss action；`BackHandler` 覆盖返回手势 |
| 左缘 24dp 触发区与系统返回手势重叠 | 用户滑返回时误开抽屉 | `systemGestureExclusion` 只声明该窄区；可一键退回 `ModalNavigationDrawer`（保留旧分支一个版本） |
| 设置页重排影响既有字符串/测试 | 测试失效 | 只移动 Item 位置不删 key；有 `SettingPage` 相关测试则同步更新 |

---

## 9. 证据索引

```
Yuihub ChatDrawer.kt:1693-1717   ChatDrawerSpec 9 个 token
Yuihub ChatDrawer.kt:1729-1799   ChatScalingDrawer（缩放抽屉）
Yuihub ChatDrawer.kt:1759        val p = progress.value —— 性能缺陷点
Yuihub ChatDrawer.kt:1771        全屏 detectHorizontalDragGestures —— 手势风险
Yuihub ChatDrawer.kt:1803        DrawerSlidePanel
Yuihub ChatDrawer.kt:1820        ScaledHomePanel（transformOrigin 左缘）
Yuihub ChatDrawer.kt:726-784     DrawerAssistantCard
Yuihub ChatDrawer.kt:787-868     DrawerActionBar / DrawerActionStack
Yuihub ChatDrawer.kt:871/940     FolderBar / FolderChip
Yuihub ChatPage.kt:241-242       ChatPageContent(drawerOpen, onOpenDrawer)
Yuihub ChatPage.kt:257           小屏用 ChatScalingDrawer
Yuihub ChatPage.kt:225           大屏仍用 PermanentNavigationDrawer
Yuihub SettingPage.kt:129-141    SegmentedPill（内联分段）
Yuihub SettingPage.kt:122-232    设置页 4 组 10 行
Yuihub ExtensionsPage.kt:79      MCP 归入扩展
> 下列 Neomix 行号为**改前基线**（`cd2cea8ba`），改造后行号已变化；`ChatScalingDrawer` 现位于
> `ui/components/drawer/`，`ChatPage` 的大屏/小屏分支在 `:280` / `:320` 附近，`drawerState` 传递链已不存在。

Neomix ChatDrawer.kt:490-520     菜单▾ 的 3 项（✅ 已删除，`5e8e6f667`）
Neomix ChatDrawer.kt:456-558     底部 5 动作行（✅ 已改为 4 动作）
Neomix ChatDrawer.kt:222-277     用户头像/昵称区（保持不变）
Neomix ChatDrawer.kt:966/1035    FolderBar / FolderChip（已存在；已移入抽屉 header）
Neomix ChatPage.kt:210-221       rememberDrawerState / BackHandler（✅ 已改为 drawerOpen: Boolean）
Neomix ChatPage.kt:286/321       大屏 / 小屏抽屉分支（小屏已改 ChatScalingDrawer）
Neomix ChatPage.kt:362/451/1109/1137  drawerState 传递链（✅ 已随重构消失）
Neomix SettingPage.kt:112-282    设置页 4 组 16 行（✅ 已为 15 行）
```
