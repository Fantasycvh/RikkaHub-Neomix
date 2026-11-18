package app.androidrik.rikkahub.service

import app.androidrik.common.android.sanitizeSensitiveText
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.files.uploadDestinationFingerprint
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.media.resolveMediaModel
import app.androidrik.rikkahub.data.media.MediaModelOption
import app.androidrik.rikkahub.data.model.MediaCreationRecord
import app.androidrik.rikkahub.data.model.MediaCreationStatus
import app.androidrik.rikkahub.data.repository.MediaCreationRepository
import app.androidrik.rikkahub.data.scheduled.RunEvidence
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAsset
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.data.scheduled.mediaGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

class ScheduledMediaRunner(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val tasks: ScheduledTaskRepository,
    private val media: MediaCreationRepository,
    private val service: MediaCreationService,
    private val runs: ScheduledRunRegistry,
) {
    internal fun agentModel(runId: String): MediaModelOption? {
        val grant = runs.mediaGrant(runId)?.takeUnless { it.isTemplate } ?: return null
        return settingsStore.settingsFlow.value.resolveMediaModel(grant.model)?.takeIf(grant::permits)
    }

    internal suspend fun authorizeImage(runId: String, count: Int, size: String): Pair<MediaModelOption, String> {
        val grant = checkNotNull(runs.mediaGrant(runId)?.takeUnless { it.isTemplate }) {
            "This run has no remaining image authorization"
        }
        val task = runs.taskIdOf(runId)?.let { tasks.byId(it) }
        check(task?.mediaGrant() == grant) { "The media authorization changed" }
        val option = checkNotNull(agentModel(runId)) { "The authorized image model is unavailable" }
        require(count in 1..(grant.params.count ?: 1)) { "Image count exceeds the task authorization" }
        val authorizedSize = grant.params.resolution ?: "auto"
        require(size == "auto" || size == authorizedSize) { "Image size exceeds the task authorization" }
        check(runs.consumeMediaGrant(runId, grant)) { "This run has already used its image authorization" }
        return option to authorizedSize
    }

    /** Retain the scheduled service until the record is durable, then wait in the app scope. */
    internal suspend fun start(task: ScheduledTaskEntity, runId: String) {
        val submitted = CompletableDeferred<Unit>()
        appScope.launch {
            run(task, runId, onSubmitted = { submitted.complete(Unit) })
        }.invokeOnCompletion { submitted.complete(Unit) }
        submitted.await()
    }

    private suspend fun run(task: ScheduledTaskEntity, runId: String, onSubmitted: () -> Unit): String {
        val taskGrant = task.mediaGrant()
        return track(task.id, runId, taskGrant) { id ->
            val grant = checkNotNull(taskGrant?.takeIf { it.isTemplate }) {
                "This media template has no task authorization"
            }
            val prompt = requireNotNull(grant.templatePrompt)
            check(task.prompt.trim() == prompt) { "The template prompt has changed" }
            val settings = withTimeoutOrNull(ScheduledTaskRunner.SETTINGS_AWAIT_TIMEOUT) {
                settingsStore.awaitLoaded()
            } ?: error("Model settings could not be loaded")
            val option = settings.resolveMediaModel(grant.model)
                ?.takeIf(grant::permits) ?: error("The authorized media model or parameters are unavailable")
            if (grant.templateAssets.isNotEmpty()) {
                // 上传目的地变化说明素材可能传到别的桶里去了：当作授权失效，让用户重新保存任务
                val current = settings.uploadS3Config.withHttpsByDefault().uploadDestinationFingerprint()
                check(current == grant.uploadFingerprint) {
                    "The asset upload destination changed. Save the task again to update its authorization"
                }
            }
            val latest = tasks.byId(task.id)
            check(latest != null && latest.mediaGrant() == grant && latest.prompt.trim() == prompt) {
                "The media authorization changed"
            }
            check(runs.consumeMediaGrant(runId, grant)) { "The media authorization has already been used" }
            withContext(NonCancellable) {
                media.getOrCreateSession(id, task.title)
                tasks.linkMediaSession(runId, id.toString())
                if (runs.isCancelled(runId)) throw CancellationException("Scheduled media run cancelled")
                service.submit(
                    MediaCreationSubmission(
                        sessionId = id,
                        option = option,
                        prompt = prompt,
                        params = grant.params,
                        assets = grant.templateAssets.map(ScheduledMediaAsset::toMediaCreationAsset),
                    ),
                    recordId = id,
                )
            }
            onSubmitted()
            service.awaitRecord(id)
        }
    }

    /** Existing provider tasks are recovered by MediaCreationService. */
    internal suspend fun reconcile() = coroutineScope {
        tasks.flowUnfinishedRuns().first().filter { it.mediaSessionId == it.id }.forEach { run ->
            launch {
                track(run.taskId, run.id) { id -> service.awaitRecord(id) }
            }
        }
    }

    private suspend fun track(
        taskId: String,
        runId: String,
        grant: ScheduledMediaAction? = null,
        execute: suspend (Uuid) -> MediaCreationRecord?,
    ): String = supervisorScope {
        val id = Uuid.parse(runId)
        if (!runs.register(runId, taskId = taskId)) {
            return@supervisorScope tasks.runById(runId)?.status ?: TaskRunStatus.INTERRUPTED
        }
        try {
            tasks.runById(runId)?.status?.let { return@supervisorScope it }
            grant?.let { runs.setMediaGrant(runId, it) }
            val generation = async(start = CoroutineStart.LAZY) { execute(id) }
            runs.attachJob(runId, generation)
            val result = generation.await()
            finish(taskId, runId, result)
        } catch (e: CancellationException) {
            if (!runs.isCancelled(runId)) throw e
            withContext(NonCancellable) {
                service.cancel(id)
                finish(taskId, runId, media.getRecord(id), forcedStatus = TaskRunStatus.CANCELLED)
            }
        } catch (e: Exception) {
            finish(taskId, runId, null, forcedStatus = TaskRunStatus.FAILED, error = e.message)
        } finally {
            runs.unregister(runId)
        }
    }

    private suspend fun finish(
        taskId: String,
        runId: String,
        record: MediaCreationRecord?,
        forcedStatus: String? = null,
        error: String? = null,
    ): String {
        val status = forcedStatus ?: when (record?.status) {
            MediaCreationStatus.SUCCEEDED -> TaskRunStatus.OK
            MediaCreationStatus.CANCELLED -> TaskRunStatus.CANCELLED
            null -> TaskRunStatus.INTERRUPTED
            else -> TaskRunStatus.FAILED
        }
        val detail = (error ?: record?.error)?.let(::sanitizeSensitiveText).orEmpty()
        tasks.finishWithEvidence(runId, status, RunEvidence("unsupported", detail, "[]"))
        tasks.recordMediaStatus(taskId, runId, status)
        return status
    }
}
