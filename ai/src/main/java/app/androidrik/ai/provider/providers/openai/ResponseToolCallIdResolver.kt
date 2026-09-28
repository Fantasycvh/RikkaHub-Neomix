package app.androidrik.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 流式 Responses API 的 **toolCallId 归一**（上游 `5ac149b9` / #1948 的等价实现）。
 *
 * **问题**：规范里 function_call 的 `item.id`（形如 `fc_xxx`）与 `call_id`（形如 `call_xxx`）
 * 是两个不同的标识，而**回传下一轮请求时必须用 `call_id`**（`ResponseAPI.buildMessages` 里
 * `put("call_id", tool.toolCallId)`）。本仓原先用 `item.id` 当 `toolCallId`，于是：
 * 1. 多轮工具调用会把 `fc_xxx` 当 `call_id` 发回去（协议不符，严格网关直接失败）；
 * 2. 兼容网关常在同一流里**混用**两种 id——`output_item.added` 带 `item.id`，
 *    而 `function_call_arguments.*` 不带 `item_id`、只带 `call_id` 或 `output_index`。
 *    各事件各取所需就会得到两个不同的 id，工具参数永远合并不上。
 *
 * **做法**：一次流内统一到**规范 id**（`call_id` 优先，无则退回 `item.id`），并记住
 * 「item.id → 规范 id」「output_index → 规范 id」两张映射，供后续 arguments 事件回查：
 *
 * | arguments 事件里有什么 | 解析结果 |
 * |---|---|
 * | `item_id` | 查映射（有则规范 id，无则原样——正常 OpenAI 形状） |
 * | `call_id` | 直接用（本就是要发回去的那个） |
 * | 只有 `output_index` | 查下标映射（网关连 id 都不给时） |
 *
 * **生命周期：每次流式请求新建一个**。`ResponseAPI` 是进程级共享实例
 * （`ProviderManager` 只注册一个 `OpenAIProvider`），状态**绝不能**放它的字段——
 * 否则并发流互相污染、条目还无限增长。
 */
internal class ResponseToolCallIdResolver {

    private val canonicalByItemId = mutableMapOf<String, String>()
    private val canonicalByOutputIndex = mutableMapOf<Int, String>()

    /**
     * 登记 `output_item.added` / `output_item.done` 里的 item。
     *
     * @param itemId `item.id`（缺失时调用方已退回 `call_id`）
     * @param callId `item.call_id`（可空）
     * @param outputIndex 顶层 `output_index`（可空）
     * @return 该工具的**规范 id**（`call_id` 优先）——所有后续事件都要与它对齐
     */
    fun registerItem(itemId: String, callId: String?, outputIndex: Int?): String {
        val canonical = callId?.takeIf { it.isNotBlank() } ?: itemId
        canonicalByItemId[itemId] = canonical
        outputIndex?.let { canonicalByOutputIndex[it] = canonical }
        return canonical
    }

    /**
     * 解析 `function_call_arguments.delta/done` 的 toolCallId（回退顺序见类注释）。
     *
     * @throws IllegalStateException 三种 id 全无——与上游一致地明确失败，
     *   而不是静默产出一个空 id 的工具调用（那会导致参数无法合并且无从排查）
     */
    fun resolveArgumentsId(payload: JsonObject): String {
        val itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull
        val callId = payload["call_id"]?.jsonPrimitive?.contentOrNull
        val outputIndex = payload["output_index"]?.jsonPrimitive?.intOrNull
        return itemId?.let { canonicalByItemId[it] ?: it }
            ?: callId?.takeIf { it.isNotBlank() }
            ?: outputIndex?.let { canonicalByOutputIndex[it] }
            ?: error("item_id not found")
    }
}
