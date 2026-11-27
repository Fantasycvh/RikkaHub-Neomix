package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant

/**
 * 聊天输入框发送前凭据检测（借鉴 Miffan `51c6b0f2`）：
 *
 * 在用户点发送前扫描文本中的高置信敏感凭据（不修改文本、不静默脱敏，仅返回命中分类与数量，
 * 由聊天页弹窗确认，防止用户误把 API Key / Token / 私钥发进模型上下文并落库）。
 *
 * 规则复用 [Redactor] 的三层检测（L3 私钥块 / L1 已配置供应商 Key 与请求头 / L2 高置信前缀），
 * 并额外覆盖 `Authorization: Bearer <token>` / `Bearer <token>` 形态（排除常见占位符）。
 * 返回结构绝不携带原始敏感文本。
 */
internal object ComposerCredentialDetector {

    enum class Kind {
        PRIVATE_KEY,
        REGISTERED_CREDENTIAL,
        API_KEY,
        TOKEN,
        BEARER_TOKEN,
    }

    data class Warning(
        val kinds: List<Kind>,
        val totalCount: Int,
    ) {
        val hasFindings: Boolean get() = totalCount > 0
    }

    // 匹配 Bearer 后至少 16 字符的实值凭据；后面过滤常见文档占位符
    private val BEARER_PATTERN = Regex(
        """(?i)\bBearer\s+([A-Za-z0-9._~+/=-]{16,})"""
    )

    private val PLACEHOLDER_WORDS = setOf(
        "your_token",
        "your-token",
        "your_api_key",
        "your-api-key",
        "your_access_token",
        "your-access-token",
        "access_token",
        "bearer_token",
        "example_token",
        "placeholder",
        "redacted",
    )

    fun scanParts(
        parts: List<UIMessagePart>,
        providers: List<ProviderSetting>,
        assistants: List<Assistant>,
    ): Warning {
        val text = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        if (text.isBlank()) return Warning(emptyList(), 0)
        return scanText(text, collectCredentials(providers, assistants))
    }

    fun scanText(text: String, credentials: Set<String> = emptySet()): Warning {
        if (text.isBlank()) return Warning(emptyList(), 0)

        val redacted = Redactor.redact(text, credentials)
        val kinds = linkedSetOf<Kind>()
        var total = 0

        fun addFromRedactor(key: String, kind: Kind) {
            val count = redacted.counts[key] ?: 0
            if (count > 0) {
                kinds += kind
                total += count
            }
        }

        addFromRedactor(Redactor.KIND_PRIVATE_KEY, Kind.PRIVATE_KEY)
        addFromRedactor(Redactor.KIND_CREDENTIAL, Kind.REGISTERED_CREDENTIAL)
        addFromRedactor(Redactor.KIND_API_KEY, Kind.API_KEY)
        addFromRedactor(Redactor.KIND_TOKEN, Kind.TOKEN)

        // 在已脱敏文本上再扫 Bearer：避免 `Bearer sk-...` 或 `Bearer eyJ...` 被重复计数
        var bearerHits = 0
        for (match in BEARER_PATTERN.findAll(redacted.text)) {
            val token = match.groupValues[1]
            if (!isObviousPlaceholder(token)) {
                bearerHits++
            }
        }
        if (bearerHits > 0) {
            kinds += Kind.BEARER_TOKEN
            total += bearerHits
        }

        return Warning(kinds = kinds.toList(), totalCount = total)
    }

    private fun isObviousPlaceholder(token: String): Boolean {
        val lower = token.lowercase()
        if (lower in PLACEHOLDER_WORDS) return true
        if (lower.startsWith("your_") || lower.startsWith("your-") || lower.startsWith("example")) return true
        // 全是重复字符（如 xxxxxxxxxxxxxxxx / ****************）
        if (token.toSet().size <= 2) return true
        return false
    }
}
