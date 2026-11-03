package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
enum class CatalogRefreshTrigger { MANUAL, AUTOMATIC, BATCH }
@Serializable
enum class CatalogAttemptStatus { QUEUED, RUNNING, COMPLETE, PARTIAL, FAILED, UNSUPPORTED, INTERRUPTED, SKIPPED }

@Serializable
data class CatalogSnapshot(val catalog: CatalogFetchResult.Complete, val checkedAt: Long)

@Serializable
data class CatalogAttempt(
    val requestId: String,
    val trigger: CatalogRefreshTrigger,
    val status: CatalogAttemptStatus,
    val queuedAt: Long,
    val finishedAt: Long? = null,
    val partial: CatalogFetchResult.Partial? = null,
    val error: CatalogError? = null,
    val httpStatus: Int? = null,
)

@Serializable
data class ProviderCatalogState(
    val scopeFingerprint: String,
    val lastComplete: CatalogSnapshot? = null,
    val lastAttempt: CatalogAttempt? = null,
    val cacheWriteFailed: Boolean = false,
)

/** Process-owned jobs are shared by every view. Caller cancellation only cancels that caller's wait. */
class ProviderModelCatalogRepository internal constructor(
    private val settings: StateFlow<Settings>,
    private val store: CatalogSnapshotStore,
    parentScope: CoroutineScope,
    private val fetch: suspend (ProviderSetting) -> CatalogFetchResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ttlMs: Long = 6 * 60 * 60_000L,
    private val retryDelayMs: Long = 60_000L,
    private val timeoutMs: Long = 30_000L,
    maxConcurrency: Int = 3,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)
    private val mutex = Mutex()
    private val permits = Semaphore(maxConcurrency)
    private data class Running(val fingerprint: String, val requestId: String, val result: Deferred<ProviderCatalogState?>)
    private val jobs = mutableMapOf<Uuid, Running>()
    private val mutableStates = MutableStateFlow<Map<Uuid, ProviderCatalogState>>(emptyMap())
    val states: StateFlow<Map<Uuid, ProviderCatalogState>> = mutableStates.asStateFlow()

    init {
        scope.launch {
            settings.filter { !it.init }.collect {
                mutex.withLock { settings.value.takeUnless { it.init }?.let(::reconcile) }
            }
        }
    }

    /** Hide old evidence synchronously, before the settings collector has processed a scope change. */
    fun currentState(providerId: Uuid): ProviderCatalogState? {
        val current = settings.value
        if (current.init) return null
        val provider = current.providers.find { it.id == providerId } ?: return null
        return states.value[providerId]?.takeIf {
            it.scopeFingerprint == catalogScopeFingerprint(provider, current.globalProxy, store.salt)
        }
    }

    /** Manual ignores TTL. AUTOMATIC is opt-in by the caller (the UI switch is introduced in stage C). */
    suspend fun refresh(providerId: Uuid, trigger: CatalogRefreshTrigger = CatalogRefreshTrigger.MANUAL): ProviderCatalogState? = withContext(dispatcher) {
        settings.first { !it.init }
        val running = mutex.withLock {
            val current = settings.value
            reconcile(current)
            val provider = current.providers.find { it.id == providerId } ?: return@withLock null
            val state = mutableStates.value.getValue(providerId)
            jobs[providerId]?.takeIf { it.fingerprint == state.scopeFingerprint }?.let { return@withLock it }
            if (trigger == CatalogRefreshTrigger.AUTOMATIC) {
                val now = clock()
                val age = state.lastComplete?.let { now - it.checkedAt }
                val lastTry = state.lastAttempt?.let { now - (it.finishedAt ?: it.queuedAt) }
                if (age != null && age in 0 until ttlMs || lastTry != null && lastTry in 0 until retryDelayMs) return@withLock null
            }
            val requestId = Uuid.random().toString()
            save(providerId, state.copy(lastAttempt = CatalogAttempt(requestId, trigger, CatalogAttemptStatus.QUEUED, clock())))
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                execute(providerId, state.scopeFingerprint, requestId, trigger)
            }
            Running(state.scopeFingerprint, requestId, deferred).also { jobs[provider.id] = it; deferred.start() }
        }
        if (running == null) return@withContext mutableStates.value[providerId]
        running.result.start()
        try {
            running.result.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // config invalidation must not cancel other members of a batch
            null
        }
    }

    suspend fun refreshAll(providerIds: Set<Uuid>? = null): Map<Uuid, ProviderCatalogState?> = supervisorScope {
        val current = settings.first { !it.init }
        val targets = current.providers.filter { it.enabled && (providerIds == null || it.id in providerIds) }.map { it.id }
        targets.map { id -> async { id to refresh(id, CatalogRefreshTrigger.BATCH) } }.awaitAll().toMap()
    }

    private suspend fun execute(id: Uuid, fingerprint: String, requestId: String, trigger: CatalogRefreshTrigger): ProviderCatalogState? {
        try {
            return permits.withPermit {
                val provider = mutex.withLock {
                    if (!isCurrent(id, fingerprint, requestId)) return@withLock null
                    val current = settings.value.providers.find { it.id == id } ?: return@withLock null
                    if (trigger != CatalogRefreshTrigger.MANUAL && !current.enabled) {
                        finish(id, fingerprint, requestId, CatalogAttemptStatus.SKIPPED)
                        return@withLock null
                    }
                    val previous = mutableStates.value.getValue(id)
                    save(id, previous.copy(lastAttempt = previous.lastAttempt!!.copy(status = CatalogAttemptStatus.RUNNING)))
                    current
                } ?: return@withPermit mutableStates.value[id]
                val result = if (provider is ProviderSetting.Account && provider.credential == null) {
                    CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.NOT_LOGGED_IN, "Sign in to refresh the model catalog"))
                } else {
                    try {
                        withTimeout(timeoutMs) { fetch(provider) }
                    } catch (_: TimeoutCancellationException) {
                        CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.TIMEOUT, "Model catalog refresh timed out"))
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        CatalogFetchResult.Failed(CatalogError(CatalogErrorKind.UNKNOWN, "Unable to refresh the model catalog"))
                    }
                }
                mutex.withLock {
                    if (!isCurrent(id, fingerprint, requestId)) return@withLock null
                    val current = mutableStates.value.getValue(id)
                    val attempt = current.lastAttempt!!
                    val updated = when (result) {
                        is CatalogFetchResult.Complete -> {
                            if (result.scope.providerId != id.toString()) return@withLock finish(id, fingerprint, requestId, CatalogAttemptStatus.FAILED,
                                CatalogError(CatalogErrorKind.UNKNOWN, "Catalog configuration changed"))
                            current.copy(lastComplete = CatalogSnapshot(result, clock()),
                                lastAttempt = attempt.copy(status = CatalogAttemptStatus.COMPLETE, finishedAt = clock()))
                        }
                        is CatalogFetchResult.Partial -> {
                            if (result.scope.providerId != id.toString()) return@withLock finish(id, fingerprint, requestId, CatalogAttemptStatus.FAILED,
                                CatalogError(CatalogErrorKind.UNKNOWN, "Catalog configuration changed"))
                            current.copy(lastAttempt = attempt.copy(status = CatalogAttemptStatus.PARTIAL, finishedAt = clock(), partial = result))
                        }
                        is CatalogFetchResult.Failed -> current.copy(lastAttempt = attempt.copy(status = CatalogAttemptStatus.FAILED, finishedAt = clock(), error = result.error))
                        is CatalogFetchResult.Unsupported -> current.copy(lastAttempt = attempt.copy(status = CatalogAttemptStatus.UNSUPPORTED, finishedAt = clock(), httpStatus = result.httpStatus))
                    }
                    save(id, updated)
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock { finish(id, fingerprint, requestId, CatalogAttemptStatus.INTERRUPTED) }
            }
            throw e
        } finally {
            withContext(NonCancellable + dispatcher) {
                mutex.withLock { if (jobs[id]?.requestId == requestId) jobs.remove(id) }
            }
        }
    }

    /** Called under mutex; compares live settings too, even if their collector has not run yet. */
    private fun isCurrent(id: Uuid, fingerprint: String, requestId: String): Boolean {
        val provider = settings.value.providers.find { it.id == id } ?: return false
        return jobs[id]?.requestId == requestId && fingerprint == catalogScopeFingerprint(provider, settings.value.globalProxy, store.salt)
    }

    private fun finish(id: Uuid, fingerprint: String, requestId: String, status: CatalogAttemptStatus, error: CatalogError? = null): ProviderCatalogState? {
        if (!isCurrent(id, fingerprint, requestId)) return null
        val current = mutableStates.value.getValue(id)
        return save(id, current.copy(lastAttempt = current.lastAttempt!!.copy(status = status, finishedAt = clock(), error = error)))
    }

    private fun save(id: Uuid, state: ProviderCatalogState): ProviderCatalogState {
        val clean = state.copy(cacheWriteFailed = false)
        val updated = clean.copy(cacheWriteFailed = !store.save(id, clean))
        mutableStates.value = mutableStates.value + (id to updated)
        return updated
    }

    private fun reconcile(current: Settings) {
        val ids = current.providers.map { it.id }.toSet()
        (mutableStates.value.keys + store.ids()).filter { it !in ids }.forEach { id ->
            jobs.remove(id)?.result?.cancel()
            mutableStates.value = mutableStates.value - id
            store.remove(id)
        }
        current.providers.forEach { provider ->
            val id = provider.id
            val fingerprint = catalogScopeFingerprint(provider, current.globalProxy, store.salt)
            val state = mutableStates.value[id]
            if (state?.scopeFingerprint == fingerprint) return@forEach
            jobs.remove(id)?.result?.cancel()
            val restored = if (state == null) store.load(id)?.takeIf { it.scopeFingerprint == fingerprint } else null
            val normalized = restored?.let { cached ->
                if (cached.lastAttempt?.status in listOf(CatalogAttemptStatus.QUEUED, CatalogAttemptStatus.RUNNING))
                    cached.copy(lastAttempt = cached.lastAttempt!!.copy(status = CatalogAttemptStatus.INTERRUPTED, finishedAt = clock()))
                else cached
            } ?: ProviderCatalogState(fingerprint)
            save(id, normalized)
        }
    }
}
