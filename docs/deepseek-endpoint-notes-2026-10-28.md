# DeepSeek 端点能力声明与 system_fingerprint 实测（2026-10-28）

> 性质：**端点行为实测记录**，不是设计稿、不承诺 API 契约。证据 = 用官方 key 实跑复现（2026-10-28 13:09 本地时间）+ 外部推文截图（图一 `/models`、图二响应指纹，原图未入库）；两路数值完全一致。
> 用途：为「端点能力 / 证据时效」留档，避免以后继续引用过期结论——特别是 `design-context-purification.md` 里 2026-10-27 那批「DeepSeek 三渠道不回传推理」的实测（见 §5）。
> 复现环境：本机 `curl`（Windows Schannel）访问 `api.deepseek.com` 报 `SEC_E_NO_CREDENTIALS`，改用 Python `urllib`（或 PowerShell `Invoke-RestMethod`）成功；探测脚本为临时文件、已删，工作区无改动（§7）。

## 1. `api_capabilities` ← `GET /models`（图一）

`GET https://api.deepseek.com/models`（`Authorization: Bearer $KEY`），2026-10-28 13:09 实测返回：

```json
{"id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek-V4.1-Flash",
 "context_window":1048576,"max_output_tokens":393216,
 "input_modalities":["text","image"],"output_modalities":["text"],
 "effort":{"supported_levels":["low","high","max"],"default_level":"high"},
 "api_capabilities":{"anthropic_messages":{"system_prompt_update":"in-history","tool_update":"addition-only"}}}
```

`deepseek-v4-pro` 同结构，但 `input_modalities` 为 `["text"]`、`api_capabilities.anthropic_messages` 为 `{"system_prompt_update":"leading-only","tool_update":"leading-only"}`。

- **两次抓取的 diff**：10/17 抓到 flash 的 `api_capabilities` 只有 `{"system_prompt_update":"in-history"}`；10/27 多出 `"tool_update":"addition-only"`（图一即这两段原文）。
- 单模型详情 `GET /models/deepseek-flash` 返回同一字段，可用于确认。
- 命名空间是 `anthropic_messages`：这是面向 **Anthropic Messages 兼容层**（`/anthropic/v1/messages`）的**客户端编码建议**，不是 OpenAI 协议的能力位。

声明语义（按字段字面 + 客户端含义）：

| 字段 | `in-history`（flash） | `leading-only`（pro） |
|---|---|---|
| `system_prompt_update` | 中途改系统提示可作为一条 system 消息**追加进 history**，前面已缓存的 prefix 不失效 | 必须并回头部 `system`；塞进 history 会破坏缓存 |
| `tool_update` | 工具只能**追加**（append-only）；删/改/换序都会破坏缓存 | 工具只能在首轮定义好 |

> ⚠️ 这两行是**声明**、不是报错规则；实测行为对照见 §3。

## 2. `system_fingerprint` ← 每次推理响应（图二）

非流式在响应体顶层，流式**每个 chunk** 都带：

| model | `system_fingerprint`（全 32 位） | 前 8 位 |
|---|---|---|
| deepseek-flash | `bc19ba97cbe016479903222bb1578db6` | `bc19ba97` |
| deepseek-v4-pro | `a307abda487cd1b463329ccb945ce396` | `a307abda` |

- 语义：**后端推理版本/权重哈希**（换部署、换权重、换量化都会变），与 prompt、参数无关；同一 alias、同一时刻可能返回两个值 ⇒ 灰度期。
- 推文时间线（外部证据）：18:21–19:07 期间 flash 同时出现 `e72d3c7f` 与 `bc19ba97`（新旧并存＝灰度），19:07 后只剩 `bc19ba97`（全量切完）；10/17 那次靠第三方存档（deepseek-monitor / archive.page.dev）补。
- **fingerprint 变了 ≠ 换模型**：只证明后端版本变了（推文自己也是这么写的：看不出是新模型还是换部署）。
- 用法：固定采样点高频轮询，记 `(时间, model, fingerprint)`；配合 `usage.prompt_cache_hit_tokens / prompt_cache_miss_tokens` 才能看「切指纹后缓存是否重建」这类副作用。

## 3. 声明 ≠ 硬校验（行为对照）

给 `deepseek-v4-pro`（声明 `leading-only`）也塞一条 mid-history 的 `system` 消息：**照样 200 且被采纳**（thinking 里明确出现 "Now answer in French"）。

⇒ `leading-only` 的意思是「客户端应这么编码以保住缓存」，**不是「这么发会报错」**；真正的收益体现在 cache hit 上，而不是错误码上。

顺带修一处常见过度解读：图一那段「现在它底层支持中途动态追加新工具而不打断上下文」方向对，但有两处要补——

1. `addition-only` 是**缓存友好**建议，不是「之前不能用」：声明式字段本来就不做校验（同上一条）；
2. 它只保证**追加**不破坏缓存；**删除/替换/重排工具仍会掉缓存**（推文没写这半句）。

## 4. OpenAI 两种协议（Chat Completions / Responses）

- 同一个 `/models` 模型对象里，`api_capabilities` **只有 `anthropic_messages` 一个命名空间**（10/27、10/28 两次返回均如此）；没有 `chat_completions` / `responses` 兄弟字段 ⇒ **OpenAI 两种协议没有可读的声明式提示**。
- 为什么 Anthropic 层需要、OpenAI 层不需要（协议形状差异）：Anthropic 兼容层把 system 与 tools 放进缓存前缀、且 system 只允许顶层，所以需要告诉客户端「能塞 history 就塞」（flash）/「必须放头部」（pro）；而 Chat Completions 的 `tools` 是每请求独立顶层字段、`messages` 里 system/developer 位置自由；Responses 的 system 只出现在顶层 `instructions`、`input` 不接受 system 角色。**两种协议本身都允许中途改系统提示/工具，没有 400 风险**，成本只是前缀缓存断掉。
- 本仓编码位置（只读核对）：
  - Chat Completions：mid-history system 原样按 `role: "system"` 发送（`ai/src/main/java/app/androidrik/ai/provider/providers/openai/ChatCompletionsAPI.kt:820`）；
  - Responses：第一条 system 进顶层 `instructions`，其余 system 降级为 user 发送（`ai/src/main/java/app/androidrik/ai/provider/providers/openai/ResponseAPI.kt:374-395`）；`store=false` + reasoning item 回填（`ResponseAPI.kt:279`、`:294-300`）⇒ 系统/工具变化都等于前缀变化；
  - Claude 类型 provider 的 `baseUrl` 可改（`ai/src/main/java/app/androidrik/ai/provider/ProviderSetting.kt:397-400`，默认 `https://api.anthropic.com/v1`）⇒ 手动指向 `https://api.deepseek.com/anthropic/v1` 时 `anthropic_messages` 声明才适用；该路径下工具按 `params.tools` 顺序**整表重发**、开 `promptCaching` 时 `cache_control` 打在最后一个工具上（`ClaudeProvider.kt:374-386`）⇒ 追加是尾部扩展（符合 `addition-only`），删/换序必然掉缓存。
- 结论：**OpenAI 两种协议无需读 `api_capabilities`、也无需为它改编码**；「系统提示尽量不改、工具只追加不重排」的经验规则照用，只是没有声明做保证。默认 DeepSeek provider 走 OpenAI 兼容 `https://api.deepseek.com/v1`（`app/src/main/java/app/androidrik/rikkahub/data/datastore/DefaultProviders.kt:114`），不经过 `anthropic_messages` 口径。

## 5. 证据时效：fingerprint 是哨兵

- `design-context-purification.md` 里 2026-10-27 的端点结论（DeepSeek Chat Completions 回传推理后 `prompt_tokens` 49=49 / 102=102；DeepSeek Responses `input_tokens` 59＝基线、960 字符推理零 token；New API 网关 73=73 ——「都不把推理放进上下文」）测的是**后端某一版**。DeepSeek 换 alias 背后的部署是静默发生的 ⇒ **`system_fingerprint` 变化后这批证据应标「需重测」**，不要继续引用；重测时把 `(时间, model, fingerprint, usage)` 一起记。
- 同理，以后要再验「推理是否回传」：用同一条 Python/OkHttp 路径、固定 temperature，除布尔结论外记录 `usage.prompt_cache_hit_tokens / miss_tokens` 与 fingerprint。

## 6. 待确认

- `/models` 是否还有其他 `api_capabilities` 命名空间（逐模型 dump；若都只有 `anthropic_messages`，§4 的结论定稿）：

```python
import json, os, urllib.request
req = urllib.request.Request("https://api.deepseek.com/models",
    headers={"Authorization": "Bearer " + os.environ["DEEPSEEK_API_KEY"]})
for m in json.load(urllib.request.urlopen(req))["data"]:
    print(m["id"], json.dumps(m.get("api_capabilities"), ensure_ascii=False, indent=1))
```

- `addition-only` 是否也接受「追加新工具、但改动既有工具描述」（未测）；`leading-only` 在 Responses 路径下是否有对应语义（未测）。

## 7. 环境备注

- 本机 `curl`（Windows Schannel）访问 `api.deepseek.com` 报 `SEC_E_NO_CREDENTIALS`；`--insecure` / `--ssl-no-revoke` 未验证可解，实际改走 Python `urllib`。
- 临时探测脚本已删；本记录只留字段与结论，**不含 key、不含响应全文**。
- 相关：`docs/design/design-context-purification.md`（§2 端点作用域表、§8 实测）、`docs/roadmap.md`。
