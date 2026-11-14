# DeepSeek OCR Tool API 接入 · RikkaHub 现状审计（raw）

> 性质：**只读审计 + 适配材料草稿**。审计过程未修改任何源码、未提交、未外发。
> 基线：本仓当前工作树（2026-11-13）。路径均相对仓库根。
> 配套资料：`docs/design/drafts/deepseek-tools-api-adapt/01-api-reference.md`（API 摘编，本文引用其 §4/§7，不重复抄表）。
> 约定：`[未知]` 表示本轮审计未验证/无法从代码确认；`[待确认]` 表示有代码证据但需要真机或上游文档确认。

---

## 0. 结论 TL;DR

1. **推荐方案 A**：在 Settings 新增「OCR 后端」选择（`MODEL` = 现有视觉模型 OCR，`DEEPSEEK` = DeepSeek `/v1/tools/ocr`），默认保持 `MODEL`（零行为变化），DeepSeek 后端同时覆盖**图片附件**与**文档附件（扫描件）**两个入口。
   - 不建议做成「仅扫描件隐式回退」（方案 B）：同一会话里一半请求走视觉模型 token、一半走按页计费，账单不可预期；且失败时用户无法判断实际走了哪条路。B 的行为应作为 A 的子路径（本地可判扫描件，见 §2.7）。
2. 现有 OCR **只处理 `UIMessagePart.Image` 且只处理 `file:` URL**（`app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/OcrTransformer.kt:70-73,82-84`）；`UIMessagePart.Document` 完全不经 OCR，而是由 `DocumentAsPromptTransformer` 本地抽文本（`.../transformers/DocumentAsPromptTransformer.kt:27-41,80-85`）。
3. **仓内没有「PDF → 图片」的聊天附件渲染管线**：`PdfRenderer` 仅用于 TikZ 渲染回退（`app/src/main/java/app/androidrik/rikkahub/tikz/PdfToBitmap.kt:5,14,26-34`；`ui/components/richtext/TikzJax.kt:56,72,228`），文档只走 MuPDF 文本层（`document/src/main/java/app/androidrik/document/PdfParser.kt:7-19`）。因此「渲染 PDF 页再喂视觉模型」属于新增工程量；DeepSeek OCR 直接吃 PDF/Office 文件，是本方案最大的实现收益。
4. **扫描件在聊天路径上是静默劣化**：`DocumentAsPromptTransformer` 用 `PdfParser.parserPdf()` 且不做「全页无文本」判定（`:49-51`），扫描 PDF 会得到只有 `---Page N:` 页头的 prompt；工具路径已有的空文本 notice（`app/src/main/java/app/androidrik/rikkahub/data/ai/tools/ReadFileWindow.kt:117-122`）在聊天附件路径上**不存在**。`document/.../DocumentText.kt:84-91` 的注释已把这条坑写死在文档模块侧。
5. **计费无法落账**：`usage_record` 只有 token 列（`app/src/main/java/app/androidrik/rikkahub/data/db/entity/UsageRecordEntity.kt:25-55`），`UsageRecorder.record` 还是 private 且零 token 行会被丢弃（`.../data/ai/UsageRecorder.kt:84-110`，`:91`）。按页计费需要 schema 变更（当前 DB `version = 68`，`data/db/AppDatabase.kt:96`）。
6. **新增付费网络调用的最大陷阱是 preview/warmup**：`builtin.document_prompt` 允许进预览（`TransformerChainRegistry.kt:144`，`allowPreview=true`），预览会真跑文档抽文本（`PromptInspectionController.kt:282-301`、`PromptInspector.kt:349-364`）。若把 DeepSeek OCR 塞进 `DocumentAsPromptTransformer`，**打开上下文检查器就会产生按页计费**。审计建议改为新增独立 contribution 并 `allowPreview=false, allowWarmup=false`（§3.7）。
7. 顺带发现两处现存瑕疵：`{images}` 变量提示是死串（`app/src/main/res/values/strings.xml:1295`，全仓无替换点，`OcrTransformer.kt:115` 原样当 system prompt）；OCR 预览标签是硬编码中文（`TransformerChainRegistry.kt:145` `previewLabel = "图片文字识别（OCR）"`）。

---

## 1. 现有「模型 OCR」全链路

### 1.1 注册与触发条件

| 事实 | 证据 |
|---|---|
| 输入链中注册为 `builtin.ocr`，阶段 `CORE`，`allowWarmup=false`、`previewLabel="图片文字识别（OCR）"` | `app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/TransformerChainRegistry.kt:145` |
| 阶段枚举 `CORE/TEMPLATE/WORKSPACE/PLUGIN/FINALIZER`，宿主决定顺序，插件只能进 `PLUGIN`，链尾强制追加 `builtin.tool_result_balancing` | `TransformerChainRegistry.kt:15,63-64,88-90,108` |
| 触发条件 1：**当前对话模型不支持 `Modality.IMAGE`** 才做 OCR（支持图片的模型直接看图，不 OCR） | `OcrTransformer.kt:66-68` |
| 触发条件 2：消息里存在 `UIMessagePart.Image` 且 `url.startsWith("file:")`；`data:`/`http:` 图片一律跳过 | `OcrTransformer.kt:70-73` |
| 执行在 `Dispatchers.IO`，写入 `processingStatus = "正在识别图片..."`，结束在 `finally` 清空 | `OcrTransformer.kt:75-94` |
| 命中后把 Image part **替换**为 `UIMessagePart.Text(performOcr(...))`（原始 Image 不进请求副本） | `OcrTransformer.kt:78-90` |

### 1.2 单张图 OCR 的请求构造

```
resolveOcrConfiguration(settings)                       // :33-41（checkNotNull，失败即抛）
provider = UsageRecorder.wrap(ProviderManager.getProviderByType(providerSetting),
                              UsageTag(UsageSource.OCR))  // :108-111
provider.generateText(
  messages = [ system(settings.ocrPrompt),
               user(Image(part.url)) ],                   // :112-120
  params   = TextGenerationParams(model, customHeaders, customBody)
)
```

- 配置解析：`settings.findModelById(settings.ocrModelId)` → `model.findProvider(settings.providers)`；两处 `checkNotNull` 的错误文案直接指导用户去 Settings > Models（`OcrTransformer.kt:33-41`）。
- **配置错误不走 fail-soft**：`resolveOcrConfiguration` 在 `runCatching` 之外（`:99`），注释明确「配置错误向上传递到聊天错误卡，不能用占位文本替换图片后继续请求模型」（`:98`）。
- 网络/模型错误走 fail-soft：返回 `"[ERROR, OCR failed: $it]"` 文本，`CancellationException` 原样抛出（`:139-142`）；模型返回空文本时占位 `"[ERROR, OCR failed]"`（`:127`）。
- 注入格式：`<image_file_ocr>...</image_file_ocr>` + 一行免责说明（`:129-134`），告诉模型这是图片描述而非用户输入。

### 1.3 缓存

| 项 | 值 | 证据 |
|---|---|---|
| 落地 | `cacheDir/ocr_cache.json` 单文件（`SingleFileCacheStore`，key/value 都是 String） | `OcrTransformer.kt:44-52` |
| 结构 | `LruCache(capacity=64, deleteOnEvict=true, preloadFromStore=true, expireAfterWriteMillis=3.days)` | `OcrTransformer.kt:53-59` |
| key | `part.url` 字符串（即 `file://.../upload/<uuid>.png`） | `OcrTransformer.kt:102,137` |
| value | **已包好的 prompt 文本**（含 `<image_file_ocr>` 包装），不是模型原始输出 | `OcrTransformer.kt:129-137` |
| 命中日志 | `Log.i(TAG, "performOcr: Using cached result for ...")` | `OcrTransformer.kt:103` |

现存缺陷（写进实施注意事项）：

- key 只含 URL，不含 `ocrPrompt`/`ocrModelId`/后端 → **改提示词或换模型后仍命中旧结果**，最长 3 天。
- 单文件 JSON 预加载（`preloadFromStore=true`）：64 条 × 每条约数百~数千字符尚可；若 DeepSeek 后端把 1000 页文本塞进同一结构，单文件会膨胀到 MB 级且启动全量反序列化，**不能复用这个 store**（§3.4）。

### 1.4 用量记账（现状）

| 事实 | 证据 |
|---|---|
| `UsageSource.OCR`（注释「图片 OCR 转文字」） | `app/src/main/java/app/androidrik/rikkahub/data/ai/UsageRecorder.kt:50-51` |
| 记账方式：`wrap(provider, tag)` 装饰器，`generateText`/`streamText` 结束时各记一行 | `UsageRecorder.kt:76-82,112-145` |
| `record` 是 private，且 `promptTokens<=0 && completionTokens<=0` 直接丢弃 | `UsageRecorder.kt:84-110`（`:91`） |
| 表结构：`source/provider_id/provider_name/model_id/model_name/assistant_id/conversation_id/prompt_tokens/completion_tokens/cached_tokens`，**无页数、无金额** | `app/src/main/java/app/androidrik/rikkahub/data/db/entity/UsageRecordEntity.kt:25-55` |
| 统计页来源标签映射（新增枚举值必须同步，否则退化为裸枚举名，`UsageRecorder.kt:23-27` 注释） | `ui/pages/stats/StatsPage.kt:1038-1049`；字符串 `values/strings.xml:1677`（`stats_page_source_ocr`） |

→ DeepSeek OCR 按页计费、`usage` 仅参考（01-api-reference.md §4/:135/:145），现有记账通道**无法表达**。方案见 §4。

### 1.5 设置项与 i18n 入口

| 项 | 证据 |
|---|---|
| `Settings.ocrModelId: Uuid = Uuid.random()`、`Settings.ocrPrompt: String = DEFAULT_OCR_PROMPT` | `app/src/main/java/app/androidrik/rikkahub/data/datastore/Settings.kt:105-106` |
| DataStore 键 `ocr_model` / `ocr_prompt` | `data/datastore/PreferencesStore.kt:319-320` |
| 读取：`ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random()` | `PreferencesStore.kt:525`（`:526` 读 prompt） |
| 写入：`settings.ocrModelId.toString()` / `settings.ocrPrompt` | `PreferencesStore.kt:905-906` |
| 默认提示词（"You are an OCR assistant..."，要求描述图标/位置/关系） | `data/ai/prompts/OcrPrompt.kt:3-18` |
| 模型选择 UI：`SettingModelPage` 的 `ModelSettingItem(title=setting_model_page_ocr_model, ...)` | `ui/pages/setting/SettingModelPage.kt:251-259`；组件定义 `:757-809`（`ModelType.CHAT` 选择器 + `CardGroup`） |
| 提示词编辑 UI：`SettingModelPromptPage` 的 `PromptSettingItem(... promptValue = settings.ocrPrompt ...)` | `ui/pages/setting/SettingModelPromptPage.kt:84-92` |
| 字符串（en / zh / zh-rTW 三套都在） | `values/strings.xml:1293-1295,1677,1746`；`values-zh/strings.xml`、`values-zh-rTW/strings.xml` 同名 key（每个文件命中 2 次 = `_ocr_model` + `_ocr_model_desc`） |
| 语言集合受 `localeFilters = ["en","zh","zh-rTW"]` 约束，新增 key 只需这三套 | `app/build.gradle.kts:121-125` |

注意 `ocrModelId` 默认是**随机 UUID**（`Settings.kt:105`、`PreferencesStore.kt:525`）：未配置时必然解析失败并弹错，这是刻意的「显式失败」（对应测试 `app/src/test/java/app/androidrik/rikkahub/data/ai/transformers/OcrTransformerTest.kt:15-23`）。DeepSeek 后端不需要模型，应让这条错误路径只在 `MODEL` 后端生效。

### 1.6 预览 / 上下文检查器路径

| 事实 | 证据 |
|---|---|
| 真实请求与预览共用同一 transformer 链（`GenerationHandler` 为唯一出口） | `data/ai/GenerationHandler.kt:1579-1602`（注释见 `:1579-1586`） |
| 预览用 `TransformerUse.PREVIEW`（或 `WARMUP`）装配快照 | `service/PromptInspectionController.kt:282-301` |
| 预览确实执行链，只是 `processingStatus` 传一次性 `MutableStateFlow(null)` | `data/ai/PromptInspector.kt:349-364` |
| OCR 因 `allowWarmup=false`（且 `allowPreview` 默认 false）被排除，进入 `omittedInputs`；仅当「模型不支持图片且消息里有 `file:` 图片」才报告为省略项 | `TransformerChainRegistry.kt:86-87,97-109` + `:31-33`（`TransformerOmission.appliesTo`） |
| 省略提示展示 | `ui/pages/promptinspect/PromptInspectPage.kt:269-272`；`ui/pages/chat/ContextCapacitySheet.kt:104-106` |
| 预览/实时链执行无 try/catch，transformer 抛异常直接冒泡 | `transformers/Transformer.kt:144-186`（`:154-165`） |

→ 结论：**OCR 目前不会在预览里花钱**。任何新后端必须保持这一性质（§3.7）。

---

## 2. 文档管线（Document / 图片如何进入请求）

### 2.1 document 模块能力

| 事实 | 证据 |
|---|---|
| 支持格式枚举：PDF(page) / DOCX(block，100 段一块) / PPTX(slide) / EPUB(chapter) | `document/src/main/java/app/androidrik/document/DocumentText.kt:8-27,53` |
| 统一入口 `DocumentReader.read(file, path, startUnit, unitCount)` / `totalUnits`；失败抛异常（区分「空文档」与「解析失败」） | `DocumentText.kt:50-82` |
| 全页无正文 ⇒ 拼串返回空串（扫描件判据），避免只有页头的串看着「有内容」 | `DocumentText.kt:84-99` |
| PDF 抽文本用 MuPDF `PDFDocument` + `toStructuredText().asText()`，逐页拼 `---Page N:` | `document/src/main/java/app/androidrik/document/PdfParser.kt:7-19` |
| PDF 按页抽 `parsePages`：`start>total` 返回空 slice，`assembleUnitText` 判空 | `PdfParser.kt:31-49`（`:43-48`） |
| `pageCount()` 可直接拿页数（打不开抛异常） | `PdfParser.kt:22-23` |
| `DocxParser` / `PptxParser` / `EpubParser` 存在（本轮未逐行审解析细节） | `document/src/main/java/app/androidrik/document/{DocxParser,PptxParser,EpubParser}.kt` [未知：内部实现] |

### 2.2 文件 → `UIMessagePart` 的入口

| 场景 | 代码路径 | 证据 |
|---|---|---|
| 文件选择器（`OpenMultipleDocuments`，`*/*`）→ 允许类型过滤 → 拷贝进 `filesDir/upload` → `Document(url=file://…/upload/<uuid>.<ext>, fileName, mime)` | `ui/pages/chat/ChatFilesPickerSheet.kt:174-203`（`:180` 过滤、`:181` import、`:189` 构造 part） |
| `importAttachments` = `FilesManager.createChatFilesByContents(uris) { toast }` | `ChatFilesPickerSheet.kt:56-58` |
| 拷贝实现：`upload/` 下 UUID 文件名、原子写、失败回吐 `Unable to save attachment (maximum 100 MiB)` | `data/files/FilesManager.kt:323-360`（`:339,:350`） |
| 粘帖文本走 `createChatTextFile` → 也生成 `Document(mime="text/plain")` | `FilesManager.kt:484-506` |
| 图片选择器 → `inputState.addImages(importAttachments(uris))` → `UIMessagePart.Image(uri.toString())` | `ChatFilesPickerSheet.kt:137-156`；`ui/hooks/ChatInputState.kt:127-133` |
| 文档加入输入框 | `ChatInputState.kt:151-157` |
| 允许的 mime/扩展名白名单（含 `application/msword`、`application/vnd.ms-excel`、`application/vnd.ms-powerpoint`） | `ui/navigation/ChatUtil.kt:36-64` |
| 附件总上限 100 MiB（图片另限 5 MiB / 4096px / 8MP） | `ai/src/main/java/app/androidrik/ai/util/AttachmentLimits.kt:11-14,16-18` |

**问题（M 级）**：白名单放行 `.doc/.xls/.ppt`（`ChatUtil.kt:39,41,43`），但 `DocumentAsPromptTransformer` 只解析 pdf/docx/pptx/epub，其余 `else -> file.readText()`（`DocumentAsPromptTransformer.kt:80-85`）→ 老式二进制 Office 文件会被当文本读出乱码塞进 prompt。DeepSeek OCR 恰好支持 DOC/PPT（01-api-reference.md §4/:110），可作为该缺陷的顺带修复。

### 2.3 请求侧：文档如何变成文本

| 事实 | 证据 |
|---|---|
| `builtin.document_prompt` 在 `builtin.ocr` **之前**（文档先转文本，再处理图片 OCR） | `TransformerChainRegistry.kt:144-145` |
| 行为：对每条消息里的 `Document` 逐个 `readDocumentContent()`，把 `<UploadFile name=... path=/upload/...>```text```</UploadFile>` **插到 parts[0]**，原 Document part 保留 | `DocumentAsPromptTransformer.kt:24-44`（`:33-40`） |
| 单文档字符上限 200_000（注释：超大文档曾 API 413/超时） | `DocumentAsPromptTransformer.kt:16-17,86` |
| mime 分派：pdf/docx/pptx/epub 走解析器，其它 `readText()` | `DocumentAsPromptTransformer.kt:73-90`（`:80-85`） |
| 工作区路径注入：仅当父目录名是 `upload` 时给 ` path="/upload/<name>"`（proot 挂载） | `DocumentAsPromptTransformer.kt:65-71` |
| 解析器直接调 `PdfParser.parserPdf`（**整本**、不走 `parsePages`、不做空文本判定） | `DocumentAsPromptTransformer.kt:49-51` |

### 2.4 Part 表示与 provider 侧

| Part | 定义 | 证据 |
|---|---|---|
| `Text(text, metadata)` | `ai/src/main/java/app/androidrik/ai/ui/UIMessagePart.kt:75-78` |
| `Image(url, metadata)`（`file:` / `content:` / `data:` / `http:`） | `UIMessagePart.kt:80-85` |
| `Document(url, fileName, mime="text/*", metadata)` | `UIMessagePart.kt:102-108` |
| provider 侧未见 `Document` 转换分支（全 `ai/src/main` 只有 `ui/Message.kt` 引用 Document：合并/校验/计数） | `ai/src/main/java/app/androidrik/ai/ui/Message.kt:192,250,325,342,475` [待确认：provider 转换器对未知 part 是跳过还是报错] |

→ 结论：`Document` 必须靠 `DocumentAsPromptTransformer` 才能进请求；不存在「把原始 PDF 上传给视觉模型」的通道。

### 2.5 PDF 是否被渲染成图片？

**没有（聊天附件路径）**。全仓 `PdfRenderer` 只出现在 TikZ：

| 用途 | 证据 |
|---|---|
| Tectonic 产出 PDF → Bitmap 渲染回退 | `app/src/main/java/app/androidrik/rikkahub/tikz/PdfToBitmap.kt:5,14,26-34` |
| 注释「SVG 失败时降级 PDF → PdfRenderer 位图」 | `tikz/TectonicNative.kt:17` |
| TikzJax 回退链第 2 级 | `ui/components/richtext/TikzJax.kt:56,72,228` |
| 文档模块无渲染，仅文本层 | `document/.../PdfParser.kt:7-19` |

影响：
- 若想「渲染每页 → 逐页调视觉模型」，需要新增：`PdfRenderer` 分页（API 21+，本仓 minSdk 26）、像素/DPI 策略、逐页 5 MiB 图片上限（`AttachmentLimits.kt:12`）、每页一次请求的 token 成本与超时重试 —— 工作量与失败面都远大于直接传 PDF 给 DeepSeek OCR。
- `PdfRenderer` 在 Android 上不支持加密 PDF（未知本方案是否需要），且分页渲染大量页会吃内存/耗时；DeepSeek 服务端做这件事，客户端只上传一次。

### 2.6 Base64 图片转本地文件（与 OCR 的关系）

| 事实 | 证据 |
|---|---|
| `Base64ImageToLocalFileTransformer` 是**输出**侧 transformer，`onGenerationFinish` 时把 assistant 输出里的 `data:` 图片落盘 | `transformers/Base64ImageToLocalFileTransformer.kt:7-17` |
| 输出链注册 `builtin.base64_image`（CORE，在 think_tag 之后、regex 之前） | `TransformerChainRegistry.kt:117-123` |
| 实现：解码 → 采样降采样 → 压缩 PNG → `createChatFilesByByteArrays()` 落 `upload/` | `FilesManager.kt:405-432`（`:434-451` 降采样） |
| 落盘函数同时负责 100 MiB 校验与 managed_files 登记 | `FilesManager.kt:368-403` |

→ 与 OCR 的耦合点：OCR 只认 `file:` URL（`OcrTransformer.kt:70-73`），所以「从模型输出/工具结果回流的 base64 图片」在输出链执行后才可被下一轮 OCR 识别；用户上传的图片由选择器直接落盘，天然满足条件。DeepSeek 后端保持同一前提即可。

### 2.7 扫描件今天的表现（两条路径不一致）

| 路径 | 判据 | 表现 |
|---|---|---|
| 工具 `workspace_read_file` | `buildDocumentResult`：`text.isBlank()` ⇒ notice「No extractable text … scanned image without a text layer」 | `app/src/main/java/app/androidrik/rikkahub/data/ai/tools/ReadFileWindow.kt:80-124` |
| 聊天附件 | `DocumentAsPromptTransformer` 无空文本判定，`parserPdf` 返回只有页头的串（`PdfParser.kt:12-16` 无条件下 `---Page N:`），再 `take(200k)` | `DocumentAsPromptTransformer.kt:49-51,86` |

→ 聊天里贴扫描 PDF，模型看到的是 `---Page 1:` 一堆空页，且**没有任何提示**。这是 DeepSeek OCR 最直接的价值点，也是方案 A 中「文档入口」必须覆盖的场景。

---

## 3. 方案判断与改动清单

### 3.1 A vs B

| 维度 | A：新增可选后端（推荐） | B：仅扫描件隐式回退 |
|---|---|---|
| 图片 OCR | 可切到 DeepSeek（接受 PNG/JPEG/WebP/GIF/BMP/TIFF，01 §4/:110） | 不变，仍视觉模型 |
| 扫描 PDF | 文件直传，无渲染、无逐页请求 | 可行（本地判空即可触发） |
| 文本型 PDF | 默认仍本地抽文本（更便宜） | 同 |
| 设置复杂度 | 中：后端选择 + Key/Provider + purpose | 低：无 UI |
| 计费可预期性 | 高：用户显式选择 | **低**：同一会话混用两种计费，用户无法归因 |
| 失败可解释性 | 高：错误卡可标明后端 | 低：OCR 失败时不知该看模型还是文件 |
| 回退 | 切回 `MODEL` 即恢复 | 无开关，只能删文档 |
| 隐私面 | 明确（用户主动选择上传整份文档） | 隐式上传，更难告知 |
| 结论 | **采用** | 作为 A 的子路径（扫描件检测触发），不单独存在 |

附加理由：DeepSeek OCR **不能替代**现有模型 OCR 的语义——现有 `DEFAULT_OCR_PROMPT` 要求「描述图标/形状/箭头、空间关系、识别建筑/人物」（`OcrPrompt.kt:7-17`），DeepSeek OCR 输出是「逐页文本 + ref/box 坐标」，不含语义描述。两条后端有真实互补性，做并列选择而不是替换。

不建议的第三方案 C：把 OCR 做成 workspace 工具让模型自行调用 —— 与现有「发送前自动 OCR」语义冲突（工具调用发生在生成中），且超出本次适配范围。

### 3.2 推荐方案的精确改动清单（A）

> 原则：默认 `MODEL` 时**逐字节保持现有行为**；新增路径全部是「显式选择后生效」。

| # | 文件 / 位置 | 改动 | 备注 |
|---|---|---|---|
| 1 | `ai/src/main/java/app/androidrik/ai/tools/ocr/DeepSeekOcrClient.kt`（新建，约 150 行） | OkHttp `multipart/form-data` 调用 `POST {baseUrl}/tools/ocr`；仅两个字段 `file` + `purpose`；180s 总超时 / 120s 写超时；前导换行 trim 后解析；HTTP 200 且 body 含 `error` 视为失败；错误码→`OcrFailure` 映射 | 放 `ai` 模块的好处：`okhttp` 是 `api` 依赖（`ai/build.gradle.kts:46-49`），且已有 `mockwebserver` 测试依赖（`:59`） |
| 2 | `ai/src/main/java/app/androidrik/ai/tools/ocr/OcrModels.kt`（新建） | `@Serializable OcrResult(id, purpose, pageCount, pages, usage?)`、`OcrPage(index, text, finishReason)`、`OcrUsage`；`OcrPurpose` 枚举（`TEXT→text_recognition` / `RICH_LAYOUT→rich_layout_recognition`）；`stripLayoutTokens(text)`（去 `ref/box`）；`toPromptText(pages, includeLayout)` | 纯函数，可 JVM 单测 |
| 3 | `data/datastore/Settings.kt`（`:105` 附近） | 新增 `enum class OcrBackend { MODEL, DEEPSEEK }`、`enum class OcrPurpose { TEXT, RICH_LAYOUT }`；字段 `ocrBackend = MODEL`、`ocrDeepSeekProviderId: Uuid? = null`（空 = 内置 DeepSeek provider `f099ad5b-…`）、`ocrDeepSeekBaseUrl = "https://api.deepseek.com/v1"`、`ocrDeepSeekPurpose = TEXT`、`ocrDeepSeekIncludeLayout = false`、`ocrDeepSeekMaxPages = 1000` | 全部带默认值；`Settings` 是 `@Serializable`（`Settings.kt:49,55-56`），新增带默认值字段对备份/导入向后兼容 |
| 4 | `data/datastore/PreferencesStore.kt` | `:319` 附近加键 `ocr_backend` / `ocr_deepseek_provider_id` / `ocr_deepseek_base_url` / `ocr_deepseek_purpose` / `ocr_deepseek_include_layout`；`:525` 附近解码、`:905` 附近编码 | 逐字段 typed prefs，缺省即默认值，无 DB/DataStore 迁移 |
| 5 | `transformers/OcrTransformer.kt` | 按 `settings.ocrBackend` 分派：`MODEL` 走现有逻辑（不动）；`DEEPSEEK` 走新 client。缓存 key 改为 `"deepseek:{purpose}:{sha256(file)}"`（§3.4）；错误映射（§3.6）；`processOcr` 仍返回 prompt 文本 | 保留 `resolveOcrConfiguration` 仅在 `MODEL` 分支调用 |
| 6 | `transformers/DeepSeekDocumentOcrTransformer.kt`（新建，约 90 行） | 遍历 `UIMessagePart.Document`，当 `backend=DEEPSEEK` 且 mime ∈ {pdf, docx, pptx, doc, ppt} 时上传 → 生成 `<UploadFile name=…>` 文本并**把 Document part 替换为 Text**（让后续 `DocumentAsPromptTransformer` 无 Document 可处理）；失败则回退调用现有本地解析器 | 替代「改 `DocumentAsPromptTransformer`」的做法，理由见 §3.7 |
| 7 | `transformers/TransformerChainRegistry.kt` | 在 `builtin.document_prompt` **之前**插入 `TransformerContribution("builtin.deepseek_document_ocr", CORE, DeepSeekDocumentOcrTransformer, allowWarmup=false, allowPreview=false, previewLabel="DeepSeek 文档 OCR")` | `init` 只要求 input 阶段 `< PLUGIN`（`:63`）；CORE 内顺序稳定（`sortedBy` 稳定，`:108`） |
| 8 | `data/ai/UsageRecorder.kt` + `data/db/entity/UsageRecordEntity.kt` + `data/db/AppDatabase.kt`（`:96`，68→69）+ 新 migration + `UsageRecordDAO` | 新增 `page_count INTEGER NOT NULL DEFAULT 0`；`record` 增加可选 `pageCount`；新增 public `recordOcrPages(...)`（不受 `:91` 零 token 过滤影响） | 页计费无 token，必须绕开零 token 丢弃 |
| 9 | `ui/pages/setting/SettingModelPage.kt`（`:251-259`） | 用 `CardGroup`/`FormItem` 加「OCR 后端」选择（`MODEL`/`DEEPSEEK`）；`MODEL` 时保留现有 `ModelSettingItem`（`:757`）；`DEEPSEEK` 时显示 provider 选择（复用 provider 列表/Key 编辑）与 purpose 选择，隐藏模型选择 | 需要 provider 选择器组件——[未知] 是否已有现成「选 provider」Composable（现有 `ModelListSheet` 是选 model；`SettingProviderPage` 是编辑） |
| 10 | `ui/pages/setting/SettingModelPromptPage.kt`（`:84-92`） | `backend=DEEPSEEK` 时隐藏或禁用 OCR 提示词项，并说明「DeepSeek OCR 无提示词参数」 | 避免用户改提示词以为生效 |
| 11 | `res/values/strings.xml`、`values-zh/strings.xml`、`values-zh-rTW/strings.xml` | 新 key：`setting_model_page_ocr_backend`、`_backend_model`、`_backend_deepseek`、`_deepseek_provider`、`_deepseek_provider_desc`、`_deepseek_purpose`、`_deepseek_purpose_desc`、`_deepseek_layout_tokens`、`_deepseek_notice`（隐私/计费提示）、错误串若干；顺带修 `setting_model_page_ocr_prompt_vars`（`{images}` 死串） | 只有 3 个 locale（`app/build.gradle.kts:121-125`） |
| 12 | `ui/pages/stats/StatsPage.kt` | 来源标签已覆盖 OCR（`:1049`）；新增「OCR 页数」聚合展示（可选，需 DAO 查询扩展） | 成本显示：仓内无 pricing 基础设施（`grep pricing` datastore 0 命中），单价常量需自建 |
| 13 | Koin：`di/DataSourceModule.kt` | `single { DeepSeekOcrClient(get()) }`（注入共享 `OkHttpClient`，`:244-303`）；`OcrTransformer` 若保持 `object`，通过 `get<DeepSeekOcrClient>()` 取 | 测试注入需要 seam（见 §7） |
| 14 | 测试 | 见 §7 | — |

不动的部分：
- `OcrTransformer` 的触发条件（chat 模型缺 IMAGE、只处理 `file:` 图片）保持不变 —— 因此**支持图片的对话模型永远不会走 DeepSeek OCR**。这是产品决定，需写进设置页描述（否则用户会问「选了 DeepSeek 为什么没生效」）。若将来要「显式后端优先」，需另起一条 `builtin.ocr_force` 逻辑，本次不建议。

### 3.3 multipart 上传（本地 File → 请求体）

前提：`Document`/`Image` 的 URL 都是 `file://…/filesDir/upload/<uuid>.<ext>`（`FilesManager.kt:323-360,484-506`；`UIMessagePart.kt:80-85,102-108`），可直接 `uri.toFile()`；`DocumentAsPromptTransformer` 已示范 `document.url.toUri().toFile()` 的解析（`:68,74`）。共享 OkHttpClient 在 Koin 里（`DataSourceModule.kt:244-303`），已带全局代理与 UA。

```kotlin
// 伪代码草案（未编译，仅示形状）
val body = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .addFormDataPart("file", fileName, file.asRequestBody(mime.toMediaTypeOrNull()))
    .addFormDataPart("purpose", purpose.wire)          // text_recognition | rich_layout_recognition
    .build()                                            // 只允许这两个字段，多传 → 400
val req = Request.Builder()
    .url("${baseUrl.trimEnd('/')}/tools/ocr")
    .header("Authorization", "Bearer $apiKey")
    .post(body)
    .build()
val client = sharedOkHttp.newBuilder()
    .callTimeout(180, TimeUnit.SECONDS)                // 服务端总预算 180s
    .writeTimeout(120, TimeUnit.SECONDS)               // 上传窗口 120s
    .readTimeout(180, TimeUnit.SECONDS)
    .build()
val response = client.newCall(req).await()             // common/src/main/java/app/androidrik/common/http/Request.kt:11
val raw = response.body!!.string().trimStart('\n', '\r', ' ', '\t')  // keep-alive 前导换行
val json = Json.parseToJsonElement(raw).jsonObject
if (json["error"] != null) throw ...                   // HTTP 200 也可能是错误体
```

要点：
- **流式上传**：`file.asRequestBody(...)` 不把 100 MiB 读进内存；不要用 `bytes.toRequestBody`。
- **文件名必须带正确扩展名**：服务端按内容嗅探格式，但保留扩展名更稳（01 §4/:110）。
- **不额外传字段**：`purpose` 之外任何字段都可能 400（01 §4/:106）。
- 现有共享 client 的 `readTimeout` 已是 10 分钟（`DataSourceModule.kt:269`），但 `writeTimeout` 只有 120s（`:270`）—— 与上传窗口同值，边界情况可能先被客户端掐断，建议在专用 client 上把 `writeTimeout` 设为 120s 而 `callTimeout` 180s，并区分「上传超时」与「识别超时」的错误文案。
- 进度：OkHttp 需要自写 `RequestBody` 包装才能报上传进度；最小实现可只显示「正在上传并识别文档…」（沿用 `processingStatus`，`OcrTransformer.kt:77`）。

### 3.4 页文本与 ref/box token 存哪里、缓存键怎么设计

| 决策 | 建议 | 理由 |
|---|---|---|
| 入 prompt 的文本 | 存（用于本轮请求）；不落 DB、不新增 `UIMessagePart` 类型 | 新增 part 类型会牵动 `ai` 模块序列化/DB 消息 JSON/导出（`UIMessagePart.kt` 是公共 API），成本高 |
| 原始 `pages[]`（含坐标） | 落 `cacheDir/deepseek-ocr/<sha256>.json`（一 hash 一文件 + 最近使用清单），TTL 30 天 | 页文本量级远超现有 `ocr_cache.json` 单文件结构（`OcrTransformer.kt:44-59`），**不要复用** |
| prompt 文本缓存 | 内存 `LruCache`（容量按条目数）或直接由原始 JSON 每次派生（strip token 很便宜） | 提示词/模型变化不影响原始结果，避免现状「换 prompt 仍命中旧包装」的缺陷 |
| 缓存键 | `sha256(文件字节) + purpose + apiVersion 常量`；不是 `part.url` | 现状 key=URL（`:102,137`）；URL 是每次导入新 UUID（`FilesManager.kt:333`），同一文件重贴会重复扣费 |
| 失败是否缓存 | 不缓存 | 现状也不缓存（错误走 `getOrElse` 直接返回文本，`:139-142`），保持 |
| `finish_reason=length` 是否缓存 | 缓存但打 `truncated` 标记，并在注入文本加提示（§3.5） | 该页已计费，丢弃等于白花钱 |

### 3.5 限制处理（100 MiB / 1000 页 / 120s / 180s / finish_reason=length）

| 限制 | 客户端处理 | 证据/说明 |
|---|---|---|
| 100 MiB | 上传前 `file.length() > 100 MiB` 直接失败并给「拆分/压缩」文案；现有附件上限已经是 100 MiB（`AttachmentLimits.kt:11`、`FilesManager.kt:339`），**边界相等**，需注意 multipart 开销可能使服务端判定略超——建议客户端阈值取 `99 MiB` 保守值 | 服务端 413 |
| 1000 页 | PDF 用 `PdfParser.pageCount(file)` 预检（`document/.../PdfParser.kt:22-23`，`app` 已依赖 `:document`：`app/build.gradle.kts:326`）；DOCX/PPTX 无法本地数页 → 依赖服务端 400/413 文案 | [未知] 超页数到底返回 400 还是 413 |
| 120s 上传 | 专用 `writeTimeout`；> ~20 MiB 时提示「网络慢会超时」 | 服务端 408（含「中途停顿 30s」） |
| 180s 总耗时 | `callTimeout(180s)`；超时文案指向「页数过多/文件过大」而非「重试」 | 服务端 504 |
| `finish_reason=length` | 逐页扫描：任一页 `length` ⇒ 注入文本前缀 `[OCR 提示：第 N 页输出达到上限，内容可能不完整]`，并在 `processingStatus`/toast 提示；不重试（重试只会再花钱且结果相同） | `pages[].finish_reason`（01 §4/:133） |
| 15 并发/账号 | 客户端全局 `Semaphore(2)`（或按账号）串行化 OCR 请求，避免并行子代理/多会话触发 429；失败按退避重试 | 01 §4/:147；429 可重试（01 §1/:27） |
| 客户端取消 | **取消不会取消服务端识别、照常计费**（01 §4/:146）→ UI 需在发起前提示，取消按钮文案避免「不会扣费」的错觉 | — |

### 3.6 错误映射（含「HTTP 200 + error body」）

| 场景 | 判定 | 建议动作 |
|---|---|---|
| 400 | 表单/purpose 非法、文件无法处理、格式不支持 | 不可重试；文案指向格式/文件损坏；DOC/PPT 加密文件提示去密码（01 §4/:141） |
| 402 | 余额不足 | 不可重试；提示 DeepSeek 账户充值，**不要**自动切换后端（会造成静默计费漂移） |
| 408 | 上传超时/停顿 | 可重试 1 次（网络原因），仍失败则提示文件过大 |
| 413 | 文件/请求过大 | 不可重试；提示拆分或压缩 |
| 429 | 账号并发超限（15） | 指数退避重试（≤2 次），仍失败提示「同时识别任务过多」 |
| 500/503 | 服务内部错误/繁忙 | 退避重试（≤2 次） |
| 504 | 识别超时（180s 预算打满） | 不盲目重试；提示减少页数 |
| **HTTP 200 且 body 是错误对象** | 识别已开始（HTTP 已 200）后失败 | **必须先检查 body 是否含 `error`**，按错误处理并**禁止写缓存**；否则会把错误 JSON 当页面文本注入 prompt |
| 网络中断/取消 | `CancellationException` | 原样抛出（沿用 `OcrTransformer.kt:140` 语义），不要转成占位文本 |
| 解析失败（body 非 JSON/半截） | 前端判断 | 视为 500 类失败，可重试，提示「响应不完整」 |

失败后的降级策略（建议）：
- 图片：沿用现状 —— 返回 `[ERROR, OCR failed: ...]` 文本继续对话（不阻塞用户）；但错误文案要区分「后端未配置」「DeepSeek 计费/限额」「文件不合格」。
- 文档：先回退本地抽文本（文本型 PDF/DOCX 仍可用）；扫描件无法本地出字 ⇒ 返回带扫描件说明的占位文本（与 `ReadFileWindow.kt:117-122` 的 notice 文案对齐）。

### 3.7 为什么文档入口用「新 transformer」而不是改 `DocumentAsPromptTransformer`

| 方案 | 优点 | 致命点 |
|---|---|---|
| 改 `DocumentAsPromptTransformer` | 少一个类、无链变更 | 该 transformer `allowPreview=true`（`TransformerChainRegistry.kt:144`）→ 打开上下文检查器/预览会真的调用付费 OCR；`TransformerContext`（`Transformer.kt:13-22`）目前**没有**任何「这是预览」标志，无法在内部绕过 |
| 新增 `builtin.deepseek_document_ocr`（推荐） | `allowWarmup=false, allowPreview=false` 天然排除预览与 warmup（`TransformerChainRegistry.kt:97-109`、 omissions `:86-87`），与现有 `builtin.ocr` 的安全性质一致 | 需改注册表 + 更新 `TransformerChainRegistryTest` 对省略项列表的精确断言（`:68` 断言 `["builtin.ocr","builtin.workspace_reminder","plugin:effects:input"]`） |

补充：新 transformer 必须**把 Document part 换成 Text**（而不是「加一段文本但保留 Document」），否则后面 `builtin.document_prompt` 会再抽一遍文本（`DocumentAsPromptTransformer.kt:27-41`），导致重复注入与重复解析。保留 `<UploadFile name=... path=...>` 外壳与 `resolveWorkspacePath` 语义（`:31-39,65-71`），避免 workspace 工具路径能力回退。

### 3.8 ref/box token 解析与注入格式

服务端 token 形如（01 §4/:134）：`<｜ref｜>标题<｜/ref｜><｜box｜>[[52,40,948,96]]<｜/box｜>`，坐标为页内归一化 0~1000（`[[x1,y1,x2,y2]]`，左上/右下）。

建议：
- 默认 `ocrDeepSeekIncludeLayout=false`：`stripLayoutTokens()` 去掉 `<｜ref｜>/<｜/ref｜>/<｜box｜>/<｜/box｜>[[…]]`，保留主体文本与换行 → 注入最省 token、模型最易读。
- 逐页注入格式：
  ```
  ---Page N (DeepSeek OCR)---
  <去 token 的正文>
  ```
  与本地解析器输出风格一致（`PdfParser.kt:12-16`），便于模型/用户对照。
- `includeLayout=true` 时保留行尾坐标（`行文本 [[x1,y1,x2,y2]]`），供未来「点文本高亮原页」使用；**像素换算缺页宽高**：响应不含页面尺寸，`x_px = x/1000*pageWidth` 无从计算 —— [未知] 是否需要本地渲染 PDF 才能做高亮。
- 正则注意点：分隔符是全角 `｜`（U+FF5C）不是 ASCII `|`；`ref`/`box` 可能一行多个、可能跨行（[未知] 嵌套与缺 `</ref>` 的情形），解析器要容忍畸形输入（丢弃无法配对的片段而不是抛异常）。

---

## 4. 定价 / 用量 / 统计影响

| 事实 | 证据 |
|---|---|
| 计费：¥1.5 / 1,000 页（$0.225），按**成功识别的 `page_count`**；失败/超时不收费；客户端断开不取消已开始的识别 | `docs/design/drafts/deepseek-tools-api-adapt/01-api-reference.md` §4/:145-147、§7/:227 |
| `usage` 字段仅供参考、不参与计费 | 01 §4/:135 |
| 仓内记账只有 token 三列，无页数/金额 | `UsageRecordEntity.kt:49-54` |
| 零 token 行会被丢弃 | `UsageRecorder.kt:91` |
| 统计页按「来源」分组展示 token | `StatsPage.kt:1038-1049`、`stats_page_usage_title` 等 |
| 仓内无任何单价/成本基础设施（datastore `pricing` 0 命中） | `app/src/main/java/app/androidrik/rikkahub/data/datastore` 全目录 grep |

建议落账方式（最小改动）：

1. `UsageSource.OCR` 继续复用（不新增枚举 → 不必改 `StatsPage` 的映射与 i18n）。
2. 新增 `page_count` 列（DB 68→69，`AppDatabase.kt:96`），`UsageRecorder` 增加 public `recordOcrPages(source=OCR, providerName="DeepSeek", pageCount=N)`：写入 `prompt_tokens = usage.input_tokens ?: 0`（参考值，只为让行可见）或直接绕过 `:91` 的零 token 过滤；**页数才是账单真相**。
3. 统计页新增「OCR 页数 / 估算费用」卡片；费用单价用常量（¥1.5/1000 页），并注明「限时价，随官方变动」——仓内没有价目表基础设施，做动态定价是另一个工程。
4. 如果想区分两个 OCR 后端：用 `providerName`/`modelName` 快照（`UsageRecordEntity.kt:36-44`）即可，例如 `providerName="DeepSeek"`, `modelName="deepseek-ocr"`；不必新增 `UsageSource` 值。

风险：`usage` 是参考值，用它当 token 记账会让统计页「输入 token」出现无法解释的数值；若团队不希望污染 token 统计，建议 `page_count` + 单独的 `source="OCR_PAGES"`（会触发 `StatsPage` 映射与 i18n 改动）。二选一，实施前需产品确认 [待确认]。

---

## 5. 风险清单

| 级别 | 风险 | 说明 / 缓解 |
|---|---|---|
| H | 预览/warmup 触发付费调用 | 由 `allowPreview=false` 结构性规避（§3.7）；测试需断言预览链不含新 transformer |
| H | 隐私面扩大 | 现状：图片会上传给用户配置的视觉模型提供商，但 **PDF/DOCX 从不离开设备**（本地 MuPDF 抽文本）。DeepSeek 后端会把整份文档上传 → 设置项旁必须有明确告知，且默认关闭（默认 `MODEL`） |
| H | 计费不可见 | 无页数记账就会「钱花了、统计页没有」（§4）；上线前至少要有 `page_count` 列 + 统计展示 |
| M | 客户端取消/退后台仍计费 | 识别开始后断开不退费（01 §4/:146）；聊天页被系统杀进程时结果丢失但已扣费 → 建议「OCR 结果先落盘再注入」 |
| M | 429 并发（账号 15） | 多会话/子代理并行时触发；客户端信号量 + 退避 |
| M | 180s 预算 | 大 PDF（数百页扫描件）天然超时，且**接口无页码范围参数** → 不能在客户端「续跑」；只能降级提示或本地拆分 PDF（[未知] MuPDF 在本仓是否可写回/拆分 PDF） |
| M | 重复扣费 | 同一文件每次导入都是新 UUID（`FilesManager.kt:333`）；缓存键改用内容 hash（§3.4） |
| M | 注入文本撑爆上下文 | 1000 页文本可能 2~8 MB；必须沿用/收紧 `MAX_DOCUMENT_PROMPT_CHARS`（`DocumentAsPromptTransformer.kt:17`）并按页窗口截断，且截断要告知模型 |
| L | 后端选择与触发条件不一致 | 支持图片的对话模型不会触发 OCR（`OcrTransformer.kt:66-68`）→ 设置页文案必须解释 |
| L | 备份/导入兼容 | `Settings` 是 `@Serializable`（`Settings.kt:49,55-56`）；新增带默认值字段对旧备份安全，但旧版本 App 导入含新枚举字段的备份行为 [未知]（是否 `ignoreUnknownKeys`） |
| L | 硬编码中文预览标签 | `TransformerChainRegistry.kt:145` 新增标签同样硬编码，会破坏 i18n 一致性（既有问题，可顺带修） |
| L | 既有 `{images}` 死串 | `values/strings.xml:1295` 提示变量但无替换点（`OcrTransformer.kt:115` 原样使用）；改 i18n 时一并清理或实现替换 |

---

## 6. 未决问题（unknowns）

1. `ref/box` token 的完整文法：`<｜ref｜>` 是否可能单独出现、一个 ref 是否可对应多个 box、畸形/嵌套如何容错（01 只给单个示例）。
2. `purpose=text_recognition` 对「扫描版 PDF」是否也返回 ref/box token（01 §4/:134 只说「逐页识别的文本保留特殊 token；直接抽文字的文档没有」，边界未定义）。
3. 图片输入（PNG/JPEG）的 `page_count` 是否恒为 1；TIFF 多页是否按页计费。
4. 超过 1000 页返回 400 还是 413；100 MiB 是按原始字节还是含 multipart 开销。
5. 429 是否带 `Retry-After` 响应头；并发窗口如何计量（瞬时/滚动）。
6. 服务端是否对相同文件去重（重复上传是否重复计费）。
7. 响应是否可能提供页面尺寸/版式信息（决定坐标是否可还原为像素）。
8. 老式 `.doc/.ppt`（非 OOXML）是否支持 `text_recognition` 直接抽字（01 §4/:111 提到「无图 Word/PPT 直接抽文字」，未区分格式）。
9. 本仓「provider 选择器」是否有可复用 Composable（现有 `ModelListSheet` 选 model，`SettingProviderPage` 编辑 provider）。
10. `TransformerContext` 是否需要顺带扩展「预览标志」（若未来还想让 `document_prompt` 内部接入付费逻辑）。
11. 备份导入对新增枚举字段的严格性（是否 `ignoreUnknownKeys`）。
12. 是否允许本地拆分 PDF（MuPDF 能力范围）以规避 180s/1000 页上限。

---

## 7. 最小测试计划

原则：**CI 不打真实计费请求**，全部用 `MockWebServer`（`ai/build.gradle.kts:59` 已有依赖）与假 client。

### 7.1 纯函数 / JVM 单测（ai 模块）

1. `stripLayoutTokens`：正常 token、缺 `</ref>`、连续多个 box、无 token、含全角括号/换行。
2. `toPromptText(pages, includeLayout)`：页码从 1 开始、空页、`finish_reason=length` 前缀提示。
3. 页数/大小预检：`file.length()` 100 MiB±1、PDF `pageCount` 1000/1001（用 fixture 或 mock 解析器）。
4. 错误映射表：400/402/408/413/429/500/503/504/200-with-error/非 JSON body。
5. 缓存键：同一文件字节 → 同 key；改 purpose → 不同 key。

### 7.2 MockWebServer 客户端测试（ai 模块）

1. 请求形状：`multipart/form-data`、**恰好**两个 part（`file`、`purpose`）、filename 带扩展名、`Authorization: Bearer`、路径 `/tools/ocr`。
2. 响应解析：body 前有 `\n\n` 前导换行仍能解析（keep-alive 形态）。
3. HTTP 200 + `{"error":{...}}` → 抛 `OcrFailure`，且不写缓存。
4. 超时配置：断言 client 的 `callTimeout/writeTimeout`（反射或构造后 getter）。
5. 分页结果解析：`page_count`/`pages[].index` 与请求 page 对齐。

### 7.3 Transformer 测试（app 模块）

1. 既有 3 条 `OcrTransformerTest` 必须原样通过（`app/src/test/.../OcrTransformerTest.kt:14-49`）—— 保证 `backend=MODEL` 行为不变。
2. `backend=DEEPSEEK` + 图片：命中 client、注入 `<image_file_ocr>`、失败降级文本；需给 `OcrTransformer` 留注入 seam（现在是 `object` + Koin `get<>()`，`OcrTransformer.kt:26-27,108-111` → 建议把 client 当参数传入 internal 函数，或在测试里用 Koin module 覆盖）。
3. 文档路径：扫描 PDF（文本层为空）→ 走 DeepSeek；文本 PDF → 仍走本地解析；`backend=MODEL` → 完全不动（回归）。
4. 链装配：`TransformerChainRegistryTest` 更新（`:68` 省略项精确列表、`:34` 顺序断言、`:97` stage 排序）。
5. 预览安全：构造 `TransformerUse.PREVIEW/WARMUP` 快照，断言新 transformer 不在 `inputTransformers` 里、且在 `omittedInputs` 中（防止未来误开 `allowPreview`）。

### 7.4 真机/手工（灰度前）

1. 1 页 PNG（模型 OCR / DeepSeek 各一次，对比结果与账单）。
2. 1 页扫描 PDF、50 页文本 PDF（文本型应仍走本地，不产生 OCR 费用）。
3. 300 页扫描 PDF：验证进度文案、180s 内完成、页数注入、`length` 提示。
4. >1000 页 / >100 MiB：本地预检文案（不发出请求）。
5. 飞行模式中途上传 → 408/超时文案；断网恢复重试不重复扣费（缓存键验证）。
6. 取消生成：确认服务端仍计费（观察充值前后余额）并在 UI 有提示。
7. 统计页：出现 OCR 行 + 页数；来源标签仍为「OCR」。
8. 备份导出→导入（含新设置字段）不回退、不丢配置。

---

## 8. 证据索引（关键路径速查）

| 主题 | 文件:行 |
|---|---|
| OCR transformer 主体 | `app/src/main/java/app/androidrik/rikkahub/data/ai/transformers/OcrTransformer.kt:33-144` |
| OCR 注册与链序 | `.../transformers/TransformerChainRegistry.kt:29-37,86-90,117-123,139-150` |
| 链执行/预览共用 | `.../transformers/Transformer.kt:13-22,81-109,144-186`；`data/ai/PromptInspector.kt:349-364`；`service/PromptInspectionController.kt:282-301` |
| 真实请求出口 | `data/ai/GenerationHandler.kt:1579-1602` |
| 设置字段/键/编解码 | `data/datastore/Settings.kt:49,55-56,105-106`；`data/datastore/PreferencesStore.kt:319-320,525-526,905-906` |
| 设置 UI/i18n | `ui/pages/setting/SettingModelPage.kt:251-259,757-809`；`ui/pages/setting/SettingModelPromptPage.kt:84-92`；`res/values/strings.xml:1293-1295,1677,1746` |
| 用量记账 | `data/ai/UsageRecorder.kt:28-52,76-110,112-145`；`data/db/entity/UsageRecordEntity.kt:25-55`；`ui/pages/stats/StatsPage.kt:1038-1049` |
| 文档解析 | `document/src/main/java/app/androidrik/document/DocumentText.kt:8-27,50-82,84-99`；`.../PdfParser.kt:7-49` |
| 文档转 prompt | `.../transformers/DocumentAsPromptTransformer.kt:15-90` |
| 文档/图片入口 | `ui/pages/chat/ChatFilesPickerSheet.kt:56-58,137-156,174-203`；`ui/hooks/ChatInputState.kt:127-133,151-157`；`data/files/FilesManager.kt:323-360,368-403,484-506` |
| 100 MiB 上限 | `ai/src/main/java/app/androidrik/ai/util/AttachmentLimits.kt:11-18` |
| PDF 渲染仅 TikZ | `tikz/PdfToBitmap.kt:5,14,26-34`；`tikz/TectonicNative.kt:17`；`ui/components/richtext/TikzJax.kt:56,72,228` |
| 扫描件 notice（工具路径） | `data/ai/tools/ReadFileWindow.kt:80-124` |
| 共享 OkHttp / 代理 | `di/DataSourceModule.kt:244-303`；`ai/src/main/java/app/androidrik/ai/util/ProxyUtils.kt:24-59`；`common/src/main/java/app/androidrik/common/http/Request.kt:11` |
| 内置 DeepSeek provider（Key 复用） | `data/datastore/DefaultProviders.kt:111-122` |
| 现有测试 | `app/src/test/java/app/androidrik/rikkahub/data/ai/transformers/OcrTransformerTest.kt:14-49`；`.../TransformerChainRegistryTest.kt:34,68,97` |
| DB 版本 | `data/db/AppDatabase.kt:96`（`version = 68`） |

---

## 9. 审计边界声明

- 本轮**只读**：未修改源码、未执行构建/测试、未做任何提交或推送。
- 未逐行审：`DocxParser` / `PptxParser` / `EpubParser` 内部、各 provider 的 part→wire 转换、`LruCache`/`SingleFileCacheStore` 全部实现细节、Room migration 惯例文件。
- 文中「未知/待确认」项见 §6；若它们影响方案取舍（尤其 §6.2/§6.6/§6.8），建议在写 02 设计稿前先用一次真实小文件调用 + 官方 FAQ 确认。
