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

## 13.3、2026-10-26 → 11-03 增量评估（2026-11-03，`6149f2a..1dd1a24`，155 提交）

155 条全为 NickEntry、0 merge，按 9 组主题评估（Rust/FFI/桌面/Android 壳本身不移植）。总评：**吸收 1 / 候选 3 / 已有等价 7 / 记账与不适用其余**。

**阶段判断（修正）**：`README.md` 仍写「stage 0（地基）」、`docs/PLAN.md` S0 仍进行中——正式阶段**未晋升**；但功能面已从「写了没接线」推进到 Android 0.15 可用聊天（`turn_run` 真发请求/逐字流式/消息·版本·助手·会话·模型编辑/代理/容器壳）。变化最大的是**过程纪律**（PLAN 每条三态化 + `docs/verification.md` V1–V58 真机清单，commit body 普遍写明「真机 Vxx 未走」）⇒ 结论改判为「**接线率大幅提升，但大量处于已实现未真机验证**」，借鉴仍取判据不取代码。

### 吸收（1）

`e3cbed8`：有 tools 时**显式**发 `tool_choice:"auto"`。省略时该网关走另一条翻译路径，`tool_calls.name` 全程空串、工具全废（真机 2026-11-03 定位）；bigeye `llm.py:983` 独立同证。本仓 `*.kt` grep `tool_choice` **0 命中**，落点 `ai/.../ChatCompletionsAPI.kt:556` tools 块后 +1 行 + 单测（tools 为空时不得发）。**✅ 已落地 `064a8b772`（2026-11-03）：`ChatCompletionsToolChoiceTest` 钉住「有 tools 必发 / 无 tools 不发」。**

### 候选（3）

`ddd2860`（D199）零字流退回非流式重发一次（已有正文不重发，否则会拼出不存在的回答；本仓无此回落，需先实测 EventSource 对「忽略 stream 的端点」的实际行为）· `ed39bf2`（D185）压缩摘要尾附「历史已结清。当前任务：<原样复述用户指令>」（本仓 `CompressPrompt.kt` 无此字段；不概括、不新增消息，避免 `last_user` 指到锚点）· 主题聚类中的其余候选均已有等价或记账（见下）。

### 已有等价（7，勿重复立项）

逐字输出开关 = `Assistant.streamOutput` · 多会话并发 + 发送队列 = `ChatService.kt:737/778/1116` · 压缩窗口读回 = `Conversation.kt:177-194` · 过程块折叠 = `ChatMessageThinkingSteps.kt:39-46` · tool_call 空 id 兜底 = `ChatCompletionsStreamDecoder.kt:232` · 备份带 workspace = `BackupArchiveEngine.kt:120-128` · 重试保留上一版 = `GenerationHandler.kt:1494-1496`。

### 修正既有结论（§13.1）

- **⑤ D46「工具循环一律非流式」存疑**：NMoi 本轮已把**每轮**都改成流式（含工具轮），根因最终定位到网关缺 `tool_choice` 与零字流，而非「流式本身丢 tool_calls」。⇒ 建议改判为「显式 `tool_choice` + 零字退回非流式重发一次后，流式可用」；D46 的原始证据是否同因待核。
- **⑥ 消息编辑分层 D148 已被作者真机两次撤回**：v6 双覆盖层（后改顶掉先改）→ v8「一版两正文列 `origin_message`/`tooledit_message`」，编辑就地改当前显示组。本仓无「AI/工具改消息」层，**不应再作借鉴目标**；可借判据只剩 D150「显示的那一组是唯一真相」。
- **D231–D233 per-conversation workspace 当天回退**：负面样本（迁进去等于把老文件藏起来）——老数据默认留共享、迁移须增量可回退。

> 记账：`0.15an` 并行工具批次、`0.15ax`「吞块」、`0.15ay` 容器覆盖安装均标「未动工」，不要当已实现引用；`0.15ap` 世界书、`0.15aq` 档位设置同。**（2026-11-08 修正：`0.15ap` 世界书 D278–D283、`0.15aq` 档位 D274/D275、`0.15an` 车道并行 D254 均已在本期落地接线，见 §13.4。）**

## 13.4、2026-11-03 → 11-08 增量评估（2026-11-08，`1dd1a24..d7fe1b0`，173 提交，D185–D289）

173 条全为 NickEntry（0 merge），三路源码深读（世界书 / 其余特性簇 / 对照本仓现状）。总评：**吸收 0 / 候选 5 / 已有等价 12 / 记账与不适用其余**。阶段判断：原记账里的三大「未动工」项本期全部落地并接线（世界书、档位、车道并行），「写了没接线」模式收敛为两处残留（世界书命中说明 UI、`reserve_fixed` 未与世界书体积挂钩）；但流程上新增「设计稿先落 docs、实现带 D 编号、验证清单 V 编号」的三段纪律，工程节奏健康。

### 候选（5，按价值排序）

1. **上下文图片清单 + 淘汰 + View-Image 工具（D248/D269/D270/D272）**——本仓**无任何历史图片上下文管理**：附件随消息永久重发，长会话带图上下文只增不减。NMoi 机制：`images.rs` 清单默认 20/硬顶 100、新图置前超容丢最旧；「临时额度」= 满了还能多留几张、活到下一次思考结束（`agent.rs:308` 每轮 trim）；`view_image` 工具（注册 `tools.rs:278`，path 限附件目录）让模型**主动把被淘汰的图拉回上下文**；两种模式 D270——inline 直发 / path 只附路径+要求模型真调得动（`session.rs:554`）。对本仓落点：图片清单可挂在请求组装处（input transformer），View-Image 挂 workspace/工具生态，~2 天。**候选池里最值钱的一项。**
2. **工具调用按车道并行（D254，`1e2b32a`）**——本仓 `GenerationHandler.kt:804` 对 tool_calls **串行** forEach。NMoi 判据：车道 = Read(并发4)/Workspace(1)/Process(并发4)，未声明的默认 Workspace 串行防写冲突；结果按调用下标 slots 回填保证顺序；`agent.rs:535 run_batch` + 单测。对本仓可直接映射（读类工具并行、shell/workspace/审批类串行），~1 天，注意与本仓审批流（Pending 挂起）的交互。
3. **任务锚点（D185，`ed39bf2`，§13.3 已列候选）**——本期已接线（`session.rs:767/1091` 拼进压缩摘要消息末尾，`anchor.rs:26` 从最后一条 user 消息**原样**截取不概括、不新增消息防 `last_user` 指错）。本仓 `CompressPrompt.kt` 仍无此字段，判据不变，~0.5 天。
4. **DB 迁移前留副本（`65e13bf`）+ 迁移可重执行（D260，`284e0b1`）**——`.v<旧版本>.bak` 副本 + 迁移函数幂等（v17 撞 duplicate column 的真机教训）。本仓已有降级隔离重建（beta.14）但**升级路径**无迁移前快照；Room 迁移包一层「先 VACUUM INTO 快照再跑 migration」+ 幂等守卫，与 [[db-downgrade-rebuild-and-schema-version]] 的防护哲学同族补齐另一半，~0.5 天。
5. **发图压缩（`4a7d465` 内）**——本仓发送侧未见位图压缩代码（对照报告确认）。NMoi 发图压缩 + 附件尺寸上限。小件，可与候选 1 同批。

### 已有等价（12，勿重复立项）

- **世界书核心语义（D278–D283）**：本仓是 NMoi 的**字段超集**——本仓有 secondaryKeywords+selectiveLogic 二次收窄（NMoi 多关键词只 OR）、`lorebookInjectBudgetTokens` 预算裁剪（NMoi 无 token 预算）、`lorebookIds` 多册任意绑定（NMoi 只有 enabled 合并无优先级）、SillyTavern 内嵌卡导入（NMoi 只有自有格式，ST 导入仅计划稿）、大小写不敏感枚举（两边都已根治）。位置枚举两边同族（BEFORE/AFTER_SYSTEM/TOP/BOTTOM/AT_DEPTH/BOTTOM_OF_CHAT）。差异判据两条可记账：NMoi 扫描窗口**含 assistant 消息与思考文本**（本仓「非 SYSTEM 消息」是否含思考待核）；NMoi 注入消息带 `SourceTag::WorldBook` 进档位白名单不降级（本仓压缩对注入内容的处理判据可对照）。
- 只换一条 regen（D284）= 本仓 MessageNode 追加候选天然只换那一条；重试失败三选一（D227/D228）= 树结构天然保留两版（残文候选 + 上一版），线性+版本模型才需要三选一弹窗。
- 按角色分模型（D289 六角色 disabled/follow/model 三态）= 本仓 Settings 已有 9 个角色字段（title/suggestion/ocr/compress/translate/fast/memoryConsolidation/imageGeneration/fallback），translate 回落 chat 即「follow」态。
- 代理栈（D226）= 本仓全局 + per-provider 代理（`SettingsProxySelector` + provider 级 Inherit）比 NMoi 全局单代理更细；其本地 SOCKS5 收拢端点与容器 netshim 垫片不适用（容器接线已停，bionic/glibc 不兼容，D237 只留清理——负面样本）。
- 「正在生成」卡死修（D249 finally + 对账）= 本仓已根治（[[generation-hang-subagent-runjob]]，SubagentRunner 裸 runJob）。
- 流式逐字 / 停手自动保存 / 会话绑工作区（D271，`workspace_id` NULL=跟助手）= 本仓同形（助手绑 + 会话 `workspaceCwd` 覆盖）。
- 消息=parts/v15、按块编辑、折行、版本=块（D255–D259）= 本仓 UIMessage content parts 同构，线性版本层是其存储形态的必然，无增量判据。
- 会话私有附件目录 + 删除即清目录（D216）= 本仓 FilesManager filesDir 子目录同族；存储管理页本仓刚做完工作区磁盘占用（`6b049a40f`），会话图片维度暂无需求。

### 记账（判据级，不立项）

- **压缩两条线默认 0.45/0.55 + 默认窗口 1M（D285/D286，`budget.rs:313/721`）**：作者真机迭代后把触发线从 0.75/0.92 大幅下调到建议线 0.45/绝对线 0.55、窗口默认 128k→1M。本仓 ladder 触发默认 80%——**方向相反的数据点**，等净化观察期（[[context-purification-design-state]]）双口径数据回来后一起判，不单独动默认值。
- **edit_message 工具（D177）**：模型经工具改自己历史消息（只排队、轮收尾统一落库 `session.rs:1287`；锁定消息拒绝；只读工作区不声明；改写落红 `blockedit.rs:6`）。本仓无此能力且暂无需求场景，安全边界四件套记档备查。
- **随包预设四态（D178/D287）**：`preset.rs` Follow/LocalEdited/UpstreamNewer/Conflict，判据 = 本地摘要 vs base_hash、上游 version/hash（非时间戳）；内置册 = 虚拟册不复制数据、「恢复默认」=逐条 follow、补缺不删。本仓若将来做「随包提示词/预设内容更新」机制，这套状态机是现成判据。
- **世界书命中说明 UI（`summarize`/`matched_keyword` 已导出未接线）**：又一个「写了没接线」残留，若本仓做 lorebook 命中调试面板，直接实现「上下文面板显示每条命中关键词」即可，不参照其半成品。
- 模型字段来源徽标/资源更新按字段对齐（D201–D213）、界面刷新周期可调（D277）、模型多选与五档资源匹配（D202/D214）——壳层 UX，无判据增量。

### 不适用（Rust/FFI/桌面/壳层本身）

signing 指纹钉死（D170）、tar.gz 自研解包（D164）、容器覆盖安装自检（D165/afcf82f）、netshim arm64 CI、目标占用报告脚本、抽屉/过程卡系列 UI 修复（D209/D265–D268）、Windows 阻塞继承修复（e2bb959）等基建与壳层件。

### 修正既有结论

- §13.3 修正项⑥「消息编辑分层不再作借鉴目标」不变；但 D150「显示的那一组是唯一真相」在本期继续演化成 v15 parts 投影（`04e8bce`）——本仓 parts 模型天然满足，判据关闭。
- §13.1⑤ D46 已彻底改判（本期全流式 + `tool_choice` 显式 + 零字退回三件套），原「工具循环一律非流式」结论作废。
- 阶段词「stage 0」从本期起停用：README 仍写 stage 0 但功能面（世界书/档位/并行/图片管理/存储管理/代理）已越过可用线，风险只剩两处残留接线（见上）。

> 判决汇总（候选 7 / 记账 14 / 等价 12+ 的拍板清单）：[docs/peer-verdicts-2026-11-08.md](../../peer-verdicts-2026-11-08.md)
