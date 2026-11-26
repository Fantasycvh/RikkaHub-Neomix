# Cuplivo + Kelivo 评估 —— 局域网双向同步与本地副本

> 对应总表 §一 #17 / #18。深度：**源码**（本地 clone 逐文件读备份与同步模块；Cuplivo 固定 `4c20b4462`、Kelivo 固定 `1e1c1d1a`）。许可证：两者均为 **AGPL-3.0**（与本仓一致）。定位：**Flutter 多端 LLM 客户端**（Android / iOS / macOS / Windows / Linux），Cuplivo 是 Kelivo 的社区 fork。两者同源、本轮围绕**备份 / 同步**一个域合并评估，故共用本台账。
> 本地副本路径：`F:\build\projects\cuplivo`、`F:\build\projects\kelivo`
> [返回索引](../../peer-apps-benchmark.md)

## 一句话结论

**协议形态与产品口径可借，代码不可搬（跨语言）。** Cuplivo 的局域网同步已把「证书指纹即身份 + PIN 配对 + 对称确定性合并 + 内容寻址差集」跑通多年（协议迭代到 v10），本仓据此在 Kotlin 侧重写为 `data/sync/lan/`；Kelivo 的「本地副本」给出了自动备份的完整产品口径（延迟检查、N 份 + 上周/上月留存、容量上限、整包模型），本仓据此落地 `data/sync/localsnapshot/`。两处均**未复制其 Dart 实现**，且按 Android 平台差异做了替换（SAF 目录、mutual TLS、Room 事务、会话墓碑）。

## 一、它们是什么

- **Kelivo**：Flutter 多端客户端，备份体系 = `DataSync`（整包：数据库快照 + 业务设置 + 资产目录，格式带版本与 manifest，`lib/core/services/backup/data_sync.dart:265-313`、`668-722`）+ 本机自动快照（`lib/core/services/backup/local_folder_backup.dart:107-191`）+ 恢复 staging/mover 两段式（`restore_bundle_staging.dart:218-326`、`restore_bundle_mover.dart:48-82`）。
- **Cuplivo**：在 Kelivo v1.3.0 基线上重构，把「增量备份 zip + 合并恢复」升级为设备间同步基础设施。同步层集中在 `lib/core/services/sync/`（`sync_engine` / `sync_server` / `sync_client` / `sync_data_plane` / `sync_merge` / `sync_family_registry` / `blob_sync` / `sync_limits` 等），UI 在 `lib/features/sync/` 与 `lib/features/settings/pages/sync_settings_page.dart`；本机自动副本在 `lib/features/backup/local_snapshot_scheduler.dart` 与 `lib/core/services/backup/local_snapshot_retention.dart`。
- **共同点**：都是 Flutter + Drift/SQLite，与上游 Kelivo 同源；备份包格式互通（`.kbackup` / `.zip`）。

## 二、值得借鉴（按价值排序）

### 2.1 局域网同步的协议形态（已落地）

1. **身份即证书指纹**：服务端自签证书，客户端把对端证书 SHA-256 当 `deviceId` 固定（`lib/core/services/sync/sync_server.dart:227-248`、`sync_client.dart:73-119`）。本仓落成 `LanIdentity`：AndroidKeyStore 生成密钥对 + 自签证书，`certSha256` 即身份。
2. **PIN + 二维码双通道配对**：二维码携带 `host / port / certSha256 / token`，PIN 用于人眼确认，配对窗口短时限。本仓落成 `LanPairingProtocol` / `LanPairingService` / `LanPairingPage` + `LanPairingQr`。
3. **对称确定性合并**：合并规则无方向性——双方各自用同一函数、同一输入算计划，结果互补（`sync_merge.dart:8-14`）。本仓落成纯函数 `planLanMerge`（`LanMergePlan.kt`），并加了一条本仓特有的破平规则：`updateTime` 相等时按 `sha256` 字典序定序，避免双端来回震荡。
4. **内容寻址差集**：blob 按哈希存储，只传对方缺的对象（`blob_sync.dart`、`sync_data_plane.dart:19-25`）。本仓复用了自己更早的增量仓库底座（`conversation:<uuid>` 逻辑键 + sha256），在 `LanSyncUpload` / `LanSyncDownload` 里做分段与续传。
5. **记忆端点**：每 peer 最多记 12 个历史端点以适配漫游（`sync_limits.dart:18-28`）。本仓首期改为「配对时记住地址 + 同步页显示本次地址」，端点池留作后续。
6. **阶段化进度**：connecting / exchanging / sending / receiving / files / applying（`sync_engine.dart:26-44`）。本仓 `LanSyncPeerPage` 沿用同样阶段语义。

**本仓的差异（不是照抄）**：

- 传输层用 **mutual TLS**（`SSLServerSocket.needClientAuth = true`，仅 TLS 1.2/1.3，只 bind `isSiteLocalAddress` 的 IPv4），Cuplivo 因为自签客户端证书在移动端不稳而只靠 token 头；本仓两者都保留（token 经 `SecretCipher` 加密落库）。
- Cuplivo 的合并只做「松散恢复」；本仓补了**会话墓碑**（Room v81 `lan_conversation_tombstone`，删除与墓碑同事务），以及**冲突分支**（`LanConversationConflict` + `mergeLanConflict`，败者并入分支不丢内容）。
- 应用阶段做了乐观并发校验（应用前重算本地对象，与计划时的捕获值不符则放弃整轮），并在覆盖前写安全备份。

### 2.2 本地副本的产品口径（已落地）

- **触发**：不做后台任务/通知，只在 app launch / resume 后延迟检查（3 秒、最小间隔 1 分钟），见 `lib/features/backup/local_snapshot_scheduler.dart:11-30`、`57-87`。本仓照此改为 `ActivityLifecycleCallbacks` + AppScope 延迟检查（冷启 8s / resume 3s / 最小间隔 1 分钟）。
- **留存**：最近 N 份 + 上周一份 + 上月一份，且**最新一份有内容的副本永不删**（`local_snapshot_retention.dart`）。本仓落成纯函数 `LocalSnapshotRetentionPolicy`，容量上限从最旧删起、保护集不越界。
- **重命名即排序**：文件名带时间戳（`local_backup_yyyyMMdd_HHmmss.zip`），不留索引文件——删文件即删副本，无账目漂移。
- **成功不必打扰**：默认不提示成功、失败必须提示。本仓加了连续失败 1/2/4/8/16h 指数退避与指纹跳过（内容没变不重写）。
- **存储位置替换**：Kelivo 用 app 目录（卸载即失），本仓改用 **SAF 用户选定目录** + 加密 zip，适配 Android 上「备份要能活过卸载」的诉求。

### 2.3 不照搬

- 不搬 Dart 代码与 Flutter 组件（跨语言，且 AGPL 下更倾向独立重写）。
- 不搬 mDNS/多网络自动发现：首期手工输入 / 扫码，`NsdManager` 留作后续。
- 不搬 `sync_family_registry` 的全量实体族（设置 / 供应商 / 技能 / 工作区内容车道）：首期只同步会话（含分支树）+ 引用附件 + 缺失助手占位。
- 不搬 Cuplivo 的「单设备托管 server / 多 server」形态；本仓是同步时临时开服务端、接收页前台最多开放 5 分钟。
- 不搬其工作区内容双向车道（`workspace_content_sync.dart`）与字节预算调度，留作后续。

## 三、落地记录（2026-11-25）

| 提交 | 内容 |
|---|---|
| `a577e45ccc` | 基础层：`LanIdentity` / `LanTlsServer` / `LanPairingProtocol` / `LanPairingService` / `LanPairingServer` / `LanManifest` / `LanMergePlan` / `LanObjectStore` / `LanTransfer` / `LanSyncDownload`；本地副本：`LocalSnapshotService` / `LocalSnapshotRetentionPolicy` / `LocalSnapshotDirectory` / `EncryptedBackupZip`；UI：`LanPairingPage` / `LanPairingQr` / `LocalSnapshotsPage` / `SnapshotPasswordSheet`；三语字符串；单测 `LanMergePlanTest` / `LanConversationObjectTest` / `LanSyncEndpointTest` |
| `849dcd40f2` | 双向闭环：`LanSyncService` / `LanSyncData` / `LanSyncUpload` / `LanConversationConflict`；Room v81 会话墓碑（`LanConversationTombstoneDAO`）与删除/撤销同事务；冲突分支落库；`LanSyncPeerPage`；真机 instrumented 测试 `LanSyncRoundTripInstrumentedTest` / `LanPairingTlsInstrumentedTest` |

真机验收：两台 Android 15 实体设备完成配对、首次全量、双向增量、删除传播、冲突分支与断点续传；本机自动副本覆盖手动 / 冷启自动 / 留存清理 / 授权失效退避。

## 四、后续候选（未立项）

1. mDNS / NsdManager 自动发现，替代手输地址。
2. 同步范围扩展到助手完整配置、技能、世界书（需要 family 化的对象协议）。
3. 端点池与漫游（采纳 `sync_limits` 的记忆端点思路）。
4. 工作区内容车道与字节预算调度（采纳 `workspace_content_sync` 的 size+mtime 变更检测）。

## 五、Kelivo 创作大版本增量评估（`1e1c1d1a → 78dd1784`，45+ 提交，2026-11-25 同步 / 11-26 评估）

> 同步方式：本地克隆 fetch。本轮只评估**媒体创作整合 + sidebar 重做**两域与 #1518/#1539；其余（桌面端/沙箱修复/iOS 等）不适用。**主线路径无吸收主体**：kelivo「独立工作台（`78c4fbc4`）→ 整合进聊天（`f4836a7c`）→ 结果与聊天/存储打通（`b4a33848`）」的演进，本仓 [design-unified-media-generation](../../design/in-progress/design-unified-media-generation.md) 已落地且架构更系统（统一执行入口 `MediaGenerationExecutor` + `generate_image` 工具卡授权/限额 + 独立创作页 + 定时模板直跑）；kelivo 反而缺本仓「授权来源/任务生命周期/结果去向按发起方决定」的顶层设计。

### 判据记账（3 条 + 2 条观察）

- **媒体恢复语义实弹对照样本**（`a9b3fe75` 完成态恢复不重提交、`c8a59261` 中断创作恢复、`ce6fa598` 超时网关任务不重提交）：与本仓设计稿挂账的「按请求 ID 对账已实现，**等厂商接口实弹**」直接同题——kelivo 已实弹踩过这三个坑，本仓实弹验证恢复语义时**直接对照其恢复/去重处理方式**。本台账最高价值判据。
- **预览内存上限**（`c8a59261` bound preview memory）：本仓发送链有 5MiB + `MediaEncodingCache`，但画廊/生成结果预览无内存上界约束；小判据，若真机出现画廊 OOM 再对照。
- **会话内可见生成图直接作为后续编辑输入**（`27589a67`）：「聊天里刚生成的图一键转参考图编辑」的顺手度改进；本仓已有手动参考图编辑，观察级 UX 细节。
- （观察）**大会话级联删除补 DB 索引**（`78f83431` index generation runs、`8340bd29` keep deletion responsive）：与本仓长会话物理清理/附件删除链对照——本仓删除链路是否已有 generation/中间产物表的索引覆盖，待审计。
- （观察）**长会话 minimap 搜索**（`5272ab67`/`072bc080`/`78dd1784`：普通搜索加速、取消即停、跳过无关读）：本仓无 minimap 概念；判据是「长会话内检索的取消传播与读取边界」——若本仓做会话内搜索/定位，对照其 cancelled-work 隔离手法。

### 无借鉴面

**sidebar 重做**（`c2ef5d46` 移动端自定义布局 + 桌面 rail、`629ad5e7` handoff rail 配套）：Flutter 双端形态，本仓 Android 手机+平板、抽屉形态无对应诉求。
