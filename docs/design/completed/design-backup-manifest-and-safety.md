# 备份 Manifest v2 + 恢复校验/预览 + A 档安全备份（设计稿，批次 3）

> 状态：**已落地（2026-10-26）**。来源：Yuihub 3.0.0 吸收项，评估见 `peer-apps-benchmark.md`
> §四.8 D、§五表 12/13。与设计的偏差：①manifest 写为 **zip 末条目**而非首条目（本仓恢复
> 本就把 settings 缓冲、DB 暂存到最后，校验天然可以末置）；②校验语义抽成纯函数
> `verifyBackupManifest`（引擎被 Context/Room 绑死，JVM 直测）；③FILES/WS 字节不强制
> （条目数强制，字节是预览信息）；④单测 11 项（校验语义/分类器/JSON 往返），流式记账
> 与 UI 走真机批次验收。

## 1. 要解决什么

- 恢复是「直接执行」：无校验和、无预览——损坏/外来/高版本备份读到一半才炸，且
  **设置在解压途中就已写入**（`BackupArchiveEngine.kt:228`），炸在中途 = 设置已变异物。
- 恢复失败无后悔药：换库前没有任何现库快照。

## 2. 现状盘点（带证据）

- 引擎唯一出口：`BackupArchiveEngine`（S3/WebDav/本地共用）。打包 `prepareBackupFileLocked`
  （settings.json 恒含 + 按项 DATABASE/FILES/WORKSPACE_FILES）；恢复 `restoreFromBackupFileLocked`
  （:172-328）：settings 流中即时写入（:228）、DB 暂存后关库换入（:246-258/:305-308）、
  FILES 按前缀流式写盘（:260-282）。
- 已有防护：空间预检（:69/:187）、`LegacyBackupConverter` 家族分流（:338）、
  换库原子顺序（:374 注释）、`BackupCoverageTest` 目录清单护栏。
- UI 入口：`BackupVM.restoreFromLocalFile`（:142）→ `WebDavSync.restoreFromLocalFile` →
  引擎；S3/WebDav 走各自 item。无预览。
- FileFolders 在 `FilesManager.kt:801`；`BackupCoverageTest` 钉全集决策。

## 3. 设计

### 3.1 Manifest v2（打包侧）

- 新文件 `BackupManifest.kt`：`@Serializable data class BackupManifest`——
  `formatVersion=2`、`createdAt`、`items`（本次勾选项）、
  `sections`（SETTINGS/DATABASE/FILES/WORKSPACE_FILES 各自 `entries`+`uncompressedBytes`）、
  `settingsSha256/bytes`、`databaseSha256/bytes`（主库；**双 SHA-256 只做 settings+主库**，
  对齐 Yuihub——全量逐文件哈希在打包/恢复两侧都是重活，收益不成比例）。
- 写为 **zip 首条目 `manifest.json`**（流式恢复必须先读到才好校验后续条目）。
  打包时：settings 内容已知即哈希；DB 在 `addDatabaseToZip` 里用 Digest 包装流边写边哈希；
  各 section 的 entries/bytes 在打包循环里累计。
- **向后兼容**：无 manifest 的旧备份走 legacy 路径（跳过校验）；`formatVersion > 2`
  恢复直接报错（「备份来自更高版本」）。

### 3.2 恢复校验（引擎侧）

按危害面分级——**破坏性步骤（settings 写入、换库）必须过校验才执行**：

1. `manifest.json` = 首条目：解析 + `formatVersion` 校验。
2. 重复条目名 → 直接报错（Yuihub `seen` 集合同款）。
3. 流式累计各 section 的 entries/bytes；settings JSON **改为缓冲不即时写**
   （原 `:228` 的即时写入移到校验通过后）；主库暂存时算 SHA-256。
4. zip 读毕统一校验：双 SHA-256 + 各 section entries/bytes 与 manifest 一致；
   不一致 → 抛错。此时 settings 未写、库未换——零破坏。
5. **FILES/WORKSPACE_FILES 的不对称性（记录在案）**：这两类在流中已写盘，损坏 zip
   可能留下部分恢复的文件；可接受——文件写是幂等覆盖（重跑一次恢复即自愈），
   且设置/会话库这两个不可再生件已被第 4 条保护。

### 3.3 RestoreByteBudget（zip 炸弹/异常条目兜底）

- 单条目解压上限 **2GB**、总量 **8GB**：流式复制处计数，超限报错
  （`ZipEntry.size` 在 data descriptor 模式下常为 -1，不能信 size 字段，必须边拷边数）。

### 3.4 A 档：恢复前自动安全备份

- `restoreFromBackupFileLocked` 开头（任何变更之前）：`items` 含 DATABASE 时
  （settings 恒含）先 `createSafetyBackup()`：复用 `prepareBackupFileLocked(items=[DATABASE])`
  （settings 恒含）写 `filesDir/safety_backup/safety_<ts>.zip`，**滚动保留 3 份**
  （按文件名时间戳排序删最旧）。
- **runCatching**：安全备份失败只记 warning、不阻断恢复（Yuihub 同款）——恢复本身
  就是用户在赌一条路，别让保险丝失效变成第二道墙。
- `FileFolders.SAFETY_BACKUP = "safety_backup"` 新增 + `EXCLUDED_FILE_FOLDERS`
  登记理由（滚动保留的派生快照）——`BackupCoverageTest` 会强制这条显式决策。

### 3.5 恢复预览（UI）

- 引擎新增 `inspectBackup(file): BackupInspection`：用 `ZipFile`（随机访问，不全量解压）
  读 manifest + 枚举条目；legacy 备份返回 `manifest=null` + 从条目名推断的 section 概览。
- `BackupVM.inspectLocalBackup(file)` 暴露给 `BackupPage`：本地恢复入口先 inspect →
  预览卡（创建时间 / 各 section 条数+大小 / 版本提示 / legacy 标记）→ 用户确认 → restore。
  S3/WebDav 恢复暂不加预览（云端条目通常带元数据列表，另议）。
- 新增 UI 字符串走 locale-tui（en/zh/zh-rTW）。

## 4. 验证计划

- 单测（`BackupArchiveEngine` 已有测试基建 `BackupCoverageTest` 等，JVM 直跑）：
  ①打包产物含 manifest 首条目且 sha256 正确；②恢复校验通过路径（写设置+换库照常）；
  ③篡改 settings 字节 → 报错且设置未被改写、库未换；④篡改主库 → 报错；⑤counts/bytes
  不符 → 报错；⑥legacy 无 manifest 照常恢复；⑦formatVersion=3 报错；⑧重复条目报错；
  ⑨单条目 >2GB 报错；⑩安全备份生成且滚动 3 份；⑪安全备份失败不阻断恢复；
  ⑫inspectBackup 对 manifest/legacy 两种包的输出。
- 真机（批次验收）：真实数据导出 → 手动改坏一个字节 → 恢复报错且数据无损；正常恢复。

## 5. 明确不做

- 不做全量逐文件 SHA-256（收益/成本不成比例，§3.1）。
- 不做 B 档 staged 换库+启动回滚（可选-高价值，独立批次另议）。
- 不做 S3/WebDav 的预览 UI（先覆盖本地入口；云端列表页已有元数据）。
- 不做自动定时备份（Yuihub 有，方向可取，独立立项）。
