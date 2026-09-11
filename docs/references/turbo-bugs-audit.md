# RikkaHub Turbo bug 清单核查表

> 来源：`dcs666/rikkahub-turbo` 分支 `perf/rendering-and-streaming` 的 `PROGRESS.md`
> （存档于本目录 `turbo-PROGRESS.md`，742 行，120+ 轮深度审查，累计修复 48+ 真实 bug）。
> 用途：**逐条对照我们 fork（app.androidrik.rikkahub）核查是否存在同款 bug**。
> 状态列：✅ 已修 / ✅ 不存在 / ⚠️ 确认同款待修 / 🔍 待查（未定性）

---

## 一、崩溃类（Crash / ANR / OOM / SOE）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| C1 | 设置保存崩溃：删光搜索服务后 `coerceIn(0, size-1)` = `coerceIn(0,-1)` → IllegalArgumentException | 5be15b3 | ✅ 已修 | `SettingsStore.sanitizeSearchServiceSelected`（空列表守卫，coerceAtLeast(0)）+ 4 单测 |
| C2 | 损坏 base64 decode → 异常崩溃（分享/粘贴文本路径） | 1588d97 | ✅ 已修 | `StringUtils.base64Decode` try-catch 降级返回空串 + 2 单测 |
| C3 | 技能 zip bomb：readBytes 无上限 → OOM | f2a84d9 | ✅ 已修 | SkillsVM 单文件 32MB/总量 256MB（已移植） |
| C4 | BitmapComposer 取消挂起 + view 泄漏 | 48dd5c3 | ✅ 已修 | 异常 resumeWithException 不挂起 + finally removeView 清理 |
| C5 | JsonExpression 求值器 SOE：余额表达式超长/深嵌套（粘贴触发） | 66483bf | ✅ 已修 | `JsonExpression.kt` MAX_EXPRESSION_LENGTH=512 + try-catch 兜底 + 4 单测 |
| C6 | parseErrorDetail 递归 SOE：恶意/异常 API 深嵌套错误 JSON | 7c50c22 | ✅ 已修 | `ErrorParser.kt` MAX_ERROR_DEPTH=32 + 2 单测 |
| C7 | collectAllParts 嵌套 SOE：工具结果递归展开无上限 | 198ec66 | ✅ 已修 | `Conversation.kt` MAX_PARTS_DEPTH=16 |
| C8 | highlight 引擎崩溃：MAX_ITERATIONS 守卫触发无降级 | ad7da54 | ✅ 不存在 | `HighlightEngine.kt:92` 已有 catch RuntimeException → 降级纯文本，check 守卫触发即被降级捕获 |
| C9 | Room AutoMigration 链缺口（6→7/11→12/13→16 缺失）→ 存量用户升级崩溃 | f2bf4e4 | ✅ 不存在 | 链完整：autoMigrations 1→6/7→11/12→13/16→24 + DataSourceModule addMigrations 补齐 |
| C10 | 悬浮窗无 SYSTEM_ALERT_WINDOW 权限 → WindowManager.addView SecurityException → TTS 朗读即崩溃 | 25bfbdc | ✅ 已修 | `FloatingWindow.kt` canDrawOverlays 守卫，未授权静默降级 |
| C11 | CustomBodies JSON 深嵌套（万层）→ parseToJsonElement SOE（Error 非 Exception）→ UI 崩溃 | a038e62 | ✅ 已修 | PropertyEditor / UIMessagePart.inputAsJson / GenerationHandler 工具输入三处 catch Throwable |
| C12 | 导入 JSON readText 全量读 + PNG tEXt/Base64 解码无上限 → OOM | 2c8487c | ✅ 已修 | CherryStudioProviderImporter readLimited 5MB（Chatbox 已是流式 JsonReader） |
| C13 | 模型输出 base64 图片无限制（超大 decode OOM + 超大尺寸无采样 bitmap OOM） | 0b2fdd8 | ✅ 已修 | `FilesManager` base64 20MB 上限 + decodeSampledBitmap（4K 采样 + RGB_565） |
| C14 | mergeJsonObjects/removeElements 递归无深度上限 → SOE（CustomBody 构造 + GoogleProvider 响应） | 6fdb22d | ✅ 已修 | `Request.kt` MAX_JSON_MERGE_DEPTH=32，超限直接覆盖 |
| C15 | JsonTree 递归渲染深度上限缺失 → 深嵌套 AI 输出 SOE | 深挖批 8-9 | ✅ 已修 | `JsonTree.kt` MAX_JSON_TREE_DEPTH=12，超限渲染折叠占位 |
| C16 | TextArea 文件导入无上限 → 输入框渲染卡死 + system prompt 膨胀 413 | b6a3599 | ✅ 已修 | `TextArea.kt` MAX_IMPORT_CHARS=200K 截断 |
| C17 | 编译/工具输出相关（3a82c42 / 836440d / 1629002） | 3 提交 | 🔍 待查 | 编译期修复，无明确代码对照位置 |

## 二、数据丢失类（Data Loss）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| D1 | 超长消息保存失败静默吞掉（>2MB blob 限制）→ 用户无感知丢消息 | c135a3a | 🔍 待定 | 发送路径有 catch+addError（ChatService.kt:354），倾向已有用户可见反馈；流式保存路径未逐一核实 |
| D2 | 压缩期间新消息被整体覆盖 → 丢失追加节点 | ffc5a3a | ✅ 已修 | 已核对 ChatService 压缩逻辑同款；`mergeCompressedConversation` 保留快照后追加节点 + 4 单测 |
| D3 | 工具结果 base64 图片漏转 → require 失败消息丢失 | 77fef1b | ✅ 已修 | `convertBase64PartsRecursively` 递归转换 Tool.output 嵌套 parts |
| D4 | 历史页滑动删除立即物理删附件 → 撤销恢复后附件永久丢失 | 2b3574d | ✅ 已修 | `deleteConversation(deleteFiles=false)` + 撤销窗口结束 `deleteConversationFiles` 清理 |
| D5 | SettingsStore.update 并发 lost update（MCP syncTools 并发丢工具列表） | df7bb7c | ✅ 已修 | `update(fn)` Mutex 串行化读-改-写（Mutex 只加在 update 方法，未动 settingsFlowRaw） |
| D6 | ShareHandler 只 getStringExtra(EXTRA_STREAM)，Parcelable Uri 类型不匹配 → 分享图片/文件丢失 | 40da9cf | ✅ 已修 | `RouteActivity.readShareStreamUri` 兼容 String extra / Parcelable Uri / clipData |
| D7 | 多图分享 Uri 在 clipData 而非 EXTRA_STREAM → 仍丢图 | 4374afa | ✅ 已修 | 随 D6（clipData 第一项兜底） |
| D8 | eventsRoutes 条件注册：eventBus null 时整个 /events 路由消失（SSE 一起丢） | 条件注册修复 | ✅ 不存在 | WebApiModule.kt:174/182 两分支均无条件注册，EventsRoutes.kt 不依赖可空 eventBus |
| D9 | workspace_edit_file 宽容匹配 → 内容不匹配时静默替换 → 声明/逻辑丢失 | 记录项 | ⏸️ 搁置 | 三级容错匹配为显式设计；找不到时报错不静默；**用户决定搁置（2026-08-14）**，需产品决策 |

## 三、资源防护类（Resource / OOM 预防）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| R1 | 文档注入 prompt 无上限 → API 413/超时 | 4f135b3 | ✅ 已修 | `DocumentAsPromptTransformer` 200K 截断 |
| R2 | 记忆无上限 → prompt 爆炸 | 06baee4 | ✅ 已修 | `MemoryRepository` 200 条上限（删最旧）+ `buildMemoryPrompt` 60 条/100K 注入截断 + 3 单测 |
| R3 | QuickJS 原生内存泄漏（每次 eval 不销毁）→ 长会话 OOM | dd60e0a | ✅ 已修 | `JavascriptTool` try/finally context.destroy() |
| R4 | fetch 响应无上限 → OOM | ce187e4 | ✅ 已修 | `QuickJSFetch` 5MB 限量读取 + TRUNCATED 标记 |
| R5 | Bing body 无上限 → OOM | 7230279 | ✅ 已修 | jsoup 默认 maxBodySize 已有 2MB 限制，显式声明 maxBodySize(2MB) |
| R6 | translateText 输入无上限 → API 413 | 15e37df | ✅ 已修 | `GenerationHandler` MAX_TRANSLATE_CHARS=200K |
| R7 | GitHub API httpGet 响应无上限 → OOM | 5f53f30 | ✅ 已修 | `SkillsVM.downloadText` 4MB 限量读取 |
| R8 | 用户消息无上限 → 存库膨胀 + 上下文爆表 + API 413 | 0e6432f | ✅ 已修 | `ChatService.preprocessUserInputParts` 200K 单点截断 |
| R9 | 分享文本经 base64 → 输入框无长度限制 | 批 291 | ✅ 已修 | 随 R8（分享文本走同一 preprocessUserInputParts 路径） |
| R10 | 模型 base64 图片（同 C13） | 0b2fdd8 | ✅ 已修 | 见 C13 |
| R11 | TtsSynthesizer 音频流全量收集无上限 → OOM | b57d2a5 | ✅ 已修 | `TtsSynthesizer` 8MB 上限停止收集 |
| R12 | writeTextRootfs 无大小限制（workspace_write_file 工具）→ 超大落盘 | a4bfa04 | ✅ 已修 | `WorkspaceTools.writeTextInRootfs` 2MB require（对齐 WorkspaceFileSystem） |

## 四、功能/正确性类（Functional）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| F1 | zip-slip 备份恢复（S3/WebDAV upload/ 分支路径穿越） | 88a12d1 | ✅ 已修 | canonical + startsWith（已移植） |
| F2 | toolCallId 文件名逃逸（模型可控 ID 含 ../） | a9449cc | ✅ 已修 | 清洗 [A-Za-z0-9_-]（已移植） |
| F3 | workspace delete/move "./" 变体可删整个工作区 | 9677cd4 | ✅ 已修 | canonical 对比根目录（已移植） |
| F4 | file_paths.xml 暴露 filesDir | db26bff | ✅ 已修 | 收窄 upload/ + tool_outputs/（已移植） |
| F5 | mermaid 导出失败误报成功 + 调试 URI 日志 | fe2109d | ✅ 不存在 | `Export.kt:270-278` onFailure 显示错误 toast + 取消向上传播，不误报成功 |
| F6 | RouteActivity CRLF 规范化 | 3652fd6 | 🔍 低优先 | 源仓 `dcs666/rikkahub-turbo` 已不可达，diff 无法复核（2026-09-11 尝试：本地对象库无 / gh api 404 / commit 搜索无果）；fork `RouteActivity.kt:206-237` 分享文本读取（EXTRA_TEXT/PROCESS_TEXT）无 CRLF 归一，影响面低（换行风格问题、无崩溃/丢失）；并入吸收 `513b784c`（动 RouteActivity）时顺手一行关闭 |
| F7 | extractTar 特殊头顺序（GNU tar 全局头 name 空跳过数据区）+ parsePax 死循环/损坏头崩溃 | d990687 | ✅ 已修 | `a766399f`（parsePax 健壮性）；`RootfsInstaller.kt` 防护齐全——空名全局头跳过数据区（120-123）、LONG_NAME/LONG_LINK/PAX pending 顺序（124-140）、OTHER 数据 skip（161-164）、parsePax length≤0 安全退出（248-267，注释明写 F7）、路径逃逸拒绝（338-348） |
| F8 | WebDav buildUrl 路径段不编码（文件名含空格/中文/#/?/& → 同步失败） | ea17fb4 | ✅ 已修 | `encodeWebDavPathSegment` 逐段 percent-encode（UTF-8 + %20）+ 3 单测 |
| F9 | ASR start() 静默失败（焦点被拒/provider 未配置无反馈） | bdae36e | ✅ 已修 | `a766399f`（ASR 静默）；VoiceMode 未配置/无 key/不支持均有文案（configure_asr/configure_key/unsupported_asr），焦点被拒显式 check 抛错（VoiceMode.kt:148），各 provider start 全失败路径 setError→ASRStatus.Error（DashScope/Volcengine 已逐一核对） |
| F10 | WebServerManager.restart() 竞态（stop 异步 → restart 只停不启） | be3a48d | ✅ 已修 | restart 同一协程内同步停止（server=null）后再启动 |
| F11 | GoogleProvider inlineData 拼完整 data URI（纯 base64 渲染失败）+ video/audio | 深挖批 8-9 | ✅ 不存在 | GoogleProvider.kt:678-705 已是纯 base64 + mimeType，video/audio 已支持 |
| F12 | URL 拼接重复（base_url 已含 path → /responses/responses 404） | 深挖批（#57） | ✅ 已修 | `apiEndpoint` 去重（ResponseAPI/ChatCompletionsAPI 应用）+ 3 单测 |
| F13 | SAF DocumentsProvider documentId 合法性校验（恶意 App ws/.. 越权） | 深挖批（v1.8.7） | ✅ 不存在 | `WorkspaceDocumentsProvider.kt:277-288` canonical + startsWith 校验路径不逃逸 |
| F14 | MCP 服务器名校验（- 和 _ 放行） | 深挖批（v1.8.6） | ✅ 已修 | `isValidMcpName` 放行 - _（原只允许字母数字） |
| F15 | 图片文件名含未清洗 modelName（用户自定义 provider 可含 /..） | 8425679 | ✅ 不存在 | `FilesManager.kt:154` 固定 buildUuidFileName("image.png")，不含 modelName |

---

## 核查进度

- [x] 安全修复 5 项已移植（F1-F4 + C3）：commit e33535eb / 发布于 v2.4.5-androidrik.17
- [x] **第一批（v2.4.5-androidrik.22）**：C1 / D2 / D4 已修
- [x] **第二批（v2.4.5-androidrik.23，P0 崩溃/数据）**：C2 / C5 / C10 / D5 / D6 / D7 / F8 / F10 已修 + 9 单测
- [x] **2026-08-14 全量核查完成**：48 项全部定论
- [x] **第三批（v2.4.5-androidrik.24，P0 剩余 + P1 资源防护 + P2）**：C4 / C6 / C7 / C11 / C12 / C13 / C14 / C15 / C16 / D3 / F12 / F14 + R1-R12 全部已修（22 项）+ 10 新单测；C8 核查后确认不存在（已有降级）；D9 按用户指示搁置
- [ ] 剩余：C17（编译期，无法对照）/ F6 / F7 / F9 待定项 + D1 待核

> 记录：2026-08-13 建立，2026-08-14 全量核查定论 + 三批修复（共 32 项）。原始来源 docs/references/turbo-PROGRESS.md（turbo 仓库存档）。
