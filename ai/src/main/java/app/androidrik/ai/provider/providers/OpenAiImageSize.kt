package app.androidrik.ai.provider.providers

import app.androidrik.ai.ui.ImageGenSize
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 判断 baseUrl 指向的是不是 x.ai（Grok）端点。
 *
 * 只认 host 本身及其子域名。旧写法 `baseUrl.contains("x.ai", ignoreCase = true)` 是在
 * **整串 URL** 上找子串，于是 `https://api.xxx-max.ai/v1`（`max.ai` 里含 `x.ai`）这类
 * 中转域名会被误判成 Grok，而 Grok 不接受 `size` 参数 ⇒ 用户自定义的分辨率被静默丢掉。
 *
 * 解析失败（空串、非 URL、非 http(s) 的奇怪写法）一律按「不是 Grok」处理：宁可多发一个
 * size 让服务端报错，也不要静默丢弃用户明确设定的参数。
 */
internal fun isXaiHost(baseUrl: String): Boolean {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
    return host == "x.ai" || host.endsWith(".x.ai")
}

/**
 * 是否应把 `size` 写进请求体。
 *
 * - `auto` 是本仓尺寸选择的默认值（[ImageGenSize.AUTO]）。服务端的默认行为本就是 auto，
 *   显式写 `"size": "auto"` 没有收益，却会让只接受具体尺寸的服务端（dall-e-2/3 等）直接 400。
 * - `isGrok` 为真时不发（Grok 的图片接口不接受该参数）。
 */
internal fun shouldSendImageSize(size: String, isGrok: Boolean): Boolean =
    size.isNotBlank() && !size.equals(ImageGenSize.AUTO.value, ignoreCase = true) && !isGrok
