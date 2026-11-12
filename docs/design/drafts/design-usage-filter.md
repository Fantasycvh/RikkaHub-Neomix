# 用量统计筛选：时段（含自定义区间）+ 提供商 + 模型

> 状态：**实现中**（Phase 1 数据层 + VM 已落地，本地单测全绿；Phase 2 UI 待实现）。2026-11-12。
> 实现备注：`UsageRange.CUSTOM` 档在 Phase 1 已进枚举但不在时段选项列表里（保持可见行为不变），
> Phase 2 接入日期区间选择器时再加入选项。
> 前置：[design-usage-stats.md](../completed/design-usage-stats.md)（usage_record 流水表，DB v44）。
> 三个筛选维度**叠加生效（AND）**，只作用于「用量」区；热力图与总览卡片不受影响（沿用
> 「热力图固定 52 周窗口、与时间范围无关」的现有边界）。

## 1. 现状与缺口

`StatsVM` 现有唯一筛选是 `UsageRange` 枚举（7/30/90/全部），DAO 四个聚合查询都只吃 `since`
一个时间参数。`usage_record` 表已存 `provider_id/provider_name/model_id/model_name`，只是
读侧没有暴露这两个维度的过滤——**不需要任何 schema 变更，纯读侧扩展**。

## 2. 筛选模型

```kotlin
data class StatsFilter(
    val range: UsageRange,          // WEEK/MONTH/QUARTER/ALL/CUSTOM
    val customStart: LocalDate?,    // 仅 CUSTOM 时有效
    val customEnd: LocalDate?,      // 含端点（闭区间）
    val providerKey: ProviderKey?,  // null = 不筛选
    val modelKey: ModelKey?,        // null = 不筛选
)
data class ProviderKey(val id: String?, val name: String?)   // id 优先，回填行按名称兜底
data class ModelKey(val id: String?, val name: String?)
```

- `UsageRange` 增加 `CUSTOM(null)` 档；`days` 语义不变（null = 由 custom 字段或「全部」决定）。
- VM 用 `_filter: MutableStateFlow<StatsFilter>` 替换现在的 `_range`；任一字段变化即重查
  （复用现有 `usageJob?.cancel()` 防抖模式）。
- `UsageStats` 已带 `range` 字段防旧数据错位，扩展为带整个 `StatsFilter` 快照——切换筛选时
  旧数据保留到新查询返回，页面按快照判断「这份数据属于哪组筛选」（沿用现有设计意图）。

### 兜底匹配（回填行）

43→44 回填行 `provider_id`/`model_id` 为 NULL、只有名称快照，SQL 匹配用 id 优先 + 名称兜底：

```sql
AND (:pid IS NULL OR provider_id = :pid
     OR (provider_id IS NULL AND provider_name = :pname))
```

模型同理（`model_id`/`model_name`）。名称兜底可能把「不同提供商下同名模型」误并入，
可接受——回填数据本来就只有快照可依。

## 3. DAO 改动

`UsageRecordDAO` 四个聚合查询（`getTotals` / `getTotalsByBucket` / `getTotalsByModel` /
`getTotalsBySource`）统一加可选参数：

| 参数 | 类型 | 语义 |
|---|---|---|
| `since` | `Long`（保留，默认 0） | 下界（含） |
| `until` | `Long?` | 上界（含），null = 不限 |
| `providerId` / `providerName` | `String?` | null = 不筛选，见 §2 兜底匹配 |
| `modelId` / `modelName` | `String?` | 同上 |

- 上界取 `customEnd + 1 day` 的 0 点（`created_at < until`）或直接 `created_at <= endOfDay`，
  实现时二选一并写明；推荐 `>= since AND < untilExclusive`，避免毫秒精度踩边。
- 维持「聚合在 SQLite 侧完成、不回传流水」的既有原则；`created_at` 索引仍覆盖区间条件，
  provider/model 过滤在索引命中的行上就地判断，不新增索引（表量级下没必要）。

## 4. 分桶规则

- 预设档不变：7/30/90 按天补 0，「全部」按月。
- **自定义区间：跨度 > 90 天自动按月分桶**（与 QUARTER 档阈值对齐），否则按天。
  `UsageStats.byMonth` 由 VM 按此规则计算，图表代码零改动。
- 自定义区间的趋势图按天补 0 只补区间内的日子，逻辑与现 `dailyPoints` 一致（start=customStart）。

## 5. 筛选选项来源

提供商/模型下拉的选项**取自 `usage_record` 的 DISTINCT 查询**，而不是当前设置里的
provider/model 列表——筛选的用途是分析历史花费，模型/提供商可能已删除，从设置取会把
它们排除在外。新增两个轻量查询：

```sql
SELECT DISTINCT provider_id AS id, MAX(provider_name) AS name FROM usage_record GROUP BY COALESCE(provider_id, provider_name)
SELECT DISTINCT model_id AS id, MAX(model_name) AS name FROM usage_record GROUP BY COALESCE(model_id, model_name)
```

展示名解析沿用排行逻辑：id 能在当前设置里查到 → 用最新名；查不到 → 名称快照
（都拿不到 → 「未知模型/提供商」）。选项是否限定在当前时间区间内（`since/until` 也传入）？
**是**——区间变了选项跟着变，保证选了必有数据。仅返回有 id 或有名称的行（`id IS NOT NULL OR name IS NOT NULL`）。

## 6. UI

「用量」区头部从单行 SegmentedPill 扩成三行（都在 LazyColumn 常驻 item 内，无 IME 陷阱）：

1. **时段**：现有 `SegmentedPill` 末尾加「自定义」档；选中自定义时该档显示当前区间
  （如 `11-01~11-12`），点击弹出日期区间选择器。
   - 选择器用 **M3 `DateRangePicker`**，宿主用 ModalBottomSheet（`verticalScroll` 子项常驻）
     或整页 dialog，**不用 AlertDialog**（AGENTS.md TextField 宿主规则 3；DateRangePicker 自带
     日期输入文本框）。
   - 确认前可取消（sheet 下滑 = 放弃修改，恢复原筛选）。
2. **提供商**：`FilterChip`（未选中显示「全部提供商」），点开选项列表（选项 ≤ 8 用下拉菜单，
  更多用 sheet 列表——实现时按选项数量自适应）。已选时 chip 上显示提供商名 + 清除按钮。
3. **模型**：同上，chip 文案「全部模型」/ 模型名。

筛选行只在「用量」标题下、合计卡之上；筛选生效中时段行下方无需额外摘要（chips 本身就是摘要）。
无数据组合（如某模型在该区间无用量）：totals 全 0 时已有 `EmptyUsageCard` 兜底，文案不改。

## 7. 字符串资源

新增 `stats_page_*` 键（约 8 个）：自定义档、日期区间占位、全部提供商、全部模型、
未知提供商、确认/清除等。en 默认 + zh + zh-rTW，实现阶段用 locale-tui 流程补齐。

## 8. 测试

- 仪器测试 `UsageRecordDAOTest` 扩展：until 上界、provider/model 单独与组合过滤、
  id 为 NULL 的回填行按名称兜底命中、全筛选项为 null 时不影响原结果。
- `StatsVM` 逻辑：CUSTOM 档分桶阈值（>90 天按月）、筛选变化触发重查、快照防错位。
- 真机验收：切自定义区间（跨月/不跨月各一次）、选已删除的模型、三筛选叠加、
  回填数据（升级前的旧会话用量）能否按名称筛中。

## 9. 实施切分

| 阶段 | 内容 | 可独立验证 |
|---|---|---|
| Phase 1 | DAO 过滤参数 + DISTINCT 选项查询 + VM `StatsFilter` 状态机 + 分桶规则（UI 仅暴露时段预设档，行为与现在等价） | 仪器测试 + 编译 |
| Phase 2 | 自定义区间选择器 + 提供商/模型 chips + 字符串资源 | 真机全量验收 |

Phase 1 不改任何用户可见行为（预设档行为不变），Phase 2 才是可见变更——出问题可单独回退 UI 层。

## 10. 已知边界

- 回填行的提供商筛选按名称快照兜底，同名不同商可能误并入（§2）。
- 筛选不作用于热力图/总览/启动次数（有意的范围界定；若以后要联动，热力图需按筛选重查 52 周窗口，成本另议）。
- 选项列表随区间变化：先选提供商再缩小区间可能导致该提供商在新区间无数据，totals 显示 0 属正常。
- `until` 用半开区间实现，选择器里选同一天 = 单日数据（含当天全天）。

## 关联

[design-usage-stats.md](../completed/design-usage-stats.md) · StatsVM.kt / StatsPage.kt / UsageRecordDAO.kt
