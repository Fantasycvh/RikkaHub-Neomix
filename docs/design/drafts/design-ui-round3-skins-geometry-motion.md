# UI 第三轮改造设计稿：皮肤档位 / 气泡几何 v2 / IA 三件 / motion tokens

> 状态：**M1 契约层已实施（2026-11-14，工作区未提交）**：§2.1/§2.2/§2.4/§2.5 落地 + §2.3 核实为已存在（ChatFontFamily.SERIF 全链路在仓，零代码）。全量单测绿、audit A=0/A'=0、真机冒烟（主页/聊天/设置五页）0 FATAL。渲染接线挂账：maxWidthFraction（M2 皮肤档一起）、advancedGroup 页面消费（M3）、motion 散值收编（M4）。
> 上游：[design-ui-overhaul-research.md](design-ui-overhaul-research.md)（第一轮调研 + §10 dsh-go 补析）
> 视觉对比稿：`原型/RikkaHub-UI第三轮-视觉对比稿.html`（用户已按稿拍板）
> 硬约束沿用调研稿 §6：IME 宿主规范、supportingContent 单子节点坑、皮肤契约、长文本性能、布局稳定性。

---

## 0. 拍板记录（2026-11-14，用户逐项确认）

| # | 拍板点 | 决定 |
|---|--------|------|
| 1 | 皮肤档位取舍 | 首轮做 **HyperOS squircle + 纸质感** 两档；工程感 / iOS 风后续批次 |
| 2 | serif 字体族 | **系统衬线起步**（不加内置字体包体），皮肤 resources 自定义字体导入兜底 |
| 3 | squircle 连续曲率 | **值得做**：AppSkinShapes 加 `continuous` + 自绘 SmoothCorner path |
| 4 | 气泡几何 v2 字段集 | 按提案执行（tailCornerDp / maxWidthFraction / padding 横竖拆分） |
| 5 | IA 三件 | 按提案执行：盘点表先行，首批覆盖 偏好设置 / 显示 / 模型设置 三页 |
| 6 | motion TOKENS 档 | 通过；散值收编首批含 chatInputIconFade 200→160ms |

---

## 1. 分阶段计划

| 阶段 | 内容 | 依赖 |
|------|------|------|
| M1 契约层 | ChatBubbleGeometry v2、AppSkinMotionTokens、ChatFontFamily.SERIF、AppSkinShapes.continuous、CardGroup trailingValue + 高级折叠段 | 无，先行 |
| M2 皮肤两档 | squircle / 纸质感两个 AppSkinPreset + 聊天域配置消费 M1 全部新 token | M1 |
| M3 IA 三件 | IA 盘点表 → trailingValue 铺三页 → 一项一家收敛 → 低频收高级 | 无（可与 M2 并行） |
| M4 散值收编 | LocalMotionTokens 暴露 + chatInputIconFade 等首批散值对齐 | M1 |

每阶段独立可真机验证、可单独回滚（皮肤档走 SkinTrialController 试用/撤销）。

## 2. 契约变更清单（逐项）

### 2.1 ChatBubbleGeometry v2（data/model/ChatAppearance.kt）

```kotlin
data class ChatBubbleGeometry(
    val radiusDp: Float = 16f,
    val tailCornerDp: Float? = null,      // 新增：尾角收紧，映射到 userAlignEnd 侧下角；null=对称四角
    val maxWidthFraction: Float = 1f,     // 新增：0.5–1.0，气泡内容宽度上限（气泡级，全局 contentWidthFraction 不变）
    @Deprecated("迁移字段：读取时拆入横竖两项，写出不再使用") val paddingDp: Float = 8f,  // 保留避免旧 JSON unknown key
    val paddingHorizontalDp: Float = 8f,  // 新增
    val paddingVerticalDp: Float = 8f,    // 新增
)
```

- 迁移：读取时若 `paddingDp != 8f` 且横竖仍为默认 8f，则拆分为横竖同值；`normalized()` 不再回写 `paddingDp`。
- 尾角实现走**逐角圆角**（CornerSize 覆盖 userAlignEnd 侧下角），不画 tail path（非连续曲率渲染与裁剪问题不引入）。
- `maxWidthFraction` 只影响测量宽度约束，不动增量渲染管线（longtext 系列约束）。

### 2.2 motion token（data/model/AppSkin.kt + ui/theme/AppSkinTheme.kt）

```kotlin
@Serializable
data class AppSkinMotionTokens(
    val pressMs: Int = 90, val quickMs: Int = 160, val standardMs: Int = 240,
    val collapseMs: Int = 280, val emphasizedMs: Int = 340,
    val sheetInMs: Int = 380, val sheetOutMs: Int = 260, val slowMs: Int = 480,
    val breathMs: Int = 2400,
    // 弹簧档位（stiffness/damping 由时长+bounce 推导或直接存 stiffness/damping，实施时定）
    val layoutSpringMs: Int = 350, val layoutBounce: Float = 0f,
    val flingSpringMs: Int = 500, val flingBounce: Float = 0.15f,
)
```

- `AppSkin` 新增可选字段 `motionTokens: AppSkinMotionTokens? = null`；`AppSkinMotion` 枚举加 `TOKENS` 档，仅当 `motion == TOKENS` 时消费 `motionTokens`（null 则回落 EXPRESSIVE）。**老皮肤 JSON 零迁移**（新字段可选）。
- `AppSkinTheme` 暴露 `LocalMotionTokens`；取值口统一判断 `Settings.Global.ANIMATOR_DURATION_SCALE == 0` → 全部归 0；`breath` 等循环动画判 reduced 停静止帧。
- **弹簧只喂位移/缩放，透明度永不越界**（dsh-go 纪律照抄）。
- 不在替换清单：思维链短 tween（perf 决策）、morph/块淡入已有弹簧调用、`animateContentSize` 存量（后续批次按需）。

### 2.3 serif 字体族（ui/theme/ChatFont.kt / data/datastore ChatFontFamily）

- `ChatFontFamily` 新增 `SERIF`：映射链 `Georgia / "Noto Serif SC" / "Source Han Serif SC" / serif`（拉丁走 Georgia，中文系统衬线，混排基线需真机核对行高）。
- 皮肤 `resources` 自定义字体导入（已有能力）可覆盖该映射；**不内置字体文件**。
- 纸质感档 `expressive = false`（GSF emphasized 与衬线气质冲突，且 CJK 本就 fallback）。

### 2.4 squircle 连续曲率（ui/theme/ + 新组件）

- `AppSkinShapes` 新增 `continuous: Boolean = false`（normalized/overrides/试用链路同步；老皮肤默认 false 零影响）。
- 新增 SmoothCorner Shape 实现（自绘 path，n≈4~5 曲率指数；**path 按尺寸缓存**，避免每帧重建；对齐 AppLogo.MorphShape 的 toPath scale(w,h) 经验）。消费点：卡片、按钮、输入框、顶栏胶囊——凡走 `MaterialTheme.shapes` 的宿主。
- 性能红线：shape path 缓存键 = (size, radius)；列表项慎用（首轮只上卡片/容器层，列表内维持圆弧圆角，观察帧率后再铺开）。

### 2.5 CardGroup 扩展（ui/components/ui/CardGroup.kt）

- `item(...)` 新增 `trailingValue` 槽：**一律 Column 包一层**（supportingContent 单子节点坑），chip 形态：布尔 →「开/关」，枚举 → 短词，超宽截断。
- 新增可折叠「高级」段：纯 `rememberSaveable` 状态折叠，CardGroup 是 Column 实现（天然免疫 Lazy IME 坑），**不引入 Lazy 宿主**；首页不放放宽保护/删数据控件。
- 搜索联动为**条件项**：仅当设置页搜索存在时做「命中折叠项自动展开高亮」，不存在则挂账不阻塞。

## 3. 皮肤两档 token 终值（消费 M1）

### 3.1 HyperOS squircle（新增 builtin preset）

| 组 | 值 |
|----|----|
| palette | 留空继承动态色（连续曲率 + 玻璃是气质来源；灰蓝面为 mockup 示意，不进 preset） |
| typography | expressive 开（GSF emphasized） |
| shapes | xs 8 / sm 12 / md 18 / lg 24 / xl 32 · cardGroup 外 28 内 6 · 按钮 28 · **continuous=true** |
| spacing | page 12 / section 18（默认档微调） |
| motion | EXPRESSIVE（弹簧） |
| 聊天域 | user 气泡 GLASS 材质 + `pageGlassEffectType` 建议值 GLASS（用户可覆盖） |

### 3.2 纸质感（新增 builtin preset）

| 组 | 值 |
|----|----|
| palette | CustomTheme：暖纸底 `#F7F2E9` / 卡片 `#FBF8F1` / 主色赭 `#A0522D`（AMOLED 纯黑覆盖自动继承） |
| typography | family=SERIF + sizeRatio 1.05，expressive 关 |
| shapes | xs 4 / sm 6 / md 10 / lg 14 / xl 20 · cardGroup 外 18 内 4 · 按钮 12 · continuous=false |
| spacing | page 12 / section 20 / formV 14 / formGap 16（舒展档系） |
| motion | STANDARD |
| 聊天域 | `assistantBubble.visible = false`（平铺，**现有字段零成本**）+ user 气泡 `#EBDFC9`、radius 14、tail 5 |

两档均为 `AppSkinPreset` 注册进 `builtInAppSkins`，默认皮肤不动；换肤走皮肤编辑器 + 试用/回滚。

## 4. IA 三件实施细则

1. **盘点表先行**（M3 第一步，产出 `docs/design/verification/` 对照表）：列 = 设置项 / 出现的家数 / 使用频率 / 影响面 / 是否收高级；套 dsh-go 七规则逐行过。
2. **每行显值**：首批铺 偏好设置 / 显示 / 模型设置 三页；布尔行值 chip 用「开/关」色区分，枚举行值短词（如 BLUR/GLASS、动态色）。
3. **一项一家**：Expressive 排版唯一家 = 皮肤编辑器；显示设置原位置改**同名跳转入口**（入口可多、名字必须相同）。其余双家项以盘点表为准逐个收敛。
4. **低频收高级**：输入栏图标淡入、页面顶栏玻璃等低频项按盘点表结论收进折叠段；玻璃效果类型随母开关折叠显示（现有交互保持）。

## 5. 散值收编首批（M4）

| 现状 | 目标 |
|------|------|
| chatInputIconFade `tween(200)` | `LocalMotionTokens.quick`（160ms），行为感知略加快，设置项不变 |
| 块淡入 blockFadeIn 240ms | 引用 `standard` 常量（数值不变，仅收编） |
| 页面级过场散值 | `slow`（480ms）；无统一 token 的按压反馈接 `press` |

## 6. 真机验证矩阵（PJX110）

| 类别 | 项 |
|------|----|
| 静态 | `python scripts/audit_ime_trap.py` A/A' 零命中；`python -B scripts/test_audit_ime_trap.py`；全量单测 |
| 兼容 | 老皮肤 JSON 回读（paddingDp 迁移 / motionTokens 缺省）；AppSkinOverrides withEdits 组级覆写回归 |
| 皮肤 | 两档试穿/撤销（SkinTrialController）；squircle continuous 卡片/按钮/输入框渲染 + 列表帧率观察；纸质感中英混排基线行高 |
| 聊天域 | 气泡几何三预设切换（尾角/限宽/平铺）；GLASS 用户气泡 + 页面顶栏玻璃叠加 |
| IA | 值 chip 读屏（clearAndSetSemantics 不误报）；高级段折叠展开；三页逐行核对 |
| 动效 | 系统设置「移除动画」开启 → 全部时长为 0；动效开关关闭全静态；AMOLED 开关验证（**顺带收方向 C 挂账**） |
| IME | 中文 IME 注入回归（输入栏拓扑本轮未动，例行防回归） |

## 7. 明确不做 / 后续批次

- 工程感 / iOS 风皮肤档（描边卡 token、inset 分隔线形态随批）
- tail path、逐字打字机、正文扫光（dsh-go 反对 + 第一轮已划界）
- 设置页全面 Bento（维持 E 收窄版结论）
- IA 全仓铺开（首批三页验证后再扩）
