package app.androidrik.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

class McpStatusErrorTest {

    @Test
    fun `connection layer failures are reported as unreachable`() {
        assertEquals(
            McpStatus.Error.Kind.UNREACHABLE,
            McpStatus.Error.from(ConnectException("Connection refused")).kind,
        )
        assertEquals(
            McpStatus.Error.Kind.UNREACHABLE,
            McpStatus.Error.from(UnknownHostException("example.invalid")).kind,
        )
        assertEquals(
            McpStatus.Error.Kind.UNREACHABLE,
            McpStatus.Error.from(SocketTimeoutException("Connect timed out")).kind,
        )
        assertEquals(
            McpStatus.Error.Kind.UNREACHABLE,
            McpStatus.Error.from(UnresolvedAddressException()).kind,
        )
    }

    @Test
    fun `protocol failures are reported as handshake`() {
        assertEquals(
            McpStatus.Error.Kind.HANDSHAKE,
            McpStatus.Error.from(IllegalStateException("Unexpected server response")).kind,
        )
        assertEquals(
            McpStatus.Error.Kind.HANDSHAKE,
            McpStatus.Error.from(RuntimeException("initialize failed")).kind,
        )
    }

    @Test
    fun `nested cause decides the kind`() {
        val wrapped = IllegalStateException(
            "connect failed",
            ConnectException("Connection refused"),
        )
        assertEquals(McpStatus.Error.Kind.UNREACHABLE, McpStatus.Error.from(wrapped).kind)
    }

    @Test
    fun `message is preserved and detail is captured`() {
        val error = McpStatus.Error.from(ConnectException("Connection refused"))
        assertEquals("Connection refused", error.message)
        assertTrue(error.detail?.contains("ConnectException") == true)
    }

    @Test
    fun `display text distinguishes the two kinds`() {
        val unreachable = McpStatus.Error.from(ConnectException("Connection refused")).displayText()
        val handshake = McpStatus.Error.from(IllegalStateException("bad init")).displayText()
        assertTrue(unreachable.contains("无法连接服务"))
        assertTrue(handshake.contains("协议握手失败"))
    }

    @Test
    fun `manually built error defaults to unknown kind`() {
        val error = McpStatus.Error("连接断开，已达最大重连次数")
        assertEquals(McpStatus.Error.Kind.UNKNOWN, error.kind)
        assertEquals("Error: 连接断开，已达最大重连次数", error.displayText())
    }
}
