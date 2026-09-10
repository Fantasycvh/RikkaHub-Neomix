package app.androidrik.rikkahub.ui.components.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private val MAX_HEIGHT = 10000.dp
private val MAX_WIDTH = 10000.dp

val LocalExportContext = staticCompositionLocalOf { false }

/**
 * Draws an arbitrary composable into a bitmap
 * mainScope has to be Dispatcher.Main because it has to perform
 * layout and measurement calculations on the UI thread.
 */
class BitmapComposer(private val mainScope: CoroutineScope) {
    /**
     * Renders an arbitrary Composable View into a Bitmap.
     *
     * @param activity The host activity that is needed to attach the composable content to the view hierarchy.
     * @param width Optional width of the bitmap in device-independent pixels. Try to provide
     * a width or height value for better results.
     * @param height Optional height of the bitmap in device-independent pixels. Try to provide
     * a width or height value for better results.
     * @param screenDensity screen density to interpret the width and height.
     * @param content An arbitrary composable content to render.
     * @return A Bitmap representing the rendered Composable content.
     */
    suspend fun composableToBitmap(
        activity: Activity,
        width: Dp? = null,
        height: Dp? = null,
        screenDensity: Density,
        content: @Composable () -> Unit
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        mainScope.launch {
            var decorView: ViewGroup? = null
            var composeViewContainer: FrameLayout? = null
            try {
                // Step 1: Interpret the pixels while taking into account the screen density
                val contentWidthInPixels = (screenDensity.density * (width ?: MAX_WIDTH).value).roundToInt()
                val contentHeightInPixels = (screenDensity.density * (height ?: MAX_HEIGHT).value).roundToInt()

                // Step 2: Create a container to hold the ComposeView temporarily
                val container = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(contentWidthInPixels, contentHeightInPixels)
                    visibility = View.INVISIBLE // Keep it invisible
                }
                composeViewContainer = container

                // Step 3: Create and configure the ComposeView using the activity
                val composeView = ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        CompositionLocalProvider(LocalExportContext provides true) {
                            content()
                        }
                    }
                }

                // add the composable view to the container
                container.addView(composeView)

                // Step 4: Attach container to the root decor view
                val decor = activity.window.decorView as ViewGroup
                decorView = decor
                decor.addView(container) // since the container is invisible, we are OK.

                // Step 5: Create measure specifications for the ComposeView
                // If height or width is not provided, use AT_MOST to let the content decide the height
                val widthMeasureSpecs = if (width == null) {
                    View.MeasureSpec.AT_MOST // or View.MeasureSpec.UNSPECIFIED
                    // UNSPECIFIED width may not work with horizontally scrollable content - use caution.
                } else {
                    View.MeasureSpec.EXACTLY
                }

                val heightMeasureSpecs = if (height == null) {
                    View.MeasureSpec.AT_MOST // or View.MeasureSpec.UNSPECIFIED
                    // UNSPECIFIED height may not work with vertically scrollable content - use caution.
                } else {
                    View.MeasureSpec.EXACTLY
                }

                // Step 6: Wait for the ComposeView to be drawn and capture the bitmap
                Handler(Looper.getMainLooper()).post {
                    // ask for the container view to measure itself
                    container.measure(
                        View.MeasureSpec.makeMeasureSpec(
                            contentWidthInPixels,
                            widthMeasureSpecs
                        ),
                        View.MeasureSpec.makeMeasureSpec(
                            contentHeightInPixels,
                            heightMeasureSpecs
                        )
                    )

                    // now request a layout at origin
                    container.layout(0, 0, contentWidthInPixels, contentHeightInPixels)

                    // Wait for async components to complete rendering before capturing bitmap
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // Re-measure after async components have loaded to get proper height
                            container.measure(
                                View.MeasureSpec.makeMeasureSpec(
                                    contentWidthInPixels,
                                    widthMeasureSpecs
                                ),
                                View.MeasureSpec.makeMeasureSpec(
                                    contentHeightInPixels,
                                    heightMeasureSpecs
                                )
                            )

                            // Re-layout with the actual measured dimensions
                            val actualWidth = container.measuredWidth
                            val actualHeight = container.measuredHeight
                            container.layout(0, 0, actualWidth, actualHeight)

                            val bitmap = composeView.drawToBitmap() // layout finished, draw to bitmap
                            continuation.resume(bitmap) // notify the caller with the bitmap
                        } catch (e: Exception) {
                            // 渲染失败：不永久挂起，向调用方传播异常
                            continuation.resumeWithException(e)
                        } finally {
                            // 无论成功失败都清理视图（取消/异常时 view 不泄漏）
                            decor.removeView(container)
                        }
                    }, 100) // delay to allow ComposeView to finish rendering
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 视图创建/挂载阶段异常：避免调用方永久挂起
                continuation.resumeWithException(e)
                decorView?.removeView(composeViewContainer)
            }
        }
    }

    /**
     * 只 measure/layout（不 draw）量取内容的真实高度（px）。
     * 长图导出第一步：超过单 Bitmap 硬件上限时须分页捕获，需先知道总高。
     */
    suspend fun measureContentHeight(
        activity: Activity,
        width: Dp,
        screenDensity: Density,
        content: @Composable () -> Unit
    ): Int = suspendCancellableCoroutine { continuation ->
        mainScope.launch {
            var decorView: ViewGroup? = null
            var composeViewContainer: FrameLayout? = null
            try {
                val widthPx = (screenDensity.density * width.value).roundToInt()
                val container = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
                    visibility = View.INVISIBLE
                }
                composeViewContainer = container

                val composeView = ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        CompositionLocalProvider(LocalExportContext provides true) {
                            content()
                        }
                    }
                }
                container.addView(composeView)

                val decor = activity.window.decorView as ViewGroup
                decorView = decor
                decor.addView(container)

                Handler(Looper.getMainLooper()).post {
                    var lastHeight = -1
                    var attempts = 0

                    fun measureOnce(): Int {
                        container.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        container.layout(0, 0, container.measuredWidth, container.measuredHeight)
                        return container.measuredHeight
                    }

                    fun step() {
                        try {
                            val height = measureOnce()
                            attempts++
                            // 连续两次测量一致才认为稳定——图片/Mermaid 等异步内容就绪后高度才不再变，
                            // 单次测量会偏小 → 分页尾部内容被截
                            if (height > 0 && height == lastHeight) {
                                continuation.resume(height)
                                decor.removeView(container)
                                return
                            }
                            lastHeight = height
                            if (attempts >= 8) {
                                continuation.resume(height.coerceAtLeast(1))
                                decor.removeView(container)
                                return
                            }
                            Handler(Looper.getMainLooper()).postDelayed({ step() }, 120)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                            decor.removeView(container)
                        }
                    }

                    step()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                decorView?.removeView(composeViewContainer)
            }
        }
    }

    /**
     * 窗口页捕获：内容整体上移 [offset]，只绘制 [offset, offset+pageHeight] 窗口段。
     * 长图导出分页：每页捕获后按位置拼接成一张连续长图（单张 Bitmap 有硬件尺寸上限）。
     */
    suspend fun composableToBitmapPage(
        activity: Activity,
        width: Dp,
        pageHeight: Dp,
        offset: Dp,
        screenDensity: Density,
        content: @Composable () -> Unit
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        mainScope.launch {
            var decorView: ViewGroup? = null
            var composeViewContainer: FrameLayout? = null
            try {
                val widthPx = (screenDensity.density * width.value).roundToInt()
                val pageHeightPx = (screenDensity.density * pageHeight.value).roundToInt()
                val offsetPx = (screenDensity.density * offset.value).roundToInt()

                val container = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(widthPx, pageHeightPx)
                    visibility = View.INVISIBLE
                }
                composeViewContainer = container

                val composeView = ComposeView(activity).apply {
                    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                    setContent {
                        CompositionLocalProvider(LocalExportContext provides true) {
                            Box(
                                modifier = Modifier
                                    .height(pageHeight)
                                    .clipToBounds()
                            ) {
                                // unbounded：内容必须按自身全高布局（父 height 约束会把内容
                                // 压到页高，offset 上移后窗口落在截断之外成空白页）
                                Box(
                                    modifier = Modifier
                                        .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                        .offset(y = -offset)
                                ) {
                                    content()
                                }
                            }
                        }
                    }
                }
                container.addView(composeView)

                val decor = activity.window.decorView as ViewGroup
                decorView = decor
                decor.addView(container)

                Handler(Looper.getMainLooper()).post {
                    container.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(pageHeightPx, View.MeasureSpec.EXACTLY)
                    )
                    container.layout(0, 0, widthPx, pageHeightPx)
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            container.measure(
                                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(pageHeightPx, View.MeasureSpec.EXACTLY)
                            )
                            container.layout(0, 0, widthPx, pageHeightPx)
                            continuation.resume(composeView.drawToBitmap())
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        } finally {
                            decor.removeView(container)
                        }
                    }, 100)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                decorView?.removeView(composeViewContainer)
            }
        }
    }
}
