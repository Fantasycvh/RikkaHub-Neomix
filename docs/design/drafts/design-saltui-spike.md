# SaltUI 3.0.0 隔离 Spike：分组元项与开关形态验证

> 状态：**方案稿，待拍板；未添加 SaltUI 依赖**
> 日期：2026-11-24
> 目标：在独立 debug fixture 中验证 SaltUI 的 `Item` / `ItemSwitcher` / `Switcher` 是否比现有 `CardGroup` 提供可感知收益，并同时验证主题、无障碍、动效、合规和包体边界。
> 上游：[高级感皮肤设计稿](design-skins-batch3-premium-dark.md)、[UI 改造调研](design-ui-overhaul-research.md)
> 约束：Material3 / AppSkin 仍是唯一主题权威；不改默认皮肤、聊天管线、输入栏宿主或 Navigation3。

---

## 1. 结论与决策请求

**建议先做 debug-only spike，不直接把 SaltUI 接入生产设置页。**

原因不是工具链不兼容，而是以下生产门槛尚未清零：

1. `Item`、`ItemSwitcher`、`Switcher`、`LabelValue`、`ItemDivider`、`PillButton` 同属一个 `salt-ui` artifact，不能按组件拆分依赖；
2. `LabelValue`、独立可点击 `Switcher`、`PillButton` 标注 `@UnstableSaltUiApi`；
3. `Switcher` 固定 46×26dp，形变/位移/颜色时长为 800/200/300ms，`SaltTheme` 没有 motion token 注入口；
4. 官方 Android POM 确认传递 `org.lsposed.hiddenapibypass:hiddenapibypass:6.1`；
5. SaltUI README 建议在 Google Play 发布时关闭 `dependenciesInfo`，与本仓当前发布策略冲突，不能为皮肤层自动关闭；
6. AAR 原始大小为 2,355,263 bytes，但 R8 后增量、运行时成本和真机稳定性仍未知。

**请求拍板的仅限 Spike 范围**：新增 `debugImplementation("io.github.moriafly:salt-ui:3.0.0")`、debug fixture 和 instrumented tests。生产依赖、发布配置和页面迁移不在本次授权内。因此本 spike 只能验证交互与 debug 运行成本，不能据此宣称 R8 后发布包增量已通过；该指标须在另行批准的测量构建中完成。

---

## 2. 依赖升级基线

Kotlin/Compose 升级已在工作区完成，作为 SaltUI spike 的前置独立变更：

| 项目 | 当前值 | 处理 |
|------|--------|------|
| Kotlin | `2.4.20` | 与 SaltUI 3.0.0 对齐 |
| Compose BOM | `2026.09.00` | app 与 BetterText 共用同一 catalog BOM |
| Compose Multiplatform | `1.12.2` | 约束 Haze/Koin 等带入的 CMP runtime/foundation/UI |
| Material3 | `1.5.0-rc01` | 保持现有版本 |
| AGP / Gradle | `9.4.0` / `9.6.0` | 不改 |
| compileSdk / minSdk | 37 / 26 | 不改 |
| JDK / JVM target | 构建 JDK 21 / target 17 | 不改；由实际编译验证 SaltUI AAR 可消费 |

落点：

- [gradle/libs.versions.toml:5-18](../../../gradle/libs.versions.toml#L5)
- [app/build.gradle.kts:196-204](../../../app/build.gradle.kts#L196)
- [bettertext-text/build.gradle.kts:6-9](../../../bettertext-text/build.gradle.kts#L6)

`dependencyInsight` 已确认 AndroidX Compose runtime 为 `1.12.1`，CMP runtime/foundation/UI 为 `1.12.2`；二者来自不同坐标体系，不能把“CMP 1.12.2”误写成“AndroidX runtime 1.12.2”。

---

## 3. SaltUI 3.0.0 已核实事实

| 主题 | 结论 | 对本仓的影响 |
|------|------|--------------|
| Artifact | Android 项目入口为 `io.github.moriafly:salt-ui:3.0.0`，Gradle 解析 Android 变体 `salt-ui-android` | 只声明一个依赖，不直接声明 `salt-ui-android` |
| 模块 | 核心 UI 在一个 artifact；`salt-core`、`salt-adaptive-window` 为传递依赖；navigation 独立 | 本批不引入 navigation |
| 工具链 | Kotlin 2.4.20、CMP 1.12.2、AGP 9.1.1、JDK 21、compileSdk 37、minSdk 23 | 本仓已满足公开门槛；AGP 9.4 仍需实际编译确认 |
| 主题 | `SaltTheme` 可注入 configs、dynamicColors、textStyles、dimens、shapes；`MaterialType.None` 时不创建 HazeState | 可在 fixture 内做纯投影适配器 |
| 动效 | 没有 Salt motion token API；`Switcher` 时长硬编码 | 不能宣称接入 AppSkin motion |
| 开关语义 | `ItemSwitcher` 整行 `toggleable(role = Role.Switch)`，内部 `Switcher(state)` 只绘制 | 与现有正确范式一致，禁止嵌套第二个 callback |
| 独立开关 | `Switcher(state, onChange)` 为 unstable，且未显式传 `Role.Switch` | 不作为生产首选 |
| 值标签 | `LabelValue` 为 unstable，标签固定 96dp | 只做观察，不替代 `CardGroupValueChip` |
| 按钮 | `PillButton` 为 unstable，使用 `Role.Button`，内部走 `subMaterial` | 即使禁用 Haze 效果，依赖仍会进入 |
| RTL | `Switcher` 显式镜像绘制；Item 使用逻辑 start 缩进 | 必须真机验证整行布局与滑块方向 |
| 合规 | Apache-2.0；POM 含 HiddenApiBypass 6.1；README 提及关闭 dependenciesInfo | 生产准入前必须单独审计 |

来源见 §12。

---

## 4. 能力缺口与候选组件

| SaltUI 能力 | 本仓现有能力 | Spike 判断 | 生产默认决定 |
|------------|--------------|-----------|--------------|
| `Item` | `CardGroup.item` + M3 `ListItem`，支持标题、副文、leading、trailingValue、点击 | 验证密度、层级和按压反馈是否明显更好 | 不替换；`CardGroup` 已覆盖 |
| `ItemSwitcher` | `Row.toggleable(Role.Switch)` + 非交互 `Switch`，已有两处成熟范式 | 验证整行单语义和 O→I 形变 | 仅在通过全部门槛后考虑局部采用 |
| `Switcher(state)` | M3 Switch / 自定义 Switch | 作为 `ItemSwitcher` 的绘制部分对比 | 不单独引入 |
| `LabelValue` | `CardGroupValueChip` 与页面值摘要 | 固定 96dp 不利于中文、长值和 200% 字体 | 不采用 |
| `ItemDivider` | `AppSkinGroups.divider` + `dividerInsetDp` + `dividerWidthDp` | 验证 hairline 与 start inset | 复用现有 token |
| `PillButton` | M3 按钮 + AppSkin shapes | unstable 且材质路径更重 | 不采用 |

现有语义与主题落点：

- `CardGroup` 的点击分支只有 `Modifier.clickable`，没有统一 `Role.Button`：[CardGroup.kt:337-367](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/CardGroup.kt#L337)
- 值 chip 最大宽度 160dp、单行省略：[CardGroup.kt:415-437](../../../app/src/main/java/app/androidrik/rikkahub/ui/components/ui/CardGroup.kt#L415)
- 现有整行开关范式使用 `Role.Switch`，视觉 Switch 不接收 callback：[AppSkinAccentControls.kt:143-158](../../../app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/components/AppSkinAccentControls.kt#L143)
- 主题与 motion 的单一入口：[AppSkinTheme.kt:28-45](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/AppSkinTheme.kt#L28)、[MotionTokens.kt:53-69](../../../app/src/main/java/app/androidrik/rikkahub/ui/theme/MotionTokens.kt#L53)

---

## 5. Spike 范围

### 5.1 包含

1. 一个 debug-only Compose A/B fixture；
2. `Item`、`ItemSwitcher`、纯绘制 `Switcher`、`ItemDivider`；
3. `LabelValue`、`PillButton` 仅放在“unstable API 观察区”，不参与生产推荐；
4. 同一 `AppSkinTheme` 下的现有 `CardGroup` 与 SaltUI 并排对比；
5. 本地 `rememberSaveable` 状态，不接 VM、DataStore、导航或真实设置；
6. 语义树、点击次数、禁用态、RTL、200% 字体、深浅色和皮肤切换测试；
7. 依赖图、HiddenApiBypass 调用路径、release 依赖排除、debug APK 差分和 FrameMetrics/PSS 记录；生产 R8 增量留待另行批准的测量构建。

### 5.2 不包含

- 不修改 `CardGroup`、自定义 `Switch`、`AppSkinTheme`、根 `RikkahubTheme`；
- 不修改任何生产设置页、DebugPage tab 或 Navigation3 route；
- 不修改默认皮肤、聊天外观、聊天气泡、ChatInput 或 IME 宿主；
- 不引入 `salt-ui-navigation`、`SaltMaterial` 玻璃效果或 Haze 新效果；
- 不关闭 `dependenciesInfo`，不为 spike 改签名、R8 或发布工作流；
- 不做数据迁移、持久化或用户可见功能开关。

---

## 6. 实施步骤

### S0：已完成的前置工具链升级（不重复验证）

Kotlin/Compose 升级已作为 SaltUI spike 的前置独立变更完成：

- Kotlin `2.4.20`、共享 Compose BOM `2026.09.00`、CMP `1.12.2`；
- app 与 BetterText 的 BOM 已收敛，CMP runtime/foundation/UI 已加约束；
- 用户已确认前置工具链升级的验证结果，本 spike 不重复执行，也不在本稿补写未经复核的通过项；
- 最终解析记录区分 AndroidX Compose runtime `1.12.1` 与 CMP `1.12.2`，二者属于不同坐标体系。

工具链升级与 SaltUI spike 保持独立变更，任何一方失败可独立回滚；用户确认的既有验证结果不因编写本方案而重跑。

### S1：debug-only 依赖与宿主

1. 在版本目录增加 `saltui = "3.0.0"` 和 `salt-ui` alias；
2. 在 app 增加 `debugImplementation(libs.salt.ui)`；
3. 复用 [SketchTestHostActivity.kt:18-36](../../../app/src/debug/java/app/androidrik/rikkahub/SketchTestHostActivity.kt#L18)，增加 intent extra `saltUiSpike` 分支；
4. 不新增 Activity、route 或 production manifest 声明。

预期发布包不会包含该依赖，因为 debug source set 与 debugImplementation 不进入 release；这一点必须用 release APK 依赖报告验证，不能只靠声明位置。

### S2：主题适配器与 A/B fixture

新增 debug-only 文件：

- `app/src/debug/java/app/androidrik/rikkahub/saltui/SaltUiSpikeTheme.kt`
- `app/src/debug/java/app/androidrik/rikkahub/saltui/SaltUiSpikePreview.kt`

适配器只做 **M3/AppSkin → Salt** 的单向投影：

| Salt token | 宿主来源 |
|-----------|----------|
| `configs.isDarkTheme` | `LocalDarkMode.current` |
| `configs.indication` | `LocalIndication.current` |
| `highlight` | `MaterialTheme.colorScheme.primary` |
| `text` | `onSurface` |
| `subText` | `onSurfaceVariant` |
| `background` | `background` |
| `subBackground` | `surfaceContainerLow` |
| `popup` | `surfaceContainerHigh` |
| `stroke` | `outlineVariant` |
| `onHighlight` | `onPrimary` |
| `error` | `error` |
| `success` / `warning` | spike 临时映射 `primary` / `tertiary`；目标组件不使用，生产采用前必须补语义映射 |
| `main` / `sub` / `paragraph` / `largeTitle` | M3 `bodyLarge` / `bodySmall` / `bodyMedium` / `headlineSmall` |
| `small` / `medium` / `large` | M3 Shapes 同名角色 |
| `item` / `itemIcon` | 56dp / 24dp，保持 Android 最小触控目标 |
| `padding` / `subPadding` | AppSkin `formHorizontalDp` / 12dp |

构造方式使用 SaltUI 3.0.0 的公开 API：

- `SaltConfigs.default(isDarkTheme, indication)`
- `SaltDynamicColors(light = ..., dark = ...)`
- `SaltColors.defaultLight(...)` / `defaultDark(...)`
- `saltTextStyles(...)`
- `SaltDimens.default(item, itemIcon, padding, subPadding)`
- `SaltShapes.default(small, medium, large)`
- `SaltTheme(..., material = SaltMaterial(type = MaterialType.None), content = ...)`

`SaltMaterial` / `MaterialType.None` 的精确构造以 3.0.0 源码和编译结果为准；若稳定重载无法关闭材质，则停止 spike，不通过复制全局默认主题绕过。

适配器边界：

1. SaltUI 类型只能出现在 debug adapter 内；
2. fixture 不直接读取 `SaltTheme.colors` 作为页面颜色权威；
3. 不向 `LocalAppSkin` 或 `LocalMotionTokens` 写回；
4. 系统“移除动画”开启时，SaltUI 固定动画不会自动归零，必须记录为差异，不得伪装成已适配。

### S3：A/B 内容

fixture 分三区：

1. **点击行**：现有 `CardGroup.item` vs SaltUI `Item`；
2. **开关行**：现有 `Row.toggleable(Role.Switch)` + 非交互 Switch vs `ItemSwitcher`；
3. **unstable 观察区**：`LabelValue`、`PillButton`，只验证编译、语义和布局，不作为采用候选。

每个组件覆盖 enabled / disabled、单行与多行副文、长中文值、200% 字体、RTL。皮肤至少切换 default 与浅蓝，明暗模式分别验证。

---

## 7. 语义与无障碍契约

| 组件 | 风险 | Spike 要求 |
|------|------|-----------|
| `Item` | 源码 `clickable` 未显式传 `Role.Button` | 外层用 `Modifier.semantics { role = Role.Button }` 补足，不叠加第二个 `clickable`；TalkBack 播报“按钮”，验证只有一个点击节点 |
| `ItemSwitcher` | 整行已有 `Role.Switch`，内部 Switcher 只绘制 | 保持单节点；点击整行只触发一次 `onChange(!state)` |
| `Switcher(state, onChange)` | unstable 且无显式 role | 不放入推荐区；若测试，外层补 role 并记录 API 风险 |
| `LabelValue` | 固定 96dp，长标签/大字体可能截断 | 只记录失败，不通过压缩字体掩盖 |
| `PillButton` | 有 `Role.Button`，但 `subMaterial` 路径较重 | 验证禁用态、触控尺寸和 TalkBack |
| 全部 | SaltUI 默认 `subText` 对比度不一定满足本仓皮肤 | 使用适配器后的实际 ColorScheme 重新测 4.5:1 / 3:1 |

最低触控区域按 Android 48dp 验收；SaltUI 的 56dp 行高有帮助，但 46×26dp 的开关自身不是独立触控目标，必须依赖整行。

---

## 8. 验证矩阵

### 8.1 编译与依赖

- `:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`；
- `:app:dependencyInsight` 核对 SaltUI、CMP、Material3、Haze、HiddenApiBypass；
- release APK 中不存在 SaltUI；
- 对比前后 release APK/AAB 与依赖报告，确认 debug 依赖未泄漏；HiddenApiBypass 若已由其他依赖引入，记录基线增减，不能用简单 `exclude` 掩盖；
- 生产 R8 `mapping.txt` 与包体增量不在 debug-only spike 范围内，须另立获批准的测量构建。
- 不修改 `dependenciesInfo` 设置。

### 8.2 Instrumented

新增 `SaltUiSpikeTest.kt`，沿用显式 Activity Intent 宿主：

- `Item` 点击 callback 恰好一次；
- `ItemSwitcher` 状态 On/Off 与 `Role.Switch` 正确；
- 内部 Switcher 不产生第二个 toggleable 节点；
- disabled 不触发 callback；
- 200% 字体无截断、无横向溢出；
- RTL 下滑块方向与整行布局正确；
- default / 浅蓝、深色 / 浅色截图；
- 旋转与重建后 `rememberSaveable` 状态保持。

参考现有测试宿主模式：[AppSkinRenderingTest.kt](../../../app/src/androidTest/java/app/androidrik/rikkahub/ui/theme/AppSkinRenderingTest.kt)、[SkinLargeFontAcceptanceTest.kt](../../../app/src/androidTest/java/app/androidrik/rikkahub/ui/theme/SkinLargeFontAcceptanceTest.kt)。

### 8.3 真机与性能

- API 31 / 33 / 35 至少各一台；当前已知可用设备为 API 35；
- TalkBack 手动播报；
- 系统移除动画开启/关闭；
- 连续快速切换 100 次，记录掉帧、ANR 与内存；
- FrameMetrics/PSS 与现有 `SkinPerformanceAcceptanceTest` 同口径；
- 7000 节点长会话回到 fixture，确认无新增卡顿。

### 8.4 发布与合规

- 审计 HiddenApiBypass 的真实调用路径；不得仅用 `exclude` 掩盖；
- 审计 SaltUI、Haze、HiddenApiBypass 许可证与 NOTICE；
- 保留 Google Play SDK 风险提示；若官方要求关闭 dependenciesInfo 才能发布，则生产方案直接否决；
- 不用 SF Symbols，不引入 SaltUI navigation。

---

## 9. 生产准入门槛

只有同时满足以下条件，才能另立生产提案：

1. **收益明确**：相同内容下，用户可感知的层级、密度或反馈优于现有组件，并有截图/任务成功数据；
2. **API 稳定**：不依赖 unstable 公共入口；若 SaltUI 升级导致编译破坏，可独立回滚；
3. **主题单一**：所有页面仍只读 AppSkin/M3，SaltTheme 只存在于一个适配器内；
4. **动效合规**：Switcher 固定时长能尊重系统移除动画与 AppSkin motion；做不到则不入生产；
5. **无障碍通过**：单节点语义、48dp 触控、RTL、200% 字体、TalkBack 全过；
6. **合规通过**：HiddenApiBypass 调用路径可解释或可移除，dependenciesInfo 不关闭；
7. **性能通过**：给出 R8 后包体增量、冷启动、长列表、FrameMetrics/PSS；
8. **可移除性**：限定页面、独立提交，不改默认皮肤、聊天管线和输入栏；
9. **用户批准**：生产依赖与页面迁移另行拍板。

---

## 10. 回滚与产物

### 回滚

删除以下内容即可回到无 SaltUI 状态：

- 版本目录中的 SaltUI alias；
- `debugImplementation(libs.salt.ui)`；
- debug adapter / fixture；
- Spike instrumented tests；
- `SketchTestHostActivity` 的新 intent extra 分支。

不涉及数据库、DataStore、AppSkin JSON、聊天绑定或路由迁移。

### 产物

1. 本设计稿；
2. 工具链升级验证记录；
3. Spike 独立提交（若获批准实施）；
4. A/B 截图与语义树；
5. 依赖、许可证、release 排除与 debug 性能报告；生产 R8/包体报告仅在另行批准的测量构建中产出；
6. 最终决策：**不采用 / 仅借鉴形态本地实现 / 限定页面采用**。

---

## 11. 待拍板项

1. 是否批准 debug-only SaltUI 3.0.0 spike；
2. fixture 是否复用 `SketchTestHostActivity`，还是新增独立 debug Activity；
3. 是否把 `LabelValue` / `PillButton` 纳入 unstable 观察区；
4. 生产默认决策是否维持“不直接依赖，优先本地实现形态”；
5. HiddenApiBypass 与 dependenciesInfo 是否作为一票否决项；本稿建议是；
6. debug-only spike 若通过交互与无障碍验收，是否另立含 SaltUI 的临时测量构建，以取得 R8、APK/AAB 与冷启动数据；未批准前不把依赖加入 release。

---

## 12. 来源

- [SaltUI 3.0.0 Release](https://github.com/Moriafly/SaltUI/releases/tag/3.0.0)
- [SaltUI 3.0.0 README](https://github.com/Moriafly/SaltUI/blob/3.0.0/README.md)
- [Item.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/Item.kt)
- [Switcher.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/Switcher.kt)
- [LabelValue.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/LabelValue.kt)
- [PillButton.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/button/PillButton.kt)
- [SaltTheme.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltTheme.kt)
- [SaltConfigs.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltConfigs.kt)
- [SaltColors.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltColors.kt)
- [SaltDimens.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltDimens.kt)
- [SaltShapes.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltShapes.kt)
- [SaltTextStyles.kt](https://github.com/Moriafly/SaltUI/blob/3.0.0/ui2/src/commonMain/kotlin/com/moriafly/salt/ui/SaltTextStyles.kt)
- [Android AAR POM](https://repo.maven.apache.org/maven2/io/github/moriafly/salt-ui-android/3.0.0/salt-ui-android-3.0.0.pom)
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
- [Android 非 SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)
- [Google Play dependenciesInfo](https://developer.android.com/build/dependencies#dependency_information_for_play_console)
