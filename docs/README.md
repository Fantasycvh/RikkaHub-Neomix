# docs —— 设计文档

本目录**只保留设计类文档**（`design-*.md`、`*-design.md`）与技术记录（`tikz-rendering-tectonic-postmortem.md`）；
项目规划 / 交接 / 竞品调研 / 上游分析等**内部记录未随本仓发布**，正文中以 `私有仓记录·xxx` 指代。

## ⚠️ 阅读约定

- 本项目在**私有开发仓**中迭代；公开仓发布前对**包名**（原个人命名空间 → 现 `app.androidrik.rikkahub`）
  与**提交身份**做了重写，因此文中出现的 **7 位短 commit 哈希**与 **`v1.0.x` 版本号**指向私有开发仓，
  **在本仓无法直接定位**——请改用提交信息关键词在本仓历史中检索。
- 形如 `<work>` / `<device>` 的尖括号串是路径与设备信息的占位符。
- 引用**上游**的内容（issue/PR 编号、`rikkahub/rikkahub` 的 commit 哈希）不受影响，可直接在上游仓库查看。

## 目录

| 文档 | 内容 |
|---|---|
| [`design-auto-compaction.md`](design-auto-compaction.md) | 自动上下文压缩（harness 式检查点、可回看可编辑） |
| [`design-backup-workspace-files.md`](design-backup-workspace-files.md) | 备份纳入工作区文件 |
| [`design-compress-presets.md`](design-compress-presets.md) | 压缩参数预设档 |
| [`design-conversation-import-export.md`](design-conversation-import-export.md) | 对话导入导出格式与附件往返 |
| [`design-conversation-manual-order.md`](design-conversation-manual-order.md) | 会话手动排序 |
| [`design-export-retention.md`](design-export-retention.md) | 导出留存区与 TTL 清理 |
| [`design-favorite-management.md`](design-favorite-management.md) | 收藏标签 / 排序 / 侧滑撤销 |
| [`design-memory-consolidation.md`](design-memory-consolidation.md) | 记忆整理器（去重、合并、消费标记） |
| [`design-memory-discipline.md`](design-memory-discipline.md) | 记忆写入纪律（何时写、写多少） |
| [`design-memory-scoring.md`](design-memory-scoring.md) | 记忆打分与字符预算注入 |
| [`design-partial-failure-draft.md`](design-partial-failure-draft.md) | 半截回复保留与失败重试分流 |
| [`design-proxy-two-layer.md`](design-proxy-two-layer.md) | 两层代理（per-provider + 全局兜底） |
| [`design-regenerate-scope.md`](design-regenerate-scope.md) | 重新生成的作用范围 |
| [`design-side-file-panel.md`](design-side-file-panel.md) | 会话内文件侧栏 |
| [`design-subagent-context.md`](design-subagent-context.md) | 子代理上下文信封与派发 |
| [`design-tokenizer.md`](design-tokenizer.md) | 真 tokenizer（cl100k BPE）与预算判定 |
| [`design-workspace-background-tasks.md`](design-workspace-background-tasks.md) | 工作区后台任务（`run_background` 等三工具） |
| [`design-workspace-multiselect-export.md`](design-workspace-multiselect-export.md) | 工作区长按多选与导出 |
| [`new-api-balance-design.md`](new-api-balance-design.md) | new-api 站点余额查询 |
| [`icon-design.md`](icon-design.md) | 应用图标风格描述与生成指南 |
| [`tikz-rendering-tectonic-postmortem.md`](tikz-rendering-tectonic-postmortem.md) | TikZ 渲染链记录（native Tectonic + mupdf SVG 桥、字体 bundle、踩坑）；配套 [`../tools/tex-poc/`](../tools/tex-poc/) |
| [`references/chat-generation-pipeline.md`](references/chat-generation-pipeline.md) | 上游：对话生成管线说明 |
| `icon.png` / `img/` / `sponsors/` / `donate.png` | 上游：README 用图与赞助素材 |
