# 上游变更分析（综合版，2.4.11 → 2.5.1+，2026-09-12 重写）

> 本文档合并原 `upstream-2.4.11/2.4.14/2.4.16/2.5.0-analysis.md` 四份分析（已删除）。
> 当前 master 同步点：`a9b35ba2`（2.5.1，2026-09-10 ff-only 推送）。上游其后 2 提交未同步：`46cacf23`（deepseek-flash，已手工吸收 `e9a39751`）、`2689e753`（AGENTS.md 支持，待评估）。
> 决策原则：**等上游稳定后按需吸收**；触及 `GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`（fork 重改最重的文件）一律手工适配，不直接 cherry-pick；cherry-pick 需包名适配（`me.rerere.*` → `app.androidrik.*`）。
> **判定方法（2026-09-12 纠正）**：fork 的"已吸收"以**代码等价核对**为准，**不用 `git merge-base --is-ancestor`**——fork 走手工直套/移植，上游 commit 不是 fork 分支祖先，merge-base 对已吸收项恒判"未吸收"（误报 glm5.3/hy4/kimi k3/deepseek v4.1 flash 等）。核对方式：grep fork 代码中对应定义/注释（如「对齐上游 xxx」）或 diff 等价。

## 批次概览

| 批次 | 范围 | 规模 | 核心主题 | 状态 |
|---|---|---|---|---|
| 2.4.11（36 提交） | `8824e0e8`..`1e3351f7` | 78 文件 +2691/-649 | 全局代理页（放弃）、终端多 Tab、R8/JWT、think 标签 | 已吸收核心件 |
| 2.4.12–2.4.14（38 提交） | `1e3351f7`..`7da69770` | 103 文件 +4842/-2196 | 后台生成稳定性（与自有方案重叠）、Gemini 工具共存、多 key/模型注册 | 已吸收核心件 |
| 2.4.16（17 提交） | `5662945c`..`08c2648b` | 54 文件 +839/-301 | TTS 重试、Gemini 混合工具后续 | 已吸收核心件 |
| 2.5.0（16 提交） | `b153028d`..`12ee935e` | — | 终端 Tab 标题、审批修复、**消息队列/语音/ASR（fork 自研落地）** | 核心功能已吸收/自研 |
| 2.5.0 后批（20 提交） | `12ee935e`..`6e0aa7d4` | 2026-09-09 | workspace 预览/Shell 兼容、时间提醒间隔、多选、工具详情/回执、模型注册 | 大半已吸收（见清单） |
| 2.5.1（3 提交） | `6e0aa7d4`..`a9b35ba2` | 2026-09-10 | DEV badge、翻译快捷方式 + RouteActivity 统一导航 | 未吸收（D/B 候选） |
| 2.5.1 后（2 提交） | `a9b35ba2`..`2689e753` | 2026-09-11+ | deepseek-flash 注册、AGENTS.md 支持 | 前者已吸收，后者待评估 |

## 已吸收（合并清单，**含 2026-09 全量**）

| 提交 | 内容 | 吸收提交 | 备注 |
|---|---|---|---|
| 2.4.8-2.4.10 批（约 13 个） | R8 keep/jlatexmath、Imggen override、流式 null 容错、reasoning max、加密推理、DashScope ASR、SAF、.agc、豆包搜索、句首大写、正则排序 | 分批 | 2026-08 |
| `e8293d35` / `b62d29d1` | 空 tool schema 归一化 / workspace stdin EOF | `758a252d` | 2026-09-04 |
| `942d0d28` / `6c6a8458` | 合成消息不参与模板 / fork 会话继承 folder+cwd | `6bbcacb6` / `77abbd89` | 2026-09-06 |
| `3a533a6a` | 终端多 Tab + 后台底座 | `fbfcde5e` | 2026-09-03 |
| `104040df` + `1d86b3c1` | 终端 Tab 动态标题 + 手势/尺寸修复 | fork 移植 | 2026-09 |
| `5736c05e` | 代码块导出截断模式 | `e2f6b836` | 2026-09-05 |
| 1c09d5cd 等 5 提交 | TikZ mupdf SVG 桥（TEXT_AS_PATH）+ 缓存 | fork 会话 | 2026-09-08 |
| `85402745` / `54030ec4` | think 标签锚定 / Skill SnakeYAML | `c3385e63` / `3b482036` | 2026-09-08 |
| `55506496` | 连续工具审批丢失 + 取消状态修复 | 09-08 批 | 2026-09-08 |
| `5902feab` / `ab8b6cc8` / `a61d116d` | 纯图片消息 / provider IO 调度 / 自定义 response API | 09-08 批 | 2026-09-08 |
| `e9b98a4b` | qwen tts 音色列表 | `89b00455` | 2026-09-09 |
| `a8f8c3a1` | Exa 新鲜度证据 | `446f5a82` | 2026-09-09 |
| `5403bc96` + `2f05019b` | TTS 合成自动重试 + 错误分类 | `ec398672` | 2026-09-09 |
| `9851d037` + `f7869e35` | Gemini 工具共存 + 混合错误修复（捆绑） | `366a8de7` + `7c77c483`（fork 内联架构重实现） | 2026-09-09 |
| `77a58c2c` / `dee88dca` / `ab07ac1f` / `6e0aa7d4` | 统计 JSON 兜底 / edit_file 安全 cast / DeepSeek V4.1 Flash / 工具详情前置可开 | `4cbdb104`（B 批直套） | 2026-09-09 |
| 模型注册组（glm5.3 `5b58c957` / hy4 `86c85236`+`5b890d22` / kimi k3 / qwen3.8 `0826a3b9` / deepseek v4.1 flash / kimi icon `979cd169`） | 模型数据 | 早于 fork（直套）+ 本次缺口 `c73f8972`/`46cacf23`→`e9a39751` | 代码等价核对（2026-09-12） |
| `fcb04126` / `3d75f83f` | web ask_user 多选 / 模型弹窗标签堆叠 | `c468f0ba` | 2026-09-11 |
| `5b4d7895` | 自定义时间提醒间隔 | `772cd77e` | 2026-09-11，真机通过 |
| `a7ee362f` | 系统栏图标色退出恢复 | `e9a39751` | 2026-09-12，真机通过 |
| `1a1e672e` | 模型/提供商名支持空格（保存去尾） | `bb92f489` | 2026-09-12，真机通过 |
| `513b784c` | 翻译快捷方式 + RouteActivity 统一 Intent 导航 | `58a64d8d` | 2026-09-12，真机通过（四路径冷/热） |
| 队列 `66de8b30` / 语音 `a621e277` / ASR `580e05d6` | 消息队列/语音模式/ASR 双向流 | **fork 自研落地**（`3a911f1e`/`6680668d` / `1c499b79`..`27dc690d`） | 2026-09-09/10 |

## 已放弃 / 不跟

| 提交 | 内容 | 原因 |
|---|---|---|
| `986b9c39`+`3509406b` | 全局代理页 | 与 per-provider ProviderProxy 设计冲突（2026-09-08 决策） |
| `9365c297` | 移除独立标题/建议模型配置 | 保留独立配置（`710ba2c3`） |
| `c62f1eb1` | 生成期临时 FGS | 与自有 KeepAliveService 重叠 |
| `c16fe44f`+`41353567` | 固定 3 次重试 + 开关 | 自有 RetrySetting 是超集 |
| `fa0305ba`+`daae3749` | IME 修复 | UX 动画优化非 bug，ChatInput 定制深 |
| `05a85a09` | ask_user 自定义文本 | **已自有**（AskUserTool 描述明示支持自由文本，`c1924f13` 复核） |
| `097cdb90` | ChatToolFactory 重构 | 零功能收益，fork 定制碰撞；除非出现第二个工具装配方 |
| `b6df5f04` / `96fbe7e3` / `0533acde` / `d3a53e0a` | OAuth 重构 / videogen / 移除默认提供商 / PickVisualMedia | 等实际需求 / 上游未出 / fork 自决 / 上游自撤 |
| `ef94834a` | MCP kotlin-sdk 换 client-only | 完整包 server 侧是 MCP host（⑥）依赖，待 ⑥ 立项时处理 |
| `7714f2bc` | chatbox v2 导入 | 按需 |

## 待吸收（现状真存量，按优先级）

### 第一批（小件，可直接套/核对）

| 提交 | 内容 | 备注 |
|---|---|---|
| `aab5026e` | Live Update 通知图标（1 行） | 极低 |
| `7b92f89e` | 禁用代码块连字（#1623） | highlight 模块 +2 行，fork 差异小—待核对 fork 是否已禁 |
| `4fcb590a` | 测试连接对话框本地化 | 小 |
| `321443d8` | tool-only 消息操作按钮可见（#1689） | 小，待核对 fork ChatMessage 状态 |
| `74397a32` | 新增 MaruCode 提供商 | 极低，fork ModelRegistry 未见，追加式注册 |

### 第二批（值得吸收，需适配/手工合）

| 提交 | 内容 | 备注 |
|---|---|---|
| `42b933d8` | debug 启动图标 DEV 角标 | **D 顺手**：debug 变体纯新增零冲突 |
| ~~`513b784c`~~ 翻译快捷方式 + RouteActivity 统一导航 | **✅ 已吸收（2026-09-12，`58a64d8d`，真机通过）**：handleIntent 统一入口（冷启动/onNewIntent + pendingIntents flush + 去重）；fork 适配保留 readShareStreamUri 三级兜底；顺带关闭 turbo F6（normalizeNewlines） |
| `4530...` 工具回执/审批细化（若有） | — | 已吸收（`6e0aa7d4`/`05a85a09` 见上），无存量 |
| `20e552f9` | X-Session-ID 会话头 | 搁置（fork 无 sessionId 概念，无 opencode 端点） |

### 第三批（大改/暂缓）

| 提交 | 内容 | 备注 |
|---|---|---|
| `7038e981` / `03246406` | workspace HTML 预览 / Shell 兼容模式+DB 迁移 | fork WorkspaceDetailPage 340 行差异 + DB 25.json 迁移对齐，高成本 |
| `3896b6d6` | Imggen 长按多选 | ImgGenPage 大改，fork 定制深 |
| `45275025` / `019c46ae` | 上下文限制文案 / /skills 免审批 | D 类低优先，按需 |
| `2689e753` | AGENTS.md 支持 | 新提交未评估——可能涉及 AGENTS.md 注入上下文，待评估 |

## 同步方式（ff-only）

```
git fetch upstream
git checkout master
git merge --ff-only upstream/master
git push origin master
git checkout imperfect-customization-and-refinement
```

## 注意点（合并历史教训）

1. **判定方法**：已吸收用**代码等价核对**（勿用 merge-base `--is-ancestor`——fork 手工直套，上游 commit 非 fork 祖先会恒误报；2026-09-12 已纠正）。
2. **包名迁移**：上游 `me.rerere.*` → fork `app.androidrik.*`；所有 cherry-pick/移植后改 import。
3. **重改文件**：`GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`——上游改动集中在这三文件时手工适配成本最高。
4. **R8 混淆**：上游 `b1effc` 修过 `@Serializable` 反射崩溃；fork release 潜在同款风险（设置/备份/搜索反序列化），异常时优先加同款 keep 规则。
5. **两个 B 编号体系**：2.4.14 批与 2.4.16 批各有 B1/B3（含义不同），引用注明批次。
6. **TikZ 路线**：mupdf SVG 桥（TEXT_AS_PATH），dvisvgm 链已废弃。
7. **fork 自研优先**：队列/语音/ASR/保活/审批串行化/多 key/fallback 均为 fork 自有实现——上游同类只是语义参考，不整体吸收。

## 关联

[peer-apps-benchmark.md](peer-apps-benchmark.md)（A1 前台服务取舍/落地顺序基准）、[handoff-2026-09-04.md](handoff-2026-09-04.md)、[design-partial-failure-draft.md](design-partial-failure-draft.md)
</content>
</invoke>
