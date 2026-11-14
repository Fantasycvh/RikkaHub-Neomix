# DeepSeek Tool APIs 适配资料包（2026-11-13）

> **状态：P0+P1+P1b+P2 全部实施（2026-11-14），全量单测绿。** 实施记录见 §9。
> 备料原文： 本目录只新增文档，源码零改动（`git status` 仅本目录 + 既有未跟踪文件）。
> 官方资料抓取时间：2026-11-13；用户提供的定价截图已与官网 CNY 价目**逐项核对一致**（见 `01-api-reference.md` §7）。
> **2026-11-13 二次核对修订**：修正 ASR 语种数（21 种）、ASR 重试现状（Step 有重试）、models.dev `cost` 机制描述；补充官方契约点（工具互斥/中途失败照常计费/`kind`+`signature` 回传/续写需 beta `prefix: true`/safety 与 Files 限制）与 SearchPicker 模型 ID 白名单。官方契约断言已与 2026-11-13 官网逐条复核。

## 1. 这是什么

2026-11-13 DeepSeek 发布「全家桶 App API」：在 `deepseek-flash` 的三种协议上提供**聊天内置工具**（联网搜索 / 深度思考搜索 / 文件输入 / 阅读链接 / 内容安全），并开放 **5 个独立 Tool API**（Search、Fetch、OCR、TTS、ASR，同一把 Key、同一 base URL）。

本资料包回答三个问题：

1. **官网契约是什么** → `01-api-reference.md`（逐项参数/限制/计费/错误，附官方链接）
2. **RikkaHub 现在差什么** → `02-repo-status.md`（含 2026-10-17 已适配的 Search/Fetch 旧账）
3. **怎么接（本轮不实施）** → `03-adaptation-design.md` + `04-plan-and-tests.md`

## 2. 文件索引

| 文件 | 内容 | 主要读者 |
|---|---|---|
| `README.md` | 一页总览、现状矩阵、优先级、必读风险 | 决策 |
| `01-api-reference.md` | 5 个 Tool API + 聊天内置工具契约摘编；CNY/USD 定价表 | 开发 |
| `02-repo-status.md` | 仓库现状与落点、已有实现与文档偏差、现状矩阵 | 开发 |
| `03-adaptation-design.md` | 每项能力的推荐方案、设置/数据/UI 落点、边界与不做清单 | 设计/评审 |
| `04-plan-and-tests.md` | 任务卡、验收、测试矩阵、风险登记、开工前探测清单 | 排期/QA |
| `raw/ocr-audit.md` | 只读审计原始笔记（OCR 全链路 + 14 项改动清单） | 追证据 |
| `raw/speech-audit.md` | 只读审计原始笔记（TTS/ASR 架构 + 穷举点） | 追证据 |
| `raw/chat-tools-audit.md` | 只读审计原始笔记（内置工具链路 + 双计费风险） | 追证据 |

阅读顺序建议：README → 01（契约）→ 02（差距）→ 03（方案）→ 04（排期）；`raw/` 只在需要核对 `file:line` 时查。

## 3. 一页结论（现状矩阵）

| 能力 | 官方入口 | 现状 | 结论 |
|---|---|---|---|
| Search | `POST /v1/tools/search` | ✅ 已适配（`786638fca`，2026-10-17） | 仅 P2 补 QPS 文案/i18n/可选 `ori_question` |
| Fetch | `POST /v1/tools/fetch` | ✅ 已适配（作为 `scrape_web`） | 同上 |
| OCR | `POST /v1/tools/ocr` | ❌ 未适配；现为「视觉模型 OCR」 | P1：新增可选后端，默认不变 |
| TTS | `POST /v1/tools/tts`（+ WSS） | ❌ 未适配；已有 11 个 TTS provider | P1：整段 POST，默认 wav |
| ASR | `POST /v1/tools/asr` | ❌ 未适配；已有 5 个 ASR provider | P1：MiMo 型分段 multipart |
| 聊天内置 web tools | `deepseek_web_search` / `deepseek_read_url` | ⚠️ 抽象齐全，Chat Completions 链路缺收发 | P0 守卫 + P1 收发 |
| 文件输入 / Files API | `POST /files` + `file_id` | ❌ 未适配 | **首版不做**（与本地解析重复） |
| 内容安全 | `safety_check` | ✅ 适配（provider 开关，默认关，仅 DeepSeek host 下发） | 对外服务场景启用 |

## 4. 优先级建议

- **P0（先做、防 bug，不花钱）**
  1. 内置工具「原生搜索 ↔ 外挂搜索」互斥守卫（现在只认 `BuiltInTools.Search`，新增 DeepSeek 变体会**双搜双计费**）。
  2. `SettingsCompatDecoder` 补 `asrProviders` 降级（否则新版导出的设置会让旧版**整份恢复失败**，与本次是否适配无关的既有缺陷）。
- **P1（三条线可并行）**：OCR 可选后端 / TTS provider / ASR provider；随后是 Chat Completions 内置工具收发（工作量最大、契约风险最高）。
- **P2（账目与体验）**：用量计数落库、价格展示、`safety_check` 开关、i18n 收尾。

## 5. 五个必读风险

1. **双重计费**：内置 `deepseek_web_search` 与外挂 `search_web` 同时生效；两者同价（¥5/千次）但会叠加。
2. **旧版恢复失败**：`asrProviders` 没有未知子类降级（TTS 有），新类型备份到旧版会整个设置回默认。
3. **整轮被打崩**：Chat Completions 注解遇未知 `type` 直接 `error`；DeepSeek 引用形态一变就整轮失败（且这是刻意对齐上游的行为，放宽需单独决策）。
4. **预览触发付费**：`builtin.document_prompt` 允许进上下文预览；付费 OCR 若塞进去，打开检查器就会按页计费。
5. **费用不可见**：全仓无页数/次数/字符/费用字段，TTS/ASR 模块无任何用量统计；`models.dev` 的 `cost` 从未声明在 DTO 上、解码即被丢弃。

## 6. 关键事实速查

| 项 | 值 |
|---|---|
| Base URL / 鉴权 | `https://api.deepseek.com`，`Authorization: Bearer <同一把 Key>` |
| Search / Fetch | ¥5 / 1,000 次（各自）；成功且有结果/内容才计费 |
| OCR / 文件解析 | ¥1.5 / 1,000 页；按成功页数；断开不取消、完成照常计费 |
| TTS | ¥3 → **¥1.5** / 10,000 字符（限时 5 折）；中文字符算 2 |
| ASR | ¥0.78 → **¥0.39** / 小时音频（限时 5 折）；按秒，不足 1 秒按 1 秒 |
| 限流（账号级） | Search 100 QPS、Fetch 25 QPS、OCR 15 并发、TTS/ASR 各 50 并发；换 Key 无效 |
| 内置工具计费计数器 | `usage.server_tool_use` 的 `tool_search`/`tool_fetch`/`tool_image_search`；`web_search_requests`/`web_fetch_requests` 只是展示计数 |
| 内置工具副作用 | 开启后 temperature/top_p 等被服务端忽略；用过内置工具的回复不能续写（400）；中途失败已执行的搜索/阅读照常计费；与其他服务端工具声明互斥 |

## 7. 本轮边界

- 未写任何实现代码、未提交、未推送；没有真实 Key（上一次 Search/Fetch 适配也没有，详见 `02` §1.2）。
- 所有契约以 2026-11-13 官网为准；文档未覆盖或与实测不符的部分，列入 `04-plan-and-tests.md` §4 的探测清单。
- 价格是「当前官网价」，限时折扣截止时间官网未公布；实现时应把单价做成可配置常量，并在 UI 标注限时。

## 8. 下一步（待用户决定）

1. 确认适配范围与优先级（是否全做 / 先做 P0+P1 / 只做某一项）。
2. 准备一把真实 Key，先跑 `04` §4 的 8 项探测（尤其是 Chat Completions 的 `server_tool_calls` 形态与 OCR 200-with-error）。
3. 批准后从 P0 开始实施；每项独立开关、默认关闭，逐项验收。

## 9. 实施记录（2026-11-14）

P0+P1+P1b 已落地，ai/app/speech/web/search/common 全量单测绿。要点：

| 包 | 状态 | 与原计划差异 |
|---|---|---|
| P0-1 | ✅ | SearchPicker 白名单加了 `deepseek` 前缀；`BuiltInSearchSetting` 关闭时清全部搜索类变体 |
| P0-2 | ✅ | `filter` 泛化出 `onRemoved` 回调；ASR 选中项被丢 → 移除 `selectedASRProviderId`（不静默回退） |
| P1-2 TTS | ✅ | `synthesize()` 抽成非 flow 内部函数（JVM 测试入口）；speech 模块加 mockwebserver + `isReturnDefaultValues` |
| P1-3 ASR | ✅ | `pcm16ToWav` 下沉 `asr/PcmWav.kt`；响应解析用 kotlinx（org.json 在 JVM 单测是 null 桩）；503/504 重试 1 次 |
| P1-1 OCR | ✅（有留白） | DB 是 **69→70 手写**（资料包 68→69 已过期；本仓 Room 下 AutoMigration 需目标 schema JSON 预导出）；文档入口**本地先解析、抽得出文本就放行**（防文本型 PDF 白花页费）；providerId/baseUrl/maxPages 字段有默认值但 UI 未露出（默认复用内置 DeepSeek Key） |
| P1b | ✅（待真机） | 发裸 `{"type":…}`（无 function 工具时不下发 tool_choice）；原始 call JSON 存 `metadata["deepseek_raw_call"]` 原样回传；注解未知 type 定向放宽（跳过+Log，偏离上游响亮失败）；用过 ServerTool 的轮次禁自动续写+隐藏手动继续 |
| P2-1/2/3 | ✅（2026-11-14） | TokenUsage+3 工具计数（流/非流双路径解析实测形态）、usage_record 三列迁移 70→71、nerd line/统计页刊例价估算、safety_check 开关（仅 DeepSeek host 下发）+拒答复用既有 content_filter 识别；顺手修 NoticeProtocolTest flake（裸 Json encodeDefaults=false） |

**真 Key HTTP 级探测已完成（2026-11-14，结论：实现全部通过、零改动）**——server_tool_calls 精确形态/回传 echo 200/流式整块单帧/OCR 恰两 part/TTS wav 24k/ASR duration_ms 全实锤，详见 `04` §9。设备级 smoke（App 内全链路）仍待装机。
