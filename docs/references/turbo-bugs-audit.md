# RikkaHub Turbo bug 清单核查表

> 来源：`dcs666/rikkahub-turbo` 分支 `perf/rendering-and-streaming` 的 `PROGRESS.md`
> （存档于本目录 `turbo-PROGRESS.md`，742 行，120+ 轮深度审查，累计修复 48+ 真实 bug）。
> 用途：**逐条对照我们 fork（app.androidrik.rikkahub）核查是否存在同款 bug**。
> 状态列：⬜ 未核查 / ✅ 已修 / ⚠️ 确认同款待修 / 🔍 待查（未定性）

---

## 一、崩溃类（Crash / ANR / OOM / SOE）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| C1 | 设置保存崩溃：删光搜索服务后 `coerceIn(0, size-1)` = `coerceIn(0,-1)` → IllegalArgumentException | 5be15b3 | ⚠️ 待修 | 我们 `PreferencesStore.kt:387` 同款，无空列表守卫 |
| C2 | 损坏 base64 图片 decode null → NPE 崩溃（应降级保留原 part） | 1588d97 | 🔍 待查 | `StringUtils.kt:24` Base64.decode 无容错 |
| C3 | 技能 zip bomb：readBytes 无上限 → OOM | f2a84d9 | ✅ 已修 | SkillsVM 单文件 32MB/总量 256MB（已移植） |
| C4 | BitmapComposer 取消挂起 + view 泄漏 | 48dd5c3 | 🔍 待查 | 导出取消场景 |
| C5 | JsonExpression 求值器 SOE：余额表达式超长/深嵌套（粘贴触发） | 66483bf | 🔍 待查 | 需 512 字符上限 + SOE 兜底 |
| C6 | parseErrorDetail 递归 SOE：恶意/异常 API 深嵌套错误 JSON | 7c50c22 | 🔍 待查 | 需 depth 32 上限 |
| C7 | collectAllParts 嵌套 SOE：工具结果递归展开无上限 | 198ec66 | 🔍 待查 | 需 depth 16 |
| C8 | highlight 引擎崩溃：MAX_ITERATIONS 守卫触发无降级 | ad7da54 | 🔍 待查 | 应降级纯文本 |
| C9 | Room AutoMigration 链缺口（6→7/11→12/13→16 缺失）→ 存量用户升级崩溃 | f2bf4e4 | 🔍 待查 | 我们用显式 Migration（8_9/16_17/22_23 + autoMigrations），需核对链完整 |
| C10 | 悬浮窗无 SYSTEM_ALERT_WINDOW 权限 → WindowManager.addView SecurityException → TTS 朗读即崩溃 | 25bfbdc | 🔍 待查 | 需 canDrawOverlays 守卫 |
| C11 | CustomBodies JSON 深嵌套（万层）→ parseToJsonElement SOE（Error 非 Exception）→ UI 崩溃 | a038e62 | 🔍 待查 | 需 catch Throwable |
| C12 | 导入 JSON readText 全量读 + PNG tEXt/Base64 解码无上限 → OOM | 2c8487c | 🔍 待查 | 需 5MB 上限 |
| C13 | 模型输出 base64 图片无限制（超大 decode OOM + 超大尺寸无采样 bitmap OOM） | 0b2fdd8 | 🔍 待查 | 需 base64 20MB + inSampleSize 4K 采样 RGB_565 |
| C14 | mergeJsonObjects/removeElements 递归无深度上限 → SOE（CustomBody 构造 + GoogleProvider 响应） | 6fdb22d | 🔍 待查 | 需 depth 32 |
| C15 | JsonTree 递归渲染深度上限缺失 → 深嵌套 AI 输出 SOE | 深挖批 8-9 | 🔍 待查 | 需 MAX_JSON_TREE_DEPTH=12 |
| C16 | TextArea 文件导入无上限 → 输入框渲染卡死 + system prompt 膨胀 413 | b6a3599 | 🔍 待查 | 需 200K 截断 |
| C17 | 编译/工具输出相关（3a82c42 / 836440d / 1629002） | 3 提交 | 🔍 待查 | 编译期修复，通常随构建暴露 |

## 二、数据丢失类（Data Loss）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| D1 | 超长消息保存失败静默吞掉（>2MB blob 限制）→ 用户无感知丢消息 | c135a3a | 🔍 待查 | 需改用户可见错误 |
| D2 | 压缩期间新消息被整体覆盖 → 丢失追加节点 | ffc5a3a | ⚠️ 已确认待修？ | 需核对我们 ChatService 压缩逻辑 |
| D3 | 工具结果 base64 图片漏转 → require 失败消息丢失 | 77fef1b | 🔍 待查 | 需抽取 parts 级转换复用 |
| D4 | 历史页滑动删除立即物理删附件 → 撤销恢复后附件永久丢失 | 2b3574d | ⚠️ 待修 | 我们 `deleteConversation`（ConversationRepository.kt:308）无延迟清理参数 |
| D5 | SettingsStore.update 并发 lost update（MCP syncTools 并发丢工具列表） | df7bb7c | 🔍 待查 | 需 Mutex 串行化读-改-写 |
| D6 | ShareHandler 只 getStringExtra(EXTRA_STREAM)，Parcelable Uri 类型不匹配 → 分享图片/文件丢失 | 40da9cf | 🔍 待查 | 需兼容 Parcelable 读取 |
| D7 | 多图分享 Uri 在 clipData 而非 EXTRA_STREAM → 仍丢图 | 4374afa | 🔍 待查 | 需兜底取 clipData 第一项 |
| D8 | eventsRoutes 条件注册：eventBus null 时整个 /events 路由消失（SSE 一起丢） | 条件注册修复 | 🔍 待查 | 现事件总线可空 |
| D9 | workspace_edit_file 宽容匹配 → 内容不匹配时静默替换 → 声明/逻辑丢失 | 记录项 | 🔍 待查 | 需 old_text 逐字一致校验 |

## 三、资源防护类（Resource / OOM 预防）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| R1 | 文档注入 prompt 无上限 → API 413/超时 | 4f135b3 | 🔍 待查 | 需 200K 截断 |
| R2 | 记忆无上限 → prompt 爆炸 | 06baee4 | 🔍 待查 | 需条数 200 + 注入 60 条/100K |
| R3 | QuickJS 原生内存泄漏（每次 eval 不销毁）→ 长会话 OOM | dd60e0a | 🔍 待查 | 需 try/finally destroy |
| R4 | fetch 响应无上限 → OOM | ce187e4 | 🔍 待查 | 需 5MB |
| R5 | Bing body 无上限 → OOM | 7230279 | 🔍 待查 | 需 2MB |
| R6 | translateText 输入无上限 → API 413 | 15e37df | 🔍 待查 | 需 200K |
| R7 | GitHub API httpGet 响应无上限 → OOM | 5f53f30 | 🔍 待查 | 需 4MB |
| R8 | 用户消息无上限 → 存库膨胀 + 上下文爆表 + API 413 | 0e6432f | 🔍 待查 | ChatService.preprocessUserInputParts 需 200K 单点截断 |
| R9 | 分享文本经 base64 → 输入框无长度限制 | 批 291 | 🔍 待查 | 与 R8 同批 |
| R10 | 模型 base64 图片（同 C13） | 0b2fdd8 | 🔍 待查 | 见 C13 |
| R11 | TtsSynthesizer 音频流全量收集无上限 → OOM | b57d2a5 | 🔍 待查 | 需 8MB 上限 |
| R12 | writeTextRootfs 无大小限制（workspace_write_file 工具）→ 超大落盘 | a4bfa04 | 🔍 待查 | 需 maxWriteBytes 2MB |

## 四、功能/正确性类（Functional）

| # | Bug | turbo commit | 状态 | 备注 |
|---|---|---|---|---|
| F1 | zip-slip 备份恢复（S3/WebDAV upload/ 分支路径穿越） | 88a12d1 | ✅ 已修 | canonical + startsWith（已移植） |
| F2 | toolCallId 文件名逃逸（模型可控 ID 含 ../） | a9449cc | ✅ 已修 | 清洗 [A-Za-z0-9_-]（已移植） |
| F3 | workspace delete/move "./" 变体可删整个工作区 | 9677cd4 | ✅ 已修 | canonical 对比根目录（已移植） |
| F4 | file_paths.xml 暴露 filesDir | db26bff | ✅ 已修 | 收窄 upload/ + tool_outputs/（已移植） |
| F5 | mermaid 导出失败误报成功 + 调试 URI 日志 | fe2109d | 🔍 待查 | |
| F6 | RouteActivity CRLF 规范化 | 3652fd6 | 🔍 待查 | |
| F7 | extractTar 特殊头顺序（GNU tar 全局头 name 空跳过数据区）+ parsePax 死循环/损坏头崩溃 | d990687 | 🔍 待查 | 我们 RootfsInstaller 需核对 |
| F8 | WebDav buildUrl 路径段不编码（文件名含空格/中文/#/?/& → 同步失败） | ea17fb4 | 🔍 待查 | 需逐段 percent-encode |
| F9 | ASR start() 静默失败（焦点被拒/provider 未配置无反馈） | bdae36e | 🔍 待查 | 需写 ASRStatus.Error |
| F10 | WebServerManager.restart() 竞态（stop 异步 → restart 只停不启） | be3a48d | 🔍 待查 | |
| F11 | GoogleProvider inlineData 拼完整 data URI（纯 base64 渲染失败）+ video/audio | 深挖批 8-9 | 🔍 待查 | |
| F12 | URL 拼接重复（base_url 已含 path → /responses/responses 404） | 深挖批（#57） | 🔍 待查 | buildEndpoint 去重 |
| F13 | SAF DocumentsProvider documentId 合法性校验（恶意 App ws/.. 越权） | 深挖批（v1.8.7） | 🔍 待查 | |
| F14 | MCP 服务器名校验（- 和 _ 放行） | 深挖批（v1.8.6） | 🔍 待查 | |
| F15 | 图片文件名含未清洗 modelName（用户自定义 provider 可含 /..） | 8425679 | 🔍 待查 | 需 sanitizeModelName |

---

## 核查进度

- [x] 安全修复 5 项已移植（F1-F4 + C3）：commit e33535eb / 发布于 v2.4.5-androidrik.17
- [ ] **C1 coerceIn(0,-1)**：确认同款，`PreferencesStore.kt:387` 需空列表守卫 —— 优先修
- [ ] **D4 删除附件延迟清理**：确认待查，deleteConversation 无 deleteFiles 参数
- [ ] **D2 压缩覆盖新消息**：待核对我们 ChatService 压缩逻辑
- [ ] 其余 ⚠️/🔍 项待逐条核查

> 记录：2026-08-13。原始来源 docs/references/turbo-PROGRESS.md（turbo 仓库存档）。
