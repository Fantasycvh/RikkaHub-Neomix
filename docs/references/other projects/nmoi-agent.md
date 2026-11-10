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

## 13.5、2026-11-08 → 11-10 增量评估（2026-11-10，`d7fe1b0..92585e8`，32 提交，D290–D309）

> 证据分级：**S** = 本仓源码 file:line 实读；**D** = NMoi 提交 diff 实读（`git -C F:/build/projects/nmoi show`）；**G** = 本仓 grep 扫描（非穷尽，「0 命中」不证明绝对不存在）；**待核** = 未证实，仅作线索。
> 范围：32 条（功能 17 / 重构 2 / 修复 2 / 文档 11），全 NickEntry、0 merge，`git diff --stat` 44 文件 +6224/−432；阶段判断不变（README/PLAN 本区间未改，「stage 0」一词继续停用）。

总评：**吸收 0 / 候选 5（P2×1、P3×4）/ 已有等价 12 / 不适用 7 / 记账 11（均按项计，`ed9b336` / `22fc123` / `5144a1b` 跨类）**。本轮 NMoi 在「世界书触发时序」「压缩入口 + 文件清单」「视觉覆盖」「多 Key 池」「两级多选 / 拖动排序」上集中补课，方向与本仓既有实现大面积重合——**真正有增量的只有一条判据：压缩摘要保留本段工具痕迹 / 文件清单（D296）**，其余是补课、UI 细节或本仓已领先。另有两处进展要记：D305 把挂了两个月的「压缩没有任何入口」补上（D296 的清单因此才真正可消费）；D291 主动标注「这一批没有本机实测」——「写了没接线」的老毛病仍在（组件先落地、消费者后补），但从「当已实现引用」变成了「自己标注」。

**判决一览**：D290 等价 · D291 等价（+记账）· D292 **候选 P3**（面板）/不适用（会话私有路径设计稿）· D293 等价（注入进内置册）/不适用（/tmp 隔离）· D294 不适用 · D295 不适用 · D296 **候选 P2** · D297 等价+记账 · D298 等价 · D299 等价 · D300 **候选 P3** · D301 等价 · D302 等价+记账 · D303 等价 · D304 等价（本仓领先）· D305 等价+**候选 P3** · D306 等价（本仓领先）· D307 等价 · D308 **候选 P3** · D309 等价（本仓保真度更高）· `84abaf4`/`2d7d38d`/`11ad376`/`92585e8` 不适用 · 文档 11 条（10 条记账；`5144a1b` 归 D293 计）。

### 候选（5，按价值排序）

1. **P2｜压缩摘要里保留本段用过的文件 / 工具痕迹（D296 `1dfda3c`，D305 `566607c` 前置解除）** —— 本仓压缩输入**只取 Text part**：`summaryAsText` 的 `when (part)` 里 `Reasoning` / `Tool` 全为空串（S：`ai/src/main/java/app/androidrik/ai/ui/Message.kt:222-231`），而压缩源正是 `plan.toCompress.map { it.currentMessage }.messagesForAuxGeneration()`（S：`ChatService.kt:2195`）⇒ **工具调用的参数（文件路径）与被读结果都进不了摘要，也没进覆盖率闸的分母**；本仓设计稿已自认这是损失①（S：`docs/design/in-progress/design-multi-pass-compression.md:22`、`:37`）。NMoi 判据（D：`crates/core/src/compress/files.rs`、`compress.rs` 的 `append_section` 两处调用）：从这段工具调用参数里扫会话路径，摘要末尾加「这一段用到的会话文件」一节，12 条 / 600 字封顶，超了写「其余 N 条没列出」；**只搬清单不搬内容**、只留真的还在的、预算不够整节不接、附件不进清单。本仓落点：压缩前扫 `UIMessagePart.Tool.input`（S：`UIMessagePart.kt:173-176`，`toolName` + JSON `input` 现成）抽路径 → 与 `summaryAsText` 产物并列成附录（或让摘要器看到「工具名 + 关键参数」一行）；可写前缀按本仓可写/只读区取 `/workspace`、`/tmp`、`/upload`、`/skills`、`/tool_outputs`（S：`WorkspaceTools.kt:1000-1014`、`di/RepositoryModule.kt:95-113`）。量级 **~1–1.5 天**（扫描 + 去重 + 截断 + 单测，无 UI）。**两处差异待核**：(a) 本仓文件在**工作区**（可跨会话、可远程 SSH、可离线），NMoi「只留真的还在的」在本仓远程/离线工作区拿不到存在性 → 建议清单标「存在性未核」而非静默过滤；(b) 本仓工具痕迹里还有 `/tool_outputs` 这类归档路径，是否都该进清单要看本仓工具输出归档策略。

2. **P3｜世界书触发时序四字段：`matchWholeWords` / `sticky` / `cooldown` / `delay` + ST 导入映射（D307/D308/D309，`22fc123`）** —— 本仓 `RegexInjection` 有 `keywords/useRegex/caseSensitive/scanDepth/constantActive/secondaryKeywords/selectiveLogic/matchAssistantPrompt`（S：`Assistant.kt:621-660`），**没有**整词匹配与 sticky/cooldown/delay；匹配是 `contains`（S：`Assistant.kt:753-768`），ST 导入的 `SillyTavernEntry` 也没有这四字段 ⇒ `ignoreUnknownKeys` 下**静默丢弃**（S：`ExportSerializer.kt:484-520`）。NMoi 判据（D）：sticky 把扫描窗口由 `scan_depth` 伸到 `scan_depth + sticky`；delay = 历史不足 N 条不激活；cooldown = 最近 N 条提到过就先不注入，**判据是「最近一次提到」而非「上一次生效」**——后者要跨轮状态、会和归档/压缩打架，其注释与界面都写明这是近似；整词匹配用词边界、**中文没有词边界要写进界面**。落点：`RegexInjection` 加 4 个同款默认值字段（0 / false）+ `isTriggered` 时序判定 + ST 映射 + 条目编辑面板 3 个数字框 / 1 个开关。量级 **~1 天**。**不要照抄的两点**：(a) 其 `is_active` 里 `nearest >= reach` 是恒假死判断（`window.iter().rev().position` 值域 ≤ reach−1），借判据别抄代码；(b) 整词匹配在 Rust regex 的 Unicode `\b` 下对中文并非绝对不命中（汉字也是 word char，被标点/空格包住仍可能命中），其「等于匹配不到」的界面文案**不严格，待核**——本仓若做，文案写「按词边界（对连续中文基本不生效）」。

3. **P3｜世界书两级多选：批量导出 / 条目复制移动（D300，`74290c9`）** —— 本仓世界书页只有**单册**导出与导入（S：`LorebookPage.kt:416`/`:433`、`:290`），没有多选、没有跨册复制/移动；册与条目两级均已支持左滑删除与拖拽排序（S：`LorebookPage.kt:333`/`:672`/`:185`）。NMoi 判据（D）：册/条目两级多选互斥、状态只活在页面；多册打包成 `{"version":1,"type":"lorebook","books":[…]}` 可原样导回；**条目复制/移动到另一册必须换新 id**（同 id 在别的册里可能是另一条）；长按 2 秒进多选、长按那行自动勾上。本仓已有可复用的多选 + 批量 zip 导出范本（S：`ui/pages/chat/DrawerFilesPanel.kt:64-94` 工作区文件面板），落地主要是 LorebookPage 的 UI 改造。量级 **~1 天（纯 UI）**。价值中等：重度用户整理多册时才有感，排在 1–2 之后。

4. **P3｜手动压缩先 plan 后 run：按前显示「压走哪一段」（D305，`566607c`）** —— 本仓手动压缩对话框只给参数（目标 token / 保留比例 / 保留条数 / 追加指令），点确认就跑（S：`CompressContextDialog.kt:43-250`）；切分函数 `estimateCompressionPlan` 已是纯函数且有测试（S：`CompressionPlanner.kt:69-100`、`CompressionPlannerTest.kt`），真实压缩路径也先在执行侧算 plan（S：`ChatService.kt:2188-2193`）⇒ 对话框打开时先调同一函数显示「将压缩 N 条（约 X token）/ 保留 M 条」成本很低。NMoi 判据（D）：压缩**永久移走一段历史**，先让用户看清移走哪段再按，比点下去就消失的按钮诚实；plan 不调模型、不动数据。量级 **~0.5 天**。

5. **P3｜会话信息面板（长按会话 → 详细信息，六项可长按复制）（D292，`ed9b336`）** —— 本仓 grep `会话信息|详细信息|ConversationInfo` 于 `app/src/main/java` **0 命中**（G）；抽屉长按菜单有置顶 / 重生成标题 / 移动助手 / 移动文件夹 / 记忆 / 删除等，但没有信息面板（S：`ConversationList.kt:385-430` 菜单项、`ChatDrawer.kt:410-460` 回调）。NMoi 六项：会话 id（要能复制，因为上下文里永不出现 id）、名称、当前版本消息数 / 请求数 / 用户数 / 助手数；**「当前版本」不是所有版本加起来**；读不出来写「…」不写 0（0 会被当成空会话）；代价是现读一次库、**不做缓存（缓存 = 第二份真值）**。本仓数据现成（`Conversation.messageNodes`、`ConversationEntity`），量级 **~0.5 天**。价值偏低（诊断 / 外链用：本仓无跨会话工具，会话 id 主要用于 Web API 与反馈排查）——列出来备查，不建议排在 1–4 之前。

### 吸收（0）

无。本轮没有可「直接照做」的判据：D296 是补本仓已自认的损失，但仍需按本仓工作区模型重新设计；其余为补课 / UI。

### 已有等价（12，勿重复立项）

1. **D290 助手级世界书启用集 + ＋面板计数（`9570052`）** —— 本仓 `Assistant.lorebookIds` 即「助手级生效册集合」，另有 `allowConversationPromptInjection` 让会话单独覆盖（S：`Assistant.kt:176`/`:186`）；＋ 面板扩展行显示生效计数（S：`FilesPicker.kt:216-239`），两级选择器按册绑定、只读展示条目、`Lorebook.enabled` 控制是否可选（S：`ExtensionPicker.kt:194-260`）。差异仅「生效 / 启用」双计数 vs 本仓单计数——记账即可。
2. **D291 视觉覆盖：发图前先转录（`a1d33ba`）** —— 本仓 `OcrTransformer` 同角色：主模型无 IMAGE 模态时逐张图调 OCR 模型转文字替换图片、带缓存（S：`OcrTransformer.kt:43-100`），模型用 `settings.ocrModelId`、提示词可编辑 `settings.ocrPrompt`（S：`Settings.kt:98-100`）。差异：本仓只在主模型不收图时触发（`OcrTransformer.kt:66`），失败写 `[ERROR, OCR failed]` 继续而不是回落发原图；NMoi 可强制覆盖 + 失败回落。**不立项**；若将来要「强制视觉覆盖」开关，架构已就位（记账）。
3. **D297 界面四件（`5cedf80`）** —— (1) 默认窗口值：本仓窗口来源是 `customContextLength > contextLength > 全局覆盖 > 256K 兜底`单点解析（S：`utils/ContextLength.kt:20-21`、`Settings.kt:210`），没有 NMoi 那种 placeholder 写死 `128000` 的第二处；(2) 角色卡 ↺：本仓 `onClear` 置 null 即「跟随 / follow」（S：`SettingModelPage.kt:162-272`）；(3) 动作行只留最后一段：本仓动作行按消息渲染，没有「一轮多段各挂一排」形态；(4) 置顶高亮：本仓有置顶分区头 + 图钉（S：`ConversationList.kt:262-280`/`:366`），但没有主色加粗——纯装饰差异，记账。
4. **D298 角色卡齿轮 + 角色系统提示词（`868b722`）** —— 本仓六个旁路角色的提示词均可编辑：`titlePrompt` / `translatePrompt` / `suggestionPrompt` / `ocrPrompt` / `compressPrompt` / `memoryConsolidationPrompt`（S：`Settings.kt:81-103`），角色模型选择在设置页集中给出（S：`SettingModelPage.kt:162-272`/`:587-649`）；「没有消费者就不摆开关」本仓同哲学。差异只是入口形态（集中设置页 vs 每卡齿轮）。
5. **D299 世界书条目左滑删除（`4e52242`）** —— 本仓册与条目两级都已是 `SwipeToDismissBox` + 确认对话框（S：`LorebookPage.kt:333-361`/`:672-700`、`:440-453`）。
6. **D301 供应商 / 模型长按拖动排序（`ae1deb8`）** —— 本仓供应商列表与模型列表都已可拖（S：`SettingProviderPage.kt:89-223`、`ModelListPage.kt:131-200`），且同样「搜索框有词时不给拖」（S：`SettingProviderPage.kt:92`）；本仓用显式拖柄，不需要 2 秒长按（更易发现）。NMoi 的「顺序原来没有存处 → 拖完回弹」是本仓未踩过的坑（本仓顺序即持久化列表）。
7. **D302 多 Key 模式与 Key 池（`950accb`）** —— 本仓是超集：`apiKeys: List<ApiKeyEntry>`（每把 key 带 `enabled`、稳定 id）+ 每 provider 内联编辑器（新增 / 删除 / 启停 / **拖拽优先级**）（S：`ProviderSetting.kt:157`/`:209-213`、`ProviderConfigure.kt:808-925`）；失败按序换 key（S：`GenerationErrorPolicy.kt:89-124`），HTTP 状态码识别比 NMoi「用户手点状态」更准（S：`GenerationErrorPolicy.kt:117-126`）。差异：本仓默认「顺序 = 优先级」（`KeyRoulette.default()` = `OrderKeyRoulette`，S：`ai/.../util/KeyRoulette.kt:16`/`:37`）；**按请求轮询的 `LruKeyRoulette` 已实现，但只在搜索线接线**（S：`search/src/main/java/app/androidrik/search/SearchService.kt:93`，聊天 provider 全用 `default()`，如 `OpenAIProvider.kt:135`）——那是本仓自身可选策略（顺序 = 优先级更贴前缀缓存），不是本轮 NMoi 增量；NMoi 的「手动标正常 / 错误」被本仓启停开关等价，「收藏优先」= 本仓顺序首位。记账。
8. **D303 ＋面板格子（`4beef0b`）** —— 本仓 ＋ 面板已是 `FlowRow` 格子：拍照 / 相册 / 草图 / 视频 / 音频 / 文件（S：`FilesPicker.kt:160-178`），下方是扩展（带生效计数）、手动压缩（带消息数）、提示词预览、定时任务、工作区 / MCP 行（S：`FilesPicker.kt:216-285`）；NMoi 五格中四格是假入口，本仓相反（视频 / 音频只在 Google 供应商下显示，隐藏优于假入口）。差异只有「按住渐深」纯样式（记账）。
9. **D304 文件附件只收文本、拼正文（`996fed2`）** —— 本仓是**超越**：`DocumentAsPromptTransformer` 把 PDF / DOCX / PPTX / EPUB 解析成文字进提示词（S：`data/ai/transformers/DocumentAsPromptTransformer.kt:15`，`document/src/main/java/app/androidrik/document/` 下 Pdf/Docx/Pptx/Epub 四个解析器），NMoi 自认「PDF / 图片 OCR / docx 没做」。
10. **D305 压缩入口（`566607c`）** —— 本仓压缩线本就领先：手动压缩对话框 + 自动压缩阶梯 C3→C2→C4 + 覆盖率闸（硬事实逐字 ≥0.8）+ 检查点表（S：`CompressionLadder.kt:1-60`、`CompressContextDialog.kt`、`ChatService.kt:2147-2300`）；缺的只是 plan 预览（→候选 4）与摘要工具痕迹（→候选 1）。
11. **D306 四角色接消费者（`8cbae23`）** —— 本仓四角色全有消费者且提示词可编辑：标题（S：`ChatService.kt:1985-1990`）、建议（S：`ChatService.kt:2063`）、翻译（S：`data/ai/TranslationGenerator.kt:49`）、压缩（S：`data/datastore/SettingsExtensions.kt:46`、`ChatService.kt:2179-2190`）；另有 fast / memoryConsolidation / imageGeneration / fallback 角色（S：`Settings.kt` 角色字段区）。
12. **D307 / D309 次级关键词 + SillyTavern 导入（`22fc123`）** —— 本仓已有且保真度更高：`secondaryKeywords` + `selectiveLogic` 四逻辑（S：`Assistant.kt:641-643`、`:783-804`）、per-entry `scanDepth` / `caseSensitive` / `useRegex` / `constantActive`、token 预算裁剪（S：`Assistant.kt:861-884`）；ST 导入是独立实现：`entries` 对象识别、`key` / `keysecondary` 的 `/regex/flags` 解析、`selective=false` 丢次键、六个 `match*` 合并成 `matchAssistantPrompt`、ANTop / ANBottom → `AT_DEPTH` 修正（S：`ExportSerializer.kt:333-458`/`:484-520`），比 NMoi 的机械翻译更全。缺的只有候选 2 的 4 个时序字段。

### 不适用（7）

- **D294 `/tmp` 会话隔离（`6622d38`）+ D293 前半（`5144a1b`）**：本仓隔离单位是**工作区**而不是会话——rootfs 内 `/tmp` 按工作区一份、启动时清 rootfs `/tmp` 与 `/var/tmp`、删工作区整目录删（S：`WorkspaceManager.kt:39`/`:89`/`:93-96`/`:476-483`），会话共享工作区是**特性**（多会话 / 子代理 / 终端协同），会话级 `/tmp` 隔离与共享工作台语义冲突；「没有会话上下文时 `/tmp` 报错不回落」在本仓无对应概念。**记账一条**：proot `-b` 目录不存在时静默不生效——本仓容器挂载前是否也 `mkdirs`，**待核**（`WorkspaceManager.kt` 的 tempDir 创建路径未逐行核）。
- **D295 跨会话只读 Session-Files（`c80adee`）**：前提是「会话私有 `/tmp` 与 `/chat`」；本仓无会话私有文件概念（附件是按 folder 的 ManagedFile，工作区文件按工作区共享，S：`data/files/FilesManager.kt:56-77`），需求被「共享工作区持久化」天然满足。工具设计判据仍记档：开关关着**不声明**（不是声明了再拒）、只读、五步校验每步点名、结果带「来自另一个会话」来源、**上下文里永不注入会话 id**。
- **D292 的会话私有路径 / 压缩继承设计稿（`ed9b336` 内 `docs/session-paths.md`）**：同上，路径模型不适用；面板部分已进候选 5。
- **`84abaf4` SwipeDeleteRow 抽取 / `2d7d38d` 多选顶栏动作条抽取 + 2 秒长按**：组件层重构，本仓已有 `DragOrderState` / `SwipeToDismissBox` / 多选组件，无判据增量。
- **`11ad376` clippy / `92585e8` 拖动 1 秒 + 末行居中**：样式与工程细节。
- **D297 动作行只留最后一段**：本仓无「一轮多段正文各挂动作行」形态。
- **10 条纯文档提交**（`0bf7f36` / `216eeb5` / `a2d46b7` / `555af8b` / `8f7843e` / `961b71a` / `411a14f` / `0913e81` / `83ba756` / `fce14bb`）：验证单 / 执行单，计入记账，不单列。

### 记账（判据级，不立项）

- **D292 两条通用判据**：「读不出来写「…」不写 0」（0 是另一种假话）；「低频面板现读一次库，不为它加缓存——缓存就是第二份真值」。
- **D293**「凡应用往上下文里塞的话一律进可编辑内置册」——本仓角色提示词集中在 `Settings` 可编辑（S：`Settings.kt:81-103`）已符合大半；细粒度「逐角色恢复默认」暂无，若做角色齿轮再加。
- **D296 设计约束**（候选 1 详）：只搬清单；预算不够整节不接、草稿正文一字不动；附件不进清单（本仓附件与工作区文件本就是两套，天然如此）。
- **D297**「默认值只有一个来源，界面读常量」——本仓 `ContextLength.kt:20-21` 是单点，同判据已满足。
- **D301**「搜索有词不给拖」本仓已同款（S：`SettingProviderPage.kt:92`）；「行高用 `onGloballyPositioned` 实测而不是固定行高」「`key()` 保住 Composable 身份」「超时只能靠 `withTimeout`」——Compose 细节，本仓 `DragOrderState` 已覆盖前两点。
- **D302**「轮询位置只在内存」「删供应商把 key 池一起清」——本仓 key 存在 provider 文档里，天然随 provider 删除；「状态用户手点、不猜请求结果」本仓反而更准（已能提取 HTTP 状态码）。
- **D303**「不给假开关但也不让点了没反应」；「按住渐深 0→0.9 / 120ms 明确反馈」。
- **D304**「只看 MIME 会把 `.md` 挡在门外 → 扩展名白名单 + 读时看 0 字节」；「截断这件事写在正文里，不只写日志」（本仓文档解析的上限提示是否同款，**待核**）。
- **D305**「plan / run 两步」「不给百分比假进度条」「兜底默认开且返回 `forced` 并照实写」「压缩与聊天用同一个 key 选择」——前两条进候选 4，后两条本仓已同（压缩走同一 provider / key 链路）。
- **D306**「旁路不落库」「没有配置就不发请求、失败不出声；只有用户明确点的那一次才写原因」「建议点一下只填输入框不直接发」「记 `suggestFor` 防删光重发再扣一次费」——最后一条本仓是否有同款去重，**待核**。
- **D307 / D308**「次级键为空 = 老数据零变」「三个时序字段默认 0 = 关」「中文无词边界写进界面」「cooldown 判据是最近一次提到、不是上次生效」——候选 2 的设计约束。
- **D309**「认不出的位置数字点名报错，不替用户挑一个最常见的位置」「`selective=false` 丢 `keysecondary`」「单册分支曾把原始文本喂给导入、转换后的 root 被忽略（真 bug）」——本仓 ST 导入已修同类坑：曾把任意 JSON 静默导成 0 条目空册（S：`ExportSerializer.kt:339-344` 注释），两条可并列做回归清单。
- **「写了没接线」复查**：D296 的 `files.rs` 在 D305 之前只有单测与 example（作者明说）；D298 只给已接消费者的卡上齿轮；D303 五格四假入口但配「差什么」文案；D291 明说这一批没有本机实测（唯一验收面 V102）。⇒ 老毛病仍在（组件先落地、消费者后补），但已从「当已实现引用」变成「主动标注」；引判据时仍要逐个查消费者。
- **未真机验证**：D291 V102（无本机实测）、D300 从 `Switch` 上起手左滑未验、D302 自动标错要等 core 带回错误码（现在带不回）——采用对应判据时须自测。

### 修正既有结论

- **§13.4「本仓世界书是 NMoi 字段超集」收窄**：NMoi 本期补了 4 个触发时序字段（`matchWholeWords` / `sticky` / `cooldown` / `delay`）并映射进 ST 导入（D307–D309）；本仓 `Assistant.kt:621-660` 无这四项 ⇒ 本仓仍是**主键 / 次键 / 四逻辑 / 预算 / 扫描源（6 个 `match*` 合并）/ 大小写 / 正则** 的超集，但**触发时序维度落后**，见候选 2。
- **§13.4「NMoi 只有自有世界书格式、ST 导入仅计划稿」作废**：D309 已落机械翻译（位置 / 角色 / `selectiveLogic` 数字映射 + 认不出报错），且修了「单册分支读原始文本」的真 bug；本仓导入保真度仍更高（正则关键词、`match*` 六开关、ANTop 修正），差异只剩候选 2 的 4 字段被丢。
- **「压缩 / 提供商线本仓领先」维持但两处收窄**：(a) 压缩**输入只取 Text part**，工具调用与结果不进摘要、也不进覆盖率闸（本仓设计稿自认）→ D296 判据补这一块，见候选 1；(b) 手动压缩无「先看切点」预览 → 候选 4。
- **「多 Key 线本仓领先」维持，补一条本仓自身现状**：`LruKeyRoulette` 只在搜索线接线、聊天 provider 全用 `KeyRoulette.default()`（S：`KeyRoulette.kt:16` vs `OpenAIProvider.kt:135` / `ClaudeProvider.kt:90` / `GoogleProvider.kt:97`），NMoi D302 的「按请求轮询」对本仓是**可选策略**而非缺口（顺序 = 优先级更贴本仓前缀缓存策略）。
- **§13.3 候选「零字流退回非流式」（`ddd2860`）** 不在本区间，维持待核。
- **§13.4 记账「世界书命中说明 UI」** 仍未接线（D307 只加了「命中了哪个关键词」的判据函数），维持记账。
