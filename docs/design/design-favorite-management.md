# 收藏管理改造（设计稿）

> 状态：**已实现**（`bb48b375`，2026-09-16）。批次 1（三个缺陷 + 排序）+ 批次 2（标签筛选 + 标签管理页）
> 均已落地并真机验证。**实现取舍**：标签**存 id 而非名**（用户选「两批 + 标签管理页」→ 需要改名能力，
> 而存名值改名要批量更新收藏）；标签池做成 `Settings.favoriteTags`（**独立于 `assistantTags`**）。
> 单测 `FavoriteMetaTagsTest` 8 项；全量 574 项仅基线失败。
> 用户反馈三点：**①可以删除（已可用）②撤销有点问题 ③侧滑难发现**；评估"改造成文件式管理"的难度后，
> **决定采纳轻量替代（标签筛选）而非层级目录**。
> 关联：`design-side-file-panel.md`（同为"扁平+筛选 vs 层级"的取舍）。

## 一、现状核实

### 1.1 数据模型（收藏是「引用」不是「文件」）

`FavoriteEntity`（表 `favorites`）：

| 列 | 说明 |
|---|---|
| `id` | **= refKey**（确定性字符串，非随机） |
| `type` | `node` / `message`(遗留) |
| `ref_key` | `node:<conversationId>:<nodeId>`，**UNIQUE 索引** |
| `ref_json` | `NodeFavoriteRef(conversationId, nodeId)` |
| `snapshot_json` | **恒为空串**（占位，未使用） |
| `meta_json` | `FavoriteMeta(title, subtitle, previewText)` —— **JSON blob** |
| `created_at` / `updated_at` | 时间戳 |

**关键结论**：收藏**没有**层级、没有用户可编辑的名字（`title` 是会话标题快照）、
没有移动/重命名语义（它指向的节点在会话里）。**"文件式"的三要素全缺。**

### 1.2 两个遗留/冗余点

- **`FavoriteType.MESSAGE` 定义了但全仓零写入**（仅 `fromValue` 兼容旧数据），
  且 `FavoritePage` 只展示 `nodeFavorites`（`listByType(NODE)`）→
  **旧数据里若有 MESSAGE 类型收藏，当前不可见**（潜在小缺口，见 §六）。
- **`snapshotJson` 恒空** —— 设计上留了"快照"位但从未写入。

### 1.3 已有能力

| 层 | 能力 |
|---|---|
| `FavoriteDAO` | `upsert`(REPLACE) / `listAll` / `listByType` / `deleteByRefKey` / `deleteById` / `existsByRefKey` |
| `FavoriteRepository` | 上述 + `addNodeFavorite` / `removeNodeFavorite` / `isNodeFavorited` |
| 聊天内 | 收藏按钮 + 两态文案（`chat_message_remove_favorite`）已接线 ✅ |
| 收藏页 | `SwipeableFavoriteCard` 侧滑删除 → `vm.removeFavorite(refKey)` ✅ |

**所以"可以删除"确实可用**，用户三点里这点准确。

### 1.4 两个已定位的缺陷

#### 缺陷 A：撤销的**时序竞态**（fire-and-forget 逃逸）

```kotlin
// FavoritePage.kt:~105
val entity = vm.getEntityByRefKey(item.refKey) ?: return@launch   // ① 挂起，取到实体
vm.removeFavorite(item.refKey)                                    // ② 内部 viewModelScope.launch → 立即返回
val result = snackbarHostState.showSnackbar(...)                   // ③ 等用户点撤销
if (result == SnackbarResult.ActionPerformed) vm.restoreFavorite(entity)  // ④ 又 launch
```

`removeFavorite` / `restoreFavorite` 都是 `viewModelScope.launch { ... }` —— **②④逃逸了当前协程**，
时机不确定。极端情况下 **④（upsert REPLACE）先于 ②（delete）落库 → 撤销被删除覆盖**。

> `HistoryPage` 的删会话也用了同样的 fire-and-forget 形状，但它**没有撤销方向的状态残留**（见缺陷 B），
> 故未暴露。**这是同一个"动作不等结果、却立刻依赖结果"的家族问题**——与 regenerate 撤销那条同源。

#### 缺陷 B：撤销后**滑动状态不重置**（更确定的视觉故障）

`SwipeableFavoriteCard` 的 `dismissState = rememberSwipeToDismissBoxState(initialValue = Settled)`
**未与 item 绑定 key**，而 `LazyColumn` 用 `key = { it.id }` 复用节点。恢复后：

- `dismissState.currentValue` 仍为 `EndToStart`
- `LaunchedEffect(currentValue)` 因值未变**不再触发**
- → 卡片停在"已滑出"位置，**看上去像撤销失败/条目消失**（实际数据已恢复）

#### 缺陷 C：入口隐蔽（用户第三点）

`SwipeToDismissBox(enableDismissFromStartToEnd = false)` → **只能从右往左滑**，
且卡片上无删除按钮、无长按菜单、无任何 affordance → 用户不知道能删。

## 二、已定决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | **采纳轻量替代：标签 + 筛选**，不做层级目录 | 见 §四（层级与 `ref_key` UNIQUE 语义冲突，且收益随收藏量才显现） |
| 2 | **先修三个缺陷**（撤销竞态 / 滑动残留 / 入口隐蔽） | 与"是否做标签"无关，是既存 bug |
| 3 | **排序可切换**（时间倒序 / 标题） | 现固定 `created_at DESC`（写死在 DAO 查询里） |
| 4 | 标签复用既有 `Tag` 形态 | 项目已有 `Tag(id: Uuid, name: String)` + `ASSISTANT_TAGS` 单 key JSON 列表先例 |

## 三、改造范围（分批）

### 批次 1：修缺陷 + 补基础操作（低，约 1 提交）

| 项 | 做法 |
|---|---|
| 撤销竞态 | `removeFavorite` / `restoreFavorite` 改为 **`suspend`**（或让 UI 直接 `await` 仓库调用），**在 snackbar 之前完成删除**；恢复同理 |
| 滑动残留 | 给 `SwipeableFavoriteCard` 的 `dismissState` 加 `key(item.id)`，或在恢复后**显式 `reset()`** |
| 入口隐蔽 | 卡片加**可见删除按钮**（或长按弹出 `DropdownMenu`），保留侧滑作为快捷方式；`enableDismissFromStartToEnd` 可两向开启以降低发现成本 |
| 排序 | 加"排序方式"选项（时间/标题），DAO 增加带 `ORDER BY` 参数的查询或 VM 侧排序 |

### 批次 2：标签 + 筛选（中，约 1 提交）

- 收藏卡片可打标签；顶部一行**标签筛选 chips**（含"未分类"）
- 标签管理（增/改/删）——可复用助手标签的 UI 形态

## 四、为什么不做层级目录（含语义冲突）

若要做"文件夹式"，需面对三个硬问题：

1. **`ref_key` UNIQUE 索引的冲突**：该约束表达"同一节点只能收藏一次"。放入文件夹后，
   "同一节点能否出现在两个文件夹"需要重新定义——若允许，则唯一约束必须改为
   `(ref_key, folder_id)` 复合唯一，**属 DB 迁移**；若不允许，则"移动"语义受限。
2. **"重命名"无对象**：收藏没有自己的名字（`title` 是会话标题快照）。要做文件式就得引入
   `displayName` 字段，且要考虑"会话改名后收藏显示什么"（快照 vs 实时）。
3. **层级导航是全新 UI**：面包屑、拖拽、移动对话框、层级状态机——成本远超"标签筛选"，
   而收益只在收藏量很大时体现。

**对比**：

| 维度 | 标签筛选 | 层级目录 |
|---|---|---|
| DB 迁移 | **不需要**（见 §五） | 需要（唯一约束/新列） |
| UI 新增 | 一行 chips + 打标签入口 | 层级导航 + 拖拽 + 移动对话框 |
| 一个收藏多归属 | 天然支持（多标签） | 需额外设计（硬链接/多重归属） |
| 与既有先例 | **与助手标签同构，可复用** | 无先例，需自建 |

**结论**：标签筛选以更低成本覆盖"分类"这一真实需求；层级留待收藏量证明必要时再议。

## 五、标签的存储方案（关键：零迁移）

**方案：放进 `FavoriteMeta`（即 `meta_json` blob）。**

```kotlin
@Serializable
data class FavoriteMeta(
    val title: String? = null,
    val subtitle: String? = null,
    val previewText: String? = null,
    val tags: List<String> = emptyList(),   // 新增（标签名；暂不引入 id 表）
)
```

**为什么零迁移**：`meta_json` 是 **JSON blob**，且 `JsonInstant` 配置了
**`ignoreUnknownKeys = true`** → 旧行缺 `tags` 字段时自动用默认空列表，**不加列、不写迁移**。
（与 `settings-persistence-trio` 记录的原理一致：`@Serializable` 子对象字段随 JSON 自动持久化。）

**⚠️ 必须处理的一个坑**：`NodeFavoriteAdapter.buildFavoriteEntity(target, existing, now)`
**每次都从 `target` 重建 meta**。所以"取消收藏再重新收藏"会**丢掉标签**。
修法：构建时保留 `existing` 的标签——

```kotlin
val meta = FavoriteMeta(
    title = ..., subtitle = ..., previewText = ...,
    tags = existing?.let { decodeMeta(it)?.tags } ?: emptyList(),   // 保留既有标签
)
```

**标签名 vs 标签 id**：本设计用**标签名（String）**直接存在收藏上，
不引入 `Tag` 表 + 关联表。理由：收藏量与标签数都小（几十条），
名值够用且省一次 join；**代价是改名需批量更新**（若将来要改名功能，再评估迁移到 id）。

**标签池从哪来**：可从全部收藏的 tags 聚合去重得到（无需单独存"标签列表"）。
若需"空标签也保留"，则需在 Settings 存一份标签池（照 `ASSISTANT_TAGS` 单 key 写法）。

## 六、风险与注意点

1. ~~**`MESSAGE` 类型收藏不可见**~~ **已实测关闭**（2026-09-16，设备库 `<device>`）：
   `SELECT type, count(*) FROM favorites GROUP BY type` → **`node|2`**，即**仅 2 条收藏、全为 node、
   零 message**。故该遗留类型**无真实数据**，可安全视为死类型（§七 已列不做）。
2. **撤销竞态要真修，别只改注释**：改成 `suspend` 后要**验证"恢复一定在删除之后"**——
   建议单测/真机用"快速删除→立刻撤销"复现原故障，再验证修复。
3. **滑动状态与 key 的绑定**：`SwipeToDismissBox` 的 state 必须随 item 身份重置，
   否则恢复的卡片会"隐身"。这与 `LazyColumn` 的 key 复用机制交互，容易被忽略。
4. **排序与筛选的组合**：标签筛选 + 排序可同时生效；DAO 侧排序参数化时注意
   `Flow` 查询的 `ORDER BY` 不能参数化（SQLite 限制）→ **排序在 VM 侧做**（列表小，成本可忽略）。
5. **标签名冲突/空标签**：需 strip 空白、去重、拒绝空串。
6. **备份/恢复**：标签存在 `meta_json` 里 → **随 favorites 表一起备份，无需额外处理**（verify）。
7. **`created_at` 复用语义**：`buildFavoriteEntity` 保留 `existing.createdAt`（取消再收藏不会刷新时间）
   ——若将来"按添加时间排序"要符合直觉，需确认这是否是期望行为。

## 七、不做（本次范围外）

- **层级目录/文件夹**（见 §四）。
- **收藏的"显示名"重命名**（无该字段；如需，先做 §四.2 的语义决策）。
- **`snapshotJson` 的启用**（如"即使原会话删除也能看收藏内容"）——独立课题，
  目前依赖跳到会话查看，会话删除后收藏条目会怎样需另评估。
- **`MESSAGE` 类型的功能补齐**（零写入，视为死类型）。

## 八、待确认

1. ~~批次划分~~ → **用户选「两批 + 标签管理页」**，已一并落地。
2. ~~标签存 `meta_json` 还是 `Tag` 表~~ → **存 `meta_json`（零迁移）+ 收藏上存 tag id**；
   标签池放 `Settings.favoriteTags`（单 key JSON 列表）。这样**改名不必批量更新收藏**
   （存 id 的直接收益），也无需 `Tag` 表 + 关联表。
3. ~~排序维度~~ → **时间 / 标题两档**，**不做"按会话分组"**（收藏量小，收益不明显）。
4. ~~**`MESSAGE` 遗留数据**是否真实存在（先查库）~~ → **已实测：无**（设备库仅 2 条 node 收藏，见 §六.1）。
