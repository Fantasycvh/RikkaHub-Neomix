# Phone Use 操作目标三态选项设计稿

> 状态：**已实施（2026-11-23），待真机验证**。拍板：提示行**精确判断轮进行中**（`ChatService.isAnyTurnProcessing`
> + 500ms 轮询）；虚拟屏选项文案含「（需 Shizuku）」并三语言适配。
> 关联：[design-phone-use.md](../completed/design-phone-use.md)（v1 实体手机操作）·
> [phoneuse-virtual-screen-2026-11-23.md](../verification/phoneuse-virtual-screen-2026-11-23.md)（虚拟屏验证闭环）
> 硬约束：沿用来源门控（`allowPhoneUse`）；不改变「每轮首次操作前确认」审批语义；HOME/RECENTS
> 全局键禁止（既有不变量）。

---

## 1. 背景与问题

当前「用实体手机还是虚拟屏」是**隐式**的：每轮开始时 `PhoneUseTurnBudget.pinDisplay()`
看有没有活跃虚拟屏（`PhoneUseVirtualDisplays.current`），有→路由到虚拟屏，无→实体手机
（`PhoneUseTool.kt:238-240` 读协程上下文里的 `PhoneUseDisplayTarget`，`display == null` 时
落到无障碍服务本体）。

用户侧唯一的「选择」是手动 Start/Stop 虚拟屏，带来两个问题：

1. **想用实体手机但装着虚拟屏**（如系统不允许副屏、某些应用在副屏异常、单纯想操作主屏）时，
   只能先停虚拟屏——且 Stop 后整条链路（观察窗、预览）随之消失，语义混乱；
2. **想用虚拟屏但忘了 Start** 时，动作静默落到实体手机——在用户主屏上点来点去，
   结果与预期完全相反，且没有任何提示。

## 2. 目标与非目标

**目标**

- 显式三态选择：`自动 / 实体手机 / 虚拟屏`，让「操作哪块屏」由用户决定而非运行状态决定；
- `虚拟屏` 模式未启动时**明确报错**，禁止静默降级到实体手机；
- 切换在**下一轮**生效（与既有「pin 在轮开始时冻结」语义一致），不影响进行中的轮次与审批续跑。

**非目标**

- 不做助手级差异化配置（同 `phoneUseBlockedPackages`，属设备级开关，放全局设置）；
- 不改虚拟屏的启停方式（仍是实验区 Start/Stop 手动开关）；
- 不改来源门控：定时任务/子代理/Web/后台续跑依旧不装配 `phone_use`（三态只影响装配后的目标路由）。

## 3. 方案

### 3.1 设置项

`DisplaySettings`（`DisplaySettings.kt`，与 `phoneUseBlockedPackages` 同处）新增：

```kotlin
enum class PhoneUseTargetMode { AUTO, DEVICE, VIRTUAL }

val phoneUseTargetMode: PhoneUseTargetMode = PhoneUseTargetMode.AUTO,
```

默认 `AUTO` = 现行为，存量用户零感知。

### 3.2 目标解析（单点）

`PhoneUseTurnBudget.pinDisplay()` 改为按模式解析（轮开始时调用一次，`reset()` 时清空——
现有语义自动获得「下一轮生效」）：

| 模式 | 解析结果 |
| --- | --- |
| AUTO | `PhoneUseVirtualDisplays.current.value`（现行为） |
| DEVICE | 恒为 `null`（忽略运行中的虚拟屏，操作实体手机） |
| VIRTUAL | `PhoneUseVirtualDisplays.current.value`，可为 `null`（由执行层报错，见 3.3） |

`PhoneUseDisplayTarget` 增加 `requireVirtual: Boolean`（或透传 mode），让执行层能区分
「AUTO 下没有屏 → 正常实体手机」与「VIRTUAL 下没有屏 → 错误」。

### 3.3 执行层与请求面

- `PhoneUseTool` execute（`:238-240`）：`requireVirtual && display == null` 时直接返回错误
  文本「Virtual display mode is selected but no display is running. Ask the user to start the
  Phone Use display, or switch the target mode to this device.」——**不落实体手机**；
- 工具 schema（`parameters` 内 `virtualDisplay` 标志，`:151`）：暴露 `open_url` / `list_recent`
  的条件从「有活跃虚拟屏」改为「有效目标为虚拟屏」（mode ≠ DEVICE 且 display 非 null）；
  DEVICE 模式下这两个动作自然从枚举中消失，描述文案同步；
- 错误文案复用既有约定：`listRecent`/`openUrl` 的非虚拟报错保持不变，另加 VIRTUAL 模式
  下的「未启动」专属提示（区分「模式不对」与「没开屏」）。

### 3.4 UI（助手 → 本地工具 → Phone Use 卡）

- Phone Use 段内、实验区上方增加三选一（SegmentedButton / 三行 Radio 均可），
  supporting 文案：
  - 自动：虚拟屏开启时在虚拟屏操作，否则操作本机
  - 实体手机：始终操作本机（忽略虚拟屏）
  - 虚拟屏：只操作虚拟屏；未启动时模型会收到明确错误
- 进行中轮次切换的提示：由于 pin 在轮开始冻结，切换后若当前轮仍在跑，显示一行
  「Change applies to the next turn.」样式提示（`phoneUse` 轮活跃状态可从
  `PhoneUseManager.acting` / 生成状态判断，简化实现可先静态展示说明文案）；
- DEVICE 模式且虚拟屏运行中：实验区顶部提示「A virtual display is running but will not be
  used while the target is This device.」——不自动关闭虚拟屏（用户可能随时切回）。

文案实现：随现有 Phone Use 段用 `stringResource`，en / zh / zh-rTW 三语言齐备
（走 `locale-tui-localization` 流程）。

## 4. 触点清单

| 文件 | 改动 |
| --- | --- |
| `data/datastore/DisplaySettings.kt` | 新增枚举 + 字段 |
| `data/ai/PhoneUseTurnBudget.kt` | `pinDisplay()` 按模式解析；`PhoneUseDisplayTarget` 带 `requireVirtual` |
| `phoneuse/PhoneUseDisplayTarget.kt` | 上下文元素扩展字段 |
| `data/ai/tools/local/PhoneUseTool.kt` | execute 的 VIRTUAL 缺屏报错；schema 暴露条件 |
| `service/ChatService.kt` | `pinDisplay()` 调用处传入当前设置（`:537`） |
| `ui/pages/assistant/detail/AssistantLocalToolPage.kt` | 三选一控件 + 提示行 |
| `res/values*/strings.xml` | 约 6 个键 × 3 语言 |
| `app/src/test/...` | 见 §5 |

## 5. 验证计划

**JVM 单测**

1. `PhoneUseTurnBudget`：三模式 ×（有屏/无屏）的 pin 结果；reset 后重新解析；
2. `PhoneUseTool`：schema 枚举暴露矩阵（三模式 × 有屏/无屏）；VIRTUAL 缺屏返回错误文本
   且**未调用**实体控制器；DEVICE 模式忽略已 pin 的虚拟屏。

**真机矩阵**（PJX110，debug/release 各一轮 Start 链路）

| 场景 | 预期 |
| --- | --- |
| AUTO + 无屏 | 操作实体手机（现行为） |
| AUTO + 有屏 | 操作虚拟屏（现行为） |
| DEVICE + 有屏 | 操作实体手机；虚拟屏保持不关；卡片提示可见 |
| VIRTUAL + 有屏 | 操作虚拟屏 |
| VIRTUAL + 无屏 | 模型收到明确错误；主屏无任何实际触摸 |
| 轮中切换模式 | 当前轮目标不变；下一轮按新模式 |

## 6. 不变量（勿回退）

1. pin 在轮开始冻结、审批续跑保持同一目标（`PhoneUseTurnBudget` 类注释既有语义）；
2. `allowPhoneUse` 来源门控不变；子代理/定时/Web 永远没有 `phone_use`；
3. VIRTUAL 缺屏**不得**静默降级到实体手机（本设计存在的根本原因）；
4. 三态不改变虚拟屏启停方式与观察窗/预览链路；
5. HOME/RECENTS 全局键禁令不因本设计松动。

## 7. 待定

- 提示行是否精确反映「轮进行中」（需要读生成状态）；简化版可用静态说明，拍板时定；
- DEVICE 模式下若用户点击 Stop 虚拟屏，行为不变（正常关闭）——确认无需特殊联动。
