package app.androidrik.rikkahub.data.ai.openai

import java.util.Base64
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex 设备码登录的纯解析/分类逻辑（无 Android 依赖，JVM 单测）。
 *
 * 协议形态来自 2026-11-01 curl 实测与上游 PR #1752/Jude 的解析口径。
 */
class OpenAICodexAuthServiceTest {

    private fun jwt(payloadJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }

    @Test
    fun `设备码响应兼容字符串与数字 interval`() {
        val withString = parseDeviceCodeResponse(
            """{"device_auth_id":"dev-1","user_code":"TJ8L-4ABRL","interval":"5","expires_at":"2026-11-01T10:32:09Z"}"""
        )
        assertEquals("dev-1", withString.deviceAuthId)
        assertEquals("TJ8L-4ABRL", withString.userCode)
        assertEquals(5, withString.intervalSeconds)
        assertEquals("https://auth.openai.com/codex/device", withString.verificationUrl)

        val withNumber = parseDeviceCodeResponse(
            """{"device_auth_id":"dev-1","user_code":"CODE","interval":8}"""
        )
        assertEquals(8, withNumber.intervalSeconds)

        // interval 缺省时用 5s 兜底，且不小于 1
        assertEquals(5, parseDeviceCodeResponse("""{"device_auth_id":"d","user_code":"c"}""").intervalSeconds)
        assertEquals(1, parseDeviceCodeResponse("""{"device_auth_id":"d","user_code":"c","interval":0}""").intervalSeconds)
    }

    @Test
    fun `设备授权成功响应解析出 code 与 verifier`() {
        val (code, verifier) = parseDeviceAuthorization(
            """{"authorization_code":"ac-1","code_verifier":"cv-1"}"""
        )
        assertEquals("ac-1", code)
        assertEquals("cv-1", verifier)
    }

    @Test
    fun `令牌响应优先取顶层 account_id`() {
        val result = parseTokenResponse(
            """{"access_token":"at","refresh_token":"rt","account_id":"acct-json","expires_in":3600}""",
            nowMs = 1_000_000L,
        )
        assertEquals("acct-json", result.accountId)
        assertEquals(1_000_000L + 3600_000L, result.expiresAtMs)
    }

    @Test
    fun `令牌响应缺 account_id 时从 JWT claim 回退`() {
        val accessToken = jwt(
            """{"exp":1700000000,"https://api.openai.com/auth":{"chatgpt_account_id":"acct-jwt","chatgpt_plan_type":"plus"},"email":"a@b.c"}"""
        )
        val result = parseTokenResponse(
            """{"access_token":"$accessToken","expires_in":60}""",
            nowMs = 0L,
        )
        assertEquals("acct-jwt", result.accountId)
        assertEquals("plus", result.planType)
        assertEquals("a@b.c", result.email)
        // expires_in 优先
        assertEquals(60_000L, result.expiresAtMs)

        // 无 expires_in 时回退 JWT exp（秒 → 毫秒）
        val onlyJwt = parseTokenResponse("""{"access_token":"$accessToken"}""")
        assertEquals(1700000000L * 1000, onlyJwt.expiresAtMs)
    }

    @Test
    fun `刷新响应可缺 refresh_token 与 id_token`() {
        val result = parseTokenResponse("""{"access_token":"at"}""", nowMs = 0L)
        assertNull(result.refreshToken)
        assertNull(result.idToken)
        // 无 expires_in/exp 时兜底 1 小时
        assertEquals(3600_000L, result.expiresAtMs)
    }

    @Test
    fun `错误码分类覆盖地区封锁与轮询中间态`() {
        assertEquals(
            CodexAuthErrorCode.REGION_BLOCKED,
            classifyAuthError(403, """{"error":{"code":"unsupported_country_region_territory"}}"""),
        )
        assertEquals(
            CodexAuthErrorCode.PENDING,
            classifyAuthError(403, """{"error":{"code":"deviceauth_authorization_pending"}}"""),
        )
        assertEquals(
            CodexAuthErrorCode.SLOW_DOWN,
            classifyAuthError(403, """{"error":{"code":"slow_down"}}"""),
        )
        assertEquals(
            CodexAuthErrorCode.EXPIRED,
            classifyAuthError(400, """{"error":{"code":"expired_token"}}"""),
        )
        assertEquals(
            CodexAuthErrorCode.INVALID_GRANT,
            classifyAuthError(400, """{"error":{"code":"invalid_grant"}}"""),
        )
        assertEquals(CodexAuthErrorCode.NETWORK, classifyAuthError(502, null))
        assertEquals(CodexAuthErrorCode.UNKNOWN, classifyAuthError(400, """{"error":{"code":"other"}}"""))
    }

    @Test
    fun `extractErrorCode 兼容嵌套与顶层两种形态`() {
        assertEquals("x", extractErrorCode("""{"error":{"code":"x"}}"""))
        assertEquals("y", extractErrorCode("""{"code":"y"}"""))
        assertNull(extractErrorCode(null))
        assertNull(extractErrorCode("not json"))
    }

    @Test
    fun `JWT payload 解码失败不抛错`() {
        assertNull("not-a-jwt".decodeJwtPayload())
        assertNull("a.b.c".decodeJwtPayload())
        val payload = jwt("""{"sub":"1"}""").decodeJwtPayload()
        assertTrue(payload!!.containsKey("sub"))
        assertEquals("1", payload["sub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `设备码响应缺少必要字段时报可读错误`() {
        val error = runCatching { parseDeviceCodeResponse("""{"user_code":"x"}""") }.exceptionOrNull()
        assertTrue(error is CodexAuthException)
        assertTrue(error!!.message!!.contains("device_auth_id"))
    }
}
