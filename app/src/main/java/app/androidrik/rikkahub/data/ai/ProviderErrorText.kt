package app.androidrik.rikkahub.data.ai

/**
 * 把已知的**线路侧**错误翻译成可执行提示。
 *
 * ## 为什么需要它
 *
 * 2026-10-18 实测证据：同一个中转（`api.rinko.ai`）在收到**完全配平**的报文时仍拒绝 ——
 * 发送前临时诊断打印 `assistant.tool_calls=18, role:tool=18`（一条不差），
 * 中转返回 400 并报：
 *
 * > Please ensure that the number of function response parts is equal to the number of
 * > function call parts of the function call turn.
 *
 * 这句是 **Gemini 原生错误**，说明中转把 OpenAI 的 `tool` 消息重建成 Gemini `contents` 时丢了
 * `functionResponse`。**客户端无法修正**（我方报文已合法），只能告知用户换线路。
 *
 * 对照：同一份报文换其它中转/提供商可正常完成（12:10 那几轮 25/25、28/28、29/29、33/33 全通过）。
 */
private const val RELAY_TOOL_PAIRING = "number of function response parts"

internal const val RELAY_TOOL_PAIRING_HINT =
    "（线路侧问题：该中转会拒绝带工具调用的请求，换模型或换线路可绕过。" +
        "原始报文已校验配平，非本地缺陷）\n" +
        "(Relay-side issue: this endpoint rejects requests carrying tool calls; " +
        "switch model or endpoint. The outgoing payload was verified balanced.)"

/**
 * 命中已知线路侧错误时返回可读提示，否则返回 null（交给原有错误展示）。
 */
fun providerErrorHint(message: String?): String? {
    val text = message ?: return null
    return if (text.contains(RELAY_TOOL_PAIRING, ignoreCase = true)) RELAY_TOOL_PAIRING_HINT else null
}

/**
 * 在保留原始错误文本的前提下追加提示；未命中则原样返回。
 */
fun Throwable.withProviderErrorHint(): Throwable {
    val hint = providerErrorHint(message) ?: return this
    return IllegalStateException("${message.orEmpty()}\n\n$hint", this)
}
