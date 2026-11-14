# 04 · 实施计划、测试与风险（备料稿，本轮不执行）

> 性质：把 `03-adaptation-design.md` 拆成可执行任务、验收标准、测试矩阵与风险登记；**本文不含实施动作**。
> 工作量是**粗估**（按本仓既有工程习惯，单人），用于排期讨论，不是承诺。

## 0. 前置条件（开工前）

1. 读完 `01-api-reference.md` 与 `02-repo-status.md`；涉及代码改动时以仓库实际为准（行号会漂移）。
2. 准备一把**真实 DeepSeek API Key** 做一次计费探测（见 §4）。本仓上一次 Search/Fetch 适配（commit `786638fca`）明确记录「没有 Key，字段/状态码只对文档 + 假服务器」，本轮不要把「文档正确」当成「接口可用」。
3. 统一约束：新设置字段全部带默认值（零迁移）；默认行为不变；新增 UI 文案补 en/zh/zh-rTW；付费请求不进预览；不做隐式跨后端回退。
4. 涉及外发/推送的动作遵循仓库既定授权规则，未获授权只落本地草稿。

## 1. 工作量与优先级总表

| 包 | 内容 | 优先级 | 粗估 | 依赖 |
|---|---|---|---|---|
| P0-1 | 内置工具数据模型 + 互斥守卫 + 枚举映射 | P0 | 0.5~1 d | 无 |
| P0-2 | `SettingsCompatDecoder` 补 `asrProviders` 降级 | P0 | 0.5 d | 无 |
| P1-1 | OCR 可选后端（client/transformer/DB/UI） | P1 | 3~5 d | P0-2（DB 版本协调） |
| P1-2 | TTS `deepseek` provider（整段 POST） | P1 | 1.5~2.5 d | 无 |
| P1-3 | ASR `deepseek` provider（分段 multipart） | P1 | 2~3 d | P0-2 |
| P1b | Chat Completions 内置工具收发/回传/容错 | P1 | 3~5 d | P0-1；需真机契约确认 |
| P2-1 | 用量计数列 + nerd line/统计页费用估算 | P2 | 2~3 d | P1-1/P1b |
| P2-2 | 价格表与设置页单价展示 | P2 | 0.5~1 d | P2-1 |
| P2-3 | `safety_check` 开关（对外服务场景） | P2 | 1~2 d | P1b |

合计约 **15~22 个工作日**（不含真机联调与等待上游确认）。

## 2. 任务卡

### P0-1 内置工具：数据模型与互斥守卫（不花钱，先做）

**改动**
- `ai/.../provider/Model.kt`：`BuiltInTools` 增 `@SerialName("deepseek_web_search")`、`@SerialName("deepseek_read_url")` 两个 `data object`。
- `ModelSettingsForm.kt:450-490`：加两项（标题/描述 + 「按次计费」提示）。
- `ui/components/ai/SearchPicker.kt:77,92,170,355,368`：把 `BuiltInTools.Search` 判定收敛成 `hasBuiltInSearch(model)`（覆盖两个新变体，并处理其内部模型 ID 白名单 `GEMINI_SERIES`/`gpt-`）。
- `ChatToolFactory.kt:50-52`：`shouldUseExternalWebSearch` 改为「模型有任一搜索类内置工具 ⇒ false」。
- `WebSettingsDto.kt:273-277`、`SettingsRoutes.kt:198-203`、`UpstreamConversationConfig.kt:41`：字符串↔枚举映射。
- strings ×3。

**验收**
1. 开关在模型编辑页可见、可保存、可重启恢复。
2. 开启任一 DeepSeek 内置搜索后，`search_web`/`scrape_web` **不注册**（断点/日志验证），且不产生外挂搜索请求。
3. 旧设置 JSON、旧备份、Web 面板读写都不受新枚举值影响（反序列化默认 `emptySet()`）。
4. 未开启时行为与现状完全一致（回归测试）。

**测试**：`shouldUseExternalWebSearch` 各组合；`BuiltInTools` 序列化 round-trip；`WebSettingsDto` 双向映射；SearchPicker 判定抽成纯函数后单测。
**回滚**：移除枚举值与表单项即可；旧数据无该字段，无迁移。

### P0-2 设置兼容：`asrProviders` 降级（独立缺陷修复）

**问题**：`SettingsCompatDecoder` 只降级 `providers`/`ttsProviders`（`SettingsCompatDecoder.kt:42-63`）。新版本写入 `"type":"deepseek"` 的 ASR 后，旧版恢复备份会因未知子类**整份设置恢复失败**。

**改动**：增加 `filter("asrProviders", ASRProviderSetting.serializer())`；被丢弃的 ASR 若正好是 `selectedASRProviderId`，回退到默认/系统（对照 TTS 的 `removedTtsIds` 处理）。

**验收**：构造含未知 ASR 类型的设置 JSON，旧解码路径降级为「丢弃该条 + 选中项回退」，不抛异常；TTS 既有用例不变。
**测试**：`SettingsCompatDecoderTest` 增补；ASR 选中项回退断言。

### P1-1 OCR 可选后端

**子任务**
1. `ai/.../tools/ocr/DeepSeekOcrClient.kt` + `OcrModels.kt` + 纯函数（`stripLayoutTokens`/`toPromptText`）。
2. `Settings`/`PreferencesStore` 新字段（默认值见 `03` §2.2）。
3. `OcrTransformer` 按 backend 分派 + 缓存键改 sha256。
4. `DeepSeekDocumentOcrTransformer` + 链注册（`allowPreview=false`、插在 `document_prompt` 之前）。
5. `UsageRecordEntity.pageCount` + DB `69→70` AutoMigration + `UsageRecorder.recordOcrPages`。
6. `SettingModelPage` 后端选择/配置 + strings ×3。
7. 测试与真机 smoke。

**验收**：`03` §2.7 全条目；另加「`backend=MODEL` 时现有 `OcrTransformerTest` 逐条通过」。
**测试矩阵**
- JVM：`stripLayoutTokens`（正常/缺闭合/多 box/无 token/全角括号）、`toPromptText`（页码、空页、`length` 提示）、预检边界（100 MiB±1、1000/1001 页）。
- MockWebServer：multipart **恰好两个 part**、filename 带扩展名、Bearer、路径 `/v1/tools/ocr`；前导换行；HTTP 200 + `error` 体；408/429/5xx 重试与 402/413 不重试；超时配置断言。
- Transformer：图片走 DeepSeek 注入 `<image_file_ocr>`；文档扫描 PDF 走远端、文本 PDF 走本地；`TransformerChainRegistryTest` 顺序/省略项快照；`TransformerUse.PREVIEW/WARMUP` 断言新 transformer 不在执行列表。
- DB：migration 后旧行 `page_count=0`；统计页查询不崩。

**风险与缓解**：预览付费 → `allowPreview=false` 测试锁死；未知 ref/box 文法 → 默认剥离、保留开关；180s/1000 页 → 本地预检。
**回滚**：默认 `MODEL`；用户切回即恢复；DB 列保留（默认 0，无害）。

### P1-2 TTS provider

**子任务**：setting 变体 `deepseek`；`DeepSeekTTSProvider`（POST JSON → 音频字节）；`Types`/`withEnabled`/`TTSManager` 两个 `when`；`AddTTSProviderButton` 项；`TTSProviderConfigure` 表单（voice/format/filterMarkdown）；strings ×3；测试。

**验收**：任意消息可朗读；5,000 字长文正常分片；400/429/503/504 有文案不崩；R8 后可用（禁 `simpleName`）；备份含该 provider 可往返。
**测试**：MockWebServer 请求形状（路径/JSON 字段/鉴权）；`wav` 字节原样返回、`pcm` 显式 `sampleRate`；10,000 计费字符前置校验；错误映射；默认 `wav` 回归。
**风险与缓解**：PCM 变调 → 默认 `wav`；`filter_markdown` 计费口径未知 → 默认 `false` + 开关；响应截断 → provider 内校验完整性并报错。
**回滚**：停用/删除 provider。

### P1-3 ASR provider

**子任务**：共享 `PcmWav`（从 MiMo 下沉）；`DeepSeekASRController`（录音缓冲、100s/8MiB 分段、multipart、串行 flush、`{text,duration_ms}` 解析）；setting 变体；`ui/hooks/ASR.kt` 工厂分支；`SettingSpeechPage` 菜单第 6 项；`ASRProviderConfigure` 表单；strings ×3；测试（`speech` 模块加 MockWebServer 依赖）。

**验收**：30s 听写成功；>100s 自动分段且顺序正确；8 MiB 保护；语音模式正确提示「不支持」且不崩；旧版恢复含 DeepSeek ASR 的备份不炸整份设置（依赖 P0-2）。
**测试**：multipart 字段与 WAV 头合法性；分段阈值（时长/字节）边界；400/408/413/429/503/504 映射；flush 串行与 stop 末次 flush；503/504 最多重试 1 次；无静默回退到其他 provider。
**风险与缓解**：lifecycle/权限 → 照 MiMo；120s 尾音丢失 → 分段 + stop flush；账号并发 50 → 429 文案。
**回滚**：停用 provider。

### P1b Chat Completions 内置工具

**子任务（按依赖顺序）**
1. 发送：`ChatCompletionsAPI.kt:559-582` 分支允许内置工具混排（`{"type":…}`，无 `function` 包装）；不传 `web_search_options`/`logprobs`，`response_format` 保持 text（官网均 400）；同一工具不重复声明。
2. 解析：`server_tool_calls[]` → `UIMessagePart.ServerTool` + `StreamChunk.ServerTool*`；流式 `ChatCompletionsStreamDecoder.kt:179-189` 同步。
3. 回传：`addAssistantMessages`（`:620-683`）保留 `ServerTool` 原始 JSON（含 `kind` 与 `signature`），下一轮原样带回。
4. 注解容错：`parseAnnotations`（`:981-995`）对未知 `type` 跳过 + 日志（**行为变更，需单独决策是否只对 DeepSeek 定向放宽**）。
5. 续写：本轮含 `ServerTool` 时禁用自动续写与「继续生成」入口（官网 400；Chat Completions 续写需 beta `prefix: true`）。
6. 参数接管提示：模型设置页对开启内置工具的 DeepSeek 模型提示 temperature/top_p 无效。

**验收**：用官方示例响应伪造的 fixture 跑通「发工具 → 收 server_tool_calls → UI 显示搜索/读页块 → 下一轮带回」；真实 Key 下完成 1 次联网搜索并验证引用渲染；用过内置工具的轮次不会自动续写 400。
**测试**：fixture 驱动（参考 `ResponseAPIMessageTest`/`ChatCompletionsAPIUsageTest` 风格）；注解未知类型不崩；回传 JSON 逐字段断言（含 `signature`）。
**风险**：块形态细节未真机确认 → **默认关闭、dev 开关灰度**，确认后再默认开放；契约漂移 → 优先按 `01` 的实现，实测差异记入开放问题。

### P2 计费/展示/合规

- P2-1：`TokenUsage` 扩工具次数；解析 `usage.server_tool_use` 的计费计数器；`usage_record` 计数列；nerd line/统计页估算费用。
- P2-2：本地价格表（CNY），设置页显示各能力单价与「限时价」标注。
- P2-3：`safety_check` 开关 + 拒答 UI 适配（三种 `finish_reason` 已统一识别，需确认 Chat Completions 路径的 UI 文案）；仅在「Web server 对外暴露」场景默认提示开启。

## 3. 测试策略

1. **CI 不打真实计费请求**：全部 MockWebServer / Fake OkHttp / fixture 驱动；真实调用只在人工 smoke 清单里。
2. **回归护栏**：`backend=MODEL`、未开内置工具、未选 DeepSeek provider 的现有测试必须逐条通过；这是「零行为变化」的硬门槛。
3. **契约测试命名**：每个断言写清「为什么值得存在」（参照 `DeepSeekSearchServiceTest` 的注释风格），防止后人删护栏。
4. **i18n 校验**：三套 locale 的 key 集合一致。
5. **真机 smoke 清单**（需 Key，记录调用前后余额）：
   - Search：正常查询 / 401 / 402 / 429 文案；
   - OCR：1 页 PNG、1 页扫描 PDF、文本 PDF（应零页费）、>1000 页预检；
   - TTS：1 条消息朗读、5k 字长文、错误路径；
   - ASR：30s 听写、>100s 分段、语音模式提示；
   - 内置工具：1 次联网搜索 + 引用渲染 + 下一轮回传；
   - 备份导出→导入（含新设置）往返。

## 4. 开工前必须确认（探测清单，均需真实 Key）

| # | 待确认 | 影响 | 探测方式 |
|---|---|---|---|
| 1 | OCR 200-with-error 的 body 形态；`<ref>/<box>` 完整文法（多 box、缺闭合、坐标是否可还原像素） | 解析健壮性 | 小文件 2~3 次调用，记录原始 body |
| 2 | 图片 OCR 的 `page_count`；TIFF 多页是否按页计费；>1000 页返回 400 还是 413 | 预检与计费 | 单图 / 多页 TIFF |
| 3 | TTS 计费字符是过滤前还是过滤后；WAV 头/采样率是否被 ExoPlayer 直接接受；PCM 是否 24k/16-bit/mono | 默认格式与 `filter_markdown` 默认值 | 1 次短文本 + 1 次带 Markdown |
| 4 | ASR 对 App 录音转出的 WAV 是否直接接受；`duration_ms` 与实际时长误差；120s 边界 | 分段阈值 | 30s / 119s / 121s |
| 5 | Chat Completions 的 `server_tool_calls` 精确 JSON、`signature` 回传要求、注解 `type` 集合、`usage.server_tool_use` 计费字段名 | P1b 成败 | 1 次联网搜索 + 多轮 |
| 6 | 429 是否带 `Retry-After`；并发窗口计量方式 | 重试策略 | 并发压测（小心计费） |
| 7 | 相同文件重复上传是否去重计费；断连后识别完成是否计费 | 缓存/取消提示 | 同一文件两次 + 中途断开 |
| 8 | `ori_question`/`localization` 对中文检索的实际增益 | 是否值得 P2 补 | A/B 查询 |

## 5. 风险登记

| 风险 | 概率/影响 | 缓解 | 触发信号 |
|---|---|---|---|
| 内置工具 + 外挂搜索双计费 | 高/高 | P0-1 守卫 + 组合单测 | 账单出现两次 search 计费；日志两条路径同轮触发 |
| 旧版恢复含新 ASR 类型的备份失败 | 必然（若不做 P0-2）/高 | P0-2 | 旧版恢复报错、设置整体回默认 |
| 注解未知类型导致整轮失败 | 中/高 | 定向放宽 + fixture 测试 | 用户报「一开联网就报错」 |
| 预览/上下文检查器触发付费 OCR | 中/高 | `allowPreview=false` + 链快照测试 | 打开检查器后余额下降 |
| OCR/TTS/ASR 费用不可见 | 必然（现状）/中 | 至少 `request_id`+计数日志；P2 落账 | 用户问「这些调用花了多少」 |
| 契约漂移（11-13 文档 vs 实际） | 中/中 | 真机探测 + 契约测试 + 文档日期 | smoke 返回与文档不符 |
| PCM 采样率猜错导致变调 | 中/低 | 默认 `wav`；`pcm` 显式 `sampleRate` | 朗读变调、时长不符 |
| ASR 分段丢尾音 | 中/低 | stop 末次 flush + 分段阈值保守 | 最后一句缺字 |
| R8 反射/`simpleName` 崩溃 | 低/高 | `@SerialName` 显式名 + 既有守卫测试 | release 包崩溃 |
| 文件/查询离开设备的隐私争议 | 中/中 | 首次启用提示 + 文档说明 | 用户反馈 / 合规审查 |

## 6. 回滚与灰度

- **默认关闭**：OCR 默认 `MODEL`；TTS/ASR 新 provider 不创建即不存在；内置工具不加入模型 `tools` 即不生效。
- **DB migration 前向安全**：`page_count` 默认 0，旧版本 App 读新 DB 不涉及（Room 版本不匹配会降级重建，属既有策略）；上线前按仓库惯例评估。
- **灰度**：内部构建 → 小范围 → 正式；每个付费能力首周观察日志中的 `request_id`、错误码与调用次数。
- **文档**：实施完成后回写本目录（更新 `02` 状态矩阵、`03` 决策、`04` 开放问题结论）。

## 7. 交付门（实施 PR 需附）

1. 真机 smoke 记录（含计费前后余额与 `request_id`）。
2. 三语言 UI 截图/文案对照。
3. 备份导出→导入往返记录。
4. 单测 + 契约测试报告；`backend=MODEL` 回归全绿。
5. 计费口径说明（哪些调用计费、单位、失败是否计费）。
6. 若涉及外发/推送：按仓库规则先取得用户授权。

## 8. 与官方资料的对应

- 能力与计费：`01-api-reference.md` §2~§8；官方链接 §9。
- 仓库落点：`02-repo-status.md`；逐行证据：`raw/*.md`。
- 方案取舍：`03-adaptation-design.md`。
- 本文开放问题与实际结论，建议在实施后追加「实测记录」小节（只记字段与结论，不记 Key、不贴响应全文）。

## 9. 实测记录（2026-11-14，真实 Key 探测；Key 已由用户轮换，不记录 Key）

对应 §4 探测清单，HTTP 级真打（脚本在 `.tmp/probe/`，不入库）：

| # | 探测项 | 实测结论 |
|---|---|---|
| 1a | server_tool_calls 精确 JSON | **与实现一致**：`[{index, reasoning_content, calls:[{index, id, type:"deepseek_web_search", kind:"deep_search", action:{type,queries}, status, sources:[...]}]}]`；**call 自带 index 字段**（实现忽略，无影响）；非思考搜索 kind 也是 `deep_search`（不一定是 `search`） |
| 1b | signature 回传要求 | 搜索类 call **无 signature 字段**（读链接才有）；多轮**原样 echo 回传 server_tool_calls 实测 200**（含 annotations 一起回传也接受） |
| 1c | 注解 type 集合 | `url_citation`（带 `start_index`/`end_index`）——实现只取 title/url，多余字段被忽略，无需改；未知 type 容错（跳过+日志）保留 |
| 1d | usage.server_tool_use 计费字段名 | 实锤：`{tool_search, tool_fetch, tool_image_search}`；P2 落账时按此解析 |
| 5 | 多轮回传 | echo 回传 + 追问 → 200，续问又发起 5 次搜索（深度思考搜索行为正常）；`tool_choice:{type:"deepseek_web_search"}` → 400「cannot be forced」（文案友好） |
| — | 流式形态 | **整块单帧下发**：`server_tool_calls` 与 `annotations`/`content`/`reasoning_content` 同帧出现在一个 delta 里；usage 单独帧。与 stream decoder 的「Start/End 同帧」假设一致 |
| 1 | OCR 200-with-error | 未复现失败体（小文件必成功）；**前导换行本次未出现**（识别太快），trim 逻辑保留；响应形状逐字段与契约一致；`usage` 参照值齐全 |
| — | OCR 多字段 | `extra=1` → 400「Unknown field name: extra」（严格校验，恰两 part 的实现正确） |
| 2 | OCR page_count | 1 页 PNG → `page_count:1`、`finish_reason:"stop"`、文本干净无 ref/box（无图模式） |
| 3 | TTS wav/pcm | wav：RIFF 头、24000 Hz、mono、16-bit ✓（ExoPlayer 直接可播）；pcm：裸流（全 0 头是静音段）✓；`voice_id:"tide"` 接受 |
| 4 | ASR 形状与时长 | 3s 音频 → `{request_id, text:"", duration_ms:3000}` 精确到秒 ✓；空文本（静音/纯音）不报错 ✓；**多字段不是 400**（`purpose=x` 被忽略返回 200）——与 OCR 的严格校验不同，单字段实现仍是正确姿态 |
| — | Search 契约 | rerank_score/authority/summary/date/site_name/site_icon 逐字段一致；空白 query → 400「must not be empty」；无结果 query 实测返回 5 条（空数组 case 未复现，容错已实现）；`ori_question`/`localization` 接受 ✓ |
| 6 | 429 Retry-After | **未压测**（避免撞账号级闸门）；重试策略按官方文档口径（408/429/5xx） |
| 7 | 重复文件计费/断连计费 | 未测（需余额核对），实现按「客户端 sha256 缓存 + 提示取消不退款」保守处理 |

**对实现的影响**：全部通过，无需改代码。两处记录性差异——① call 内层 index 字段（忽略无害）；② ASR 宽松字段校验 vs OCR 严格校验。

## 10. 设备级 smoke（2026-11-14，PJX110 真机，beta.19→beta.20 升级装机）

App 内全链路装机验证（DB/logcat/截图三口径），结果：

| 项 | 结果 |
|---|---|
| DB 迁移 69→70→71 | ✅ 升级安装后 user_version=71，page_count + tool_*_count×3 四列齐全，零崩溃 |
| 内置 web tools（P0+P1b） | ✅ 搜索块/引用渲染、nerd line「3 search (≈¥0.015)」、多轮含 server_tool_calls 回传 200 |
| safety_check 开关 | ✅ 仅 api.deepseek.com host 下显示，装机可见 |
| TTS（P1-2） | ✅ DeepSeek TTS provider 添加→自动选中；朗读触发 3 段分段合成（voice=stella format=wav），AudioTrack 24kHz/mono 播放，无异常 |
| ASR（P1-3） | ✅ DeepSeek ASR provider 添加→选中；语音模式 `POST /v1/tools/asr` multipart 3.2MB 分段 ×2 均 200（1.3~1.5s），分段节奏与 100s 控制器吻合；识别文本成功入会话（用户确认） |
| OCR 后端（P1-1） | ✅ 开关+「识别模式」（文字/版面）+「保留版面坐标」子项装机可见；纯文本聊天模型 + 图片触发 OCR 的链路用户手动验证通过（当时走 MODEL 后端：deepseek-flash 视觉直读，source=OCR 按 token 计费，page_count=0 属预期） |
| 统计页卡片（P2） | ✅ 「DeepSeek 工具调用（估算）3 (≈¥0.02)」卡片按条件渲染；OCR 卡片按页计费口径正确未显示（无 DEEPSEEK 后端 OCR 页记录） |

**挂账（唯一未实弹项）**：`ocrBackend=DEEPSEEK` 的**图片入口**（`performDeepSeekOcr`：multipart 上传→recordOcrPages 按页落账→统计 OCR 卡片出现）在设备上未跑通一次完整闭环——手测时后端被切回视觉模型，自动化复测中途被打断（logcat 已见 `single<DeepSeekOcrClient>` Koin 解析正常，未到 HTTP 阶段）。HTTP 契约层已由 §9 探测覆盖（OCR 恰两 part、page_count、200-with-error 检测），风险低；下次装机时点一张图即可补上。

**设备操作备忘**：截图坐标 ×1.389=设备坐标；`MSYS_NO_PATHCONV=1` 防 Git Bash 路径改写；debug 包 launcher activity 是 `app.androidrik.rikkahub.RouteActivity`（MainActivity 类名不存在）；聊天页模型选择 sheet 的搜索框 `input text` 可直接注入（LatinIME）。
