# UI 精简与重设计：细节核查版

**核查时间**：2026-10-19
**代码基线**：`master` @ `148278311`（1.0.28-beta.3 / 305）
**核查方式**：静态代码审查（路由表、页面 Composable、导航调用点）。**未**运行应用、未采集真机点击数据、无用户调研。

> 本文是上一版 `ui-simplification-proposal.md` 的**修正版**。上一版有若干基于猜测的结论（当时在文末列为"待确认问题"），本次已逐条落到代码上：**6 条被证伪**，见 §3。请以本文为准。

> **状态更新（2026-10-19，UI 深化批次落地后）** —— 本文是**改前基线**（`cd2cea8ba`），以下数字已过时，引用前先看这里：
> - **设置页 16 行 → 15 行**：TTS 并入「媒体与语音」页（三 Tab），颜色模式改成内联 `SegmentedPill`（不再是一次弹层）
> - **抽屉底部 5 动作 + 菜单▾ → 4 动作**（助手 / 收藏 / 统计 / 设置）；聊天历史、媒体创作、AI 翻译上移为抽屉顶部三条同级 `DrawerEntry`（搜索并入历史行尾），**已无二级菜单**
> - **小屏抽屉 `ModalNavigationDrawer` → 自研 `ChatScalingDrawer`**（`ChatPage` 状态由 `DrawerState` 化简为 `drawerOpen: Boolean`）
> - **「媒体创作」点击深度 3 → 2**：抽屉直达 `MediaCreationSessions`；旧版生图改为媒体创作页顶部兼容入口
> - **抽屉目的地仍为 10 个**（原计划“翻译外移”未做——应用内翻译只有抽屉一个入口，外移会成死路）
>
> 改造结果与验收见 [design/design-ui-yuihub-inspired.md](design/design-ui-yuihub-inspired.md) §7；提交 `538605ca7`（阶段 1）、`5e8e6f667`（阶段 2+3）。**文中代码行号同样是改前基线**。

---

## 1. 核查范围与方法

| 项 | 做法 |
|---|---|
| 路由全集 | 数 `RouteActivity.kt` 中 `sealed interface Screen` 的 `data class/object` 成员 |
| 可达性 | 全仓 grep `navigate(Screen.X)`，逐条建边，比对路由全集 |
| 每页结构 | 直接读 `@Composable fun XxxPage` 的 Scaffold 主体（LazyColumn / CardGroup / Tab 行数） |
| 入口深度 | 从 `Screen.Chat` 起按点击跳数计（不含返回） |
| 代码级缺陷 | grep 硬编码文本、未本地化字符串、孤儿路由 |

**样本量**：`ui/pages/` 132 个 Kotlin 文件；路由 61 条；导航调用点 89 处。

---

## 2. 事实清单（可直接引用）

### 2.1 导航模型

- **没有应用级底部导航栏。** 全仓无 `NavigationBar` 作为主导航（`NavigationBar` 仅用于页内双 Tab：`PromptPage.kt:172`、`WorkspaceDetailPage.kt:379`、`ImgGenPage.kt:209`、`SettingProviderDetailPage.kt:220`、`SettingSpeechPage.kt:135`、`SettingModelPage.kt:90`）。
- 导航是**抽屉 + 路由栈**，两套实现按屏宽切：
  - 小屏 `ModalNavigationDrawer`（`ChatPage.kt:321`）
  - 大屏 `PermanentNavigationDrawer`（`ChatPage.kt:286`，`isBigScreen` 分支）
- 路由 61 条，**全部可达**（无孤儿路由；`AgentRunDetail` 有深链入口 `RouteActivity.kt:269`）。

### 2.2 抽屉的真实构成（`ChatDrawer.kt`）

自上而下 3 个功能区 + 底部动作行：

| 区 | 内容 | 证据 |
|---|---|---|
| 顶部 | 备份提醒卡（条件显示） | `:217` |
| | 头像 / 昵称 / 问候语 | `:222-277` |
| 搜索区 | **搜索对话** → `MessageSearch` | `:875`（`DrawerActions`，在 `:279` 调用） |
| | **历史记录** → `History` | `:905` |
| 列表区 | 助手切换器、文件夹、会话列表、工作区文件面板切换 | `:432`、`:343`、`:168` |
| **底部动作行** | 助手 / **菜单▾** / 收藏 / 统计数据 / 设置 | `:463`、`:478`、`:523`、`:535`、`:549` |

**菜单▾ 内含 3 项**（`DropdownMenu`，`:490-520`）：
1. AI 翻译 → `Screen.Translator`（`:499`）
2. 图像生成 → `Screen.ImageGen`（`:507`）
3. 媒体创作 → `Screen.MediaCreationSessions`（`:517`）

→ 抽屉里共 **10 个跳转目的地**（Assistant、MessageSearch、History、Translator、ImageGen、MediaCreationSessions、Favorite、Stats、Setting、Backup）+ 会话列表本身。

### 2.3 设置页的真实构成（`SettingPage.kt`）

4 个 `CardGroup`，**16 行**（含 1 个内联控件）：

| 组 | 行 | 行数 |
|---|---|---|
| 通用设置 | 颜色模式（内联 Select）` :147`、偏好设置 `:151`、助手 `:157`、**扩展** `:163` | 4 |
| 模型与服务 | 默认模型 `:177`、提供商 `:183`、搜索服务 `:189`、TTS `:195`、媒体 `:201`、MCP `:207`、知识库 `:213`、Web 服务器 `:219` | 8 |
| 数据设置 | 备份 `:236`、聊天存储 `:242` | 2 |
| 关于 | 关于 `:269`、请求日志 `:275` | 2 |

- `SettingMediaPage` 确实是 **2 个 Tab**（`SecondaryTabRow`，`:162`）：Tab0 生成提供商、Tab1 素材存储（`MediaUploadStorageTab`，`:453`）。
- `SettingPreferencesPage` 是 **5 行**：主题 / 通知 / 通用 / UI / 网络（`:63-87`）。

### 2.4 助手详情页的真实形态（重要修正）

`AssistantDetailPage.kt` **不是 Tab 页**，是**单个 `LazyColumn` + 一个 `CardGroup` + 9 行同级入口**（`:95-161`）：

基础信息 `:99` / 提示词 `:106` / 扩展注入 `:113` / 记忆 `:120` / 请求配置 `:127` / MCP `:134` / 本地工具 `:141` / 生图 `:148` / 知识库 `:155`

每行都是 `headline + supporting + →`，**视觉权重完全相同**，没有分组。

### 2.5 扩展页的真实形态

`ExtensionsPage.kt` 是**6 项扁平列表**（`:62` QuickMessages / `:68` Prompts / `:74` Skills / `:80` Workspaces / `:86` AgentRuns / `:92` SubagentPresets）。

→ 结构本身已经合理，**不需要改造成 Tab**（上一版此建议作废）。

### 2.6 生图确实存在两套并行实现

| | 旧路径 | 新路径 |
|---|---|---|
| 页面 | `ImgGenPage` | `MediaCreationPage` |
| 配置来源 | 聊天 provider 的 `ModelType.IMAGE` 模型（`ImgGenPage.kt:404`） | `settings.mediaGenerationProviders`（`SettingMediaPage` Tab0） |
| 设置入口 | 设置 → 提供商 → 提供商详情 →「模型」Tab（该页共 3 Tab：配置/模型/网络代理，`SettingProviderDetailPage.kt:223/233/243`） | 设置 → 媒体（Tab0，`SettingMediaPage.kt:162`） |
| Drawer 入口 | 菜单▾ → 图像生成 | 菜单▾ → 媒体创作 |

→ **同一能力两处配置、两个 UI、抽屉里两个并列入口**。这是全应用最严重的一处概念重复。

### 2.7 画板与附件面板（修正"没有入口"的说法）

- 画板**有入口**：对话输入框附件面板 `SketchButton`（`FilesPicker.kt:162`）+ 媒体创作 composer（`MediaCreationComposer.kt:352`）。是 `SketchDialog` 弹窗，非独立页。
- 附件面板 `FilesPicker` 单屏内容：
  - 按钮行 `:158-170`：拍照 / 相册 / **画板** /（仅 Google provider）视频 / 音频 / 文件 → 最多 **6 个**
  - 其下列表项：工作区 `:178`、MCP `:200`、扩展注入 `:219`、压缩上下文 `:249`、提示词预览 `:280`、工作区 CWD `:307`
  - → 单面板最密时 **11 个入口**，且"压缩上下文""提示词预览"混在"附件"语义里。

### 2.8 隐蔽入口与代码级缺陷

| 现象 | 证据 |
|---|---|
| 调试页只能**长按"关于"**进入 | `SettingAboutPage.kt:136` |
| 统计页抽屉标签**硬编码中文**「统计数据」 | `ChatDrawer.kt:537`、`:540` |
| 主题子页需 3 跳：设置 → 偏好 → 主题 → 设置主题 | `SettingPage.kt:151` → `SettingPreferencesPage.kt:63` → `SettingPreferencesThemePage.kt:83` |
| 全局硬编码中文 `Text("…")` 仅 51 处（其中 `MemoryRecoveryDialog` 15、`DebugPage` 5） | grep `Text\("[^"]*[\u4e00-\u9fff]` |

→ **本地化整体健康**，i18n 不是主要复杂度来源，只有零星遗漏。

---

## 3. 上一版结论的修正（逐条）

| # | 上一版说法 | 核查结果 | 证据 |
|---|---|---|---|
| 1 | 助手配置"8 个子页面需要左右滑动或点击 Tab 切换" | ❌ 错。是单页扁平 9 行列表，无 Tab、无滑动 | `AssistantDetailPage.kt:95-161` |
| 2 | 设置页"11 个一级入口" | ❌ 错。是 16 行 / 4 组 | `SettingPage.kt` 全量 |
| 3 | "Extensions 需用 Tab 整合" | ❌ 不需要。已是 6 项扁平列表 | `ExtensionsPage.kt:62-96` |
| 4 | "画板没有独立入口，新用户不知道存在" | ❌ 错。输入框附件面板 + 媒体创作 composer 均有入口 | `FilesPicker.kt:162`、`MediaCreationComposer.kt:352` |
| 5 | "子代理没有在主导航出现" | ⚠️ 半错。有入口，但深 2 层（设置 → 扩展 → 运行记录） | `SettingPage.kt:163` → `ExtensionsPage.kt:86` |
| 6 | "SettingTheme 独立入口，为什么不在设置内" | ❌ 错。它只在偏好→主题页内，深度 3 | `SettingPreferencesThemePage.kt:83` |
| 7 | "主导航可能是底部 5 Tab" | ❌ 错。无底部导航 | `ChatPage.kt:286/321` |
| 8 | "设置页分组不清晰，模型/提供商概念重叠" | ✅ 成立 | `SettingPage.kt:177` vs `:183` |
| 9 | "生图功能 3 个入口重复" | ✅ 成立（且是 2 套后端 + 2 套配置） | §2.6 |

**方法论教训**：上一版把"列表页多行入口"误判为"Tab 切换"，把"抽屉里的菜单"误判为"底部导航"。这类判断必须读 `Scaffold` 主体，不能从路由表推断。

---

## 4. 量化指标（可复核）

| 指标 | 实测值 | 复核方式 |
|---|---|---|
| 路由（Screen）数 | **61** | `grep -c "^    data \(class\|object\) \w+" RouteActivity.kt` |
| 导航调用点 | **89** | `grep -c "navigate(Screen\." -r app/src/main` |
| 设置页一级行数 | **16**（4 组） | `SettingPage.kt` |
| 抽屉跳转目的地 | **10** | §2.2 |
| 抽屉底部动作 | **5** + 菜单内 3 | `ChatDrawer.kt:463-557` |
| 助手详情同级入口 | **9** | `AssistantDetailPage.kt` |
| 扩展页入口 | **6** | `ExtensionsPage.kt` |
| 附件面板最密入口 | **11** | `FilesPicker.kt` |
| 生图后端套数 | **2** | §2.6 |
| 最大点击深度（到功能） | **4**（子代理运行记录）/ 4+长按（调试页） | §2.8 |

**从对话页出发的点击深度实测**：

| 目标 | 路径 | 跳数 |
|---|---|---|
| 设置 | 抽屉 → 设置 | 2 |
| 媒体创作 | 抽屉 → 菜单▾ → 媒体创作 | 3 |
| 改媒体生成提供商 | 抽屉 → 设置 → 媒体 → Tab | 3 + Tab |
| 改生图（旧）模型 | 抽屉 → 设置 → 提供商 → 提供商详情 → Tab | 4 + Tab |
| 子代理运行记录 | 抽屉 → 设置 → 扩展 → 运行记录 | 4 |
| 主题子项 | 抽屉 → 设置 → 偏好 → 主题 → 设置主题 | 5 |
| 调试页 | 抽屉 → 设置 → 关于 → **长按** | 4 + 长按 |

---

## 5. 仍然成立的复杂度问题（按证据强度排序）

### 5.1 ⭐⭐⭐ 生图双栈：两套配置、两个入口（最高优先）

- 用户要在**两个不同设置页**配置同一件事，且两处术语不同（"图像生成" vs "媒体"）。
- 抽屉里两个并列菜单项，用户无法从字面判断该点哪个。
- 影响：配置失败率高、支持成本高。这已不是"设计偏好"问题，是功能正确性问题。
- **改法**（小步、可回滚）：
  1. `SettingProviderDetailPage` 的生图模型配置标注"仅旧版图像生成"；
  2. Drawer 菜单把两项合并为一项「创作」，进入后由 `MediaCreationSessionsPage` 主导，旧 `ImgGen` 作为页内 Tab 或弃用；
  3. 中期：把 `ModelType.IMAGE` 迁移/桥接到 `mediaGenerationProviders`，彻底删掉一套。

### 5.2 ⭐⭐⭐ 设置页 16 行 × 概念重叠

- "默认模型"（`:177`）与"提供商"（`:183`）相邻且语义纠缠：默认模型的选择列表本来就来自提供商。
- 服务组 8 行里，"媒体""TTS""搜索"其实是同类（外部能力服务），"MCP""Web 服务器"是另一类（本机/协议服务），"知识库"是数据——分组按实现而非按用户心智。
- 补充事实：`SettingModelPage` 本身是 2 个 Tab（模型 / 提示词，`SettingModelPage.kt:93-104`），且只处理 `ModelType.CHAT`（`:452`）。所以"默认模型"其实是**跨提供商的默认对话模型选择**——与"编辑某个提供商"是不同任务，**不建议硬合并两页**（硬合并会让"选默认"变成"先进某个提供商"）。
- **改法**：
  1. 改名为「默认对话模型」，与「提供商」不再字面撞车；并在提供商页顶部加一行「设为默认对话模型」快捷入口（反向链接，不合并页面）；
  2. 服务组拆成两小组：**AI 能力**（提供商、媒体、搜索、TTS、知识库）/ **连接与协议**（MCP、Web 服务器）；
  3. 加设置页**搜索框**（16 行跨 4 组，收益明确，成本约 1 天）。

### 5.3 ⭐⭐ 助手详情 9 行同权重

- 9 个平级行没有分组、没有主次。新手要靠读 9 条 supporting 文本猜"记忆""扩展注入"是什么。
- **改法**（不改路由，只改这一个 LazyColumn）：
  - 拆成 3 个 `CardGroup`：**身份与对话**（基础/提示词/请求）、**能力**（MCP/本地工具/知识库/生图）、**进阶**（扩展注入/记忆）
  - 进阶组默认折叠（`AnimatedVisibility`），保留全部 9 个入口不变
  - 收益：首屏从 9 行降到 7 行且语义成组；风险接近 0（纯布局）

### 5.4 ⭐⭐ 附件面板语义混杂

- "压缩上下文""提示词预览"是**对话操作**，被放进了**附件**面板（`FilesPicker.kt:249/280`），与"拍照/相册/文件"同权。
- **改法**：这两项移到输入框区域的长按菜单或 overflow（⋮）；附件面板只留真正的附件来源。入口数 11 → 9。

### 5.5 ⭐ 抽屉底部 5 动作 + 菜单内 3 项

- 「菜单▾」是二级菜单套在抽屉里——抽屉本身已是二级导航。
- **改法**：把 3 项提到动作行（5 → 8 个动作会挤），或**反向简化**：把「收藏」「统计」收进菜单▾，底部只留 助手 / 创作 / 设置 三个高频动作。
- 需先有使用频率数据，见 §6。

### 5.6 ⭐ 零星缺陷（一次性清理）

- `ChatDrawer.kt:537/540` 硬编码「统计数据」→ 走 `stringResource`。
- 决定 `Stats` 去留：它的入口是硬编码中文，说明是实验性遗留。
- `Debug` 长按入口（`SettingAboutPage.kt:136`）建议保留但加可发现性（版本号连点 7 次是该场景的通用约定）或明确写入文档。

---

## 6. 代码规模撑不起的判断（必须靠数据，不要拍板）

以下问题静态代码**答不了**，agent 不应给出结论：

1. **哪个入口真正常用**——决定抽屉/设置排序的唯一可靠依据是埋点。本仓已有 `Logging` 设施（`common/.../Logging.kt`，含落盘开关与脱敏），加一层页面进入事件成本很低。
2. **用户是否真的被 16 行设置页困扰**——需要看设置页的平均停留、回溯率、以及从设置页返回对话页的比例。
3. **是否该改主导航模型**（抽屉 vs 底部 Tab）——这是全局 IA 决策，需 A/B 或至少灰度；本仓当前无实验框架，**不建议在没有数据前改动**。
4. **生图双栈里哪套有真实调用**——查 provider 配置与 `mediaGenerationProviders` 的实际填充率即可，属于可测项，建议先测再决定合并方向。

---

## 7. 建议的执行顺序（重排后）

| 优先级 | 事项 | 改动面 | 风险 | 预估 |
|---|---|---|---|---|
| **P0-1** | 生图栈归一：抽屉菜单合并为一个「创作」入口 + 旧路径加"旧版"标注 | `ChatDrawer.kt` + 1 处字符串 | 低 | 0.5 天 |
| **P0-2** | 修 `统计数据` 硬编码；决定 Stats 去留 | `ChatDrawer.kt:537/540` | 极低 | 0.5 小时 |
| **P0-3** | 设置页「默认模型」改名「默认对话模型」+ 提供商页加重定向行（不合并页面） | `SettingPage.kt` + `SettingProviderPage.kt` + 字符串 | 低 | 0.5 天 |
| **P0-4** | 埋点：页面进入事件 + 设置页/抽屉入口点击 | `Logging` 复用 | 低 | 1 天 |
| **P1-1** | 设置页搜索框 | 新组件 + `SettingPage.kt` | 低 | 1 天 |
| **P1-2** | 助手详情 9 行分 3 组 + 进阶折叠 | 仅 `AssistantDetailPage.kt` | 低 | 0.5 天 |
| **P1-3** | 附件面板去除对话操作（11 → 9 入口） | `FilesPicker.kt` | 中（有多处测试依赖语义） | 1 天 |
| **P1-4** | 设置服务组拆"AI 能力 / 连接与协议" | `SettingPage.kt` | 低 | 0.5 天 |
| **P2** | 主导航模型评估（待 §6 数据） | 全局 | 高 | 需立项 |

**明确不做**：
- 不改 Extensions 页（已合理）。
- 不把助手详情改成 Tab（不是 Tab，改成折叠组即可）。
- 不在无数据前提下动主导航结构。
- 不为了"看起来简洁"删除功能入口——本仓面向进阶用户，MCP/工作区/子代理是卖点。

---

## 8. 附录：证据索引

```
RouteActivity.kt:732          sealed interface Screen（61 条路由）
RouteActivity.kt:269          AgentRunDetail 深链入口
ChatPage.kt:286               PermanentNavigationDrawer（大屏）
ChatPage.kt:321               ModalNavigationDrawer（小屏；✅ 已改 ChatScalingDrawer）
ChatDrawer.kt:217             备份提醒卡
ChatDrawer.kt:279             DrawerActions 调用点（✅ 现为 历史/媒体创作/翻译 三条目）
ChatDrawer.kt:432             AssistantPicker
ChatDrawer.kt:463-557         底部动作行：助手/菜单▾/收藏/统计/设置（✅ 现为 4 动作，无菜单▾）
ChatDrawer.kt:490-520         菜单▾ 的 3 项（翻译/图像生成/媒体创作）（✅ 已删除）
ChatDrawer.kt:537,540         硬编码「统计数据」
ChatDrawer.kt:875/905         搜索对话 / 历史记录（✅ 搜索已并入历史行尾）
SettingProviderDetailPage.kt:220 3 个 Tab（配置/模型/网络代理）
SettingProviderDetailPage.kt:223/233/243  Tab 定义
SettingModelPage.kt:93-104     默认模型页 2 Tab（模型/提示词）
SettingPage.kt:112-282        4 组 16 行（✅ 现为 15 行）
SettingPage.kt:163            设置 → 扩展
SettingMediaPage.kt:162       2 个 Tab
SettingMediaPage.kt:453       MediaUploadStorageTab
SettingPreferencesPage.kt:63-87   5 行事项目
SettingPreferencesThemePage.kt:83 主题 → 设置主题（第 3 跳）
SettingAboutPage.kt:136       长按进调试
AssistantDetailPage.kt:95-161     9 行扁平 CardGroup
ExtensionsPage.kt:62-96       6 项扁平列表
FilesPicker.kt:158-170        按钮行（含画板）
FilesPicker.kt:249/280        压缩上下文 / 提示词预览（语义错位）
FilesPicker.kt:162            SketchButton
MediaCreationComposer.kt:352  媒体创作内画板
ImgGenPage.kt:404             ModelType.IMAGE（旧生图栈）
```

### 复核命令

```bash
# 路由数
grep -c '^    data \(class\|object\) \w+' app/src/main/java/app/androidrik/rikkahub/RouteActivity.kt
# 导航调用点
grep -rc 'navigate(Screen\.' app/src/main/java/app/androidrik/rikkahub | awk -F: '{s+=$2} END {print s}'
# 硬编码中文（应持续压低）
grep -rEc 'Text\("[^"]*[一-龥]' app/src/main/java/app/androidrik/rikkahub/ui
```
