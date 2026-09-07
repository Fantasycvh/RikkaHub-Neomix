package app.androidrik.rikkahub.tikz

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TikZ native 引擎隔离进程（方案 B 第 4 步，docs 8.4.5）。
 *
 * 根因隔离：tectonic 与 dvisvgm（已移除，mupdf 链接管）同进程共存时会 C++
 * 静态单例/流状态互污染。现在 tectonic 独占 [TexCompileService] 隔离进程
 * （PDF/XDV 编译）；SVG 转换由主进程内 MupdfSvgBridge（libmupdf_java.so）
 * 完成，无需第二个隔离进程。native 崩溃只杀隔离进程，主进程无感。
 *
 * Messenger 协议：
 * - 请求 MSG_RUN(1)：data 携带业务参数（见各 Service）
 * - 响应 MSG_RESULT(2)：arg1=0 成功（data "data" ByteArray）/ 1 失败（data "error"）
 */

private const val TAG_BASE = "TexIsolated"

/** 通用响应协议常量 */
object IsoProto {
    const val MSG_RUN = 1
    const val MSG_RESULT = 2
    const val KEY_DATA = "data"
    const val KEY_ERROR = "error"
}

/** tectonic 编译协议 */
object TexProto {
    const val KEY_TEX = "tex"
}

/**
 * tectonic 专属进程：tex 源码 → PDF/XDV。
 * format 用 arg1 传递（0=pdf, 1=xdv）。
 */
class TexCompileService : Service() {

    // 注意：Service 构造阶段 base context 尚未 attach，messenger 必须懒初始化
    //（ IncomingHandler 需要 applicationContext，构造里用 context 会 NPE）
    private var messengerField: Messenger? = null

    override fun onCreate() {
        super.onCreate()
        messengerField = Messenger(TexIncomingHandler(applicationContext))
        Log.i(TAG_BASE, "texcompile process ready (pid=${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder = messengerField!!.binder
}

/** tectonic 编译请求处理（文件级：Service 构造阶段不实例化） */
private class TexIncomingHandler(private val app: Context) : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
        val replyTo = msg.replyTo ?: return
        if (msg.what == IsoProto.MSG_RUN) {
            val tex = msg.data.getString(TexProto.KEY_TEX) ?: ""
            val format = if (msg.arg1 == 1) "xdv" else "pdf"
            respond(replyTo) {
                val data = TectonicNative.compile(app, tex, format)
                    ?: error(TectonicNative.lastErrorText() ?: "compile failed")
                data
            }
        } else {
            super.handleMessage(msg)
        }
    }
}

/** 统一的执行+回包 helper：block 抛异常转错误响应 */
private fun respond(replyTo: Messenger, block: () -> ByteArray) {
    val result = Message.obtain(null, IsoProto.MSG_RESULT)
    result.data = Bundle()
    runCatching {
        val data = block()
        result.arg1 = 0
        result.data.putByteArray(IsoProto.KEY_DATA, data)
    }.onFailure {
        result.arg1 = 1
        result.data.putString(IsoProto.KEY_ERROR, it.message ?: "unknown error")
    }
    runCatching { replyTo.send(result) }
}

/**
 * 主进程侧客户端：bindService 到指定隔离 Service，同步执行并取回数据。
 * 每次调用独立 bind（请求量低，简单可靠优于长连接状态管理）。
 * 必须在 IO 线程调用（内部阻塞）。
 */
object IsoServiceClient {

    private const val TAG = "IsoServiceClient"
    private const val TIMEOUT_MS = 90_000L

    data class IsoResult(val data: ByteArray?, val error: String?)

    /**
     * [prepare] 在连接建立后构造请求 Message（设置 what/arg1/data/replyTo），
     * 返回 null 表示构造失败（直接报错）。
     */
    fun call(context: Context, serviceClass: Class<*>, prepare: (Messenger) -> Message?): IsoResult {
        val latch = CountDownLatch(1)
        var result = IsoResult(null, "no response")
        val responseMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == IsoProto.MSG_RESULT) {
                    result = if (msg.arg1 == 0) {
                        IsoResult(msg.data.getByteArray(IsoProto.KEY_DATA), null)
                    } else {
                        IsoResult(null, msg.data.getString(IsoProto.KEY_ERROR) ?: "unknown error")
                    }
                    latch.countDown()
                }
            }
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val request = prepare(responseMessenger) ?: run {
                    result = IsoResult(null, "request build failed")
                    latch.countDown()
                    return
                }
                runCatching { Messenger(service).send(request) }
                    .onFailure {
                        result = IsoResult(null, "send failed: ${it.message}")
                        latch.countDown()
                    }
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }

        val ok = runCatching {
            context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            return IsoResult(null, "bindService failed")
        }
        try {
            // 覆盖 bind + 编译全流程（首次 format 重建 ~10s + pgfplots ~4s，留足余量）
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return IsoResult(null, "isolated call timed out")
            }
        } catch (e: InterruptedException) {
            return IsoResult(null, "interrupted: ${e.message}")
        } finally {
            runCatching { context.unbindService(connection) }
        }
        return result
    }
}