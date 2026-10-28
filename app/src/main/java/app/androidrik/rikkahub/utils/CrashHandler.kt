package app.androidrik.rikkahub.utils

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import app.androidrik.common.android.sanitizeSensitiveText
import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CrashHandler"
private const val PREFS_NAME = "crash_handler"
private const val KEY_CRASHED = "crashed"
private const val KEY_STACKTRACE = "stacktrace"
private const val MAX_STACKTRACE_LENGTH = 8000

/**
 * 崩溃文件保留数量：日志目录里的 crash-*.txt 超过则删最旧。
 * 本地留存最近几次崩溃，替代 Crashlytics 移除后的云端聚合。
 */
private const val MAX_CRASH_FILES = 5

object CrashHandler {
    fun install(context: Context) {
        val appContext = context.applicationContext
        // 与 Logging.initLogDirectory 同目录（logs/），崩溃报告和诊断日志放一起
        val crashDir = File(appContext.filesDir, FileFolders.LOGS)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            markCrashed(appContext, thread, throwable)
            writeCrashFile(crashDir, thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun hasCrashed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHED, false)
    }

    fun getStackTrace(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STACKTRACE, null)
    }

    fun clearCrashed(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_CRASHED).remove(KEY_STACKTRACE) }
    }

    private fun markCrashed(context: Context, thread: Thread, throwable: Throwable) {
        val stackTrace = buildString {
            appendLine("Thread: ${thread.name}")
            appendLine(throwable.stackTraceToString())
        }.take(MAX_STACKTRACE_LENGTH)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_CRASHED, true)
                putString(KEY_STACKTRACE, stackTrace)
            } // commit() 同步写入，确保崩溃前写完
    }

    /** 完整堆栈落盘（脱敏后），prefs 里的单条记录是截断版，SafeModeActivity 用它做兜底展示。 */
    private fun writeCrashFile(dir: File, thread: Thread, throwable: Throwable) {
        runCatching {
            dir.mkdirs()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val fileName = "crash-" + time.replace(" ", "_").replace(":", "-") + ".txt"
            File(dir, fileName).writeText(
                buildString {
                    appendLine("===== crash @ $time | thread: ${thread.name} =====")
                    appendLine(sanitizeSensitiveText(throwable.stackTraceToString()))
                }
            )
            pruneOldCrashFiles(dir)
        }
    }

    private fun pruneOldCrashFiles(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: return
        files.sortedByDescending { it.name }
            .drop(MAX_CRASH_FILES)
            .forEach { it.delete() }
    }
}
