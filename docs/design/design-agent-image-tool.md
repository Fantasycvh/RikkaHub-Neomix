# 设计：会话内 Agent 生图工具（上游 #2123）

> 来源：上游 issue [#2123「允许 Agent 使用 toolcall 唤起图片生成模型生图」](https://github.com/rikkahub/rikkahub/issues/2123)（open）。
> 评估见 `docs/upstream-analysis.md`「#2123 助手生图工具评估」。本文是**实施设计**。
> 两位决策已由用户拍板：**产物形态 = 工具卡内显示**；**默认不审批 + 硬上限，同时保留
> 助手级审批开关**（与 `memoryCreateApproval` 同型）。

## 1. 真问题不是"生成"，是"显示"

issue 正文要的是一个本地工具（参数按 `MediaGenerationRequest`，字段从 `capabilities(IMAGE)` 取，
参考图由 Agent 自选，结果进消息流、tool result 不回灌图片）。**但评论串暴露的卡点更重要**：

- contributor 的第一反应是「自己写一个 skills 让 agent 运行不更好」；
- 作者回答：**「我也有 py 写的 cli harness，但问题统一为『没办法在聊天界面直接显示图片』」**；
- 绕到最后是「干脆创个 repo 放图片」——即外链托管。

⇒ 这条需求里，**生成那一半早就能做，缺的是宿主把它显示出来**。而这正是本仓已经具备的一半
（`UIMessagePart.Image` + 工具卡渲染体系 + 画廊/文件落盘）。所以本设计的目标不是"接一个新后端"，
而是**把已有能力接到会话里**，并且**不让它污染上下文**。

## 2. 现状（源码级）

| 环节 | 有/无 | 落点 |
|---|---|---|
| 生图调用链 | ✅ | `ImageGenerationParams(model, prompt, numOfImages, size, partialImages, customHeaders, customBody)`、`ImageEditParams`（`ai/.../provider/Provider.kt:69`）、`providerManager.getProviderByType(provider).generateImage(provider, params)` |
| 生图模型的选取 | ✅ | **独立设置**：`settings.findModelById(settings.imageGenerationModelId)`（`ui/pages/imggen/ImgGenVM.kt:160-165`），**不是 chat 模型** |
| 产物落盘/落库 | ✅ | `collectImageGeneration(...)`（`ImgGenVM.kt:250`）→ `FilesManager` + `GenMediaEntity`（`type = TYPE_IMAGE_GENERATION`），画廊可见 |
| 消息流能装图片 | ✅ | `UIMessagePart.Image(url, metadata)`（`ai/.../ui/UIMessagePart.kt:58`） |
| 工具注册点 | ✅ | `ChatToolFactory.createToolSegments(...)`（`data/ai/tools/ChatToolFactory.kt:137`），按来源分段、按助手设置门控 |
| 工具结果可带图片部件 | ✅ | `Tool.execute: suspend (JsonElement) -> List<UIMessagePart>`（`ai/.../core/Tool.kt:33`） |
| 工具卡渲染体系 | ✅ | `ToolUIRenderer`（`ui/components/message/tools/ToolUI.kt:58`）+ `ToolUIRegistry`（同文件 :93），已有约二十个渲染器 |
| UI-only 过滤先例 | ✅ | `withoutUiNotices()`，装配侧**唯一过滤点** `PromptAssembly.kt:321` |
| **模型可调生图** | ❌ | `data/ai/` 对生图类型**零引用**——本设计要补的就是这一个缺口 |

**关键前提**：`Tool.execute` 返回 `List<UIMessagePart>`，所以"工具能不能回图片"在类型上是通的；
本设计**刻意不这么做**（理由见 §3.2）。

## 3. 决策

### 3.1 产物形态：渲染在工具卡里（用户拍板 A）

`generate_image` 的工具步骤在消息流里就地展开成图片（Coil 直接加载本地文件），点击进 BottomSheet
看大图/换图/看 prompt 与模型。**不把图片做成助手消息的一等部件。**

为什么（这一条决定改动面）：

- 工具卡体系**已经存在**，新增一个 `ToolUIRenderer` 即可，不碰消息落库、不碰分支、不碰装配；
- 一等图片部件（ChatGPT 那种"回复里就是一张真图"）必须额外解决：①标记为 UI-only
  ②装配侧过滤 ③落库与消息分支的一致性。**成本高一个数量级**，而用户要的能力（看得见、能分享）
  在工具卡内已经拿到——分享/保存走既有图库即可。
- 代价如实记下：图片不参与后续请求（§3.2），所以**模型下一轮"看不到"自己刚生成的图**。
  这是刻意的，也是 issue 的原文要求。

若将来要 B，正确落点是给 `ai` 模块的 UI-only 机制加一个**同型标记**（对齐 `withoutUiNotices`），
而不是在工具结果里塞 base64。

### 3.2 tool result 只回一行 JSON —— "不回灌"的最强形式

工具返回值里**只有文本**：`ok / count / 每张图的本地路径 / model`。**没有图片字节、没有 data URI。**

- 满足 issue 的「tool result 不回灌图片」；
- 顺带解掉一个 issue 没写、但更贵的问题：**产物图如果进了历史，下一轮会被当输入模态再发一遍**
  （OpenAI 兼容的 image_url/data URI），一张图动辄上千 token。路径只有几十字符，量级差三个数量级。
- 路径为什么必须留在结果里：**卡片要靠它加载图片**（`GeneratedImage.filePath` 同款用法）。
  用不透明 id 也能做，但要多一张 id→文件的查找表；v1 不引入。

### 3.3 用哪个模型：设置里的**生图模型**，不是 chat 模型

沿用 imggen 页的既有口径（`settings.imageGenerationModelId`）。这带来两个直接后果，都要写进工具描述：

- **chat 模型不支持生图也能用**——能力由"用户是否配了生图模型"决定，而不是 chat 模型的
  `capabilities`。所以 issue 里 `capabilities(IMAGE)` 那句**不是前置**（评估里已论证）。
- 工具描述里要说清"当前用哪个模型"，否则模型会对一张不在自己能力范围内的图感到困惑。

### 3.4 门控：**没配生图模型就不装配这个工具**

`ChatToolFactory` 已有同款定式，直接沿用其原话：**「宁可没有工具，也不能出现一个一调用就崩的工具」**
（见 `subagentDispatcher: () -> SubagentDispatcher?` 的注释）。判定 = `Settings.resolveImageGenerationModel()`
（`data/image/ImageGenerationRunner.kt` 的顶层扩展函数：能解析出模型、且模型能找到 provider。
抽成顶层纯函数是为了能直接单测门控本身，不必构造整个 runner）。

顺带的收益：**没配生图模型的用户一个 token 都不付**——工具 schema 是要进每次请求的
（三参数约 120–200 tokens），这条门控把这笔固定开销精确地只落在用得上的人身上。

### 3.5 上限与审批：硬上限永远在，审批可选（用户拍板）

| 机制 | 取值 | 理由 |
|---|---|---|
| 单次张数 `num` | 上限 **4** | 与 imggen 页的既有产品上限一致（`ImgGenVM` 里 `coerceIn(1, 4)`） |
| 单轮调用次数 | 上限 **2** | 最坏 2×4 = 8 张/轮。超出**返回错误文本**（不是抛异常），让模型自己收敛 |
| 执行超时 | 显式声明 **180s** | 默认 60s 对"4 张图 + 慢中转"太紧；对齐 `workspace_shell` 显式放宽的先例（`Tool.executionTimeoutMillis`） |
| 审批 | `Assistant.imageGenApproval`，**默认 false** | 与 `memoryCreateApproval` 同型。默认零摩擦；打开后每次调用前拦一次（安全但啰嗦，用户已确认接受） |

**计数放哪**：工具装配**每轮生成一次**（`ChatService.kt:1285` 装配 → `generateText(tools = assembledTools)`，
生成循环内复用同一份 `List<Tool>`），所以计数器可以是闭包里的一个 `var`，作用域天然=本次生成。
唯一例外：**审批后续跑会重新装配**（另一次 `generateText`），计数会重置——已知且可接受（审批本身就是人盯着）。

## 4. 接口

### 4.1 工具

```
名称：generate_image
参数：
  prompt  string   必填   图片描述（英文效果通常更好，但不强制）
  size    string   可选   枚举 auto | 1024x1024 | 1536x1024 | 1024x1536 | 512x512；缺省 auto
  num     integer  可选   1..4，缺省 1
```

`size` 只暴露 5 个（不是 `ImageGenSize` 全部 8 个）：全网关/厂商支持面最大的是这几个，
其余留给 imggen 页的高级用法。**不支持的值由 provider 报错**，错误文本里明确建议改用 `auto`。

### 4.2 返回（进模型的文本）

成功：

```json
{"ok":true,"count":2,"model":"<displayName>","images":[{"path":"/…/xxx.png"},{"path":"/…/yyy.png"}],
 "note":"Images are shown in the conversation. Their content is not included here."}
```

失败（**一律是正常返回的文本，不是异常**——模型要能据此自我纠正）：

```json
{"ok":false,"error":"image_generation_unavailable","hint":"No image generation model is configured."}
{"ok":false,"error":"too_many_calls","hint":"At most 2 image generations per turn. Use the images already generated."}
```

### 4.3 卡片

`GenerateImageToolUI : ToolUIRenderer`（`toolName = "generate_image"`）：

- `title`：`已生成 2 张图片`（缺省/进行中/失败各一态）
- `Summary`：**图片网格**（1 张就单图，2–4 张开两列），点击进 `Preview`
- `Preview`：大图（可缩放）+ prompt 全文 + 模型名 + 「在图库中打开」
- 图标：待用 `find-hugeicons` 技能查一个已导入的名字后再定（本仓有 `Pin02` 未导入的先例，**不猜**）

## 5. 实现落点

| # | 文件 | 动作 |
|---|---|---|
| 1 | `data/image/ImageGenerationRunner.kt`（新） | 把 `ImgGenVM.collectImageGeneration` 的"生成 → 落盘 → 落 `GenMediaEntity`"抽成**共用一条写路径**，工具与生图页共用。**不复制**：本仓一贯要求单一装配点/单一写点 |
| 2 | `data/ai/tools/ImageTools.kt`（新） | `createImageTools(assistant, generate: suspend (prompt, num, size) -> List<GeneratedImage>)`：声明 `generate_image`、参数 schema、§3.5 的上限与计数、§4.2 的返回。**生成那一步用注入的 lambda**（同 `buildMemoryTools` 的 onCreation/onUpdate 注入法）——要测的是判定，不是网络 |
| 3 | `data/ai/tools/ChatToolFactory.kt` | 新 `ToolSegment("image", …)`，门控见 §3.4。**位置固定**并写注释：Claude 的 promptCaching 把断点打在最后一个工具上，顺序不能随手改（一次性失效可接受，长期必须稳定） |
| 4 | `ui/components/message/tools/GenerateImageToolUI.kt`（新） | §4.3 |
| 5 | `ui/components/message/tools/ToolUI.kt:94` | 注册进 `renderers` |
| 6 | `data/model/Assistant.kt` | `imageGenApproval: Boolean = false` + 序列化默认值（**必须**，否则老数据反序列化炸/丢设置） |
| 7 | **单开一类**：`Screen.AssistantImage` + 路由 + `AssistantDetailPage` 分类项 + 新页 `AssistantImagePage.kt` | 审批开关 + **当前生图模型**（只读，与装配门控同源）。见下方 §5.1 |
| 7b | `values*/strings.xml` × 6 | 文案走 locale-tui（本仓无 `<plurals>`，计数用 `%1$d`）；英文由工具写入，其余 5 语言手工补 |
| 8 | `docs/roadmap.md` / `docs/upstream-analysis.md` | 落地记录 + #2123 小节标注已实施 |

`ChatService` / `GenerationHandler` **不需要改**：审批走的是 `Tool.needsApproval` 既有通路。

### 5.0 落地与真机验证记录（2026-10-16）

**真机（装配）**：助手配置里能看到新分类「生图」，其「生图模型」行显示
「当前：**[特价]gpt-image-2-1k**」⇒ 设备上确实配了生图模型；拼装预览里**工具 Schema 20 个**，
且 JSON 中确有 `"name":"generate_image"` ⇒ **门控在设备上生效、工具真的进了真实请求的工具表**。

**真机（工具卡渲染）**：往库里插一条**临时会话**，其助手消息带一条 `generate_image` 工具调用，
结果就是 [imageToolSuccess] 的形状（指向一张**已有图库图片**的本地路径）。App 里该卡片渲染为
**「已生成 1 张图片」＋图片缩略图**（标题里的计数与结果 `count` 一致），点图走
`ZoomableAsyncImage` 的缩放查看 —— 也就是说**「能在聊天界面里看到图」这一半确实成立了**。
（该临时会话由用户手动清理。）

**真机（真实调用的端到端，2026-10-16，用户执行）**：会话 `bf81b131` 的一次调用**成功**——
入参 `{"num":2,"prompt":"一幅温暖治愈的插画：一只橘猫趴在窗台上晒太阳…"}`，结果
`{"ok":true,"count":2,"model":"nai-diffusion-5-full","images":[…]}`，且媒体表**新增两条**
`type=image_generation` / `model=nai-diffusion-5-full` 的记录 ⇒ **生成 → 落盘 → 落 `GenMediaEntity`
整条链走通**（图片也确实出现在工具卡里）。

**失败路径被真实世界一次性验了四种**，且全部落成**文本**而非异常：
`503 model_not_found`、`401 Invalid API key`、`400 Model grok-4.5 is not supported`、
`402 The Gems balance is insufficient` —— 正是 §4.2 想要的形态：模型拿到原因后自己换做法，
而不是撞一个异常把整轮打掉。

**硬上限在真机生效**：同一节点内第 3 次调用被 `too_many_calls` 拦下。

**真机暴露的一个待定问题（见 §8）**：**失败也计入上限**。用户真实遇到的是
"同一轮里前两次被 provider 拒（`503`/`401`，**根本没花钱**）→ 第三次尝试被上限拦住"，
只能另发一条消息重试（新节点 ⇒ 计数重置，与 §3.5 的作用域设计一致）。
本设计的取舍本来就是"计数发生在调用之前"，但真机体验说明这条边界值得再讨论一次。

### 5.1 开关落在「助手配置里单独一类」（用户拍板，两次纠正后的结论）

最初的落点是**记忆页**（那里已经住着 `memoryCreateApproval`/`memoryModifyApproval` 两个同型开关），
被否；第二次试**本地工具页**，也被否；用户要的是**在助手配置里单独列一类**。最终：

- 助手详情页的分类列表里新增一项**「生图」**（`Screen.AssistantImage` → `AssistantImagePage`），
  排在「本地工具」与「知识库」之间；
- 页面里两项：**生图审批**（开关）+ **生图模型**（只读，显示"当前：<模型名>"）。

**为什么用户的三次选择里这个是对的**：`generate_image` 既不是记忆（与记忆不共享任何机制，
"审批开关住在记忆页"只是形态上的类比），也不是**本地**工具（`LocalToolOption` 那一组跑在设备上：
JS 引擎/剪贴板/时间/朗读；本工具走的是设置里配置的**远程**生图模型）。硬塞进任一边都会让
"我该去哪里找它"这个问题没有正确答案，而单开一类正好让页面**把两件事分清楚**：

1. **能不能生图** = 设置里配没配生图模型（没配时工具**根本不装配**，开关点了也没用）；
2. **动手前要不要问** = `imageGenApproval`。

页面显示的那个模型名**与装配门控调用同一个函数**（`resolveImageGenerationModel()`），
所以不会出现"本页说已配置、工具却没装配"这种最难查的不一致。

## 6. 测试计划

新增 `ImageToolTest`（纯函数优先，与 `memoryToolNeedsApproval` 同款：**把判定抽成纯函数**，
否则测试只能复刻 lambda 等于没测到生产逻辑）：

1. `num` 超上限被夹到 4；`num=0` 被夹到 1；
2. 第 3 次调用返回 `too_many_calls` 文本而**不是抛异常**；
3. 未配生图模型 ⇒ 工具**不在**装配结果里（行为级断言，不是看常量）；
4. 成功返回的文本**不含** `data:` / `base64`（防止有人日后"顺手"回灌图片）；
5. `imageGenApproval` 默认 false；打开后 `needsApproval` 为真；
6. 返回 JSON 的字段形状稳定（scheme 变更会静默影响模型理解）。

**证伪惯例（本仓已确立）**：每加一个断言就注入一次反例看它是否精确变红——尤其 3、4 两条
（门控与不回灌）是本设计的全部价值所在，**只测 happy path 等于没测**。

## 7. 不做 / 后续

- **图生图/编辑**（`ImageEditParams` 已有）：需要"参考图"参数，而模型能拿到的是会话里图片的 url。
  放到第二批（届时定：只允许引用本会话出现过的 url，防模型编造任意路径）。
- **视频**、**能力声明矩阵**（按接口实际支持动态给字段）：那是 mediagen 路线，**维持
  `docs/upstream-analysis.md` 10-07 的「触发点立项」结论不变**——本设计刻意不依赖它。
- **把图片做成一等消息部件**：见 §3.1 末尾。
- 生图进度（`partial_images` 流式预览）进卡片：v1 只收终态，不分心。

## 8. 未决

- 是否需要一个**总开关**（"允许模型自主生图"），而不只是审批开关？v1 用"配了生图模型即启用 + 硬上限"，
  但模型确实可能在用户没要求时生成（例如把"画个图"理解成生图）。若真机上出现这种越界，再加开关。
- `num ≤ 4` 与 `每轮 ≤ 2 次` 都是**拍的初值**，真机跑一轮看体感与账单再定。
- **失败是否计入每轮上限**（真机暴露，未定）：当前实现是"调用前计数"，所以 provider 侧的
  `503`/`401`/`402` 这类**没花钱**的失败也会吃掉一次额度，用户真实撞到过"两次报错后第三次被拦"。
  三个候选：①保持现状（最严）；②只有**真的产出了图**才计入（符合"上限是为了管花钱"的本意，
  代价是 provider 一直坏时模型可以无限重试，要靠工具循环自身收敛）；③失败不计入，但每轮
  另设一个更宽的**总尝试次数**上限（例如 6 次）。
