# 设计稿：手动压缩后台化（design-manual-compress-background）

状态：已批准并实施（2026-11-25）；实施落地与 §4.1 的差异：单飞注册表抽为独立 `ManualCompressionRegistry`（`ManualCompressionParams` + `CompressionEvent` 在 `ChatServiceModels.kt`），不再借用 `AuxJobRegistry`
来源：上游 `7f11a5f15`（Closes #2337，2026-11-25 同步评估 P2 候选）；UX 方向已定夺为**方案 A：后台化 + 阶段文案**（2026-11-25 讨论）

## 1. 背景与目标

手动压缩当前是「弹窗等待」模式：用户在压缩对话框里等完解析→摘要→写回全过程，期间只能取消，切走页面还会把压缩杀掉。上游 3.3.x 已验证后台化体验（弹窗即关、聊天页提示、失败报对话错误）。本仓采纳后台化骨架，但**不照搬上游的共用任务槽禁生成方案**——本仓有独立的安全机制（见 §4.4），压缩期间可以继续聊天，UX 上限更高。

目标：

1. 手动压缩确认后立即关闭弹窗，任务后台执行，切走页面/切换会话不中断
2. 聊天页提示条展示阶段文案（解析上下文→生成摘要→写回检查点），满足「避免无感知等待」约定
3. 提示条可取消；失败走既有对话错误卡
4. 压缩期间**继续聊天不受影响**（不排队、不禁发送）

## 2. 现状链路（改造基线）

```
CompressContextDialog (ChatPage.kt:1011)
  onConfirm ──> ChatVM.handleCompressContext (ChatVM.kt:394)
                 viewModelScope.launch                        ← 隐患1：切走即取消
                 chatService.compressToCheckpoint(...)
                   onProgress ──> ChatVM._compressProgress    ← 隐患2：进度挂 VM
                 onSuccess/onFailure ──> addError / fork
```

- `compressToCheckpoint`：ChatService 内已有，跑在调用方协程（即 VM scope）
- `mergeCompressedConversation`（[ChatServiceModels.kt:72](../../app/src/main/java/app/androidrik/rikkahub/service/ChatServiceModels.kt#L72)）：已解决「压缩期间新消息被快照覆盖丢失」——按 latest 合并、保留快照之后追加的节点
- `compressionMutexes`：同会话压缩串行化（后台预压缩/手动/自动互斥）
- `auxJobs`（AuxJobRegistry）：同会话同类型单飞注册表，AppScope 寿命——标题/建议生成已用此模式
- 自动压缩/预压缩链路（`maybeAutoCompress`/`preCompressJobs`）**本稿不动**

## 3. UX 契约（已定夺）

| 环节 | 行为 |
|---|---|
| 发起 | 压缩弹窗确认即关（移除弹窗内加载态/进度渲染） |
| 执行 | AppScope（经 ChatService），切换会话/离开页面不中断 |
| 反馈 | 聊天页输入框上方提示条：阶段文案三档 + 会话标题（多会话场景区分），无进度条 |
| 发送 | **不排队不禁用**——压缩期间照常聊天（安全性论证见 §4.4） |
| 取消 | 提示条取消按钮 → 任务取消 → 提示条收起 |
| 失败 | 既有 `addError` 对话错误卡（保留现状文案） |
| 完成 | 提示条收起 + toast「压缩完成（节省 N tokens）」（savings 可用时） |
| 新窗口分支 | 「在新会话继续」fork 完成后若用户仍在本会话页则自动跳转；已离开则仅 toast 提示，不强制跳 |

## 4. 技术方案

### 4.1 任务宿主迁移（ChatService）

手动压缩任务迁入 ChatService，复用 `AuxJobRegistry` 单飞模式：

```kotlin
// ChatService 新增
private val manualCompressJobs = AuxJobRegistry()   // 或独立 map，视 AuxJobRegistry 泛型适配

suspend fun startManualCompression(params: ManualCompressionParams): ManualCompressionHandle
fun cancelManualCompression(conversationId: Uuid)
```

- 任务体 `appScope.launch { compressToCheckpoint(...) }`——与 VM 生命周期解耦
- 同会话重复发起：单飞语义——取消旧任务、以新参数起新任务（与「停止→重发」同约定）；参数相同则幂等
- VM 的 `handleCompressContext` 收缩为转发 + 不再持有 Job

### 4.2 压缩状态提升（ChatService → UI）

```kotlin
// ChatService
private val _compressionStates = MutableStateFlow<Map<Uuid, CompressionProgress>>(emptyMap())
val compressionStates: StateFlow<Map<Uuid, CompressionProgress>>

// 完成事件（一次性）：saved tokens、fork 结果
private val _compressionEvents = MutableSharedFlow<CompressionEvent>(extraBufferCapacity = 8)
```

- `CompressionProgress`/`CompressionProgressStage` 枚举已存在（data.ai.compression），**不新增阶段**
- ChatPage 收集 `compressionStates[conversation.id]` 渲染提示条；VM 侧 `_compressProgress` 删除
- `onSavings`/fork 结果改走 `_compressionEvents`；ChatPage 收集事件：在前台且当前会话匹配 → toast / 导航 fork

### 4.3 UI 改动

**CompressContextDialog**：移除 `progress` 参数与内部进度渲染，回归纯参数配置弹窗（`onPreview` 范围预览保留）。

**提示条**（新组件 `CompressBackgroundBar`，置于 ChatInput 上方、输入区域 insets 内）：

- 左：`Lucide.Server`（或折行图标）+ 阶段文案（三档映射 CompressionProgressStage）
- 右：取消按钮（`Lucide.X`）
- 样式：次要色调 surface、圆角、不参与消息列表滚动（常驻输入区上方）
- 多会话不串台：只显示当前会话的进度（按 id 过滤）

### 4.4 竞态分析：为什么不需要排队（与上游方案的本质差异）

定夺时方案 A 假设「压缩中点发送→排队」，**实施论证后推翻该假设**，理由：

1. **新消息不丢**：`mergeCompressedConversation` 已保证压缩结果与期间追加节点按 latest 合并，快照覆盖丢失问题在 2026-11 修复中已根治
2. **检查点指纹只锁窗口边界**：指纹校验针对压缩窗口的边界子树（pathNodes 激活路径下标），尾部追加的新节点不在边界子树内，不影响指纹匹配
3. **压缩互斥已有**：`compressionMutexes` 保证手动/自动/预压缩不会并发改写检查点；压缩与生成是不同槽位，互不占用

结论：压缩期间发消息、生成回复均安全。上游禁生成是因为其压缩占用生成任务槽（架构不同），不是安全问题。**风险残留**：压缩完成前发出的请求仍基于未压缩上下文（预期行为，仅首个请求偏大）。

### 4.5 「在新会话继续」分支

- fork 由后台任务在压缩成功后执行（现状逻辑平移）
- fork 结果经 `_compressionEvents` 发 `ForkReady(fromId, forkId)`
- ChatPage 收集：`fromId == 当前会话` 且页面可见 → 自动导航；否则 toast「已在新会话继续压缩结果」（点击会话列表可见新会话）

## 5. 边界与安全

- **进程被杀**：后台压缩任务丢失（与现状弹窗模式一致，可接受；检查点机制保证无脏写）
- **压缩中再点压缩**：单飞取消旧起新（提示条无感知切换）
- **离线/断网**：LLM 请求失败 → 既有 `addError` 路径
- **压缩中撤销编辑/切换分支**：进入 `compressToCheckpoint` 前已全量 hydrate（既有约束不变）；期间用户操作分支会影响窗口边界——沿用现状弹窗模式的同等风险，不在本稿扩大
- **自动压缩链路**：`maybeAutoCompress`/mid-turn 机制不动

## 6. strings 三语言清单（en / zh / zh-rTW）

| key | 用途 |
|---|---|
| `chat_page_compress_stage_parsing` | 解析上下文 |
| `chat_page_compress_stage_summarizing` | 生成摘要 |
| `chat_page_compress_stage_writing` | 写回检查点 |
| `chat_page_compress_background_running` | 提示条通用前缀「后台压缩中」 |
| `chat_page_compress_background_cancel` | 取消按钮 contentDescription |
| `chat_page_compress_background_done` | 完成 toast（含 savings 占位） |
| `chat_page_compress_background_fork_ready` | fork 已离开页面时的 toast |
| `chat_page_compress_background_failed` | 失败 toast（错误卡之外的兜底） |

## 7. 验收清单

1. 单测：manual compress job 经 AppScope 宿主后 VM 释放不影响任务（模拟 onCleared）；单飞取消旧起新；compressionStates 映射
2. 单测：压缩期间 append 消息后 `mergeCompressedConversation` 保留（可能已有，补断言）
3. `audit_ime_trap` 无新增命中（提示条无 TextField）
4. 真机：长会话发起压缩→立即关弹窗→切走→返回看到提示条→完成 toast；中途发送消息正常；取消按钮生效

## 8. 不做的事

- 不改自动压缩/预压缩/mid-turn 链路
- 不改压缩参数弹窗的参数项与范围预览
- 不做进度条/百分比（阶段文案三档足够）
- 不做压缩任务跨进程持久化
