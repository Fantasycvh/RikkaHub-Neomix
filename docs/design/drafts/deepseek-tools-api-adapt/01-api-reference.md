# 01 · DeepSeek Tool APIs 官方资料摘编（2026-11-13 发布）

> 性质：**官方资料摘编 + 适配判据**，供后续实施时对照；本文不含实现代码，也不承诺上游契约不变。
> 来源：DeepSeek 官方文档（2026-11-13 抓取，中文版为准），链接见 §9。
> 与用户提供的定价截图逐项核对一致（见 §7）。

## 0. 本次发布一句话

2026-11-13 DeepSeek 发布「全家桶 App API」：在 `deepseek-flash`（V4.1-Flash）的 Chat Completions / Responses / Messages 三种协议上提供**服务端内置工具**（联网搜索、深度思考搜索、文件输入、阅读链接、内容安全、会话标题、用户元信息标记），同时提供 **5 个独立 Tool API**（Search、Fetch、OCR、TTS、ASR）供自定义工作流直接调用。

对 RikkaHub 而言，两条线要分开看：

| 线 | 入口 | 计费 | 与现有实现的关系 |
|---|---|---|---|
| 独立 Tool API | 直接 HTTP/WSS 调 `api.deepseek.com/v1/tools/*` | 按次/页/字符/时长 | 适配成 search / OCR / TTS / ASR provider |
| 聊天内置工具 | 请求里声明 `deepseek_web_search` / `deepseek_read_url` | Token + 每次搜索/抓取另计费 | 与 App 侧 search 工具**互斥或去重**，否则双重计费 |

## 1. 共同约定

| 项 | 值 |
|---|---|
| Base URL | `https://api.deepseek.com` |
| 鉴权 | `Authorization: Bearer <DeepSeek API Key>`；Messages 兼容层用 `x-api-key`，路径前缀 `/anthropic` |
| 独立 Tool API 形态 | 裸 JSON（Search/Fetch/TTS），multipart（OCR/ASR），WebSocket（TTS 流式） |
| 与聊天 API 的 Key | **同一把 Key**，不区分 |
| 错误体 | OpenAI 兼容 `{"error":{"message","type","param","code"}}`；OCR 有「HTTP 200 但 body 是 error」的特例 |
| 重试约定 | 408/429/5xx 可等待后重试；其余 4xx 不重试；402 余额不足，充钱前重试无意义 |
| 账号级限流 | 搜索/抓取、OCR、TTS、ASR 各有限制，**按账号而非 Key**，多 Key 轮询绕不过 |
| 币种 | 官网中/英文档分别给 CNY / USD 价目；本仓 UI 面向国内用户，用 CNY |

## 2. Search API（网页搜索）

`POST https://api.deepseek.com/v1/tools/search` — 自建搜索引擎，返回结构化结果；不返回合成答案。

### 请求字段

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `query` | string | 是 | - | 1~128 个 Unicode 字符，不能全空白；支持下述查询算子 |
| `count` | int | 否 | 10 | 1~50 |
| `ori_question` | string | 否 | - | 触发本次搜索的用户原问题（1~128 字符），用于改善查询理解；`null` 视作未提供 |
| `localization` | string | 否 | "" | 文档原文：用于**优先返回**本地化搜索结果的 IP 地址 |
| `need_content` | bool | 否 | false | 是否在 `content` 返回网页正文；`false` 时恒为空串 |

### 查询算子（原样透传给服务端）

| 算子 | 示例 | 含义 |
|---|---|---|
| `site:` | `DeepSeek site:github.com` | 限定站点；多个用空格分隔 `site:` 并用 `OR` 连接 |
| `-site:` | `DeepSeek -site:example.com` | 排除站点，多个空格分隔 |
| `-` | `DeepSeek -advertisement` | 排除含该词的页面 |
| `-"..."` | `DeepSeek -"release notes"` | 排除精确短语 |
| `"..."` | `DeepSeek "exact phrase"` | 精确短语匹配 |
| `lang:` | `DeepSeek lang:zh,en` | 限定语言，逗号分隔 |
| `after:` / `before:` | `after:2025`、`before:2026-06-01` | 限定发布时间 |

### 响应（HTTP 200）

`{"results":[{...}]}`，无匹配时是空数组（**成功，不是错误**）。结果项字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| `rerank_score` | number | 0~3 相关度（综合相关/权威/新鲜度），≥1.5 视为优质 |
| `title` / `url` | string | 标题 / 链接 |
| `snippet` | string | 100~200 字符短摘要，**给 UI 展示** |
| `summary` | string | 600~800 字符长摘要，**给模型阅读** |
| `content` | string | 网页正文；`need_content=false` 或内容不可得时为空串 |
| `site_name` / `site_icon` | string? | 站点名 / 站点图标，可为 null |
| `date` | string? | UTC RFC 3339 发布时间，可为 null |
| `authority` | int | 站点权威度 0~4；`-1` 未知，≥3 高权威 |

### 错误 / 计费 / 限流

- 错误码：400（参数/JSON 非法）、401（Key 无效）、402（余额不足）、403（Fetch 被安全策略拒绝）、404（Fetch 取不到可用内容）、408（读超时）、413（请求体过大）、429（QPS 超限）、500/504。
- 计费：**按成功请求**，¥5 / 1,000 次（$0.75 / 1,000 次）；仅当 HTTP 200 且 `results` 非空才计费；`count` 不影响单价；失败/空结果/超时不计费。
- 限流：Search **100 QPS**、Fetch **25 QPS**（账号级、互相独立；Fetch 另有按域名 QPS）。

### 适配判据

- 模型侧 `text` 应取 `summary`（缺则回退 `snippet`），UI 展示用 `snippet` —— 本仓已按此实现（见 02）。
- `count` 必须夹紧到 1~50；`need_content=false` 时不要指望 `content`。
- 重试 408/429/5xx；402 直接给「余额不足」提示；账号级 QPS 不要用多 Key 轮询规避。

## 3. Fetch API（网页抓取）

`POST https://api.deepseek.com/v1/tools/fetch` — 抓取单个网页，返回 Markdown 正文。

| 请求字段 | 说明 |
|---|---|
| `url` | 必填；≤8192 字符；必须是带 host 的 http/https URL；不能含用户名密码 |

响应：`{"title":"...","content":"# Markdown 正文"}`；`title` 不可得时为空串。

- 错误：400（参数非法）、401、402、403（安全策略拒绝）、404（URL 不可达/站点策略/错误状态/不支持的内容类型/无可提取 Markdown）、408、413、429、500、504。
- 计费：按成功请求，¥5 / 1,000 次；只有返回页面内容才计费。
- 限流：同 §2，Fetch 25 QPS（账号级 + 每域名限制）。

### 适配判据

- 本仓 `scrape` 需要 Markdown 正文 —— 与 Fetch 的返回天然对齐。
- URL 前置校验（长度/协议/host/凭据）应在本地做，省一次计费与 QPS。
- 403/404 不可重试；仅 408/429/5xx 重试。

## 4. OCR API（文字识别）

`POST https://api.deepseek.com/v1/tools/ocr` — multipart/form-data，**两个字段都必填，多传任何字段返回 400**。

| 字段 | 说明 |
|---|---|
| `file` | 待识别文件，带文件名；支持 PDF、Word（DOC/DOCX）、PowerPoint（PPT/PPTX）、图片（PNG/JPEG/WebP/GIF/BMP/TIFF）；格式按文件实际内容判断 |
| `purpose` | `text_recognition`：无图 Word/PPT 直接抽文字，其余逐页识别；`rich_layout_recognition`：所有文件逐页识别 |

### 限制

| 限制 | 值 |
|---|---|
| 文件大小 | 100 MiB（超出 413） |
| 页数 | 1,000 页 |
| 上传 | 120 秒内完成，且中途停顿不超过 30 秒，否则 408 |
| 总耗时 | 从开始上传起 180 秒，含上传+识别+返回结果 |

### 响应

识别期间服务端持续发送换行保活，之后一次性返回 JSON（前导换行是空白，整个 body 仍可整体解析）：

```json
{"id":"ocr-...","object":"ocr.result","created":1762790000,"purpose":"text_recognition",
 "page_count":2,
 "pages":[{"index":0,"text":"...","finish_reason":"stop"},{"index":1,"text":"...","finish_reason":"length"}],
 "usage":{"input_tokens":1536,"output_tokens":412,"total_tokens":1948}}
```

- `pages[].text` 是模型原始输出、未清洗；`finish_reason` 为 `stop`，或 `length`（该页输出触顶、文本不完整）。
- 逐页识别的文本保留特殊 token：`<｜ref｜>标题<｜/ref｜><｜box｜>[[52,40,948,96]]<｜/box｜>`，坐标 `[[x1,y1,x2,y2]]` 为左上/右下角、**按页宽高归一化到 0~1000**（像素 = 值 × 页宽/1000）。直接抽文字的文档没有这些 token。
- `usage` 仅供参照，不参与计费。

### 错误（易踩的两个特例）

- 状态码：400（表单/`purpose` 非法，或文件无法处理）、402、408、413、429（账号并发识别超限）、500、503、504。
- **识别已开始后 HTTP 已是 200**；若识别失败/超时，响应 body 是错误对象而不是结果 —— 必须检查 body 是否含 `error`。
- 不可重试：文件加密（提示去密码）、格式不支持/无法解析/图片无法解码、超过 1,000 页、超过 100 MiB、识别超时（改小文件）。可重试：服务繁忙、内部错误。

### 计费 / 限流

- ¥1.5 / 1,000 页（$0.225），按**成功识别的 `page_count`** 计费；失败/超时不收费。
- 客户端断开**不会**取消已开始的识别：文件收到后跑完，完成照常计费；跑完前**仍占用 15 并发名额**。
- 账号级并发识别上限 15（与聊天模型、与 Files API 分开计）。

### 适配判据

- 本仓当前 OCR 是「配置一个视觉模型，让模型看图说话」；DeepSeek OCR 是**文件进、逐页文本出**的专用接口，能直接吃 PDF/Office，适合扫描件。
- 已在本地的文件可直接 multipart 上传，无需先渲染 PDF 为图片；`rich_layout_recognition` 才需要额外处理图（本仓暂无消费位）。
- 注意 200-with-error、`finish_reason=length`（页文本不完整）、1,000 页/100 MiB 前置校验。

## 5. TTS API（语音合成）

两种模式共用 `wss://api.deepseek.com/v1/tools/tts` 与 `POST https://api.deepseek.com/v1/tools/tts`。

### 音色 / 语言 / 输出

- `voice_id`：`stella`（女声，默认）、`tide`（男声）。
- 语言：zh、yue、en、ja、ko、ms、ar、id、th、vi；**没有语言参数**，按文本自动判定。
- 格式：`wav`（默认，`audio/wav`）或 `pcm`（裸 16-bit 单声道 24 kHz）。
- 单请求音频上限 30 分钟；`filter_markdown` 默认 `true`。

### 整段 HTTP

| 字段 | 必填 | 说明 |
|---|---|---|
| `text` | 是 | 非空；≤10,000 计费字符；请求体 ≤64 KiB |
| `voice_id` | 否 | `stella`（默认）/`tide` |
| `format` | 否 | `wav`（默认）/`pcm` |

- 音频边合成边流式返回；400 表示无可读文本或未过内容审核（`Content Exists Risk`）；429/503/504 见错误。
- 若合成中途失败，响应会被截断 —— **响应提前结束就当失败**。不支持语速控制，也不支持断点续传。

### WebSocket 流式

- 连接 `wss://api.deepseek.com/v1/tools/tts`，query 参数 `voice_id`、`format`（默认 `pcm`，或 `opus`）、`filter_markdown`。
- 鉴权二选一：`Authorization: Bearer <key>`，或子协议 `Sec-WebSocket-Protocol: tts, bearer.<key>`。
- 发文本：`{"event":"text","text":"..."}`，最后发 `{"event":"finish_text"}`；音频以二进制消息下发；结束消息 `{"event":"finish","code":"...","message":"..."}`，`code` 为 `success` 或 `invalid_input`/`content_filter`/`input_timeout`/`service_error`。
- 限制：60 秒无文本则结束；单会话 ≤30 分钟；文本 ≤10,000 计费字符且 ≤64 KiB；服务端每 25 秒 ping。

### 计费 / 限流

- 刊例价 ¥3 / 10,000 字符，**限时 5 折 ¥1.5 / 10,000 字符**（$0.45 / $0.225）。
- 计费字符：**中文字符算 2，其他字符（字母/数字/空格等）算 1**。
- 账号级并发上限 50。

### 适配判据

- 现有 TTS provider 多为「整段文本 → 音频」；DeepSeek 两种模式都支持，整段 HTTP 最省事。
- `pcm` 是裸流，需 `AudioTrack` 直接播；`wav` 可直接落文件/播放。WebSocket 适合「边生成边朗读」。
- 输入上限 10k 计费字符（中文≈5k 字），长回复需要分段；无语言/语速参数。

## 6. ASR API（语音识别）

`POST https://api.deepseek.com/v1/tools/asr` — multipart/form-data，字段 `file`；**整段音频一次请求，返回完整转写，不支持流式**。

| 项 | 值 |
|---|---|
| 格式 | 16-bit PCM WAV 或 Ogg Opus（按内容判断）；WAV 支持 8000/16000/22050/24000/32000/44100/48000 Hz |
| 声道 | 单声道或立体声；立体声只识别左声道 |
| 大小 | ≤8 MiB |
| 上传 | 10 分钟内完成 |
| 时长 | ≤120 秒 |
| 语言 | 21 种（zh、yue、en、ja、ko、de、fr、es、pt、it、ru、ar、hi、id、vi、th、tr、ms、fil、pl、hu）；**无语言参数**，自动判定 |

响应：`{"request_id":"...","text":"...","duration_ms":3200}`。

- 错误：400（不是 16-bit PCM WAV / Ogg Opus，或缺 `file`）、408（上传超 10 分钟）、413（>8 MiB）、429（并发超限）、503（服务忙）、504（识别超时）。
- 计费：刊例价 ¥0.78 / 小时，**限时 5 折 ¥0.39 / 小时**（$0.116 / $0.058）；按**音频时长到秒**计费，不足 1 秒按 1 秒。
- 账号级并发上限 50。

### 适配判据

- 现有 ASR 多为实时流式（Realtime/WebSocket）；DeepSeek 是「录完再传」，**120 秒上限**意味着长录音必须分片。
- App 录音若为 AAC/AMR 等格式，需要先转 16-bit PCM WAV 或 Ogg Opus。
- 返回 `duration_ms` 可用于计费展示；无语言参数 → 不需要语言选择项。

## 7. 定价总表（官网 CNY，与用户截图逐项核对）

| 项目 | 计费单位 | 官网 CNY | 用户截图 | 官网 USD（英文版） | 备注 |
|---|---|---|---|---|---|
| 搜索（文字/图片） | 每 1,000 次 | 5 元 | 5 元/千次 | $0.75 | 成功且有结果才计费 |
| 网页抓取（Fetch） | 每 1,000 次 | 5 元 | 5 元/千次 | $0.75 | 成功取到内容才计费 |
| 文件解析 / OCR | 每 1,000 页 | 1.5 元 | 1.5 元/千页 | $0.225 | 按成功页数；文件解析与 OCR 同价 |
| 语音合成 TTS | 每 10,000 字符 | 3 元 → **1.5 元（限时 5 折）** | 1.5 元/万字符（原 3.0） | $0.45 → $0.225 | 中文字符算 2 |
| 语音识别 ASR | 每小时音频 | 0.78 元 → **0.39 元（限时 5 折）** | 0.39 元/小时（原 0.78） | $0.116 → $0.058 | 按秒，不足 1 秒按 1 秒 |

> 结论：用户截图与官网 CNY 价目一致；截图底部「语音识别与语音合成当前处于限时 5 折优惠期」与官网「限时折扣价（5 折）」一致。5 折截止时间官网未给出，价格可能调整（官网保留改价权利）。

## 8. 聊天内置工具（`deepseek-flash`）

### 声明方式

三种协议都在 `tools` 里声明，但格式不同：

```jsonc
// Messages (Anthropic 兼容)：需要 name
{"type":"deepseek_web_search","name":"deepseek_web_search"}
{"type":"deepseek_read_url","name":"deepseek_read_url"}

// Responses / Chat Completions：只需要 type
{"type":"deepseek_web_search"}
{"type":"deepseek_read_url"}
```

- `tool_choice` **不能**指定这两个工具（返回 400）；`tool_choice:none` 会关闭全部工具（含读链接）；`tool_choice:"required"` 按 `auto` 处理。
- 只能配 `deepseek-flash`；`safety_check`、`title` 放在顶层。
- 只读链接时只声明 `deepseek_read_url`。
- 同一工具声明两次返回 400；**声明其他服务端工具与这两个工具互斥**（function 工具可混排）。

### 能力与返回块（Messages 格式为主）

| 场景 | 返回块 | 关键字段 |
|---|---|---|
| 联网搜索（非思考） | `server_tool_use` + `web_search_tool_result` | `input.action=search`，`input.queries[]`；结果含 `title/url/snippet/site_name/site_icon/encrypted_content/page_age` |
| 深度思考搜索（思考开） | `server_tool_use` + `web_search_tool_result` / `web_fetch_tool_result` | `action` 为 `search`/`open_page`/`find_in_page`，`kind=deep_search`；打开页只回 URL/标题/时间，`data` 为空且无 `signature` |
| 阅读链接 | `server_tool_use` + `web_fetch_tool_result` | `action=read_link`；正文加密在 `signature`，**必须原样回传**；错误码 `url_not_accessible`/`url_not_allowed`/`unavailable` |
| 图片搜索 | 同联网搜索 | `kind=image_search`/`deep_image_search`，`queries` 为空，结果多 `image_url`（仅展示、可能失效） |
| 引用 | text 块 `citations[]` | `type=web_search_result_location`，`cited_text` 是「回答文本截至引用处」，不是来源原文 |

- 深度思考搜索最多约 20 次工具调用。
- 读链接只检查**最后一条用户消息**，重复 URL 只算一次，最多考虑前 10 个不同 URL，由模型决定读哪些。
- 流式：`server_tool_use` 与结果块都一次性出现在 `content_block_start`；最终 `usage` 以 `message_delta` 为准；`stop_reason=refusal` 表示拒答。

### Responses / Chat Completions 差异

| 协议 | 调用块 | 引用 |
|---|---|---|
| Responses | `output[]` 里的 `web_search_call`（`kind`、`action.type=sources`、`page`、`signature`） | `output_text` 的 `url_citation` 注解；`status=incomplete` + `content_filter` 表示拒答 |
| Chat Completions | `message.server_tool_calls[]`（按「步」分组：`index`、`reasoning_content`、`calls[]`，每个 call 有 `type/kind/action/status/sources/page/signature`；`status` 为 `completed`/`failed`，响应结束时还没有结果的调用为 `failed`；`reasoning_content` 只含最后一步思考） | `message.annotations[]`（`url_citation`，`start_index/end_index` 按 Unicode 码点）；`finish_reason=content_filter` 拒答 |

补充约束：
- Chat Completions 请求里带 `web_search_options`、`response_format`（非 text）、`logprobs`/`top_logprobs` 均返回 400；Responses 的 `text.format`（非 text）、`previous_response_id`、`conversation` 同样 400。
- 多轮回传时须把服务端工具块的 **`kind` 与 `signature`** 原样带回。

### 内置工具的计费（易错）

- 最终 `usage.server_tool_use` 里两套计数器：
  - `web_search_requests` / `web_fetch_requests`：**展示用**（含错误块），**不是计费次数**；
  - `tool_search` / `tool_fetch` / `tool_image_search`：**计费次数**。
- 搜索：一次调用返回结果时，**按查询词条数**计费（3 个 query = 3 次）；失败/无结果不计费。
- 图片搜索：每张图 1 次；模型框选图片区域时每个区域另计 1 次。
- 抓取（读链接）：按成功抓取的网页数，**包括被内容安全替换成 `url_not_allowed`/`unavailable` 的网页**；完全失败不计费。
- 深度思考搜索的「打开网页」只在实际从网络抓取时计费；重复打开、翻页、打开已随搜索结果返回的网页免费；页内查找免费。
- 与独立 Search/Fetch API **同价**（¥5/千次）。
- 生成中途失败时 HTTP 已是 200，**失败前已执行的推理/搜索/阅读照常计费**。

### 被忽略的参数

声明内置工具 / `safety_check=high` / `title` 任一开启时，服务端接管以下参数（请求里传了也不生效）：

- Messages：`temperature`、`top_p`、`top_k`、`stop_sequences`、`thinking.budget_tokens`、`preserve_thinking`、`tool_choice.disable_parallel_tool_use`、`metadata`（`user_id` 除外）
- Responses：`temperature`、`top_p`、`reasoning.summary`、`text.verbosity`、`include`、`max_tool_calls`、`truncation`、`metadata`、`parallel_tool_calls`、`store`
- Chat Completions：`temperature`、`top_p`、`frequency_penalty`、`presence_penalty`、`stop`、`seed`、`n`、`preserve_thinking`、`parallel_tool_calls`

`max_tokens` 仍生效，但限制的是**每一步推理**的输出，不是整轮总量。用过内置工具的回复**不能续写**（400）；Chat Completions 的续写需 beta 接口加 `prefix: true`，Responses API 不支持继续生成。

### 内容安全

- `"safety_check":{"level":"high"}` 开启基础风控；`low`（默认）等同不传。
- 拒答形态：Messages `stop_reason=refusal`；Responses `status=incomplete` + `incomplete_details.reason=content_filter`；Chat Completions `finish_reason=content_filter`。
- 已流式下发的文本无法撤回，前端要替换为拒答文案；拒答前 Token 照常计费。
- 审核 Token 按输出 Token 计费，单列在 `output_tokens_details.safety_tokens`（Chat Completions 为 `completion_tokens_details.safety_tokens`）。
- 单独使用 `safety_check`（未声明内置工具）时，`tool_choice` 只能 `auto`/`none`；输入审核未过返回 400 时**输入 Token 照常计费**（按缓存命中价）；响应不会说明拒答的具体原因。
- **合规**：对公众提供服务时官方要求开启 `safety_check`，并自行履行内容安全审核、大模型登记、AI 标识等义务（不能替代你的责任）。

### 文件输入（Files API，另一条线）

- 上传 `POST /files`（multipart），`purpose` 必须在 `file` 之前；`text_recognition`（只文字）或 `rich_layout_recognition`（文字+文档里的图，按原位置）。
- 对话里引用 `file_id`：Messages `{"type":"document","source":{"type":"file","file_id"}}`（需加 `anthropic-beta: files-api-2025-04-14` 头）、Responses `{"type":"input_file","file_id"}`、Chat Completions `{"type":"file","file_id"}`；只能引用 `file_id`，不能直接传文件内容或 URL。
- 限制：单请求 ≤20 个文档、文档文字合计 ≤8 MiB；文件 ≤100 MiB / ≤1,000 页；上传 120 秒；解析超时 120 秒；账号并发 15（与 OCR 分开）；`user_data` 原文件 ≤32 MiB、文本内容 ≤8 MiB；上下文超长返回 400、不会静默截断文档。
- 计费：**上传解析时按页一次性计费**（与 OCR 同价），引用时不再收解析费、只按拼入上下文的输入 Token 计费。
- 纯文本/表格/电子书（txt、md、csv、json、代码、xlsx、xls、epub、mobi）用 `purpose=user_data`，转 Markdown，**不计费**；不支持 AZW3 与 DRM 电子书。

## 9. 官方链接（2026-11-13 抓取）

- 发布公告：<https://api-docs.deepseek.com/zh-cn/news/news261113/>
- 构建 Chatbot 总览：<https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot/>
- Search & Fetch 指南：<https://api-docs.deepseek.com/zh-cn/guides/search/>
- 联网工具（内置 web tools）：<https://api-docs.deepseek.com/zh-cn/guides/web_tools/>
- OCR：<https://api-docs.deepseek.com/zh-cn/guides/ocr/>
- TTS：<https://api-docs.deepseek.com/zh-cn/guides/tts/>
- ASR：<https://api-docs.deepseek.com/zh-cn/guides/asr/>
- 文件解析：<https://api-docs.deepseek.com/zh-cn/guides/document_parsing/>
- 模型内容安全：<https://api-docs.deepseek.com/zh-cn/guides/safety/>
- 模型与价格：<https://api-docs.deepseek.com/zh-cn/quick_start/pricing/>
- API 参考（Search）：<https://api-docs.deepseek.com/zh-cn/api/search/>
- API 参考（Fetch）：<https://api-docs.deepseek.com/zh-cn/api/fetch/>

> 注：docs 站支持在 URL 后加 `.md` 取纯 Markdown 全文（本文摘编即以此方式抓取，便于逐字核对）。
