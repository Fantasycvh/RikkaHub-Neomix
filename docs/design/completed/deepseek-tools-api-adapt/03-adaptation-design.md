# 03 · 适配方案设计（设计已按本稿实施：2026-11-14，3b73b4cdd/ab6e576d5；实施偏差见同目录 README §9）

> 性质：**设计草案**。本文不修改代码，只给落点、取舍与边界；实施前需按 `04-plan-and-tests.md` 的验收与开放问题过一遍。
> 契约依据：同目录 `01-api-reference.md`；仓库事实依据：`02-repo-status.md` 与 `raw/` 三份审计。

## 0. 总原则

1. **默认零变化**：新能力默认关闭；OCR 默认后端仍是现有视觉模型；旧设置/旧备份/旧版 App 恢复都不应因此报错。
2. **付费请求不进预览/预热**：任何会花钱的 transformer 必须 `allowPreview=false`、`allowWarmup=false`（现状 `builtin.document_prompt` 是反例，不能照抄）。
3. **单一计费路径**：内置工具与外挂搜索二选一；OCR 后端二选一；不做「跨后端隐式回退」——否则用户无法归因账单。
4. **计费可解释**：每次付费调用至少有「次数/页数/字符数/时长」之一可落账，进度与错误文案说明实际走的后端。
5. **复用现有抽象**：聊天用 `BuiltInTools`/`UIMessagePart.ServerTool`；搜索用 `SearchService`；语音用 `TTSProvider`/`ASRController`。不新增并行的第二套机制。
6. **契约先行**：字段、状态码、限流以 `01` 为准；文档没写的（分页 token 文法、计费边界）进 `04` 开放问题，不靠猜实现。

## 1. Search + Fetch 补强（P2，小改）

已实现且行为正确，只做四件事：

| # | 改动 | 落点 | 验收 |
|---|---|---|---|
| 1 | QPS 注释/提示从「各 5」改为「Search 100 / Fetch 25，账号级」 | `DeepSeekSearchService` 类注释；`SettingSearchDetailPage.kt:805-810` | 文案与 01 §2/§3 一致 |
| 2 | 设置页说明走 i18n 三套 locale，并补「失败/空结果不计费、限时价可能调整」 | `res/values*/strings.xml` | 三语言无硬编码英文 |
| 3 | 可选：`ori_question`（用户原问题）提升多轮中文查询质量 | 需从 `SearchTools` 把原问题透传到 `SearchCommonOptions`（当前接口只给 `params`，无原问题） | 需先评估接口改动；收益不确定就**不做** |
| 4 | 价格/次数展示留给 §6 的统一计费层 | — | 不单独造第二套展示 |

> 判断：3 的收益依赖真实检索质量对比，且要动 `SearchService` 公共接口；**建议一期跳过**，只做 1/2。

## 2. OCR：新增可选后端 `OCR_BACKEND = { MODEL, DEEPSEEK }`（P1，中）

### 2.1 方案选择

- 采用 **A：设置里显式选择后端**（默认 `MODEL`，现有行为逐字节不变）。
- 不采用 **B：扫描件隐式回退** 作为独立模式；扫描件检测只作为 A 内部的子路径（本地文本为空 → 若用户选了 `DEEPSEEK` 才走远端）。
- 理由：两种后端计费口径不同（token vs 按页），混用不可归因；且 DeepSeek OCR 只输出「逐页文本 + ref/box 坐标」，不含现有 `ocrPrompt` 要求的图标/空间关系/建筑人物语义描述，是**互补而非替代**。

### 2.2 数据与设置（全部带默认值，零迁移）

| 字段 | 默认 | 说明 |
|---|---|---|
| `ocrBackend: OcrBackend` | `MODEL` | `MODEL` / `DEEPSEEK` |
| `ocrDeepSeekProviderId: Uuid?` | `null` | 空 = 复用内置 DeepSeek provider（`f099ad5b-…`，`DefaultProviders.kt:111-122`），共享 Key |
| `ocrDeepSeekBaseUrl: String` | `https://api.deepseek.com` | 端点 `{base}/v1/tools/ocr` |
| `ocrDeepSeekPurpose: OcrPurpose` | `TEXT` | `TEXT → text_recognition`、`RICH_LAYOUT → rich_layout_recognition` |
| `ocrDeepSeekIncludeLayout: Boolean` | `false` | 是否在 prompt 里保留 `<ref>/<box>` 坐标 token |
| `ocrDeepSeekMaxPages: Int` | `1000` | 本地预检上限，防白发请求 |

设置页：`SettingModelPage.kt:251-259` 区域加「OCR 后端」选择；`MODEL` 保留现有模型选择/提示词；`DEEPSEEK` 显示 provider/Key、purpose、includeLayout、上限。i18n 补 en / zh / zh-rTW 三份。

### 2.3 客户端与解析（建议放 `ai` 模块）

- `ai/.../tools/ocr/DeepSeekOcrClient.kt`：OkHttp multipart，**恰好两个 part**（`file` + `purpose`）；`callTimeout=180s`、`writeTimeout=120s`；响应 `body` 前可能带换行保活，先 trim 再解析；HTTP 200 且 body 含 `error` 视为失败；408/429/5xx 可重试，其余不重试。
- `OcrModels.kt`：`OcrResult(id, purpose, pageCount, pages[], usage?)`、`OcrPage(index, text, finishReason)`；纯函数 `stripLayoutTokens(text)`、`toPromptText(pages, includeLayout)`（页码从 1 开始、`finish_reason=length` 页加「文本可能不完整」标注）。
- 本地预检：文件 ≤100 MiB；PDF 页数 ≤`maxPages`。超限直接报错，不请求（省一次 413/400 与 180s 等待）。

### 2.4 两个入口

**入口 1：图片附件（现有 `OcrTransformer`）**

- 按 `settings.ocrBackend` 分派：`MODEL` 原逻辑不动；`DEEPSEEK` 上传图片文件 → 取第 1 页文本 → 复用现有 `<image_file_ocr>` 包裹格式。
- 缓存 key 从「URL」改为 `"deepseek:{purpose}:{includeLayout}:{sha256(file)}"`；现有「只按 URL」会在换 prompt/换后端时错误命中（`OcrTransformer.kt:102,137`）。
- 触发条件保持现状（仅当对话模型无 IMAGE 模态 + `file:` 图片），避免和视觉模型重复花钱。

**入口 2：文档附件（新增独立 transformer）**

- 新增 `DeepSeekDocumentOcrTransformer`，在 `TransformerChainRegistry` 里插到 `builtin.document_prompt` **之前**，`allowPreview=false`、`allowWarmup=false`。
- 仅当 `backend=DEEPSEEK` 且 `UIMessagePart.Document` 的 mime ∈ {pdf, doc, docx, ppt, pptx} 时上传；成功 → 把 Document part 替换为带 `<UploadFile name=…>` 的 Text（后续 `DocumentAsPromptTransformer` 自然无 Document 可处理）；失败 → 回退现有本地解析并保留错误提示，不静默。
- 这样**扫描 PDF 的静默劣化**（现状聊天路径只看到 `---Page N:`，见 `02` §2）被修复，同时不改变文本型 PDF 的本地解析路径（省页费）。

### 2.5 用量与界面

- `usage_record` 加 `page_count INTEGER NOT NULL DEFAULT 0`，DB `69 → 70`（实施时已改手写 Migration_69_70，见 README §9）；`UsageRecorder` 加 public `recordOcrPages(...)`，**绕开零 token 行被丢弃的逻辑**（`UsageRecorder.kt:91`）。
- 统计页 OCR 行展示页数；错误卡标明「DeepSeek OCR（按页计费）」还是「模型 OCR（token）」。
- 首次切换后端/首次上传文档时给隐私提示：文件将离开设备。

### 2.6 边界与错误

| 场景 | 处理 |
|---|---|
| HTTP 200 + `{"error":…}` | 当失败，不写缓存 |
| 页面 `finish_reason=length` | 该页文本前加「本页识别触顶、可能不完整」 |
| 400 加密/格式不支持/超 1000 页 | 错误文案区分「去密码重传 / 转 PDF 或图片 / 拆分文件」 |
| 413 / 408 / 429 / 503 / 504 | 分别给「文件过大 / 上传超时 / 并发超限稍后 / 服务忙重试 / 识别超时」 |
| 用户取消生成 | 服务端仍可能计费：UI 提示「已提交的识别不会因取消而退款」 |
| 同一文件重复上传 | 客户端按 sha256 缓存首轮结果（不跨 session 持久化账单，但避免重复花钱） |

### 2.7 验收（摘要）

1. `backend=MODEL` 时现有 `OcrTransformerTest` 全部原样通过。
2. `backend=DEEPSEEK`：1 张 PNG、1 份扫描 PDF 成功；文本型 PDF **不产生** OCR 费用（仍走本地）。
3. 预览/上下文检查器打开**不触发**任何 DeepSeek 请求（`allowPreview=false` 断言进测试）。
4. 200-with-error、`length` 页、>1000 页、>100 MiB 都有明确文案且不崩。
5. 统计页出现 OCR 行 + 正确页数；备份导出→导入配置不丢。

## 3. TTS：新增 `deepseek` provider（P1，小-中）

### 3.1 形态

- 新增 `TTSProviderSetting.DeepSeek`（`@SerialName("deepseek")`）：`apiKey`、`baseUrl="https://api.deepseek.com"`、`voiceId="stella"`、`format="wav"`、`filterMarkdown=false`。无 `model` 字段。
- 新增 `DeepSeekTTSProvider`：`POST {baseUrl}/v1/tools/tts`，JSON `{text, voice_id, format, filter_markdown}`，响应体直接是音频字节；以单个 `AudioChunk(format, sampleRate, isLast=true)` 返回（本仓 `TtsSynthesizer` 会把流拍平，**不需要** WSS 增量流，`TtsSynthesizer.kt:29-48`）。

### 3.2 注册点（约 6 处，全部是 `when` 穷举）

`TTSProviderSetting.Types`（`:308-324`）、`withEnabled`（`:333-349`）、`TTSManager` 两个 `when`（`:19-69`）、`AddTTSProviderButton` 的添加项、`TTSProviderConfigure` 表单、strings ×3、`TTSProviderSettingDeepSeekTest`。

### 3.3 关键取舍

| 取舍 | 决定 | 理由 |
|---|---|---|
| 默认格式 | `wav` | `AudioPlayer` 对裸 PCM 缺省 24000 Hz 包 WAV 头（`AudioPlayer.kt:56-58`），采样率猜错会变调且不报错；WAV 直接交 ExoPlayer 最稳 |
| `filter_markdown` | 默认 `false`，设置页开关 | 本仓朗读前已 `stripMarkdown`（`ui/hooks/TTS.kt:145-148`）；服务端再过滤是否影响计费口径未知（开放问题） |
| WSS 流式 | v1 不做 | 播放端拍平，增量流零收益；将来做真流式播放时再加 |
| 超长文本 | 依赖现有 160 字符分片 | 10,000 计费字符（中文≈5,000 字）远大于单片，天然安全 |
| 计费字符 | 仅日志记录 `text.length` + 请求失败原因 | speech 模块无用量体系，一期不新建 |

### 3.4 错误与验收

- 400（无可读文本/内容审核）→ 明确文案；429 → 「并发超限稍后」；503/504 → 可重试（沿用 `TTSProviderException.isRetryable`）。
- 响应提前截断视为失败（`TtsSynthesizer` 收流结束即返回，provider 需在流内校验完整性）。
- 验收：任意消息朗读成功；5,000 字长文分片正常；错误有文案不崩；R8 后可选（禁 `simpleName`，用 `@SerialName`）。

## 4. ASR：新增 `deepseek` provider（P1，中）

### 4.1 形态

- 新增 `ASRProviderSetting.DeepSeek`（`@SerialName("deepseek")`）：`apiKey`、`baseUrl="https://api.deepseek.com"`、`sampleRate=16000`、`segmentDurationSec=100`（上限 120s / 8 MiB 取保守值）。
- 新增 `DeepSeekASRController`，照 **MiMo**（`MiMoASRController.kt`）：
  - `AudioRecord` 16 kHz / 16-bit / mono 采集 → 缓冲；
  - 达到 `min(segmentDurationSec, 8 MiB 字节阈值)` 就 flush：`pcm16ToWav` 后 **multipart** `POST /v1/tools/asr`；
  - 解析 `{text, duration_ms}`，按完成顺序拼接并回调；
  - 串行 flush（复用 MiMo 的 `flushJob` 模式），避免乱序。
- 共享 util：把 MiMo 的 `pcm16ToWav`（`:309-338`）下沉为 `asr/PcmWav.kt`，避免第三份拷贝。
- multipart 写法参考 `ai/.../OpenAIProvider.kt:318-355`（speech 模块目前没有 multipart 代码）。

### 4.2 注册点（约 8 处，最容易漏的）

`ASRProviderSetting.Types`/`withEnabled`、`ui/hooks/ASR.kt:133-160` 工厂、`SettingSpeechPage.kt:688-786` **5 个硬编码菜单项**（不读 `Types`，必须手动加第 6 项）、`ASRProviderConfigure` 表单、strings ×3、测试。
`VoiceMode.kt:123-138` **不加分支**：`supportsServerVadVoiceMode` 不含 DeepSeek（`:207-209`），语音模式在 `:71` 就被正确拦截并提示「不支持」——DeepSeek 是整段文件识别、无 VAD/流式，确实不适合语音模式。

### 4.3 必须先修的结构性问题（P0，独立于本次能力）

`SettingsCompatDecoder` 只对 `providers`/`ttsProviders` 做未知子类降级（`SettingsCompatDecoder.kt:42-63`），**没有 `asrProviders`**。后果：用户在装有 `"type":"deepseek"` 的新版上导出备份、再在旧版恢复 → **整份设置恢复失败**。
修法：增加 `filter("asrProviders", ASRProviderSetting.serializer())`，并在选中项被丢弃时回退到默认（对照 TTS 的 `removedTtsIds` 处理）。这一条即使本次不适配 ASR 也应修。

### 4.4 边界与验收

- 120s / 8 MiB：客户端主动分段，不把超限文件发给服务端。
- 语言：无参数、自动检测，UI 不提供语言选择。
- 错误：400（格式/缺字段）、408、413、429、503、504；ASR 层仅 Step 有现成重试实现（`StepASRController.executeWithRetry`）→ DeepSeek 分段级复用该模式，最多重试 1 次（仅 503/504），其余直接报错。
- 验收：30s 听写、>100s 自动分段、8 MiB 保护、语音模式正确提示不支持、旧版恢复含 DeepSeek ASR 的备份不炸整份设置。

## 5. 聊天内置 web tools（分阶段：P0 守卫 → P1 收发 → P2 计费）

### 5.1 数据模型（P0，零迁移）

- `BuiltInTools` 增两个变体（`@SerialName("deepseek_web_search")`、`@SerialName("deepseek_read_url")`）；`Model.tools` 已存在，无需新字段。
- `ModelSettingsForm` 加两项（含「按次计费」说明）；`SearchPicker` 的 5 处 `BuiltInTools.Search` 硬编码收敛为 `hasBuiltInSearch(model)` 判定（同时处理其内部的**模型 ID 白名单** `GEMINI_SERIES`/`gpt-`，否则 DeepSeek 模型在 picker 里的入口表现与守卫不一致）。
- `ChatToolFactory.shouldUseExternalWebSearch` 改为「模型有任一搜索类内置工具 ⇒ 不注册外挂」（否则对同一问题双搜双计费）。
- 同步 `WebSettingsDto` / `SettingsRoutes` / `UpstreamConversationConfig` 的字符串↔枚举映射。

### 5.2 Chat Completions 链路（P1，主要工作量）

DeepSeek 出厂走 Chat Completions（`DefaultProviders.kt:111-122`），而该链路当前：

1. **发送**：模型 `tools` 含内置工具时，在 `tools[]` 里追加 `{"type":"deepseek_web_search"}` / `{"type":"deepseek_read_url"}`（与 function tools 混排）；`tool_choice` 不能指定它们。
2. **解析**：`message.server_tool_calls[]`（按「步」分组）→ 映射为现有 `UIMessagePart.ServerTool` + `StreamChunk.ServerTool*`，让思考链 UI 直接可用；`annotations[]` 的 `url_citation` 目前只有 `title/url`，可选扩 `startIndex/endIndex` 做句末角标。
3. **回传**：下一轮必须把 `server_tool_calls` 连同 `kind` 与 `signature` 原样带回；现状 `addAssistantMessages` 只分组 Content/Tools，`ServerTool` 会被吞——需要把原始 JSON 存进 part metadata 再回传（`ChatCompletionsAPI.kt:620-683`）。
4. **容错**：注解未知 `type` 现在硬 `error`（`:981-995`，刻意对齐上游）。上线前**定向放宽**（跳过 + 日志）或至少确认 DeepSeek 只发 `url_citation`；否则一次格式变化就整轮失败。
5. **续写**：用过内置工具的回复不能续写（官网 400；Chat Completions 续写需 beta 接口 `prefix: true`）。当本轮出现 `ServerTool` 时禁用自动续写（`autoContinueOnLengthTruncation`）并隐藏「继续生成」。
6. **参数被接管**：开启内置工具后 temperature/top_p 等被服务端忽略——在模型设置页给出提示，避免被当 bug。

### 5.3 Responses / Messages（P1 可选）

- Responses 链路已闭环（注意内置工具 `type` 写死处还有 `image_generation`、`UrlContext` 为静默空实现，`ResponseAPI.kt:476-486`），只需把发送的 `{"type":"web_search"}` 按 provider 映射为 `deepseek_web_search`，并补 `deepseek_read_url` 的解析分支。
- Messages：用户自建 Claude 型 provider 可连 `/anthropic`；当前只解码不回传、不声明内置工具。**首版不做**，除非明确要支持该路径。

### 5.4 计费（P2）

- 解析 `usage.server_tool_use` 的**计费计数器**（`tool_search`/`tool_fetch`/`tool_image_search`，不是展示用的 `web_search_requests`/`web_fetch_requests`）。
- 扩展 `TokenUsage`（`ai/.../core/Usage.kt:6-11`）加工具次数；nerd line 估算费用（¥5/千次）；若要进统计页，`usage_record` 需加列 + AutoMigration。
- 价格表放 App 本地常量（models.dev 的 `cost` 从未声明在 DTO 上、解码即被丢弃，拿不到）。

### 5.5 明确不做（首版）

- Files API 文件输入：与本地 `DocumentAsPromptTransformer` 重复解析、双份 token/页费；且 `file_id` 生命周期与备份/多端同步复杂。等有明确需求另立设计。
- `safety_check` 默认开启：只在「Web server 对外暴露」或用户显式开关时启用（合规要求只针对对外提供服务；开启后还会引入拒答 UI 与额外 token 计费）。
- 内置工具与 WSS/图片搜索的完整块形态（`image_search`/`deep_image_search`）：先只做文本搜索 + 读链接。

## 6. 横向：计费、隐私、合规、i18n

| 主题 | 设计 |
|---|---|
| 单位与价格 | 本地常量表：Search/Fetch ¥5/千次、OCR/文件解析 ¥1.5/千页、TTS ¥1.5/万字符（限时，刊例 3）、ASR ¥0.39/小时（限时，刊例 0.78）；注明「限时价截止时间未公布」 |
| 落账 | `usage_record` 扩展计数列（页数、工具次数）而非金额；金额只在展示层按当前价估算，避免价格调整后历史账目失真 |
| 展示 | nerd line / 统计页显示「token + 次数/页数 + 估算费用」；设置页显示各能力单价 |
| 隐私 | 首次启用 DeepSeek OCR/文档上传/内置搜索时弹一次性说明：文件/查询会离开设备 |
| 合规 | 对外提供服务（内置 Web server 暴露）时提示启用 `safety_check` 并履行内容审核/大模型登记/AI 标识义务；不默认替用户开启 |
| i18n | 所有新增 UI 文案三套 locale（en / zh / zh-rTW） |
| 回滚 | 每个能力独立开关（OCR 后端切回 MODEL、TTS/ASR 停用 provider、内置工具从模型 `tools` 移除即可回到现状） |

## 7. 实施顺序建议

```
P0（不花钱、先防 bug）
  ├─ 聊天内置工具：BuiltInTools 变体 + SearchPicker/ChatToolFactory 互斥守卫 + 枚举映射
  └─ SettingsCompatDecoder：补 asrProviders 降级
P1（并行三条线，各自可独立验收）
  ├─ OCR 后端 A（客户端/transformer/DB/UI）
  ├─ TTS provider（整段 POST，默认 wav）
  └─ ASR provider（MiMo 型分段 multipart）
P1b（依赖 P0 数据模型）
  └─ Chat Completions：发内置工具 → 解析 server_tool_calls → 多轮回传 → 注解容错 → 禁续写
P2（账目/体验/合规）
  ├─ usage_record 计数列 + nerd/统计页费用估算
  ├─ 价格表与设置页单价展示
  └─ safety_check 开关（对外服务场景）
```

依赖：OCR/TTS/ASR 三条线互不影响，可并行；OCR 与聊天内置工具都要动 `usage_record`/DB，若同批上线需合并 migration 版本号。
