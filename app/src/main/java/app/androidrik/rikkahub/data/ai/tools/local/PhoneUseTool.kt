package app.androidrik.rikkahub.data.ai.tools.local

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.phoneuse.PhoneController
import app.androidrik.rikkahub.phoneuse.PhoneElement
import app.androidrik.rikkahub.phoneuse.PhoneKey
import app.androidrik.rikkahub.phoneuse.PhoneScreen
import app.androidrik.rikkahub.phoneuse.PhoneUseManager

const val PHONE_USE_TOOL_NAME = "phone_use"

// 坐标统一用 0-1000 的网格：截图发给模型前会被缩放，像素坐标对不上
private const val GRID = 1000

// 悬浮窗收到通知后要过一帧才真正从屏幕上消失
private const val OVERLAY_HIDE_MS = 120L

// 操作之后等界面稳定再读屏幕，结果直接带回新界面，省掉模型再调一次 observe
private const val SETTLE_MS = 800L
private const val OPEN_APP_SETTLE_MS = 1500L
private const val TAP_MS = 60L
private const val LONG_PRESS_MS = 700L
private const val SWIPE_MS = 350L
private const val SCREENSHOT_MAX_SIDE = 1568
private const val MAX_LISTED_APPS = 120

/**
 * 已知会阻止读屏/截图的**受限应用**（应用自身的保护配置，不是系统权限）。
 *
 * 失败时按应用名给出针对性说明（停止重试、让用户手动完成），而不是通用错误；新增应用
 * 只需把包名加进这个注册表。微信的防截图/防读屏配置是当前已知实例（2026-11-11）。
 */
internal val PHONE_USE_RESTRICTED_APPS = setOf(
    "com.tencent.mm", // 微信
)

private enum class RestrictionKind { READING, SCREENSHOT }

/**
 * 不允许 AI 操作的应用：支付和银行类。
 * 不能打开，从别的应用跳转进去后也不能读屏和操作，只能返回或回到桌面。
 */
internal val PHONE_USE_BLOCKED_APPS = setOf(
    "com.eg.android.AlipayGphone", // 支付宝
    "com.unionpay", // 云闪付
    "com.paypal.android.p2pmobile", // PayPal
    "com.google.android.apps.walletnfcrel", // Google Wallet
    "com.google.android.apps.nbu.paisa.user", // Google Pay
    "com.icbc", // 工商银行
    "com.chinamworld.main", // 建设银行
    "com.android.bankabc", // 农业银行
    "com.chinamworld.bocmbci", // 中国银行
    "com.bankcomm.Bankcomm", // 交通银行
    "cmb.pb", // 招商银行
    "com.yitong.mbank.psbc", // 邮储银行
)

private val ACTIONS = listOf(
    "observe", "screenshot", "tap", "long_press", "swipe", "scroll", "type", "press", "open_app", "list_apps", "wait",
)

/** 悬浮窗和通知里显示当前在做哪个动作；参数还在流式输出时解析不出来，返回 null */
fun phoneUseActionOf(input: String): String? = runCatching {
    Json.parseToJsonElement(input).jsonObject["action"]?.jsonPrimitive?.contentOrNull
}.getOrNull()

/**
 * 这个调用要不要走本轮审批（S2）。
 *
 * - `observe`（dump）、`list_apps`、`wait`：只读/无副作用
 * - `press HOME`：安全导航（AI 要离开本应用）
 * - `screenshot`：像素会发给模型服务商，按敏感读取处理
 * - 其余变更类全部需要
 *
 * 审批开关关闭时由装配点整体替换为 [app.androidrik.ai.core.TOOL_NO_APPROVAL]。
 */
internal fun phoneUseActionNeedsApproval(input: JsonElement): Boolean {
    val args = runCatching { input.jsonObject }.getOrNull() ?: return true
    return when (args.string("action")) {
        "observe", "list_apps", "wait" -> false
        "press" -> !args.string("key").equals("home", ignoreCase = true)
        // 参数还在流式输出时按最严格处理，避免半截 JSON 绕过审批
        null -> true
        else -> true
    }
}

/**
 * [saveScreenshot] 把 JPEG 存成聊天文件并返回地址；[isSelfInForeground] 判断本应用是否在前台，单测里替换。
 */
internal fun buildPhoneUseTool(
    ownPackageName: String,
    manager: PhoneUseManager,
    saveScreenshot: (ByteArray) -> String,
    isFloatingWindowOn: () -> Boolean,
    isSelfInForeground: suspend () -> Boolean = ::isAppInForeground,
    isDeviceLocked: () -> Boolean = { false },
    /** 用户附加黑名单（包名）；内置支付/银行表不可移除 */
    userBlockedPackages: () -> Set<String> = { emptySet() },
): Tool {
    val session = PhoneUseSession(
        ownPackageName,
        manager,
        saveScreenshot,
        isFloatingWindowOn,
        isSelfInForeground,
        isDeviceLocked,
        userBlockedPackages,
    )
    return Tool(
        name = PHONE_USE_TOOL_NAME,
        description = """
            Operate the user's Android phone: read the screen, tap, swipe, type and open apps.
            Workflow: open_app (or press home) first, then observe to get the on-screen elements, then act.
            Every action that changes the screen returns the new element list, so you rarely need to call observe again.
            Prefer targeting elements by their index from the latest element list; indexes are only valid for that list.
            Coordinates are on a $GRID x $GRID grid: (0,0) is the top-left of the screen and ($GRID,$GRID) the bottom-right,
            in both the element list and screenshots.
            Use screenshot only when the element list is not enough (images, canvas, games, custom views).
            This app itself cannot be operated; leave it with open_app or press home before acting.
            Payment and banking apps are blocked: you cannot open, read or operate them. If a task ends up in one,
            press back or home and tell the user to finish that step themselves.
            Stop and ask the user before irreversible steps such as paying, sending messages or deleting data,
            unless they already told you to do exactly that.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { ACTIONS.forEach { add(it) } })
                        put(
                            "description",
                            "observe: list on-screen elements. screenshot: capture the screen as an image. " +
                                "tap / long_press: on 'element' or at 'x','y'. " +
                                "swipe: drag from 'x','y' to 'to_x','to_y'. " +
                                "scroll: scroll the content in 'direction', optionally inside 'element'. " +
                                "type: replace the text of an input field ('element', or the focused field) with 'text'. " +
                                "press: press 'key'. open_app: launch an app by 'app' (name or package). " +
                                "list_apps: list installed apps. wait: wait 'seconds' for loading."
                        )
                    })
                    put("element", buildJsonObject {
                        put("type", "integer")
                        put("description", "Element index from the latest element list")
                    })
                    put("x", buildJsonObject { put("type", "number"); put("description", "0-$GRID") })
                    put("y", buildJsonObject { put("type", "number"); put("description", "0-$GRID") })
                    put("to_x", buildJsonObject { put("type", "number"); put("description", "0-$GRID, swipe end") })
                    put("to_y", buildJsonObject { put("type", "number"); put("description", "0-$GRID, swipe end") })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { listOf("up", "down", "left", "right").forEach { add(it) } })
                        put("description", "For scroll: 'down' reveals content further down the page")
                    })
                    put("text", buildJsonObject { put("type", "string"); put("description", "Text for type") })
                    put("submit", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For type: press enter afterwards (search, send)")
                    })
                    put("key", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { PhoneKey.entries.forEach { add(it.name.lowercase()) } })
                    })
                    put("app", buildJsonObject {
                        put("type", "string")
                        put("description", "App name or package name for open_app")
                    })
                    put("seconds", buildJsonObject { put("type", "number"); put("description", "For wait, up to 10") })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input -> phoneUseActionNeedsApproval(input) },
        execute = { session.execute(it.jsonObject) },
    )
}

private suspend fun isAppInForeground(): Boolean = withContext(Dispatchers.Main) {
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}

private class PhoneUseSession(
    private val ownPackageName: String,
    private val manager: PhoneUseManager,
    private val saveScreenshot: (ByteArray) -> String,
    private val isFloatingWindowOn: () -> Boolean,
    private val isSelfInForeground: suspend () -> Boolean,
    private val isDeviceLocked: () -> Boolean,
    private val userBlockedPackages: () -> Set<String>,
) {
    // 元素编号和坐标换算都以模型最后看到的那份为准；别的会话插进来操作后要丢弃
    private var lastScreen: PhoneScreen? = null
    private var lastEpoch: Long? = null

    // 模型手上那份元素列表对应的屏幕：与 lastScreen 分开——后者只用于编号换算，
    // 而差分压缩只在「模型确实见过全量列表」时才敢回“没变化”
    private var lastDescribedScreen: PhoneScreen? = null

    suspend fun execute(params: JsonObject): List<UIMessagePart> = manager.withPhoneControl(this) { epoch ->
        if (lastEpoch != epoch) {
            lastScreen = null
            lastDescribedScreen = null
            lastEpoch = epoch
        }
        // 开启工具时已经要求过，这里防的是之后又把悬浮窗或它的权限关掉
        check(isFloatingWindowOn()) {
            "The background floating window is turned off, so the user cannot watch or stop you. " +
                "Ask them to turn it on in Settings > Notifications (and grant the overlay permission) first."
        }
        check(!isDeviceLocked()) {
            "The phone is locked or the screen is off. Ask the user to unlock it and try again."
        }
        val controller = manager.controller.value ?: error(
            "Phone Use is not enabled. Ask the user to turn on \"Neomix Phone Use\" in the system accessibility settings."
        )
        when (val action = params.string("action") ?: error("action is required")) {
            "list_apps" -> {
                val blocked = blockedPackages()
                val apps = controller.listApps().filterNot { it.packageName in blocked }
                text(
                    buildString {
                        apps.take(MAX_LISTED_APPS).forEach { appendLine("${it.label} (${it.packageName})") }
                        if (apps.size > MAX_LISTED_APPS) {
                            append("… ${apps.size - MAX_LISTED_APPS} more apps; use open_app with a name instead.")
                        }
                    }.trimEnd()
                )
            }

            "open_app" -> {
                val app = resolveApp(controller, params.string("app") ?: error("app is required"))
                check(app.packageName != ownPackageName) { "This app itself cannot be operated." }
                check(app.packageName !in blockedPackages()) {
                    "${app.label} is blocked for safety. Tell the user to do this step themselves."
                }
                check(controller.openApp(app.packageName)) { "Failed to open ${app.label}" }
                delay(OPEN_APP_SETTLE_MS)
                text("Opened ${app.label}.\n" + describeCurrent(controller))
            }

            "wait" -> {
                delay(((params.number("seconds") ?: 2.0).coerceIn(0.5, 10.0) * 1000).toLong())
                text(describeCurrent(controller))
            }

            "press" -> {
                val key = params.string("key")?.let { name -> PhoneKey.entries.find { it.name.equals(name, true) } }
                    ?: error("key must be one of ${PhoneKey.entries.joinToString { it.name.lowercase() }}")
                when (key) {
                    // 回到桌面是离开本应用和被禁应用的办法
                    PhoneKey.HOME -> {}
                    // 返回也能离开被禁的应用，但本应用在前台时会落在本应用上
                    PhoneKey.BACK -> ensureNotSelf()
                    else -> ensureOperable(controller)
                }
                act(controller, "press ${key.name.lowercase()}") { controller.pressKey(key) }
            }

            "observe" -> {
                ensureOperable(controller)
                // 显式读屏是模型重新拿到全量列表的入口，不做差分
                text(describeCurrent(controller, forceFull = true))
            }

            "screenshot" -> {
                ensureOperable(controller)
                val screen = capture(controller)
                val bytes = try {
                    hidingOverlays { controller.screenshot(SCREENSHOT_MAX_SIDE) }
                } catch (e: Exception) {
                    // 受限应用的防截图会以 FLAG_SECURE/空窗口形式失败：给针对性说明，别让模型反复试
                    screen.restrictedMessage(RestrictionKind.SCREENSHOT)?.let { throw IllegalStateException(it) }
                    throw e
                }
                listOf(
                    UIMessagePart.Image(url = saveScreenshot(bytes)),
                    UIMessagePart.Text(
                        "Screenshot of ${screen.appName()}. Locate points on a $GRID x $GRID grid: " +
                            "x = horizontal position / image width * $GRID, y = vertical position / image height * $GRID." +
                            if (screen.truncated) " Some elements were omitted from the element list." else ""
                    ),
                )
            }

            "tap", "long_press" -> {
                ensureOperable(controller)
                val (x, y) = targetPoint(controller, params)
                val duration = if (action == "tap") TAP_MS else LONG_PRESS_MS
                act(controller, action) { controller.gesture(x, y, x, y, duration) }
            }

            "swipe" -> {
                ensureOperable(controller)
                val screen = lastScreen ?: capture(controller)
                val (fromX, fromY) = targetPoint(controller, params)
                val toX = screen.gridToX(params.number("to_x") ?: error("to_x is required"))
                val toY = screen.gridToY(params.number("to_y") ?: error("to_y is required"))
                act(controller, action) { controller.gesture(fromX, fromY, toX, toY, SWIPE_MS) }
            }

            "scroll" -> {
                ensureOperable(controller)
                val screen = lastScreen ?: capture(controller)
                val area = params.int("element")?.let { screen.element(it) }
                val left = area?.left ?: 0
                val top = area?.top ?: 0
                val width = area?.let { it.right - it.left } ?: screen.width
                val height = area?.let { it.bottom - it.top } ?: screen.height
                val centerX = left + width / 2f
                val centerY = top + height / 2f
                // 手指移动方向和内容滚动方向相反；只滑中间一段，避开屏幕边缘的系统手势
                val dx = width * 0.3f
                val dy = height * 0.3f
                val (fromX, fromY, toX, toY) = when (params.string("direction") ?: "down") {
                    "down" -> listOf(centerX, centerY + dy, centerX, centerY - dy)
                    "up" -> listOf(centerX, centerY - dy, centerX, centerY + dy)
                    "right" -> listOf(centerX + dx, centerY, centerX - dx, centerY)
                    "left" -> listOf(centerX - dx, centerY, centerX + dx, centerY)
                    else -> error("direction must be one of up, down, left, right")
                }
                act(controller, action) { controller.gesture(fromX, fromY, toX, toY, SWIPE_MS) }
            }

            "type" -> {
                ensureOperable(controller)
                val input = params.string("text") ?: error("text is required")
                val element = params.int("element")?.also { (lastScreen ?: capture(controller)).element(it) }
                act(controller, action) {
                    controller.setText(element, input) &&
                        (params.boolean("submit") != true || controller.pressKey(PhoneKey.ENTER))
                }
            }

            else -> error("unknown action: $action, must be one of $ACTIONS")
        }
    }

    /** 执行一个会改变界面的动作，返回执行后的新界面 */
    private suspend fun act(
        controller: PhoneController,
        name: String,
        block: suspend () -> Boolean,
    ): List<UIMessagePart> {
        val done = hidingOverlays { block() }
        if (!done) {
            return text("$name failed: the system rejected it. Observe the screen and try another way.")
        }
        delay(SETTLE_MS)
        return text("$name done.\n" + describeCurrent(controller))
    }

    private suspend fun <T> hidingOverlays(block: suspend () -> T): T {
        manager.beginActing()
        try {
            delay(OVERLAY_HIDE_MS)
            return block()
        } finally {
            manager.endActing()
        }
    }

    private suspend fun capture(controller: PhoneController): PhoneScreen =
        controller.captureScreen().also { lastScreen = it }

    // 本应用在前台时不操作：模型不能替用户点自己的工具审批，也不该改动应用设置
    private suspend fun ensureNotSelf() {
        check(!isSelfInForeground()) {
            "This app is in the foreground and cannot be operated. Use open_app or press home first."
        }
    }

    // 每次操作前都看一眼屏幕上有谁：被禁的应用可能是从别的应用跳转进来的（比如购物应用拉起支付）。
    // 只看应用窗口，通知栏等内容默认不读（见设计稿隐私边界）。
    private suspend fun ensureOperable(controller: PhoneController) {
        ensureNotSelf()
        controller.visiblePackages().blocked()?.let { error(blockedMessage(it)) }
    }

    /**
     * 读取并描述当前界面；到了被禁的应用里不把界面内容交给模型。
     *
     * [forceFull] 为真时忽略差分（`observe`）：模型显式读屏就应该拿到完整列表。
     */
    private suspend fun describeCurrent(controller: PhoneController, forceFull: Boolean = false): String {
        val screen = capture(controller)
        screen.packages.blocked()?.let { return blockedMessage(it) }
        // 受限应用可能返回空元素树：这不是“还在加载”，重试没有意义
        if (screen.elements.isEmpty()) {
            screen.restrictedMessage(RestrictionKind.READING)?.let { return it }
        }
        val text = describe(screen, previous = lastDescribedScreen.takeUnless { forceFull })
        // 差分文本同样让模型对新列表建立完整认知：作为下一份基线
        lastDescribedScreen = screen
        return text
    }

    private fun blockedPackages(): Set<String> = PHONE_USE_BLOCKED_APPS + userBlockedPackages()

    private fun Set<String>.blocked(): String? = firstOrNull { it in blockedPackages() }

    private fun blockedMessage(packageName: String) =
        "$packageName is now on screen and is blocked for safety: you cannot read or operate anything " +
            "while it is visible. Press back or home, and tell the user to finish this step themselves."

    private suspend fun targetPoint(controller: PhoneController, params: JsonObject): Pair<Float, Float> {
        val screen = lastScreen ?: capture(controller)
        params.int("element")?.let { index ->
            val element = screen.element(index)
            return (element.left + element.right) / 2f to (element.top + element.bottom) / 2f
        }
        val x = params.number("x") ?: error("element or x,y is required")
        val y = params.number("y") ?: error("element or x,y is required")
        return screen.gridToX(x) to screen.gridToY(y)
    }

    private fun resolveApp(controller: PhoneController, query: String) = controller.listApps().let { apps ->
        // 包名 > 完整标签 > 前缀 > 包含；模糊档取最短标签，避免 "note" 命中更长的无关应用
        apps.find { it.packageName == query }
            ?: apps.find { it.label.equals(query, ignoreCase = true) }
            ?: apps.filter { it.label.startsWith(query, ignoreCase = true) }.minByOrNull { it.label.length }
            ?: apps.filter { it.label.contains(query, ignoreCase = true) }.minByOrNull { it.label.length }
            ?: error("App not found: $query. Use list_apps to see installed apps.")
    }
}

private fun text(text: String) = listOf(UIMessagePart.Text(text))

private fun PhoneScreen.element(index: Int): PhoneElement =
    elements.getOrNull(index) ?: error(
        restrictedMessage(RestrictionKind.READING)
            ?: "Element $index is not in the latest element list. Observe the screen again."
    )

/** 受限应用的针对性说明；不在注册表内的应用返回 null（走通用错误） */
private fun PhoneScreen.restrictedMessage(kind: RestrictionKind): String? {
    if (packages.none { it in PHONE_USE_RESTRICTED_APPS } && packageName !in PHONE_USE_RESTRICTED_APPS) {
        return null
    }
    val capability = when (kind) {
        RestrictionKind.READING -> "screen reading"
        RestrictionKind.SCREENSHOT -> "screenshots"
    }
    return "${appName()} restricts $capability on this device (the app's own protection). " +
        "Do not retry; press back or home, and ask the user to do this step themselves."
}

private fun PhoneScreen.gridToX(value: Double) = (value.coerceIn(0.0, GRID.toDouble()) / GRID * width).toFloat()

private fun PhoneScreen.gridToY(value: Double) = (value.coerceIn(0.0, GRID.toDouble()) / GRID * height).toFloat()

private fun PhoneScreen.appName() = when {
    packageName == null -> "unknown app"
    appLabel != null -> "$appLabel ($packageName)"
    else -> packageName
}

/**
 * 把界面交给模型。给了 [previous]（模型已见过的上一份列表）时做差分压缩：
 *
 * - 完全一致（含坐标）→ 只回一行“没变化”，索引仍然有效；
 * - 只有文字/勾选等状态变化、布局与坐标都没动 → 只回变化的元素；
 * - 其余情况（换应用、键盘出现、增删元素、位置变化、列表截断状态变化）→ 回全量列表。
 *
 * 索引是列表位置而不是稳定 id：一旦增删/移动元素，旧编号就会指向别的控件，所以那种
 * 情况必须回全量，不能猜。
 */
internal fun describe(screen: PhoneScreen, previous: PhoneScreen? = null): String {
    if (previous != null) {
        unchangedText(screen, previous)?.let { return it }
        changedText(screen, previous)?.let { return it }
    }
    return buildString {
        appendLine("App: ${screen.appName()}")
        if (screen.keyboardVisible) appendLine("Keyboard: visible")
        if (screen.elements.isEmpty()) {
            append("No elements found. The screen may still be loading (wait), or needs a screenshot.")
            return@buildString
        }
        appendLine("Elements as [index] type \"label\" (or #resource-id when unlabeled) flags @(x,y):")
        screen.elements.forEach { appendElement(it, screen) }
        if (screen.truncated) append("More elements were omitted; scroll or take a screenshot to see the rest.")
    }.trimEnd()
}

private fun unchangedText(screen: PhoneScreen, previous: PhoneScreen): String? {
    if (screen != previous) return null
    return "Screen unchanged: still ${screen.appName()} with the same ${screen.elements.size} elements " +
        "at the same positions. The element list you already have still applies." +
        if (screen.truncated) " More elements were omitted; scroll or take a screenshot to see the rest." else ""
}

private fun changedText(screen: PhoneScreen, previous: PhoneScreen): String? {
    // 编号 = 位置：布局层面的任何变化都可能让旧编号失效，直接退回全量
    if (screen.packageName != previous.packageName ||
        screen.packages != previous.packages ||
        screen.width != previous.width ||
        screen.height != previous.height ||
        screen.keyboardVisible != previous.keyboardVisible ||
        screen.truncated != previous.truncated ||
        screen.elements.size != previous.elements.size
    ) {
        return null
    }
    val changed = mutableListOf<PhoneElement>()
    screen.elements.forEachIndexed { index, element ->
        val old = previous.elements[index]
        if (element == old) return@forEachIndexed
        if (element.className != old.className ||
            element.resourceId != old.resourceId ||
            element.left != old.left ||
            element.top != old.top ||
            element.right != old.right ||
            element.bottom != old.bottom
        ) {
            return null
        }
        changed += element
    }
    if (changed.isEmpty()) return null
    return buildString {
        appendLine("Screen: ${screen.appName()} (same layout, ${screen.elements.size} elements)")
        appendLine("Only these elements changed; indexes, positions and the rest of the list are unchanged:")
        changed.forEach { appendElement(it, screen) }
        if (screen.truncated) append("More elements were omitted; scroll or take a screenshot to see the rest.")
    }.trimEnd()
}

private fun StringBuilder.appendElement(element: PhoneElement, screen: PhoneScreen) {
    append("[${element.index}] ${element.className}")
    if (element.label.isNotEmpty()) {
        append(" \"${element.label}\"")
    } else {
        // 没有文字的图标按钮只剩资源 ID 能说明用途；有文字时不带，省些篇幅
        element.resourceId?.let { append(" #$it") }
    }
    if (element.password) append(" password")
    if (element.clickable) append(" clickable")
    if (element.editable) append(" editable")
    if (element.scrollable) append(" scrollable")
    element.checked?.let { append(if (it) " checked" else " unchecked") }
    if (element.focused) append(" focused")
    val x = (element.left + element.right) / 2 * GRID / screen.width.coerceAtLeast(1)
    val y = (element.top + element.bottom) / 2 * GRID / screen.height.coerceAtLeast(1)
    appendLine(" @($x,$y)")
}

// 模型有时把数字写成字符串，按内容解析
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String) = string(key)?.toDoubleOrNull()

private fun JsonObject.int(key: String) = number(key)?.toInt()

private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
