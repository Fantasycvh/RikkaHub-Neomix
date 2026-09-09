# 上游变更分析（综合版，2.4.11 → 2.5.0）

> 本文档合并原 `upstream-2.4.11/2.4.14/2.4.16/2.5.0-analysis.md` 四份分析（已删除）。
> 当前 master 同步点：`6e0aa7d4`（2026-09-09 ff-only 同步并推送；上一同步点 `12ee935e` 2.5.0 tag）。
> 决策总原则：**等上游稳定后按需吸收**；触及 `GenerationHandler.kt` / `ChatService.kt` / `ChatInput.kt`（我们重改最重的文件）的提交一律手工适配，不直接 cherry-pick；所有 cherry-pick 需包名适配（`me.rerere.rikkahub` → `app.androidrik.rikkahub`）。

## 批次概览

| 批次 | 范围 | 规模 | 核心主题 |
|---|---|---|---|
| 2.4.11（36 提交） | `8824e0e8`..`1e3351f7` | 78 文件 +2691/-649 | 全局代理页（设计冲突）、终端多 Tab 架构、R8/JWT、think 标签 |
| 2.4.12–2.4.14（38 提交） | `1e3351f7`..`7da69770` | 103 文件 +4842/-2196 | 后台生成稳定性（FGS/重试，与我们正面重叠）、Gemini 工具共存 |
| 2.4.16（17 提交） | `5662945c`..`08c2648b` | 54 文件 +839/-301 | TTS 重试、Gemini 混合工具后续修复 |
| 2.5.0（16 提交） | `b153028d`..`12ee935e` | — | 终端 Tab 标题、工具审批修复、消息队列/语音、ChatToolFactory |
| 2.5.0 后批（20 提交） | `12ee935e`..`6e0aa7d4` | 2026-09-09 | workspace 预览/Shell 兼容、会话头、时间提醒间隔、多选、工具详情/回执 |

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
| `85402745` | think 标签误判锚定修复 | `c3385e63` | 2026-09-08 |
| `54030ec4` | Skill frontmatter 改 SnakeYAML 解析 | `3b482036` | 2026-09-08 |

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
| ~~`fa0305ba`+`daae3749`~~ | IME 修复 | 2.4.14 B5 | **不吸收（2026-09-08）**：UX 动画优化非 bug，ChatInput 定制深，合入成本>收益 |
| `9328a6e5`（fence/弧度归一化系 TikZ 改）| TikZ 细节 | 2026-09 | 已随 mupdf 桥路线消化 |

## 已放弃 / 不跟（合并清单）

| 提交 | 内容 | 原因 |
|---|---|---|
| `9365c297` | 移除独立标题/建议模型配置 | 产品决策放弃，保留独立配置（2026-09-04，`710ba2c3`） |
| `7da69770` 系设置页 bottom sheet | 文件清理 UX | 随设置页定制程度评估 |
| `7714f2bc` | chatbox v2 导入 | 仅按需 |
| `c62f1eb1` | 生成期临时 FGS | 与自有 KeepAliveService 重叠，保留自有 |
| `c16fe44f`/`41353567` | 固定 3 次重试 + 关闭开关 | 自有的 RetrySetting 是超集，保留自有 |
| `986b9c39`+`3509406b` | 全局代理页 | **已放弃（2026-09-08）**：与 per-provider ProviderProxy 设计冲突，融合语义需改动代理配置模型 + 请求链路，收益小于维护两套代理的成本；保留每提供商独立代理 |
| `b6df5f04` | OAuth 模块重构 | 等 MCP OAuth 实际需求 |
| `96fbe7e3` | videogen 模块 | 上游 UI 未出 |
| `0533acde` | 移除默认 rikkahub 提供商 | fork 默认提供商策略自行决策 |
| `ef94834a` | MCP kotlin-sdk 换 client-only | 完整包 server 侧是 MCP host（⑥）依赖，避免回切，暂不吸收（2026-09-06 决策） |
| `d3a53e0a` | PickVisualMedia | 上游自撤，失效 |
| ~~2.5.0 队列~~（`66de8b30`） | ~~大功能~~ | **✅ 已自研落地（2026-09-09，`3a911f1e`+`6680668d`）**：生成间连发入队、轮次后自动派发、失败暂停、附件清理；保活走 fork 独立 KeepAliveService |
| ~~语音 `a621e277` / ASR 双向流 `580e05d6`~~ | ~~大功能~~ | **✅ 已落地（2026-09-10）**：服务端 VAD 语音轮闭环（`1c499b79`/`d2789207`/`bf0cf85a`/`a80fbd47`/`27dc690d`），OpenAIRealtime/DashScope/Volcengine 支持 |
| ChatToolFactory `097cdb90` | 纯重构 | **不吸收**（2026-09-09 复核）：结构重组零功能收益，fork ChatService/GenerationHandler 深层定制碰撞成本高；除非出现第二个工具集装配调用方（MCP host / web 生成） |

## 2.5.0 后批评估（2026-09-09，`12ee935e`..`6e0aa7d4`，20 提交）

> 本批 20 提交已逐个评估。前 10 个已核源码，其余按文件名/规模/与 fork 差异估算。
> fork 差异度数据见各表备注（`git diff --stat upstream/master HEAD -- <file>`）。

### A 已吸收 / 重叠（无需动作）

| 提交 | 内容 | 说明 |
|---|---|---|
| `5bf6d8c2`/`7dd2b3f8`/`8b3e094b` | 新增 apimart 赞助商、移除 ack ai（3 连 docs） | README/文档类，our fork 独立 README，不跟 |

### B 修复优先级（值得吸收，成本低-中）

| 提交 | 内容 | 成本 | 备注 |
|---|---|---|---|
| `7b92f89e` | 禁用代码块连字（#1623） | 极低 | highlight 模块独立，`Highlighter.kt` +2 行；fork 该文件 8 行差异，可直套 |
| `77a58c2c` | 统计页不合法 JSON 崩溃（#1694） | 低 | `MessageNodeDAO` 用 `json_valid` CASE 兜底 + 单测；fork 文件 17 行差异，结构一致可直套 |
| `dee88dca` | workspace_edit_file 非 JSON 对象崩溃（#1873） | 低 | `ChatMessageEditedFiles` JsonArray 安全转换 +2 行；fork 16 行差异 |
| `a7ee362f` | 退出终端后系统栏图标颜色异常（#1876） | 低-中 | `Theme.kt` SideEffect→DisposableEffect + onDispose 恢复；fork Theme 109 行差异，改动自包含 |
| `20e552f9` | X-Session-ID + opencode 会话头 | 中 | ai 5 文件：Request.kt 新增 `configureSessionHeaders`；fork provider 路径已迁（`app.androidrik.ai.provider.*`）、baseUrl 用 `apiEndpoint` 工具——需适配 apiEndpoint 拼 URL，`ApiKeyEntry` 无影响 |
| `ab07ac1f` | 注册 DeepSeek V4.1 Flash（多模态） | 极低 | ModelRegistry +8 行；fork 同文件 700 行差异（fork 模型更多）但追加式注册，`ALL_MODELS`/definition 两处插入 |
| `6e0aa7d4` | 工具执行完成前可打开调用详情（#1840） | 中 | `ChatMessageTools` 移除点击状态限制（1 行实质）；fork 该文件 168/91 差异（审批/回执定制深），需手工合 |
| `05a85a09` | ask_user 所有选项支持自定义文本（#1868） | 中 | `AskUserTool` +5 行 + `ChatMessageTools` 129 行重构；与 fork 定制重叠，手工合 |
| `5b4d7895` | 自定义时间提醒间隔 | 中 | `TimeReminderTransformer` 间隔参数化（fork 20 行差异）+ `Assistant` 新字段（**需补 PreferencesStore 三件套**）+ `AssistantMemoryPage` 配置（fork 81 行差异） |

### C 冲突 / 大改（需先评估，暂缓）

| 提交 | 内容 | 备注 |
|---|---|---|
| `7038e981` | workspace HTML/SVG 预览 | `WorkspaceFileEditorPage` +39 行 + DetailPage；fork WorkspaceDetailPage 340 行差异（终端 Tab 定制），合入前先比 |
| `03246406` | workspace Shell 兼容模式 + 基础信息页布局 | 19 文件 +747/-128，含 **DB 迁移 25.json**（WorkspaceEntity +compatMode）+ PRoot seccomp 开关 + DetailPage 210 行重排；fork 有独立 WorkspaceEntity 演进史，需 DB 迁移对齐 |
| `1a1e672e` | 模型名/提供商名支持空格（保存时去尾） | 3 文件小改；fork SettingProviderDetailPage 372 行 / ProviderConfigure 239 行差异（多 key 编辑器定制），需手工合 |
| `3896b6d6` | 图片生成页长按多选（#1813） | ImgGenPage 375 行大改 + VM；fork ImgGenPage 405 行差异（Imggen override/模型定制），成本高，收益集中图片管理场景 |

### D chore / UI（低优先或按需）

| 提交 | 内容 | 备注 |
|---|---|---|
| `45275025` | 上下文限制 1~19 + 文案 | AssistantBasicPage 配置文案；fork 该页定制，低优先 |
| `fcb04126` | web ask_user 多选（#1654） | web-ui 前端 1 文件，需 pnpm 重装 web-ui |
| `3d75f83f` | web-ui 模型弹窗厂商标签堆叠（#1871） | web-ui 2 行 |
| `019c46ae` | /skills 免审批安全区 | WorkspaceTools 白名单 +2 行；fork 有自有白名单，核对后 1 行可合 |

### 本批推荐吸收顺序（按价值/成本）

1. **立即**：`7b92f89e`（连字）、`77a58c2c`（统计 JSON 崩溃）、`dee88dca`（edit_file 崩溃）、`ab07ac1f`（DeepSeek 注册）——全部低风险直套
2. **近期**：`a7ee362f`（系统栏颜色）、`20e552f9`（会话头）
3. **规划**：`5b4d7895`（时间提醒间隔，含持久化三件套）、`6e0aa7d4`/`05a85a09`（工具回执/自定义文本，ChatMessageTools 手工合）、`1a1e672e`（空格支持）
4. **暂缓**：`7038e981`/`03246406`（workspace 大改+DB）、`3896b6d6`（Imggen 大改）

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
