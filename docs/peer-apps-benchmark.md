# 同类应用借鉴评估（索引 + 汇总）

> **本仓说明**：本文写于私有开发仓的开发过程中，恢复时已按本仓实际改写——提交短哈希已重映射到本仓历史中的对应提交（映射不上的说明该提交未随本仓发布）；`<work>` / `<device>` / `<tmp>` 等尖括号串是路径与设备信息的占位符。本仓基线 = 上游 `2c980642`（RikkaHub `2.4.5`），此后对上游修复/功能按需**人工评估吸收**（不做 ff-only 同步、改动不回推上游）；包名 `app.androidrik.rikkahub`、显示名 **Neomix**、发布分支 `master`、**已发首个正式版 `v1.0.27`**（当前源码 `1.0.28-beta.13`（315）（预发布）；**自 `beta.7` 起随 release 提供 arm64-v8a APK 资产**，更早版本仅 tag、无资产，CI 自动发版仍禁用）。

> 生成时间：2026-08-28（Yuihub 记忆机制评估 2026-09-14 补，见 [`yuihub.md`](references/other%20projects/yuihub.md)）
> **2026-10-30 按应用拆分**：正文（每个应用的评估原文）迁至 [`docs/references/other projects/`](references/other%20projects/)，本页只保留**索引、跨应用章节与汇总分析**。拆分只重排位置，正文逐字保留（仅 4 处链接按新位置修正了相对路径）；每个应用文件头部标注了它对应原 §几。
> 许可证结论：涉及的同类项目多为 AGPL-3.0（与本仓一致）/ MIT / Apache-2.0，**结论均为「不摘代码、只取设计」**（个别可独立移植的小件除外，文内已单独标注）。逐项许可多数写在应用文件头部，个别在正文（如 bigeye 标注其无 License、华灯为 AGPL），拿不准时以各项目 LICENSE 为准。

**怎么读这份文档**：

1. 要**做事** → 直接看 §三「落地顺序与状态」（唯一口径的当前状态）。
2. 要**判断方向** → 看 §四「汇总分析」（跨应用反复出现的判据、已明确不做的清单）。
3. 要**读原文** → 按 §一 的表进对应应用文件；旧引用里的 `§6.2`、`§11.3 E`、`§四.7 D` 用 §五 的章节号对照表定位。

---

## 一、应用清单（索引）

**评估深度**：`源码` = 本地逐条读源码核验；`远端` = 仅远端仓库 / README / 论文与 CHANGELOG；`二手` = 转述他人或自己的笔记。

| # | 应用 | 域 / 形态 | 深度 | 评估日期 | 结论一句话 | 文件 |
|---|---|---|---|---|---|---|
| 1 | rikkahub-agent | 同源 Kotlin，Agent 化改造成 80+ 设备工具 / 工作流 / Cron / 子 Agent | 源码 | 2026-08-28 | **摘取成本最低**；ContextBudgetPlanner / LoopGuard / AgentRun 账本 / 工具审批模型已陆续落地 | [rikkahub-agent.md](references/other%20projects/rikkahub-agent.md) |
| 2 | SoLab | Kelivo 二次开发，APK 逆向工作台（Flutter + Kotlin） | 源码 | 2026-08-28 / 08-31 | 逆向域无关；**客户端工程件**可取（工具 schema 三档、结果外置、previewToken 审批、保活四件套）；MCP host 未立项 | [solab.md](references/other%20projects/solab.md) |
| 3 | Yuihub | RikkaHub fork，仅 en/zh | 源码 | 2026-09-14 → 10-26（四批） | 记忆/备份/压缩三域**落地最多**：评分预算、提取整理器、工具循环上下文闸、备份 manifest + staged restore | [yuihub.md](references/other%20projects/yuihub.md) |
| 4 | SillyTavern | 同域竞品，注入 / 预设 / 世界书 | 源码 | 2026-09-16 → 09-21 | 用户判断「后期堆面板」正确；**只借不是面板的那部分**（世界书激活质量 + 预设槽位序），已落地 6.8 / 6.11 / 6.12 多批 | [sillytavern.md](references/other%20projects/sillytavern.md) |
| 5 | senera | 宿主型 TS Agent 工作台，命题是「执行可审计」 | 远端 | 2026-10-02 / 10-26 | 动态工具检索 / 元工具桥的成熟参考；**schema 三档缩水版已落地** | [senera.md](references/other%20projects/senera.md) |
| 6 | 华灯（rikkahub-sillytavern-android） | 另一条 RikkaHub × ST 融合路线 | 源码 | 2026-10-05 / 10-14 | 价值在**待办命中**与提示词调试面（`PromptDebugCache` 借自它） | [huadeng.md](references/other%20projects/huadeng.md) |
| 7 | agentd | Python 自托管**运维** Agent（SSH / 浏览器 / 屏幕） | 远端 | 2026-10-08 | 不同源不同域；经验记忆 + 信用记账**只记账不立项** | [agentd.md](references/other%20projects/agentd.md) |
| 8 | bigeye | Python 桌面/服务端，「无限上下文」→ 服务「当下关注」 | 源码（逐模块精读） | 2026-10-14 / 10-16 / 10-26 | 记忆机制最有信息量：**反思回路不做、记忆网络不做网络、连续思维补「工作记忆」**；§11.3 E 判据度量是本仓记忆清单层的源码 | [bigeye.md](references/other%20projects/bigeye.md) |
| 9 | billion-context | TS 上下文压缩中间件（kernel + HTTP 代理） | 远端（含论文 / CHANGELOG） | 2026-10-18 | 证明本仓「单 checkpoint 替换式压缩」是**结构性反模式**：追加式块表 / 不可变头 / breakeven 模型；**未落地** | [billion-context.md](references/other%20projects/billion-context.md) |
| 10 | archify | JS 图表产物生成（84.1k★），不是同类 app | 远端 | 2026-10-16 | **抄契约、不抄运行时**：typed JSON IR + 交付前原子校验 + 修复清单；Node CLI 在 Android 跑不了 | [archify.md](references/other%20projects/archify.md) |
| 11 | NMoi-Agent | Rust 无头核心 + 原生 UI，stage 0「写了没接线」 | 源码 | 2026-10-26 | 思路与判据可借：**备份发布前自校验** + **内容级脱敏**（各 ~0.5 天，近期可做） | [nmoi-agent.md](references/other%20projects/nmoi-agent.md) |
| 12 | PurrCat（PurrPod/purrcat） | Python + Electron + Docker 的 local-first Agent 宿主（桌面） | 源码 | 2026-10-30 | 代码复用 ≈ 0、方法论价值高：**收尾闸门 / 能力生产线 / 子代理交付物契约** 三项建议，均未落地；含第三方文档偏差核验 | [purrcat-analysis.md](purrcat-analysis.md)（独立成文） |
| — | 上游 `3a533a6a` | 上游工作区终端后台运行 + 多 Tab | 源码 | 2026-08-28 | 多 Tab 底座以吸收上游为准（见 §二） | [upstream-analysis.md](upstream-analysis.md) |
| 13 | BiliPai | B 站第三方客户端，液态玻璃工程化参照系 | 源码 | 2026-10-31 | **门控层 + 可读性 + 回归测试**是本仓液态玻璃缺的防线；haze 2.0.1 / SDK 分级 / 前后台 gate / 51 个 UI 回归 | [bilipai.md](references/other%20projects/bilipai.md) |

> 新增一个应用时按 §六 的约定加行、加文件、加章节号；**不要**把评估原文再塞回本页。

---

## 二、跨应用：结合上游 `3a533a6a` 的终端基线（原 §二）

`3a533a6a`（已在 master，特性分支待吸收）：`WorkspaceTerminalSessionManager` 提升为 AppScope 单例、会话生命周期与页面解耦、`SecondaryScrollableTabRow` 多 Tab、`closeWorkspace` 在 rootfs 替换前串行化创建任务。

rikkahub-agent 的终端（`WorkspaceTerminalSession.kt`，336 行）**仍是单会话**（proot rootfs + `/workspace`、`/skills` 绑定挂载 + RootfsPatcher 写 DNS），落后上游一代；且因 Termux `TerminalView` 无公开换绑 API，反编译版可见其直接跨包改内部字段（`mTermSession`/`mEmulator`），属脆弱写法。**结论：多 Tab 底座以吸收上游 3a533a6a 为准。**

其上有三个上游没有的增量值得补齐：

1. **终端内 URL 点击**（上游 7da69770 中 grep 为 0，直接可摘）：`getColumnAndRow` 定位 → `getLineWrap()` 上下扩展软换行还原长 URL（上限 50 行防全 transcript 遍历）→ 宽字符用 `[0, column]` 文本长度精确换算点击下标 → 尾部标点裁剪（`.,;:!?)]}'"`）→ `ACTION_VIEW` 打开。
2. **Agent shell 四重预算**：单命令超时（60s）+ 单轮总预算（600s）+ 最大步数（32）+ stdout/stderr 截断（8KB），配 `workspace_run_background / _status / _kill` 三工具 + `TailBuffer` 输出尾缓冲追踪后台进程。上游 3a533a6a 只解决"页面不杀会话"，未约束"Agent 跑命令失控"。
   - **本仓状态（2026-09-26）**：三工具 + `TailBuffer` **已落地（`7207a1a2`）**；与同源实现不同处：并发上限 4（超限拒绝不排队）、完成即淘汰保留 20、审批分档（run_background/kill 需审批、status 免审批）、LoopGuard 把状态查询加入轮询豁免。单轮字符/步数预算已另落地（`4a91f932` `WorkspaceTurnBudget`，口径=输出字符 + 步数）；**后台任务时长上限已落地**（09-26 收尾，30 分钟看门狗 + `TIMED_OUT` 状态 + 完整输出落盘 `.tasks/`）。详见 `design/design-workspace-background-tasks.md`。
3. **Termux 双路线失败归类**：内嵌 terminal 库之外有外部 `RUN_COMMAND Intent` 路线，探测命令 + 枚举失败原因（未安装 / 无权限 / `allow-external-apps` 未开 / 超时）。

---

## 三、落地顺序与状态（原 §五）

> 这是**唯一口径的落地状态表**。各应用文件里的「建议顺序 / 待排期」多为评估当时的原文存档，冲突时以本表为准。

> **落地状态（2026-10-02 复核，2026-10-12 更新）**：① 终端多 Tab **已落地**（`86cd6a70`）；② 的 **ContextBudgetPlanner 已落地**（`f821181c`），终端 URL 点击仍待做；③ LoopGuard/AgentRun 账本 **已落地**（`15a4dc8c`/`4adcda07`）；④ WifiLock **已落地**（`935f8b97`）+ LoopGuard 已落地；⑤ 的 seed 迁移 + dry-run 审批 **已落地**（`2d30c528`）、**schema 三档缩水版也已落地（`8bf348d0` + `05571808`，10-06）⇒ 本行「仍待排期」已过期**；⑥ MCP host 仍是真·未立项（其前置后台任务三件套已就绪）；⑦ 记忆打分已落地（`91d88551`）；⑧ **记忆提取管道 + 整理器已落地**（见 [`yuihub.md` §4.4](references/other%20projects/yuihub.md) 末复核），不再是「未立项」。

| # | 来源 | 内容 | 成本 |
|---|---|---|---|
| 1 | 上游 `3a533a6a` | 终端多 Tab 会话管理 | ~~低~~ ✅ **已落地**（`86cd6a70`） |
| 2 | rikkahub-agent | 终端 URL 点击（单文件）、~~ContextBudgetPlanner~~ ✅ 已落地（`f821181c`） | 低（剩 URL 点击） |
| 3 | rikkahub-agent | ~~LoopGuard~~ ✅（`15a4dc8c`）、~~AgentRun 账本~~ ✅（`4adcda07`）、工具审批模型（部分已落地） | ~~中~~ 基本收口 |
| 4 | SoLab | 保活补强（**WifiLock ✅ `935f8b97`** + specialUse 决策依据）、~~ToolCallLoopGuard~~ ✅（`15a4dc8c`）、工具结果外置 + 反翻页（部分由 .tasks/ 落盘替代） | ~~低-中~~ 基本收口 |
| 5 | SoLab | ~~世界书 seed 三重迁移、previewToken 审批~~ ✅（`2d30c528`）、~~schema 三档加载（缩水版）~~ ✅ **已落地（`8bf348d0` + `05571808`，10-06）** | 收口 |
| 6 | SoLab | MCP host 模式（手机当桌面 AI 工具箱） | 高，按需；**一旦立项即成为第 4 个工具装配方**（远程调用者需设备工具子集、复用审批/loop guard，见 `upstream-analysis.md`「注意点」第 9 条装配方盘点） |
| 7 | Yuihub | ~~记忆打分校准 + 字符预算~~ ✅ 已落地（`91d88551`，真机验证） | ~~低~~ 完成 |
| 8 | Yuihub | ~~记忆提取管道 + 整理器~~ ✅ **整理器已落地**（V1/V2/V3-b1~b3，`f4c2def2`…`63a92148`，10-09）；提取以 `memory_tool` 主动调用形态覆盖 | 收口 |
| 9 | Yuihub 3.0.0 | ~~**工具循环上下文闸**~~ ✅ **已落地（2026-10-26，`4d32eea84`+`abb2b0bc8`+`d5ef5a3be`）**：A per-call 动态限幅（65/80 分级+恒截断+能力三分支指引）/ B 85% 紧急卸载+90% 优雅停止（请求期投影，阈值比 Yuihub 收紧）/ C 聊天接入 ContextBudgetGuard；单测 11 项；真机验收待批次验收 | ~~中~~ 代码完成 |
| 10 | Yuihub 3.0.0 | ~~**MediaEncodingCache**~~ ✅ **已落地（2026-10-26，`7396efbc4`）**（8MB/2MB LRU，三 encodeBase64 挂缓存） | ~~小~~ 完成 |
| 11 | Yuihub 3.0.0 | ~~**ReadOnlyMountSnapshots**~~ ✅ **已落地（2026-10-26，设计稿 `design-readonly-mount-snapshots.md`）**：/upload 只读快照 bind（全局存储跨工作区复用、恒复用与 Yuihub 有意差异、runner require fail-fast）；单测 17 项；真机验收待批次验收 | ~~中~~ 代码完成 |
| 12 | Yuihub 3.0.0 | ~~**备份 manifest v2 + 双 SHA-256 + 恢复预览 + RestoreByteBudget**~~ ✅ **已落地（2026-10-26，设计稿 `design-backup-manifest-and-safety.md`）**：manifest 末条目、settings 缓冲校验后才写、单条 2GB/总 8GB 预算、预览卡（inspectBackup + BackupRestorePreviewDialog，3 语言）；纯函数校验 11 项单测；真机验收待批次验收 | ~~1 天~~ 代码完成 |
| 13 | Yuihub 3.0.0 | ~~**备份 A 档：恢复前自动安全备份**~~ ✅ **已落地（2026-10-26，同上）**：safety_backup/ 滚动 3 份、失败不阻断恢复、FileFolders 登记 + CoverageTest 钉豁免 | ~~0.5 天~~ 完成 |
| 14 | Yuihub 3.0.0 | ~~小件打包：**provider 日志脱敏**（正文 chars=N、结束事件保留 event 名）、**ToolSchemaCompactor**、**TodoTools `next`+无 in_progress 警告**~~ ✅ **已落地（2026-10-26）**：四 provider 请求体/SSE/错误体全部元数据化（`LogRedaction.kt`，println 清零）；ToolSchemaCompactor 移植 + Settings 三档（默认 OFF，设置→通用）；TodoTools next/警告同款 | ~~小~~ 完成 |
| 15 | Yuihub 3.0.0 | ~~（可选-高价值）**B 档 staged 换库 + 启动应用 + 失败回滚**~~ ✅ **已落地（2026-10-26，阶段 1+2：`ab0c16803`+`586955c20`，设计稿 `design-backup-staged-restore.md`）**：PendingRestoreStore 状态机（现库 rename 让位零拷贝，13 项 JVM 测试）+ 启动期换库（startKoin 推迟/StartupState/RouteActivity 全屏 gate/引擎 stage-only）；真机回归修三件（Stream Closed、gate/SafeMode 提前解析 Koin、重复退出提示）；**阶段 3 真机验收待做**（§9） | ~~2-3 天~~ 代码完成 |
| 16 | Yuihub 3.0.0 | 可选小件（记账不排期）：history_search/read、memory keywords、写入 Jaccard 去重、Skills 内联上限、增量 emit、整理事件流水、query≤128 校验、backup_rules 钉白名单 | 按需 |
| 17 | NMoi-Agent | 小件打包（近期可做）：**备份快照发布前自校验**（VACUUM 快照上跑 integrity_check，通过才进 zip）+ **内容级脱敏**（工具输出进历史时 6 类正则，见 [`nmoi-agent.md` §13.1](references/other%20projects/nmoi-agent.md)） | 各 ~0.5 天 |

**记忆机制结论（Yuihub，详见 [`yuihub.md`](references/other%20projects/yuihub.md)）**：值得借鉴的是**检索与写入的组织方式**（打分校准 / 提取管道 / 整理器），**不是语义向量**——Yuihub 上线语义检索后第二天整套删除（`a8c33b2d` → `fbd15331`），构成「记忆语义检索不做」的独立二次印证（与 `design-auto-compaction.md` §6.3 成本结论一致）。

**LoopGuard 移植提醒**：必须带 SoLab 2.3 的 `ToolCallLoopGuard.forget()` 补丁（失败调用移出指纹窗口，见 [`solab.md` §三](references/other%20projects/solab.md)）——否则网络恢复后相同参数的合理重试会被误判 `LOOP_DETECTED`。

---

## 四、汇总分析

### 4.1 跨应用反复出现的五条通用判据

这五条不是某一家的观点，而是**多个独立来源撞到同一结论**，所以可信度高于单篇评估：

| # | 判据 | 独立来源 | 本仓落点 |
|---|---|---|---|
| 1 | **语义检索不做** | Yuihub 上线语义检索后**第二天整套删除**（[§4.1](references/other%20projects/yuihub.md) 时间线）；`design-auto-compaction.md` §6.3 的三条独立证据（本仓迁移成本 / 云同步与隐私定位冲突 / Yuihub 实证） | 已定案：打分 + 预算 + 词元重叠（`MemoryRelevance.kt`） |
| 2 | **判据不是杠杆，预算才是；而「怎么压」比「压多少」更贵** | bigeye §11.3 E（清单层度量：判据换方向只值 +0.5 覆盖度）+ billion-context §11.4 D（5 次小折叠重付 2.29×/tok vs 1 次大折叠 0.032×/tok，差 ~70 倍） | 先定预算线（`memoryInjectBudgetTokens` / `ContextBudgetGuard`），再谈布局 |
| 3 | **失败路径要给「修复清单」，不是让模型重掷** | SoLab `previewToken` 指纹不匹配回 `expectedArguments`；rikkahub-agent Edit 三级匹配失败时错误文案即修复指令；archify `supportedFixes` 只许按清单修 | `ToolResultBalancingTransformer` 已管「配平」；**「可自修的错误载荷」仍是空白项** |
| 4 | **长工具输出一律「落盘 + 句柄 + 受限翻页」** | SoLab 16KB 预览 + `get_tool_result` 只准续读一次；senera 工具结果 → Artifact 投影；NMoi R4 重复读路由（未变→掩码占位）；billion-context 大输出到达即外置（CAS + 确定性占位符） | `.tasks/` 落盘 + 8KB 尾部 + `ReadFileWindow` 已有骨架；判据可直接补进 `design-tool-output-budget.md` |
| 5 | **后台 / 无头运行必须显式状态机 + 终态** | rikkahub-agent `AgentRun` 的 `awaiting_approval` 一等状态 + `process_lost` 终态；Yuihub `StartupState` + `PendingRestoreStore` 恢复状态机 | **本仓缺口**：后台任务表是内存态（见 `design-scheduled-ai-tasks.md` 的 Room 权威状态取向） |

### 4.2 评估方法论（本页真正的沉淀）

**三档证据，不混用**：

- `源码`：8 个（rikkahub-agent、SoLab、Yuihub、SillyTavern、华灯、bigeye、NMoi-Agent、PurrCat —— 其中 bigeye 是逐模块精读 8410 行、Yuihub 是四批增量同步）。
- `远端`：4 个（senera、agentd、billion-context、archify）——**结论一律限定在「机制/判据」层面，不当实现细节依据**。
- `二手`：零星出现（如 `design-subagent-context.md` 里的 rikkahub-agent 子代理三层结构），文内已显式标「属二手，未逐行核对」。

**三条教训**：

1. **自述 ≠ 事实，先核验再记账。** billion-context 把「5×」标成 `[A]` 反事实、把「tier-3 从未被观测」写进 README，是这个清单里**最好的证据纪律榜样**；反例见 `docs/purrcat-analysis.md` §七（第三方 README 宣称的字段无消费方、示例写法照抄会崩）——**凡要移植的，先 grep 消费方**。
2. **评估一家的「缺点」往往比优点有用。** SillyTavern 的后期面板堆叠、bigeye 的「不建议生产」、NMoi 的「写了没接线」，都是免费拿到的负面样本。
3. **同名不同物要拆开算。** bigeye / billion-context / senera 三方在「上下文策略」和「自主性」上**互相不认同**（详见 [billion-context.md §I](references/other%20projects/billion-context.md)）——「自主」是产品取舍，不是技术收敛，不可当普适结论。

### 4.3 已明确「不做」的方向（跨应用汇总）

| 方向 | 出处 | 理由（一句话） |
|---|---|---|
| 语义检索 / 向量记忆（HNSW） | Yuihub §4.1、`design-auto-compaction.md` §6.3 | 上线次日删除 + 短文本区分度差 + 与云同步/隐私定位冲突 |
| 反思回路 / 记忆网络本体（图 / 向量） | bigeye §11.3 B/C | 反思产物平时不加载、**对行为零约束**且每轮烧 LLM；记忆网络**生产路径只有 1/3 真通电**（「抄一个 2/3 没通电的系统不划算」） |
| DAG / 思维链工具 | bigeye §11.3 D/F | 本仓「任务」就是一轮对话、轨迹已在 history 里；让模型自己写「思维步」是**与 history 冗余的 token 税** |
| 注入面板堆叠（ST 后期字段与面板） | SillyTavern §6.4 | 用户判断「花里胡哨」；ST 值得抄的恰好不是面板 |
| 代理式压缩（每轮全量重放 + wire 重写） | billion-context §11.4 F | 代理形态的必然，移动端带宽/耗电不划算 |
| 需要 Node 运行时的「技能」 | archify | Android 宿主无 Node，装上去就是「命令跑不通」的技能 |
| Android 壳层 / FFI 架构移植 | NMoi-Agent §13.2 | Rust 前置，且其 Android 壳是 stage 0 骨架 |
| MCP host 模式（手机当桌面工具箱） | SoLab ⑥ | 成本高、按需；一旦立项即成为**第 4 个工具装配方** |

---

## 五、章节号对照（旧引用定位）

> **兼容性约定**：仓库里大量代码注释与设计稿仍写「见 `peer-apps-benchmark.md` §6.2」这类引用（约 40 处，含 `.kt` kdoc）。**不逐条改写代码注释**是为了让 diff 保持干净——按本表定位到新文件即可。新增引用请直接写新文件的路径。

| 原节号 | 内容 | 现文件 |
|---|---|---|
| 附录 | SoLab 2.3 增量分析（2026-08-31） | [solab.md §三](references/other%20projects/solab.md) |
| §一 | rikkahub-agent 与 SoLab 是什么 | 按来源拆到 [rikkahub-agent.md §一](references/other%20projects/rikkahub-agent.md) / [solab.md §一](references/other%20projects/solab.md) |
| §二（§二.2） | 结合上游 `3a533a6a` 的终端分析（含 `workspace_run_background` 三工具 / `TailBuffer`） | **本页 §二** |
| §三 | 值得借鉴的机制（源码级验证） | 按来源拆到 [rikkahub-agent.md §二](references/other%20projects/rikkahub-agent.md) / [solab.md §二](references/other%20projects/solab.md) |
| §四、§4.1–§4.4 | Yuihub 记忆机制评估（时间线 / 借鉴项 / 不照搬 / 建议顺序） | [yuihub.md](references/other%20projects/yuihub.md) |
| §四.5 | Yuihub 2.5.5 → 2.5.7 增量 | [yuihub.md §四.5](references/other%20projects/yuihub.md) |
| §四.6 | Yuihub 备份体系对照 | [yuihub.md §四.6](references/other%20projects/yuihub.md) |
| §四.7（A–D） | Yuihub 压缩 / 静默截断 / 检查点 / 自动续写 | [yuihub.md §四.7](references/other%20projects/yuihub.md) |
| §四.8（A–E） | Yuihub 2.5.8 → 3.0.0（workspace / ai / 记忆 / 备份 / 搜索 UI） | [yuihub.md §四.8](references/other%20projects/yuihub.md) |
| §五 | 落地顺序建议 + 落地状态 | **本页 §三**（唯一口径） |
| §六、§6.1–§6.12 | SillyTavern 注入 / 预设（世界书字段、借鉴项、导入器缺陷、不照搬、社区讨论、落地记录、预设中心化、拼装预览与 Tier 4） | [sillytavern.md](references/other%20projects/sillytavern.md) |
| §七、§7.1–§7.5 | senera（动态工具检索 / Artifact 投影 / 分层上下文 / 增量） | [senera.md](references/other%20projects/senera.md) |
| §八、§8.1（A–E） | 华灯（增量同步复评） | [huadeng.md](references/other%20projects/huadeng.md) |
| §九、§9.1–§9.3 | agentd（经验记忆 + 信用记账 / 委员会 / 小件） | [agentd.md](references/other%20projects/agentd.md) |
| §十一、§11.1–§11.3 | bigeye（结构与借鉴 / 记忆深评 / 逐模块精读 / 三件套判定与判据度量） | [bigeye.md](references/other%20projects/bigeye.md) |
| §十二 | archify | [archify.md](references/other%20projects/archify.md) |
| §11.4（A–I） | billion-context（追加式压缩 / 前缀缓存三规则 / breakeven / 三方综合对比） | [billion-context.md](references/other%20projects/billion-context.md) |
| §11.5 | 10-18 → 10-24 增量（bigeye） | [bigeye.md](references/other%20projects/bigeye.md) 末尾 |
| §13、§13.1–§13.2 | NMoi-Agent | [nmoi-agent.md](references/other%20projects/nmoi-agent.md) |
| —（独立成文，未纳入本次拆分） | PurrCat 借鉴评估（2026-10-30） | [purrcat-analysis.md](purrcat-analysis.md) |

**编号为什么是乱的**：原文按评估时间追加，`§11.4` 是插进 bigeye 序列里的另一个应用、`§十` 从未存在、`§六`/`§七` 的小节号与 `§6.x`/`§7.x` 混用。拆分时**保留原编号**（便于旧引用与提交历史对账），不做重排。

---

## 六、新增一个应用的约定

1. 在 `references/other projects/` 新建 `<app>.md`，头部 blockquote 四件套：**评估日期 / 许可证（没核实就写「未核」）/ 定位一句话 / 本地副本路径** + 「返回索引」链接；标注它对应原 §几（若有）。
2. 正文按「它是什么 → 值得借鉴（按价值）→ 不照搬 → 建议顺序 → 落地记录」写；**每条结论带源码路径**，远端调研的标 `远端`，自述标 `[自述]`。
3. 本页 §一 加一行（含深度与一句话结论）、§五 加章节号、§三 表按需加项。
4. 落地后回写两处：§三 表的状态单元格（划掉 + 提交短哈希）、各应用文件的落地记录小节。

> 拆分前的原文可从 git 历史取回：`git log --oneline -- docs/peer-apps-benchmark.md`。
