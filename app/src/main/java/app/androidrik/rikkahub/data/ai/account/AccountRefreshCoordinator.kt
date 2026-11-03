package app.androidrik.rikkahub.data.ai.account

import app.androidrik.ai.provider.OAuthCredential
import app.androidrik.ai.provider.ProviderSetting
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** 与 Codex CLI 一致：access token 过期前 5 分钟内就刷新 */
internal const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60_000L

/** 刚被服务端拒绝（401），或者快过期了 */
internal fun OAuthCredential.needsRefresh(rejectedAccessToken: String?): Boolean =
    accessToken == rejectedAccessToken ||
        (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS)

/**
 * 「读最新凭据 → 按需刷新 → 写回」的编排，抽出来是为了让**并发刷新**这条规则能被 JVM 单测覆盖：
 * 刷新用到的 refresh token 服务端只认一次（每次刷新轮换），两路请求同时刷新会把第二个 token 打废，
 * 用户只能重新登录。因此同一 provider 的刷新必须**串行 + 拿锁后重读设置**。
 *
 * 与存储/网络解耦（[read] / [refresh] / [store] 都是注入的），生产实现见 [ProviderAccountManager]。
 *
 * @param read 读该 provider 的最新快照（凭据已解密）；provider 不存在返回 null
 * @param refresh 真正调网络换 token；[ProviderSetting.Account] 的快照 + 明文 refresh token
 * @param store 仅当设置里的凭据仍等于「刷新前那份」时写回新凭据，返回是否写入
 */
internal class AccountRefreshCoordinator(
    private val read: suspend (Uuid) -> ProviderSetting.Account?,
    private val refresh: suspend (ProviderSetting.Account, String) -> OAuthCredential,
    private val store: suspend (Uuid, expected: OAuthCredential, credential: OAuthCredential) -> Boolean,
    private val locks: MutableMap<Uuid, Mutex> = ConcurrentHashMap(),
) {
    suspend fun refreshIfNeeded(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential {
        val lock = locks.computeIfAbsent(setting.id) { Mutex() }
        return lock.withLock {
            // 拿到锁后重读设置：并发请求可能已经刷新过，refresh token 只能用一次
            val latest = read(setting.id) ?: setting
            val credential = latest.credential
                ?: error("${latest.name}: not signed in")
            if (!credential.needsRefresh(rejectedAccessToken)) return@withLock credential

            val refreshToken = credential.refreshToken
                ?: error("${latest.name}: session expired, please sign in again")
            val refreshed = try {
                refresh(latest, refreshToken).copy(loginSessionId = credential.loginSessionId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(
                    "${latest.name}: failed to refresh sign-in, please sign in again",
                    e,
                )
            }
            // 刷新期间用户可能退出或换了账号，这时结果作废，不能把旧会话写回去
            if (!store(latest.id, credential, refreshed)) {
                error("${latest.name}: sign-in changed during refresh, please try again")
            }
            refreshed
        }
    }
}
