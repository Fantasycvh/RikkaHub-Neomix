package app.androidrik.rikkahub.data.ai.openai

import android.util.Log
import app.androidrik.ai.provider.CodexSubscriptionAccount
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.providers.openai.CodexToken
import app.androidrik.ai.provider.providers.openai.OpenAICodexTokenProvider
import app.androidrik.common.http.await
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "OpenAICodexAuth"

/** Codex CLI 的公开 client id（非机密，device flow 用）。 */
internal const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
internal const val CODEX_DEVICE_CODE_URL = "https://auth.openai.com/api/accounts/deviceauth/usercode"
internal const val CODEX_DEVICE_TOKEN_URL = "https://auth.openai.com/api/accounts/deviceauth/token"
internal const val CODEX_TOKEN_URL = "https://auth.openai.com/oauth/token"
internal const val CODEX_DEVICE_VERIFY_URL = "https://auth.openai.com/codex/device"
internal const val CODEX_DEVICE_REDIRECT_URI = "https://auth.openai.com/deviceauth/callback"

/** 设备码总有效期：申请时服务端给 15 分钟，这里兜底同样时长。 */
private const val DEVICE_CODE_TIMEOUT_MS = 15 * 60 * 1000L

/** access token 过期前多久刷新（避免请求边界踩线）。 */
private const val REFRESH_LEEWAY_MS = 5 * 60 * 1000L

/** 登录失败分类：UI 按它映射本地化文案并决定是否清凭据。 */
enum class CodexAuthErrorCode {
    /** 直连被地区封锁（需要代理） */
    REGION_BLOCKED,

    /** 设备授权在等待用户操作（轮询中间态，不展示为错误） */
    PENDING,

    /** 轮询过快 */
    SLOW_DOWN,

    /** 设备码/授权码过期，需要重新发起登录 */
    EXPIRED,

    /** refresh token 失效/被回收，需要重新登录 */
    INVALID_GRANT,

    /** 网络/代理不可用 */
    NETWORK,

    UNKNOWN,
}

class CodexAuthException(
    val code: CodexAuthErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 设备码（一次性，只在内存与 UI 中出现，不落盘、不打日志）。 */
data class CodexDeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Int,
    val verificationUrl: String = CODEX_DEVICE_VERIFY_URL,
)

/** 兑换/刷新接口解析后的令牌集合。 */
internal data class CodexTokenResult(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresAtMs: Long,
    val accountId: String?,
    val email: String?,
    val planType: String?,
)

/**
 * ChatGPT 订阅（Codex）登录服务。
 *
 * 职责：设备码登录、令牌刷新、加密落盘，并实现 ai 层的 [OpenAICodexTokenProvider]。
 * 不依赖前台服务/回调服务器：登录期间协程挂在调用方作用域（设置页），浏览器用外部 Intent。
 *
 * 安全口径见 `docs/design/drafts/design-codex-subscription-login.md` 第八节：
 * 明文令牌只在内存/请求头出现，落盘一律 [SecretCipher] 密文。
 */
class OpenAICodexAuthService(
    private val settingsStore: SettingsStore,
    private val client: OkHttpClient,
) : OpenAICodexTokenProvider {

    private data class CachedToken(val token: CodexToken, val expiresAtMs: Long)

    private val cachedTokens = ConcurrentHashMap<Uuid, CachedToken>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    // ---- 登录 ----

    /**
     * 走完设备码登录并把凭据写入指定 provider。
     *
     * @param onDeviceCode 拿到设备码后回调（UI 展示 + 打开浏览器），可挂起直到用户点击。
     */
    suspend fun startLogin(
        providerId: Uuid,
        onDeviceCode: suspend (CodexDeviceCode) -> Unit,
    ): CodexSubscriptionAccount {
        val deviceCode = requestDeviceCode()
        onDeviceCode(deviceCode)
        val code = pollForAuthorizationCode(deviceCode)
        val tokens = exchangeAuthorizationCode(code.first, code.second)
        return persist(providerId, tokens, previous = null)
    }

    /** 退出登录：清空订阅状态并回到 API Key 模式（baseUrl/apiKey 原样保留）。 */
    suspend fun logout(providerId: Uuid) {
        cachedTokens.remove(providerId)
        updateProvider(providerId) { setting ->
            setting.copy(
                authType = OpenAIAuthType.API_KEY,
                codexAccount = null,
            )
        }
    }

    suspend fun requestDeviceCode(): CodexDeviceCode = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("client_id", CODEX_CLIENT_ID) }
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(CODEX_DEVICE_CODE_URL)
            .post(body)
            .build()
        val (httpCode, responseBody) = execute(request)
        if (httpCode !in 200..299) {
            throw authError(httpCode, responseBody, "申请设备码失败")
        }
        parseDeviceCodeResponse(responseBody)
    }

    /**
     * 轮询设备授权，直到拿到 `authorization_code` 或超时。
     *
     * @return pair(authorizationCode, codeVerifier)
     */
    internal suspend fun pollForAuthorizationCode(deviceCode: CodexDeviceCode): Pair<String, String> {
        val deadline = System.currentTimeMillis() + DEVICE_CODE_TIMEOUT_MS
        val intervalMs = deviceCode.intervalSeconds.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            val body = buildJsonObject {
                put("device_auth_id", deviceCode.deviceAuthId)
                put("user_code", deviceCode.userCode)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(CODEX_DEVICE_TOKEN_URL)
                .post(body)
                .build()
            val (httpCode, responseBody) = execute(request)
            when (classifyAuthError(httpCode, responseBody)) {
                CodexAuthErrorCode.PENDING, CodexAuthErrorCode.SLOW_DOWN -> Unit // 继续轮询
                CodexAuthErrorCode.EXPIRED -> throw CodexAuthException(
                    CodexAuthErrorCode.EXPIRED,
                    "设备码已过期，请重新发起登录",
                )

                else -> {
                    if (httpCode in 200..299) {
                        return parseDeviceAuthorization(responseBody)
                    }
                    throw authError(httpCode, responseBody, "设备授权失败")
                }
            }
        }
        throw CodexAuthException(CodexAuthErrorCode.EXPIRED, "设备码已过期，请重新发起登录")
    }

    internal suspend fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
    ): CodexTokenResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorizationCode)
            .add("redirect_uri", CODEX_DEVICE_REDIRECT_URI)
            .add("client_id", CODEX_CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()
        val (httpCode, responseBody) = execute(
            Request.Builder().url(CODEX_TOKEN_URL).post(form).build()
        )
        if (httpCode !in 200..299) {
            throw authError(httpCode, responseBody, "兑换订阅令牌失败")
        }
        parseTokenResponse(responseBody)
    }

    // ---- 令牌提供（实现 ai 层接口） ----

    override suspend fun getToken(providerSetting: ProviderSetting.OpenAI): CodexToken {
        val providerId = providerSetting.id
        cachedTokens[providerId]?.let { cached ->
            if (cached.expiresAtMs - REFRESH_LEEWAY_MS > System.currentTimeMillis()) {
                return cached.token
            }
        }
        val mutex = refreshLocks.getOrPut(providerId) { Mutex() }
        return mutex.withLock {
            // 双检：并发请求等锁期间可能已被其他协程刷新
            cachedTokens[providerId]?.let { cached ->
                if (cached.expiresAtMs - REFRESH_LEEWAY_MS > System.currentTimeMillis()) {
                    return@withLock cached.token
                }
            }
            val setting = currentSetting(providerId)
                ?: throw CodexAuthException(CodexAuthErrorCode.INVALID_GRANT, "订阅配置已删除，请重新登录")
            val account = setting.codexAccount
                ?: throw CodexAuthException(CodexAuthErrorCode.INVALID_GRANT, "订阅登录已失效，请重新登录")
            if (account.accountId.isBlank()) {
                throw CodexAuthException(CodexAuthErrorCode.INVALID_GRANT, "订阅账号信息缺失，请重新登录")
            }

            if (account.expiresAtMs() - REFRESH_LEEWAY_MS > System.currentTimeMillis()) {
                // 未过期：解密 access token 即可（无需网络）
                val token = CodexToken(
                    accessToken = decryptOrReauth(account.accessTokenEncrypted),
                    accountId = account.accountId,
                )
                cachedTokens[providerId] = CachedToken(token, account.expiresAt)
                return@withLock token
            }

            refreshLocked(providerId, setting, account)
        }
    }

    /** 用 refresh token 换新令牌并落盘；失败按错误类型清凭据。 */
    private suspend fun refreshLocked(
        providerId: Uuid,
        setting: ProviderSetting.OpenAI,
        account: CodexSubscriptionAccount,
    ): CodexToken {
        val refreshToken = decryptOrReauth(account.refreshTokenEncrypted)
        val tokens = try {
            refreshTokens(refreshToken)
        } catch (e: CodexAuthException) {
            if (e.code == CodexAuthErrorCode.INVALID_GRANT) {
                // 刷新令牌已失效：清空本地凭据，由 UI 引导重新登录
                logout(providerId)
            }
            throw e
        }
        val updated = persist(providerId, tokens, previous = account)
        val token = CodexToken(
            accessToken = decryptOrReauth(updated.accessTokenEncrypted),
            accountId = updated.accountId,
        )
        cachedTokens[providerId] = CachedToken(token, updated.expiresAt)
        Log.i(TAG, "refresh: token refreshed for provider ${setting.name}")
        return token
    }

    /** 刷新接口：JSON 优先（上游 #2186 口径），编码不兼容时回退 form 重试一次。 */
    internal suspend fun refreshTokens(refreshToken: String): CodexTokenResult = withContext(Dispatchers.IO) {
        val jsonBody = buildJsonObject {
            put("client_id", CODEX_CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }.toString().toRequestBody("application/json".toMediaType())
        val jsonResult = execute(
            Request.Builder().url(CODEX_TOKEN_URL).post(jsonBody).build()
        )
        if (jsonResult.first in 200..299) {
            return@withContext parseTokenResponse(jsonResult.second)
        }
        // 400/415 视为编码不被接受，回退 form（PR #1752/Jude 实测可用形态）
        val formResult = execute(
            Request.Builder()
                .url(CODEX_TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("grant_type", "refresh_token")
                        .add("refresh_token", refreshToken)
                        .add("client_id", CODEX_CLIENT_ID)
                        .build()
                )
                .build()
        )
        if (formResult.first in 200..299) {
            parseTokenResponse(formResult.second)
        } else {
            throw authError(formResult.first, formResult.second, "刷新订阅令牌失败")
        }
    }

    // ---- 落盘 ----

    private suspend fun persist(
        providerId: Uuid,
        tokens: CodexTokenResult,
        previous: CodexSubscriptionAccount?,
    ): CodexSubscriptionAccount {
        val refreshToken = tokens.refreshToken
            ?: previous?.refreshTokenEncrypted?.let { decryptOrReauth(it) }
            ?: throw CodexAuthException(CodexAuthErrorCode.INVALID_GRANT, "服务端未返回 refresh token，请重新登录")
        val accountId = tokens.accountId
            ?: previous?.accountId?.takeIf { it.isNotBlank() }
            ?: throw CodexAuthException(CodexAuthErrorCode.INVALID_GRANT, "无法识别 ChatGPT 账号，请重新登录")

        val account = CodexSubscriptionAccount(
            accountId = accountId,
            email = tokens.email ?: previous?.email,
            planType = tokens.planType ?: previous?.planType,
            expiresAt = tokens.expiresAtMs,
            accessTokenEncrypted = SecretCipher.encrypt(tokens.accessToken),
            refreshTokenEncrypted = SecretCipher.encrypt(refreshToken),
        )
        updateProvider(providerId) { setting ->
            setting.copy(
                authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                codexAccount = account,
            )
        }
        cachedTokens[providerId] = CachedToken(
            CodexToken(accessToken = tokens.accessToken, accountId = accountId),
            account.expiresAt,
        )
        return account
    }

    private suspend fun updateProvider(
        providerId: Uuid,
        transform: (ProviderSetting.OpenAI) -> ProviderSetting.OpenAI,
    ) {
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.OpenAI) {
                        transform(provider)
                    } else {
                        provider
                    }
                }
            )
        }
    }

    private fun currentSetting(providerId: Uuid): ProviderSetting.OpenAI? =
        settingsStore.settingsFlow.value.providers
            .filterIsInstance<ProviderSetting.OpenAI>()
            .firstOrNull { it.id == providerId }

    private fun decryptOrReauth(value: String): String = try {
        SecretCipher.decrypt(value)
    } catch (e: Exception) {
        Log.w(TAG, "decrypt failed (${e.javaClass.simpleName})")
        throw CodexAuthException(
            CodexAuthErrorCode.INVALID_GRANT,
            "本机无法解密订阅凭据（可能来自备份恢复），请重新登录",
            e,
        )
    }

    // ---- HTTP ----

    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).await().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        } catch (e: CodexAuthException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "http failed: ${e.javaClass.simpleName} ${e.message}")
            throw CodexAuthException(CodexAuthErrorCode.NETWORK, "网络请求失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun authError(httpCode: Int, body: String?, prefix: String): CodexAuthException {
        val code = classifyAuthError(httpCode, body)
        val detail = extractErrorCode(body).orEmpty()
        return when (code) {
            CodexAuthErrorCode.REGION_BLOCKED -> CodexAuthException(
                code,
                "当前网络无法访问 OpenAI 授权服务（地区限制），请配置代理后重试",
            )

            CodexAuthErrorCode.INVALID_GRANT -> CodexAuthException(
                code,
                "订阅登录已失效，请重新登录",
            )

            else -> CodexAuthException(code, "$prefix（HTTP $httpCode${detail.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}）")
        }
    }
}

// ---- 纯解析函数（单测覆盖，不依赖 Android/网络） ----

internal fun parseDeviceCodeResponse(body: String): CodexDeviceCode {
    val root = runCatching { JsonInstant.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备码响应不是 JSON：${body.take(200)}")
    val deviceAuthId = root["device_auth_id"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备码响应缺少 device_auth_id")
    val userCode = root["user_code"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备码响应缺少 user_code")
    // interval 可能是数字也可能是字符串
    val interval = root["interval"]?.jsonPrimitiveOrNull?.let { it.contentOrNull?.toIntOrNull() ?: it.longOrNull?.toInt() }
    return CodexDeviceCode(
        deviceAuthId = deviceAuthId,
        userCode = userCode,
        intervalSeconds = interval?.coerceAtLeast(1) ?: 5,
    )
}

/** 解析设备授权成功响应：`{"authorization_code": "...", "code_verifier": "..."}`。 */
internal fun parseDeviceAuthorization(body: String): Pair<String, String> {
    val root = runCatching { JsonInstant.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备授权响应不是 JSON：${body.take(200)}")
    val code = root["authorization_code"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备授权响应缺少 authorization_code")
    val verifier = root["code_verifier"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "设备授权响应缺少 code_verifier")
    return code to verifier
}

/**
 * 解析 `/oauth/token` 响应并解析账号信息。
 *
 * 账号 id 优先级：令牌 JSON `account_id` → `id_token`/`access_token` 的 JWT claim
 * （`https://api.openai.com/auth.chatgpt_account_id`）→ null（由调用方报错）。
 */
internal fun parseTokenResponse(body: String, nowMs: Long = System.currentTimeMillis()): CodexTokenResult {
    val root = runCatching { JsonInstant.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "令牌响应不是 JSON：${body.take(200)}")
    val accessToken = root["access_token"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: throw CodexAuthException(CodexAuthErrorCode.UNKNOWN, "令牌响应缺少 access_token")
    val refreshToken = root["refresh_token"]?.jsonPrimitiveOrNull?.contentOrNull
    val idToken = root["id_token"]?.jsonPrimitiveOrNull?.contentOrNull
    val expiresIn = root["expires_in"]?.jsonPrimitiveOrNull?.longOrNull
        ?: root["expires_in"]?.jsonPrimitiveOrNull?.contentOrNull?.toLongOrNull()

    val authClaims = listOfNotNull(
        accessToken.decodeJwtPayload(),
        idToken?.decodeJwtPayload(),
    )
    val accountId = root["account_id"]?.jsonPrimitiveOrNull?.contentOrNull
        ?: authClaims.firstNotNullOfOrNull { payload ->
            payload["https://api.openai.com/auth"]
                ?.jsonObjectOrNull
                ?.get("chatgpt_account_id")
                ?.jsonPrimitiveOrNull
                ?.contentOrNull
        }
    val planType = authClaims.firstNotNullOfOrNull { payload ->
        payload["https://api.openai.com/auth"]
            ?.jsonObjectOrNull
            ?.get("chatgpt_plan_type")
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
    }
    val email = authClaims.firstNotNullOfOrNull { payload ->
        payload["email"]?.jsonPrimitiveOrNull?.contentOrNull
    }
    val jwtExpMs = accessToken.decodeJwtPayload()?.get("exp")?.jsonPrimitiveOrNull?.longOrNull
        ?.let { it * 1000 }
    val expiresAtMs = expiresIn?.let { nowMs + it * 1000 }
        ?: jwtExpMs
        ?: (nowMs + 3600_000L)

    return CodexTokenResult(
        accessToken = accessToken,
        refreshToken = refreshToken,
        idToken = idToken,
        expiresAtMs = expiresAtMs,
        accountId = accountId,
        email = email,
        planType = planType,
    )
}

/** 从错误体提取 `error.code` 或顶层 `code`。 */
internal fun extractErrorCode(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val root = runCatching { JsonInstant.parseToJsonElement(body).jsonObjectOrNull }.getOrNull() ?: return null
    return root["error"]?.jsonObjectOrNull?.get("code")?.jsonPrimitiveOrNull?.contentOrNull
        ?: root["code"]?.jsonPrimitiveOrNull?.contentOrNull
}

internal fun classifyAuthError(httpCode: Int, body: String?): CodexAuthErrorCode {
    val code = extractErrorCode(body).orEmpty()
    return when {
        code.contains("unsupported_country_region_territory", ignoreCase = true) ->
            CodexAuthErrorCode.REGION_BLOCKED

        code.contains("authorization_pending", ignoreCase = true) -> CodexAuthErrorCode.PENDING
        code.contains("slow_down", ignoreCase = true) -> CodexAuthErrorCode.SLOW_DOWN
        code.contains("expired_token", ignoreCase = true) ||
            code.contains("expired", ignoreCase = true) -> CodexAuthErrorCode.EXPIRED

        code.contains("invalid_grant", ignoreCase = true) ||
            code.contains("refresh_token_reused", ignoreCase = true) ||
            code.contains("invalidated", ignoreCase = true) -> CodexAuthErrorCode.INVALID_GRANT

        httpCode >= 500 -> CodexAuthErrorCode.NETWORK
        else -> CodexAuthErrorCode.UNKNOWN
    }
}

internal fun String.decodeJwtPayload(): JsonObject? = runCatching {
    val payload = split('.').getOrNull(1) ?: return null
    val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
    String(Base64.getUrlDecoder().decode(padded)).let {
        JsonInstant.parseToJsonElement(it).jsonObject
    }
}.getOrNull()

/** 持久化的 `expiresAt` 是 epoch millis；老数据/缺省为 0 表示未知。 */
private fun CodexSubscriptionAccount.expiresAtMs(): Long = expiresAt
