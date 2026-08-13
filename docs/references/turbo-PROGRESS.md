# RikkaHub Turbo - 异步任务队列进度

## 当前状态：✅ 120 轮深度审查完成，等待最终 CI 通过后发 v1.1.8-turbo

## 已完成

### 核心功能 (commit e5132e34 + ad75fb73)
- BackgroundTaskManager：单例，自适应轮询（5s active / 30s idle）
- GitHubActionsClient：OkHttp + User-Agent + callTimeout(60s) + 日志限 512KB
- TaskEntity + TaskDao：Room 持久化，DB v24→25，status/created_at/conversation_id 索引
- TaskNotificationManager：消费事件 → 通知（尊重 notifyOnSuccess）+ 注入对话 + CI 失败自动 AI 分析
- BackgroundTaskTool：AI 工具（create_ci_monitor / create_timer / list_tasks / cancel_task）
  - 完整 InputSchema（action enum, repo, branch, run_id, poll_interval_sec 等）
  - 自动注入 conversationId（AI 不需要知道 UUID）
  - 去重：同 repo+branch+runId 不重复创建
- TaskRoutes：REST API + GitHub webhook 端点（/api/tasks/*）
- AppEvent.BackgroundTaskCompleted：新事件类型
- 通知渠道：background_task（IMPORTANCE_HIGH）

### 扩展功能
- SettingTasksPage：完整任务管理 UI（列表、取消、状态图标、Toast 反馈）
- Settings 字段：taskGithubToken, taskAutoAnalyze, taskNotifyOnSuccess, taskPollIntervalSec
- Webhook：完成已有任务（不创建新任务），JSON parse 错误返回 400
- 指数退避：base → 2x → 3x → 5x，最大 5min
- /api/tasks/timer 端点（验证 delay > 0）
- TaskDto 含 description 字段（repo@branch）

### 代码审查修复（10 批 × 12 轮 = 120 轮）
- 竞态条件：completeTask 防重入 + markRunningIfPending 条件更新
- per-task try-catch：单个任务异常不中断整批
- 自适应轮询间隔（省电）
- JSON 注入修复：buildJsonObject + JsonPrimitive
- 图标修复：全部使用确认存在的图标
- Okio API：ByteString.utf8()
- cleanupJob 引用追踪
- sealed class serializer 统一解码
- Timer 支持小数（toDoubleOrNull）
- 去重逻辑
- Toast 反馈
- 准确的消息文案（"active" not "created"）

## 发布历史
- v1.1.7-turbo：已发布（含到 batch 2 的修复）
- v1.1.8-turbo：待发布（含全部 120 轮审查修复）
- v1.6-turbo：已发布（2026-08-04，含 R3.2 块级流式渲染 + 会话池/SSE 修复）

## perf/rendering-and-streaming 分支审查（第 121 轮起）
### 已实现并通过 CI（run 73-75）
- `116a160` fix(ai): 工具级执行超时（workspace_shell = 命令 timeout + 15s 缓冲，其余 60s）+ 死代码清理 + task_completed 事件桥接 SSE
- `6077ce4` perf(workspace): 每 workspace 独立常驻 shell 会话池（LRU，MAX_SESSIONS=3，独立锁）
- `115ca09` perf(ai): 同轮多工具并行执行（Semaphore 上限 4，结果按序挂回，取消语义与串行一致）

### 本轮审查修复（2026-08-04）
- ProotShellRunner 会话淘汰：原 removeEldestEntry 在 sessionFor 锁内同步 destroy()，若被淘汰会话有命令在跑会阻塞全部 workspace 的 shell（全局头阻塞，最长 600s）。改为手动扫描、只淘汰空闲会话；全忙时允许池暂时超限（有界于并发命令数）
- eventsRoutes 条件注册 → 无条件注册：原 `eventBus?.let{}` 在 eventBus 为 null 时整个 /events 路由消失（settings/conversations/folders SSE 一起丢失）。现 eventBus 可空，仅缺 task_completed 事件
- R3.1 流式代码块语言标记剥离：```kotlin\ncode 流式渲染时首行会显示 "kotlin"（标记），生成结束切完整渲染后该行消失（跳变）。现按保守规则（紧贴围栏单 token + 有换行）剥离标记行；shebang/含空格行/单行代码不误删（JS 模拟 8 组边界全过）
- R3.1 注释纠偏：原文档称"未闭合尾段按普通文本兜底"，实现实为奇数索引段（含未闭合尾段）按代码渲染——后者才是"生成中代码可见"的预期行为，注释已更正

### R3.2 块级结构化流式渲染（2026-08-04）
- 流式分支从"仅代码块拆分"升级为"按行块分类"：代码块/标题/引用/列表/段落，样式与最终渲染对齐（HeaderStyle.fromLevel / 引用左线+斜体+浅背景 / "• " 与 "N. " 前缀 / monospace 容器）
- 分类规则与 intellij 解析器对齐（# 后必须空格、> 空格可选、列表符号后必须空格）→ 结束时无块类型跳变
- 追加式扫描：每帧 O(n) 行扫描，已渲染块参数不变 → Compose 智能跳过重组；JS 模拟 18 帧流式序列验证块序列完全稳定
- 行内格式（加粗/行内代码/链接）仍 strip 擦除兜底；跨行行内标记不做解析（防闪现又消失）
- 未闭合代码块尾段按代码块渲染（生成中代码可见），语言标记随围栏行整体丢弃（替代 R3.1 的单 token 启发式，更简单准确）

### 已确认无误（记录）
- R2.1 折叠调优：阈值 5000、整区可点展开、!loading 排除生成中消息、USER 不折叠——验证通过
- MarkdownParseCache LRU 上限 30，流式每帧缓存有界无泄漏
- AppEventBus 为 DI 单例，SSE 桥接可收到事件；timeout 参数名/钳制与工具一致；工具级超时对 shell 命令真正生效（runInterruptible→杀进程）；并行结果按序、取消语义与串行一致；锁顺序无死锁

### 已知取舍（记录不修）
- 并行工具对同一文件路径的写竞态（workspace_write_file + edit 同轮同路径可能 lost update）——OpenAI parallel calling 语义固有，模型通常不会同轮改同一文件
- 非 shell 工具超时后底层工作可能短暂继续（workspace_shell 走 runInterruptible 会真正杀进程）
- 流式语言标记仅限 ``` 围栏；~~~ 围栏与 4+ 反引号围栏不识别（既有范围）

## 第 123-124 轮全面审查（2026-08-04，workspace 模块 + data/service 层 + web 层）
### 本轮审查修复（6 个提交）
- `d990687` fix(workspace): extractTar 特殊头（LONG_NAME/LONG_LINK/PAX/GLOBAL_PAX）处理移到 name.isBlank() 检查之前 —— GNU tar 全局头 name 为空，原顺序直接跳过数据区，GLOBAL_PAX 支持（e6a347f 引入）实际从未生效；parsePax 加固：length<=0 死循环、损坏头 substring 崩溃（JS 6 组边界全过）
- `df7bb7c` fix(settings): SettingsStore.update(fn) 读-改-写加 Mutex 串行化 —— 并发调用基于同一快照导致 lost update（MCP syncTools 并发丢工具列表的根因）
- `88a12d1` fix(sync): zip-slip 路径穿越 —— S3/WebDAV 备份恢复的 upload/ 分支直接 File(uploadFolder, fileName) 无穿越防护（FONTS 有 contains('/')、skills 有 SkillPaths canonical 防护），恶意/损坏备份可写 app 私有目录任意位置。修复为 canonical + startsWith 检查（JS 模拟 8 场景验证）
- `9d73247` fix(web): webhook HMAC 验证恒失败 —— expected 带 "sha256=" 前缀而 provided removePrefix 去前缀，MessageDigest.isEqual 对长度不同数组恒 false，配置 Token 后 webhook 全 401（秒级通知通道失效只剩轮询）
- `859a16e` fix(mcp): getAllAvailableTools 用 getCurrentAssistant() 过滤 → 改为调用方传入对话所属 assistant —— 多助手场景工具错配（暴露未授权 server 或缺失应有工具）
- `c897647` fix(chat): ConversationSession.setJob 旧 job 完成回调无条件清 _generationJob.value，晚于新 job 设置时误清新引用 → isGenerating 假 false / stopGeneration 失效 / 空闲检查误清理。改为 identity 比较

### 已确认无误（本轮深审）
- PromptInjectionTransformer（findSafeInsertIndex 防护、AT_DEPTH 深到浅、正则错误安全降级）
- GitHubActionsClient（rate limit 专用异常、4MB 日志上限、错误行提取）
- McpSessionRegistry（stale 检查、重连去重、NonCancellable 清理、connectionKey 精确判定）—— 记录：两 server 并发 syncTools 的 settingsStore.update lost update（已随 df7bb7c 修复）
- FilesManager（UUID 文件名、磁盘↔DB 双向同步、content:// 先落本地）
- ChatboxImporter（流式 JsonReader 不整载、SYSTEM 提示合并、未知 part 保留原文）
- ConversationRepository / MessageFtsManager（事务外 decode、FTS 参数化、blob 超限兜底）
- AppDatabase（migration 链 1→25 完整、WAL、jieba+FTS5）
- ChatService（fork 文件独立副本、内存-库一致性、压缩并行无上限——记录不修）
- ScreenTimeTool / CalendarTool / WebDavClient / S3Client（SigV4 签名正确）/ McpOAuth（PKCE+state 防 CSRF+刷新锁）
- WebApiModule（JWT 动态验证、常量时间比较、webhook 独立认证、zip-slip 双重防护）
- transformers / ExportSerializer / LocalTools / AppEventBus / BackgroundTaskManager 调度（动态唤醒+退避）
- MarkdownNew = HTML 内容回退渲染（活代码）

### 记录不修（低风险）
- compressConversation 的 async 并行无并发上限（chunk 数 = ceil(消息/256)，长对话并行请求多）
- tar size 字段 base-256 编码（>8GB 归档才出现）
- 下载无完整性校验（截断由 extractTar EOF 防御兜底）
- SimpleHtmlBlock 的 img src 任意 scheme（Coil 加载，用户主动渲染的 AI 输出）
- 多 <think> 块只保留第一个进 Reasoning（replace 删全部但 find 取第一个）

## 功能改进批次（2026-08-04，a797c10）
1. **搜索结果分页**：FTS LIMIT 50 硬编码 → limit/offset 参数化；SearchVM 每页 30 条滚动加载（过期页丢弃：查询/排序变化时忽略），SearchPage 接近底部自动加载下一页
2. **对话压缩限并发**：compressConversation 全并行 → Semaphore(2)，避免长对话同时打十几个请求触发 API 速率限制
3. **R3.3 流式行内格式**：闭合的行内代码/加粗/斜体生成中即可见（与最终渲染样式一致：等宽/Bold/Italic，复用旧 strip 同一组正则 → 识别规则零偏差）；未闭合标记按字面量显示（intellij 同样按字面量渲染 → 流式→完整零跳变）；链接仍剥除（半截 URL 渲染成链接会跳变）；JS 帧模拟验证：闭合瞬间出现样式且后续帧稳定、code 优先不重叠、嵌套正确、孤立标记字面量
4. **编辑消息版本切换 UI**：editMessage 追加版本但 UI 无切换入口（selectMessageNode 仅 web API）→ 多版本消息显示 v{n}/{m} 徽章，点击循环切换（ChatMessage → ChatList → ChatPage → ChatVM.selectMessageNode 链路）

## 功能改进批次修复（2026-08-04，0b3424f）
- **Build APK #104 失败**（a797c10 触发）：SearchVM 分页状态声明（isLoadingMore/hasMore/loadedCount/activeQuery/PAGE_SIZE）在首次编辑时被 workspace_edit_file 的宽容匹配**静默吞掉**——old_text 与实际文件不一致（误以为文件已有这些声明），宽容匹配跳过不存在的行后整块替换，代码引用了未声明变量 → unresolved reference
- 修复：恢复全部声明；逐一核对 10 个改动文件完整性（Markdown/ChatMessage/ChatList/ChatPage/ChatVM/ChatService/FTS/SearchVM/SearchPage/MessageFtsManager）
- **教训**：workspace_edit_file 的 old_text 必须与文件实际内容逐字一致；宽容匹配会在内容不匹配时静默替换，导致声明/逻辑丢失。编辑后用 grep 核对新增符号（本会话已核对）

## v1.7-turbo 发布（2026-08-04，2.4.6/175）
- **CI 连续修复 3 轮（df7bb7c → 0b4f002）**：
  1. **根因**：df7bb7c 把 `updateMutex = Mutex()` 插进 settingsFlowRaw 流链中间 → decode 管线挂到 Mutex() 上（非 Flow）→ 205 个编译错误，所有 Settings 消费方（~25 文件）级联失败。CI 从 df7bb7c 起红，之前监控未确认误以为绿
  2. **暴露的真实错误**：Settings 修好后 13 个错误全在功能改进批次：
     - 并行 edit 同文件竞态（read-modify-write 非原子）丢参数/import（ChatList onSelectVersion ×2、SearchPage 3 import）
     - `SpanStyleRange` 不存在 → `AnnotatedString.Range`
     - 原 bug 被掩盖：ChatService:553 `getAllAvailableTools()` 缺 assistant 参数
  3. **教训**：① edit 后必须 grep 核对新增符号；② 同文件编辑必须严格串行；③ CI 失败要拉完整错误列表（grep 全量 e: 行），别只看头部
- 发布：v1.7-turbo（含 8 修复 + 4 功能改进 + 3 轮 CI 修复）

## 全面逐行审查批次（2026-08-04，v1.7-turbo 发布期间）
- **新修复**：
  1. **JavascriptTool QuickJS 内存泄漏**（bc72f78）：每次 eval 创建 QuickJSContext 不销毁 → 原生内存泄漏，长会话 OOM。try/finally destroy()
  2. **i18n 硬编码中文**（d26bb49）：折叠按钮"展开全文"抽到 strings.xml（en/zh/zh-TW/ja/ko/ru 6 语言）
- **确认无问题**：web 模块全量（WebApiModule JWT 动态密码/常量时间比较、WebServerService FGS 容错、WebServerManager 端口预检、NsdServiceRegistrar、FilesRoutes 双保险路径防护、TaskRoutes webhook HMAC、ConversationRoutes UUID 校验）、通知管理器（节流/前后台）、ChatInputState 编辑合并、hooks（debounce/throttle/生命周期）、transformers（时间提醒/think 标签/正则/模板）、MCP OAuth（PKCE/DCR/串行刷新）、DAO 全参数化、SkillPaths 防护、migrations 兜底、CrashHandler、终端会话、VM 无泄漏
- **记录不修（低风险/取舍）**：
  - CoroutineUtils.toMutableStateFlow 流异常时 Runtime.halt(1) 硬杀进程（fail-fast 设计，settings 损坏场景）
  - MCP OAuth token 明文存 Preferences（app 私有目录，与既有模式一致）
  - webhook 未配 token 时不校验签名（局域网暴露场景低危）
  - WebServerManager.restart 未使用（stop 异步 + start early-return 竞态，无调用方）
  - FilesRoutes canonicalPath.startsWith 缺分隔符边界（已被 ".." 检查前置拦截）

## 持续逐行审查批次 2（2026-08-04，v1.7-turbo 发布后）
- **新修复**：
  1. **web-ui workbench 预览 XSS 加固**（9a15eab/5757477/7f4431b）：iframe sandbox 去掉 allow-same-origin（srcDoc iframe 继承父 origin，allow-scripts+allow-same-origin 组合可读写父页面 localStorage 里的 JWT）；mermaid securityLevel loose→strict（禁点击回调脚本）
  2. **ChatList.kt 括号修复**（6af5d66）：并行 edit 竞态残留的多余闭括号
- **确认无问题**：ai 模块全量（Provider 流式/KeyRoulette LRU/SSE/工具调用聚合/无界缓冲）、common（LruCache/QuickJSFetch 未用）、document、search（19 服务）、speech（TTS/ASR）、highlight（纯 Kotlin 引擎）、前端 api.ts（JWT 生命周期/401 处理/SSE）
- **记录不修**：QuickJSFetch 死代码（无调用方）、JSON 深层渲染无限制（acyclic）、McpOAuth deep link 无 state 时被忽略（随机 state 防护）
- **教训 3**：JSX 属性间不能写 // 注释（esbuild 报错）；同文件 edit 串行是硬约束（本轮 4 次竞态：ChatList 括号、sandbox 注释、文件尾部三重重复）

## 持续逐行审查批次 3（2026-08-04，审查收官）
- **确认无问题**：ChatSizeChecker（768 节点/30万token 阈值）、ChatDrawerVM（Paging+分隔符）、PromptVM/QuickMessagesVM（引用完整性清理）、SkillsVM（GitHub 导入 URL 正则校验 + zip 路径穿越防护 + 原子保存回滚）、WorkspaceVM、ProviderConnectionTester、前端编辑 draft 逻辑（附件保留/追加）、设置页全量
- **记录不修**：SkillsVM GitHub 导入无文件数/下载大小限制（技能仓库通常小）；workspace runBlocking（DocumentsProvider 同步 binder 必需）
- **收官状态**：621 Kotlin + 109 前端文件全部覆盖（深审核心逻辑 + 全库危险模式扫描）；TODO/FIXME=0；GlobalScope/Thread.sleep/System.exit=0

## 持续审查批次 4（2026-08-04）
- **新修复**：file_paths.xml 收窄 filesDir 暴露（db26bff）：原 path="." 把整个 filesDir（skills/fonts/workspaces）暴露给获得分享 URI 的外部 app → 只留 upload/images/tool_outputs；导出/相机/workspace 分享均在 cacheDir
- **确认**：Manifest 权限/exported 状态合理（WebServerService exported=false、FileProvider=false、DocumentsProvider 标准配置）；ShareSheet 的 apiKey 明文分享为有意设计（测试锁定往返）；测试套件 17 个文件覆盖 TextReplacers/PromptInjection/MCP key/backoff/rate-limit
- **记录不修**：usesCleartextTraffic=true（自托管 provider 需要）、McpOAuth deep link exported（state 防护）

## v1.7.1-turbo 发布（2026-08-04，2.4.7/176）
- **包含修复**（v1.7-turbo 之后）：QuickJS 原生内存泄漏（bc72f78）、i18n 折叠按钮 6 语言（d26bb49）、冗余 UI 移除（290973f+6af5d66）、web-ui 预览 iframe sandbox + mermaid strict（e9b7749）、file_paths 收窄 filesDir 暴露（db26bff）、全部审查记录
- CI 全绿验证：09888a0（Unit Tests + Build APK success）

## v1.7.2-turbo 候选修复批次（2026-08-04，11 个提交 c135a3a..4f135b3）
- c135a3a 超长消息保存失败静默吞掉 → 用户可见错误（>2MB blob 限制）
- ffc5a3a 压缩期间新消息被整体覆盖 → 合并最新状态保留追加节点
- 5be15b3 删光搜索服务后 coerceIn(0,-1) 设置保存崩溃
- 77fef1b 工具结果 base64 图片漏转 → require 失败消息丢失（抽取 parts 级转换复用）
- 1588d97 损坏 base64 图片 decode null → NPE 崩溃（降级保留原 part）
- fe2109d mermaid 导出失败误报成功 + 移除调试 URI 日志（CI 已验证 ✅）
- 3652fd6 RouteActivity CRLF 规范化
- f2a84d9 技能 zip 导入 zip bomb 防护（单文件 32MB/总量 256MB）
- 48dd5c3 BitmapComposer 取消安全（view 泄漏 + 永久挂起）+ import
- 4f135b3 文档注入 prompt 截断（200K 字符，防 API 413/超时）

## v1.7.2-turbo 发布（2026-08-04，2.4.8/177）
- **包含 18 个修复**（v1.7.1 之后，7230279 全绿验证）：
  - 数据丢失：超长保存静默失败（c135a3a）/ 压缩覆盖新消息（ffc5a3a）/ 工具 base64 漏转（77fef1b）
  - 崩溃：coerceIn(0,-1) 设置保存（5be15b3）/ decode null NPE（1588d97）/ 技能 zip bomb（f2a84d9）/ 导出取消挂起（48dd5c3）/ 编译修复 ×3（3a82c42, 836440d, 1629002）
  - 资源防护：文档注入截断（4f135b3）/ 记忆上限（06baee4）/ QuickJS 内存+超时（dd60e0a）/ fetch 5MB（ce187e4）/ Bing body 2MB（7230279）
  - 其他：mermaid 误报（fe2109d）/ CRLF（3652fd6）/ 日志清理
- CI 全绿：7230279（Unit Tests + Build APK success）
- **v1.7.2-turbo 发布完成**：2026-08-04 15:35（2.4.8/177，3 个 profileable APK，Release Turbo success，约 24 分钟构建）

## 全库深挖收官（2026-08-04，v1.7.2 发布后）
- 6 批深挖（未发现新 bug，全部干净）：
  1. utils 22 个工具类（UpdateChecker/SemVer/SimpleCache/SoundPool/通知/CursorWindow/时间格式化等）
  2. 设置页 21 个（端口校验/权限/主题导入/任务/MCP 导入/文件删除确认等）
  3. 功能页面（ImgGen/Translator/终端 proot/日志/收藏/历史/分享/Debug/工作区）
  4. speech 模块 4303 行全审（TTS 8 provider + ASR 5 provider：超时/SSE/WebSocket/录音释放/临时文件清理全到位）
  5. web-ui hooks 6 个 + chat-input + 组件（epoch 竞态防护/订阅清理/发送状态机高质量）
  6. material3 DynamicSchemeExt + locale-tui（yaml.safe_load 安全）

## 深挖第二轮（2026-08-04，v1.7.2 后批 7-29，发现 3 个新修复）
- 66483bf JsonExpression 求值器 StackOverflowError：余额表达式超长/深嵌套（粘贴触发）→ 512 字符上限 + SOE 兜底
- 7c50c22 parseErrorDetail 递归深度上限 32：恶意/异常 API 深嵌套错误 JSON → SOE 崩溃
- 防御：collectAllParts 嵌套深度上限 16（工具结果递归展开）
- 深审确认干净：ai Provider 全家（FileEncoder OOM 防护/KeyRoulette LRU/ResponseAPI 流式/Claude/Google/Vertex JWT）、common 缓存/SSE、workspace 安装器（tar/pax/symlink 防护）、document 解析器、highlight 状态机（无 ReDoS）、web 路由全家（AIIcon/Folder/Settings/RouteUtils）、DTO、依赖审计（全最新版）、BackgroundTaskManager 状态机、MCP OAuth（PKCE/刷新锁）、Navigation、ChatDrawerVM

## 深挖第三轮（2026-08-04 批 30-40，新增 4 个修复，累计 24）
- ad7da54 highlight 引擎兜底：MAX_ITERATIONS 守卫触发时降级纯文本（原崩溃 UI）
- a9449cc toolCallId 文件名清洗：模型可控 ID 含 ../ → 逃逸写 app 私有目录
- 1ed113a 记忆双端限制：条数 200（最旧淘汰）+ 注入 60 条/100K 字符（防 prompt 爆炸）
- 深审干净：ChatVM/ChatService（防并发生成+Cancellation 过滤）/FilesManager/S3Sync+WebDav 导入（zip-slip canonical 防护在案）/SkillPaths/HighlightEngine（零宽匹配防死循环+迭代守卫）/CalendarTool（LIKE 参数化）/BackgroundTaskTool（poll 下限 10s）/MemoryTools/GenerationHandler

## 深挖第四轮（批 41-50，全部干净，0 新 bug）
- 审过：PreferencesStore 迁移 V1-V3/SettingsJsonMigrator（runCatching 兜底）、设置 ProviderDetail（删除确认）、OCR（LRU 缓存+getOrElse）、ASR 音频焦点（GRANTED 检查+abandon）、ModelDsl TokenMatcher（内置正则）、WebDav/S3 同步（zip-slip 同款防护）、WebApiModule JWT（动态密码/subject 校验/403 区分）、前端 files/export-markdown、AI 嵌入
- 剩余轻微 UX 项（非 bug，记录不修）：ASR 焦点拒绝无提示、ModelList 加载失败静默、WebDav URL 未编码（path 内部生成）

## v1.7.3-turbo 发布（2026-08-04，2.4.9/178）
- 包含 6 个修复（v1.7.2 后深挖轮）：JsonExpression SOE（66483bf）/ parseErrorDetail 深度（7c50c22）/ collectAllParts 深度（198ec66）/ highlight 兜底（ad7da54）/ toolCallId 文件名清洗（a9449cc）/ 记忆条数 200+注入封顶（1ed113a）
- 双绿验证：5813aef（Unit Tests + Build APK success）

## 深挖第六轮（批 61-90，发现 1 个修复，累计 26）
- f2bf4e4 Room AutoMigration 链缺口（6→7/11→12/13→16 缺失 → 存量用户升级崩溃）补齐
- 审过干净：MCP 官方 SDK transport（本地 transport 为死代码）、ChatPage 图片链路（HEIF 转换+回退）、assistant 详情/导入（SillyTavern 解析容错）、SearchVM（debounce）、web-ui conversation/code-block（subscriber 清理）、highlight 语言定义、ai 核心类型（Modality 设计）、AppDatabase 其余迁移、工作区编辑器、导出导入（JSON 校验）、Koin 4 模块、Manifest（exported 正确）、Gradle（minify=false 有注释）、前端 types/locales、SafeMode（崩溃恢复）、WebViewPage（JS 默认关闭）、Provider.getBalance TODO（被类型守卫挡住非 bug）

## 深挖第七轮（批 91-140，50 批，0 新 bug）
- 审过：消息组件（分组/分支/工具 UI）、ChatSizeChecker（阈值）、PropertyEditor（JSON 容错）、Navigator（自研导航）、ai 类型、highlight 31 语言、FTS（limit coerce+cursor.use+枚举 orderBy 无注入）、OCR 缓存 64、ChatExport（FileProvider）、通知渠道 5 个、StatsVM（GROUP BY≤371 行）、web-ui hooks 全 6 个（cleanup 完整）、SkillsVM（zip bomb 防护复核）、终端（onDispose finish）、i18n 完整性验证（@string 引用 0 缺失）、ModelRegistry（模态解析正确）、ChatService 压缩 Semaphore(2)、S3Sync 复核、RikkaHubApp 初始化链（全 runCatching）、CrashHandler、后台维护 5 函数、TTSAutoPlay/ConversationList/WorkspaceRepository

## 深挖第八轮（批 141-190，50 轮，发现 1 个修复，累计 27）
- 15e37df translateText 输入截断 200K（与文档注入一致，防 API 413/超时）
- 审过干净：AudioPlayer 全量（listener 清理/invokeOnCancellation/WAV 头）、TaskEntity 4 配置、WebServerService、ChatboxImporter（流式+稳定 UUID）、翻译双分支、ImgGenPage、FolderRepository、CustomTheme（toInt 截断安全）、workbench（iframe sandbox=allow-scripts 无 same-origin ✔）、McpOAuthCallback（appScope 防丢事件）、AskUser/ScreenTime（时间解析容错+范围校验）/Clipboard/Calendar（审批+权限）工具、SettingSearchDetailPage

## 深挖第九轮（批 191-240，50 轮，发现 1 个修复，累计 28）
- 5f53f30 GitHub API httpGet 响应限量 4MB（与日志限量一致，防异常响应 OOM）
- 审过干净：ChatDrawer（snapshotFlow 滚动保存）、MeshGradient/Background（opacity coerce）、BalanceOption、SearchPage snippet 解析（index 推进无死循环）、web-ui clipboard（execCommand 回退+选区恢复）、DTO 全量、speech 模型、GitHubActionsClient（30s 超时/rate limit 识别/4MB 日志）、web-ui i18n 键 0 差异、Settings 模型默认值、ChatList（滚动/选中）、HighlightCache（LRU 100+synchronized）、ImageGenSize、LanguageSelector（when+else 兜底）、ConversationSystemPromptButton

## 深挖第十轮（批 241-290，50 轮，0 新 bug）
- 审过：PresetTheme/findThemeById（兜底）、消息组件剩余（CopySheet/NerdLine/Translation/EditedFiles 导出 FileProvider）、ToolUI/BuiltinToolUIs（记忆删除）、ChatInputState（编辑态）、web-ui api.ts（localStorage token+expiresAt 校验）、ProviderConfigure/ConnectionTester（runCatching）、AppEventBus（buffer 16+tryEmit 丢弃策略，7 文件消费方无孤儿）、PreferencesStore update（dummy 防护）、NotificationUtil、SettingPage（赞助弹窗）


## 深挖第十一轮（批 291-300，10 轮，5 新修复）
- 批 291 ShareHandlerVM/Page：分享文本经 base64 → 输入框无长度限制 → 与用户消息截断合并修复
- 批 292 Favorite/History：**修复 30** 历史页滑动删除立即物理删附件 → 撤销恢复后附件永久丢失；deleteConversation 加 deleteFiles 参数，撤销窗口结束才清理文件（2b3574d）
- 批 293 ImgGenVM/TranslatorVM：**修复 31** 图片文件名含未清洗 modelName（用户自定义 provider 远端响应可含 /.. → 路径逃逸）；sanitizeModelName [A-Za-z0-9_-]+48（8425679）
- 批 294 AssistantDetailVM 头像/背景：干净（createChatFilesByContents 复制私有目录，删除安全）
- 批 295 PropertyEditor/Extensions：**修复 32** CustomBodies JSON 解析深嵌套（万层）→ parseToJsonElement SOE（Error 非 Exception）→ UI 崩溃；catch Throwable（a038e62）
- 批 296 LocalTool/Memory 页：干净（权限守卫 + 数据层记忆限制兜底）
- 批 297 Mcp/Request/Prompt 页：干净（正则 compileRegexCached + 异常兜底完善）
- 批 298 MemoryPage/Importer：**修复 33** 导入 JSON readText() 全量读 + PNG tEXt/Base64 解码无上限 → OOM；5MB 上限（2c8487c）
- 批 299 DetailPage/BackgroundPicker：干净（URL 背景安全、删除只删本地副本）
- 批 300 AssistantPage/VM：干净（删除 assistant 有确认弹窗，连带删记忆+对话有提示）
- 另：**修复 29** 用户消息 200K 截断（0e6432f，ChatService.preprocessUserInputParts 单点）——
  分享超大文本/粘贴超长 → 消息存库膨胀 + 上下文爆表 + API 413，与翻译/文档 200K 一致


## 深挖第十二轮（批 301-322，22 轮，2 新修复）
- 批 301 FloatingWindow/WebViewContentCache：**修复 34** 悬浮窗无 SYSTEM_ALERT_WINDOW 权限 → WindowManager.addView SecurityException → TTS 朗读即崩溃；Manifest 无声明且未检查 → canDrawOverlays 守卫静默降级（25bfbdc）；WebViewContentCache id=sha256 校验防路径遍历 ✔
- 批 302 ai/completion：干净（BFS 上限 500/80 + gitignore 缓存 + fuzzyScore 线性）
- 批 303 richtext Mermaid/SimpleHtmlBlock：XSS 安全确认（escapeHtml 完整转义 + Jsoup 白名单 + jsInterface 无注入点）
- 批 304 MarkdownNew/ZoomableAsyncImage：干净（LinkAnnotation 无代码执行、coil file:// 仅显示）
- 批 305 MathBlock/LatexText/DiffView：干净（jlatexmath 本地渲染无 WebView）
- 批 306 MarkdownBlock：干净（MarkdownParseCache LRU 30 有界）
- 批 307 HighlightCodeBlock/JsonTree/QRCode：干净（JsonTree 仅日志页、输入自产）
- 批 308 hooks TTS/ASR/SharedPreferences：干净（ASR 焦点拒绝=已知记录项）
- 批 309 PlayStore/EmojiBurst/DataTable：干净
- 批 310 hooks 剩余 + NavContext：干净（useDebounce 无调用方、useThrottle 调用安全）
- 批 311 UpdateChecker/StringUtils：干净（官方端点可信、escapeHtml 完整转义；模板二次替换轻微记录）
- 批 312 SoundEffectPlayer/SimpleCache：干净（pendingPlay 泄漏有限、缓存过期清理）
- 批 313 CrashHandler/PlayStoreUtil/DatabaseUtil：干净（commit 同步写崩溃标记）
- 批 314 ContextUtil/AIIconMatcher：干净（openUrl runCatching 兜底）
- 批 315 ImageUtils：干净（inSampleSize + RGB_565 采样解码）
- 批 316 TimeUtil/ChatUtil：干净
- 批 317 FilesPicker：文件附件无大小限制=用户主动选择，记录不修
- 批 318 ModelList/SearchPicker：干净
- 批 319 permission/WebViewLocalAssets：干净（assets 路径遍历拦截）
- 批 320 BitmapComposer/ShareSheet：干净（分享含 key=用户主动；decodeProviderSetting runCatching 兜底）
- 批 321 activity/theme/杂项：干净（均已覆盖）
- 批 322 TextArea 等收尾：**修复 35** TextArea 文件导入无上限 → 输入框渲染卡死 + system prompt 膨胀 413；200K 截断（b6a3599）


## 深挖第十三轮（批 323-334，12 轮，2 新修复）
- 批 323 PreferencesStore/CoroutineUtils：干净（Mutex 串行化 + dummy 防护 + halt(1) 防损坏设计）
- 批 324 data/model + db/entity：干净（纯数据类）
- 批 325 data/files/favorite/export/api：**修复 36** ExportSerializer.readUri 无大小限制 → 导入 JSON OOM；5MB 上限（1130ba8）
- 批 326 TaskValidation/TaskDao：干净（REPO_PATTERN + 条件 UPDATE 防终态覆盖）
- 批 327 DAO 批量：干净（全参数化无 SQL 注入）
- 批 328 transformers：**修复 37** 模型输出 base64 图片无限制（超大 base64 decode OOM + 超大尺寸无采样 bitmap OOM）；base64 20MB 上限 + inSampleSize 4K 采样 RGB_565（0b2fdd8）
- 批 329 PromptInjectionTransformer：干净（用户自配注入 + findSafeInsertIndex 防 USER→ASSISTANT(tools) 间隙）
- 批 330 transformers 剩余 + prompts：干净（静态 prompt、占位符替换轻微记录）
- 批 331 tools：干净（shell 超时 600s + 读文件 8MB 限量）
- 批 332 tools/local + AppDatabase：干净（迁移链最终确认 version 25 完整）
- 批 333 GenerationPrompts/WebServerManager：干净（记忆注入 60/100K 确认；restart 竞态在未调用路径记录）
- 批 334 NsdServiceRegistrar/web 剩余：干净（jmdns runCatching 兜底）


## 深挖第十四轮（批 335-345，11 轮，4 新修复）
- 批 335 ai/util：**修复 38** mergeJsonObjects/removeElements 递归无深度上限（CustomBody 请求构造 + GoogleProvider 模型响应深嵌套 → SOE 崩溃）；depth 32 防御截断（6fdb22d）
- 批 336 ai 核心：干净（InstantSerializer/TokenUsage.merge/ReasoningLevel）
- 批 337 common 模块：干净（await/SSE/AcceptLang）
- 批 338 WorkspaceFileSystem：**修复 39** delete/move 只拒绝字面量 "."，"./" 变体可解析到工作区根 → recursive 删除整个工作区（模型可控 path）；canonical 对比根目录拦截（9677cd4）
- 批 339 PersistentShellSession：干净（shellQuote 注入防护 + sentinel 超时销毁 + destroy SIGTERM→SIGKILL）
- 批 340 ProotShellRunner/RootfsPatcher：干净（会话池 LRU 有界 + 空闲才淘汰 + 每 workspace 锁 + fallback 一次性 proot）
- 批 341 WorkspaceManager：**修复 40** writeTextRootfs 无大小限制（workspace_write_file 工具模型可控 text → 超大落盘）；maxWriteBytes 2MB 上限（a4bfa04）
- 批 342 search 模块：确认（readLimitedBody 2MB 全服务在案）
- 批 343 speech TextChunker：**修复 41** 超长无标点段不硬切 → 单 chunk 超 TTS provider 上限失败/413；按 maxChunkLength 硬切（2561283）
- 批 344 TtsSynthesizer/document：干净（音频缓冲=用户配置 provider，记录不修；4 解析器已审）
- 批 345 模块覆盖确认：web-ui/material3/locale-tui/highlight 全部已覆盖（highlight 47 文件）


## 深挖第十五轮（批 346-352，7 批，2 新修复）
- 批 346 web/Entry.kt + RootfsInstaller 确认：CORS anyHost+Authorization 允许 = 本地服务设计项（token 同源保护，记录）；RootfsInstaller zip-slip 防护在案
- 批 347 gradle 配置 + baselineprofile：干净（纯构建/测试）
- 批 348 data/event + ui/activity：**修复 42** RouteActivity.ShareHandler 只 getStringExtra(EXTRA_STREAM)，系统分享（相册/文件）的 Parcelable Uri 类型不匹配 → null → 分享图片/文件丢失；兼容 Parcelable 读取（40da9cf）；AppEventBus tryEmit 丢弃设计 ✔；ShortcutHandlerActivity 字符串存储兼容 ✔
- 批 349 SafeModeActivity + McpOAuthCallbackActivity：干净
- 批 350 AppEvent + modifier：**修复 43** onClick(enabled=false) 未透传给 clickable → 禁用态按钮仍可点击（重复提交/竞态）；补 enabled 透传（ff55890）
- 批 351 ui/theme + ui/context：干净（ChatFont canonical 逃逸检查在案；15 主题纯 UI）
- 覆盖确认：399 文件全量（ui/pages 96 + components 92 + data/ai 46 + db 29 + theme 15 + hooks 14 + routes 9 + sync 8 + model 8 + repository 7 + datastore 7 + context 6 + task 5 + files 5 + activity 3 + modifier 2 + favorite 2 + export 2 + event 2 + api 2）


## 深挖第十六轮（批 353-360，8 批，收尾全仓库非 Kotlin 面）
- 批 353 AndroidManifest 全量：干净（WorkspaceDocumentsProvider 有 MANAGE_DOCUMENTS 系统级权限保护；RouteActivity SEND/PROCESS_TEXT 预期；McpOAuth deep link state 校验在案；记录：usesCleartextTraffic=true 本地服务设计 + largeHeap）
- 批 354 res/xml：file_paths 已收窄（cache 全目录 + files 仅 upload/images/tool_outputs）；记录：data_extraction_rules 默认全备份（DataStore 含 API key 会上云备份，用户可关 allowBackup）
- 批 355 .github/workflows 全量（5 个）：干净（release.yml 最小权限 contents:write + timeout 40min；记录：workflow_dispatch 默认 tag v1.1.5-turbo 陈旧）
- 批 356 gradle 全量：干净（gradle 9.5 + toolchain 21；记录：sqlite-android -SNAPSHOT 依赖 + mavenLocal + jitpack）
- 批 357 测试代码 8954 行：干净（无真实凭据泄漏，敏感词仅命中测试 fixture 假数据）
- 批 358 skills/docs/package/skills-lock：干净（computedHash 哈希锁定校验在案）
- 批 359 res 资源：干净（strings 纯文案，drawable 矢量）
- 批 360 全仓库文件清单终确认：64 java = MuPDF fitz JNI 第三方绑定（vendored）；43 tokens = highlight 测试 fixture；19 pro = proguard 规则（-dontobfuscate 已知设计 + JWT/serializable keep 合理）；web-ui 前端 113 个 ts/tsx 已全审
- **结论：全仓库代码面（Kotlin 399 + Java 64 + TS/TSX 113 + XML 38 + 构建配置 + 测试）全部深挖完毕，剩余均为记录项（设计选择/隐私选项）**


## 深挖第十七轮（批 361-366，记录项清理，4 新修复）
- **修复 44** WebDav buildUrl 路径段不编码：文件名含空格/中文/#/?/& → URL 非法 → 同步失败；逐段 percent-encode（UTF-8 + %20）（ea17fb4）
- **修复 45** TtsSynthesizer 音频流全量收集无上限：异常 provider 响应 → OOM；8MB 上限抛异常优雅失败（b57d2a5）
- **修复 46** ASR start() 静默失败：焦点被拒（其他应用占用）/ 未配置 provider key 时无任何反馈（录音无声但按钮显示激活）；写入 ASRStatus.Error + errorMessage（UI 已有 toast 机制）（bdae36e）
- **修复 47** WebServerManager.restart() 竞态：stop() 异步 → restart 立即 start() 时旧 server 未停（start 直接 return）→ 只停不启；同步停止后再启动（be3a48d，当前无调用方加固）
- 其他：release.yml dispatch 默认 tag v1.1.5-turbo 陈旧 → 空默认回退 github.ref_name（3cc2262）；模板二次替换确认无风险（Pebble 单次 evaluate）；模型列表为本地数据无加载面


## 深挖第十八轮（批 367-372，测试补强 + 边界复查）
- **修复 48**（边界补全 #42）：多图分享（相册多选）Uri 在 clipData 而非 EXTRA_STREAM → 仍丢图；兜底取 clipData 第一项（4374afa）
- 编译修复 8df2310：ASR controller smart cast（局部变量）+ WebServerManager suspend unregister 同步调用（restart 只停 server，NSD 保持）
- 回归测试 c5252a0：#38 JSON 深度防护（1000 层嵌套 removeElements/mergeCustomBody 不 SOE）
- 回归测试 cf10a2f：TaskConfig 序列化（Timer autoAi round-trip + 缺省 false + CIMonitor + 旧格式兼容）
- 定时器治理：旧版 v1.7.7 指令定时器（7cfe590d/6bce01f8）已取消，唯一活跃 c40845d0（无限巡检 auto_ai=true 新版指令）；行为准则固化进记忆（定时器触发 = 查 CI + 找活干，不许只"待命"）
- **累计修复 48 个真实 bug**；v1.7.8 候选：cf10a2f（44-47 修复 + 编译修复 + 测试 + clipData + ci tag）

## v1.7.4-turbo 发布（2026-08-04，2.5.0/179）
- 包含 3 个修复（v1.7.3 后）：04bbb45 搜索响应 2MB 限量（19 服务）/ 15e37df 翻译输入 200K 截断 / 5f53f30 GitHub API 4MB 限量
- 双绿验证：9e78b88（Unit Tests + Build APK success）

## v1.7.5-turbo 发布（2026-08-04，2.5.1/180，3d71c62 + tag）
- 包含 8 个修复：fde8a25 定时器注入重试 / 0e6432f 用户消息 200K / 2b3574d 撤销恢复附件 / 8425679 图片文件名清洗 / a038e62 JSON 深嵌套 SOE / 2c8487c 导入 5MB / 25bfbdc 悬浮窗权限 / b6a3599 TextArea 200K
- 双绿依据 669c9d1；Release run 30900213796 success（Release #25）；3 个 profileable APK

## v1.7.6-turbo 发布（2026-08-04，2.5.2/181，b491d4c + tag）
- 包含 3 个修复：1130ba8 导出导入 5MB（OOM）/ 0b2fdd8 模型 base64 图片 20MB+4K 采样 / 1ad406a Bitmap import 修复
- 双绿依据 1ad406a；Release run 30902103140 success（Release #26）；3 个 profileable APK

## 自动进度同步（判定驱动机制）

> 时间：2026-08-09 13:35 (UTC+8) | 最新提交：4785866（#52 修正：channelFlow 版 throttleLatest）
> 版本：2.5.4/183（v1.7.8-turbo，Release #28 31293664450 success）｜3 个 profileable APK 已上传
> 判定：[COMPLETE] 代码已同步、CI 全绿（4785866 Unit Tests + Build APK 双 success）、无活跃 Release
> 修复总数：52（#51 finish_reason 截断提示；#52 流式尾部丢失 throttleLatest + channelFlow 修正 + FlowThrottleTest 7 用例）
> 活跃：5332a4cd（5min×∞，auto_ai=true）
> 其他：progress_check.sh 判定修复——run 名含 SHA 前缀导致历史失败 run 永远计入判定（已修复的旧失败阻塞 INCOMPLETE），改为只检查最新提交（REMOTE_HEAD）的 run，最新提交尚无 run 时 wait；FIXES 计数 48→52；已 push

## v1.8.1-turbo 发布（2026-08-09，2.5.7/186，99b577f + tag）
- 累计修复 55 个真实 bug（a610224 #55 非流式 reasoning 三格式兼容 + 流式 onEvent 防悬挂 / 8b79263 编译修复 / 96feda5 Claude 流式防悬挂 / d2afd23 response.incomplete+failed 事件处理（DeepSeek 文档驱动，#51 截断提示在 Responses 上真正生效）/ 7e638da Google 非流式 blockReason / c5a2388 测试修复）
- 双绿依据 c5a2388（Unit Tests + Build APK 双 success）；Release run 31302055027 success；3 个 profileable APK（arm64 38.7MB / universal 48.8MB / x86_64 39.4MB）
- DeepSeek Responses API 文档核对：reasoning 明文 content 回传 ✓ / effort low/high/max ✓ / 流无 [DONE] ✓ / OpenCode Zen 端点矩阵（GPT→responses、Claude→messages、DeepSeek 等→chat/completions）

## CI 提速（2026-08-09，dba888d + 8e70885）
- ① 普通 push 只出 arm64（-PsingleAbi，splits + ndk abiFilters 双开关）；发布出全 ABI
- ② 项目级 .gradle 独立缓存（gradle-project- key + restore-keys 前缀命中）
- ③ 三 workflow JDK 17 → 21
- ⑤ gradle/actions/setup-gradle@v3 替代手写 actions/cache（依赖 + 构建缓存 + daemon）
- test.yml 加 paths-ignore .github/**；release.yml 补 Gradle 缓存
- 8e70885：ndk abiFilters 跟随 singleAbi（修复 AGP 冲突配置错误）

## Bug #56：Console Go thinking mode 工具消息缺 reasoning 回传 400（2026-08-09）
- 现象：App 报 `Error from provider (Console Go): The reasoning_text in the thinking mode must be passed back to the API`（ChatService rd5，16:06）
- 复现（curl 实测 /zen/go/v1）：chat/completions 带工具调用的 assistant 消息缺 reasoning_content → 400「reasoning_content must be passed back」（空字符串可接受）；responses 缺 reasoning item → 400「reasoning_text must be passed back」（空文本拒绝、非空即可、id 可选）；无工具消息不校验
- 根因：网关校验只认结构——思考模式下带 tool_calls 的 assistant 消息必须回传思维链；历史消息若未捕获 Reasoning part（如开启思考前产生的工具消息），App 就不回传 → 网关 400
- 修复（c0253dc）：opencode.ai host + thinking 开启时工具消息兜底——chat/completions 补 `reasoning_content:""`（buildAssistantMessageJson else-if）；responses 补占位 reasoning item（text="…"，非空必需）；useReasoningTextArray 分支思维链为空时也换占位符；新增 3 测试
- 佐证（opencode 代码阅读）：Zen 网关闭源（opencode-ai org 仅 opencode/homebrew-tap），端点矩阵见 opencode.ai/docs/zen（GPT→/zen/v1/responses、Claude→/zen/v1/messages、DeepSeek/MiniMax/GLM/Kimi→/zen/v1/chat/completions）；openai-go SDK ResponseReasoningItem 官方注释要求多轮必须把 reasoning items 放回 input；opencode CLI 本体只用 Chat Completions（openai-go SDK）
- CI 修复（0aad389）：① #56 测试缺 jsonObject import（编译错）；② Build APK 的 ndk.abiFilters 与 splits 冲突——AGP 源码 ApplicationVariantFactory.checkSplitsConflicts 确认：isUniversalApk=true（发布）跳过检查（v1.8.1 Release 成功之谜），isUniversalApk=false（-PsingleAbi）时 abiFilters 非空必报错（与 splits 一致也没用）→ singleAbi 场景移除 abiFilters
- 状态：0aad389 CI 验证中

## v1.8.2-turbo 发布（2026-08-09，2.5.8/187，b03ea20 + tag）
- 包含 #56 全链路：c0253dc（Console Go thinking 工具消息 reasoning 兜底：chat/completions 空串 + responses 占位 item + 3 测试）+ 0aad389（AGP checkSplitsConflicts 源码级修复：singleAbi 移除 ndk.abiFilters）+ 27fc45a（反射测试补参 + jsonObject import）
- 27fc45a 双绿（Unit Tests + Build APK success）；Release run 31304788189 success（08:58→09:09）；3 个 profileable APK（arm64 36MB / universal 46MB / x86_64 37MB）

## #57 全链：Responses API 修复 + 增量发送（2026-08-09 ~ 08-10，v1.8.3 ~ v1.8.8）

### v1.8.3-turbo（2.5.9/188）：URL 拼接去重 + 400 诊断（8-09）
- 新增 buildEndpoint(baseUrl, path)：base_url 已以 path 结尾时不重复拼接——修 `/responses/responses` 404（用户把完整端点填进 base_url 实测触发）
- 覆盖 7 处：ResponseAPI ×2 / ChatCompletionsAPI ×2 / OpenAIProvider models·embeddings·images·balance；+ BuildEndpointTest 7 用例
- 400 诊断日志：generateText/onFailure 记录响应体 + input 形状（先用 Log.w——教训：只进 logcat 远程不可见；后改 Logging.log 进 App 内存日志）

### v1.8.4-turbo（2.5.10/189）：诊断升级结构摘要（8-09）
- summarizeInput：逐 item 输出 type/role/id/name/call_id/长度/开头，不受 4000 字符截断影响（rd5 400 复现的破案关键）
- CI 教训：JsonElement import 缺失（compileDebugKotlin 失败）

### v1.8.5-turbo（2.5.11/190）：400 根因修复（8-09）
- rd5 400 根因：网关按 function_call 逐个校验 reasoning——1 个 reasoning 只服务其后的第 1 个 fc；同消息第 2 个 fc 起前面无 reasoning → 400「reasoning_text must be passed back」（继续生成请求 fco 结尾时校验；user 结尾不校验）
- 实测矩阵：1r+1fc 200 / 1r+2fc 400 / 每 fc 前补占位 200 / assistant content 不能替代 / reasoning 补在 fc 之后无效
- 修复：addAssistantItems 引入 fcSinceReasoning 计数器，Tools group 每个 fc 输出前检查补占位 + 跨 group 覆盖；+ 多工具 7 项序列断言测试
- 借鉴 openai/codex prepare_response_items_for_request：reasoning 回传移除服务端 id（af1d11d）
- 发布链实战坑 4 连：annotated tag push 时 context.sha 是 tag 对象 SHA（用 commits API resolve ref）→ inputs.tag 读取（context.payload.inputs）→ download-artifact@v4 跨 workflow run 不兼容（REST+fetch 401 → octokit downloadArtifact 端点）

### v1.8.6-turbo（2.5.12/191）：审查批次（8-09）
- Claude/Google provider URL 拼接统一 buildEndpoint（/messages/messages 双拼）
- GoogleProvider：inlineData 拼完整 data URI（纯 base64 渲染失败 bug）+ video/audio 支持 + 缺 content/未知 mime 不崩溃 + functionCall 防御
- MCP 服务器名校验放行 - 和 _（OpenAI/Anthropic 工具名规范 ^[a-zA-Z0-9_-]+$，ChatService + SettingMcpPage UI 两处对齐）

### v1.8.7-turbo（2.5.13/192）：增量发送核心（8-10）
- JsonTree 递归渲染深度上限（MAX_JSON_TREE_DEPTH=12，深嵌套 AI 输出 SOE 崩溃防护）
- SAF DocumentsProvider documentId root 段合法性校验（恶意 App 构造 ws/.. 越权加固）
- **增量发送（ae423f8 + eb4dd4c + 4263fa1）**：previous_response_id + store，借鉴 openai/codex get_incremental_items
  - IncrementalSessions：input 前缀逐项比较 + 请求属性签名一致（model/instructions/tools/reasoning/store/stream 等，对齐 responses_request_properties_match）+ 发送前 stripItemIds（保留 call_id）+ 400 时 invalidate 会话
  - 实测：纯文本增量 200（服务端记住 1+1=? 只发"那3+3呢"正确答 6）；全量请求也 store=true（增量链前提）
  - 解决 rd5「下一步思考前卡顿」：历史思维链全量重传（input 几百 KB）→ 只发新增

### v1.8.8-turbo（2.5.14/193）：工具轮次准增量（8-10）
- 新实测：纯 fco 增量 400「No tool call found」（网关不关联之前的 fc）；增量重发 fc+fco 200（当新输入处理）——单 fc 直接 OK，双 fc 必须每个 fc 前补占位 reasoning（400 reasoning_text，规则与全量一致）
- needRebuildFcs = delta 新 fc + sentInput 中 delta 有对应 fco 的历史 fc（distinctBy call_id）；剩余跳过 fc/fco 防重复；纯消息轮次直接增量
- 工具重建逻辑 3 轮打磨（fco 重复 → responseItems 顺序 vs input 交错 → 精准规则），测试 10 用例全绿
- 对照结论：openai/codex 有 WebSocket 增量；sst/opencode 客户端无增量（全量，AI SDK 层）；我们是三家唯一做 HTTP previous_response_id 增量 + 工具准增量的客户端

### 发布历史（#57 系列）
- v1.8.3-turbo：bc659e8 → 84232a0 → 4089c74(失败修复) → 9554777 → 1cd4b7f（Release 31308706242）
- v1.8.4-turbo：d5516aa → 4089c74 诊断结构摘要 → 9554777 import 修复 → 1cd4b7f
- v1.8.5-turbo：2b52342 → af1d11d → 720b0c6（Release 31311484120，octokit 下载打通）
- v1.8.6-turbo：7ce8dfe → 64c7f75 → a38003e → 9f05b6e（Release 31316201652）
- v1.8.7-turbo：f39035c → 2dbcc03（Release 31343624630）
- v1.8.8-turbo：5a1408a → d4fdf24 → f068695 → f646d89 → 2a1d115 → fd5f149（Release 31347453777）
- 教训：kotlinx 测试三大坑（parse jsonObject/jsonArray、import 易漏、签名变更全量替换）；工具重建 4 轮 CI 打磨；定时器 08-10 早 6 小时停止导致 CI 失败堆积

### 当前状态（2026-08-10 09:47）
- FIXES 计数 67（#57 系列 11 个真实修复）
- 判定：[COMPLETE] 代码同步、CI 全绿、无活跃构建
- 待用户验证：v1.8.8 装后 rd5 全场景（含工具轮次）增量效果

## v1.8.9 回退 + v1.9.0 审查修复（2026-08-10，52f56fd → v1.9.0-turbo）
### v1.8.9-turbo（f135af31，2.5.13/194）：回退 v1.8.8 工具轮次准增量
- 原因：v1.8.8 工具轮次增量在真实使用中出问题（用户实测回退），IncrementalSessions 恢复 1.8.7 版
- 回退范围：仅 IncrementalSessions.kt/Test/build.gradle（保留纯文本增量 + fc 禁用增量）

### v1.8.7→v1.8.8 代码审查发现 11 个 bug（52f56fd 全部修复）
- **P0-1 工具轮次上下文重复**：网关"消息追加"语义下，增量重发 fc+fco 导致服务端上下文 fc×2
  （上次 output 一次 + 本次 input 一次）+ 占位 reasoning "…" 累积；多轮工具循环后上下文膨胀/模型困惑。
  修复：delta 含 fc/fco 一律回退全量，删除 needRebuildFcs 重建逻辑与占位累积（F1/F6/F7）
- **P0-2 增量失败无回退**：非流式 400 → invalidate + throw（用户直接报错），流式同。
  修复：失败自动重试全量一次（IncrementalFailedException 信号 + streamOnce 拆分）（F2）
- **P0-3 非 opencode.ai 也走增量**：store=false 的 host（OpenAI 官方/DeepSeek 直连）服务端不保存
  response，previous_response_id 必 400。修复：applyIncremental 检查 store==true（F3）
- **P1-4 跨 host 串用会话**：bucketKey 仅首条消息 hash + signature 无 host，切换 host 同签名命中
  → previous_response_id 发错网关。修复：bucketKey 加 host 前缀（F4）
- **P1-5 流式 [DONE] 不记录会话**：某些网关 [DONE] 结束不发 response.completed → 永不增量。
  修复：response.created 捕获 id，[DONE] 时也记录（F5）
- **P2-9 update 死参数**：v1.8.8 删 responseItems 存储但参数未删。修复：恢复存储用于回显过滤（F8）
- **P2-10 400 诊断盲区**：非流式 Logging 硬编码 contains("reasoning")，previous_response_id 类
  400 无日志。修复：400 全部记日志（F9）
- **P2-11 sessions 超限全清**：多对话互相踢出。修复：LRU 淘汰最旧（仅新会话触发）（F10）
- **F11 纯文本回显 a1 重复**：App 把上次回复回显进 input，服务端已保存 output → 增量重发重复。
  修复：delta 过滤与上次 output 匹配的 assistant 回显（role+文本序列比较，兼容结构差异）
- 测试：11 用例（工具回退×2/回显过滤×3/host 隔离/LRU/原 7 用例适配），CI 双绿 52f56fd
- 发布：v1.9.0-turbo（2.5.15/195），tag 52f56fd

### 当前状态（2026-08-10 11:5x）
- FIXES 计数 78（+11：52f56fd 审查修复）
- 判定：发布链进行中（v1.9.0-turbo tag 已推，Release 运行中）

## v1.9.1 + v1.9.2：深挖修复与 opencode 网关实测（2026-08-10，4edd2c1 → 2b718e08）
### v1.9.1-turbo（4edd2c1，2.5.16/196）：深挖 3 项（未发布——CI 测试失败被 v1.9.2 覆盖）
- F16 思维链回显未过滤：reasoning item 与 message 一样回显进 input，但过滤只匹配 message
  → 思考模式下增量照样重发完整思维链。修复：isEchoOf 按 type 分发（message/reasoning 都过滤）
- F12 非流式重试连接泄漏：增量失败重试前失败 response 未消费 body 未 close → OkHttp 连接泄漏
- F13 流式 onFailure 诊断缺失：增量 400 分支未读 body → 失败原因丢失
- ⚠️ 此提交 CI Unit Tests 失败（isEchoOf 方向 bug，6 用例挂 delta.size）→ 未发布

### v1.9.2-turbo（2b718e08，2.5.17/197，Release 31360288191）：修复 + opencode 网关实测结论
- **F17 修复 isEchoOf 方向**：known.isEchoOf(item) 参数语义错误（isEchoOf 按参数 type 分发，
  传 App 回显无 type → 永远 false → 过滤全失效）。改为 item.isEchoOf(known)。6 测试恢复
- **F18 增量全禁用（重大实测结论）**：opencode.ai /zen/v1 与 /zen/go/v1（deepseek-v4-flash/
  flash-free 实测）**静默丢弃 previous_response_id**：请求 200 但服务端不关联上下文——
  强测试（设秘密词→增量问）模型失忆；此前「答对 6」是常识弱测试误判（3+3=6 无需上下文）。
  全量回显对照实验（数组/字符串）→ 正确记住。增量白名单置空 = 全禁用
  （返回 200 无法用 400 兜底 → 不禁用则每轮对话失忆）
- F19 claude/gemini 前缀防御：未来白名单启用也先禁（anthropic/google 转换路径 prev id 同样
  被丢 + fromOpenaiRequest 的 fc 取 .id 非 call_id）
- F20 completed 事件 output 兜底：转换路径流式无 output_item.done，completed 带完整 output
  数组时用作 responseItems

### opencode 网关源码深挖（packages/console/app/src/routes/zen/）
- **架构**：/zen/v1/responses（full）与 /zen/go/v1/responses（lite）→ handler.ts →
  createBodyConverter(from, to)：from===to 原样透传；异格式转 CommonRequest（白名单无
  previous_response_id/store → 丢弃）
- **Bug 清单**：① prev id 静默丢弃（实测）② fromOpenaiRequest 的 fc 取 .id 非 call_id
  （转换路径工具断裂）③ toOpenaiRequest 硬编码 reasoning.effort=medium ④ toOpenaiChunk
  只发 4 种事件（无 output_item.done/response.created）⑤ /zen/v1（Console）assistant
  content 数组回显 400（go 端点无此问题）⑥ 流式结束追加 event:ping cost chunk（App 忽略）
  ⑦ 错误类：Auth/Credits/Model=401、RateLimit=429（带 retry-after，App 未处理）
- **用户实际端点确认**：rd5 = https://opencode.ai/zen/go/v1（go/lite 端点，deepseek 系）
- 透传路径（deepseek/gpt，go 端点）：响应 id 为 UUID；kimi/glm 返回 chatcmpl-（oa-compat）

### 当前状态（2026-08-10 13:5x）
- FIXES 计数 80（+2：4edd2c1 深挖 3 项计 3、2b718e08 计 4 但 F17 为回归修复）——实际增量
  #57 结论：**previous_response_id 增量在 opencode.ai 不可用，全量发送为唯一正确路径**
- 判定：[COMPLETE] 代码同步、CI 全绿（2b718e08 双绿 + Release success）、v1.9.2 已发布
- 待用户验证：v1.9.2 装后 rd5 全场景（全量发送路径，预期回到 v1.8.9 稳定行为）
- 已记录取舍：思维链瘦身优化（截断历史 reasoning）用户明确不做

---

### 大文件拆分专项（2026-08-12，Strangler Fig 第 6-38 步，用户要求"拆分剩下所有能够拆分的"）

**目标**：>800 行文件全拆 + 尽可能拆到 <600 行。已拆分 33 个文件（按 commit 序）：

| 文件 | 原行数 | 现行数 | 拆出 |
|---|---|---|---|
| ChatService.kt | 1291 | 746 | ChatErrors/ChatSessionManager/ChatToolBuilder/ChatMessageOps |
| BackgroundTaskManager.kt | 1069 | 703 | CiTaskPoller/TimerTaskPoller |
| PreferencesStore.kt | 801 | 519 | SettingsModels |
| GoogleProvider.kt | 820 | 332 | GoogleRequestBodyBuilder/GoogleResponseParser |
| TTSProviderConfigure.kt | 1203 | 166 | TTSOpenAICompatConfigs/TTSChinaConfigs/TTSOtherConfigs |
| PromptPage.kt | 1194 | 166 | ModeInjection/Lorebook/RegexInjection |
| SettingMcpPage.kt | 1106 | 441 | McpCommonOptionsConfigure/McpToolsUI/McpServerDialogs |
| SettingSearchDetailPage.kt | 995 | 349 | SearchServiceOptionsConfigs |
| MarkdownNew.kt | 970 | 163 | MarkdownHtmlElements/MarkdownStyleParser |
| SettingSpeechPage.kt | 964 | 300 | SpeechTTSDomain/SpeechASRDomain |
| ChatDrawer.kt | 930 | 675 | ChatDrawerSections |
| WorkspaceDetailPage.kt | 913 | 322 | WorkspaceBasicSection/WorkspaceFilesSection |
| SettingTasksPage.kt | 889 | 639 | TaskCardUI |
| ChatList.kt | 865 | 545 | ChatListPreview |
| ModelList.kt | 838 | 664 | ModelListItems |
| ChatPage.kt | 833 | 508 | ChatFilesPickerSheet/ChatTopBar |
| BuiltinToolUIs.kt | 827 | 580 | BuiltinToolUIsScreenTime/BuiltinToolUIsCalendar |
| Markdown.kt | 801 | 457 | MarkdownNodeRenderer（v1.9.10 后二次拆分 1162→457） |
| ChatInput.kt | 787 | 417 | ChatInputComponents |
| ResponseAPI.kt | 783 | 465 | ResponseBodyBuilder（v1.9.10 后二次拆分 1411→465） |
| GenerationHandler.kt | 776 | 648 | GenerationToolExecutor |
| Export.kt | 780 | 239 | ExportMarkdown/ExportImage |
| ImgGenPage.kt | 754 | 235 | ImgGenScreenSection/ImgGenGallerySection |
| AssistantPromptPage.kt | 754 | 134 | AssistantPromptContent/AssistantRegexCard |
| RouteActivity.kt | 744 | 591 | ScreenRoutes |
| SettingThemePage.kt | 725 | 308 | ThemeCustomItems/ThemeColorTools |
| ChatMessage.kt | 707 | 289 | MessagePartsBlock/CollapsedMessagePreview |
| SimpleHtmlBlock.kt | 688 | 312 | SimpleHtmlTextBuilder/SimpleHtmlExtras |
| ClaudeProvider.kt | 647 | 272 | ClaudeRequestBodyBuilder/ClaudeResponseParser |
| ChatboxImporter.kt | 626 | 597 | ChatboxImportModels |
| ConversationRepository.kt | 613 | 599 | ConversationRepositoryModels |

**跳过（纯数据/注册表，拆分无价值）**：GoogleSans.kt 883（31 个 FontFamily val）、ModelRegistry.kt 677（模型/系列定义）。

**已修复 CI 问题（run 357-375 失败链）**：
- cb40da39: GoogleRequestBodyBuilder 缺 jsonObject import
- 2b1fcdbd: ModelAbility import 路径 / processingStatus 需 MutableStateFlow /
  DEFAULT_TTS_PROVIDERS internal / TaskKeepAliveService+isActive import /
  deleteTask 用 getById+delete / onCompleteTask 命名参数改位置（9 处）
- 63391cba: ResponseAPI import 区损坏（'import import X' 残行拼接，11 处）/
  MessageExtensions CONTEXT_KEEP_RATIO 重复声明
- df65aab4: Message.kt 悬空 KDoc 未闭合（切掉 */ → MessageChunk 被吞进注释，
  全模块 151 个 Unresolved 级联）

**流程要点**：
- 提取脚本：先剥字符串（含单引号 Char 字面量）再剥注释，URL 里的 // 是坑；表达式体函数按范围切分
- Python del 左闭右开易漏删函数结束 }；多次删除从后往前，索引基于删除前文件
- private → internal 跨文件；@OptIn/@Composable 注解随函数迁移；常量（含 internal）随域搬迁
- 已回写 SKILL：拆分流程 + 16 坑清单 + 新增提取脚本教训

### CI 状态（2026-08-12 15:2x）
- **Unit Tests run 387 SUCCESS**（2bfc05a2）：ai 模块编译 + app 模块编译 + 221 测试全过
- Build APK run 402 进行中（打包验证）
- 修复链（run 357→387）：10 轮修复（编译错误→测试适配→app 模块首编），
  详见上方"已修复 CI 问题"列表 + 各 fix commit

### 发布 v1.9.11-turbo（2026-08-12）
- 内容：大文件拆分专项（33 文件）+ CI 修复链
- turbo Release ID 369063434（07:34 UTC，3 个 APK）✅
- pro：构建中 → 待 tag 发布

### 极致优化专项（2026-08-12 15:5x）—— 死代码清理 + 大函数拆分
**提交**（4 commit，-1200+ 行）：
- dfd819cd: 死代码第一轮（FilesManager/SkillManager/Assistant/Favorite，-100 行）
- 53660595: 死代码第二轮（utils/service/sync/UI，-663 行，删 3 文件）
- 873282d8: ChatDrawerContent 577→220 行（拆 8 域 Composable，ChatDrawerSections.kt）
- 48baaffe: SettingTasksPage 551→160 行（拆 TaskSettings/CIMonitor/Timer 对话框，SettingTasksSections.kt）

**删除清单**（grep 全仓库零引用验证）：
- ChatboxImporter.importConversations + sessionObjects（eb1f491b 流式改造遗留）
- FilesManager: saveManagedFromUri/saveManagedText/saveUploadFromUri/saveUploadText/listImageFiles
- SkillManager: readSkillBody/readSkillContent
- Assistant.getTriggeredInjections / Favorite.fromValue / WorkspaceEntity.toWorkspace
- ConversationSession.withRef/withRefSuspend（无调用者）
- S3Client: getObject/getObjectStream/headObject/objectExists/getPublicUrl + S3ObjectMetadata
- S3Config.bucketUrl / WebDavClient: get/getStream/head/mkcol
- WorkspaceDetailVM: 终端 3 方法 + WorkspaceTerminalState/Entry（终端由 WorkspaceTerminalSession 驱动）
- PermissionState: requestPermission/getPermissionResults
- WebView: 包装 clearHistory() + WebContent.Url.clearHistory 参数
- CustomThemeButton.kt（整个文件）/ Lifecycle.kt（整个文件）/ CollectionUtils.kt（整个文件）
- utils: checkDifferent/getComponentActivity/unescapeHtml/codeToEmoji/getAllEmojiVariants/getImageInfo
- hooks: useDebounce / rememberAppLifecycleState / SharedPreferences.writeBooleanPreference
- 孤儿 import 84 个（ChatDrawer 47 + SettingTasksPage 37）

**保留观察**：SearchServiceOptionsConfigs.kt 715 行（17 个独立小组件集合文件）；
@Preview 函数（IDE 辅助）；图谱 dead-code 误报多（CALLS 边缺失 + 旧节点残留），
清理死代码以 grep 交叉验证为准。

**教训**：workspace_edit_file 的 old_text 锚点必须唯一（Assistant.kt 两处
data class Assistant 差点匹配错位）；弹窗提取时 if 条件要保留在调用处；
状态机括号检查（/tmp/bracecheck.py）比正则剥离可靠（模板字符串/KDoc 花括号误报）。

### ChatService 再拆分 + 补测试（2026-08-12 16:5x，用户要求"做到完美不计成本"）
**ChatService 746→370 行**（13ec0c92）：
- ChatGenerationCore.kt（新，~260 行）：handleMessageComplete/checkInvalidMessages/
  cancelToolByUser/finishInterruptedPendingTools + transformers 常量 + WorkspaceReminderTransformer
- ChatMessageActions.kt（新，~160 行）：sendMessage/regenerateAtMessage/handleToolApproval/stopGeneration
- ChatService 保留：构造+域组装+门面转发+saveConversation（~370 行）
- 公开 API 零变化；错误上报/持久化/标题生成用回调注入避免循环依赖
**补测试 23 用例**（177e1ba2）：
- ChatboxSessionParseTest 12 用例：parseSession（system 合并/image 计数/空消息计数/tool 解析/
  content 兜底/时间戳/标题回退/无 id）+ importProviders（provider 提取/能力映射/apiKey 过滤）
- RepoListParserTest 7 用例：parseRepoList（逗号/换行/混合/去空白/去重/空输入）
- SettingsTasksSerializationTest 4 用例：task 字段默认值 + JSON 往返 + POLL_INTERVAL_OPTIONS
- parseSession/importProviders private→internal 供同包测试
**CI 修复链**（run 392/394→395）：
- run 392：ChatGenerationCore 误 import model.toText（实为 UIMessage 成员方法）+
  ChatService 重写丢 R import
- run 394：SettingsTasksSerializationTest 缺 POLL_INTERVAL_OPTIONS import（跨包 internal 需显式 import）

### 深度审查修复专项（2026-08-12 19:0x，用户要求"全部修，不计时间 token"）
**审查产出**：深度阅读核心链路（生成/流式/网络/存储/任务/UI），分级：
- 🔴 C1 SSE 静默失败（4 端）→ ✅ 已修
- 🟠 P1 流式每帧全量视觉转换 O(N) → ✅ 已修
- 🟠 P2 翻译流式 O(n²) → ✅ 已修
- 🟡 B1 自动监控重复创建 → ✅ 已修
- ⚪ M2 togglePinStatus 全量加载 → ✅ 已修
- ⚪ M3 ChatboxImporter 测试缺口 → ✅ 已补
**6 commit（27783d2a..110da410）**：
- a79f1393 fix: SSE onFailure 空 body 静默成功兜底（ChatCompletions/Claude/Response/Google 四端）
  `close(exception ?: Exception("Stream failed: HTTP code"))`——HTTP 错误时 okhttp-sse 传 t=null、
  网关空 body → 原 close(null) 正常结束流用户无感知失败
- 11046df2 perf(streaming): ① onUpdateMessages 每帧 no-op transforms 删除（output transformers
  均未实现 transform）+ visualTransform 只作用于最后一条（流式期间前面消息引用不变、视觉转换
  逐条纯函数幂等）→ 长对话每帧 O(N)→O(1)；**关键坑：外层 messages 同步靠 onUpdateMessages
  每帧回写，必须保留 messages=it（直接删 transforms 会断同步）** ② translateText 流式 O(n²) 缓解
  （text-only StringBuilder 累积器 ~64ms 快照，fallback 路径语义不变，消费方全量覆盖模式）
- 88bb939e fix: autoCreateCIMonitorByWebhook 四维查重（requested/queued/in_progress 三事件
  原会创建 3 个重复任务；注释声称 createCIMonitorTask 去重但实际直接 insert）
- eb1b40f1 test: StreamingVisualTransformTest（P1 守护：两帧间 last-only==full，ThinkTag/Regex）
- 799e6c7d perf: togglePinStatus 单列查询 getPinStatus（原全量加载 nodes 只为读 isPinned）
- 110da410 test: ChatboxImporterTest（importProviders 7 用例：映射/缺省/空 key/混合）
**豁免**：C1/P2/B1 依赖 okhttp-sse 回调/flow/Room DAO，JVM 单测无 mockk/Robolectric 基建；
C1 已与 GoogleProvider 既有防御对齐人工验证。
**测试技巧**：visualTransform 不读 context → `null as Context` 传参 JVM 单测可用
（ThinkTag 只看消息、Regex 只看 assistant）；TransformerContext 全字段默认构造可行。

### 工作模式命令（2026-08-12 19:2x，用户明确"写入底层"）
**"不要闲着，在轮询 CI 过程中持续深度优化代码"**——已写入三层：
① 记忆（memory_tool id=1）② /skills/code-optimization/SKILL.md 阶段五 ③ 本文件。
流程：push 后建 CI monitor → 等待期间继续深读/优化其他代码 → 一轮 CI 结束看结果
→ 仍在跑就继续优化 → 失败立即修复重推再继续优化。默认工作模式，自动遵守。

### 深度审查专项 CI 修复链（2026-08-12 19:1x-19:3x）
- run 398（edcde08a 前）: compileDebugKotlin 失败——P2 的 flushTranslationText 局部函数
  未声明 suspend（内部调 emit）→ edcde08a 修复（1 行）
- run 400（ChatboxImporterTest 首版）: compileDebugUnitTestKotlin 失败——putJsonObject(key,
  JsonObject)/putJsonArray/JsonArrayBuilder.put 重载不存在 → 3274416a 改 JsonObject(buildMap)+成员 put
- run 401: buildMap 的 receiver 是 MutableMap<String, JsonElement>，put("modelId", String)
  需 JsonPrimitive 包装 → aa7547db 修复
- 经验沉淀：① 测试里构造 JsonObject 用 JsonObject(buildMap{}) + put(key, JsonElement) 最稳；
  buildJsonObject 的 String put 有重载（JsonObjectBuilder）但 buildMap 没有 ② putJsonArray/putJsonObject
  的 builder 重载易与 (key, JsonObject) 混淆，避免 ③ 局部函数调 emit 必须 suspend
- 47043c61: 清理 GenerationToolExecutor 孤儿 import（visualTransforms，拆分遗留）
- 工作模式命令已写入底层（SKILL.md 阶段五第 3 条 + 手册 §5 + 本文件 + 记忆）：轮询期间持续优化

### v1.9.14-turbo 发布成功（2026-08-12 13:28 UTC，Release ID 369260348）
**3 APK**：arm64 37.0MB / universal 46.6MB / x86_64 37.6MB。分支 CI 全绿（197 测试）。
**含**：C1 SSE 静默失败兜底 + P1 流式每帧 O(N)→O(1) + P2 翻译 O(n²) 缓解 + B1 自动监控查重
+ M2 单列查询 + M3 补测 + TransformerContext 可空化 + WebDAV/S3 备份一致性 + Google listModels 防御。
**发布流程验证**：tag v1.9.14-turbo push → release.yml（tags v*-turbo）自动触发 → 复用分支
Build APK 成功 run 的 artifact → softprops 发布 → API 验证 3 assets。全自动零手工。
**设备中断对话根因**：旧 APK 的 TaskNotificationManager 注入 sendMessage 无条件 cancel 生成
Job（otb）→ 更新 v1.9.14 后解决（awaitGenerationIdle 保护）。

### 拆分专项（2026-08-12 14:0x，用户"1 2 3 都做了"）
- ① GenerationHandler 拆分（a68d6e5c）：translateText 移出至新 TranslationHandler（712→579+169），
  构造注入 providerManager，门面转发调用方零改动；清理 7 个仅翻译用 import + 常量随迁
- ② web-ui conversations.tsx 拆分（45bfa595）：11 纯函数+编辑类型/常量 → lib/conversation-editing.ts
  （1228→1002）；**坑：python 原样复制丢失 export 前缀 → run 407 rollup not exported → 838e6f0c 补 export**；
  顺带删 EMPTY_* 死常量
- ③ web-ui chat-input.tsx 拆分（822cb80f）：4 纯函数 → lib/chat-input-parts.ts（815→747，手写带 export 无坑）
- ④ **ScreenRoutes.kt 删除事故（063302ab→6f5255bb revert）**：grep 文件名零引用误判死文件，
  但文件内 Screen sealed interface（49 成员）被 RouteActivity 导航 + ErrorCard 引用。
  **教训：删文件前必须检查文件内每个顶层类型名的引用（文件名≠类型名）；Kotlin 文件名无需匹配类型名**
- ⑤ ErrorParser when 穷尽 else 清理（e0041d8d）：JsonElement sealed 穷尽，else 仅兜底 JsonNull →
  显式 JsonNull 分支（编译 warning 消除）
- 保留：conversation-sidebar.tsx 1044（巨型 JSX 组件）、RouteActivity 590（Activity 内聚）、
  BuiltinToolUIs 579（组件集合）——提取子组件需重构 props 传递，收益/风险不成比例

### 拆分专项 2（2026-08-12 15:0x，用户"继续拆分吧"）
**8 个大文件拆分**（b23b2122..70c670b5）：
- conversation-sidebar.tsx 1044→710（ConversationListRow 独立，b23b2122）
- ChatDrawerSections.kt 773→242（7 对话框/Sheet → ChatDrawerDialogs.kt，23df9fe4）
- SearchServiceOptionsConfigs.kt 715→352（8 服务 → SearchExtraServiceOptions.kt，4c87196c）
- BuiltinToolUIs.kt 579→324（网页/搜索域 → BuiltinToolUIWeb.kt，ad1f056b）
- SettingProviderDetailPage.kt 570→258（模型列表域 → SettingProviderModelList.kt，4905894a）
- TTSChinaConfigs.kt 600→392（StepTTS → TTSChinaStepConfig.kt，7ec4b8bd）
- code-block.tsx 718→501（shiki 纯逻辑 → lib/code-highlight.ts，90c1194c + import 清理 70c670b5）
**CI 修复链**：
- run 426：conversation-list-row.tsx **重复 interface 声明**（生成时 header 手写 export interface
  + 原 block 内声明重复 → esbuild Unexpected {）→ 4367bcab 合并单一声明
- 教训：python 生成文件的 header 与 block 边界不得重叠（①丢失 export 前缀②重复声明）
  → 生成后自查：重复声明检测 + 括号平衡 + 主文件残留引用检查
**保留理由**：ModelList（拖拽状态机）、BackgroundTaskManager（复杂状态）、GoogleSans（嵌套 object 树）、
RouteActivity（Activity 内聚）、AssistantBasicPage（线性表单）、ConversationRepository（核心数据层）、
tool-part/extension-picker/chat-input（巨型 JSX 组件，子组件提取需重构 props 传递）

### 拆分专项 2 CI 修复链闭环（run 407→435 全绿）
- run 407 丢 export → 838e6f0c；run 426 重复 interface → 4367bcab
- run 430 JSX 进 .ts → 7b0a2917；run 431 丢 export → df273b59
- run 432 KDoc 截断 → 62a11a1e；run 433 孤儿注解 → 81ebf981
- run 434 @Composable 缺失×4 + private 跨文件 → 330f628c → run 435 ✅ 全绿
**提取块标准（升级 7 项自查）**：
① 注释平衡（/** : */）② 尾部孤儿注解/@Composable ③ 括号平衡（剥离模板字符串）
④ export 前缀（python 提取必丢）⑤ 重复声明（header 与 block 边界不得重叠）
⑥ @Composable 注解完整性（提取块必须含函数上一行的注解！从函数名开始会丢）
⑦ private 顶层跨文件可见性（提取后必须 internal）

### v1.9.15-turbo 发布成功（2026-08-12 16:06 UTC，Release ID 369369055）
**3 APK**：arm64 37.0MB / universal 46.6MB / x86_64 37.6MB。
**含**：拆分专项 1+2 全部 18 个拆分 commit + 修复链（run 407→435 全绿）。
**发布坑（已入记忆）**：tag 必须打在最后一个有成功 Build APK 的**代码 commit** 上，
不能打 docs-only commit（release.yml 等不到匹配 Build APK run→4min 超时失败）。
v1.9.15 首打 a012b552（docs）失败 → 删 tag 重指 330f628c（最后代码 commit）→ 成功。
**发布后状态**：分支 CI 全绿（Unit Tests 197 + Build APK），无活跃后台任务，轮询已关闭。
**版本线**：v1.9.12（死代码清理）→ v1.9.13（ChatService 拆分+23 测试）→ v1.9.14
（深度审查 12 修复）→ v1.9.15（18 文件拆分）。剩余大文件均有意保留（理由见上）。

### v1.9.16-turbo 发布成功（2026-08-13 00:00 UTC，Release ID 369592004）
**3 APK**：arm64 37.0MB / universal 46.6MB / x86_64 37.6MB（大小与 v1.9.15 持平，重构无膨胀）。
**含 3 commit（71f78ef2..c1b3f1ca）**：
1. 71f78ef2 日志页实时刷新——Logging 监听器（CopyOnWriteArrayList，锁外通知）+ LogPage DisposableEffect 订阅
2. 5689b3ab ErrorParserTest 10 用例（测试缺口闭环：递归/数组/primitive/JsonNull/深度上限/兜底）
3. c1b3f1ca 编译修复（message 可空断言 !!）
**本轮探索确认健康域**：ChatCompletionsAPI 全链路、TranslationHandler、GenerationHandler 主链路、
BackgroundTaskManager、TaskNotificationManager（awaitGenerationIdle）、ConversationRepository、
web-ui SSE 客户端、zustand selector、零组合期 IO、零空 catch、无 TODO 残留、架构 14 社区 0 警告。
**版本线**：v1.9.12（死代码）→ v1.9.13（ChatService 拆分+测试）→ v1.9.14（深度审查 12 修复）→
v1.9.15（18 文件拆分）→ v1.9.16（日志实时刷新 + 测试加固）。CI 全绿（207 测试）。
