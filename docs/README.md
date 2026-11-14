# docs —— 文档索引

分两层：**设计类文档**在 [`design/`](design/)（按 `completed/` 已落地 → `in-progress/` 进行中 → `drafts/` 评估稿三档归档），**规划/记录/调研类文档**在本目录。

## ⚠️ 阅读约定

- 本仓由私有开发仓的成果重建：**提交短哈希已重映射**为本仓历史里的对应提交；映射不上说明该提交未随本仓发布。
- 形如 `<work>` / `<device>` / `<tmp>` 的尖括号串是路径与设备信息的占位符。
- 引用**上游**的内容（issue/PR 编号、上游 commit 哈希）可直接在 [`rikkahub/rikkahub`](https://github.com/rikkahub/rikkahub) 查看。
- 涉及**发布**的段落（`v1.0.x` / `Latest` / tag / versionCode 递增 / `gh release` 流程）属**私有开发仓时期的历史记录**；
  本仓现行发布约定见 [`README`](../README.md) 顶部：**自 `1.0.28-beta.7` 起随 release 提供 arm64-v8a APK 资产**（tag 触发的
  CI 发版流水线自 `beta.14` 起启用），签名为自签测试密钥、换密钥需卸载重装；
  各版发布说明原文存档在 [`releases/`](releases/)。

## design/ —— 设计类文档（三档）

> **2026-11-14 整理**：已落地稿统一移入 `completed/`（84 篇）；`in-progress/` 保留有明确未完条目的稿；`drafts/` 保留评估/未立项稿。
> 每篇头部状态行为该稿口径的真相源；目录归属只反映整理时点的状态。

### design/in-progress/（进行中，7 篇）

| 文档 | 内容 |
|---|---|
| [`design-agent-task-runtime.md`](design/in-progress/design-agent-task-runtime.md) | Agent 任务运行时：P0/P0.5/P1a/P1b/P3/P4a/P4b 已落地，**P2 嵌套派发待启动**（同时接管 design-subagent-context Phase 3） |
| [`design-context-purification.md`](design/in-progress/design-context-purification.md) | 上下文净化 rev8：P0 已随 beta.12 发布（观察期中），P1 暂缓（结构性） |
| [`design-memory-consolidation-trigger.md`](design/in-progress/design-memory-consolidation-trigger.md) | 记忆整理触发器 rev4：P0+P1+cron 已落地；子代理执行/MONTHLY 缓做 |
| [`design-memory-recycle-bin.md`](design/in-progress/design-memory-recycle-bin.md) | 记忆回收站：阶段 1/2/4 已落地（`1a910c620`）；**阶段 3 过期 sweep 未实现** |
| [`design-multi-pass-compression.md`](design/in-progress/design-multi-pass-compression.md) | 多步压缩 rev3：C3→C2→C4+覆盖率闸已落地；滚动摘要/精炼未实现 |
| [`design-storage-management.md`](design/in-progress/design-storage-management.md) | 缓存管理：Phase 1+2 已实现并真机验证；**Phase 3 部分** |
| [`design-subagent-context.md`](design/in-progress/design-subagent-context.md) | 子代理上下文信封：Phase 1+2 已落地；Phase 3+ 由 agent-task-runtime P2 接续 |

### design/drafts/（评估稿 / 未立项，5 篇）

| 文档 | 内容 |
|---|---|
| [`design-ui-overhaul-research.md`](design/drafts/design-ui-overhaul-research.md) | UI 改造调研稿（dsh-go 补析 §10）：**A+G+D/C/B/I/F 七方向已于 2026-11-14 落地**；仅剩方向 E（Bento IA 三件） |
| [`design-dshgo-kotlin-migration-assessment.md`](design/drafts/design-dshgo-kotlin-migration-assessment.md) | dsh-go → Kotlin 迁移评估：插件设计重构先行、引擎托管独立立项；**评估稿，未立项** |
| [`design-history-tools-and-delta-emit.md`](design/drafts/design-history-tools-and-delta-emit.md) | 历史检索工具族 + 增量发送：**草案（暂不实现）**；若重启需先复核 §B.1（树语义重构已改变前提） |
| [`design-tool-output-budget.md`](design/drafts/design-tool-output-budget.md) | 工具输出预算：阶段 1 变体已落地（`_meta` 完整性声明）；阶段 2/3 未做 |
| [`design-thinking-guard-rewrite-fix.md`](design/drafts/design-thinking-guard-rewrite-fix.md) | 思维链退化护栏续写修复：**占位草稿（未展开、未动代码）** |

> 另：**DeepSeek Tool API 适配**整套已移入 [`design/completed/deepseek-tools-api-adapt/`](design/completed/deepseek-tools-api-adapt/README.md)（P0+P1+P1b+P2 已于 2026-11-14 落地；唯一挂账 = OCR DEEPSEEK 后端图片入口实弹闭环）。

### design/completed/（已落地归档，84 篇）

> 均已实施（多数含实施记录与验证链接）；此处按主题分组列索引，细节以各稿头部状态行为准。

**会话 / 消息**：[design-conversation-tree-branching](design/completed/design-conversation-tree-branching.md)（会话树形分支 P0–P3）、[design-auto-compaction](design/completed/design-auto-compaction.md)（自动压缩）、[design-compress-presets](design/completed/design-compress-presets.md)（压缩预设）、[design-conversation-import-export](design/completed/design-conversation-import-export.md)（导入导出）、[design-conversation-manual-order](design/completed/design-conversation-manual-order.md)（手动排序）、[design-partial-failure-draft](design/completed/design-partial-failure-draft.md)（半截回复保留）、[design-regenerate-scope](design/completed/design-regenerate-scope.md)（重生成范围）、[design-favorite-management](design/completed/design-favorite-management.md)（收藏管理）、[design-side-file-panel](design/completed/design-side-file-panel.md)（文件侧栏）、[design-whole-turn-folding](design/completed/design-whole-turn-folding.md)（整轮折叠）、[design-task-completion-wait-and-resume](design/completed/design-task-completion-wait-and-resume.md)、[design-task-handoff-and-immediate-message](design/completed/design-task-handoff-and-immediate-message.md)、[design-task-notice-inbox-and-proactive-sync](design/completed/design-task-notice-inbox-and-proactive-sync.md)（任务收尾三件套）

**记忆**：[design-memory-discipline](design/completed/design-memory-discipline.md)（写入纪律）、[design-memory-scoring](design/completed/design-memory-scoring.md)（打分+预算）、[design-memory-consolidation](design/completed/design-memory-consolidation.md)（做梦整理）、[design-memory-retrieval-and-consolidation](design/completed/design-memory-retrieval-and-consolidation.md)、[design-memory-retrieval-evaluation](design/completed/design-memory-retrieval-evaluation.md)、[design-memory-kinds](design/completed/design-memory-kinds.md)（记忆分类 A1/A2）、[design-memory-injection-tiering](design/completed/design-memory-injection-tiering.md)（注入分层）、[design-memory-manual-order](design/completed/design-memory-manual-order.md)（记忆手动排序）、[design-assistant-memory-modes](design/completed/design-assistant-memory-modes.md)（助手级双记忆模式）

**备份 / 存储**：[design-backup-workspace-files](design/completed/design-backup-workspace-files.md)、[design-backup-legacy-import](design/completed/design-backup-legacy-import.md)、[design-backup-manifest-and-safety](design/completed/design-backup-manifest-and-safety.md)、[design-backup-staged-restore](design/completed/design-backup-staged-restore.md)、[design-incremental-backup](design/completed/design-incremental-backup.md)（增量备份 P0–P2 + pack/多设备 GC）、[design-readonly-mount-snapshots](design/completed/design-readonly-mount-snapshots.md)、[design-export-retention](design/completed/design-export-retention.md)（导出留存 TTL）、[design-media-upload](design/completed/design-media-upload.md)（素材远端存储）

**工具 / 任务**：[design-tool-context-gate](design/completed/design-tool-context-gate.md)、[design-tool-timing-and-usage-fix](design/completed/design-tool-timing-and-usage-fix.md)、[design-deliverable-gate](design/completed/design-deliverable-gate.md)（交付物闸门）、[design-proactive-task-management](design/completed/design-proactive-task-management.md)、[design-agent-image-tool](design/completed/design-agent-image-tool.md)、[design-phone-use](design/completed/design-phone-use.md)（手机操控）、[design-rule-engine](design/completed/design-rule-engine.md)（规则插件）

**插件 / 调度**：[design-plugin-composable-runtime](design/completed/design-plugin-composable-runtime.md)（可组合插件运行时，2026-11-07 收官）、[design-plugin-dev-revision-channel](design/completed/design-plugin-dev-revision-channel.md)（修订通道 R1–R3）、[design-scheduled-ai-tasks](design/completed/design-scheduled-ai-tasks.md)（定时任务 P1–P3）、[design-scheduled-random-trigger-and-memory-sync](design/completed/design-scheduled-random-trigger-and-memory-sync.md)（随机窗口/记忆同步）、[deepseek-tools-api-adapt/](design/completed/deepseek-tools-api-adapt/README.md)（DeepSeek Tool API 适配全套：契约摘编/仓库审计/设计/计划与实施记录）

**提供商 / 网络**：[design-proxy-two-layer](design/completed/design-proxy-two-layer.md)（两层代理）、[design-provider-model-catalog-refresh](design/completed/design-provider-model-catalog-refresh.md)（模型目录刷新 A–E）、[design-upstream-oauth-port](design/completed/design-upstream-oauth-port.md)（OAuth 订阅登录移植）、[design-codex-subscription-login](design/completed/design-codex-subscription-login.md)（设备码路线，已被 OAuth 路线取代，历史存档）、[new-api-balance-design](design/completed/new-api-balance-design.md)（余额查询）、[design-usage-filter](design/completed/design-usage-filter.md)、[design-usage-stats](design/completed/design-usage-stats.md)

**UI / 外观**：[design-ui-yuihub-inspired](design/completed/design-ui-yuihub-inspired.md)、[design-custom-theme](design/completed/design-custom-theme.md)（自定义主题增强）、[design-light-personalization](design/completed/design-light-personalization.md)（轻量个性化）、[design-chat-appearance-contract](design/completed/design-chat-appearance-contract.md)（聊天外观契约）、[design-bubble-textures-and-layers](design/completed/design-bubble-textures-and-layers.md) / [design-bubble-shader-templates](design/completed/design-bubble-shader-templates.md)（气泡纹理/AGSL shader）、皮肤族六篇：[design-app-skin-foundation](design/completed/design-app-skin-foundation.md) / [design-app-skin-panels](design/completed/design-app-skin-panels.md) / [design-app-skin-embedded](design/completed/design-app-skin-embedded.md) / [design-app-skin-component-migration](design/completed/design-app-skin-component-migration.md) / [design-skin-packages](design/completed/design-skin-packages.md) / [design-skin-trials-and-rollback](design/completed/design-skin-trials-and-rollback.md)，验收三件：[design-skin-acceptance](design/completed/design-skin-acceptance.md) / [skin-acceptance-matrix](design/completed/skin-acceptance-matrix.md) / [app-skin-component-coverage](design/completed/app-skin-component-coverage.md)（后两份为脚本生成物）；[design-model-card-scale](design/completed/design-model-card-scale.md)（卡片连续缩放）、[design-settings-search](design/completed/design-settings-search.md)、[design-sketch-port](design/completed/design-sketch-port.md)、[design-ask-user-interrupt](design/completed/design-ask-user-interrupt.md)、[design-translation-history](design/completed/design-translation-history.md)、[design-media-generation](design/completed/design-media-generation.md)、[icon-design](design/completed/icon-design.md)

**工作区**：[design-ssh-workspace](design/completed/design-ssh-workspace.md)、[design-workspace-background-tasks](design/completed/design-workspace-background-tasks.md)、[design-workspace-multiselect-export](design/completed/design-workspace-multiselect-export.md)

**性能 / 工程**：[design-longfile-split-refactoring](design/completed/design-longfile-split-refactoring.md)（超长文件拆分批次 1–10 收官）、[design-tokenizer](design/completed/design-tokenizer.md)（真 tokenizer）、长文本五阶段：[longtext-p0-baseline](design/completed/longtext-p0-baseline.md) / [longtext-p2-display-document](design/completed/longtext-p2-display-document.md) / [longtext-p3-static-lazy](design/completed/longtext-p3-static-lazy.md) / [longtext-p4-streaming](design/completed/longtext-p4-streaming.md) / [longtext-p5-large-atoms](design/completed/longtext-p5-large-atoms.md)（验证记录见 [`design/verification/`](design/verification/)）、[prompt-preview-performance](design/completed/prompt-preview-performance.md)、[design-db-downgrade-rebuild](design/completed/design-db-downgrade-rebuild.md)（SCHEMA_VERSION 漂移根治 + 降级隔离重建）、[design-card-lorebook-import](design/completed/design-card-lorebook-import.md)（角色卡内嵌世界书）

## 规划 / 记录 / 调研

| 文档 | 内容 |
|---|---|
| [`roadmap.md`](roadmap.md) | 规划方向 + 「不做」决策与理由（§八 起为**日志索引**；逐日落地日志已按月迁至 [`changelog/`](changelog/)） |
| [`changelog/`](changelog/) | **逐日落地日志**（2026-08-12 起，按月一文件、只追加）：做了什么 / 提交短哈希 / 验证状态 / 暂缓理由；检索方式见 roadmap §八 |
| [`handoff-2026-09-04.md`](handoff-2026-09-04.md) | 开发交接：**待办全量盘点（§三，最新真相源）**、现状快照、验证习惯、发布流程（§二.2 = 2026-10-22 起的「带资产发布」现行定式） |
| [`upstream-analysis.md`](upstream-analysis.md) | 上游逐条评估记录与「吸收 / 不吸收」结论 |
| [`ui-simplification-proposal.md`](ui-simplification-proposal.md) | UI 复杂度证据基线（**改前快照**；改后状态见 design-ui-yuihub-inspired §7） |
| [`peer-apps-benchmark.md`](peer-apps-benchmark.md) | 同类客户端 / 竞品调研：**索引 + 跨应用汇总**（落地状态表、五条通用判据、不做清单、章节号对照）；**每个应用的评估原文在 [`references/other projects/`](references/other%20projects/)**（14 个文件，2026-10-30 拆分；2026-11-03 全量增量；2026-11-06 增 #15 dsh-Go） |
| [`peer-verdicts-2026-11-08.md`](peer-verdicts-2026-11-08.md) | peer 增量轮判决汇总（§六 11-10 轮、§七 11-14 轮） |
| [`purrcat-analysis.md`](purrcat-analysis.md) | PurrCat（桌面 Python Agent 宿主）借鉴评估：**A1 收尾闸门 / A3 交付物契约 / A4.1 idle 门控 / A4.2 会话级目标已落地，A2 能力生产线未动工**（2026-11-03 回写）；含三项建议与去重清单 |
| [`tikz-rendering-tectonic-postmortem.md`](tikz-rendering-tectonic-postmortem.md) | TikZ 渲染链复盘（native Tectonic + mupdf SVG 桥、字体 bundle）；配套 [`../tools/tex-poc/`](../tools/tex-poc/) |
| [`PLUGIN_SYSTEM_DESIGN_DRAFT.md`](PLUGIN_SYSTEM_DESIGN_DRAFT.md) | 插件系统最初调研底稿（借鉴 BiliPai；**已落地，现行稿见 design/completed/design-plugin-composable-runtime.md**，本文不再维护） |
| [`bug-audit-2026-10-27.md`](bug-audit-2026-10-27.md) | bug 审计快照（已分 6 批落地修复，存档） |
| [`deepseek-endpoint-notes-2026-10-28.md`](deepseek-endpoint-notes-2026-10-28.md) | DeepSeek 端点行为实测记录（证据时效留档） |
| [`references/chat-generation-pipeline.md`](references/chat-generation-pipeline.md) | 上游：对话生成管线说明 |
| [`references/other projects/`](references/other%20projects/) | 同类应用评估**原文**（按应用一文件）：rikkahub-agent / SoLab / Yuihub / SillyTavern / senera / 华灯 / agentd / bigeye / billion-context / archify / NMoi-Agent / BiliPai / Miffan / dsh-Go；索引入口见 `peer-apps-benchmark.md` |
| [`releases/`](releases/) | **发布说明存档**（按 tag 存 release 正文原文；`1.0.28-beta.7` 起带 arm64-v8a APK，含 SHA-256 与签名指纹；最新见 [`1.0.28-beta.20.md`](releases/1.0.28-beta.20.md)，索引见 [`releases/README.md`](releases/README.md)） |
| `icon.png` / `img/` / `sponsors/` / `donate.png` | 上游：README 用图与赞助素材 |

> 内部审计类记录（`turbo-*`）**未随本仓发布**；`roadmap.md` 等文中以「内部记录（未随本仓发布）」指代。

## 🕒 文档新鲜度（盘点待办前先看这里）

这几份文档是**混合体**：「已落地」与「规划中/未立项」写在同一篇里，正文还含大量**评估当时的原文存档**。
**不要按文档标题里的日期判断有效性**，按该文档的**状态行 / 待办表**取最新口径：

| 文档 | 待办/状态最新刷新 | 说明 |
|---|---|---|
| `handoff-2026-09-04.md` | **2026-11-14**（§〇 快照 → beta.20 (323)/DB 71；§三 已落地待办逐项划掉） | **待办的第一真相源**；§三.1 表内**未划掉**的才是未完成项——长文本里的「YYYY-MM-DD 评估，未实现」多为**存档**，不代表当前状态 |
| `roadmap.md` | **2026-11-14**（头部快照刷新；§八 日志索引） | §八 只保留**日志索引 + 追加约定**（最新条目见 `changelog/2026-11.md`）；§一~§七 的规划判断**可能已被日志推翻**，冲突时以 `changelog/` + handoff §三 为准 |
| `changelog/2026-11.md` | **2026-11-14**（最后一条） | 逐日落地日志（**只追加**，最新月份文件为准）；查某功能全过程按关键词全文搜 `changelog/`，不要只看某月 |
| `peer-apps-benchmark.md` | **2026-11-14 晚**（§七+§八 两轮判决；上轮 2026-11-10） | 本页**只剩索引与汇总**：落地状态看「§三」（原 §五，唯一口径）；各应用评估原文已迁 `references/other projects/`；11-14 两轮明细见 peer-verdicts §七/§八与各台账当日节 |
| `upstream-analysis.md` | **2026-11-14**（同步点 `64452581d`，tag `3.2.0`） | 已评估到上游 `64452581d`；issue 扫描至 #2312；唯一候选 c1c3c4d12 已落地（fork `0bf49eed4`）；**下次同步从 `64452581d` 往后取** |
| `purrcat-analysis.md` | **2026-11-03**（状态回写；原评估 2026-10-30） | 全篇为**评估原文**；**A1/A3/A4.1/A4.2 已落地，A2 仍空**；引用第三方项目的说法一律标注「自述」并附源码核验 |

> **一条教训（2026-10-12）**：`handoff` §三 表头曾写「2026-10-05 复核」，但 10-05 之后落地的 4 项
> （`read_conversation`、记忆整理器、MCP schema 分层加载、启停抽象化）仍挂在表内，且与 `changelog/`（原 `roadmap` §八）的
> 「已落地」记录**互相矛盾**。**盘点待办必须用代码事实（`git log -S` / 符号是否存在）逐项回核**，
> 不能照抄表头日期，也不能只看一处——同一份文档里两处可能一旧一新。
