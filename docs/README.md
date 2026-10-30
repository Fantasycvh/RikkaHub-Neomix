# docs —— 文档索引

分两层：**设计类文档**在 [`design/`](design/)，**规划/记录/调研类文档**在本目录。

## ⚠️ 阅读约定

- 本仓由私有开发仓的成果重建：**提交短哈希已重映射**为本仓历史里的对应提交；映射不上说明该提交未随本仓发布。
- 形如 `<work>` / `<device>` / `<tmp>` 的尖括号串是路径与设备信息的占位符。
- 引用**上游**的内容（issue/PR 编号、上游 commit 哈希）可直接在 [`rikkahub/rikkahub`](https://github.com/rikkahub/rikkahub) 查看。
- 涉及**发布**的段落（`v1.0.x` / `Latest` / tag / versionCode 递增 / `gh release` 流程）属**私有开发仓时期的历史记录**；
  本仓现行发布约定见 [`README`](../README.md) 顶部：**自 `1.0.28-beta.7` 起随 release 提供 arm64-v8a APK 资产**
  （此前 `v1.0.27` 与 `1.0.28-beta.1`…`beta.6` 仅 tag、无资产），签名为自签测试密钥、换密钥需卸载重装；
  各版发布说明原文存档在 [`releases/`](releases/)。

## design/ —— 设计类文档

| 文档 | 内容 |
|---|---|
| [`design-auto-compaction.md`](design/design-auto-compaction.md) | 自动上下文压缩（harness 式检查点、可回看可编辑） |
| [`design-backup-workspace-files.md`](design/design-backup-workspace-files.md) | 备份纳入工作区文件 |
| [`design-compress-presets.md`](design/design-compress-presets.md) | 压缩参数预设档 |
| [`design-conversation-import-export.md`](design/design-conversation-import-export.md) | 对话导入导出与附件往返 |
| [`design-conversation-manual-order.md`](design/design-conversation-manual-order.md) | 会话手动排序 |
| [`design-export-retention.md`](design/design-export-retention.md) | 导出留存区与 TTL 清理 |
| [`design-favorite-management.md`](design/design-favorite-management.md) | 收藏标签 / 排序 / 侧滑撤销 |
| [`design-memory-consolidation.md`](design/design-memory-consolidation.md) | 记忆整理器（去重、合并、消费标记） |
| [`design-memory-discipline.md`](design/design-memory-discipline.md) | 记忆写入纪律 |
| [`design-memory-scoring.md`](design/design-memory-scoring.md) | 记忆打分与字符预算注入 |
| [`design-partial-failure-draft.md`](design/design-partial-failure-draft.md) | 半截回复保留与失败重试分流 |
| [`design-proxy-two-layer.md`](design/design-proxy-two-layer.md) | 两层代理（per-provider + 全局兜底） |
| [`design-regenerate-scope.md`](design/design-regenerate-scope.md) | 重新生成的作用范围 |
| [`design-side-file-panel.md`](design/design-side-file-panel.md) | 会话内文件侧栏 |
| [`design-storage-management.md`](design/design-storage-management.md) | 缓存管理：占用聚合、白名单清理、明细展开与备份互斥（Phase 1+2 已实现并真机验证；Phase 3 部分） |
| [`design-subagent-context.md`](design/design-subagent-context.md) | 子代理上下文信封与派发 |
| [`design-tokenizer.md`](design/design-tokenizer.md) | 真 tokenizer（cl100k BPE）与预算判定 |
| [`design-ui-yuihub-inspired.md`](design/design-ui-yuihub-inspired.md) | UI 重设计（以 Yuihub 为参考并改进其缺陷）：抽屉缩放、设置页分组、创作入口归一；**含实施结果与实测偏差** |
| [`design-workspace-background-tasks.md`](design/design-workspace-background-tasks.md) | 工作区后台任务（`run_background` 等三工具） |
| [`design-workspace-multiselect-export.md`](design/design-workspace-multiselect-export.md) | 工作区长按多选与导出 |
| [`new-api-balance-design.md`](design/new-api-balance-design.md) | new-api 站点余额查询 |
| [`icon-design.md`](design/icon-design.md) | 应用图标风格描述与生成指南 |

## 规划 / 记录 / 调研

| 文档 | 内容 |
|---|---|
| [`roadmap.md`](roadmap.md) | 规划方向 + 「不做」决策与理由（§八 起为**日志索引**；逐日落地日志已按月迁至 [`changelog/`](changelog/)） |
| [`changelog/`](changelog/) | **逐日落地日志**（2026-08-12 起，按月一文件、只追加）：做了什么 / 提交短哈希 / 验证状态 / 暂缓理由；检索方式见 roadmap §八 |
| [`handoff-2026-09-04.md`](handoff-2026-09-04.md) | 开发交接：**待办全量盘点（§三，最新真相源）**、现状快照、验证习惯、发布流程（§二.2 = 2026-10-22 起的「带资产发布」现行定式） |
| [`upstream-analysis.md`](upstream-analysis.md) | 上游逐条评估记录与「吸收 / 不吸收」结论 |
| [`ui-simplification-proposal.md`](ui-simplification-proposal.md) | UI 复杂度证据基线（**改前快照**；改后状态见 design-ui-yuihub-inspired §7） |
| [`peer-apps-benchmark.md`](peer-apps-benchmark.md) | 同类客户端 / 竞品调研：**索引 + 跨应用汇总**（落地状态表、五条通用判据、不做清单、章节号对照）；**每个应用的评估原文在 [`references/other projects/`](references/other%20projects/)**（11 个文件，2026-10-30 拆分） |
| [`purrcat-analysis.md`](purrcat-analysis.md) | PurrCat（桌面 Python Agent 宿主）借鉴评估：**评估完成、未落地**；含「收尾闸门 / 能力生产线 / 交付物契约」三项建议与去重清单 |
| [`tikz-rendering-tectonic-postmortem.md`](tikz-rendering-tectonic-postmortem.md) | TikZ 渲染链复盘（native Tectonic + mupdf SVG 桥、字体 bundle）；配套 [`../tools/tex-poc/`](../tools/tex-poc/) |
| [`references/chat-generation-pipeline.md`](references/chat-generation-pipeline.md) | 上游：对话生成管线说明 |
| [`references/other projects/`](references/other%20projects/) | 同类应用评估**原文**（按应用一文件）：rikkahub-agent / SoLab / Yuihub / SillyTavern / senera / 华灯 / agentd / bigeye / billion-context / archify / NMoi-Agent；索引入口见 `peer-apps-benchmark.md` |
| [`releases/`](releases/) | **发布说明存档**（按 tag 存 release 正文原文；`1.0.28-beta.7` 起带 arm64-v8a APK，含 SHA-256 与签名指纹；最新见 [`1.0.28-beta.8.md`](releases/1.0.28-beta.8.md)） |
| `icon.png` / `img/` / `sponsors/` / `donate.png` | 上游：README 用图与赞助素材 |

> 内部审计类记录（`turbo-*`）**未随本仓发布**；`roadmap.md` 等文中以「内部记录（未随本仓发布）」指代。

## 🕒 文档新鲜度（盘点待办前先看这里）

这几份文档是**混合体**：「已落地」与「规划中/未立项」写在同一篇里，正文还含大量**评估当时的原文存档**。
**不要按文档标题里的日期判断有效性**，按该文档的**状态行 / 待办表**取最新口径：

| 文档 | 待办/状态最新刷新 | 说明 |
|---|---|---|
| `handoff-2026-09-04.md` | **2026-10-22**（§〇 快照 + §二.2 资产化发布流程） | **待办的第一真相源**；§三.1 表内**未划掉**的才是未完成项——长文本里的「YYYY-MM-DD 评估，未实现」多为**存档**，不代表当前状态 |
| `roadmap.md` | **2026-10-30**（§八 日志按月迁至 `changelog/`） | §八 只保留**日志索引 + 追加约定**（最新条目见 `changelog/2026-10.md`，10-28）；§一~§七 的规划判断**可能已被日志推翻**，冲突时以 `changelog/` + handoff §三 为准 |
| `changelog/2026-10.md` | **2026-10-28**（最后一条） | 逐日落地日志（**只追加**，最新月份文件为准）；查某功能全过程按关键词全文搜 `changelog/`，不要只看某月 |
| `peer-apps-benchmark.md` | **2026-10-30**（按应用拆分；落地状态同步为 10-12 口径） | 本页**只剩索引与汇总**：落地状态看「§三」（原 §五，唯一口径）；各应用评估原文已迁 `references/other projects/`，旧引用 `§6.2` 等按本页「§五 章节号对照」定位 |
| `upstream-analysis.md` | **2026-10-24**（同步点 `f267e8478`，上游 HEAD） | 10-23 批 10 提交全部结案：8 条经 `chore/remove-tikz` 分支移植回 master（保留 TikZ，PDF 替换/applicationId/诊断不带），`f267e8478`/`537f3b0ed` 直接吸收。**下次同步从 `f267e8478` 往后取** |
| `purrcat-analysis.md` | **2026-10-30**（新建，评估完成） | 全篇为**评估原文**，状态行即口径：建议项**均未落地**；引用第三方项目的说法一律标注「自述」并附源码核验 |

> **一条教训（2026-10-12）**：`handoff` §三 表头曾写「2026-10-05 复核」，但 10-05 之后落地的 4 项
> （`read_conversation`、记忆整理器、MCP schema 分层加载、启停抽象化）仍挂在表内，且与 `changelog/`（原 `roadmap` §八）的
> 「已落地」记录**互相矛盾**。**盘点待办必须用代码事实（`git log -S` / 符号是否存在）逐项回核**，
> 不能照抄表头日期，也不能只看一处——同一份文档里两处可能一旧一新。
