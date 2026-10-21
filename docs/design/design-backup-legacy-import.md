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

## 6. 原版 3.1.0：P0 兼容加固（2026-10-21）

对照上游 `eeef60b22`（DB v31）与本应用 DB v45：原版 14 张业务表中，13 张同名表
可按列交集复制，没有缺失的目标必填列；`memory_file` 遍历全部文件并按顶层条目转为行式记忆。
这是 schema 对照结论，不代表真实 3.1.0 zip 已完成端到端验证。

### 已处理

- **不支持的提供商/TTS 类型不再阻断整份恢复**：新增
  [SettingsCompatDecoder](<../../app/src/main/java/app/androidrik/rikkahub/data/datastore/migration/SettingsCompatDecoder.kt>)，
  在包名迁移后逐条验证 `providers` / `ttsProviders`。顶层类型不支持（例如 `chatgpt`、`grok`、
  `volcengine` TTS）时仅跳过该条目，继续解码其余设置。不会将其强行映射为 OpenAI 协议。
  被删除的 TTS 恰为选中项时回退系统 TTS ID；被删除提供商内的模型引用不自动改绑，使用时需重新选模型。
- **有限容错，而非忽略损坏数据**：已知类型的字段损坏、嵌套未知类型、其他设置错误仍会报错中止；
  不通过解析异常 JSON path 任意删除设置。恢复结果只携带条目位置与类型名，不包含 API key 或完整 JSON。
- **降级提示贯通三入口**：本地、WebDAV、S3 恢复结果携带跳过列表，完成时显示警告并记录日志。
- **失败的服务端工具消息可读取**：`ServerToolStatus` 增加 serial name `failed`，消息 UI 显示失败图标/文案，
  不再因该枚举值不受支持导致会话反序列化失败。

### 本轮验证

- [SettingsCompatDecoderTest](<../../app/src/test/java/app/androidrik/rikkahub/data/datastore/migration/SettingsCompatDecoderTest.kt>)：
  9 项，含支持类型 JSON 往返、ChatGPT/Grok 降级、火山 TTS 回退、旧包名与退役搜索组合、
  损坏已知类型/嵌套未知类型不误删、非法设置仍失败、`failed` 消息往返。
- 与既有 `SettingsJsonMigratorTest` 合计 **18 项 JVM 测试通过**；应用及仪器测试 Kotlin 编译通过。
- 扩展 [LegacyBackupConversionTest](<../../app/src/androidTest/java/app/androidrik/rikkahub/data/sync/LegacyBackupConversionTest.kt>)
  合成库为 v31 形状，补充 `usage_record`、`translation_history`、第二篇主题记忆；真机 **2/2 通过**。
  断言用量/翻译正文保留、缺失 `provider_name` 为 null、多文件记忆扁平化且 revision=1。
- 本轮应用 JVM 全量 **2089/2089 通过**，`lintDebug` 通过；未因此提升为整链路验收。

### P0 时仍有的语义损失与未验证项

会话配置、压缩检查点和模式注入在后续 §7 继续加固；多文件记忆的元数据、网络代理设置及
部分消息统计字段尚未映射。导入仍是覆盖而非合并，操作前应先备份当前数据。

## 7. 原版 3.1.0：会话语义与真实备份验证（2026-10-21）

### 会话级配置

- Room v46 新增 nullable `ConversationEntity.upstream_config`，AutoMigration 45→46 对原有会话默认 null。
- [UpstreamConversationConfig](<../../app/src/main/java/app/androidrik/rikkahub/data/model/UpstreamConversationConfig.kt>)
  保留七个原版字段：chatModelId、reasoningLevel、enableWebSearch、builtInSearch、mcpServers、workspaceId、enabledSkills。
  `false`、空集合及 model/workspace 的显式 null 都是冻结值，不当作继承。无 config 才沿用助手。
- 外挂搜索与内置搜索分别作用于 Assistant 与 Model；只替换 Search 工具，保留其他模型工具。
  实发及只读提示词预览均应用相同快照，助手本身不因打开旧会话而被改写。
- 转换阶段验证已知字段类型；损坏配置报错而非静默回退。未知字段在转换 JSON 中保留，
  但领域对象后续保存仅保留当前已知字段，不宣称任意未来字段无损往返。

### 压缩检查点：保留原始消息而非破坏性抽取

- 为 UIMessage 增加持久化 `isContextCheckpoint=false`。原版消息节点、候选、选择索引及正文保留，
  不删检查点、不把原文再套本应用的摘要前导语、不按时间排序/拼接多份摘要。
- 请求时扫描**当前选中候选**，最后一个带标记的节点为有效检查点，发送其原始消息及后续尾部。
  头节点检查点、混合候选切换、删除最新检查点后回退、生成循环新增工具结果均有回归覆盖。
  上下文条数限制只作用于尾部，检查点自身始终保留。
- 后续本应用生成的有效检查点仅在边界严格晚于原版节点时接管；继续压缩时以有效发送窗口及
  已有摘要为基线，避免把摘要之前的历史重复并回。原版标记不硬塞进本应用独立检查点表，
  原版检查点默认使用本应用压缩提示行：检查点及之前的历史默认折叠，展开历史后检查点显示为
  可展开正文的摘要卡，而非普通聊天气泡；原始消息节点不删除。界面与请求共用同一有效边界。
- 加载器的历史“合成消息泄漏”清理不再单凭文本前缀删候选：需要跨节点重复 ID 佐证，
  且含原版检查点的节点及全部替代候选受保护，真实用户文本与前导语撞名也能保留。

### 模式注入

[SettingsJsonMigrator](<../../app/src/main/java/app/androidrik/rikkahub/data/datastore/migration/SettingsJsonMigrator.kt>)
V5 把旧 `modeInjections` 原样包进单个 `promptPresets` 容器，保留条目 ID、顺序、字段及助手/会话绑定。
显式空列表不填默认项，已有 `promptPresets`（包括空列表）不覆盖，重复迁移幂等。

### 真实样本及验证边界

- 用户指定目录最新 `rikkahub_backup_20261021_144128.zip`（42,754,732 字节，DB v31）：
  3 个助手、30 个提供商、9 条模式注入、3930 个消息节点、5 份非空会话配置；
  1 个会话含 2 个原版检查点，均为独立非头节点，无混合检查点候选。
- 私有备份仅放临时目录，不进 Git、测试资产或公开报告；测试只输出计数/断言，不输出密钥/聊天正文。
- [Upstream31RealBackupTest](<../../app/src/androidTest/java/app/androidrik/rikkahub/data/sync/Upstream31RealBackupTest.kt>)
  通过显式仪器参数选择私有 fixture，无参数默认跳过：从 ZIP 读取设置/DB，迁移转换后经
  ConversationRepository 加载所有会话、保存并重载；逐一验证配置、节点、检查点正文及有效请求窗口。
  验证 DB 使用独立路径，不覆盖正式包数据库；隔离索引使用普通 SQLite 表模拟索引写入契约，
  不将其称为生产自定义 FTS 分词器测试。
- 最终验证：应用 JVM **2118/2118 通过**；真机真实样本往返 + 合成转换 + 45→46 迁移共 **4/4 通过**。
  `lintDebug` 通过（首次并行分析触发 Kotlin FIR 工具内部错误，串行重跑通过，并非忽略检查）。
- 这覆盖了真实 ZIP 的数据层组件组合与仓库往返，**不是文件选择器 → 实际覆盖换库 → App 重启的完整 UI 验收**。
  未发送用户会话到任何远端模型，也未验证所有 MCP/技能的远端实际可用性。

### 仍未覆盖

- `networkSetting → globalProxy`、provider quotaQuery、Google useInteractionsApi 等未映射。
- 多文件记忆仍只保留正文，不保留路径/目录、标题/描述/别名、来源标记、常驻注入语义。
- `modelSnapshot/generationTimeMs/lastUsage/stepCount`、`Reasoning.reasoningType` 等部分消息元数据未映射。
- 被 P0 丢弃的提供商对应模型 UUID 不自动重绑，需用户重新选模型。
