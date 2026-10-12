# docs —— 文档索引

分两层：**设计类文档**在 [`design/`](design/)，**规划/记录/调研类文档**在本目录。

## ⚠️ 阅读约定

- 本仓由私有开发仓的成果重建：**提交短哈希已重映射**为本仓历史里的对应提交；映射不上说明该提交未随本仓发布。
- 形如 `<work>` / `<device>` / `<tmp>` 的尖括号串是路径与设备信息的占位符。
- 引用**上游**的内容（issue/PR 编号、上游 commit 哈希）可直接在 [`rikkahub/rikkahub`](https://github.com/rikkahub/rikkahub) 查看。
- 涉及**发布**的段落（`v1.0.x` / `Latest` / tag / versionCode 递增 / `gh release` 流程）属**私有开发仓时期的历史记录**：
  本仓 `master` 暂无 tag、**不发预编译包**（`daily-build` 已改为仅手动触发），签名目前为测试密钥。

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
| [`design-subagent-context.md`](design/design-subagent-context.md) | 子代理上下文信封与派发 |
| [`design-tokenizer.md`](design/design-tokenizer.md) | 真 tokenizer（cl100k BPE）与预算判定 |
| [`design-workspace-background-tasks.md`](design/design-workspace-background-tasks.md) | 工作区后台任务（`run_background` 等三工具） |
| [`design-workspace-multiselect-export.md`](design/design-workspace-multiselect-export.md) | 工作区长按多选与导出 |
| [`new-api-balance-design.md`](design/new-api-balance-design.md) | new-api 站点余额查询 |
| [`icon-design.md`](design/icon-design.md) | 应用图标风格描述与生成指南 |

## 规划 / 记录 / 调研

| 文档 | 内容 |
|---|---|
| [`roadmap.md`](roadmap.md) | 规划方向 + 逐日落地记录（含「不做」决策与理由） |
| [`handoff-2026-09-04.md`](handoff-2026-09-04.md) | 开发交接：现状快照、验证习惯、发布流程（私仓时期实践） |
| [`upstream-analysis.md`](upstream-analysis.md) | 上游逐条评估记录与「吸收 / 不吸收」结论 |
| [`peer-apps-benchmark.md`](peer-apps-benchmark.md) | 同类客户端 / 竞品调研与借鉴结论 |
| [`tikz-rendering-tectonic-postmortem.md`](tikz-rendering-tectonic-postmortem.md) | TikZ 渲染链复盘（native Tectonic + mupdf SVG 桥、字体 bundle）；配套 [`../tools/tex-poc/`](../tools/tex-poc/) |
| [`references/chat-generation-pipeline.md`](references/chat-generation-pipeline.md) | 上游：对话生成管线说明 |
| `icon.png` / `img/` / `sponsors/` / `donate.png` | 上游：README 用图与赞助素材 |

> 内部审计类记录（`turbo-*`）**未随本仓发布**；`roadmap.md` 等文中以「内部记录（未随本仓发布）」指代。
