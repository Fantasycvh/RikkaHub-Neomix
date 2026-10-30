# NMoi-Agent（NickEntry/NMoi-Agent）借鉴评估

> 评估：**2026-10-26**（原 §十三，本地源码调研 `F:\build\projects\nmoi`，commit `6149f2a`）。
> 定位：Rust 无头核心 + 各平台原生 UI 的本地优先 LLM 客户端；**stage 0 且大量「写了没接线」** ⇒ 借鉴价值在思路与判据，不在移植代码。代码质量高，每个魔法数带实测依据。
> 返回 [索引](../../peer-apps-benchmark.md)。

## 十三、NMoi-Agent（NickEntry/NMoi-Agent）评估（2026-10-26，本地源码调研 `F:\build\projects\nmoi`，commit `6149f2a`）

**定位**：Rust 无头核心（~29.3k LOC，510 测试）+ 各平台原生 UI 的本地优先 LLM 客户端（OpenAI-compat / Responses / Anthropic / Gemini 四端口）。**状态 stage 0 且大量「写了没接线」**：写入时刻路由/内容形态 F1-F5/退化检测均有测试但未接进 session/stream 主路径；压缩梯队 L1-L3 仅设计稿（docs/compression.md 自注「未实现」）；Android 壳 ~3.2k LOC 无 DI 无 ViewModel、发送按钮灰置。⇒ **借鉴价值在思路与判据，不在移植代码**（唯一例外是可独立移植的小件）。代码质量本身很高：每个魔法数带实测依据（token 估算误差表、D143 窗口形态修正的口头禅率 0.00→2.30），决策编号（D44/D137/D143/D148）贯穿注释。

### 13.1 值得借鉴（按价值排序）

**① 备份发布前自校验**（`crates/storage/src/backup.rs`）——备份 = Online Backup API 导出到 `dest.tmp`，**在副本上**跑 `integrity_check` + `foreign_key_check`（integrity_check 不查 FK，两个都要），通过才原子改名发布；失败不发布、旧备份不动。fork 已 VACUUM INTO 快照（BackupArchiveEngine.addDatabaseToZip），加一步对快照跑 integrity_check 再进 zip 即可，~0.5 天，直接补强批次 3。**本节最划算项。**

**② 重复读路由 R4**（`crates/core/src/router.rs:167`）——同路径/URL 重复读：未变 → F3 掩码占位（~10 tok），变了 → F2 截断+「与上次差 N 处」。fork 读文件分页已有、**重复读检测空白**——agent 循环反复 cat 同一文件是最常见的上下文浪费。路由共 9 条确定性规则（用户输入/protected 永不路由、二进制→指针、>32k→指针、>8k→截断、>2k 仅可预测类型截断），纯函数命中即停；连同 F1-F5 形态（指针行带 source+位置+token 数+一行描述，模型可自行决定取回）与「内容寻址 ref + 取回确定性严格（不存在就报错，拒绝模糊匹配）」整套归档观 → **沉淀进 `design-tool-output-budget.md`**。

**③ 卸载成本模型两判据**（`budget.rs`）——对批次 1 的 85%/90% 闸门是判据级修正：
- **净节省显式计入回灌成本**：`d·r·turns − (L−i)(w−r) − Σ(refill×turns)`，有测试证明回灌可把节省翻负（budget.rs:873）；
- **`reserve_fixed`**：系统提示/skills 等不随压缩收缩的固定开销先扣再算百分比阈值（budget.rs:96），防「每轮都触发压缩」死循环。

**④ 内容级脱敏**（`redact.rs`，6 类正则：ghp_/sk-/AKIA/PEM 私钥块/URL 凭据/JWT）——在工具输出**进归档时**脱敏（URL 凭据规则须排最前，userinfo 可能嵌 token），命中只记 kind+长度不留片段，幂等有测试；导出时 `contains_secret` 二次扫描（注意：该函数当前只有测试引用，导出路径未接线——抄判据别照抄「已实现」的印象）。fork 批次 4 只做了**日志**脱敏，内容级空白。与上游 #2254 备份加密同属「数据离开设备」防线。~0.5 天，可与 ① 同批。

**⑤ 实测判据若干（判据级记账，不实现）**：
- **D46：某上游流式静默丢 `tool_calls` ⇒ 工具循环一律走非流式 `complete()`**——值得对着 fork 常用的中转场景核实一次；
- usage 归一表：Anthropic `input_tokens` 不含 cache 需加 `cache_read+cache_creation`、Gemini `usageMetadata` 累计值用 max 合并、`message_start` 的 cache 计数是占位不可信；
- token 估算**按 (模型，层级，内容类) 三维标定**：L1 用 cl100k 估中文误差 1.94×；EWMA α=0.3、≥3 样本、ratio 夹 0.7–1.8、<1500 token 样本拒收（固定开销稀释）；安全系数只用于预算线判定（L0 ×1.3 / L1 ×1.1 / 已标定 1.0），展示值不带系数；
- 退化检测：流式 n-gram（n=5）连续重复监视器 min_repeats=20 且 min_words≈2000 门控（生成中途停），与 fork loop_detector（回合间）位置不同、互补。

**⑥ 消息编辑分层 D148**（schema v6，`6149f2a`）——用户编辑与 AI/工具改写分独立层（`user_text`+`user_edit_of` vs `edited_text`+`edited_by_tool`），红/蓝色只表示「被工具/AI 改过」，「我改了个错字」不再染成「模型重写过」；配套 D137 的教训：**列表摘要查询的键与实体加载的键必须对齐并用测试钉住**（真机踩过：列表显示 0/0、面板读出 2/2）。对 fork 是 DB 40+ 级另议，与会话级覆盖列议题放一起。

### 13.2 已有等价 / 不吸收

- **已有等价**：三级截断+截断告知（行 2000/256KiB/单行 4KiB + remaining 计数 + 恢复提示——fork 批次 1 同款且阈值动态化更细）；`force_full` 逃生口优先级最高（fork read_file 分页可覆盖，可顺手补一个同名参数，小）；路径安全 resolve/ensure_inside（fork SkillPaths + zip-slip 防护同族）；unique-match edit + AmbiguousMatch（fork edit 工具同款）；上下文分界线（fork 检查点模型无此概念且无意引入）；protected/pinned/locked 三标记（fork 无多级编辑锁需求；注意其反直觉决策 Q7/D11：locked 允许压缩）。
- **不适用**：Rust/FFI 架构（C ABI + panic 不跨界 + JNI 桥 + 符号一致性 CI 脚本——工程上漂亮但前提是业务逻辑在 Rust）；crates/cli doctor（fork 无独立可执行诊断入口的形态）；**Android 壳整体**（stage 0 骨架）；NativeEditText 决策记为**已知问题参照**而非借鉴项——BasicTextField 横屏全屏提取编辑退格残留/密码框明文闪（其文档自认依赖 Compose foundation 版本、非独立可证），fork 纯 Compose 遇到再回来查；D146 返回键分层（fork 已有等价 BackHandler 分层）。
- **不照搬**：启动校验 L0-L3 全套 + 隔离区/undo 双审计表（fork 恢复走 safety_backup 全量回滚路线，行级 quarantine 是另一套哲学；但 **L0 的 WAL 取证一条值得记**：主库缺失而 -wal/-shm 还在 = 同步撕裂 → 拒开；0 字节主库 + WAL 存在 → 拒开（SQLite 会静默丢 WAL）——fork 恢复路径已清陈旧 sidecar，但**启动路径**无此检查，记账备查）。
