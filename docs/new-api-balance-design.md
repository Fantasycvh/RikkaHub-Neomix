# New API 真实余额计算——方案设计

> 状态：**待启动**（2026-08-17 记录，用户安排时间后立项）
> 关联：`BalanceOption`（`ai/.../provider/ProviderSetting.kt:26`）、`evaluateJsonExpr`（`common/.../http/JsonExpression.kt:58`）

---

## 问题

New API 的「真实余额」需要计算：**真实余额 = 总充值 − 已使用**。

但当前 `BalanceOption` 只支持**单接口 + 单 JSON 节点取值**（`apiPath` + `resultPath`），无法做来自两个不同 API 的减法。

### 后端接口

| 所需数据 | 接口 | 字段 |
|---|---|---|
| 总充值额度 | `/api/user/self` | `quota` |
| 已使用额度 | `/dashboard/billing/usage` | `total_usage` |
| 最终结果 | — | `quota - total_usage` |

### 当前配置的局限

网上常见的配置方式（不推荐）：
```
余额API路径：/dashboard/billing/usage
JSON结果键：total_usage/100
```
这显示的是**累计已使用额度**，不是真实余额。`BalanceOption` 只有一个 `apiPath`（单 URL）+ 一个 `resultPath`（单 JSON 路径），无法表达「从 A 接口取 quota，从 B 接口取 total_usage，然后相减」的逻辑。

---

## 方案：扩展 BalanceOption 支持多数据源

### 核心思路

`evaluateJsonExpr` 已经是一个完整的表达式求值器（支持 `+` `-` `*` `/` 四则运算、括号、路径导航、数组索引），**差的是多接口数据合并**。

扩展 `BalanceOption`：新增 `sources` 字段（多接口配置列表）+ `expression` 字段（最终表达式）。**旧字段保留不动，向后兼容**。

### 数据模型

```kotlin
@Serializable
data class BalanceOption(
    // 旧字段（保持兼容，sources 为空时走旧逻辑）
    val enabled: Boolean = false,
    val apiPath: String = "/credits",
    val resultPath: String = "data.total_usage",

    // 新字段：多接口模式
    val sources: List<BalanceSource> = emptyList(),
    val expression: String = "",
)

@Serializable
data class BalanceSource(
    val name: String,       // 变量名，供 expression 引用（如 "quota"）
    val apiPath: String,    // API 路径（如 "/api/user/self"）
    val resultPath: String, // JSON 取值路径（如 "quota"）
)
```

### 执行流程

`getBalance` 修改后：

```
if (sources.isEmpty()) {
    // 旧逻辑：单接口 → 直接求值
    val json = callApi(apiPath)
    evaluateJsonExpr(resultPath, json)
} else {
    // 新逻辑：多接口 → 逐个调用 → 合并为扁平 JSON → 求值
    val merged = buildJsonObject {
        for (source in sources) {
            val json = callApi(source.apiPath)
            val value = evaluateJsonExpr(source.resultPath, json)
            put(source.name, JsonPrimitive(value.toDoubleOrNull() ?: value))
        }
    }
    evaluateJsonExpr(expression, merged)
}
```

### New API 配置用例

```kotlin
BalanceOption(
    enabled = true,
    sources = listOf(
        BalanceSource("quota", "/api/user/self", "quota"),
        BalanceSource("usage", "/dashboard/billing/usage", "total_usage"),
    ),
    expression = "quota - usage",
)
```

### 兼容性

| 场景 | 是否兼容 |
|---|---|
| 现有配置（硅基流动/DeepSeek/月之暗面等） | ✅ `sources` 默认空 → 走旧逻辑，完全不变 |
| OpenRouter 默认配置（`"data.total_credits - data.total_usage"`） | ✅ 单接口同对象减法，已在旧逻辑内支持 |
| 用户自定义 `apiPath` + `resultPath` | ✅ 不受影响 |
| 序列化旧备份恢复 | ✅ `sources = emptyList()` 默认值，反序列化兼容 |

---

## 实现要点

### 1. 数据层改动

| 文件 | 改动 |
|---|---|
| `ai/.../provider/ProviderSetting.kt` `BalanceOption` | 加 `sources: List<BalanceSource>` + `expression: String` |
| 同上 | 新增 `BalanceSource` data class |
| `ai/.../provider/providers/OpenAIProvider.kt` `getBalance` | `sources` 分支：多接口调用 → 合并 → 求值 |
| `app/.../datastore/DefaultProviders.kt` | 加 New API 默认配置（含 `sources` + `expression`） |

### 2. UI 层改动（可选，可先不做）

| 组件 | 改动 |
|---|---|
| `app/.../setting/components/BalanceOption.kt` | 新增 `sources` 列表编辑（增删改 BalanceSource 项） |
| 设置页 "余额 API 路径" 输入框 | 保持兼容；新增 "表达式" 输入框（expression 字段） |

**UI 简化方案**：如果先不做 UI，可以把 `sources` 和 `expression` 在 `DefaultProviders.kt` 里硬编码，UI 只显示最终余额值。用户如需自定义再补 UI。

### 3. 串行 vs 并行

多个 sources 的 API 调用可以**并行**（`async/await`），因为彼此无依赖：
```kotlin
val results = sources.map { source ->
    async { source.name to callAndExtract(source) }
}.awaitAll()
```

### 4. 错误处理

- 任一 source 调用失败 → 整体返回错误（不显示残缺余额）
- 表达式求值失败（如字段不存在、类型不匹配）→ 返回错误提示

---

## 工作量评估

| 项 | 规模 |
|---|---|
| `BalanceOption` + `BalanceSource` 数据类扩展 | 小（~10 行） |
| `getBalance` 多接口分支 | 中（~30 行 + 并行调度） |
| New API 默认配置 | 小（~10 行） |
| UI 层（可选） | 中（可复用现有组件的编辑模式） |
| **总计** | **中**（2-3 小时纯数据层，加 UI 再加 1-2 小时） |