# 02 · 仓库现状与落点（基线：2026-11-13 HEAD 72497eeec）

> ⚠️ 历史快照：本文审计基线为 72497eeec（实施前）。2026-11-14 实施后 OCR/TTS/ASR/内置工具/计费状态已全部改变，现口径见同目录 README §3 与 04-plan-and-tests §9/§10。
> 性质：三个只读审计的**汇总结论**（原始证据与逐行清单见同目录 `raw/` 三份笔记）；本文不改代码、不提交。
> 状态口径：✅ 已适配 · ⚠️ 部分/有坑 · ❌ 未适配。
> 路径均相对仓库根，沿用本 fork 实际包名 `app.androidrik.*`。

## 0. 一句话结论

- **Search + Fetch 已适配**（2026-10-17 `786638fca`），实现质量高、14 项契约测试；但 11-13 官方文档有漂移（QPS 5→100/25、新增 `ori_question`/`localization`），代码注释与设置页文案过期，且没有价格展示/i18n。
- **OCR / TTS / ASR 三个独立 Tool API 均未适配**；三者在本仓各有一个很合适的「最近邻」可直接照抄（模型 OCR / Step TTS / MiMo ASR），改动面集中在 settings 变体 + provider + UI 穷举点。
- **聊天内置 web tools 是「抽象齐全、链路半通」**：`BuiltInTools`、`UIMessagePart.ServerTool`、`StreamChunk.ServerTool*`、思考链 UI 都已存在；但 DeepSeek 实际走的 **Chat Completions 链路完全不发/不解析/不回传**服务端工具，Responses 链路可用但 `type` 写死为 OpenAI 名字。
- **计费基础设施为零**：全仓没有页数/次数/字符/费用字段，TTS/ASR 模块没有任何用量统计；`usage_record` 只有 4 个 token 列。

## 1. Search + Fetch：✅ 已适配

### 1.1 落点

| 功能 | 文件:行 | 说明 |
|---|---|---|
| 服务注册 | `search/src/main/java/app/androidrik/search/SearchService.kt:72` | `DeepSeekOptions -> DeepSeekSearchService` |
| 选项定义 | `SearchService.kt:349-356` | `@SerialName("deepseek") data class DeepSeekOptions(id, enabled, apiKey, apiKeys)` |
| 实现主体 | `search/src/main/java/app/androidrik/search/DeepSeekSearchService.kt:59-354` | Search → `search()`，Fetch → `scrape()` |
| 端点 | 同文件 `:62-63` | `https://api.deepseek.com/v1/tools/search`、`/v1/tools/fetch` |
| 映射核心 | 同文件 `:297-304` | `text = summary`（缺则 `snippet`）、`highlights = snippet`、`publishedDate = date` |
| 参数约束 | 同文件 `:66-67,130,133` | `count` 夹紧 1~50；`need_content=false`（契约无正文字段） |
| 错误/重试 | 同文件 `:224-284` | 408/429/5xx 重试；402 明确「余额不足、不重试」；错误体取 `error.message` |
| Fetch 前置校验 | 同文件 `:206-218` | http/https、有 host、无凭据、≤8192 字符 |
| 多 Key | 同文件 `:290-291` | 沿用 `keyRoulette`，但注释明确「账号级 QPS，换 Key 无效」 |
| 设置 UI | `app/.../ui/pages/setting/SettingSearchDetailPage.kt:226-227,795-811` | Key 表单 + 价格/QPS 说明文案 |
| 新增默认项 | `app/.../ui/pages/setting/SettingSearchPage.kt:280` | 显式构造默认实例（R8 安全，不用反射） |
| 测试 | `search/src/test/java/app/androidrik/search/DeepSeekSearchServiceTest.kt` | 14 项，FakeServer 走完整 HTTP 链路 |

### 1.2 与 11-13 官网的偏差（需要知道的「旧账」）

| 项 | 本仓（2026-10-17 依据的文档） | 11-13 官网现状 | 影响 |
|---|---|---|---|
| QPS | 各 5（`DeepSeekSearchService` 类注释、commit message） | Search 100、Fetch 25（账号级、互相独立、Fetch 另有按域名限制） | **仅注释/文案过期**，代码不做 QPS 自限，行为不受影响 |
| 请求字段 | 只用 `query`/`count`/`need_content` | 另有 `ori_question`（原问题，改善查询理解）、`localization`（本地化 IP） | 可选增强；`ori_question` 对中文多轮搜索有实际价值 |
| 响应字段 | 解析了 `authority`/`rerank_score` 但未进契约 | 文档明确两者语义 | 低价值，可保持 |
| 设置页文案 | 硬编码英文 `¥5 / 1000 calls` + QPS per account（`:805-810`） | 官方 CNY 价目 | 未走 i18n 三套 locale，且未提「限时价/失败不计费」 |
| 验证边界 | commit 自述：**无真实 Key**，字段/状态码只对文档 + 假服务器；403/404/408/413/429 未逐一测；设置页未装机 | — | 上线前需一次真机 smoke（含 401/402/429） |

### 1.3 结论

无需重写。P2 补强即可：修正 QPS 注释与设置页文案、按需加 `ori_question`、i18n、价格/用量展示（与 §6 的计费体系一起做）。

## 2. OCR：❌ 未适配（现有「视觉模型 OCR」）

| 事实 | 证据 |
|---|---|
| 仅当**对话模型不支持 IMAGE 模态**时才 OCR | `app/.../data/ai/transformers/OcrTransformer.kt:66-68` |
| 只处理 `UIMessagePart.Image` 且 `url` 以 `file:` 开头 | `OcrTransformer.kt:70-73,82-84` |
| 实现方式是「配置一个视觉模型，让模型看图说话」：`settings.ocrModelId` + `settings.ocrPrompt` | `OcrTransformer.kt:33-41,108-120`；`data/datastore/Settings.kt:105-106` |
| 缓存 key = 图片 URL，TTL 3 天，单文件 `ocr_cache.json` | `OcrTransformer.kt:44-59,102,137` |
| 失败 fail-soft 为 `[ERROR, OCR failed: ...]` 文本；配置错误则上抛到聊天错误卡 | `OcrTransformer.kt:98-99,127,139-142` |
| `Document` 不经过 OCR：由 `DocumentAsPromptTransformer` 本地抽文本（pdf/docx/pptx/epub） | `DocumentAsPromptTransformer.kt:24-44,49-51,73-90` |
| **扫描 PDF 在聊天附件里静默劣化**：解析器无空文本判定，模型只看到 `---Page N:` | `document/.../PdfParser.kt:7-19`；对照工具路径已有 notice：`ReadFileWindow.kt:80-124` |
| 仓内**没有 PDF→图片**的聊天渲染管线（`PdfRenderer` 只用于 TikZ） | `tikz/PdfToBitmap.kt:5,14,26-34`；`ui/components/richtext/TikzJax.kt:56,72,228` |
| 用量记账：`UsageSource.OCR` 已存在，但表结构只有 token 列 | `data/ai/UsageRecorder.kt:50-51`；`data/db/entity/UsageRecordEntity.kt:25-55` |
| 预览/预热安全：`builtin.ocr` 已 `allowWarmup=false`；但 `builtin.document_prompt` **`allowPreview=true`** | `TransformerChainRegistry.kt:144-145`；预览会真执行链：`PromptInspector.kt:349-364` |
| 设置 UI：模型选择 / 提示词编辑 | `SettingModelPage.kt:251-259,757-809`；`SettingModelPromptPage.kt:84-92` |

缺口与方案倾向：**新增 OCR 后端选择（MODEL / DEEPSEEK）**，默认 MODEL 零行为变化；文档入口用**独立 transformer + `allowPreview=false`**，不要塞进 `builtin.document_prompt`（否则打开上下文检查器就产生按页计费）。设计与清单见 `03-adaptation-design.md` §2，取证见 `raw/ocr-audit.md`。

## 3. TTS：❌ 未适配

| 事实 | 证据 |
|---|---|
| 接口：`generateSpeech(context, providerSetting, request): Flow<AudioChunk>`；可选 `promptGuidance` | `speech/.../tts/provider/TTSProvider.kt:8-27` |
| 11 个 setting 变体；「注册表」就是 `Types` 列表 | `TTSProviderSetting.kt:308-324`（变体见 `:29-306`） |
| 分发：`TtsController → TtsSynthesizer → TTSManager`（手写 `when`，无 DI 多绑定） | `TTSManager.kt:19-69` |
| 朗读分片 160 字符、预取 2、3 次退避重试 | `TtsController.kt:43-62,319-343` |
| **播放端把流拍平**：整片收完才返回 `TTSResponse`（8 MB 上限） | `TtsSynthesizer.kt:29-48` |
| PCM 缺省按 24000 Hz 包 WAV 头；WAV 原样交 ExoPlayer | `AudioPlayer.kt:54-66,155-180` |
| 朗读入口：气泡喇叭 / 自动朗读 / 语音模式 / `text_to_speech` 工具 / 设置页测试播放 | `ChatMessageActions.kt:137-166`、`TTSAutoPlay.kt:20-39`、`VoiceMode.kt:86-108`、`TextToSpeechTool.kt:17-54`、`SettingSpeechPage.kt:906-925` |
| 设置 UI：列表 + `AddTTSProviderButton`（弹 sheet 内嵌 `TTSProviderConfigure`） | `SettingSpeechPage.kt:347-462,615-686`；`TTSProviderConfigure.kt:22-166` |
| 选中解析 / 停用不静默回退 | `SettingsExtensions.kt:76-84` + `common/.../Enableable.kt:34-38` |
| 无任何用量/计费统计（全模块 grep usage/cost/price 零命中） | — |

最近邻：**`StepTTSProvider`**（POST JSON → 二进制 → 单 `AudioChunk`，`StepTTSProvider.kt:46-118`）；PCM 采样率处理参考 `MiMoTTSProvider.kt:26,82-102`。
新增需要动约 **6 个穷举点**（见 `raw/speech-audit.md` §3.2），不动 `TtsController/TtsSynthesizer/AudioPlayer`。
注意：**WSS 增量流在现架构零收益**（播放端拍平），v1 只做整段 POST；默认 `wav`，`pcm` 需显式采样率。

## 4. ASR：❌ 未适配

| 事实 | 证据 |
|---|---|
| 接口：`state/start/stop/pauseCapture/dispose`；**没有 ASRManager** | `speech/.../asr/ASRController.kt:5-12` |
| 5 个变体：OpenAIRealtime / DashScope / Volcengine（WSS）、MiMo / Step（HTTP 一次性/SSE） | `ASRProviderSetting.kt:32-192` |
| **控制器在两处各自 `when` 手工 new** | `ui/hooks/ASR.kt:133-160`；`ui/pages/chat/VoiceMode.kt:123-138` |
| 新增类型菜单是 **5 个硬编码 `DropdownMenuItem`，不读 `Types`** | `SettingSpeechPage.kt:688-786`（`:706-760`） |
| 语音模式能力判据：只有三个 WSS 类支持服务端 VAD | `ASRProviderSetting.kt:207-209`；`VoiceMode.kt:69-79`（不支持会提示，不崩） |
| 最近邻 **MiMo**：录音缓冲 + 分段（6 MB/时间阈值）+ `pcm16ToWav` + HTTP 一次性 + 串行 flush | `MiMoASRController.kt:44-46,72-73,136-194,196-203,309-338` |
| speech 模块**没有 multipart 代码**；范例在 `ai`：`OpenAIProvider.kt:318-355` | — |
| ASR 层仅 **Step** 有重试（`MAX_RETRY=3` + 300ms 线性退避），MiMo 与三个 WSS 类无重试（TTS 才有 3 次退避） | `StepASRController.kt:53,282-303`；对照 TTS：`TtsController.kt:319-343` |
| 无任何用量/计费统计 | — |
| ⚠️ 设置兼容：`SettingsCompatDecoder` **只降级 `providers`/`ttsProviders`**，未处理 `asrProviders` | `data/datastore/migration/SettingsCompatDecoder.kt:42-63` |
| 设置 UI / i18n / 测试 | `ASRProviderConfigure.kt:34-65`；`setting_asr_configure_*`；`ASRProviderSettingStepTest.kt` |

风险要点：旧版 App 恢复含 `"type":"deepseek"` 的 ASR 备份会**整份设置恢复失败**（TTS 侧已有容错）。这是本次适配最值得顺手修的结构性缺陷（见 `04-plan-and-tests.md` P0）。

## 5. 聊天内置 web tools：⚠️ 部分（抽象齐全，链路半通）

### 5.1 已有抽象（可直接复用，零迁移）

| 层 | 类型/位置 |
|---|---|
| 模型级开关 | `Model.tools: Set<BuiltInTools>`（`ai/.../provider/Model.kt:34`）；`BuiltInTools` 现有 `Search`/`UrlContext`/`ImageGeneration`（`:77-92`） |
| 服务端工具 part | `UIMessagePart.ServerTool(toolCallId, toolName, input, output, status, metadata)`（`ai/.../ui/UIMessagePart.kt:161-169`） |
| 流事件 | `StreamChunk.ServerToolStart/InputDelta/InputEnd/End`（`ai/.../ui/StreamChunk.kt:73-101`） |
| 折叠与 UI | `ai/.../ui/StreamChunkHandler.kt:158-220`；思考链 `ui/components/message/ChatMessageCot.kt:17-31,85` |
| 模型编辑表单 | `ModelSettingsForm.kt:450-490` |

### 5.2 DeepSeek 实际链路（Chat Completions）缺口

| 缺口 | 证据 |
|---|---|
| 出厂 DeepSeek 是 `ProviderSetting.OpenAI` + `baseUrl=.../v1`，**未开 Responses** → 默认走 Chat Completions | `data/datastore/DefaultProviders.kt:111-122` |
| 只发送 function tools，**不发送内置工具** | `ai/.../providers/openai/ChatCompletionsAPI.kt:559-582` |
| **不解析** `message.server_tool_calls`（解析只在 Responses/Claude 解码器） | `ChatCompletionsAPI.kt:898-978`；对照 `ResponseApiStreamDecoder.kt:140-190` |
| 多轮回传时 `ServerTool` part **被静默丢弃** | `ChatCompletionsAPI.kt:620-683` |
| 注解未知 `type` **硬 error`（会把整轮打成失败） | `ChatCompletionsAPI.kt:981-995`；`ChatCompletionsStreamDecoder.kt:179-189` |
| Responses 链路已闭环，但内置工具 `type` 写死（`web_search`、`image_generation`；`UrlContext` 是静默空实现） | `ResponseAPI.kt:476-486,582-587,738-769,1036-1063` |
| Messages：用户自建 Claude 型 provider 可连 `/anthropic`，但只解码、不回传、不声明 | `ClaudeProvider.kt:326-334,632-645` |

### 5.3 双计费守卫（最高优先）

| 事实 | 证据 |
|---|---|
| 「已有原生搜索 ⇒ 不注册外挂搜索」只认 `BuiltInTools.Search` 一个值 | `ChatToolFactory.kt:50-52` |
| 外挂搜索注册入口 | `ChatToolFactory.kt:277-278` → `SearchTools.kt:26-127` |
| 搜索 UI 的 `BuiltInTools.Search` 判定共 5 处硬编码；另有**模型 ID 白名单**（`GEMINI_SERIES`/`gpt-` 前缀）判定「该模型支持内置搜索」 | `ui/components/ai/SearchPicker.kt:77,92,170,355,368`；白名单 `:170` 附近 |
| 枚举同步点 | `WebSettingsDto.kt:273-277`、`SettingsRoutes.kt:198-203`、`UpstreamConversationConfig.kt:41`、`ModelSettingsForm.kt:470-486` |

不扩守卫则「助手开联网搜索 + 模型开 `deepseek_web_search`」会同时走两条路，token 之外**双重计费**。

### 5.4 用量/费用

| 事实 | 证据 |
|---|---|
| `TokenUsage` 只有 4 个 token 字段 | `ai/.../core/Usage.kt:6-11` |
| usage 解析只挑 4 个字段，`server_tool_use` 计数器无人读取 | `ChatCompletionsStreamDecoder.kt:192-204`；`ChatCompletionsAPI.kt:1001-1012` |
| `usage_record` 无费用/工具次数列 | `UsageRecordEntity.kt:25-52` |
| `models.dev` 的 `cost` 从未声明在 DTO 上，JSON 解码即被丢弃（slim 只裁 modalities/limit） | `ModelsDevCatalog.kt:77-95` |
| 全仓无价格表（grep pricing/cost 0 命中） | — |

## 6. 横向现状与约束

| 主题 | 现状 |
|---|---|
| 设置持久化 | 整体 JSON 单 key + typed prefs；新增**带默认值**的 `@Serializable` 字段零迁移（`Settings.kt:49,55-56`） |
| 设置兼容降级 | 只对 `providers`/`ttsProviders` 做未知子类丢弃（`SettingsCompatDecoder.kt:42-63`）→ ASR 缺同类保护 |
| i18n | 仅 en / zh / zh-rTW 三套（`app/build.gradle.kts:121-125`）；TTS/ASR/OCR 新键各补 3 份 |
| 隐私 | 现状 PDF/DOCX **不出设备**（本地 MuPDF 解析）；接 DeepSeek OCR/文件输入后改变，需要显式告知 |
| 公开服务合规 | 内置 Ktor Web server 可把聊天暴露到 LAN/远端（`web/.../Entry.kt:15-33`）；官方要求对外提供服务时开启 `safety_check` 并履行内容审核/大模型登记/AI 标识义务 |
| 计费基础设施 | 无页数/次数/字符/费用记账；TTS/ASR 无用量；聊天侧 token 统计只覆盖 token |
| 测试 | search/ai/speech/app 均有单测传统；`speech` 模块缺 MockWebServer 依赖，HTTP 级测试需加 `testImplementation` |

## 7. 现状矩阵

| 能力 | 官方入口 | 现状 | 与现有实现的冲突点 | 建议 |
|---|---|---|---|---|
| Search | `POST /v1/tools/search` | ✅ 已适配 | 与内置 `deepseek_web_search` 二选一，否则双计费 | P2 补强 |
| Fetch | `POST /v1/tools/fetch` | ✅ 已适配（`scrape_web`） | 同上（对 `deepseek_read_url`） | P2 补强 |
| OCR | `POST /v1/tools/ocr` | ❌ | 现有模型 OCR；文档入口会与本地解析重复 | P1 |
| TTS | `POST /v1/tools/tts` / WSS | ❌ | 现架构拍平流，WSS 无收益 | P1 |
| ASR | `POST /v1/tools/asr` | ❌ | 语音模式不支持（无 VAD/流式） | P1 |
| 聊天内置 web tools | `deepseek_web_search` / `deepseek_read_url` | ⚠️ 抽象有、链路缺 | 与 App 外挂搜索互斥/双计费 | P0 守卫 + P1 收发 |
| 文件输入（Files API） | `POST /files` + `file_id` | ❌ | 与本地 `DocumentAsPromptTransformer` 重复解析 | 首版不做 |
| 内容安全 | `safety_check` | ❌ | 公开服场景合规缺口 | P2 |
