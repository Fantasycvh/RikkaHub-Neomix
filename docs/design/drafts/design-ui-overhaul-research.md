# UI 改造初步调研：控件、风格与竞品（2026-11）

> 状态：**调研稿** · 2026-11-13 撰写；A+G+D/C/B/I/F 七方向已于 2026-11-14 全部落地（9df438545/2f7eaec17/7e9b623ca/6704e4258/a945e0f57/4ee3390ca/858ef57be），仅剩方向 E（Bento IA 三件）未做
> 目的：为下一轮 UI 改造提供外部情报（设计语言 / 组件库 / 竞品模式 / 视觉趋势）与本仓基线对照，产出候选方向清单供决策。
> 方法：网络调研（来源见 §8）+ 本仓代码盘点（关键结论均已核实到 file:line）。

---

## 0. 结论速览

本仓 UI 基建**已领先上游一个身位**（AppSkin 皮肤系统、气泡材质引擎、Expressive 主题均已落地），改造不应推倒重来，而应「补齐 Expressive 缺口 + 材质出聊天域 + 输入栏形态升级」。候选方向按 ROI 排序：

| # | 方向 | 感知度 | 成本 | 主要风险 |
|---|------|--------|------|----------|
| A | 激活 Expressive 排版（GoogleSansFlex 死代码 + emphasized 注释块） | 高 | 低 | 中文无 CJK 覆盖，需混排 fallback |
| B | 输入栏浮动 pill 化 + FAB menu 渐进披露 | 高 | 中 | IME 宿主规范、键盘 insets |
| C | 玻璃/材质出聊天域（抽屉/设置/列表顶栏） | 中高 | 中 | 低端机性能、AMOLED 纯黑下无意义 |
| D | 补齐 Expressive 组件（wavy progress / FAB menu / shape morph） | 中 | 低 | 1.5.0 API 转正前仍可能微调 |
| E | 设置页 Bento 化 / miuix 风分组形态 | 中 | 中 | ListItem 槽位坑、表单宿主规范 |
| F | 流式渲染动效（逐块淡入，借鉴 llm-typewriter） | 中 | 中 | 长文本性能（longtext 系列约束） |
| G | material3 1.5.0-beta02 → 1.5.0-rc01 bump | — | 低 | rc 阶段 API 变动 |
| H | 大屏/折叠屏 Expressive layout scaffold | 中 | 高 | 观察项，暂不建议本轮做 |
| I | Agentic UI 卡片化：可折叠 tool call trace + 结构化输出卡片 + 活动感知加载态（shimmer/阶段步进/计时，见 §9） | 中高 | 中 | longtext 性能约束；tool 事件映射需逐个核对 |

**不建议**：整套引入 miuix / compose-cupertino 替换 M3（与 AppSkin/M3E 体系冲突，风格应做成皮肤 tokens 而非换库）；直接替换 ChatInput 为 prompt-bar 库（IME 历史坑族，交互模式可借鉴、宿主不可轻换）。

---

## 1. 本仓基线盘点（已核实）

### 1.1 技术栈版本

- Compose BOM `2026.06.01`、material3 `1.5.0-beta02`（[gradle/libs.versions.toml:16](../../../gradle/libs.versions.toml#L16)）
- haze `2.0.1`，含 `haze-blur` / `haze-glass` 双引擎（[gradle/libs.versions.toml:53](../../../gradle/libs.versions.toml#L53)、:166-170）
- material3-adaptive（Nav3 版）`1.3.0-rc01` 已在依赖；Coil 3.5、Lucide/HugeIcons 图标

### 1.2 已有能力（改造时不可重复建设）

| 能力 | 现状 | 证据 |
|------|------|------|
| Expressive 主题 | `MaterialExpressiveTheme` + `MotionScheme.expressive()` 默认开启，皮肤可切 standard | ui/theme/AppSkinTheme.kt:34-35 |
| AppSkin 皮肤系统 | 全链路已实现：typography/shapes/spacing/motion/palette/顶栏/抽屉/卡片/终端/resources；3 内置皮肤 + 插件注册 + 试用/回滚（SkinTrialController） | data/model/AppSkin.kt、ui/theme/AppSkinRegistry.kt、Theme.kt:186 |
| 三级色源 | 皮肤 palette → 动态色（Monet 壁纸 / 用户 seed 走 TONAL_SPOT）→ 预设/自定义 themeId；角色覆写层 + AMOLED 纯黑覆盖 + gray1-10 HCT 插值 | ui/theme/Theme.kt:70-148 |
| 气泡材质引擎 | SOLID/GRADIENT/PATTERN/NOISE/GLASS + 最多 4 层图层栈（TEXTURE/SHADER），AGSL RuntimeShader + CPU 降级，纹理 zip 导入导出 | data/model/ChatAppearance.kt、BubbleLayers.kt、ui/components/message/BubbleShaderRenderer.kt |
| 已用 Expressive 组件 | ButtonGroupDefaults（MediaCreationComposer）、SplitButtonLayout（AssistantImporter）、HorizontalFloatingToolbar（ChatList/LorebookPage）、ContainedLoadingIndicator、MaterialShapes（AvatarShape/AppLogo） | 各消费点 |
| haze 玻璃 | **仅聊天域**：ChatPage 双 HazeState（blur+glass 分离）、TopBarCapsule、BubbleGlass；RecoverableVisualEffects 按 SDK 31/33 分级降级 | ui/pages/chat/ChatPage.kt:461,534、ui/components/haze/ |
| 聊天页结构 | ChatPage（宿主）/ChatList/ChatTopBar/ChatDrawer/DrawerChrome/MeshGradientBackground；**输入栏在 ui/components/ai/ChatInput.kt** | ui/pages/chat/ |
| 设置页形态 | CardGroup 是 Column 实现（天然免疫 Lazy item IME 坑）；SettingProviderPage 用自定义卡片 + 拖拽排序 | ui/components/ui/CardGroup.kt:282 |

### 1.3 明显缺口（改造的落点）

1. **排版全默认**：`Typography()` 未定制（[ui/theme/Type.kt:13-14](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/Type.kt#L13)）；emphasized 排版整段被注释（Type.kt:17-78）；`GoogleSansFlex` 可变字体族**是死代码**——全仓仅 Type.kt 注释引用，字体资源已就位却未接线。
2. **Expressive 组件未用齐**：无 `FloatingActionButtonMenu` / `ToggleFloatingActionButton`、无 VerticalFloatingToolbar、无 wavy ProgressIndicator、无 shape morphing 动画。
3. **材质局限聊天域**：设置页、抽屉、对话列表、助手详情无玻璃/材质表达，与聊天页视觉断层。
4. **版本滞后**：material3 1.5.0-rc01 已于 2026-10-21 发布（Expressive 转 stable 的候选版本），本仓仍在 beta02。

---

## 2. 设计语言动态

### 2.1 Material 3 Expressive 时间线与版本现状

| 时间 | 事件 |
|------|------|
| 2025-05（I/O 25） | M3 Expressive 发布：弹簧动效、形状 morphing、可变字体排版、大胆配色；随 Android 16 QPR1 在 Pixel 6+ 首发，Google 全家桶陆续重设计 |
| 2025-09-24 | Compose material3 **1.4.0 stable**（基线 M3，部分实验 API 转正；Expressive 仍实验） |
| 2026-05（I/O 26） | Material Android 宣布 **Compose-first**（Views 侧 MDC-Android 1.14.x 为最后主线）；发布 **Expressive layout system**（layout scaffold + spacing system，面向手机/桌面/空间设备自适应）；Expressive search 新视觉与动效 |
| 2026-10-21 | material3 **1.5.0-rc01**；官方口径「1.5.0 将把 Expressive 实验 API 转 stable」，同时带来 **Styles API**（更细粒度组件定制）与 adaptive 库的 Navigation3 集成 |

**Expressive 核心体系**（对本仓的意义）：

- **Motion physics**：`MotionScheme.expressive()`（弹跳弹簧）vs `standard()`（阻尼弹簧）——本仓已默认 expressive，但大量自定义动画仍用手写 tween/spring，可逐步统一到 MotionScheme token。
- **形状**：MaterialShapes 形状库 + 形状间 morphing（如圆形↔Cookie 形过渡），本仓已用 MaterialShapes 静态形状，未用 morph 动画。
- **排版**：Roboto Flex / Google Sans Flex 可变字体，六轴（weight/width/optical size/slant/grade/roundedness）；emphasized 字阶是 M3E 的标志性气质。Google Sans Flex 已公开免费下载（OFL）。
- **新组件清单**：ButtonGroup（连接按钮组）、SplitButton、**FloatingActionButtonMenu + ToggleFloatingActionButton**（2–6 个相关动作的展开菜单）、Horizontal/**Vertical**FloatingToolbar、ContainedLoadingIndicator、**LinearWavyProgressIndicator / CircularWavyProgressIndicator**（波浪进度）、新 Slider、Expressive search bar、toolbar 变体。

### 2.2 视觉趋势（2025–2026，多来源交叉）

1. **Liquid Glass / Soft Spatial UI**：iOS 26 液态玻璃带火全行业；2026 趋势文章普遍把「玻璃 + 纵深 + 柔和空间感」列为主流（tubik「soft spatial UI」）。Android 侧靠 AGSL shader 实现（Kyant0 Backdrop、haze-glass），本仓已有引擎与降级经验。
2. **Bento Grid**：模块化卡片栅格（Apple 产品页带火），适合设置页/仪表盘/助手详情——「一屏多信息但不乱」。2026 仍在热度上，但也有「重复设计陷阱」的批评，宜用于信息聚合页而非聊天流。
3. **Purposeful motion**：动效必须有语义（状态反馈、层级引导），反对纯装饰；且要用户可控（reduce motion / 无障碍）。与本仓「动效分级降级」思路一致。
4. **Fluid / expressive typography**：可变字体随场景调轴，标题「有个性」、正文「保可读」。
5. **Raw aesthetics / functional minimalism**：反向趋势——网格、线框、无装饰的「工程感」，在开发者工具类受众中流行（可作为一套皮肤风格）。
6. **Ethical personalization**：个性化要透明、可预览、可回滚——本仓 SkinTrialController 试用/撤销已符合该趋势，是差异化卖点。
7. **AI copilot 交互**：AI 作为「可检查、可覆盖的副驾」而非自动代理；侧边栏式 AI、生成内容可编辑可回退。与本仓 Phone Use / agent 任务的方向一致，UI 上应强化「过程可见、随时接管」。

---

## 3. 组件库调研（引入 vs 借鉴）

| 库                                        | 风格/定位                                                               | 版本（调研时）                                   | 坐标                                                                                                                                     | 评估                                                                                                                   |
| ---------------------------------------- | ------------------------------------------------------------------- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| **miuix**（compose-miuix-ui）              | 小米 HyperOS 风格全套 CMP 组件                                              | v0.9.4（2026-09-20），minSdk 24，experimental | `top.yukonga.miuix.kmp:miuix-ui` + `miuix-preference`/`miuix-blur`（progressive blur）/`miuix-glass`/`miuix-nav`（BreadcrumbBar）/squircle | **不整库引入**（与 AppSkin/M3E 冲突）。借鉴：squircle 连续曲率形状、progressive blur（渐进模糊）、preference 分组形态；可做成一套「HyperOS 风」AppSkin tokens |
| **compose-cupertino**                    | iOS 风格全套 CMP 组件                                                     | v1.6.1，Apache 2.0                         | `io.github.alexzhirkevich:cupertino`                                                                                                   | 同上，风格参考而非依赖；iOS 风皮肤在角色扮演受众中有市场                                                                                       |
| **Kyant0 AndroidLiquidGlass / Backdrop** | 液态玻璃 backdrop（折射/透镜畸变/色散），CMP                                       | 已 stable（2025-11 起）                       | `io.github.kyant0:backdrop`                                                                                                            | 效果上限高于 haze-glass，但**无高层组件**需自建；AGSL 需 Android 13+。候选：作为 BubbleGlass/TopBarCapsule 的增强后端，与现有 SDK 分级降级框架对接            |
| **haze**（已在仓）                            | blur + glass 双引擎                                                    | 2.0.1                                     | `dev.chrisbanes.haze:*`                                                                                                                | 扩展到全局（抽屉/设置顶栏）成本最低，首选                                                                                                |
| **prompt-bar**（NadeemIqbal）              | AI 聊天输入栏：自增高多行、slash 命令、@ 提及、附件 chips、send/stop 状态机、语音输入、token 计数   | 0.2.0                                     | `io.github.nadeemiqbal:prompt-bar`                                                                                                     | 与 ChatInput.kt 功能重叠度高；**不替换**（IME 宿主历史坑族），借鉴其交互模式与状态机划分                                                              |
| **llm-typewriter**（originalFactor）       | Flow\<String\> token 流式渲染：渐进 Markdown + 语法高亮 + LaTeX + 打字机/淡入       | 0.1.0                                     | `cc.ptoe:llm-typewriter`                                                                                                               | 本仓已有自研流式 Markdown 管线，替换成本高；借鉴逐块淡入动效思路                                                                                |
| **compose-markdown**（adamglin0）          | append-only 流式 Markdown 渲染器                                         | —                                         | —                                                                                                                                      | 参考实现（增量解析边界处理）                                                                                                       |
| 其他（jetc.dev #315 新库）                     | compose-capabilities / filament-kmp（3D）/ ComposePdfReader / Laygent | 新                                         | — 关注度低，本轮不评估                                                                                                                           |                                                                                                                      |

**组件参考资产**：`meticha/material-3-expressive-catalog`（Expressive 组件目录 demo app，可作为改造时的对照样例）。

---

## 4. 竞品 UI 模式与可借鉴点

| 产品 | 关键 UI 模式 | 可借鉴 | 教训/反例 |
|------|--------------|--------|-----------|
| **Gemini Android** | M3E 全面落地；2025-12 起测试**浮动 pill 输入栏**（视觉轻量、高级功能收进菜单） | 浮动输入栏 + 渐进披露；M3E 组件全家桶的落地参照 | 社区抱怨输入栏膨胀成「scroll marathon」、常用动作被藏太深——**常用键必须外露** |
| **ChatGPT** | 2025-08 composer-first 重设计（输入框为绝对核心）；2026 桌面**浮动 sidebar** 与聊天历史分离 | composer 中心化；sidebar 浮动化、与内容分层 | 频繁小改 UI 引发大量用户抱怨——**布局稳定性本身是体验**；本仓改造应一次到位 + 皮肤可回滚 |
| **Claude** | 暖色极简、纸质感、衬线标题，「安静的高级感」 | 助手个性可通过皮肤/字体表达（本仓 AppSkin 天然支持） | — |
| **Cherry Studio** | 亮暗主题 + **透明窗口**、浮动侧边栏、**一问多答对比**、Trace 调用链可视化、主题/布局/快捷键高度可定制 | 多模型对比 UI（本仓多 provider 已有数据基础）；工具调用 trace 可视化（本仓 tool timing 已有数据） | 桌面端模式，移动端需简化 |
| **LobeChat** | 精致亮暗主题、移动端垂直流式 + 输入框固定底部 | 主题精致度基准；移动适配的保守正确解 | — |
| **SillyTavern** | 主题 JSON/CSS **社区分享生态**；USER/CHAR 气泡独立设置（颜色/透明度/渐变/圆角/间距/左右对齐）；背景图；MVU 前端美化 | 本仓 ChatAppearance 已覆盖大半材质能力；差距在**气泡几何自由度**（宽度/对齐/边距）与**社区主题生态**（皮肤 zip 导入导出已有，可进一步做分享格式） | 来历不明主题可注入恶意脚本——皮肤包需沙箱/校验（本仓插件体系已有边界） |
| **上游 RikkaHub** | Material You + 动态主题 + 深色模式 | 本仓已全面超越（皮肤/材质/试用回滚），无需回看 | — |

**聊天界面解剖（setproduct 2026 指南）**：composer、流式状态机（queued/thinking/streaming/error）、逐消息动作是三大件；反模式 = 假打字延迟、笼统报错、无停止按钮。本仓状态机已齐，改造重点是**状态的视觉表达**（wavy progress、thinking 动效）而非补状态。

---

## 5. 候选方向详述

### A. 激活 Expressive 排版（低成本高感知，建议首选）

- **落点**：解注释 [Type.kt:17-78](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/Type.kt#L17) 的 emphasized 字阶，接线 GoogleSansFlex（字体资源已在仓）；或评估 Roboto Flex 替代。
- **收益**：标题/按钮/数字立即获得 M3E 标志性气质，全局生效，零布局改动。
- **风险**：GoogleSansFlex **无 CJK 覆盖**——中文仍 fallback 系统字体，需验证中英混排基线/行高一致；可变字体轴在 Android O+ 可用（本仓 minSdk 满足）。emphasized 字阶偏大偏圆，需真机核对设置页/聊天页标题不溢出。
- **决策点**：是否做成 AppSkin typography token 的一部分（皮肤可切换「默认/Expressive」），而非全局硬切。

### B. 输入栏浮动 pill 化 + FAB menu（中成本高感知）

- **落点**：[ChatInput.kt](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ai/ChatInput.kt) 改为浮动胶囊形态（与 ChatTopBar 的 TopBarCapsule 呼应）；附件/工具/模型切换等低频动作收进 `FloatingActionButtonMenu`（2–6 动作），发送/停止/语音保持外露。
- **收益**：聊天页视觉重心上移、内容区更大；与 Gemini/ChatGPT 的 composer-first 趋势对齐。
- **风险**：**IME 宿主四条 + 写穿回灌规范**（AGENTS.md）——ChatInput 宿主拓扑不得破坏，改造后必须跑 `python scripts/audit_ime_trap.py` + 真机中文 IME 注入测试；浮动形态与键盘 insets 协调需真机验证；Gemini 的教训是常用动作不能藏。
- **决策点**：pill 化是否作为聊天外观预设之一（glass/compact 已有先例），允许回退经典形态。

### C. 玻璃/材质出聊天域（中成本）

- **落点**：复用 `ui/components/haze/` 引擎与 RecoverableVisualEffects 分级降级，扩展到抽屉顶栏（DrawerChrome）、设置页顶栏、对话列表页顶栏；评估 Kyant0 Backdrop 作为 glass 增强后端。
- **收益**：消除「聊天页精致、其他页朴素」的视觉断层。
- **风险**：低端机模糊性能（已有 SDK 31/33 分级框架可复用）；**AMOLED 纯黑下玻璃无意义**，需按主题自动禁用；与 AppSkin palette 的叠加顺序要进皮肤契约。

### D. 补齐 Expressive 组件（低成本）

- wavy ProgressIndicator → 生成中/加载态；shape morphing → 发送↔停止按钮形态过渡；VerticalFloatingToolbar → 聊天页侧缘快捷动作（可选）。
- 风险：1.5.0 转正前 API 可能微调，集中在少数文件封装以便跟进。

### E. 设置页 Bento 化 / miuix 风分组（中成本）

- CardGroup（Column 实现）基础上引入 squircle 形状 token + 分组间距节奏；信息聚合页（助手详情、用量统计）可试 Bento 栅格。
- 风险：ListItem supportingContent 单子节点坑（AGENTS.md）；长表单宿主规范不变。

### F. 流式渲染动效（中成本）

- 借鉴 llm-typewriter 的逐块淡入（非逐字，避免「假打字」反模式）；思维链展开用 MotionScheme 弹簧。
- 风险：longtext 系列设计稿的性能约束（大原子/静态 lazy），动效不得破坏增量渲染管线。

### G. 依赖 bump（低成本，先行）

- material3 `1.5.0-beta02` → `1.5.0-rc01`：Expressive 转 stable 前最后验证窗口；跑全量单测 + audit + 真机冒烟。

### H. 大屏 adaptive（观察项）

- I/O 26 的 Expressive layout scaffold / spacing system 值得跟踪；本仓 material3-adaptive 已在依赖，但本轮不建议投入。

---

## 6. 硬约束（改造时不可违反）

1. **IME 宿主规范**（AGENTS.md 四条 + 2026-11-12 写穿回灌定案）：输入栏/表单改造保持常驻稳定宿主、本地权威 + 去抖单向写；回归 `python scripts/audit_ime_trap.py`，新增违规禁止加豁免。
2. **ListItem supportingContent 坑**：多块内容必须 Column 包一层。
3. **AGSL/RenderEffect 分级**：SDK 31/33 降级框架已有，新材质必须接入而非另起。
4. **皮肤契约**：新视觉 tokens（字体/形状/材质）必须进 AppSkin 体系，禁止绕过皮肤硬编码；保留试用/回滚。
5. **长文本性能**：longtext 系列稿的增量渲染约束优先于动效。
6. **布局稳定性**：竞品教训——改造一次到位，形态切换走皮肤/预设而非反复改默认布局。
7. **中文排版**：GoogleSansFlex 无 CJK，emphasized 只影响拉丁/数字，混排需真机验证。

---

## 7. 建议下一步

1. 从 §0 表中选定方向组合（建议 A + G 先行，B/C 二选一跟进）。
2. 出正式设计稿：tokens 契约变更、分阶段计划、真机验证矩阵（含中文 IME 注入、AMOLED、低端机降级）。
3. 实施前 bump material3 rc01 并跑全量单测 + audit 基线。

---

## 8. 参考来源

**设计语言 / M3 Expressive**
- [Start building with Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)（官方发布）
- [What's new at Google I/O 2026](https://m3.material.io/blog/whats-new-at-io26)（Expressive layout system / search）
- [Material Android is Compose-first](https://m3.material.io/blog/material-is-compose-first)（1.5.0 转 stable 口径）
- [Compose Material 3 发布页](https://developer.android.com/jetpack/androidx/releases/compose-material3)（1.4.0 stable / 1.5.0-rc01，2026-10-21）
- [Material 3 Expressive deep dive — Android Authority](https://www.androidauthority.com/google-material-3-expressive-features-changes-availability-supported-devices-3556392/)
- [Material 3 Just Got a Serious Upgrade — Medium](https://medium.com/@androidlab/material-3-just-got-a-serious-upgrade-67b284b0e7ab)（MotionScheme 预设）
- [FloatingActionButtonMenu API](https://composables.com/jetpack-compose/androidx.compose.material3/material3/components/FloatingActionButtonMenu/api)、[Joe Birch: FAB Menu](https://joebirch.co/android/exploring-material-3-for-compose-floating-action-button-menu/)
- [Unlocking Expressive UI（@ExperimentalMaterial3ExpressiveApi）](https://levelup.gitconnected.com/unlocking-expressive-ui-in-jetpack-compose-with-experimentalmaterial3expressiveapi-9276a1a628c6)
- [material-3-expressive-catalog（组件目录 demo）](https://github.com/meticha/material-3-expressive-catalog)
- [Expressive Design: Google's UX Research](https://design.google/library/expressive-material-design-google-research)

**字体**
- [Google Sans Flex — Google Fonts](https://fonts.google.com/specimen/Google+Sans+Flex)、[设计官方介绍（六轴）](https://design.google/library/google-sans-flex-font)、[Engadget 公开下载报道](https://www.engadget.com/big-tech/the-google-sans-flex-typeface-is-now-available-to-download-214535934.html)
- [M3 Typography](https://m3.material.io/styles/typography/overview)、[Compose 可变字体](https://developer.android.com/develop/ui/compose/text/fonts)

**组件库**
- [miuix](https://github.com/compose-miuix-ui/miuix)、[releases](https://github.com/compose-miuix-ui/miuix/releases)（v0.9.4）
- [compose-cupertino](https://github.com/alexzhirkevich/compose-cupertino)（v1.6.1）
- [Kyant0 AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)、[Backdrop 文档](https://kyant.gitbook.io/backdrop)
- [Haze 1.0 发布文](https://chrisbanes.me/posts/haze-1.0/)
- [prompt-bar](https://github.com/NadeemIqbal/prompt-bar)、[llm-typewriter](https://github.com/originalFactor/llm-typewriter/blob/main/README.md)、[compose-markdown](https://klibs.io/project/adamglin0/compose-markdown)
- [jetc.dev #315（I/O 26 新库 roundup）](https://jetc.dev/issues/315.html)

**竞品 / 趋势**
- [Gemini 浮动 pill 输入栏 — Android Police](https://www.androidpolice.com/google-gemini-updated-ui-android-input-box/)、[Windows Forum 分析](https://windowsforum.com/threads/google-tests-floating-pill-input-bar-for-gemini-android.395381/)、[r/GoogleGemini 输入栏膨胀抱怨](https://www.reddit.com/r/GoogleGemini/comments/1thzu6d/my_disappointment_with_the_new_ui_update/)
- [ChatGPT composer-first 重设计 — BGR](https://www.bgr.com/1933698/chatgpt-app-redesign-ahead-of-gpt-5-release/)、[桌面浮动 sidebar](https://community.openai.com/t/chatgpt-desktop-26-924-sidebar-redesign-some-missing-features-are-still-there-just-moved/1401386)、[频繁改 UI 抱怨](https://www.reddit.com/r/OpenAI/comments/1wrq35q/annoyed_of_chatgpt_app_changing_their_ui_every/)
- [Cherry Studio 官网](https://cherryai.com.cn/)、[LobeChat README](https://github.com/xiaoluoboding/lobe-chat/blob/main/README.zh-CN.md)
- [SillyTavern 界面自定义](https://sillytavern.wiki/usage/core-concepts/uicustomization/)、[Charites 气泡独立设置插件](https://github.com/SenriYuki/SillyTavern-Charites)、[主题安全风险](https://www.erocraft.com/docs/sillytavern/10-themes)
- [AI chat interface UI design — setproduct](https://www.setproduct.com/blog/ai-chat-interface-ui-design)（composer/流式状态/反模式）
- [7 UI Design Trends of 2026 — tubik](https://tubikstudio.com/blog/ui-design-trends-2026/)、[UI/UX Trends 2026 — Fuselab](https://fuselabcreative.com/ui-ux-design-trends-2026-modern-ui-trends-ux-trends-guide/)、[Bento Grid 趋势与批评](https://www.icertglobal.com/community/bento-grid-ui-trend-and-functional-minimalism-2026)、[2026 数据向趋势指南](https://www.sanjaydey.com/ux-ui-design-trends-2026-biggest/)

---

## 9. 第二轮增补调研（2026-11-13）

> 聚焦第一轮未覆盖的盲区：AI「思考/加载」动效、Agentic UI 模式、导航趋势、骨架屏、竞品增补、生态动态。

### 9.1 AI「思考/加载」动效模式（对方向 F/D 有直接落地价值）

2024–2026 的 AI 产品加载态已从「通用 spinner」全面转向**活动感知指示器**，主流手法四类，可叠加：

| 手法 | 说明 | 对本仓映射 |
|------|------|-----------|
| 动画点阵 | 气泡内 dot wave / pulse（最经典 typing indicator） | 现有 loading 态可升级 |
| Shimmer 扫光文字 | "Thinking…" 一行字上光带扫过——AI 产品标志性视觉，几乎成行业标准 | 流式等待态首选，实现成本低（Compose 一个 shader/brush modifier 即可，无需引库） |
| 阶段步进标签 | "Searching → Reading → Writing" 随工具调用切换 | 本仓有 tool call 事件流，可直接映射为阶段文案（搜索/工具/阅读中） |
| 计时器 | 经过时间 n 秒透明展示 | 思维链/工具调用可加 elapsed timer，强化「过程可见」 |

React 生态参考（借鉴形态）：[BoardUI](https://github.com/BoardUI/boardui)（agentic UI 组件系统：dot wave/spin/stars/infinity + shimmer label + elapsed timer）、[21st.dev AI loader 合集（26 个）](https://21st.dev/community/components/s/ai-loader)、[Wensity 阶段步进 typing indicator](https://ui.wensity.com/components/ai-typing-indicator)。

### 9.2 Agentic UI / Generative UI 模式（2026 主流，对应 Phone Use / agent 任务 UI）

- **AG-UI** 已成为 agent↔前端流式通信事实协议（tool call 事件、状态同步、GenUI payload）；Compose 侧已有 KMP 落地（[a2ui-kmp](https://github.com/BinaryTape/a2ui-kmp)、A2UI/Open-JSON、MCP Apps 三条协议线）。
- 2026 主流模式清单（[Kanopy Labs](https://kanopylabs.com/blog/agentic-ui-patterns-designing-ai-agent-interfaces)、[Zylos](https://zylos.ai/research/2026-05-28-agentic-ux-frontend-design-patterns-ai-agents)、[Fuselab](https://fuselabcreative.com/ui-design-for-ai-agents)）：
  1. **inline tool call 可视化**：tool start/args/result 流式渲染为可折叠卡片（对应 Cherry Studio Trace；本仓 tool timing 已有数据基础）
  2. **结构化输出卡片**：JSON 类数据渲染为卡片而非裸文本
  3. **可折叠 reasoning/tool traces**：思维链默认收起、可展开
  4. **human-in-the-loop 检查点**：危险操作前暂停确认（本仓 Phone Use 已有闸门措辞）
  5. **信任/进度信号**：透明展示 agent 在干什么、可随时接管
- 三种 GenUI 谱系（[CopilotKit](https://github.com/CopilotKit/generative-ui)）：controlled（App 控制组件）→ declarative（schema 驱动）→ open-ended（agent 自由生成 UI）。本仓近期只值得做前两种的「卡片化」形态，open-ended 是远期观察项。

### 9.3 导航趋势（对本仓主骨架有影响，观察项）

- **M3E 官方倾向 top-level 用 bottom navigation bar 取代 navigation drawer**（Material spec 更新报道）：本仓主导航是抽屉（ChatDrawer），与官方最新取向相反——但抽屉改底栏是骨架级改造且本仓抽屉功能密度高，**本轮不建议动**，记录为观察项。
- Navigation 3 的 per-tab backstack 模式与底栏天然配套；predictive back 已成默认预期，[Navigation Events 库](https://www.tunjid.com/articles/3-unique-predictive-back-animations-you-can-create-with-the-navigation-events-library-68af812b7c4b054f8bb3e0a3)可做自定义 predictive back 动画（低成本增强，可并入方向 C/H）。
- M3 flexible navigation bar（medium window width）属大屏话题，归入方向 H。

### 9.4 骨架屏 / Shimmer（低成本补充）

- 2026 Compose 生态共识：**无依赖的 shimmer modifier**（一个 brush 扩展函数，[ProAndroidDev 教程](https://proandroiddev.com/easiest-way-to-create-a-shimmer-effect-in-jetpack-compose-b56eae5e311e)）优于引库；KMP 库 [compose-shimmer-skeleton](https://github.com/timoseyfarth/compose-shimmer-skeleton) 备选；设计师驱动的复杂占位走 Lottie/Rive（本仓无 Lottie 依赖，不建议为此引入）。
- 落点：对话列表/设置页异步加载态、流式首 token 前的消息占位——与 9.1 的 shimmer 扫光共用实现。

### 9.5 竞品增补

| 产品 | 动态 | 对本仓 |
|------|------|--------|
| Microsoft 365 Copilot | 2026-05 大改版：更快、自适应工具、更清晰 AI 工作流（[官方博客](https://www.microsoft.com/en-us/copilot/blog/2026/05/28/introducing-a-new-design-for-microsoft-365-copilot)） | 工作流清晰化 = 工具过程可视化的又一印证 |
| Character.AI | UI 改版引发社区争议（[r/CharacterAI](https://www.reddit.com/r/CharacterAI/comments/1urk05k/they_changed_the_sites_ui)，新旧界面混杂灰度） | 再次印证 §4「布局稳定性」教训；其角色卡/ persona 导向 UI 是角色扮演受众参照 |
| Perplexity | Comet 浏览器（AI 系统级嵌入，2025-10） | AI 入口从聊天框外扩的趋势，远期观察 |
| 豆包/Kimi/DeepSeek | 公开渠道**无专门 UI 设计分析文章**，横评仅提到豆包「四模块分区」界面形态 | 文献缺位，如需借鉴建议真机体验而非依赖二手资料 |

### 9.6 生态动态（jetc.dev #337 / 版本线）

- Compose BOM alpha/beta 已到 **2026.11.00**；Compose 与 Material3 均出 RC；Compose 1.13 stable 预计 2026 年底前；I/O 26 宣布 Compose-first（[jetc.dev #337](https://jetc.dev/issues/337)、[#335](https://jetc.dev/issues/335)、[Five years of Compose](https://android-developers.googleblog.com/2026/07/five-years-of-jetpack-compose.html)）。
- 新官方库 **AppState StateStore/Transform**（快照状态提升到 app 层 + composition 外运行）：与本仓 DataStore/VM 架构重叠度低，观察即可。
- 值得留意的小库：[kapow](https://github.com/jantursky/kapow)（状态变化触发动画，如组件消失时烟雾效果）、[crease](https://github.com/jantursky/crease)（foldable 感知布局）——关注度中低，暂不引入。

### 9.7 第二轮增量结论

1. **最高性价比新增项**：shimmer 扫光「Thinking…」+ 工具调用阶段步进标签 + elapsed timer——三者共同构成「活动感知加载态」，实现零新依赖，直接强化方向 F，可合并进正式设计稿。
2. **Agentic UI 卡片化**（可折叠 tool call trace + 结构化输出卡片）应作为独立候选方向补进 §0 清单（建议编号 I，成本中、感知中高）。
3. **导航骨架不动**：M3E 底栏取向与本仓抽屉结构相悖，记录观察项即可；predictive back 动画可作为低成本糖。
4. **验证了第一轮判断**：Character.AI/Copilot 的改版争议再次确认「布局稳定性 + 可回滚」原则；AI 加载态趋势与方向 F 完全同向。

### 9.8 第二轮来源

**加载态 / Agentic UI**
- [The AI Aesthetic — Falcon AI](https://falcoxai.com/main/the-ai-aesthetic-how-design-is-shaping-the-future-of-software)、[Loading Indicator Glossary — Kombai](https://kombai.com/glossary/loading-indicator)
- [BoardUI](https://github.com/BoardUI/boardui)、[21st.dev AI loaders](https://21st.dev/community/components/s/ai-loader)、[Wensity typing indicator](https://ui.wensity.com/components/ai-typing-indicator)
- [Agentic UI Patterns — Kanopy Labs](https://kanopylabs.com/blog/agentic-ui-patterns-designing-ai-agent-interfaces)、[Agentic UX — Zylos](https://zylos.ai/research/2026-05-28-agentic-ux-frontend-design-patterns-ai-agents)、[Agent UX — Fuselab](https://fuselabcreative.com/ui-design-for-ai-agents)、[Generative UI — CopilotKit](https://github.com/CopilotKit/generative-ui)、[AG-UI on Bedrock — AWS](https://aws.amazon.com/blogs/machine-learning/build-generative-ui-for-ai-agents-on-amazon-bedrock-agentcore-with-the-ag-ui-protocol)

**导航 / 骨架屏**
- [Predictive back — Android Developers](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)、[Predictive back animations — tunjid](https://www.tunjid.com/articles/3-unique-predictive-back-animations-you-can-create-with-the-navigation-events-library-68af812b7c4b054f8bb3e0a3)、[M3 Navigation bar](https://m3.material.io/components/navigation-bar/overview)、[Mobile Navigation 2026 — UXPin](https://www.uxpin.com/studio/blog/mobile-navigation-examples)
- [Shimmer in Compose — ProAndroidDev](https://proandroiddev.com/easiest-way-to-create-a-shimmer-effect-in-jetpack-compose-b56eae5e311e)、[compose-shimmer-skeleton](https://github.com/timoseyfarth/compose-shimmer-skeleton)、[LottieTools skeleton generator](https://lottietools.app/skeleton-loader)

**竞品 / 生态**
- [M365 Copilot redesign](https://www.microsoft.com/en-us/copilot/blog/2026/05/28/introducing-a-new-design-for-microsoft-365-copilot)、[r/CharacterAI UI 反馈](https://www.reddit.com/r/CharacterAI/comments/1urk05k/they_changed_the_sites_ui)、[国产 AI App 横评 — 36kr](https://m.36kr.com/p/3825603016823686)
- [jetc.dev #337](https://jetc.dev/issues/337)、[#335](https://jetc.dev/issues/335)、[Five years of Compose](https://android-developers.googleblog.com/2026/07/five-years-of-jetpack-compose.html)

---

## 10. 本地竞品补析：dsh-go（2026-11-14）

> 对象：`F:\build\projects\dsh-go`（已同步 origin/main `72ab5f23`，2026-11-14）。DSH（DeepSeek Harness）的 Android 客户端，**Flutter 实现**——可取的是 UI/UX 设计模式与设计规范，不是代码复用。它最大的资产是 `docs/design/` 50+ 篇「可执行规格」级设计文档（含几何参数、时长、文案终稿、测试清单）+ `app/lib/ui/theme/` 设计 token 层；抽查确认文档所述均已在代码落地。

### 10.1 高价值可借鉴点

| # | 模式 | dsh-go 出处 | 对本仓 |
|---|------|------------|--------|
| 1 | **agent 状态→宠物状态机（PetMood）**：宠物=状态指示器非养成游戏；优先级 error>notification>sweeping>attention>carrying/juggling>working>thinking>idle>sleeping；working 按并发数分档、一次性态带 minDisplay/autoReturn、**完成去抖 1.5s**（同对话续跑则丢掉庆祝）、升档立即/降档延迟 5s | docs/design/pets.md §4.2-4.3；app/lib/pets/pet_mood.dart | 本仓 Phone Use/后台任务若做「生命感」表达，这套事件流归约规范是完整先例 |
| 2 | **多通道提醒去重**：宠物可见时气泡**替代**横幅；正在看的对话不冒气泡只做动作；悬浮窗可见时系统通知静默——「同一件事只提醒一次」贯穿三路；气泡文案仅 4 种（做完/出错/等确认/等回答），2s 内合并、停留 4/6/8s 分级 | pets.md §2.6-2.7、notify_state.dart | 本仓多通道（应用内/系统通知）通知设计直接可用 |
| 3 | **confirmRisk 三档风险确认 + 「问 AI」**：高=每次确认、中=首次+默认勾「不再提示」、低=就地副标题；弹层不可拖走、**只有一个主键**（取消由 × 承担）；「问 AI」=预填不发送（新开对话、聚焦不发送，花钱的事由用户决定） | risk-notices-and-ask-ai.md §2/§4；app/lib/common/risk_notice.dart | 本仓工具审批/危险开关可整套平移（注意本仓 M3 语境下主键形态适配） |
| 4 | **AppMotion 动效 token 表**：press 90/quick 160/standard 240/collapse 280/emphasized 340/sheetIn 380/sheetOut 260/slow 480ms；曲线 std=M3 standard、enter=emphasizedDecelerate、exit=emphasizedAccelerate、glide=Cubic(0.32,0.72,0,1)；**弹簧 withDurationAndBounce**：layout 350/b0、sheet 500/b0、fling 500/b0.15，灵动档专属 release 320/b0.45 等（回弹只喂位移/缩放，透明度永不越 1）；**全经 token 取时长，系统关动画自动为 0** | app/lib/ui/theme/motion.dart:1-110、polish-guide.md §2 | 可对照补全本仓 AppSkin motion token 的时长/曲线/弹簧档位；「系统关动画→0」的可及性纪律应照抄 |
| 5 | **玻璃性能约定**：GlassTier chrome/sheet/card/pill 四档，只有 chrome/sheet 真模糊且**同屏真模糊 ≤3 个**；互不重叠的模糊件进同一 BackdropGroup 只采一次背景；高对比度/低端机降级磨砂纸 fallback；阴影 even-odd 只画形状外 | app/lib/ui/theme/glass.dart + polish-guide.md | 对本仓 haze 玻璃的「模糊预算/分组采样/降级」直接有参数级参考（本仓已有 SDK 分级，可补预算与分组概念） |
| 6 | **设置页 IA 七规则**：设置管「以后」、对话管「现在」；一项设置只有一个家（入口可多、名字必须相同）；按意图分组（每行右侧显示**当前值**）；首页不放放宽保护/删数据控件；搜不到落「问 AI」；低频收「高级」、开发者项连点解锁。配套完整诊断方法论（点击步数/频率/影响面盘点表） | settings-ia.md（1194 行）§4.1；settings_screen.dart 已落地搜索+跳转高亮 | 本仓设置页重构可直接套用盘点表与七规则；「每行显示当前值」是低成本高收益改动 |
| 7 | **输入栏多状态条叠放（dock_stack）**：待办/目标/运行/插件状态条 >2 张时叠放（露边 6dp、每张小 5%、淡些），点击展开、上下滑换卡（0.35 张阈值）、新条按比例生长防整摞跳动 | page-design.md §7；dock_stack.dart | 本仓输入区上方状态条（工具运行/队列/错误）共存时的形态参考 |
| 8 | **Composer 状态让位**：`waiting` 时发送键让位给审批卡（唯一主键）、placeholder 换「等你回答」；`running` 且空输入时发送键变停止键；长按发送=插话/中断并发送/排队三选；**`linesFor()` 公式**：可见行数=键盘上方高度 40% 换算 clamp(2,6) | app/lib/ui/chat/composer.dart（941 行），linesFor 在 :158-165 | 本仓 ChatInput 的生成中/待确认状态表达；行数公式直接可抄 |
| 9 | **悬浮条三形态变形**：小球 48/胶囊 48/卡片 300 同一条 24 圆角曲线连续变形；胶囊宽度**防抖**（变长立即、变短需持续 1.2s 且 ≥24dp）；**停止永远一步可达**（胶囊右端恒为停止键）；信息分级（胶囊只说「正在做的事」，已解除限制降为小图标）；危险色用低饱和砖玫红非正红 | phone-overlay.md §1-§3 | 若做任务运行悬浮条/常驻通知，防抖与「停止一步可达」是安全底线 |
| 10 | **主动弹窗打扰时机判定表**（star-prompt）：前台累计 30 分钟+**闲下来保持 3 秒才弹**，10 余条不弹条件（对话在跑/有事等你/操控中/键盘/草稿…） | star-prompt.md | 可移植到任何主动弹窗（评分邀请/更新提示/新功能引导） |

### 10.2 中价值

- **流式输出视觉规范**（polish-guide.md §2）：正文匀速 reveal、无光标、停顿时呼吸小点；列表跟随「滑过去」不跳；工具运行中=细圆弧、刚成功画对勾再归静；**明确不做逐字弹跳/模糊/扫光/庆祝动画**——与本仓方向 F 的「非假打字」判断一致，且给了反向清单。
- **roleplay 三级渲染外壳**：纯文本=原生 Markdown、带 HTML=沙箱 WebView「岛屿」、带脚本卡=整页画布；顶栏/输入区/抽屉永远原生。三种排版每对话自选（气泡/小说/视觉小说）；**左右滑换回答（显示 2/5，滑到头=再生成）**；长按「看这一回合的提示词」检视页（每段来源+token 数+缓存命中+花费，打碎缓存的标黄）；**下拉顶部开分支树流程图**（岔口节点/当前路径高亮）——RikkaHub MessageNode 树已有数据结构，这套交互最成熟（roleplay-ui.md + tavern/ 30 文件）。
- **建议回复胶囊**：点击填入输入框不直接发送、长按才发；状态面板数值变化旁「+5」小标签下回合消失、可直接点数值改。
- **OKLCH 配色守卫**：强调色 6 预设 OKLCH 推导（固定 L/彩度表）；对比度守卫=色相彩度不动、只沿 L 二分 24 步达标（≥3:1/4.5:1）不弹提示直接画守卫后颜色；强调彩度上限 0.13、中性色彩度<0.025（app/lib/ui/theme/accent.dart，398 行 + palette_contrast_test）。本仓 AppSkin palette 可借鉴守卫思路。
- **引导并行化**：长任务后台跑+每页底部固定玻璃胶囊进度、点开状态弹层；「外观页只有继续没有跳过」（选项即点即生效）；提前完成不放用户先进 App（空 App 首印象=「坏了」论证）；中断恢复靠偏好记录步骤（onboarding-parallel-setup.md）。
- **页面骨架五型**（page-design.md §1）：枢纽/详情（PageHero 随滚动收进顶栏）/列表/表单（底部固定主按钮贴键盘）/弹层（圆角 28 左右 20）；横屏高<480 一律单列的论证。
- **分享面板两层结构**：「分享什么」（范围/包含/预览）设一次，四个去向各一页；打码不是开关（密钥永远隐藏）；share-redesign.md。
- **companion 反模式清单**：连签清零/卖惨通知/限时物件=不惩罚缺席；主动邀请极严触发条件（companion.md §6.4）——若有「陪伴感」方向可参考其克制原则。
- **文案规范**（polish-guide.md §3）：短、确定、像产品；术语统一表；按钮动词开头 2-4 字；报错=发生了什么+怎么办。本仓中文化/i18n 可直接对照。

### 10.3 低价值（平台绑定过深，仅产品灵感）

像素宠物 pack.json 格式、Live2D 舞台协议、Kotlin 悬浮窗窗口几何、桌面小组件、潮水/日记等具体玩法；Web Components 消息组件层（本仓无此架构）。

### 10.4 增量结论（并入 §0 候选视角）

1. dsh-go 验证并细化了方向 F 的边界：**做「匀速 reveal + 呼吸点 + 工具弧线」，明确不做逐字弹跳**——但 dsh-go 反对扫光、AI 生态主流是扫光（§9.1），两者冲突可做成皮肤/外观档位拍板。
2. 动效纪律三件套可直接移植：motion token 表 + 系统关动画归零 + fluid 两档树结构一致（树形不变、子树状态不丢）。
3. 设置页 IA 七规则与盘点表是本仓设置页 Bento 化（方向 E）之前的**前置诊断工具**，建议设计稿阶段先跑一遍盘点。
4. 工具审批（confirmRisk 三档+问 AI 预填）与 dock_stack 状态条叠放是两个独立可落地项，可与方向 I（Agentic 卡片化）合并设计。