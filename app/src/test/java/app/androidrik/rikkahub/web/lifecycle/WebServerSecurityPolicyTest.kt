package app.androidrik.rikkahub.web.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.androidrik.rikkahub.service.shouldReleaseForegroundAfterState
import app.androidrik.rikkahub.web.WEB_STARTUP_ERROR_AUTH_REQUIRED
import app.androidrik.rikkahub.web.WEB_STARTUP_ERROR_PASSWORD_REQUIRED
import app.androidrik.rikkahub.web.WebLifecycleIntent
import app.androidrik.rikkahub.web.WebServerState
import app.androidrik.rikkahub.web.prepareStateForIntent
import app.androidrik.rikkahub.web.webStartupAuthError

/**
 * Web 服务「强制鉴权 + 生命周期收尾」安全策略的纯函数单测。
 *
 * 这批修复的核心约束：
 * 1. **不存在不鉴权模式**：口令为空 / 鉴权被关（存量设置）时拒绝启动，而不是退化成 LAN 裸奔；
 * 2. start 意图必须**同步**清掉上一次 attempt 的 error 并进入 loading——否则前台服务观察器
 *    先订阅到旧 error，会在失败重试刚发起时立刻 `stopSelf`（review 抓到的实际缺陷）；
 * 3. 启动失败（端口占用 / 缺口令 / 绑定异常）与「从未 running 的 STOP」必须真正收尾 FGS；
 * 4. 默认值安全侧：只绑回环 + 鉴权恒开。
 *
 * 只测纯函数与数据类默认值，不依赖 Android/Robolectric。
 */
class WebServerSecurityPolicyTest {

    // ---- 启动前鉴权校验（WebServerManager.webStartupAuthError） ----

    @Test
    fun `blank password is rejected even when auth flag is on`() {
        assertEquals(WEB_STARTUP_ERROR_PASSWORD_REQUIRED, webStartupAuthError("", jwtEnabled = true))
        assertEquals(WEB_STARTUP_ERROR_PASSWORD_REQUIRED, webStartupAuthError("   ", jwtEnabled = true))
    }

    @Test
    fun `disabled authentication is rejected even with a password`() {
        // 存量偏好/备份恢复里可能有 webServerJwtEnabled=false：绝不能因此裸奔。
        assertEquals(WEB_STARTUP_ERROR_AUTH_REQUIRED, webStartupAuthError("s3cret", jwtEnabled = false))
    }

    @Test
    fun `valid password with authentication enabled is allowed`() {
        assertNull(webStartupAuthError("s3cret", jwtEnabled = true))
    }

    // ---- 安全默认值 ----

    @Test
    fun `default state stays loopback only and not running`() {
        val state = WebServerState()
        assertTrue("默认必须只监听回环", state.localhostOnly)
        assertFalse(state.isRunning)
        assertFalse(state.isLoading)
        assertEquals(8080L, state.port.toLong())
        assertNull(state.error)
    }

    // ---- start/stop 意图的同步状态准备 ----

    @Test
    fun `start intent clears previous error and enters loading synchronously`() {
        val failed = WebServerState(
            isRunning = false,
            isLoading = false,
            port = 8080,
            hostname = "old-host",
            address = "192.168.1.2",
            error = "Port 8080 is already in use",
        )
        val prepared = prepareStateForIntent(
            WebLifecycleIntent.Start(port = 9090, serviceName = "hub", localhostOnly = false),
            failed,
        )
        // 关键：旧 error 必须清掉（否则重试会被观察器当成「启动失败」立刻收尾）。
        assertNull(prepared.error)
        assertNull(prepared.hostname)
        assertNull(prepared.address)
        assertTrue(prepared.isLoading)
        assertFalse(prepared.isRunning)
        // 新参数必须立即反映到状态（前台服务/设置页读 state.port）。
        assertEquals(9090L, prepared.port.toLong())
        assertEquals("hub", prepared.serviceName)
        assertFalse(prepared.localhostOnly)
    }

    @Test
    fun `duplicate start while running keeps mdns hostname and address`() {
        // review 场景：运行中重复 start 若清空 hostname，`additionalAllowedHosts` 里的
        // mDNS 主机名白名单会瞬时失效（`<service>.local` 请求被 403）。
        val running = WebServerState(
            isRunning = true,
            isLoading = false,
            port = 8080,
            serviceName = "hub",
            localhostOnly = false,
            hostname = "hub.local",
            address = "192.168.1.2",
            error = "stale",
        )
        val prepared = prepareStateForIntent(
            WebLifecycleIntent.Start(port = 8080, serviceName = "hub", localhostOnly = false),
            running,
        )
        assertTrue("服务仍在运行，不能提前置 stopped", prepared.isRunning)
        assertFalse(prepared.isLoading)
        assertEquals("hub.local", prepared.hostname)
        assertEquals("192.168.1.2", prepared.address)
        assertNull("旧 error 仍要清掉", prepared.error)
    }

    @Test
    fun `restart with a different port clears mdns fields while stopping`() {
        val running = WebServerState(
            isRunning = true,
            port = 8080,
            serviceName = "hub",
            localhostOnly = false,
            hostname = "hub.local",
            address = "192.168.1.2",
        )
        val prepared = prepareStateForIntent(
            WebLifecycleIntent.Start(port = 9090, serviceName = "hub", localhostOnly = false),
            running,
        )
        assertFalse(prepared.isRunning)
        assertTrue(prepared.isLoading)
        assertNull(prepared.hostname)
        assertNull(prepared.address)
        assertEquals(9090L, prepared.port.toLong())
    }

    @Test
    fun `stop intent clears error and running fields but keeps config`() {
        val running = WebServerState(
            isRunning = true,
            isLoading = false,
            port = 9090,
            serviceName = "hub",
            localhostOnly = false,
            hostname = "hub.local",
            address = "192.168.1.2",
            error = "stale",
        )
        val prepared = prepareStateForIntent(WebLifecycleIntent.Stop, running)
        assertFalse(prepared.isRunning)
        assertTrue(prepared.isLoading)
        assertNull(prepared.error)
        assertNull(prepared.hostname)
        assertNull(prepared.address)
        assertEquals(9090L, prepared.port.toLong())
        assertEquals("hub", prepared.serviceName)
        assertFalse(prepared.localhostOnly)
    }

    @Test
    fun `retry prepared state cannot be released by observer`() {
        // 复现 review 场景：上一次失败留下 error，重试 start 的同步准备态必须让观察器
        // 判定「仍在启动」而不是收尾（旧实现会在这里 stopSelf 掉刚发起的前台服务）。
        val failed = WebServerState(error = WEB_STARTUP_ERROR_PASSWORD_REQUIRED)
        val prepared = prepareStateForIntent(
            WebLifecycleIntent.Start(port = 8080, serviceName = "hub", localhostOnly = true),
            failed,
        )
        assertFalse(
            shouldReleaseForegroundAfterState(
                sawRunning = false,
                isLoading = prepared.isLoading,
                isRunning = prepared.isRunning,
                hasError = prepared.error != null,
            )
        )
    }

    // ---- 状态观察器的前台服务收尾判据（WebServerService.shouldReleaseForegroundAfterState） ----

    @Test
    fun `failed start without ever running releases foreground service`() {
        assertTrue(
            shouldReleaseForegroundAfterState(
                sawRunning = false,
                isLoading = false,
                isRunning = false,
                hasError = true,
            )
        )
    }

    @Test
    fun `idle state without error does not release foreground service`() {
        // 启动协程尚未把 isLoading 置 true 的瞬间：不能提前收尾，否则会误杀这次启动。
        assertFalse(
            shouldReleaseForegroundAfterState(
                sawRunning = false,
                isLoading = false,
                isRunning = false,
                hasError = false,
            )
        )
    }

    @Test
    fun `starting or running keeps foreground service`() {
        assertFalse(
            shouldReleaseForegroundAfterState(
                sawRunning = false,
                isLoading = true,
                isRunning = false,
                hasError = false,
            )
        )
        assertFalse(
            shouldReleaseForegroundAfterState(
                sawRunning = true,
                isLoading = false,
                isRunning = true,
                hasError = false,
            )
        )
    }

    @Test
    fun `normal stop after running releases foreground service`() {
        assertTrue(
            shouldReleaseForegroundAfterState(
                sawRunning = true,
                isLoading = false,
                isRunning = false,
                hasError = false,
            )
        )
    }
}
