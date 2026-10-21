# 翻译历史：翻译页的「最近 100 条」

> 状态：**已落地**（DB v45）。2026-10-21。
> 借鉴来源：上游 `93f231149 feat(translator): 翻译页 MD3 Expressive 改版 + 新增翻译历史`（2026-10-19）。
> fork 只取「翻译成功后留档」这一件事，历史表与迁移按自己的版本线重写；上游的页面改版与
> 迁移 30 不吸收（见 §6）。

## 1. 问题：翻译结果用完即走

翻译页原本是「输入 → 结果 → 复制」的一次性工具，翻译完成后：

- 切走页面结果就没了，想对照上一版/复用上次译文只能重新翻译（再花一次模型调用，且结果可能不同）；
- 同一段文本反复翻译时，每次都要重新粘贴；
- 从原版 RikkaHub 3.x 备份导入时，`translation_history` 是转换器日志里被跳过的那张表
  （见 [design-backup-legacy-import.md](design-backup-legacy-import.md)）——原版用户的历史白丢。

历史不是「聊天记录」：它不需要分支、编辑、重新生成，只需要**按时间倒序看最近若干条、点一条恢复、
不要了能删**。所以不做消息树，就是一张独立的扁平表。

## 2. 数据模型：`translation_history`（DB v45）

实体 `data/db/entity/TranslationHistoryEntity.kt`，表名 `translation_history`：

| 列 | 类型 | 含义 |
|---|---|---|
| `id` | TEXT PK | 应用生成的随机 UUID 字符串（不是自增） |
| `source_text` | TEXT | 翻译前的原文 |
| `translated_text` | TEXT | 模型返回的译文（成功后才会写） |
| `target_language` | TEXT | 目标语言 **BCP 47 标签**（`zh-CN`/`en`/`ja`…） |
| `model_id` | TEXT | 翻译时用的模型 id（只作线索，模型删了不影响历史） |
| `create_at` | INTEGER | 写入时刻（epoch millis），列表排序与裁剪都靠它 |

索引只加 `create_at` 一个——读侧永远是「按时间倒序取最近 N 条」，没有第二种查询。

几个刻意的决定：

- **主键用 UUID 字符串而不是自增**：历史是「本机产生的记录」，id 在写入时就能确定，
  与旧备份的同名表列名/类型保持同构（转换器按列名交集复制，见 §4）；`REPLACE` 语义也天然幂等。
- **`target_language` 存 BCP 47 而不是枚举序号**：页面的目标语言清单会变（上游就动过），
  存标签后旧记录不会因为枚举重排而指错语言；展示时再 `Locale.forLanguageTag()` 本地化。
- **不加外键**：模型/提供商可能被删，历史必须还在；`model_id` 仅用于排查，不参与显示。
- **不存源语言**：翻译请求是按「自动检测 → 目标语言」发的，源语言是模型判断结果，存下来也不可信。

## 3. 写入时机与上限

`ui/pages/translator/TranslatorVM.kt` 的 `translate()` 在 **收集完流、拿到非空最终结果之后** 写一条：

1. 只有 `_translatedText.value` 非 blank 才落库——取消、异常、空响应都不进历史。
   流式过程中不做增量写：一个请求只对应一条历史，而不是每个 chunk 一条。
2. `runCatching { historyRepository.add(...) }` + `Log.w` 兜底：**历史写失败不能让用户以为翻译失败**，
   页面上不弹 toast（与用量流水「统计失败不影响对话」同一口径）。
3. `add` 内部用 `now()` 取 `create_at`、`Uuid.random()` 取主键，写入后立即 `trimTo(MAX_HISTORY_COUNT)`。

上限 **100 条**（`TranslationHistoryRepository.MAX_HISTORY_COUNT`）：

- 翻译是顺手用的功能，历史无限增长只会占库、没人翻；100 条足够覆盖「最近的几次」。
- 裁剪在**每次写入后**由 SQLite 完成（`DELETE … WHERE id NOT IN (SELECT id … ORDER BY create_at DESC LIMIT :keep)`），
  不把全表拉回 Kotlin、不依赖后台任务/启动清理——调用方不需要记得清理。
- 上限是「最近 100 次**成功**翻译」而不是「100 段不同原文」：同一段文本翻两次就是两条。

## 4. 迁移与备份兼容（44 → 45）

纯新增表，**AutoMigration** 即可（没有回填、没有重建）：

- `AppDatabase.version = 45` + `AutoMigration(from = 44, to = 45)`；
- 空的 44 库升级后得到一张空表，旧表一条不碰（`Migration_44_45_Test` 断言旧表 CREATE 语句逐字不变）；
- schema `45.json` 由编译时 `room.schemaLocation` 导出并提交，供迁移测试校验。

备份兼容：`LegacyBackupConverter` 本来就按「**目标库有哪些同名表**」逐表、按列名交集复制，
所以新表一旦存在，原版 3.x 备份里的 `translation_history` 就会自动随转换导入，不需要专门分支。
本次只更新了转换器 KDoc（原来写的是「翻译历史不复制」）。边界：

- 只有 **3.x 备份**有这张表；2.5.6 世代（v25）没有，那条路径不受影响。
- 目标表的列全部 NOT NULL，若第三方改包删过列，按表跳过并记日志——不让一个可选表毁掉整库恢复
  （与其它兼容表同一策略）。
- 旧备份记录的主键/时间原样带过来；这些行同样计入 100 条上限，下一次写入时按 `create_at` 裁剪。

## 5. UI 与交互

历史区在翻译结果与「复制翻译结果」按钮**下方**（`TranslatorPage.kt` 的 `HistorySection`），
没有历史时整段不渲染——页面主体仍是输入与结果：

1. **列表**：标题行 + 「清空」按钮；每项显示原文（最多 2 行截断）、译文（最多 3 行）、
   目标语言显示名 + 记录时间。上限 100 条，直接 `Column` 渲染，不做分页。
2. **恢复**：点一项 → 原文回输入框、译文回结果区、目标语言切回记录里的语言，
   并滚回页面顶部（恢复的内容在输入区）；不重新发请求。
3. **删除**：左滑（`EndToStart`）删除单条。用 `settledValue` 而不是 `currentValue` 判定，
   避免动画途中、手指还没松开就触发删除；列表项 `key(entry.id)` 保证删除后复用节点时滑动状态复位。
4. **清空**：二次确认对话框（清空不可撤销）。

恢复/删除/清空都走 ViewModel → 仓储，不在 UI 里直接碰 DAO；历史用
`SharingStarted.WhileSubscribed(5_000)` 订阅，页面不可见后停查 5 秒，不让后台一直持有数据库查询。

## 6. 与上游的差异（有意为之）

| 项 | 上游 `93f231149` | 本 fork | 理由 |
|---|---|---|---|
| 页面形态 | 翻译页整体 MD3 Expressive 改版 | **不吸收改版**，历史嵌进 fork 自有翻译页 | fork 翻译页有自身定制（快捷方式入口、语言清单、模型选择）；整页重写属独立 UX 任务 |
| 数据库版本 | 新增 Room 迁移 30 | **44 → 45 AutoMigration** | fork 的 DB 版本线早已与上游分叉，上游的 `30.json` 不能照搬 |
| 主键 | — | UUID 字符串 | 写入时即可确定、与备份同名列同构、REPLACE 幂等 |
| 保留上限 | — | 100 条，写后裁剪 | fork 自定的体量取舍；裁剪放在写入路径，不引入后台任务 |
| 设置项 | 翻译页改版附带设置 | 不引入 | 历史不需要配置：固定 100 条、自动留档 |

## 7. 已知边界（不得写成已解决）

- **同毫秒排序不稳定**：`ORDER BY create_at DESC` 没有二级排序键，同一毫秒写入的两条记录顺序由
  SQLite 自行决定（裁剪边界上「删哪条」同理）。实际使用中同一毫秒翻译两次几乎不可能，未加 `id` 二级键。
- **不存源语言、不去重**：同一原文重复翻译会产生多条记录；这是「历史 = 操作流水」而非「译文收藏」的语义。
- **清空不可撤销**：只有二次确认，没有回收站（历史本身有 100 条上限，做回收站的复杂度不匹配）。
- **历史不参与用量统计**：翻译请求的 token 花费仍由 `usage_record` 的 `TRANSLATION` 行统计
  （见 [design-usage-stats.md](design-usage-stats.md)）；这张表只存文本，不存 usage。
- **备份是整库级**：本应用自己的整库备份会带上 `translation_history`；但导出的库版本是 45，
  原版 RikkaHub 打不开（与既有备份策略一致，不是新限制）。

## 8. 验证

- JVM `TranslationHistoryRepositoryTest`：字段与注入时钟、主键是合法 UUID 且互不相同、
  101 条写入后裁剪到 100（最旧被删）、`history()` 透传 DAO 流并声明上限、`delete`/`clear` 转发。
- 真机 Room `TranslationHistoryDAOTest`：倒序 + `LIMIT`、`REPLACE` 主键不产生重复行、
  单条删除（含删不存在的 id）、清空、`trimTo` 保留最新 N 条（含 `keep = 0` 全删）。
- 真机 `Migration_44_45_Test`：v44 无该表 → v45 有且列/索引与 `45.json` 一致、升级后为空；
  旧数据（会话/消息节点/用量流水）CREATE 语句与行内容不变、新表可写。
- 真机端到端（升级 + 翻译成功留档 + 点恢复 + 左滑删除 + 清空）：2026-10-21 实测通过——release 构建
  （`1.0.28-beta.5 (307)`，v44 库）覆盖安装后正常启动（44→45 迁移无崩溃）；翻译一次后历史出现在结果下方；
  `am force-stop` 重启后仍在；点条目恢复原文/译文/目标语言并滚回顶部；左滑删除单条；「清空」二次确认后
  整段历史消失；空历史时整段不渲染。

## 关联

[design-usage-stats.md](design-usage-stats.md) · [design-backup-legacy-import.md](design-backup-legacy-import.md) · [docs/upstream-analysis.md](../upstream-analysis.md)
