package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderModelCatalogRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val provider = ProviderSetting.OpenAI(apiKey = "secret-primary", baseUrl = "https://example.com/v1")
    private fun settings(vararg providers: ProviderSetting) = MutableStateFlow(Settings(init = false, providers = providers.toList()))
    private fun complete(p: ProviderSetting, id: String = "remote") = CatalogFetchResult.Complete(listOf(CatalogEntry(id)), CatalogScope(p.id.toString(), listOf("test")))
    private fun TestScope.repository(
        config: MutableStateFlow<Settings>,
        store: CatalogSnapshotStore = FileCatalogSnapshotStore(temp.newFolder()),
        fetch: suspend (ProviderSetting) -> CatalogFetchResult = { complete(it) },
    ) = ProviderModelCatalogRepository(config, store, backgroundScope, fetch,
        clock = { 1_000_000L + testScheduler.currentTime }, dispatcher = StandardTestDispatcher(testScheduler))

    @Test fun `two callers share a job and cancelling one waiter does not cancel refresh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = repository(settings(provider)) { calls++; gate.await(); complete(it) }
        val one = async { repo.refresh(provider.id) }
        val two = async { repo.refresh(provider.id) }
        runCurrent()
        assertEquals(1, calls)
        one.cancel()
        gate.complete(Unit)
        assertEquals(CatalogAttemptStatus.COMPLETE, two.await()?.lastAttempt?.status)
        assertEquals(1, calls)
    }

    @Test fun `batch limits concurrency globally and one failure does not cancel siblings`() = runTest {
        val providers = (1..6).map { provider.copy(id = Uuid.random()) }
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val repo = repository(settings(*providers.toTypedArray())) { p ->
            active++; maximum = maxOf(active, maximum)
            gate.await(); active--
            if (p.id == providers.first().id) CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.NETWORK, "network")) else complete(p)
        }
        val batch = async { repo.refreshAll() }
        runCurrent()
        assertEquals(3, active)
        gate.complete(Unit)
        val result = batch.await()
        assertEquals(3, maximum)
        assertEquals(5, result.values.count { it?.lastAttempt?.status == CatalogAttemptStatus.COMPLETE })
        assertEquals(CatalogAttemptStatus.FAILED, result[providers.first().id]?.lastAttempt?.status)
    }

    @Test fun `failure and partial results retain last complete snapshot and timestamp`() = runTest {
        var result: CatalogFetchResult = complete(provider, "original")
        val repo = repository(settings(provider)) { result }
        val initial = repo.refresh(provider.id)!!.lastComplete
        result = CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.AUTHENTICATION, "auth", 401))
        assertEquals(initial, repo.refresh(provider.id)!!.lastComplete)
        result = CatalogFetchResult.Partial(listOf(CatalogEntry("new")), CatalogScope(provider.id.toString(), listOf("test")), IncompleteReason.SOURCE_FAILED)
        val partial = repo.refresh(provider.id)!!
        assertEquals(initial, partial.lastComplete)
        assertEquals("new", partial.lastAttempt?.partial?.entries?.single()?.modelId)
    }

    @Test fun `same URL with different provider UUIDs never shares state or requests`() = runTest {
        val other = provider.copy(id = Uuid.random(), apiKey = "other-key")
        var calls = 0
        val repo = repository(settings(provider, other)) { calls++; complete(it, it.id.toString()) }
        val one = repo.refresh(provider.id)!!
        val two = repo.refresh(other.id)!!
        assertEquals(2, calls)
        assertNotEquals(one.scopeFingerprint, two.scopeFingerprint)
        assertEquals(provider.id.toString(), one.lastComplete!!.catalog.entries.single().modelId)
    }

    @Test fun `manual refresh bypasses TTL while automatic refresh obeys TTL and retry delay`() = runTest {
        var calls = 0
        val repo = repository(settings(provider)) { calls++; complete(it) }
        repo.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        repo.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        assertEquals(1, calls)
        repo.refresh(provider.id)
        assertEquals(2, calls)
        advanceTimeBy(6 * 60 * 60_000L + 1)
        repo.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        assertEquals(3, calls)
        val failed = repository(settings(provider)) { calls++; CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.NETWORK, "network")) }
        failed.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        failed.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        assertEquals(4, calls)
        advanceTimeBy(60_001)
        failed.refresh(provider.id, CatalogRefreshTrigger.AUTOMATIC)
        assertEquals(5, calls)
    }

    @Test fun `timeout becomes a failed attempt without losing previous complete data`() = runTest {
        var hang = false
        val repo = repository(settings(provider)) { if (hang) awaitCancellation() else complete(it) }
        val previous = repo.refresh(provider.id)!!.lastComplete
        hang = true
        val timedOut = repo.refresh(provider.id)!!
        assertEquals(CatalogErrorKind.TIMEOUT, timedOut.lastAttempt?.error?.kind)
        assertEquals(previous, timedOut.lastComplete)
    }

    @Test fun `changing credentials discards even an uncancellable late result`() = runTest {
        val config = settings(provider)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = repository(config) { p ->
            calls++
            if (calls == 1) withContext(NonCancellable) { gate.await(); complete(p, "obsolete") }
            else complete(p, "current")
        }
        val old = async { repo.refresh(provider.id) }
        runCurrent()
        config.value = config.value.copy(providers = listOf(provider.copy(apiKey = "new-secret")))
        runCurrent()
        assertNull(repo.states.value[provider.id]?.lastComplete)
        val current = repo.refresh(provider.id)!!
        gate.complete(Unit)
        old.await()
        runCurrent()
        assertEquals(current, repo.states.value[provider.id])
        assertEquals("current", repo.states.value[provider.id]!!.lastComplete!!.catalog.entries.single().modelId)
    }

    @Test fun `provider deletion cancels work and removes disk snapshots`() = runTest {
        val directory = temp.newFolder()
        val store = FileCatalogSnapshotStore(directory)
        val config = settings(provider)
        val repo = repository(config, store)
        repo.refresh(provider.id)
        config.value = config.value.copy(providers = emptyList())
        runCurrent()
        assertTrue(repo.states.value.isEmpty())
        assertFalse(File(directory, "${provider.id}.json").exists())
        assertNull(repo.refresh(provider.id))
    }

    @Test fun `complete snapshots survive restart and interrupted attempts are recovered`() = runTest {
        val directory = temp.newFolder()
        val store = FileCatalogSnapshotStore(directory)
        val config = settings(provider)
        val repo = repository(config, store)
        val first = repo.refresh(provider.id)!!
        store.save(provider.id, first.copy(lastAttempt = first.lastAttempt!!.copy(status = CatalogAttemptStatus.RUNNING)))
        val reopened = repository(config, FileCatalogSnapshotStore(directory))
        runCurrent()
        assertEquals(first.lastComplete, reopened.states.value[provider.id]?.lastComplete)
        assertEquals(CatalogAttemptStatus.INTERRUPTED, reopened.states.value[provider.id]?.lastAttempt?.status)
        val text = File(directory, "${provider.id}.json").readText()
        assertFalse(text.contains("secret-primary"))
        assertFalse(text.contains("https://example.com"))
    }

    @Test fun `corrupt cache becomes unknown rather than a complete empty catalog`() = runTest {
        val directory = temp.newFolder()
        File(directory, "${provider.id}.json").writeText("{bad json")
        val repo = repository(settings(provider), FileCatalogSnapshotStore(directory))
        runCurrent()
        assertNull(repo.states.value[provider.id]?.lastComplete)
        assertNull(repo.states.value[provider.id]?.lastAttempt)
    }

    @Test fun `unlogged accounts do not issue network requests and explicit manual can check disabled API provider`() = runTest {
        val account = ProviderSetting.ChatGPT()
        val disabled = provider.copy(enabled = false)
        var calls = 0
        val repo = repository(settings(account, disabled)) { calls++; complete(it) }
        assertEquals(CatalogErrorKind.NOT_LOGGED_IN, repo.refresh(account.id)?.lastAttempt?.error?.kind)
        repo.refreshAll()
        assertEquals(0, calls)
        assertEquals(CatalogAttemptStatus.COMPLETE, repo.refresh(disabled.id)?.lastAttempt?.status)
        assertEquals(1, calls)
    }

    @Test fun `queued batch target disabled before execution is skipped`() = runTest {
        val providers = (1..4).map { provider.copy(id = Uuid.random()) }
        val config = settings(*providers.toTypedArray())
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = repository(config) { calls++; gate.await(); complete(it) }
        val batch = async { repo.refreshAll() }
        runCurrent()
        config.value = config.value.copy(providers = providers.mapIndexed { index, p -> p.copy(enabled = index < 3) })
        runCurrent()
        gate.complete(Unit)
        val result = batch.await()
        assertEquals(3, calls)
        assertEquals(CatalogAttemptStatus.SKIPPED, result[providers.last().id]?.lastAttempt?.status)
    }

    @Test fun `renaming or selecting models keeps scope but active key headers and inherited proxy changes invalidate it`() {
        val salt = ByteArray(32) { 7 }
        fun fingerprint(p: ProviderSetting = provider, proxy: GlobalProxySetting = GlobalProxySetting()) = catalogScopeFingerprint(p, proxy, salt)
        assertEquals(fingerprint(), fingerprint(provider.copy(name = "Renamed", models = listOf(Model(modelId = "local")))))
        val keys = provider.copy(apiKeys = listOf(ApiKeyEntry("first"), ApiKeyEntry("unused")))
        assertEquals(fingerprint(keys), fingerprint(keys.copy(apiKeys = keys.apiKeys.take(1) + ApiKeyEntry("changed-unused"))))
        val duplicateHeaders = provider.copy(customHeaders = listOf(CustomHeader("X-Account", "first"), CustomHeader("X-Account", "last")))
        assertNotEquals(fingerprint(duplicateHeaders), fingerprint(duplicateHeaders.copy(customHeaders = listOf(CustomHeader("X-Account", "different"), CustomHeader("X-Account", "last")))))
        assertNotEquals(fingerprint(), fingerprint(provider.copy(baseUrl = provider.baseUrl + "/")))
        assertNotEquals(fingerprint(), fingerprint(provider.copy(apiKey = "changed")))
        assertNotEquals(fingerprint(), fingerprint(provider.copy(customHeaders = listOf(CustomHeader("X-Account", "B")))))
        assertNotEquals(fingerprint(), fingerprint(proxy = GlobalProxySetting(host = "changed")))
        assertNotEquals(fingerprint(), catalogScopeFingerprint(provider, GlobalProxySetting(), ByteArray(32) { 8 }))
    }

    @Test fun `OAuth token renewal does not change scope but a new login does`() {
        val account = ProviderSetting.ChatGPT(credential = OAuthCredential("old", loginSessionId = "session"))
        val salt = ByteArray(32)
        fun fingerprint(p: ProviderSetting) = catalogScopeFingerprint(p, GlobalProxySetting(), salt)
        assertEquals(fingerprint(account), fingerprint(account.copy(credential = account.credential!!.copy(accessToken = "renewed", refreshToken = "rotated"))))
        assertNotEquals(fingerprint(account), fingerprint(account.copy(credential = account.credential!!.copy(loginSessionId = "new-login"))))
    }
    @Test fun `in-flight OAuth refresh tolerates token renewal but replacing the login clears its evidence`() = runTest {
        val account = ProviderSetting.ChatGPT(credential = OAuthCredential("old", loginSessionId = "login-A"))
        val config = settings(account)
        val gate = CompletableDeferred<Unit>()
        val repo = repository(config) { gate.await(); complete(it) }
        val request = async { repo.refresh(account.id) }
        runCurrent()
        val renewed = account.copy(credential = account.credential!!.copy(accessToken = "renewed"))
        config.value = config.value.copy(providers = listOf(renewed))
        runCurrent()
        gate.complete(Unit)
        assertEquals(CatalogAttemptStatus.COMPLETE, request.await()?.lastAttempt?.status)
        config.value = config.value.copy(providers = listOf(renewed.copy(credential = renewed.credential!!.copy(loginSessionId = "login-B"))))
        runCurrent()
        assertNull(repo.states.value[account.id]?.lastComplete)
    }

    @Test fun `cache write failure is observable without turning network success into an empty result`() = runTest {
        val disk = FileCatalogSnapshotStore(temp.newFolder())
        val failing = object : CatalogSnapshotStore by disk {
            override fun save(id: Uuid, state: ProviderCatalogState) = false
        }
        val repo = repository(settings(provider), failing)
        val result = repo.refresh(provider.id)!!
        assertTrue(result.cacheWriteFailed)
        assertEquals(CatalogAttemptStatus.COMPLETE, result.lastAttempt?.status)
        assertEquals("remote", result.lastComplete?.catalog?.entries?.single()?.modelId)
    }

    @Test fun `deleting provider during a request prevents its late result from recreating a cache`() = runTest {
        val directory = temp.newFolder()
        val config = settings(provider)
        val gate = CompletableDeferred<Unit>()
        val repo = repository(config, FileCatalogSnapshotStore(directory)) { p ->
            withContext(NonCancellable) { gate.await(); complete(p) }
        }
        val old = async { repo.refresh(provider.id) }
        runCurrent()
        config.value = config.value.copy(providers = emptyList())
        runCurrent()
        gate.complete(Unit)
        old.await()
        runCurrent()
        assertFalse(repo.states.value.containsKey(provider.id))
        assertFalse(File(directory, "${provider.id}.json").exists())
    }

    @Test fun `invalid headers do not terminate scope observation and can be corrected`() = runTest {
        val config = settings(provider.copy(customHeaders = listOf(CustomHeader("bad\nname", "invalid\nvalue"))))
        val repo = repository(config)
        runCurrent()
        val firstScope = repo.states.value[provider.id]!!.scopeFingerprint
        config.value = config.value.copy(providers = listOf(provider))
        runCurrent()
        assertNotEquals(firstScope, repo.states.value[provider.id]!!.scopeFingerprint)
        assertEquals(CatalogAttemptStatus.COMPLETE, repo.refresh(provider.id)?.lastAttempt?.status)
    }

    @Test fun `UI cannot read evidence for old credentials before the settings observer catches up`() = runTest {
        val config = settings(provider)
        val repo = repository(config)
        repo.refresh(provider.id)
        assertNotNull(repo.currentState(provider.id)?.lastComplete)
        config.value = config.value.copy(providers = listOf(provider.copy(apiKey = "new-key")))
        assertNull(repo.currentState(provider.id))
    }

}
