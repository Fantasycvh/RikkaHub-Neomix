package app.androidrik.rikkahub.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()

    /**
     * 连接/同步出错。
     *
     * @param message 简短摘要，用于列表内联展示
     * @param detail 完整错误信息（含 cause 链与堆栈），用于展开查看与复制；无异常来源时为 null
     * @param kind 失败环节（服务不可达 / 协议握手失败），用于区分提示文案
     */
    data class Error(
        val message: String,
        val detail: String? = null,
        val kind: Kind = Kind.UNKNOWN,
    ) : McpStatus() {
        /**
         * 失败环节。
         *
         * - [UNREACHABLE]：TCP/TLS 层就没连上——服务没启动、地址或端口不对、网络不通
         * - [HANDSHAKE]：连接已建立，但 MCP 协议层失败（初始化 / 工具列表 / 响应异常）
         * - [UNKNOWN]：无法判定来源（如 OAuth 流程失败、重连次数耗尽）
         */
        enum class Kind { UNREACHABLE, HANDSHAKE, UNKNOWN }

        companion object {
            fun from(throwable: Throwable, fallbackMessage: String? = null): Error {
                val summary = throwable.message?.takeIf { it.isNotBlank() }
                    ?: fallbackMessage
                    ?: throwable.javaClass.simpleName
                return Error(
                    message = summary,
                    detail = throwable.stackTraceToString(),
                    kind = classify(throwable),
                )
            }

            /** 按异常类型/message 特征判定失败环节，与 [McpOAuthCoordinator.needsAuthorization] 同风格。 */
            private fun classify(throwable: Throwable): Kind {
                val chain = generateSequence(throwable) { it.cause }.toList()
                val signal = chain.joinToString(" ") { it.javaClass.name + " " + (it.message ?: "") }
                return if (UNREACHABLE_MARKERS.any { signal.contains(it, ignoreCase = true) }) {
                    Kind.UNREACHABLE
                } else {
                    // 能走到这里说明异常来自已建立的连接（协议层），而非 TCP/TLS 拨号阶段
                    Kind.HANDSHAKE
                }
            }

            private val UNREACHABLE_MARKERS = listOf(
                "ConnectException",
                "UnresolvedAddressException",
                "UnknownHostException",
                "SocketTimeoutException",
                "ConnectTimeoutException",
                "NoRouteToHostException",
                "SSLHandshakeException",
                "Failed to connect",
                "Connection refused",
                "Connection reset",
            )
        }
    }

    /** 服务器返回 401，需要用户完成 OAuth 授权。 */
    data object NeedsAuthorization : McpStatus()

    /** 正在进行 OAuth 授权流程（等待浏览器回调 / 交换令牌）。 */
    data object Authorizing : McpStatus()
}

/**
 * 面向 UI 的错误摘要：区分「服务不可达」与「协议握手失败」，让用户能判断该查服务端还是查配置。
 * 原始异常摘要仍通过 [McpStatus.Error.message] 提供。
 */
fun McpStatus.Error.displayText(): String = when (kind) {
    McpStatus.Error.Kind.UNREACHABLE -> "无法连接服务（服务未启动或地址/网络不可达）: $message"
    McpStatus.Error.Kind.HANDSHAKE -> "连接已建立但协议握手失败: $message"
    McpStatus.Error.Kind.UNKNOWN -> "Error: $message"
}
