# speech 模块适配审计 · DeepSeek TTS / ASR Tool APIs

> 性质：**只读审计 + 适配素材**（草稿质量，供实施阶段对照）。
> 审计对象：`speech` 模块 + `app` 侧消费链路；审计基线 = 工作区当前 HEAD（2026-11-13）。
> 本文**不含实现代码**，不修改任何源文件；行号均来自本次实际读取。
> DeepSeek 侧契约以 Lead 的 `01-api-reference.md`（官方文档摘编）为准；本文只讨论"怎么接进现有 speech 架构"。
> 标注约定：**[事实]** = 有 `file:line` 证据；**[未知]** = 文档未给、需向服务商核实或真机验证；**[建议]** = 设计取舍，非现状。

---

## 0. 现状一句话

`speech` 模块是**两套彼此独立的反射式（`when` 穷举）架构**：

- **TTS**：`TTSProvider<T>` 接口 + sealed `TTSProviderSetting` + `TTSManager` 手写 `when` 分发（无 Koin 多绑定、无注册表），app 侧 `TtsController` 做「分片 → 预取 → 逐段合成 → ExoPlayer 播放」。
- **ASR**：`ASRController` 接口（state / start / stop / dispose）+ sealed `ASRProviderSetting`，**没有 Manager**，控制器由 app 侧两处各自 `when` 手工 new（`ui/hooks/ASR.kt` 与 `ui/pages/chat/VoiceMode.kt` 各一份）。

因此新增一个 provider 的"成本"主要不在协议实现，而在**穷举点数量**：TTS 约 6 处、ASR 约 8 处（详见 §3.2/§4.2）。两类 provider 都**没有任何用量/计费统计**（§3.7）。

---

## 1. TTS 现状

### 1.1 接口与数据模型

| 角色 | 证据 |
|---|---|
| Provider 接口 `fun generateSpeech(context, providerSetting, request): Flow<AudioChunk>` | `speech/src/main/java/app/androidrik/tts/provider/TTSProvider.kt:8-13` |
| 可选 `promptGuidance`（给 `text_to_speech` 工具注入 system prompt 的语气标记说明，默认空） | 同上 `:15-27` |
| 请求体只有 `text`；`AudioFormat` 枚举 MP3/WAV/OGG/AAC/OPUS/PCM | `speech/src/main/java/app/androidrik/tts/model/TTSRequest.kt:5-18` |
| 响应/流块 `TTSResponse`、`AudioChunk(data, format, sampleRate, isLast, metadata)` | `speech/src/main/java/app/androidrik/tts/model/TTSResponse.kt:5-11`、`:38-45` |
| 播放状态 `PlaybackStatus{Idle,Buffering,Playing,Paused,Ended,Error}`、`PlaybackState` | `speech/src/main/java/app/androidrik/tts/model/PlaybackState.kt:6-23` |
| 错误类型 `TTSProviderException(statusCode)`，`isRetryable = 408/429/5xx` | `speech/src/main/java/app/androidrik/tts/provider/TTSProviderException.kt:6-13` |

### 1.2 Setting 变体与"注册"

`sealed class TTSProviderSetting : Enableable`，11 个变体；每个变体都是 `data class(... override var id/name/enabled, apiKey, baseUrl, model, voice...)`：

- 变体清单（含 `@SerialName`）：`OpenAI "openai"` `TTSProviderSetting.kt:29-49`、`Gemini "gemini"` `:51-71`、`SystemTTS "system"` `:73-91`、`MiniMax "minimax"` `:93-114`、`Qwen "qwen"` `:116-137`、`Groq "groq"` `:139-159`、`XAI "xai"` `:161-181`、`MiMo "mimo"` `:183-204`、`ElevenLabs "elevenlabs"` `:206-228`、`Step "step"` `:245-277`、`FishAudio "fish-audio"` `:279-306`
- **"注册表"就是 `companion object { val Types = listOf(...class) }`**：`TTSProviderSetting.kt:308-324`（设置页下拉菜单与测试断言都读它）
- `withEnabled` 必须逐子类型分发：`TTSProviderSetting.kt:333-349`
- 默认内置项：系统 TTS + 一个 AiHubMix(OpenAI 兼容) 条目 `app/.../data/datastore/SettingsDefaults.kt:25-38`；墓碑式回填 `:64-71`；"系统 TTS 兜底不变式" `:85-92`
- 持久化：`Settings.ttsProviders` / `selectedTTSProviderId` / `defaultTTSPlaybackSpeed` `app/.../data/datastore/Settings.kt:170-172`；pref key `tts_providers` / `removed_tts_providers` / `selected_tts_provider` / `default_tts_playback_speed` `app/.../data/datastore/PreferencesStore.kt:372-377`，读 `:584-592`，写 `:934-938`
- 选中解析：`Settings.getSelectedTTSProvider()` → `Enableable.resolveSelected`（停用不静默回退）`app/.../data/datastore/SettingsExtensions.kt:76-84` + `common/.../common/settings/Enableable.kt:34-38`

### 1.3 Manager 与 controller 层

```
TtsController (speech)  ──> TtsSynthesizer ──> TTSManager.generateSpeech ──> XxxTTSProvider.generateSpeech
   │                            └─ 把 Flow<AudioChunk> 收成单个 TTSResponse（8MB 上限）
   └─> AudioPlayer (media3 ExoPlayer, PCM→WAV 包装)
```

| 组件 | 证据 | 关键行为 |
|---|---|---|
| `TTSManager(context)`：11 个 provider 字段 + 两个 `when`（`generateSpeech` / `getPromptGuidance`） | `speech/.../tts/provider/TTSManager.kt:19-49`、`:51-69` | 无 DI 多绑定；`when` 非穷举（无 else，靠 sealed 保证） |
| `TtsController`：`TextChunker(maxChunkLength = 160)`、预取窗口 `prefetchCount = 2`、片间 120ms、重试上限 3 次（指数退避 500ms 起） | `speech/.../tts/controller/TtsController.kt:43-62`、`:319-343` | 重试条件 `IOException` 或 `TTSProviderException.isRetryable` `:348-350` |
| `speak(text, flush)`：分片入队 → worker 逐片 `awaitOrCreate` → `audio.play` | `TtsController.kt:111-146`、`:221-283` | 每片一次独立 HTTP 请求 |
| `TtsSynthesizer.synthesize`：**把整片音频全量收集成单 `TTSResponse`**，上限 8MB | `speech/.../tts/controller/TtsSynthesizer.kt:20-27`、`:29-48`、`:52` | **关键**：即使 provider 流式吐块，播放端也等整片收完 ⇒ WS 增量流在本架构里拿不到"边收边播"收益 |
| `AudioPlayer`：ExoPlayer + `ByteArrayDataSource`；**`format == PCM` 时先包 WAV 头**，sampleRate 缺省 24000 | `speech/.../tts/controller/AudioPlayer.kt:54-58`、`:155-180` | 非 PCM（如 WAV/MP3）原样喂给 ExoPlayer；PCM 默认 `channels=1, bitsPerSample=16`（`:155-160`） |
| `TextChunker.split`：按 `\n\n` + 中英标点切，`maxChunkLength` 默认 150（controller 用 160） | `speech/.../tts/controller/TextChunker.kt:6-8`、`:9-37`；`TtsChunk` `:40-44` | 单片远小于 10k 字符 |

### 1.4 谁在"朗读回答"（callsites，全部经 `CustomTtsState.speak`）

| 入口 | 证据 | 说明 |
|---|---|---|
| 气泡上的喇叭按钮 | `app/.../ui/components/message/ChatMessageActions.kt:137-166` | 助手消息才显示；先按 `ttsOnlyReadQuoted` / `ttsOnlyReadOutsideBrackets` 过滤 |
| 生成完自动朗读 | `app/.../ui/pages/chat/TTSAutoPlay.kt:20-39` | `displaySetting.autoPlayTTSAfterGeneration` |
| 语音模式（ASR 对话）播报 | `app/.../ui/pages/chat/VoiceMode.kt:86-108` | 播完等 `isSpeaking=false` 再继续听 |
| AI 工具 `text_to_speech` | `data/ai/tools/local/TextToSpeechTool.kt:17-54` → 发 `AppEvent.Speak`；`RouteActivity.kt:376` 消费后 `tts.speak(...)`；工具卡片按钮 `ui/components/message/tools/BuiltinToolUIs.kt:427` | `systemPrompt` 注入 `TTSManager.getPromptGuidance` `TextToSpeechTool.kt:29-34` |
| 设置页"测试播放" | `SettingSpeechPage.kt:906-925`（`R.string.setting_tts_page_test_text`） | 供联调验证 |
| 状态注入 | `RouteActivity.kt:370-371`（`rememberCustomTtsState`/`rememberCustomAsrState`）、`:431-432` CompositionLocal 注入 | 进程级单例 |
| Hook 实现 | `ui/hooks/TTS.kt:36-68`（随 settings 变化 `setProvider`）、`:123-179`（`CustomTtsStateImpl`，`speak` 前 `stripMarkdown` `:145-148`） | `TTSManager` 从 Koin `inject()` `:128`；`TtsController` 懒建 `:129` |

### 1.5 设置 UI

- 页面：`app/src/main/java/app/androidrik/rikkahub/ui/pages/setting/SettingSpeechPage.kt`（1096 行），两 Tab（`speech_tab_tts`/`speech_tab_asr`）`:164-210`。
- TTS 列表 `TTSProviderList` `:347-462`：可拖拽排序（松手才落盘）、选中/启停/编辑/删除、停用兜底提示、内置墓碑。
- TTS 单项 `TTSProviderItem` `:806-967`：类型副标题是大 `when` 硬编码字符串（`:858-870`）、启停 `Switch`、**测试播放按钮 `:906-925`**、下拉删除（系统 TTS 禁止删）。
- 新增 TTS provider：`AddTTSProviderButton` `:615-686`，默认 `TTSProviderSetting.SystemTTS()`，弹 ModalBottomSheet 内嵌 `TTSProviderConfigure`。
- 类型选择与表单：`ui/pages/setting/components/TTSProviderConfigure.kt`

  - 类型下拉用 `TTSProviderSetting.Types` `:32`，显示名 `when`（**禁止 `simpleName`**）`:38-71`
  - 切换到某类型时**重建默认 setting（保留 id）**`:72-132`
  - 表单分发 `when` `:152-164`
  - 各 provider 表单项：如 `StepTTSConfiguration` `:996-1204`（apiKey `:1000-1020` / baseUrl `:1021-1029` / model 下拉 `:1031-1057` / voice 下拉 `:1058-1103` / responseFormat 下拉 `:1112-1130`，`mp3|wav|pcm|opus|flac`）
- 源码守卫测试：**`TTSProviderConfigure.kt` 里禁止出现 `KClass.simpleName`**（R8 混淆后 release 会显示 `it9`）`app/src/test/java/app/androidrik/rikkahub/ui/pages/setting/ProviderTypeLabelSourceGuardTest.kt:48-51`

### 1.6 i18n 命名

- 文件：`app/src/main/res/values/strings.xml`（en，默认）、`values-zh`、`values-zh-rTW`（3 个 locale 都要补）。
- 家族前缀（**[事实]** 抽样统计：`setting_tts_page_*` + `setting_asr_*` + `speech_tab_*` + `asr_error_*` 共 113 条）：
  - 通用 `setting_tts_page_api_key` / `..._base_url` / `..._model` / `..._voice` / `..._provider_type`（`values/strings.xml:1586-1587` 等）
  - **provider 专属**：`tts_config_<provider>_*`（例：`tts_config_step_api_key_desc` `values/strings.xml:2897`）；ASR 侧为 `setting_asr_configure_<provider>_*`（例：`setting_asr_configure_step_api_key_desc` `:1043`，通用 `setting_asr_configure_base_url` `:1034`、`..._segment_duration` `:1036`）
  - 页面/错误：`speech_page_title` `:1650`、`speech_tab_tts/asr` `:1651-1652`、`asr_error_not_configured` / `asr_error_audio_focus` `:2019-2020`

### 1.7 测试现状

| 文件 | 内容 |
|---|---|
| `speech/src/test/java/app/androidrik/tts/provider/TTSProviderSettingStepTest.kt` | 默认值、`Types` 包含该类（`:24-28`）、`copyProvider` 保留字段（模板可直接套） |
| `TTSProviderSettingMiMoTest.kt`、`TTSProviderSettingElevenLabsTest.kt`、`TTSProviderSettingFishAudioTest.kt` | 同上模式 |
| `speech/src/test/java/app/androidrik/tts/provider/providers/MiMoTTSProviderTest.kt` | **纯函数化**的 SSE 解析器 `MiMoSseProcessor` 单测（不联网）：`process(SseEvent)` → `AudioChunk`/异常（`:14-65`） |
| app 侧 `data/datastore/TtsProviderEnabledTest.kt` | 启停 + 选中解析不变式 |
| app 侧 `ProviderTypeLabelSourceGuardTest.kt` | 源码级 R8 守卫 |

`speech/build.gradle.kts` 测试依赖只有 `libs.junit`（无 MockWebServer）；`mockwebserver3-junit4` 只在 `ai` 模块（`ai/build.gradle.kts:59`，坐标 `gradle/libs.versions.toml:129`）⇒ **想在 speech 里做 HTTP 级单测，需加该 `testImplementation`**（或沿用 MiMo 的"把解析/构造抽成纯函数"风格）。

---

## 2. ASR 现状

### 2.1 接口与状态

| 角色 | 证据 |
|---|---|
| `interface ASRController { state: StateFlow<ASRState>; start(onTranscriptChange); stop(); pauseCapture(); dispose() }` | `speech/src/main/java/app/androidrik/asr/ASRController.kt:5-12` |
| `ASRStatus{Idle,Connecting,Listening,Stopping,Error}`；`ASRState(status,isAvailable,transcript,errorMessage,amplitudes,voiceTurn)` | `speech/.../asr/ASRState.kt:3-21` |
| `ASRVoiceTurn`（服务端 VAD 语句边界） | `speech/.../asr/ASRVoiceTurn.kt:1-21` |
| 振幅工具 `calculateRmsAmplitude` / `appendAmplitude`（32 点环形） | `speech/.../asr/AudioAmplitude.kt:10-32` |

**没有 `ASRManager`**：控制器实例化在 app 侧两个地方各写一遍（见 §2.4）。

### 2.2 Setting 变体与"注册"

`sealed class ASRProviderSetting : Enableable`，5 个变体（**全部带 `websocketUrl` 或 `baseUrl` 之一**）：

| 变体 | `@SerialName` | 端点形态 | 证据 |
|---|---|---|---|
| `OpenAIRealtime` | `openai_realtime` | WSS（服务端 VAD，`sampleRate=24000`，language/prompt/vadThreshold/prefixPadding/silenceDuration） | `ASRProviderSetting.kt:32-57` |
| `DashScope` | `dashscope` | WSS（`sampleRate=16000`） | `:59-82` |
| `Volcengine` | `volcengine` | WSS（`resourceId`，`VOLCENGINE_ASR_WEBSOCKET_URL` `:8`） | `:84-105` |
| `MiMo` | `mimo` | **HTTP 一次性**（chat/completions + base64 WAV，`segmentDurationSec=30`） | `:119-145` |
| `Step` | `step` | **HTTP + SSE**（`/v1/audio/asr/sse`，PCM base64） | `:162-192` |

- 注册表：`ASRProviderSetting.Types` `:194-204`（**添加新变体必须登记**，否则...实际上只有测试断言在用，UI 未使用，见 §4.2）
- `withEnabled` 逐子类型分发 `:217-227`
- **能力判据**：`val ASRProviderSetting.supportsServerVadVoiceMode get() = OpenAIRealtime || DashScope || Volcengine` `:207-209`（**[事实]** 注释明确：描述的是"我们适配器"而非厂商全 API）
- 持久化：`Settings.asrProviders` / `selectedASRProviderId: Uuid?` `Settings.kt:173-174`；pref key `asr_providers` / `selected_asr_provider` `PreferencesStore.kt:379-381`，读 `:593-596`，写 `:939-942`；默认值 `emptyList()`（无内置 ASR）
- 选中解析：`Settings.getSelectedASRProvider()` `SettingsExtensions.kt:86-93`

### 2.3 两类实现（决定 DeepSeek 的最近邻）

| 维度 | WSS 类（OpenAIRealtime / DashScope / Volcengine） | HTTP 分段类（MiMo / Step） |
|---|---|---|
| 连接 | `httpClient.newWebSocket` + `WebSocketListener`（如 `DashScopeASRController.kt:88-108`） | 无连接阶段，`start()` 直接置 `Listening`（`MiMoASRController.kt:99-107`） |
| 麦克风 | `AudioRecord(VOICE_COMMUNICATION, mono, PCM16)` 边录边发 | 同一 `AudioRecord`，但**先写 `ByteArrayOutputStream` 缓冲**（`MiMoASRController.kt:136-194`） |
| 出字 | 服务端 VAD 事件边出 partial（`ASRVoiceTurn`） | **分段 flush 后才出整段文本，无 partial**（`MiMoASRController.kt:272-284`，`publishTranscript` 用 `joinToString(" ")` 拼接） |
| 段上限 | 无 | MiMo `MAX_SEGMENT_BYTES = 6MiB`（`:44-46`），另有 `segmentDurationSec` 时间阈值 |
| PCM→WAV | 不需要 | **`pcm16ToWav()` 私有实现**（`MiMoASRController.kt:309-338`）；Step 则直接 base64 PCM（`ASRProviderSetting.kt:147-161` 注释） |
| 并发/顺序 | 单连接 | `flushJob` 串行化，避免乱序（`MiMoASRController.kt:72-73`、`:196-203`） |
| 错误 | `setError` | `setError`（`MiMoASRController.kt:286-293`）；HTTP 非 2xx → `IOException`（`:254-262`） |

### 2.4 消费路径（两条，且各有一份控制器工厂）

1. **听写（输入框麦克风）**：`ui/components/ai/ChatInput.kt:561-583`——点麦克风：`Listening` → `asr.stop()`；否则 `asrBaseText = 当前文本`，`asr.start { transcript -> state.setMessageText(asrBaseText + " " + transcript) }`。即 **`onTranscriptChange` 直接改写输入框**（流式 partial 会实时回填；HTTP 分段类则每段回来时整体回填一次）。错误经 toast 提示 `:317-322`。
   工厂：`ui/hooks/ASR.kt:133-160`（`createController`，`when(provider)` 逐个 `if apiKey.isBlank() return null`）+ 音频焦点获取/释放 `:95-118`。
2. **语音模式（免手按连续对话）**：`ui/pages/chat/VoiceMode.kt:68-110`，前置校验 `:69-79`：`provider == null` → `chat_page_voice_configure_asr`；`!supportsServerVadVoiceMode` → `chat_page_voice_unsupported_asr`（`:71`）。工厂：`createVoiceAsr` `:123-138`，**`else -> error(...)`（只支持三个 WSS 类）**，并在外面包一层音频焦点的 `ASRController by delegate`。
   会话管理：`ui/pages/chat/VoiceSessionController.kt`（播放 TTS 时 `pauseCapture` 保活）；测试 `app/src/test/java/app/androidrik/rikkahub/ui/pages/chat/VoiceSessionControllerTest.kt:30` 有 fake controller。
3. 状态注入：`RouteActivity.kt:371`、`:432`；`LocalASRState`。

### 2.5 ASR 设置 UI / i18n / 测试

- 列表：`ASRProviderList` `SettingSpeechPage.kt:511-613`；单项 `ASRProviderItem` `:968` 起（启停/选中/编辑/删除，停用不静默回退的注释在 `:574-579`）。
- 新增：**`AddASRProviderButton` `:688-786`，类型是 5 个硬编码 `DropdownMenuItem`（`:706-760`），没有读 `ASRProviderSetting.Types`** → 与 TTS 不一致，新增 ASR 类型**必须手改这里**。
- 配置表单：`ui/pages/setting/components/ASRProviderConfigure.kt`
  - 类型是**只读文本框** `when` 硬编码 `:34-40`（不是下拉）
  - 表单分发 `when` `:59-65`；各配置函数：`:70` OpenAIRealtime / `:185` DashScope / `:271` Volcengine / `:325` MiMo / `:411` Step
  - 通用字段：apiKey、name；MiMo/Step 额外 baseUrl / model / language / sampleRate / `segmentDurationSec` / itn / hotwords 等
- i18n：`setting_asr_configure_*`（通用 `:1034-1036`，provider 专属 `setting_asr_configure_step_*` `:1043-1050`）。
- 测试：
  - `speech/src/test/java/app/androidrik/asr/ASRProviderSettingStepTest.kt`（默认值 / `Types` 登记 `:24-27` / copyProvider）
  - `ASRProviderSettingMiMoTest.kt`、`ASRVoiceTurnTest.kt`、`providers/VolcengineASRProtocolTest.kt`（**二进制协议编解码纯函数测试**，118 行）
  - app 侧 `data/datastore/AsrProviderEnabledTest.kt`

---

## 3. DeepSeek TTS 适配判据

DeepSeek TTS 契约（Lead 摘要）：`POST https://api.deepseek.com/v1/tools/tts`，JSON `{text, voice_id: stella|tide, format: wav|pcm, filter_markdown}`，Bearer 鉴权，音频流式返回；最多 10000 billed chars/请求；WSS 增量文本版另算（30min 上限、50 并发/账号）；计费按 1 万字符 $0.45 / 限时 $0.225。

### 3.1 最近邻：**`StepTTSProvider`（POST JSON 一次性返回二进制）为主，`MiMoTTSProvider`（PCM + 显式 sampleRate）为辅**

- Step：`POST {baseUrl}/v1/audio/speech`，JSON body 显式字段、`Accept: application/octet-stream`、非 2xx 读 body 抛 `TTSProviderException(statusCode)`、`responseFormat` 字符串 → `AudioFormat` 映射、单 `AudioChunk(isLast=true)` —— `StepTTSProvider.kt:46-118`。**DeepSeek 的 `/v1/tools/tts` 同形态**（区别：url 路径、字段名 `text` 而非 `input`、`voice_id` 而非 `voice`、`format` 而非 `responseFormat`、无 model/speed/sampleRate 字段）。
- MiMo：PCM 流必须带 `sampleRate`（`MiMoTTSProvider.kt:26`、`:82-102` 用常量 24000）——DeepSeek 若选 `pcm`，**本仓 `TtsSynthesizer` 收集时只取第一个 chunk 的 sampleRate**（`TtsSynthesizer.kt:33-35`），若 provider 不给则 `AudioPlayer` 回退 24000（`AudioPlayer.kt:57`）⇒ 采样率猜错会变调/变速。**[未知]**：DeepSeek PCM 采样率与声道数文档未给 ⇒ 建议 TTS 默认 `wav`，`pcm` 作为高级选项并暴露 sampleRate。

### 3.2 需要新增/改动的文件清单（TTS）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `speech/src/main/java/app/androidrik/tts/provider/TTSProviderSetting.kt` | 新增 `@Serializable @SerialName("deepseek") data class DeepSeek(...) : TTSProviderSetting()`（**字段建议见 §3.3**）；`Types` 加入 `:308-324`；`withEnabled` 加分支 `:333-349` |
| 2 | `speech/src/main/java/app/androidrik/tts/provider/providers/DeepSeekTTSProvider.kt`（新文件） | `TTSProvider<TTSProviderSetting.DeepSeek>`；OkHttp 独立 client（Step 给 120s readTimeout `:41-44`）；body 构造 → POST `/v1/tools/tts`；非 2xx → `TTSProviderException`；format 映射；`AudioChunk(isLast=true)` |
| 3 | `speech/src/main/java/app/androidrik/tts/provider/TTSManager.kt` | 字段 + 两个 `when` 分支 `:19-31`、`:32-49`、`:55-69` |
| 4 | `app/.../ui/pages/setting/components/TTSProviderConfigure.kt` | 类型显示名 `:38-71`、切换重建默认 setting `:72-132`、表单分发 `:152-164`、新增 `DeepSeekTTSConfiguration`（apiKey/baseUrl/voice 下拉/format 下拉/filter_markdown 开关）；**禁止 `simpleName`** |
| 5 | `app/.../ui/pages/setting/SettingSpeechPage.kt` | `TTSProviderItem` 的类型副标题 `when`（`:858-870`）加分支 |
| 6 | `app/src/main/res/values/strings.xml` + `values-zh` + `values-zh-rTW` | `tts_config_deepseek_*`（按 `tts_config_step_*` 命名） |
| 7 | `speech/src/test/java/app/androidrik/tts/provider/TTSProviderSettingDeepSeekTest.kt`（新） | 照 `TTSProviderSettingStepTest` 模板 |
| 8 | （可选）`speech/build.gradle.kts` | 若做 HTTP 级测试：加 `testImplementation(libs.okhttp.mockwebserver)` |
| 9 | （可选）`app/.../data/datastore/SettingsDefaults.kt` | 若要做"内置 DeepSeek 入口"（当前内置项只有 SystemTTS/OpenAI-AiHubMix）：`DEFAULT_TTS_PROVIDERS` `:26-38` + `isBuiltInTtsProvider` `:74-75` + 墓碑回填 `:64-71` |

**不需要改**：`TtsController` / `TtsSynthesizer` / `AudioPlayer` / `TextChunker`（协议无关）；持久化 key（`ttsProviders` 整体 JSON，新子类自动进 `@SerialName("deepseek")` 判别字段；`JsonInstant` 未改 `classDiscriminator` ⇒ 默认 `"type"`，`utils/Json.kt:9-18`）。

### 3.3 建议的 settings 字段（[建议]，非现状）

```kotlin
@Serializable @SerialName("deepseek")
data class DeepSeek(
    override var id: Uuid = Uuid.random(),
    override var name: String = "DeepSeek TTS",
    override var enabled: Boolean = true,
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val voiceId: String = "stella",        // stella | tide（UI 下拉，避免自由文本 400）
    val format: String = "wav",            // wav | pcm（默认 wav：播放端零转换）
    val filterMarkdown: Boolean = false,   // 客户端已 stripMarkdown，见 §3.4
    val pcmSampleRate: Int = 24000,        // 仅 format=pcm 时用于 AudioPlayer 包装，**[未知] 真实值待核实**
) : TTSProviderSetting()
```

要点：
- `voiceId` / `format` 用下拉而非文本（参考 `StepTTSConfiguration` 的 model/voice/format 三处 `SelectTextField`，`TTSProviderConfigure.kt:1031-1130`）。
- apiKey **不能复用聊天 provider 的 key**：本仓 DeepSeek 聊天走的是通用 OpenAI 兼容 provider（`ai/.../registry/ModelRegistry.kt` 只有模型 id 规则，无 `ProviderSetting.DeepSeek`），与现有 11 个 TTS provider 一样是独立 `apiKey` 字段。
- **不要覆盖 `promptGuidance`**：DeepSeek 文档未给情绪/语气标记语法，保持默认空（`TTSProvider.kt:26-27`），否则往 system prompt 注入无效指令、白烧 token。

### 3.4 `filter_markdown` 怎么填（[未知] + [建议]）

- 现状：朗读文本在 `CustomTtsStateImpl.speak` 里已 `stripMarkdown()`（`ui/hooks/TTS.kt:145-148`），`text_to_speech` 工具也要求模型输出纯文本（`TextToSpeechTool.kt:23-28`）。
- 因此服务端再过滤是**冗余但无害**；唯一实质差异是**计费口径**（按 billed chars）：若服务端 `filter_markdown=true` 会先剔除 markdown 字符再计费，则 `true` 更省钱。**文档没写计费是否以过滤后为准** ⇒ 列为开放问题 Q1，实现默认 `false`（行为可预测），设置页给出开关。

### 3.5 WSS 增量流：**v1 明确不做**

- `TtsSynthesizer` 把整片 `Flow<AudioChunk>` 收完才返回 `TTSResponse`（`TtsSynthesizer.kt:29-48`），`TtsController` 拿整片再 `audio.play`（`:253-270`）⇒ 即使 WS 边收边出，也**听不到"更早开播"**，只多一条连接与状态机。
- 本仓唯一的流式 TTS 是 MiMo（SSE 增量 PCM），它同样被 `TtsSynthesizer` 拍平 —— 是既有事实。
- 若将来要做真流式播放（改 `AudioPlayer` 为 `MediaSource` 流式 / 自建 AudioTrack 队列），WS 才有价值；那时可复用 `common/.../http/SSE.kt` 的写法与现有 WS 控制器模式（`DashScopeASRController.kt:88-108`）作为连接管理参考。
- 附带：30 min/连接上限与 50 并发/账号限制在"每片一次 HTTP"模式下都不触碰。

### 3.6 播放可行性（wav / pcm）

| 格式 | 现有消费链 | 结论 |
|---|---|---|
| `wav` | `AudioPlayer.play` 只在 `format == PCM` 时包 WAV 头（`AudioPlayer.kt:56-58`），WAV 直接 `ByteArrayDataSource` + `ProgressiveMediaSource` 交给 ExoPlayer（`:60-66`）。Step provider 已可选 `wav`（`TTSProviderSetting.kt:257-258`、`StepTTSProvider.kt:94-102`） | **[事实]** 代码路径存在；**[未知]** 未在真机验证 DeepSeek 的 WAV 头/采样率是否被 ExoPlayer 接受（建议实测 1 片） |
| `pcm` | 无头裸 PCM → `pcmToWav` 包装（`AudioPlayer.kt:155-180`，强制 mono/16bit，默认 24000） | **[未知]** DeepSeek PCM 的采样率/声道/位深不明；猜错即变调。必须由 provider 显式 `sampleRate`，且确认 mono/16bit，否则需扩展 `pcmToWav` 参数（当前参数已支持 channels/bitsPerSample，但 `play` 只传 sampleRate，`AudioPlayer.kt:56-58`） |

附加建议：TTS 端"整片一次性返回"与 Step 一致，`TtsSynthesizer` 的 8MB 上限（`TtsSynthesizer.kt:36-41`）对 160 字符片段的 WAV 足够（1 分钟 24k/16bit mono ≈ 2.9MB；160 字 ≈ 40s 语音）⇒ 不会撞上限，但**长句/慢语速 + 24k 采样**要留意，必要时把 `maxChunkLength` 保持 160 不动并灰度。

### 3.7 用量/计费跟踪：**当前完全没有**

- `speech/` 全模块 grep `usage|cost|price|TokenUsage` 无命中；`app/src/main/java` 无 `ttsUsage/asrUsage`。
- 聊天侧 token 用量体系（`UIMessage` 的 usage）与 TTS/ASR 无耦合；仓库也无任何 "per 1M tokens 单价" 表（grep `pricing|pricePerMillion` 无命中）。
- ⇒ DeepSeek 的"每万字符 $0.45 / 每小时音频 $0.116"**无处展示、也不会记账**。**建议**：本次适配不新增计费体系，仅在 `Log.i` 里记录 `text.length`（TTS）/ `duration_ms`（ASR 响应自带）+ `request_id`，为后续统计留钩子；若要真做，需另立设计（settings 里的费价表 / 用量库），不要塞进 speech 模块。

---

## 4. DeepSeek ASR 适配判据

DeepSeek ASR 契约：`POST https://api.deepseek.com/v1/tools/asr`，**multipart 字段 `file`**，16-bit PCM WAV 或 Ogg Opus，mono/stereo（取左声道），`<=8 MiB`、`<=120 s`，语言自动检测，**无流式**，返回 `{request_id, text, duration_ms}`；计费按音频小时 $0.116 / 限时 $0.058。

### 4.1 最近邻：**`MiMoASRController`（录音分段 + PCM→WAV + HTTP 一次性 + 拼文本）**

理由逐条对齐：
- MiMo 也是"非流式 HTTP、录音期间按时间/字节分段、每段独立上传、完成后拼进 `completedTranscripts` 回调输入框"（`MiMoASRController.kt:48-57`、`:209-284`）。
- MiMo 仓库里已有 `pcm16ToWav`（`:309-338`）——DeepSeek 要的正是 PCM WAV，**复制/下沉这一函数即可**。
- 差异点（DeepSeek 更简单）：无 `model`、无 `language`、无 `asr_options`、无 base64（**multipart 直传二进制**，`<=8MiB` 比 MiMo 的 base64 膨胀友好）、有 `duration_ms`。

### 4.2 需要新增/改动的文件清单（ASR）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `speech/src/main/java/app/androidrik/asr/ASRProviderSetting.kt` | 新增 `@SerialName("deepseek")` 变体（**字段见 §4.3**）；`Types` `:194-204`；`withEnabled` `:217-227`；**`supportsServerVadVoiceMode` 保持不含它**（`:207-209`） |
| 2 | `speech/src/main/java/app/androidrik/asr/providers/DeepSeekASRController.kt`（新） | 录音循环（照 `MiMoASRController.kt:136-194`）+ 分段阈值（`120s`/`8MiB` 双限，见 §4.4）+ WAV 包装 + **multipart POST** + `{text, duration_ms}` 解析 + `setError`；`stop()` 里最后一次 flush（`:109-127`） |
| 3 | `speech/.../asr/`（可选重构） | 把 `MiMoASRController.pcm16ToWav`（`:309-338`）与 `AudioPlayer.pcmToWav`（`AudioPlayer.kt:155-180`）**下沉成一份共享 util**（如 `asr/PcmWav.kt`）；否则第三份拷贝 |
| 4 | `app/.../ui/hooks/ASR.kt` | `createController` `:133-160` 加分支 |
| 5 | `app/.../ui/pages/chat/VoiceMode.kt` | **不需要**加分支（`:123-138` 的 `else -> error` 是正确行为：`supportsServerVadVoiceMode=false` 时在 `:71` 就被 `chat_page_voice_unsupported_asr` 拦掉）；但要在注释里确认这一点，避免后人误加 |
| 6 | `app/.../ui/pages/setting/SettingSpeechPage.kt` | **`AddASRProviderButton` 的 5 个硬编码 `DropdownMenuItem` `:688-786` 必须加一项**（它不读 `Types`，是本案最容易漏的穷举点） |
| 7 | `app/.../ui/pages/setting/components/ASRProviderConfigure.kt` | 只读类型文本 `:34-40`、表单分发 `:59-65`、新增 `DeepSeekASRConfiguration`（apiKey/baseUrl/sampleRate/segmentDurationSec/预期 8MiB‑120s 提示） |
| 8 | `app/src/main/res/values*/strings.xml` ×3 | `setting_asr_configure_deepseek_*` |
| 9 | `speech/src/test/java/app/androidrik/asr/ASRProviderSettingDeepSeekTest.kt`（新） | 照 `ASRProviderSettingStepTest` 模板 |
| 10 | （可选）`speech/build.gradle.kts` | MockWebServer，用于验证 multipart 字段名/字节数 |

**不需要改**：`ASRState`/`ASRVoiceTurn`（无 VAD 就恒空，MiMo 同样如此）、`ChatInput.kt`（只消费 `onTranscriptChange` 与 `isRecording/isAvailable`）、`VoiceSessionController`（只在语音模式用）。

### 4.3 建议的 settings 字段（[建议]）

```kotlin
@Serializable @SerialName("deepseek")
data class DeepSeek(
    override val id: Uuid = Uuid.random(),
    override val name: String = "DeepSeek ASR",
    override val enabled: Boolean = true,
    val apiKey: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val sampleRate: Int = 16000,          // AudioRecord 采样率；16k mono 16bit ≈ 32KB/s
    val segmentDurationSec: Int = 30,     // 0 = 仅在 stop() 时整体上传（须自行兜 120s/8MiB）
) : ASRProviderSetting()
```
- **不设 `language`**（文档：自动检测，无参数）；不设 `model`（端点固定）。
- `sampleRate` 建议限制 8000/16000/24000（现有 MiMo 配置也是自由数，见 `ASRProviderConfigure.kt:325` 起）。

### 4.4 录音、分段与上传（关键约束）

| 约束 | 换算/后果 | 建议实现 |
|---|---|---|
| `<=120 s` | 16k/16bit/mono = 32000 B/s → 120s = **3.84 MB** | 时间阈值取 **100s**（留余量），字节阈值取 **3.5 MB**，先到先切 |
| `<=8 MiB` | 同上，120s 时远低于 8MiB；只有提高 `sampleRate`/stereo 才逼近 | 片段字节上限硬校验，超限即切（不要等 413） |
| 16-bit PCM WAV 或 Ogg Opus | `AudioRecord` 输出 PCM16 → 包 WAV 头即可（无需编码器） | 复用 `pcm16ToWav`；**WAV 头里的 sampleRate/channels/bitsPerSample 必须与实际录音一致** |
| mono/stereo，取左声道 | 本仓录音恒 `CHANNEL_IN_MONO`（`MiMoASRController.kt:141-155`） | 无需处理 |
| 无流式 | 只能"录一段传一段" | 与 MiMo 同构：`flushJob` 串行，结果顺序拼接（`:196-203`、`:272-284`） |
| `duration_ms` 返回 | 可用于日志/估算计费 | 存进 `Log.i` 或 `ASRState` 之外；无 UI 消费点 |

**multipart 发送方式**（本仓唯一范例在 `ai` 模块）：
- `ai/src/main/java/app/androidrik/ai/provider/providers/OpenAIProvider.kt:318-355`：`MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("image", file.name, bytes.toRequestBody(mediaType))`
- DeepSeek 需 `addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))`；
- **[事实]** `speech` 模块目前**没有** multipart 代码（全仓 grep `MultipartBody` 仅 `ai` 与 `ai` 测试）；`okhttp` 已在 `speech/build.gradle.kts` 依赖里，无需加库。
- 音频来源：**不是文件**，是 `AudioRecord` 内存缓冲（`ByteArrayOutputStream`），无需经过磁盘/格式转换（不做 mp3/opus 编码）。**[未知]** 是否接受 `Content-Type: audio/wav` 之外的类型或 filename 有无要求——按文档字段名 `file` 传即可。

### 4.5 语音模式：**不支持，且现有代码会自动如实拦截**

- `supportsServerVadVoiceMode` 不含新类型（`ASRProviderSetting.kt:207-209`）⇒ `VoiceMode.kt:71` 弹出 `chat_page_voice_unsupported_asr`，用户不会进入一条注定失败的路径。
- 因此 **`pauseCapture()` 保持默认空实现**（`ASRController.kt:10`）即可，无需像 DashScope/Volcengine 那样实现暂停（`DashScopeASRController.kt:136`）。
- 结论：DeepSeek ASR 只能替代"听写"，不能替代"语音模式"；这是产品口径要在设置页讲清的（当前 UI 没有按 provider 显示能力差异，[建议] 在 `DeepSeekASRConfiguration` 里加一句说明文案）。

### 4.6 听写体验差异（无 partial）

- MiMo/Step 同样"无 partial"，只是 MiMo 每 30s 回一段、Step 靠 SSE 出 delta 但最终仍是 done 才拼整段（`ASRProviderSetting.kt:147-161`）。
- 现状：`ChatInput.kt:571-577` 的 `onTranscriptChange` 会**整体覆盖**输入框后半段（`asrBaseText + " " + transcript`）。⇒ DeepSeek 每段回来时输入框内容会"跳一段"，第一段要等 30s 才出现；`stop()` 后 `ASRStatus.Stopping` 期间还要等最后一段 HTTP 返回（`MiMoASRController.kt:109-127`），期间麦克风已停但状态未 Idle，UI 依赖 `isRecording`（`ASRState.kt:19-21`）决定按钮禁用——**与 MiMo 完全同款体验，不构成回归**，但需在验收清单里写"≤30s 内无实时字幕"。
- **[建议]** 多段拼接用 `joinToString(" ")` 会在中文间插空格（MiMo 现状 `MiMoASRController.kt:281`）；DeepSeek 若中文为主，拼接策略应改为"中文边界不加空格"，属既有小瑕疵，可顺手在共享 util 里修。

---

## 5. 可复用的共享设施盘点

| 设施 | 位置 | 备注 |
|---|---|---|
| `OkHttpClient`（全局代理/UA/Accept-Language，readTimeout 10min，writeTimeout 120s） | `app/.../di/DataSourceModule.kt:244-300`；ASR 侧经 Koin 注入（`ui/hooks/ASR.kt:34`） | TTS 各 provider 自建 client（如 `StepTTSProvider.kt:41-44`），不共享全局代理设置 —— **[事实]** 这是既有不一致（TTS 不走用户配的代理）；DeepSeek TTS 建议同样自建以对齐，但在设计里注明 |
| `Call.await()` 协程封装 | `common/.../http/Request.kt:10-29` | 可在新 provider 里用 |
| SSE 工具 `SseEvent` / `sseFlow` | `common/.../http/SSE.kt`（MiMo 用 `httpClient.sseFlow`，`MiMoTTSProvider.kt:173`） | DeepSeek 两个 API 都不需要 SSE |
| PCM→WAV | `AudioPlayer.pcmToWav`（`speech/.../tts/controller/AudioPlayer.kt:155-180`，private）、`MiMoASRController.pcm16ToWav`（`:309-338`，private） | **两份重复实现**；新 ASR 建议下沉共享 |
| WebSocket 连接/事件模式 | `DashScopeASRController.kt:88-108`、`OpenAIRealtimeASRController.kt:86` 起、`VolcengineASRController.kt`（二进制协议 + `VolcengineASRProtocol.kt`） | 仅 TTS-WSS 远期需要时参考；当前不做 |
| 启停/选中/内置回填契约 | `common/.../common/settings/Enableable.kt:34-80`（`resolveSelected` / `refillBuiltIns`） | 新变体自动获得"停用不静默回退"语义 |
| 设置 JSON 兼容降级 | `app/.../data/datastore/migration/SettingsCompatDecoder.kt:42-63` | **只过滤 `providers` 与 `ttsProviders`**（见风险 R5） |
| 鉴权 header 惯例 | TTS：`Authorization: Bearer`（`StepTTSProvider.kt:70`）；ASR：三种混用（OpenAI `Bearer`、MiMo `api-key`、Volcengine 自定义） | DeepSeek 两个 API 都是 `Bearer`，选 `Bearer` 无需选项 |

---

## 6. 风险清单（按影响排序）

| # | 风险 | 证据 | 影响 | 缓解 |
|---|---|---|---|---|
| **R1** | **ASR 视频率/时长双限（120s / 8MiB）必须由客户端兜住**；超限是 4xx，且当前 ASR 层基本无重试——仅 **Step** 有（`StepASRController.kt:53` `MAX_RETRY=3`、`:282-303` 300ms 线性退避），MiMo 与三个 WSS 类无重试（TTS 有 3 次退避） | `MiMoASRController.kt:44-46`（6MiB 常量）、`:254-262`（非 2xx 直接 IOException）、`StepASRController.kt:53,282-303`、`TtsController.kt:319-343`（TTS 重试对照） | 长录音（>120s 且用户忘了点停）最后一段整段失败 → 文本丢一半，只有 toast | 双阈值（100s / 3.5MB）+ 段内 1 次重试（仅 429/5xx/超时）；`stop()` 的最后一段失败要保留已得文本（MiMo 已如此：`completedTranscripts` 逐步 append，`:272-284`） |
| **R2** | **PCM 采样率不明**：`AudioPlayer` 缺省 24000 静默套用 | `AudioPlayer.kt:56-58`；`TtsSynthesizer.kt:33-35`（只取首块 sampleRate） | 变调/变速，且不会报错——典型"看起来能用"的坑 | TTS 默认 `wav`；`pcm` 时强制 provider 显式给 `sampleRate` 并允许用户覆盖；真机 A/B 听感 |
| **R3** | **WAV 可播放性未验证**（ExoPlayer 对服务端 WAV 头的容忍度） | `AudioPlayer.kt:60-66`（无 WAV 解析，直接喂 Extractor）；Step 有 wav 选项但本仓无真机记录 | 若容器头异常 → `PlaybackStatus.Error`（`:114-119`），TTS 整段哑掉 | 首次联调拿 1 段 WAV 落盘用 `ffprobe`/Audacity 核对头 + 真机播放；必要时服务端换 pcm 由客户端包头 |
| **R4** | **`filter_markdown` 与计费口径未知** | 文档未写计费是否按过滤后字符 | 成本差可能达"整段 markdown 量"（实际很小），但口径不清会导致设置页文案说不准 | 开放问题 Q1；默认 false + 设置页开关，文案写"是否让服务端先过滤 Markdown（不影响本机已过滤的文本）" |
| **R5** | **旧版本 App 恢复新备份会整体失败**（ASR 无降级解码） | `SettingsCompatDecoder.kt:42-63` 只 `filter("providers")`/`filter("ttsProviders")`；测试 `SettingsCompatDecoderTest.kt:51-58` 明确覆盖的是 TTS 未知类型 | 用户在"新 App 配好 DeepSeek ASR → 备份 → 旧 App 恢复"时，**恢复整份设置失败**（不是丢一条 ASR，是整个 `SerializationException`）。TTS 侧已被容错，ASR 侧没有 | 顺带在 `SettingsCompatDecoder` 增加 `filter("asrProviders", ASRProviderSetting.serializer())` + 选中项回退（`selectedASRProviderId`），并补测试；**这是本次适配最值得顺手修的结构性风险** |
| **R6** | **新增 ASR 类型有 8 个穷举点，其中 `AddASRProviderButton` 硬编码菜单不读 `Types`** | `SettingSpeechPage.kt:688-786`；`ASRProviderConfigure.kt:34-40`/`:59-65`；`hooks/ASR.kt:133-160`；`ASRProviderSetting.kt:194-227`；`VoiceMode.kt:123-138`（不需要动） | 漏改 → 设置页能编辑但**新增列表里没有该类型**（用户根本建不出条目）或反之 | 实施时按 §4.2 清单逐项勾；`ASRProviderSettingDeepSeekTest` 加"Types 含 DeepSeek"断言（该断言**不覆盖 UI 硬编码菜单**，只能靠 review） |
| **R7** | 语音模式不可用，用户可能以为"换了 DeepSeek 就不能语音对话了" | `ASRProviderSetting.kt:207-209`、`VoiceMode.kt:69-79` | 产品预期落差（非 bug） | 设置项文案明示；或未来做"ASR 选中 DeepSeek 时语音模式回退提示"（现有 `chat_page_voice_unsupported_asr` 已覆盖） |
| **R8** | 计费/用量不可见 | §3.7（全模块无 usage/cost 代码） | 用户对"每万字符/每小时"无感，长期可能意外烧钱；与聊天 token 用量体验不一致 | 本次不做账本；至少 `Log.i` 记 `chars`/`duration_ms`/`request_id`，并在设计稿里记为后续项 |
| **R9** | TTS 并发/限流：预取 2 + 重试 3 → 单次朗读最多约 3 条并发请求；账号级 50 并发/30min 上限 | `TtsController.kt:60-62`、`:285-295`、`:319-343`；DeepSeek 50 并发/账号 | 单机单用户不会撞限流；多设备/多会话共用同一 key 才可能 | 无需改动；文档记录"不要并发开多路朗读"（本仓 UI 本身串行，`internalReset` 会取消前一轮 `:148-163`） |
| **R10** | 旧 TTS 类型名已被"退休"处理（`volcengine` 会被丢弃），说明 **`@SerialName` 一旦发布不宜改** | `SettingsCompatDecoderTest.kt:51-58`；`TtsProviderSetting.kt` 无 volcengine 变体 | 选错 `SerialName`（如先 `deepseek` 后改 `deepseek_tts`）会让用户已存配置变"未知类型"被丢 | 一次定名：建议 TTS/ASR 都用 `"deepseek"`（不同 sealed 家族，判别字段互不影响） |
| **R11** | `TTSProviderConfigure.kt` 的 R8 混淆守卫：类型名必须是字面量 | `ProviderTypeLabelSourceGuardTest.kt:48-51` | 用 `KClass.simpleName` 生成类型名 → 仅 release 版显示乱码（debug 正常，最难查的一类） | 新增分支照抄现有 `"Step"`/`"Fish Audio"` 写法 |
| **R12** | i18n 三份 locale 都要补齐（en 默认 + zh + zh-rTW） | `app/src/main/res/values*` 三个目录；键名模式见 §1.6 | 漏 zh-rTW → 繁中界面回落英文 | 按 `tts_config_deepseek_*` / `setting_asr_configure_deepseek_*` 两组键各补 3 份 |
| **R13** | 鉴权/端点路径易错：TTS 是 `/v1/tools/tts`、ASR 是 `/v1/tools/asr`，而现有 provider 都是 `/v1/audio/speech`、`/v1/audio/asr/sse` 之类 | `StepTTSProvider.kt:69`；`StepASRController`（`ASRProviderSetting.kt:156` 注释 `/v1/audio/asr/sse`） | 拼错 → 404，错误信息里只看到 "HTTP 404"（Step 读 body 尚可，OpenAI 不读 body 只报 code） | 新 provider 统一把 `baseUrl.trimEnd('/')` + 常量路径，非 2xx 时把 body 前 500 字符塞进 `TTSProviderException.message`（照 `StepTTSProvider.kt:77-84`） |

---

## 7. 开放问题（需产品/上游确认，实现前定）

- **Q1** `filter_markdown=true` 时计费的 "billed chars" 是过滤前还是过滤后？（影响默认值与文案）
- **Q2** TTS `format=pcm` 的采样率/声道/位深是多少？是否固定？（决定 `pcmSampleRate` 默认值与是否需要扩展 `pcmToWav`）
- **Q3** TTS POST 的"音频流式返回"具体形态：`Transfer-Encoding: chunked` 的连续 PCM/WAV 字节，还是 NDJSON/base64 包装？（决定 provider 是"读整个 body 发一个 chunk"还是"边读边 emit"——虽然当前播放端拍平，但影响 8MB 上限与错误时机）
- **Q4** ASR multipart 的 `file` 是否对 filename 扩展名/`Content-Type` 敏感（`.wav` 必需？）；错误体格式是否与 chat 一致 `{"error":{...}}`（便于统一解析 4xx 原因）
- **Q5** ASR 对 "Ogg Opus" 的偏好：WAV 直传体积大（16k mono 100s ≈ 3.2MB），若 Opus 更省流量，是否需要引入编码器（本仓无 opus 编码依赖，`media3` 只用于播放）——**[建议] v1 不做 Opus，WAV 足够**
- **Q6** 是否要为 DeepSeek 内置一条默认 TTS/ASR 配置（类似 `DEFAULT_TTS_PROVIDERS` 的 AiHubMix 条目）？涉及墓碑回填与"内置项不可删"语义
- **Q7** 是否复用聊天 DeepSeek key：本仓无 `ProviderSetting.DeepSeek`（DeepSeek 走通用 OpenAI 兼容），无法自动取 key；是否需要"从聊天 provider 导入 key"的一键按钮（跨模块耦合，[建议] 不做）

---

## 8. 最小测试计划

### 8.1 TTS（DeepSeek）

| 层 | 用例 | 参照 |
|---|---|---|
| 单元（纯） | 默认值（name/baseUrl/voiceId="stella"/format="wav"/filterMarkdown=false）；`Types` 含 `DeepSeek::class`；`copyProvider` 保留 apiKey/voiceId/format | 照 `TTSProviderSettingStepTest.kt:8-49` |
| 单元（纯） | 请求体字段映射：`text/voice_id/format/filter_markdown` 与"url = baseUrl + /v1/tools/tts"；format `wav|pcm`→`AudioFormat` 映射；未知则回落 wav | 把 body 构造抽成 `internal fun buildBody(setting, text): String` 纯函数，照 `MiMoTTSProviderTest.kt` 风格（**无需 mockwebserver**） |
| 单元（HTTP，可选） | 非 2xx → `TTSProviderException(code)` 且 `code in {429,500}` 时 `isRetryable`；空 body → 异常；正常 200 + wav 字节 → 单个 `AudioChunk(isLast=true)` | 需给 `speech` 加 `libs.okhttp.mockwebserver`（`gradle/libs.versions.toml:129`） |
| 集成（真机手动） | ①设置页新增 DeepSeek TTS → 测试播放按钮（`SettingSpeechPage.kt:906-925`）出声；②气泡喇叭朗读；③自动朗读（`autoPlayTTSAfterGeneration`）；④`text_to_speech` 工具；⑤长文本（>160 字被分片）连续播完无断点；⑥飞行模式/错 key 报错不崩（`TtsController` 记 error，`:253-261`）；⑦wav 与 pcm 各一遍，pcm 听感不变调 | — |
| 回归 | `ProviderTypeLabelSourceGuardTest` 通过；`TtsProviderEnabledTest` 通过；备份→恢复（新旧版本）不丢 TTS 配置 | — |

### 8.2 ASR（DeepSeek）

| 层 | 用例 | 参照 |
|---|---|---|
| 单元（纯） | 默认值；`Types` 含 `DeepSeek::class`；`copyProvider` 保留字段；`supportsServerVadVoiceMode == false` | 照 `ASRProviderSettingStepTest.kt:8-57` |
| 单元（纯） | **WAV 头正确性**：给定 PCM 字节 + sampleRate，断言 `RIFF`/`fmt ` size=16/audioFormat=1/channels=1/bits=16/`data` size、总长 = 44 + n | 先下沉 `pcm16ToWav` 到可测 util（现状 private：`MiMoASRController.kt:309-338`） |
| 单元（纯） | 分段阈值判定：`(bytes, elapsedMs) -> shouldFlush` 纯函数，覆盖 100s 边界、3.5MB 边界、`segmentDurationSec=0` 仅 stop 上传 | 照 MiMo `:169-177` 抽出 |
| 单元（纯） | 响应解析：`{request_id,text,duration_ms}` → text；空 text 不入结果；畸形 JSON → 明确异常 | — |
| 单元（HTTP，可选） | multipart 请求：方法 POST、path `/v1/tools/asr`、`Authorization: Bearer`、part 名 `file`、`Content-Type: audio/wav`、body 字节数 = WAV 长度 | 需 mockwebserver；参考 `ai/src/test/.../OpenAIProviderImageEditTest.kt:137`（同款断言写法） |
| 集成（真机手动） | ①设置页新增 DeepSeek ASR（确认菜单里有该项）→ 听写 10s 短句出字；②持续说话 60s（跨 2 段）文本完整、顺序对；③**115s 以上**录音不报 413/超时；④`stop()` 后最后一段能回来（状态回到 Idle）；⑤错 key → `ASRStatus.Error` + toast（`ChatInput.kt:317-322`）；⑥语音模式选中 DeepSeek → 提示"不支持"（`VoiceMode.kt:71`）而非崩；⑦中文文本段间空格问题记录 | — |
| 回归 | 备份→恢复：旧版本 App 恢复含 `"type":"deepseek"` 的 asrProviders（**当前会整份失败，预期红 → 需先修 R5 或明确不接受该场景**）；`AsrProviderEnabledTest` 通过 | — |

### 8.3 建议的执行顺序（最小改动换最大验证价值）

1. `speech` 层：`TTSProviderSetting.DeepSeek` + `DeepSeekTTSProvider` + `TTSManager` 分支 + 设置默认值单测 → 用"临时把 setting 写进 DataStore 的 debug 入口/设置页新增"跑通真机播放（验证 Q2/Q3/R3）。
2. `speech` 层：`ASRProviderSetting.DeepSeek` + `DeepSeekASRController` + 共享 `pcm16ToWav` + 单测 → 真机听写（验证 R1/Q4）。
3. `app` 层 UI：`TTSProviderConfigure` / `ASRProviderConfigure` / `SettingSpeechPage`（TTS 副标题 + ASR 添加菜单）/ i18n ×3。
4. 顺手项（低风险高收益）：`SettingsCompatDecoder` 加 `asrProviders` 降级（R5）。
5. 全量回归：`./gradlew :speech:test :app:test` + 真机清单 §8.1/§8.2 集成项。

---

## 9. 关键行号索引（速查）

**TTS**
- 接口 `TTSProvider.kt:8-27`；Setting/`Types`/`withEnabled` `TTSProviderSetting.kt:9-27,308-324,333-349`
- `TTSManager.kt:19-49,55-69`；`TtsController.kt:43-62,111-146,221-283,319-350`；`TtsSynthesizer.kt:20-52`；`AudioPlayer.kt:54-66,155-180`；`TextChunker.kt:6-44`
- 播放器/钩子：`ui/hooks/TTS.kt:36-68,123-179`；`RouteActivity.kt:370-376`
- 朗读入口：`ChatMessageActions.kt:137-166`；`TTSAutoPlay.kt:20-39`；`VoiceMode.kt:86-108`；`TextToSpeechTool.kt:17-54`；`BuiltinToolUIs.kt:427`
- 设置：`SettingSpeechPage.kt:347-462,615-686,806-967`；`TTSProviderConfigure.kt:22-166,996-1204`
- 持久化：`Settings.kt:170-174`；`PreferencesStore.kt:372-377,584-592,934-938`；`SettingsDefaults.kt:25-92`；`SettingsExtensions.kt:76-84`
- 测试：`TTSProviderSettingStepTest.kt`、`MiMoTTSProviderTest.kt`、`TtsProviderEnabledTest.kt`、`ProviderTypeLabelSourceGuardTest.kt:48-51`

**ASR**
- 接口/状态 `ASRController.kt:5-12`、`ASRState.kt:3-21`、`ASRVoiceTurn.kt`、`AudioAmplitude.kt:10-32`
- Setting/`Types`/能力判据/`withEnabled` `ASRProviderSetting.kt:8,32-192,194-204,207-209,217-227`
- 控制器：`MiMoASRController.kt:44-57,109-127,136-194,196-203,209-284,309-338`；`DashScopeASRController.kt:88-108`；`VolcengineASRController.kt` + `VolcengineASRProtocol.kt`
- app 侧：`ui/hooks/ASR.kt:31-53,95-118,133-160`；`ui/pages/chat/VoiceMode.kt:68-110,123-138`；`ChatInput.kt:292-322,561-583`
- 设置：`SettingSpeechPage.kt:511-613,688-786,968+`；`ASRProviderConfigure.kt:20-67,70,185,271,325,411`
- 持久化：`Settings.kt:173-174`；`PreferencesStore.kt:379-381,593-596,939-942`；`SettingsExtensions.kt:86-93`
- 兼容/测试：`SettingsCompatDecoder.kt:42-63`；`SettingsCompatDecoderTest.kt:51-58`；`ASRProviderSettingStepTest.kt`、`VolcengineASRProtocolTest.kt`、`AsrProviderEnabledTest.kt`、`VoiceSessionControllerTest.kt:30`

**共享**
- `common/.../settings/Enableable.kt:34-80`；`common/.../http/Request.kt:10-29`；`common/.../http/SSE.kt`
- 全局 OkHttp：`app/.../di/DataSourceModule.kt:244-300`；multipart 范例：`ai/.../OpenAIProvider.kt:318-355`；mockwebserver 依赖：`ai/build.gradle.kts:59` + `gradle/libs.versions.toml:129`
- 设置 JSON：`app/.../utils/Json.kt:9-18`（默认 `type` 判别字段）
