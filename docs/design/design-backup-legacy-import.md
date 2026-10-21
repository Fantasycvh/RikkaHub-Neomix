# 原版 RikkaHub 备份导入兼容（只读兼容）

> 2026-10-19。目标：本应用能恢复原版 RikkaHub 的整库备份；**不要求**导出的备份能被原版打开。
> 依据是设备上两份真实备份 + 上游仓库代码，不是推测。

## 1. 真实备份的实测结构

两份备份都来自设备 `Rikkahub_plus/备份兼容测试/`，各约 38.8 MB，
`zip(settings.json, rikka_hub.db, upload/*.md…)`：

| | 旧版（2.5.6 世代） | 新版（3.x） |
|---|---|---|
| SQLite `user_version` | 25 | 30 |
| 会话 / 消息节点 | 165 / 3916 | 167 / 3922 |
| 记忆 | `MemoryEntity` **49 行**行式 | `memory_file` **1 篇** `/profile.md`（53,599 字符，49 个 `- [stated]` 条目） |
| 其他独有表 | — | `memory_consolidation`(167)、`media_creation_*`、`translation_history` |

settings.json 差异很小：旧版多 `compressPrompt/compressModelId`（已删除字段），
新版多 `enableAutoTitle/mediaGenerationProviders/translateTargetLanguage/uploadS3Config`。
现有 `SettingsJsonMigrator` 会改写 `me.rerere.*`/`app.fantasycvh.*` 的包名与绝对路径；
`JsonInstant` 忽略未知字段、缺字段走默认值，因此两版都能直接解码（已用真实文件验证）。

**关键结论**：两份备份的数据库不是同一个版本线——
- v25 与本应用**同一套 schema**（表/列零差异），可以交给 Room 迁移链；
- v30 之后本应用与原版**分叉**：本应用是行式记忆 + agent/knowledge，原版是文件式记忆 + 媒体/翻译。
  因此 v30 备份不能靠迁移，只能做数据级转换。

## 2. 恢复路径的分流

恢复入口仍是 `BackupArchiveEngine.restoreFromBackupFile`，在换库前新增一步
`prepareStagedDatabase`，按暂存主库的家族分流（[LegacyBackupConverter](<../../app/src/main/java/app/androidrik/rikkahub/data/sync/LegacyBackupConverter.kt>)）：

| 家族 | 判据 | 处理 |
|---|---|---|
| CURRENT | 有 `MemoryEntity` | 就地改写旧包名附件路径 → 原有换库 → Room 迁移到当前版本（45） |
| LEGACY_UPSTREAM | 无 `MemoryEntity`，有 `memory_file` 或 `media_creation_session` | 数据级转换到当前 schema，再走同一套换库 |
| UNKNOWN | 两者都不满足 | 报错中止，不猜、不覆盖 |

两条路径都在**换库之前**完成：转换写到独立文件，失败只删半成品，当前库不受影响
（与原有恢复的失败语义一致）。

## 3. 新版备份的数据映射（用户已确认的口径）

- **记忆按顶层条目拆条**：`- [stated] …` 每一条变成一条 `MemoryEntity`；
  `[stated]/[inferred]` 是原版来源标记，本应用无对应字段 ⇒ 剥离标记、保留正文；
  front matter 的 `description` 不硬塞给每一条（会污染检索），留空由渲染兜底。
  实测 `/profile.md` → 49 条，条长 192–3657 字符。
- **同名兼容表按列交集复制**：会话、消息节点、整理进度、收藏、工作区、文件夹、媒体、托管文件。
  目标表多出的列由建表 DEFAULT 兜底；`room_master_table/android_metadata/sqlite_sequence` 绝不复制
  （复制源的 schema 身份哈希会让 Room 拒绝打开）。
- **媒体创作表随功能移植一起导入**：本应用移植了 `media_creation_session/node/record`
  且 schema 与上游 3.x 同构 ⇒ 转换器按同名表复制；媒体文件由 `media_creation/<sessionId>/…`
  条目恢复到同一相对目录，记录里存的就是相对路径。
- **翻译历史随同名表导入**：DB v45 起本应用也有 `translation_history`（列名/类型与上游 3.x 同构），
  转换器按「目标库有哪些同名表 + 列名交集」自动复制 ⇒ 3.x 备份里的翻译历史一并带过来，
  不需要专门分支。上游独有的媒体生成设置仍只记日志（见
  [design-translation-history.md](design-translation-history.md)）。
- **表级容错**：目标表若有「NOT NULL 且无默认值」的列在源库不存在，按表跳过并记录，不让一个
  可选功能表毁掉整库恢复（例如第三方改包删过列）。
- **旧路径改写**：`messages/nodes/config` 等文本列里的 `/data/user/0/me.rerere.rikkahub*`
  改写成当前 applicationId；旧版堆栈文本里的包名**不动**（属于用户正文）。

恢复语义沿用现有整库恢复：**覆盖当前数据**、需要重启；不做合并。

## 4. 验证

- 真实 2.5.6 库：v25 → v42 迁移，165 会话 / 49 记忆 / 3916 消息节点保留；旧附件路径改写后无残留。
- 真实新版库：转换后 167 会话 / 3922 消息节点 / 167 整理记录 / 49 条记忆（标记已剥离、revision=1）。
- 两份真实 settings.json 均能迁移并解码（3 个助手、providers 非空）。
- 永久回归（不含用户数据）：
  - JVM [LegacyBackupTextTest](<../../app/src/test/java/app/androidrik/rikkahub/data/sync/LegacyBackupTextTest.kt>) 7 项：
    条目拆分、续行、front matter、无条目兜底、路径改写、调试后缀/旧 fork 根、堆栈文本不改写。
  - 真机 [LegacyBackupConversionTest](<../../app/src/androidTest/java/app/androidrik/rikkahub/data/sync/LegacyBackupConversionTest.kt>) 2 项：
    合成原版库的家族识别、兼容表复制、记忆拆条、路径改写、未知库拒绝。
- 全量回归：**210 JVM suites / 1916 测试**、**隔离真机 Room 57/57**，0 失败。
- 探针用的真实备份/设置只放在设备 `/data/local/tmp`，验证后已删除；未提交任何用户数据。

## 5. 未完成与边界（不得写成已解决）

1. **未做端到端 zip 恢复测试**：DB 迁移、转换、settings 解码、路径改写分别验证过，
   但「选文件 → 解包 → 换库 → 重启」整链路还没有自动化用例，需要在调试包上实测一次。
2. **只做导入**：导出的备份仍是本应用 45 版格式，原版 2.5.6/3.x 打不开（用户已确认本轮不做）。
3. **翻译历史已导入、但上游翻译页设置未移植**：3.x 备份里的 `translation_history` 现在按同名表随转换导入
   （本应用 DB v45 起有同构表，见 [design-translation-history.md](design-translation-history.md)）；
   上游翻译页的 MD3 改版与相关设置项仍未移植。媒体生成产物改由媒体创作功能承载，
   但上游的 S3 上传/画板等子功能未移植，相关素材可能只有本地文件、没有远端引用。
4. 新版记忆的**主题/来源语义**只保留正文；`[stated]` 等标记与 front matter 名称不落库。
5. 覆盖式恢复会替换现有记忆库；导入前应提示并建议先导出当前数据。
6. 设备上第三份变体（`excp.rikkahub.zhcn` 2.4.10 等）未取到备份，未验证其 schema 是否也落在已知家族。
