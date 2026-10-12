package app.androidrik.rikkahub.data.ai

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 应用是否在前台。调度器用它决定「后台时要不要提前占住保活」。
 *
 * 单独一个小持有者（而不是把判断塞进 Runtime）：调度器在构造时就要求 `isAppVisible`，
 * 而 Runtime 的观察者要在 `Application.onCreate` 之后才注册，两者生命周期不同。
 */
class AppForegroundState {
    @Volatile
    var isForeground: Boolean = true
}

/**
 * 做梦（V3）的**触发面**：把「一轮对话结束」翻译成调度器的输入。
 *
 * 为什么与 [MemoryConsolidationRuntime] 分开：这一层是**纯逻辑**（拿到会话 → 交给调度器），
 * 可以用 JVM 单测钉住「事件 → 调度器」这条链路；Runtime 那层只是 Android 粘合
 * （`ProcessLifecycleOwner` + `AppEventBus`），由源码扫描守卫兜住。
 */
class MemoryConsolidationTrigger(
    private val scheduler: MemoryConsolidationScheduler,
    private val conversations: suspend (Uuid) -> Conversation?,
) {
    /**
     * 一轮对话结束（生成完成、失败、取消都算——这一轮确实结束了）。
     *
     * **故意不判"这轮有没有值得记的内容"**：那是 worker 的门控与 prompt 的职责，
     * 触发面只需要把「用户又动了一下」这个事实交上去（调度器据此重排空闲计时）。
     */
    suspend fun onGenerationEnded(conversationId: Uuid) {
        val conversation = conversations(conversationId) ?: return
        scheduler.onTurnFinished(conversation)
    }

    /** 启动补漏：进程被杀前没整理完的对话 */
    suspend fun resumePending() = scheduler.resumePending()
}

/**
 * 做梦的 Android 粘合层：三个钩子 + 一次通知。
 *
 * 触发面（`docs/design/design-memory-consolidation.md` §9.2）：
 * - **轮末**：`ChatGenerationEnded`（应用自己的事件总线，`ChatService` 在生成结束时发）；
 * - **切后台**：`ProcessLifecycleOwner.onStop` ⇒ `onAppHidden`（后台的空闲计时不一定走得完，
 *   有待整理的就立刻开始）；
 * - **启动补漏**：`start()` 里 `resumePending()`；
 * - **离开对话**：**暂不接**（见下）。
 *
 * **为什么"离开对话"这条不接**：`ChatPage` 没有可靠的离页信号（只有一个抽屉的 `BackHandler`），
 * 而它带来的收益只是把「等空闲 2 分钟」提前成「立刻」——轮末计时与切后台已经覆盖了这条路径，
 * 差别在体感不在功能。要接的话得动导航层，值得单独一刀。
 *
 * **为什么保活先留空实现**：本仓已有的 `KeepAliveService` 是**用户自己的开关**（常驻通知 + 电池白名单），
 * 拿它给后台整理续命会把用户没开的「保活」悄悄打开，语义冲突；独立的前台服务要新渠道 + manifest +
 * 生命周期，也值得单独一刀。退化的代价是可接受的：切后台时立刻开始跑，进程若被冻结则这一轮不算完成
 * ——**水位不推进、内容不丢**，下次再来。
 */
class MemoryConsolidationRuntime(
    private val trigger: MemoryConsolidationTrigger,
    private val scheduler: MemoryConsolidationScheduler,
    private val foreground: AppForegroundState,
    private val appEventBus: AppEventBus,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "MemoryConsolidation"
    }

    /** 必须主线程调用（`ProcessLifecycleOwner` 的要求，与 `ChatNotificationManager` 同款）。 */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground.isForeground = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground.isForeground = false
                    // 切后台：有待整理的就现在开始（后台的空闲计时不一定走得完）
                    scheduler.onAppHidden()
                }
            }
        )

        appScope.launch(Dispatchers.Default) {
            appEventBus.events.collect { event ->
                if (event is AppEvent.ChatGenerationEnded) {
                    runCatching { trigger.onGenerationEnded(event.conversationId) }
                        .onFailure { Log.w(TAG, "turn-end trigger failed", it) }
                }
            }
        }
        appScope.launch(Dispatchers.Default) {
            runCatching { trigger.resumePending() }
                .onFailure { Log.w(TAG, "startup resume failed", it) }
        }
        appScope.launch(Dispatchers.Default) {
            // 自动整理的结果**只在这里**变成用户可见的一次性提示（手动整理的结果落在记忆页状态里）
            scheduler.automaticRuns.collect { run ->
                if (run.changedMemories == 0 && run.proposals.isEmpty()) return@collect
                appEventBus.tryEmit(
                    AppEvent.MemoryConsolidationFinished(
                        applied = run.changedMemories,
                        proposals = run.proposals.size,
                    )
                )
            }
        }
    }
}
