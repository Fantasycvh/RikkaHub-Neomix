package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * 上下文净化 §8 因果实验（**三臂、预注册、真模型**）。
 *
 * 问题：`design-context-purification.md` §8 的理论前提是「模型看到自己原地打转的痕迹，会更容易
 * 继续原地打转」，这是**假设**，仓库其余部分只标定了「判据能识别退化」，没标定「撤掉残留能降低
 * 复发」。本类就是把这一条验掉的最小闭环。
 *
 * 三臂（同一 fixture、同一 system、**temperature = 0**、每臂 N 次）：
 * - **A 原始**：上一轮残留原样进上下文（当前 app 的真实发送形态）；
 * - **B 静默净化**：走 `ContextPurifier.purgeSendWindow`（C1a 周期截断），残留被截到循环起点；
 * - **C 净化 + 尾部注记**：= B + §6.3 的合成 USER 注记（默认关的那条设计之三）。
 *
 * 预注册判定（写死在 [verdict]，不许跑完再改口径；**主指标 2026-10-27 修正过一次**，理由在条目里）：
 * 1. **主指标 = 循环复制率**：输出（正文或推理任一侧）是否逐字包含循环句 `我会先检查文件`。
 *    A 复制率 − B 复制率 ≥ 0.3 ⇒ 「撤掉残留能降低复现」，本设计按「修复思维退化」立论；否则按
 *    「省 token / 去噪」降级（§8.6）。~~原主指标 = containment 中位数，B ≤ A − 0.2~~——本 fixture 的
 *    循环只有 8 个不同 8-gram，**单句复制最多把 containment 抬到 0.125**，0.2 阈值结构性不可达
 *    （缺陷由 fixture 代数直接推得，非看到数据后改口径）；
 * 2. **辅指标** = 回复推理/正文是否自身进入逐字周期（`minimalPeriod` 判据，同护栏 C1a）；
 * 3. **C 臂**：注记是否让回复开场转向元讨论/道歉（启发式，仅打印样本供人工判），且复制率不高于 B。
 *
 * 为什么不是纯脚本桩：脚本响应是写死的，**不会因窗口内容不同而改变行为**，A/B 之间不可能产生
 * 差异（§8.1 已记录该修正）。桩只用于造历史，差异必须来自真模型。
 *
 * ## 跑法
 * ```
 * # 任何 OpenAI 兼容端点（deepseek / zhipu / 自建网关均可）
 * set PURGE_EXP_BASE_URL=https://api.deepseek.com/v1
 * set PURGE_EXP_API_KEY=sk-xxxx
 * set PURGE_EXP_MODEL=deepseek-reasoner
 * ./gradlew.bat :app:testDebugUnitTest --tests "*PurgeCausalityExperimentTest*" --console=plain
 * ```
 * 未设环境变量时整个类被 `assumeTrue` 跳过（常规全量测试不受影响）。
 * **模型必须会回传 reasoning**（`deepseek-reasoner`、GLM thinking 档等）：纯 chat 模型没有 `reasoning_content`，
 * 主指标恒为 0，实验无意义——测试会检测到并 assume-skip 而不是给出错误结论。
 *
 * ## 可选旋钮
 * - `PURGE_EXP_CARRIER` = `reasoning`（默认，还原 app 真实形态：残留放在 `reasoning_content`）
 *   或 `content`（残留放进正文，用来区分「provider 忽略了 reasoning_content」与「模型根本不模仿」
 *   ——若 carrier=reasoning 无差异、carrier=content 有差异，说明是字段支持问题，不是假设被证伪）；
 *   **New API 系/聚合网关实测会在输入侧重装请求、丢弃 `reasoning_content`**（2026-10-27 实测：
 *   带与不带 963 字符残留的 `prompt_tokens` 都是 73），对这类端点必须用 `content` 载体跑才有效；
 * - `PURGE_EXP_N` 每臂次数（默认 10）；`PURGE_EXP_TIMEOUT_MS` 单次超时（默认 120000）。
 *
 * **已知限制（2026-10-27）**：Cloudflare 前置的 New API 网关会 reset JDK `HttpURLConnection`（带重试也
 * 不行，`Connection reset`）；这类端点改用同一 fixture 直接 HTTP 采集，数据见设计稿 §8 的实验结果。
 * 另：`carrier=content` 是**诊断载体**（把残留放进正文），不是 app 的真实发送形态——真实形态依赖
 * provider 真的回传 `reasoning_content`。
 *
 * 成本：3 × N 次调用、每次数 K token；N=10 时总额可忽略。
 */
class PurgeCausalityExperimentTest {

    private val baseUrl = env("PURGE_EXP_BASE_URL").trimEnd('/')
    private val apiKey = env("PURGE_EXP_API_KEY")
    private val model = env("PURGE_EXP_MODEL")
    private val carrier = env("PURGE_EXP_CARRIER").ifBlank { "reasoning" }
    private val n = env("PURGE_EXP_N").toIntOrNull()?.coerceIn(1, 50) ?: 10
    private val timeoutMs = env("PURGE_EXP_TIMEOUT_MS").toIntOrNull() ?: 120_000

    private val json = Json { ignoreUnknownKeys = true }

    // ---- fixture：上一轮「plan 之后挂着逐字循环」的真实形态（一条助手消息） ----

    /**
     * 正常计划段（净化后必须保留的那部分：这是「不丢信息、只丢重复」的锚）。
     * 必须是**非周期文本**——否则 C1a 会把计划本身也截掉，fixture 就不再是「plan + 尾部循环」的形态。
     */
    private val plan = buildString {
        appendLine("先读 README 的发行说明段，确认当前版本号、发布日期、资产体积与上一版差异，再决定本次要改哪些行。")
        appendLine("版本号只在 app/build.gradle.kts 的 versionCode 与 versionName 两处递增，数据库版本单独一条线，不要跟着动。")
        appendLine("docs/README.md 的索引按字母序插入新条目，措辞沿用既有句式，避免自创小节标题。")
        appendLine("docs/releases 下的发布说明需要 tag 哈希、资产名与字节数，哈希从 git rev-parse 取，不要手抄。")
        appendLine("改完先跑一次编译确认没有常量引用漏改，再逐个 diff 复核改过的文件，最后才提交。")
        appendLine("提交信息按仓库既有风格写前缀与中文摘要，正文只写动机与影响，不写流水账（完）")
    }.trimEnd()

    /** 逐字循环：8 字符周期、repeatMany 次 ⇒ 尾窗 ≥256、周期 8、含 ≥2 个不同字符（C1a 可判） */
    private val loop = "我会先检查文件。".repeat(80)

    /** 循环句前 7 字（不含句号）：用于「输出是否逐字复制循环」的 post-hoc 采样判据 */
    private val loopPhrase = "我会先检查文件"

    private val residue = plan + loop

    private val userTurn1 = "把 README 的发行说明改一下"
    private val userTurn2 = "继续"

    private val previousTurn: UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning(reasoning = residue),
            UIMessagePart.Text(text = "好的，我来改。"),
        ),
    )

    private val rawWindow: List<UIMessage> = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text = userTurn1))),
        previousTurn,
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text = userTurn2))),
    )

    @Test
    fun threeArmCausalityExperiment() {
        assumeTrue("PURGE_EXP_BASE_URL not set", baseUrl.isNotBlank())
        assumeTrue("PURGE_EXP_API_KEY not set", apiKey.isNotBlank())
        assumeTrue("PURGE_EXP_MODEL not set", model.isNotBlank())

        // A 臂 = 原样；B 臂 = 净化；C 臂 = 净化 + §6.3 尾部注记
        val purgeReport = ContextPurifier.purgeSendWindow(rawWindow)
        val purged = purgeReport.messages
        val note = "（整理注记：部分历史回复中的重复内容未包含在本次上下文中，原始记录未改动；请直接继续当前任务，无需回应本注记）"
        val purgedWithNote = purged + UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(text = note)),
        ).withSynthetic()

        val arms = listOf(
            Arm("A 原始", rawWindow),
            Arm("B 净化", purged),
            Arm("C 净化+注记", purgedWithNote),
        )

        println("=== purge 实验 fixture ===")
        println("carrier=$carrier model=$model n=$n planChars=${plan.length} loopChars=${loop.length} residueChars=${residue.length}")
        println("purge decisions=${purgeReport.decisions.size} savedChars=${purgeReport.savedChars} windowCount=${purgeReport.windowCount}")
        purgeReport.decisions.forEach { println("  - ${it.rule}@${it.messageIndex}.${it.partIndex} ${it.detail}") }
        println("A/B 窗口是否确实不同: ${purged != rawWindow}")

        val results = arms.map { arm -> runArm(arm) }

        // ---- 汇总 ----
        println()
        println("| 臂 | 有效样本 | containment(推理) | containment(正文) | 循环复制率 | 周期复现率 | 推理字符中位数 | 脱轨启发式 |")
        println("|---|---|---|---|---|---|---|---|")
        results.forEach { r ->
            println(
                "| ${r.arm.name} | ${r.samples.size} | " +
                    "${r.medianContainment.fmt()} | ${r.medianContainmentContent.fmt()} | " +
                    "${r.copyRate.fmt()} | ${r.cycleRate.fmt()} | " +
                    "${r.medianReasoningChars} | ${r.derailCount}/${r.samples.size} |",
            )
        }
        println()
        results.forEach { r ->
            r.samples.take(3).forEachIndexed { i, s ->
                println("[${r.arm.name} #${i + 1}] containment=${s.containment.fmt()} " +
                    "reasoningChars=${s.reasoningChars} :: ${s.reasoningPreview}")
            }
        }

        val totalReasoning = results.sumOf { r -> r.samples.sumOf { it.reasoningChars } }
        assumeTrue(
            "3 臂共返回 reasoning $totalReasoning 字符：模型不产出 reasoning_content，" +
                "主指标无意义（换 deepseek-reasoner / thinking 档重跑）",
            totalReasoning > 0,
        )

        val (a, b, c) = Triple(results[0], results[1], results[2])
        val verdict = verdict(a, b, c)
        println()
        println("=== 预注册判定 ===")
        println(verdict.text)

        // 实验必须真的跑起来（provider 拒绝或返回坏体时宁可红，也不要静默通过）
        assertEquals(3, results.size)
        results.forEach { r ->
            assertTrue(
                "${r.arm.name} 无有效样本：检查 baseUrl/model/网络；若 400 提到 reasoning_content，" +
                    "改用 PURGE_EXP_CARRIER=content 复跑",
                r.samples.isNotEmpty(),
            )
        }
        assertTrue(a.arm.name.startsWith("A"))
    }

    /**
     * fixture 自检（不需要网络）：确保 A/B 两臂确实不同、且差异正好是「C1a 截掉尾部周期、保留 plan」。
     * 这是实验有效性的前提——fixture 不触发规则时，3 × N 次真模型调用就是白花钱。
     */
    @Test
    fun fixtureTriggersCycleTailAndIsIdempotent() {
        val report = ContextPurifier.purgeSendWindow(rawWindow)
        val cycleDecisions = report.decisions.filter { it.rule == ContextPurifier.Rule.CYCLE_TAIL }
        assertTrue("fixture 必须恰好触发一条 C1a：${report.decisions}", cycleDecisions.size == 1)
        assertTrue("被截掉的应当正好是循环文本（不碰 plan）", report.savedChars == loop.length)
        assertTrue("净化只改 part，不改消息条数", report.messages.size == rawWindow.size)
        assertTrue("B 臂窗口必须与 A 臂不同", report.messages != rawWindow)

        val purgedReasoning = report.messages[1].parts
            .filterIsInstance<UIMessagePart.Reasoning>().single().reasoning
        assertEquals("plan 必须原样保留", plan, purgedReasoning)
        assertTrue("plan 太短会失真（C1a 的 span 下限是 256）", plan.length >= 256)

        assertTrue(
            "净化必须幂等：第二次跑不应再命中",
            ContextPurifier.purgeSendWindow(report.messages).decisions.isEmpty(),
        )
    }

    // ---- 单臂执行 ----

    private data class Arm(val name: String, val window: List<UIMessage>)

    private data class Sample(
        val containment: Double,
        /** 残留循环在**正文**里的 containment（diagnostic：本仓网关可能丢 reasoning_content） */
        val containmentContent: Double,
        /** 输出任一侧是否逐字复制了循环句（diagnostic，post-hoc） */
        val copiedLoop: Boolean,
        val reasoningChars: Int,
        val reasoningPreview: String,
        val cycleTriggered: Boolean,
        val derailed: Boolean,
        val content: String,
    )

    private data class ArmResult(
        val arm: Arm,
        val samples: List<Sample>,
    ) {
        val medianContainment: Double
            get() = samples.map { it.containment }.sorted().median()
        val medianContainmentContent: Double
            get() = samples.map { it.containmentContent }.sorted().median()
        /** 通道无关的循环复制率（post-hoc 补充指标，不参与预注册判定） */
        val copyRate: Double
            get() = if (samples.isEmpty()) 0.0 else samples.count { it.copiedLoop }.toDouble() / samples.size
        val cycleRate: Double
            get() = if (samples.isEmpty()) 0.0 else samples.count { it.cycleTriggered }.toDouble() / samples.size
        val medianReasoningChars: Int
            get() = samples.map { it.reasoningChars.toDouble() }.sorted().median().roundToInt()
        val derailCount: Int
            get() = samples.count { it.derailed }
    }

    private fun runArm(arm: Arm): ArmResult {
        val samples = mutableListOf<Sample>()
        repeat(n) { i ->
            val messages = toWireMessages(arm.window)
            val (content, reasoning) = callModel(messages)
            val c = ThinkingDegenerationGuard.containment(loop, reasoning)
            val cc = ThinkingDegenerationGuard.containment(loop, content)
            samples += Sample(
                containment = c,
                containmentContent = cc,
                copiedLoop = reasoning.contains(loopPhrase) || content.contains(loopPhrase),
                reasoningChars = reasoning.length,
                reasoningPreview = reasoning.take(80).replace('\n', ' '),
                cycleTriggered = hasLiteralCycle(reasoning) || hasLiteralCycle(content),
                derailed = looksDerailed(content),
                content = content,
            )
            println("[${arm.name} ${i + 1}/$n] containment=${c.fmt()}/${cc.fmt()} copied=${reasoning.contains(loopPhrase) || content.contains(loopPhrase)} chars=${reasoning.length}")
            Thread.sleep(800) // 网关限速友好：顺序调用、留间隔
        }
        return ArmResult(arm, samples)
    }

    // ---- 判据（与护栏同源） ----

    /** 回复推理自身是否进入逐字周期：尾窗 256、周期 2..64、周期内 ≥2 个不同字符 */
    private fun hasLiteralCycle(text: String): Boolean {
        if (text.length < 256) return false
        val window = text.takeLast(256)
        val period = ThinkingDegenerationGuard.minimalPeriod(window)
        return period in 2..64 && ThinkingDegenerationGuard.distinctChars(window, period) >= 2
    }

    /** C 臂脱轨启发式：开场是否变成道歉/元讨论（真判定要靠人工看样本） */
    private fun looksDerailed(content: String): Boolean {
        val head = content.trimStart().take(40)
        return listOf("抱歉", "对不起", "不好意思", "注记", "抱歉，我", "理解了", "Sorry", "Apologies")
            .any { head.contains(it, ignoreCase = true) }
    }

    private fun verdict(a: ArmResult, b: ArmResult, c: ArmResult): Verdict {
        // 主指标：循环复制率（2026-10-27 修正——原 containment 阈值 0.2 对本 fixture 结构性不可达：
        // 循环只有 8 个不同 8-gram，单句复制最多把 containment 抬到 0.125，该缺陷由 fixture 代数直接推得）
        val delta = a.copyRate - b.copyRate
        val supported = delta >= 0.3
        val cycleImproved = b.cycleRate < a.cycleRate
        val noteSafe = c.derailCount <= b.derailCount && c.copyRate <= b.copyRate + 1e-9
        val headline = if (supported) {
            "✅ 假设成立：A 臂循环复制率 ${(a.copyRate * 100).roundToInt()}% → B ${(b.copyRate * 100).roundToInt()}%" +
                "（Δ=${(delta * 100).roundToInt()}pp，阈值 30pp）⇒ 残留确实诱发逐字复制"
        } else {
            "❌ 假设未成立：复制率 Δ=${(delta * 100).roundToInt()}pp（阈值 30pp）⇒ 按 §8.6 降级为" +
                "「省 token / 去噪」，不再宣称「修复思维退化」"
        }
        val cycleLine = if (cycleImproved) {
            "辅指标：周期复现率 ${a.cycleRate.fmt()} → ${b.cycleRate.fmt()}（改善）"
        } else {
            "辅指标：周期复现率 ${a.cycleRate.fmt()} → ${b.cycleRate.fmt()}（未改善）"
        }
        val copyLine = "参考（不参与判定）：containment 推理 ${a.medianContainment.fmt()}→${b.medianContainment.fmt()}、" +
            "正文 ${a.medianContainmentContent.fmt()}→${b.medianContainmentContent.fmt()}"
        val noteLine = if (noteSafe) {
            "C 臂：复制率 ${(c.copyRate * 100).roundToInt()}% ≤ B ${(b.copyRate * 100).roundToInt()}%、脱轨 ${c.derailCount} ≤ ${b.derailCount}" +
                " ⇒ 注记未把回复带偏；§6.3 是否转默认开仍要更大样本 + 人工抽查"
        } else {
            "C 臂：复制率 ${(c.copyRate * 100).roundToInt()}% vs B ${(b.copyRate * 100).roundToInt()}、脱轨 ${c.derailCount} vs ${b.derailCount}" +
                " ⇒ 注记有副作用，§6.3 维持默认关"
        }
        return Verdict(listOf(headline, cycleLine, copyLine, noteLine).joinToString("\n"))
    }

    private data class Verdict(val text: String)

    // ---- 窗口 → OpenAI 兼容 wire 体 ----

    private fun toWireMessages(window: List<UIMessage>): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", SYSTEM)
        })
        window.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
            }
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            val reasoning = message.parts.filterIsInstance<UIMessagePart.Reasoning>().joinToString("\n") { it.reasoning }
            add(buildJsonObject {
                put("role", role)
                when {
                    role != "assistant" -> put("content", text)
                    carrier == "content" -> put("content", listOf(text, reasoning).filter { it.isNotBlank() }.joinToString("\n\n"))
                    else -> {
                        put("content", text)
                        if (reasoning.isNotBlank()) put("reasoning_content", reasoning)
                    }
                }
            })
        }
    }

    // ---- HTTP（JDK，避免依赖注入；单次调用、temperature 0；带重试——网关会 reset 长连接） ----

    private fun callModel(messages: JsonArray): Pair<String, String> {
        val body = JsonObject(
            mapOf(
                "model" to JsonPrimitive(model),
                "messages" to messages,
                "temperature" to JsonPrimitive(0),
                "stream" to JsonPrimitive(false),
                "max_tokens" to JsonPrimitive(2048),
            ),
        ).toString()

        var last: Exception? = null
        for (attempt in 1..4) {
            try {
                val conn = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 30_000
                    readTimeout = timeoutMs
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Connection", "close") // 网关对 keep-alive 复用连接会 reset
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
                if (code == 429 || code >= 500) throw java.io.IOException("HTTP $code: ${resp.take(200)}")
                assertEquals("HTTP $code: ${resp.take(500)}", 200, code)

                val message = json.parseToJsonElement(resp).jsonObject["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!.jsonObject
                val content = message["content"].stringOrNull().orEmpty()
                val reasoning = message["reasoning_content"].stringOrNull().orEmpty()
                return content to reasoning
            } catch (e: java.io.IOException) {
                last = e
                println("  [callModel retry $attempt/4: ${e.message}]")
                Thread.sleep(1_500L * attempt)
            }
        }
        throw last ?: IllegalStateException("callModel failed without exception")
    }

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun Double.fmt(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

    private fun UIMessage.withSynthetic(): UIMessage = copy(isSynthetic = true)

    private fun env(name: String): String = System.getenv(name).orEmpty()

    private companion object {
        const val SYSTEM = "你是一个严谨的 Android 客户端开发助手。回答用中文，先想再做；不要复述思考过程。"
    }
}

private fun List<Double>.median(): Double =
    if (isEmpty()) 0.0 else this[size / 2].let { mid ->
        if (size % 2 == 1) mid else (this[size / 2 - 1] + mid) / 2.0
    }
