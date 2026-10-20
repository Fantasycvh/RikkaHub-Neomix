# 设置页全局搜索设计

## 1. 问题

设置项分散在 20 多个页面里（`ui/pages/setting/` 23 个 `*Page.kt`，外加助手、扩展、备份等页面），
用户经常「知道有这功能，但不知道藏在哪一页」。设置根页只能逐层点进去，长页面（常规 39 项、界面 19 项）
还要滚很久。

目标：**在设置页任意位置一次搜索，直接定位到具体设置项**，而不是只搜页面名。

## 2. 方案取舍

| 方案 | 评价 |
|---|---|
| A. 只搜页面标题（20 条） | 成本最低，但「深色模式」「代理端口」这类具体项搜不到，价值有限 |
| B. 静态索引「页面 + 分组 + 条目」三元组（✅ 采用） | 覆盖具体设置项；新增项要手动补一条，可接受 |
| C. 运行时反射/遍历 Compose 树建索引 | 设置页大量条件渲染与动态列表，抓不全，且会把不可见项也索引进来，反而更迷惑 |
| D. 每个设置页内各自带搜索框 | 用户得先猜页面，与本需求相反 |

选 B：索引是**静态数据**（`SettingSearchIndex.entries`），匹配是**纯函数**（`SettingSearchMatcher`），
UI 只负责输入与跳转。三者解耦，匹配逻辑可以单测、索引可以脚本生成。

## 3. 结构

```
ui/pages/setting/search/
├── SettingSearchMatcher.kt   纯匹配逻辑（可单测，不碰 Compose/资源）
├── SettingSearchIndex.kt     索引数据：页面/分组/标题/描述/别名/目标路由（151 条）
└── SettingGlobalSearchPage.kt 搜索页：输入框 + 结果列表 + 空态
```

- 路由：`Screen.SettingGlobalSearch`；入口：设置根页与「偏好设置」页顶栏的搜索图标；
- 索引条目字段：`title` / `description` / `pageTitle` / `section`（都是 **string 资源 id**，按当前语言匹配与展示）+ `route` + `keywords`（额外别名）。

## 4. 匹配规则

- 查询按空白切成 token，**全部 token 命中**才算命中（AND）；
- 每个 token 在「标题 > 别名 > 描述」中取最高分：
  - 标题完全相同 120 > 标题前缀 90 > 标题包含 70 > 别名相同 65 > 别名包含 55 > 描述包含 30；
- 大小写不敏感；中文天然子串匹配；
- 不做模糊纠错（拼错/拼音缩写）：宁可搜不到，也不给一堆似是而非的结果；
- 结果按分数降序、同分保持索引顺序（即页面顺序），最多 80 条。

别名（`keywords`）是召回率的关键：例如「theme / zhuti / 皮肤」都能命「主题」，「proxy / 翻墙 / 代理端口」
都能命网络代理，「tts / 语音识别 / 听写」能命语音页。

## 5. 结果与跳转

- 结果行显示「条目标题 + 页面 › 分组」面包屑；页面名与标题重复时省略面包屑（避免「主题 / 主题」）；
- 点击 → `navigate(entry.route)` 到对应设置页；
- 未输入时给「常去页面」快捷入口（7 个），键盘弹起时用 `FlowRow` + 可滚动，保证入口点得到；
- 无结果时提示换更短关键词。

## 6. 已知限制（v1 刻意不做）

1. **只跳到页面，不滚动/高亮到具体条目**。设置页的 `LazyColumn` 没有稳定锚点，逐页接入锚点是
   20 多个文件的改动，收益不抵成本。用户到页后靠面包屑里的「分组」名自己找（同页通常 1~2 屏）。
2. **条件渲染的条目可能落地后不可见**：代理的主机/端口/账号/密码只在「全局代理 ≠ 无」时渲染；
   主题预设/自定义列表只在「动态颜色关闭」时渲染；「实时更新通知」只在生成通知开启时出现。
   索引里仍保留这些条目（用户搜「代理密码」说明他就是要改它），但落地后需要先满足前置条件。
3. **动态列表不索引**：提供商、搜索服务、知识库、媒体提供商、自定义主题、文件卡片等列表项由用户
   数据决定，静态索引无意义；这些以「页面」为落点。
4. **不做拼音全拼/首字母检索**：需要拼音字典与多音字处理，收益待验证；当前用别名覆盖常见口语说法。
5. 未命中项会「搜不到」——索引是人工维护的，新加设置项必须补索引（见 §7）。

## 7. 维护方式

新增/改名设置项时，在 `SettingSearchIndex.kt` 的 `entries` 里补一条：

```kotlin
entry(
    title = R.string.setting_xxx,              // 条目标题
    route = Screen.SettingPreferencesGeneral,  // 点结果跳到哪
    pageTitle = R.string.setting_page_preferences_general,
    section = R.string.section_xxx,            // 可选：分组标题
    description = R.string.setting_xxx_desc,   // 可选：只在命中描述时展示
    keywords = listOf("别名", "alias", "pinyin"),
)
```

索引生成于 `build/gen_index.py`（扫源码里的 `CardGroup { item(...) }` 自动提取 + 人工盘点补充），
生成结果已入库为普通 Kotlin 文件；**改索引直接改 Kotlin 文件即可**，脚本只是当初的一次性工具。

## 8. 验证

- 单测 `SettingSearchMatcherTest`（11 项）：空白查询、中英文、大小写、别名、描述降权、AND 语义、
  精确/前缀/包含优先级、无结果、带空格、结果上限；
- 真机（1080×2340 / API 35 / ColorOS）：搜 `theme` 4 条、搜 `proxy` 6 条、点「主机地址」正确跳到网络页；
- 全量单测 1970 项、lint 0 error。

## 关联

[design-ui-yuihub-inspired.md](design-ui-yuihub-inspired.md)（设置页与抽屉入口）· `ui/pages/setting/SettingPage.kt`（根页入口）·
`ui/pages/setting/SettingPreferencesPage.kt`（偏好页入口）
