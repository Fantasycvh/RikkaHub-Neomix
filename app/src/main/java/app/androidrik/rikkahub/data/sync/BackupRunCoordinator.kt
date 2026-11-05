package app.androidrik.rikkahub.data.sync

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.BACKUP_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity
import app.androidrik.rikkahub.data.sync.repo.BackupSnapshotPlan
import app.androidrik.rikkahub.service.BackupForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

private const val TAG = "BackupRun"

/** 长任务类型：决定通知标题与「结束提示」文案。 */
enum class BackupRunKind {
    FULL_BACKUP,
    FULL_RESTORE,
    EXPORT_BACKUP,
    SNAPSHOT_PLAN,
    SNAPSHOT_COMMIT,
    SNAPSHOT_RESTORE,
    SNAPSHOT_GC,
    SNAPSHOT_EXPORT,
}

/** 正在进行的长任务（UI 据此显示「任务进行中」与进度；通知栏同步）。 */
data class BackupRunState(
    val id: Long,
    val kind: BackupRunKind,
    val startedAt: Long,
    val progress: BackupProgress? = null,
)

/**
 * 备份/恢复长任务的进程级执行器：任务跑在应用作用域（[AppScope]）而不是页面 ViewModel 里，
 * 因此离开备份页、切后台后任务继续；同时用 [BackupForegroundService] 把进程留在前台并显示进度通知。
 *
 * 关键语义：
 * - **调用方取消不取消任务**：页面被销毁时 `await` 抛 `CancellationException`，但任务协程继续跑完，
 *   完成后由通知栏报告结果（成功/失败/取消）。
 * - **单任务**：同一时刻只允许一个长任务；第二个 `await` 立即抛错（UI 也会按 [state] 禁用按钮）。
 * - **通知栏取消**：通知里的「取消」直接取消任务协程；引擎自身负责中途清理。
 */
class BackupRunCoordinator(
    private val context: Application,
    private val appScope: AppScope,
) {

    private val lock = Any()
    private val nextId = AtomicLong()
    private val notifications = NotificationManagerCompat.from(context)

    private val _state = MutableStateFlow<BackupRunState?>(null)

    /** 当前长任务；null = 空闲。 */
    val state: StateFlow<BackupRunState?> = _state.asStateFlow()

    /**
     * 「扫描并预估」产生的计划。放在进程级是为了：扫描期间离开页面，任务仍跑完并把计划留着，
     * 用户回到备份页可以直接确认，而不是重新扫描一遍。
     */
    private val _pendingPlan = MutableStateFlow<BackupSnapshotPlan?>(null)
    internal val pendingPlan: StateFlow<BackupSnapshotPlan?> = _pendingPlan.asStateFlow()

    private var current: Deferred<*>? = null
    private var running = false
    private var planExpiry: Job? = null

    /**
     * 在应用级作用域里跑一个长任务并等待结果。
     *
     * 调用方被取消（页面销毁）只会让本 `await` 返回取消异常；任务继续执行到结束并更新通知栏。
     */
    suspend fun <T> await(kind: BackupRunKind, block: suspend (report: (BackupProgress) -> Unit) -> T): T {
        val deferred = start(kind, block)
        return deferred.await()
    }

    /** 通知栏「取消」。没有任务时是 no-op。 */
    fun cancel() {
        val job = synchronized(lock) { current }
        job?.cancel()
    }

    /** 保存扫描计划（替换旧计划时关闭旧的）。 */
    internal fun storePlan(plan: BackupSnapshotPlan) {
        val previous = synchronized(lock) { _pendingPlan.value.also { _pendingPlan.value = plan } }
        if (previous != null && previous !== plan) closePlanAsync(previous)
        planExpiry?.cancel()
        planExpiry = appScope.launch {
            delay(PLAN_TTL.inWholeMilliseconds)
            if (_pendingPlan.compareAndSet(plan, null)) closePlanAsync(plan)
        }
    }

    /** 关闭并清空当前计划（用户取消、已提交、或过期）。 */
    internal fun clearPlan(only: BackupSnapshotPlan? = null) {
        val previous = if (only == null) {
            synchronized(lock) { _pendingPlan.value.also { _pendingPlan.value = null } }
        } else {
            _pendingPlan.compareAndSet(only, null).let { if (it) only else null }
        }
        planExpiry?.cancel()
        planExpiry = null
        previous?.let(::closePlanAsync)
    }

    private fun closePlanAsync(plan: BackupSnapshotPlan) {
        appScope.launch(Dispatchers.IO) {
            runCatching { plan.close() }
                .onFailure { Log.w(TAG, "Unable to close backup plan", it) }
        }
    }

    private fun <T> start(kind: BackupRunKind, block: suspend (report: (BackupProgress) -> Unit) -> T): Deferred<T> {
        synchronized(lock) {
            check(!running) { "已有备份任务正在进行" }
            running = true
            val runId = nextId.incrementAndGet()
            val startedAt = System.currentTimeMillis()
            val initialState = BackupRunState(runId, kind, startedAt)
            _state.value = initialState
            val deferred = appScope.async(CoroutineName("BackupTask")) {
                val selfJob = coroutineContext[Job]
                val runThrottle = ProgressThrottle(minFractionStep = 0.02f, minIntervalMillis = 500L)
                var failure: Throwable? = null
                var cancelled = false
                val foreground = BackupForegroundService.acquire(context, buildProgressNotification(initialState))
                try {
                    block { progress ->
                        val currentState = _state.value
                        if (currentState != null && currentState.id == runId) {
                            _state.value = currentState.copy(progress = progress)
                            if (runThrottle.shouldEmit(progress)) {
                                notifyCompat(buildProgressNotification(currentState.copy(progress = progress)))
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                } catch (throwable: Throwable) {
                    failure = throwable
                    throw throwable
                } finally {
                    withContext(NonCancellable) {
                        _state.value = null
                        synchronized(lock) {
                            running = false
                            if (current === selfJob) current = null
                        }
                        if (foreground) BackupForegroundService.release(context)
                        notifyCompat(buildFinishedNotification(kind, failure, cancelled))
                        if (failure != null) Log.e(TAG, "Backup task $kind failed", failure)
                    }
                }
            }
            current = deferred
            return deferred
        }
    }

    private fun buildProgressNotification(state: BackupRunState): Notification {
        val progress = state.progress
        val fraction = progress?.fraction
        return NotificationCompat.Builder(context, BACKUP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(titleFor(state.kind))
            .setContentText(progressText(progress))
            .setProgress(100, fraction?.let { (it * 100).toInt() } ?: 0, fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .addAction(0, "取消", cancelPendingIntent())
            .build()
    }

    private fun buildFinishedNotification(kind: BackupRunKind, failure: Throwable?, cancelled: Boolean): Notification {
        val text = when {
            cancelled -> "任务已取消"
            failure != null -> "任务失败：${failure.message ?: failure.javaClass.simpleName}"
            else -> finishedTextFor(kind)
        }
        return NotificationCompat.Builder(context, BACKUP_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(if (failure != null && !cancelled) "备份任务失败" else titleFor(kind))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun notifyCompat(notification: Notification) {
        runCatching { notifications.notify(BackupForegroundService.NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Unable to post backup notification", it) }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = BackupForegroundService.cancelIntent(context)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun titleFor(kind: BackupRunKind): String = when (kind) {
        BackupRunKind.FULL_BACKUP -> "正在备份"
        BackupRunKind.FULL_RESTORE -> "正在恢复"
        BackupRunKind.EXPORT_BACKUP -> "正在导出备份"
        BackupRunKind.SNAPSHOT_PLAN -> "正在扫描备份内容"
        BackupRunKind.SNAPSHOT_COMMIT -> "正在写入备份仓库"
        BackupRunKind.SNAPSHOT_RESTORE -> "正在恢复备份快照"
        BackupRunKind.SNAPSHOT_GC -> "正在回收未引用对象"
        BackupRunKind.SNAPSHOT_EXPORT -> "正在导出快照"
    }

    private fun finishedTextFor(kind: BackupRunKind): String = when (kind) {
        BackupRunKind.FULL_BACKUP, BackupRunKind.SNAPSHOT_COMMIT -> "备份完成"
        BackupRunKind.FULL_RESTORE, BackupRunKind.SNAPSHOT_RESTORE -> "恢复完成，重启应用后生效"
        BackupRunKind.EXPORT_BACKUP -> "备份导出完成"
        BackupRunKind.SNAPSHOT_PLAN -> "扫描完成，回到应用确认备份"
        BackupRunKind.SNAPSHOT_GC -> "回收完成"
        BackupRunKind.SNAPSHOT_EXPORT -> "快照导出完成"
    }

    private fun progressText(progress: BackupProgress?): String {
        if (progress == null) return "准备中…"
        val phase = when (progress.phase) {
            BackupPhase.SCANNING -> "扫描"
            BackupPhase.COMMITTING -> "提交"
            BackupPhase.PACKING -> "打包"
            BackupPhase.PREPARING -> "准备"
            BackupPhase.UPLOADING -> "上传"
            BackupPhase.DOWNLOADING -> "下载"
            BackupPhase.EXTRACTING -> "解压"
        }
        val percent = progress.fraction?.let { "${(it * 100).toInt()}%" }.orEmpty()
        return if (percent.isEmpty()) phase else "$phase $percent"
    }

    private companion object {
        /** 计划有效期与 UI 文案一致（30 分钟）；过期自动释放临时文件。 */
        val PLAN_TTL = 30.minutes
    }
}
