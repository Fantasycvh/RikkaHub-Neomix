# 上游变更分析（综合版，2.4.11 → 2.5.0）

> 本文档合并原 `upstream-2.4.11/2.4.14/2.4.16/2.5.0-analysis.md` 四份分析（已删除）。
> 当前 master 同步点：`12ee935e`（2.5.0 tag，2026-09-08 ff-only 同步并推送）。
> 决策总原则：**等上游稳定后按需吸收**；触及 `GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`（我们重改最重的文件）的提交一律手工适配，不直接 cherry-pick；所有 cherry-pick 需包名适配（`me.rerere.rikkahub` → `app.androidrik.rikkahub`）。

## 批次概览

| 批次 | 范围 | 规模 | 核心主题 |
|---|---|---|---|
| 2.4.11（36 提交） | `8824e0e8`..`1e3351f7` | 78 文件 +2691/-649 | 全局代理页（设计冲突）、终端多 Tab 架构、R8/JWT、think 标签 |
| 2.4.12–2.4.14（38 提交） | `1e3351f7`..`7da69770` | 103 文件 +4842/-2196 | 后台生成稳定性（FGS/重试，与我们正面重叠）、Gemini 工具共存 |
| 2.4.16（17 提交） | `5662945c`..`08c2648b` | 54 文件 +839/-301 | TTS 重试、Gemini 混合工具后续修复 |
| 2.5.0（16 提交） | `b153028d`..`12ee935e` | — | 终端 Tab 标题、工具审批修复、消息队列/语音、ChatToolFactory |

## 已吸收（合并清单）

| 提交 | 内容 | 吸收提交 | 时间 |
|---|---|---|---|
| 2.4.8-2.4.10 批（约 13 个） | R8 keep/jlatexmath、Imggen override、流式 null 容错、reasoning max、加密推理、DashScope ASR、SAF 判断、.agc、豆包搜索、句首大写、正则排序 | 分批吸收 | 2026-08 |
| `3a533a6a` | 工作区终端后台运行 + 多 Tab（架构底座） | `fbfcde5e` | 2026-09-03 |
| `e8293d35` | Chat Completions 空 tool schema 归一化 (#1781) | `758a252d` | 2026-09-04 |
| `b62d29d1` | workspace 非交互命令 stdin EOF (#1605) | `758a252d` | 2026-09-04 |
| `942d0d28` | 合成消息不参与模板 (#1790) | `6bbcacb6` | 2026-09-06 |
| `6c6a8458` | fork 会话继承 folder id + workspace cwd (#1778) | `77abbd89` | 2026-09-06 |
| `5736c05e` | 代码块导出截断模式修复 (#1841) | `e2f6b836` | 2026-09-05 |
| 1c09d5cd 等 5 提交 | TikZ 渲染走 mupdf SVG 桥（TEXT_AS_PATH）+ 缓存版本 | fork 会话 | 2026-09-08 |

## 待吸收（按优先级，合并清单）

### 第一批（小件，低风险）

| 提交 | 内容 | 批次 | 成本 |
|---|---|---|---|
| `aab5026e` | Live Update 通知图标（1 行） | 2.4.14 B8 | 极低 |
| `e9b98a4b` | qwen tts 音色列表（1 行） | 2.4.14 B9 | 极低 |
| `4fcb590a` | 测试连接对话框本地化 | 2.4.14 B10 | 小 |
| `321443d8` | tool-only 消息操作按钮可见 (#1689) | 2.4.14 B7 | 小 |
| `5902feab` | 纯图片消息（无空文本 part） | 2.5.0 C1 | 小 |
| `ab8b6cc8` | provider 阻塞工作移 IO 调度 | 2.5.0 C3 | 小 |
| `a61d116d` | 自定义 response API 路径 | 2.5.0 B4 | 小 |
| `74397a32` | 新增 MaruCode 提供商 | 2.5.0 B5 | 极低 |
| 模型注册组（glm 5.3/hy4/qwen 3.8/kimi icon 等） | 模型数据 | 2.4.14 C5 | 极低 |

### 第二批（值得吸收，需适配）

| 提交 | 内容 | 批次 | 备注 |
|---|---|---|---|
| `104040df` + `1d86b3c1` | 终端 Tab 标题动态更新 + 手势/尺寸修复 | 2.5.0 A1+A2 | 多 Tab 底座已就绪；`WorkspaceTerminalSessionClient` 构造签名上游改三参，需适配 |
| `a8f8c3a1` | Exa 搜索新鲜度证据 (#1854) | 2.5.0 B3 | search 模块独立，直接可用 |
| `5403bc96` + `2f05019b` | TTS 合成自动重试 + 可重试错误分类 | 2.4.16 B1 | speech 模块改动少，两提交合并吸收 |
| `9851d037` + `f7869e35` | Gemini 工具共存 + 混合工具错误修复 | 2.4.14 B2 + 2.4.16 B2 | **必须捆绑吸收**，单独 `9851d037` 会引入已知 bug |
| `55506496` | 连续工具审批丢失 + 取消状态误标修复 | 2.5.0 A3 | 需先核对我们的审批路径（needsApproval） |

### 第三批（评估后定）

| 提交 | 内容 | 批次 | 备注 |
|---|---|---|---|
| `3b4b4a4`（实为 R8 系）→ 见注意点 | R8 JWT keep 规则 | 2.4.11 B1 | 我们正暴露同样 bug（webServerJwt release 混淆） |
| `85402745` | think 标签误判（正则锚定 \A） | 2.4.11 B2 | 我们分支有同款 bug |
| `54030ec4` | Skill frontmatter 用 SnakeYAML | 2.4.11 B3 | 手写 YAML 边界问题 |
| `6c6a8458` 系列之后的 `fa0305ba`+`daae3749` | IME 修复 | 2.4.14 B5 | ChatInput 定制，逐段确认 |
| `9328a6e5`（fence/弧度归一化系 TikZ 改）| TikZ 细节 | 2026-09 | 已随 mupdf 桥路线消化 |

## 已放弃 / 不跟（合并清单）

| 提交 | 内容 | 原因 |
|---|---|---|
| `9365c297` | 移除独立标题/建议模型配置 | 产品决策放弃，保留独立配置（2026-09-04，`710ba2c3`） |
| `7da69770` 系设置页 bottom sheet | 文件清理 UX | 随设置页定制程度评估 |
| `7714f2bc` | chatbox v2 导入 | 仅按需 |
| `c62f1eb1` | 生成期临时 FGS | 与自有 KeepAliveService 重叠，保留自有 |
| `c16fe44f`/`41353567` | 固定 3 次重试 + 关闭开关 | 自有的 RetrySetting 是超集，保留自有 |
| `986b9c39`+`3509406b` | 全局代理页 | 与 per-provider ProviderProxy 设计冲突，需融合语义（provider 优先，None=跟随全局）后再谈 |
| `b6df5f04` | OAuth 模块重构 | 等 MCP OAuth 实际需求 |
| `96fbe7e3` | videogen 模块 | 上游 UI 未出 |
| `0533acde` | 移除默认 rikkahub 提供商 | fork 默认提供商策略自行决策 |
| `ef94834a` | MCP kotlin-sdk 换 client-only | 完整包 server 侧是 MCP host（⑥）依赖，避免回切，暂不吸收（2026-09-06 决策） |
| `d3a53e0a` | PickVisualMedia | 上游自撤，失效 |
| 2.5.0 大块（`66de8b30` 队列 / `a621e277` 语音 / `580e05d6` ASR 双向流 / `097cdb90` ChatToolFactory） | 大功能 | 暂缓；语音与 roadmap 合并评估，ChatToolFactory 与重改 ChatService 冲突大 |

## 同步方式（ff-only）

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master
git push origin master
git checkout feat/restore-proxy   # 特性分支独立演进
```

## 注意点（合并历史教训）

1. **包名迁移**：上游全部 `me.rerere.*`，特性分支 `app.androidrik.*`（728 文件迁移过）；cherry-pick 后每个文件改 import。
2. **重改文件**：`GenerationHandler.kt`（重试包裹/#1812 分流/MCP 净化）、`ChatService.kt`（#1812 落库/审批/tool 组装）、`ChatInput.kt`——上游改动集中在这三个文件时，手工适配成本最高。
3. **R8 混淆**：上游 `b1effc`（2.4.8 批）修过 `@Serializable` 反射崩溃；我们 fork 的 release 构建潜在同款风险（设置/备份/搜索反序列化），异常时优先加同款 keep 规则。
4. **两个 B 编号体系**：2.4.14 批与 2.4.16 批各有 B1/B3（含义不同），引用时注明批次。
5. **TikZ 路线**：dvisvgm 矢量链已废弃（文字悬空/字体映射遗留，见 tikz-rendering-tectonic-postmortem 8.4.9），现为 mupdf SVG 桥。

## 关联

[peer-apps-benchmark.md](peer-apps-benchmark.md)（A1 前台服务取舍/落地顺序基准）、[handoff-2026-09-04.md](handoff-2026-09-04.md)、[design-partial-failure-draft.md](design-partial-failure-draft.md)
