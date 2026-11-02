package app.androidrik.rikkahub.data.datastore

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.AppScope
import java.io.File
import kotlin.uuid.Uuid
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 回滚开关的真机落盘/回读验证（不进用户配置）。
 *
 * 为什么要在 instrumentation 里跑：四个开关的值走 DataStore protobuf，JVM 单测只能验
 * `Settings` 默认值与 copy；这里用隔离 `settings.preferences_pb` 真正写一次、重开一次，
 * 确认 key 映射与 `Settings` 字段没有接错——这类"设置页点了没生效"的错位只有真机路径能抓到。
 */
@RunWith(AndroidJUnit4::class)
class P0RollbackSwitchInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rollbackSwitchesPersistAndReload() = runBlocking {
        val files = File(context.cacheDir, "rollback-switch-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = files
        }
        val scope = AppScope()
        val settingsFile = File(files, "settings.preferences_pb")
        try {
            val store = SettingsStore(isolated, scope, settingsFile)
            val initial = withTimeout(10_000) { store.settingsFlow.first() }
            assertTrue(initial.taskCenterEnabled)
            assertTrue(initial.namespacedNotifications)
            assertTrue(initial.dynamicRunGate)
            assertTrue(initial.killShellTasksOnRunCancel)

            store.update {
                it.copy(
                    taskCenterEnabled = false,
                    namespacedNotifications = false,
                    dynamicRunGate = false,
                    killShellTasksOnRunCancel = false,
                )
            }
            assertFalse(withTimeout(10_000) { store.settingsFlow.first() }.taskCenterEnabled)

            // DataStore 同一文件在同进程只能有一个活动实例，故复制到新路径再开新实例：
            // 读的是同一份 protobuf 字节，证明写路径与读路径的 key 一致，而不是只改了内存。
            val copy = File(files, "settings-copy.preferences_pb")
            settingsFile.copyTo(copy, overwrite = true)
            val reloadScope = AppScope()
            try {
                val reloaded = SettingsStore(isolated, reloadScope, copy)
                val persisted = withTimeout(10_000) { reloaded.settingsFlow.first() }
                assertFalse(persisted.taskCenterEnabled)
                assertFalse(persisted.namespacedNotifications)
                assertFalse(persisted.dynamicRunGate)
                assertFalse(persisted.killShellTasksOnRunCancel)
            } finally {
                reloadScope.cancel()
            }
        } finally {
            scope.cancel()
            files.deleteRecursively()
        }
    }
}
