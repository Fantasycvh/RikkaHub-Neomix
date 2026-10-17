package app.androidrik.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具输出的**完整性声明**（`_meta`）。
 *
 * ## 为什么要有
 *
 * 「截断要说出来」以前是各工具各写各的散文（`note` / `omitted`），人能读、程序不能读：
 * 上层 UI、遥测、以及将来想按「是否被削过」分支的调用方都只能去匹配中文/英文句子。
 * 这里把同一件事收敛成一个**结构化**字段，散文保留不动（纯加法，不破坏既有断言与 UI）。
 *
 * ## 契约
 *
 * ```
 * "_meta": {
 *   "truncated": true,
 *   "omitted": { "kind": "chars" | "items", "count": 12345 },
 *   "hint": "…可执行的下一步…"
 * }
 * ```
 *
 * - **只在真的丢了东西时出现**：`_meta` 缺席 == 输出完整。完整这条路径（99% 的情况）
 *   不增加任何字节，所以别在没截断时顺手 `put` 一个 `truncated: false`。
 * - `omitted.kind`：`chars` = 按字符削掉的正文；`items` = 整条没返回的记录/条目。
 * - `omitted.count`：被丢掉的数量（与 `kind` 同单位）。
 * - `hint`：必须是**本仓真实存在的下一步工具调用**，不写「narrow the query」这种空话。
 * - 结果体不是 JSON object 时（纯文本结果）**不套**本声明——没有顶层键可挂。
 */
internal object ToolOutputMeta {

    /** 声明用的顶层键。 */
    const val KEY = "_meta"

    /**
     * 组装声明体；`count <= 0` 时返回 `null`。
     *
     * 为什么 0 不写声明：`_meta` 缺席本身就是「完整」的唯一判据，写一个
     * `count: 0` 的条目等于自己跟自己打架（声明「被截断」却说一个都没丢）。
     * 而「丢了 0 字节」在语义上就是没丢，缺席反而是**正确**的回答。
     */
    fun payload(kind: OmittedKind, count: Int, hint: String): JsonObject? {
        require(hint.isNotBlank()) { "hint must be actionable, not blank" }
        if (count <= 0) return null
        return buildJsonObject {
            put("truncated", true)
            put(
                "omitted",
                buildJsonObject {
                    put("kind", kind.wireName)
                    put("count", count)
                },
            )
            put("hint", hint)
        }
    }
}

/** 被丢掉的东西以什么为单位。取值直接就是线上字符串，改动即破坏契约。 */
internal enum class OmittedKind(val wireName: String) {
    /** 正文按字符削掉（单条消息被切、日志被切）。 */
    CHARS("chars"),

    /** 整条记录/消息没有返回。 */
    ITEMS("items"),
}

/**
 * 在工具结果 JSON 上挂 `_meta`。
 *
 * 调用点纪律（源码守卫见 `ToolTruncationDeclarationGuardTest`）：**凡手写「我截断了」的地方
 * 都必须走这里**，否则模型只拿到散文、拿不到可机读的损失量与下一步。
 * 这样散文（`note` / `omitted`）与 `_meta` 不会各说各话。
 */
internal fun JsonObjectBuilder.markTruncated(kind: OmittedKind, count: Int, hint: String) {
    val meta = ToolOutputMeta.payload(kind, count, hint) ?: return
    put(ToolOutputMeta.KEY, meta)
}
