package app.androidrik.rikkahub.data.ai.account

import app.androidrik.ai.provider.OAuthCredential
import app.androidrik.ai.provider.ProviderSetting
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 并发刷新契约（refresh token 服务端只认一次，并发刷新会把第二个 token 打废 ⇒ 只能重新登录）：
 * 1. 同一 provider 的刷新串行；拿到锁后**重读设置**，已被别的请求刷新过就直接复用新 token；
 * 2. 快过期 / 被服务端 401 才刷新，其它情况不发网络请求；
 * 3. 刷新失败包装成可读错误，且不写回；
 * 4. 刷新期间凭据被换掉（登出/换账号）⇒ 结果作废，不把旧会话写回去。
 */
class AccountRefreshCoordinatorTest {

    private val providerId: Uuid = Uuid.random()

    private fun credential(
        accessToken: String,
        refreshToken: String? = "refresh-1",
        expiresAt: Long = 0L,
    ) = OAuthCredential(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
    )

    private fun account(credential: OAuthCredential?) = ProviderSetting.ChatGPT(
        id = providerId,
        name = "ChatGPT",
        credential = credential,
    )

    /** 内存版设置存储：read 返回当前快照，store 只在 expected 匹配时替换 */
    private class FakeStore(private val state: AtomicReference<ProviderSetting.Account?>) {
        val storeCalls = AtomicInteger()
        fun coordinator(
            refresh: suspend (ProviderSetting.Account, String) -> OAuthCredential,
        ) = AccountRefreshCoordinator(
            read = { state.get() },
            refresh = refresh,
            store = { _, expected, new ->
                storeCalls.incrementAndGet()
                val current = state.get() as? ProviderSetting.Account
                if (current?.credential == expected) {
                    state.set(current.withCredential(new))
                    true
                } else {
                    false
                }
            },
        )
    }

    @Test
    fun `valid token is returned without touching the network`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(
            account(credential("still-valid", expiresAt = System.currentTimeMillis() + 60 * 60_000))
        )
        val refreshCalls = AtomicInteger()
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ ->
            refreshCalls.incrementAndGet()
            credential("unused")
        }

        val result = coordinator.refreshIfNeeded(state.get()!!, rejectedAccessToken = null)

        assertEquals("still-valid", result.accessToken)
        assertEquals(0, refreshCalls.get())
        assertEquals(0, store.storeCalls.get())
    }

    @Test
    fun `concurrent requests refresh exactly once and share the new token`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(
            account(credential("expired", expiresAt = System.currentTimeMillis() - 1_000))
        )
        val refreshCalls = AtomicInteger()
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, refreshToken ->
            refreshCalls.incrementAndGet()
            // 模拟网络往返：让另一路请求在锁上排队，验证「拿锁后重读设置」
            delay(100)
            credential("fresh-${refreshToken}", expiresAt = System.currentTimeMillis() + 60 * 60_000)
        }
        val setting = state.get()!!

        val results = listOf(
            async { coordinator.refreshIfNeeded(setting, null) },
            async { coordinator.refreshIfNeeded(setting, null) },
        ).awaitAll()

        assertEquals("只能真正刷新一次（refresh token 一次性）", 1, refreshCalls.get())
        assertEquals("两路请求都要拿到新 token", listOf("fresh-refresh-1", "fresh-refresh-1"), results.map { it.accessToken })
        assertEquals(1, store.storeCalls.get())
    }

    @Test
    fun `rejected access token forces one refresh`() = runTest {
        val current = credential("rejected", expiresAt = System.currentTimeMillis() + 60 * 60_000)
        val state = AtomicReference<ProviderSetting.Account?>(account(current))
        val refreshCalls = AtomicInteger()
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ ->
            refreshCalls.incrementAndGet()
            credential("fresh")
        }

        val first = coordinator.refreshIfNeeded(state.get()!!, rejectedAccessToken = "rejected")
        val second = coordinator.refreshIfNeeded(state.get()!!, rejectedAccessToken = "rejected")

        assertEquals(1, refreshCalls.get())
        assertEquals("fresh", first.accessToken)
        // 第二次 401 带的是旧 token：拿锁后重读发现已刷新，直接复用，不再刷新
        assertEquals("fresh", second.accessToken)
    }

    @Test
    fun `refresh failure is wrapped and nothing is written back`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(
            account(credential("expired", expiresAt = System.currentTimeMillis() - 1_000))
        )
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ -> error("invalid_grant") }

        try {
            coordinator.refreshIfNeeded(state.get()!!, null)
            fail("刷新失败应抛出可读错误")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("failed to refresh sign-in"))
            assertTrue(e.cause!!.message!!.contains("invalid_grant"))
        }
        assertEquals("失败不能写回", 0, store.storeCalls.get())
        assertEquals("expired", state.get()!!.credential?.accessToken)
    }

    @Test
    fun `sign out during refresh discards the refreshed credential`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(
            account(credential("expired", expiresAt = System.currentTimeMillis() - 1_000))
        )
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ ->
            // 刷新期间用户退出登录：设置里的凭据已不是刷新前那份
            state.set(account(null))
            credential("fresh")
        }

        try {
            coordinator.refreshIfNeeded(state.get()!!, null)
            fail("刷新期间登出应拒绝写回")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("sign-in changed during refresh"))
        }
        assertEquals(1, store.storeCalls.get())
        assertFalse("不能把旧会话写回去", state.get()!!.credential != null)
    }

    @Test
    fun `missing refresh token asks the user to sign in again`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(
            account(credential("expired", refreshToken = null, expiresAt = System.currentTimeMillis() - 1_000))
        )
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ -> credential("unreachable") }

        try {
            coordinator.refreshIfNeeded(state.get()!!, null)
            fail("没有 refresh token 应要求重新登录")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("session expired"))
        }
        assertEquals(0, store.storeCalls.get())
    }

    @Test
    fun `signed out provider is rejected`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(account(null))
        val store = FakeStore(state)
        val coordinator = store.coordinator { _, _ -> credential("unreachable") }

        try {
            coordinator.refreshIfNeeded(state.get()!!, null)
            fail("未登录应报错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not signed in"))
        }
    }
    @Test
    fun `automatic token renewal preserves the login epoch even if adapter omits it`() = runTest {
        val state = AtomicReference<ProviderSetting.Account?>(account(
            credential("expired", expiresAt = 1L).copy(loginSessionId = "original-session"),
        ))
        val store = FakeStore(state)
        val result = store.coordinator { _, _ -> credential("renewed").copy(loginSessionId = "wrong-session") }
            .refreshIfNeeded(state.get()!!, null)
        assertEquals("original-session", result.loginSessionId)
        assertEquals("original-session", state.get()!!.credential!!.loginSessionId)
    }

}
