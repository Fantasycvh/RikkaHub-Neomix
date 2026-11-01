# 轻量个性化（头像框 + 输入框背景 + 思维链样式）— 设计稿

> 状态：**已实现（2026-11-01，编译 + 全量单测 2650 项 0 失败），待真机验证**（roadmap §一.3 橘瓣条目收尾；五项中气泡透明度/字体包本就已有）。
> 五项中**气泡透明度**（`bubbleOpacity`）与**字体包导入**（`chatFontFamily=CUSTOM`）已有，本稿只做剩余三项。
> 参照：橘瓣（仅灵感来源，见 `docs/roadmap.md` §一.3）；接入点经源码核验。

## 一、决策表

| # | 决策 | 说明 |
|---|---|---|
| 1 | 三项全部存 `DisplaySetting`（全局个性化），不进 ThemeSnapshot | 单 JSON key 零迁移；快照是「主题域」，聊天个性化不混入 |
| 2 | 头像框作用于**聊天页双侧头像**（用户 + 助手），其他页面不动 | 聊天页专属个性化；`UIAvatar` 加带默认值的 `frame` 参数，其余调用处零改动 |
| 3 | 头像框用**纯 Canvas 预设绘制**，不做自定义图片框 | 6 档预设（无/单环/渐变环/双环/虚线环/光晕）零资源、随主题取色；沿 `rememberAvatarShape` 的实际形状描 outline，兼容非圆形头像 |
| 4 | 输入框背景图与玻璃/模糊**互斥**：设了背景图即跳过 `chatInputBackgroundEffect` | 模糊采样的是页面背景，叠加输入框内图语义混乱；互斥最可靠，注释说明 |
| 5 | 输入框背景图**不裁剪**，`ContentScale.Crop` 渲染 | 输入栏是宽条，渲染端裁切即可；图经 `FilesManager.createBackgroundImage` 落 `backgrounds/`（managed 目录防清理误删，复用先例） |
| 6 | 思维链三档：CARD(现状) / QUOTE(引用条：透明底+左缘主色竖线) / MINIMAL(极简：透明底+无时间线) | `ChainOfThought` 加带默认值 `style` 参数控制 Card 配色/形状/时间线绘制；折叠/展开状态机零改动；`WholeTurnFoldBlock` 透传 |
| 7 | 非 CARD 档的 `LocalCardColor` 仍用原容器色 | 步骤图标用它遮时间线，透明会漏线；图标保持低调 chip 观感 |

## 二、数据与接入点

| 字段（DisplaySetting） | 默认 | 消费点 |
|---|---|---|
| `avatarFrameStyle: AvatarFrameStyle` | NONE | `ChatMessageAvatar.kt` 两处 `UIAvatar(frame=…)`；绘制在 `UIAvatar.kt` |
| `chatInputBackgroundImage: String` | "" | `ChatInput.kt` 内层 Surface（Box 底层 AsyncImage + 颜色分支 + 跳过模糊） |
| `chatInputBackgroundOpacity: Float` | 0.35f | 同上 alpha |
| `chainOfThoughtStyle: ChainOfThoughtStyle` | CARD | `ChatMessage.kt:424` cardColors 换算 + `ChainOfThought`/`WholeTurnFoldBlock` |

设置入口：`SettingPreferencesUIPage` 新增「聊天个性化」区块（头像框 6 选 FlowRow chips、输入框背景选择/清除行、思维链样式 SegmentedPill）。

## 三、非目标

- 头像框不做自定义图片框/动画框；不改抽屉/助手列表等其他页面头像。
- 输入框背景不做 per-assistant（聊天背景图已是 per-assistant，二者语义分层：全局个性化 vs 会话主题）。
- 思维链不改折叠/展开/整轮折叠逻辑，只做容器视觉。
