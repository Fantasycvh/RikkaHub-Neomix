# 导航统计（P0-4 埋点）设计稿

> 状态：**M1 已实施并真机验证通过（2026-11-22）**——单测 6/6 绿、IME 审计 A/A'=0；真机（PJX110/SDK35, debug 变体）实测：Chat→设置→关于→长按进 Debug 全链路计数与转移对正确（`Chat>Setting`/`Setting>SettingAbout`/`SettingAbout>Debug` 各 1），Debug Nav tab 显示与 run-as 文件一致，force-stop 重启后计数保留、新会话再计一次。**未提交**
> 上游：[ui-simplification-proposal.md](../../ui-simplification-proposal.md)（P0-4）· [design-ui-overhaul-research.md](design-ui-overhaul-research.md)
> 硬约束：不引入遥测/上报（Firebase 已移除，纯本地统计）；不动 IME 宿主拓扑；LayoutInspector 坑照旧。

---

## 0. 拍板记录（2026-11-22，用户逐项确认）

| # | 拍板点 | 决定 |
|---|--------|------|
| 1 | 「UI 改进可切换」边界 | 同意分法：**语义收敛类单向**、**形态选择类档位化** |
| 2 | 输入栏 pill 化 | **视为已完成**——`chatInputFloatingPill` + `chatInputIconFade` 已落地（DisplaySettings:274/279，开关在 SettingPreferencesGeneralPage:243），实现采用「低频图标淡出披露」替代 FAB menu 方案 |
| 3 | 第三批皮肤档位（暖色极简等） | **先跳过**，待工程感/iOS M4 真机验收收尾后再议 |
| 4 | 皮肤↔聊天外观联动升级绑定+可解绑覆写 | **已完成**（`ae3bfa2909`） |
| 5 | P1-3 / P1-4 / P0-4 | P1-4 **核查发现已清偿**（SettingPage 现为 通用/AI 与生成/连接与数据/关于 四组）；P1-3 **暂不动**（见 #6）；P0-4 本稿实施 |
| 6 | 压缩上下文/提示词预览位置 | **暂时保持不动**（留在附件面板；面板内已有「会话级动作」语义分组注释，用户 2026-11-22 拍板） |

> 更正记录：讨论稿初版把 P1-4/M4 列为挂账，现状核对后均为已完成——后续以本稿状态行为准。

---

## 1. 目标与非目标

**目标**

1. 回答 ui-simplification-proposal §6 的四个「必须靠数据」问题：哪些入口真正常用、设置页/抽屉的回溯行为、主导航模型是否值得动（P2）。
2. 采集口径：**页面进入事件（含来源页）**——`From>To` 转移对即可推断入口点击（「从设置页进入 X」= enter(X, from=Setting)），无需逐个入口埋点。
3. 纯本地：无上报、无 PII（只记路由类名，不记会话 id/参数）。

**非目标**

- 不做远程遥测/分析框架。
- 不动 `Logging` ring（内存上限 100 条且与请求日志共享——页面事件会把请求日志挤掉，故另立存储，见 §2）。
- 不在本期加任何面向普通用户的 UI；查看入口放 DebugPage（长按「关于」进入，符合提案 §5.6 对调试入口的定位）。

## 2. 设计

### 2.1 存储：独立 NavStats（不走 Logging ring）

```kotlin
// app/.../utils/NavStats.kt
@Serializable
data class NavStatsData(
    val startedAt: Long,               // 首次记录时刻（epoch ms），重置时刷新
    val pageCounts: Map<String, Long>, // 路由类名 -> 进入次数
    val transitions: Map<String, Long>,// "From>To" -> 次数（仅相邻两页）
)

object NavStats {
    fun init(file: File)               // App 启动时载入既有 JSON（损坏则丢弃重建）
    fun recordPageEnter(name: String?) // 与上次同名去重（同页实例重建不重复计数）
    fun snapshot(): NavStatsData       // DebugUI 只读
    fun reset()                        // DebugUI 清零
}
```

- 写盘：单线程 executor + 3s debounce 合并写；文件 `filesDir/nav_stats.json`，IO 失败静默（同 Logging 纪律）。
- 记录口径：`Screen::class.simpleName`（61 条路由天然分组，data class 的 id 参数不参与——页面身份而非实例身份）。

### 2.2 挂载点：RouteActivity（零逐页接线）

- `RouteActivity.onCreate` 调 `NavStats.init(File(filesDir, "nav_stats.json"))`。
- 组合体内：

```kotlin
val topScreen by remember(backStack) { derivedStateOf { backStack.lastOrNull() } }
LaunchedEffect(topScreen) { NavStats.recordPageEnter(topScreen?.let { it::class.simpleName }) }
```

- 返回（pop）同样计为新一次进入——对「回溯率」这正是要观测的行为；`recordPageEnter` 内的同名去重挡住 nav3 重复派发/实例重建造成的同页连击（呼应 RouteActivity:405 空栈防线的手势补投问题）。

### 2.3 查看：DebugPage 新增 Nav tab

- 列出 Top 页面（次数降序）与 Top 转移对；`Reset` 按钮；显示 startedAt。
- 沿用 DebugPage 现有英文直书风格（该页不走 stringResource）。

## 3. 风险

| 风险 | 对策 |
|------|------|
| nav3 手势补投导致重复计数 | 同名去重 + 只对相邻转移记录（已在 §2.2 说明） |
| JSON 文件损坏 | init 时 runCatching 丢弃重建，等价于重置计数 |
| 长期膨胀 | 路由封顶 61 类 + 转移对封顶 61²，量级可控；Debug 可重置 |
| 隐私 | 类名不含参数；无上报通道 |

## 4. 实施与验收

- M1：NavStats + RouteActivity 挂载 + DebugPage Nav tab + 单测（纯逻辑：去重/转移计数/JSON 往返/损坏容错）。
- 真机验收：导航数页后 Debug → Nav 核对计数与转移对；重启进程后计数保留。
- 数据用途：积累 ≥1 周后回看 proposal §6 的 Stats 去留与抽屉排序决策。
