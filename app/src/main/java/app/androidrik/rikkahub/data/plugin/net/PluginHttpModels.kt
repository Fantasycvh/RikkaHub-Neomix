package app.androidrik.rikkahub.data.plugin.net

import kotlinx.serialization.Serializable

/**
 * JS 插件侧 `rikka.http({...})` 请求的宿主投影。
 *
 * **签名被 `JsPluginRuntime` 直接依赖**(`json.decodeFromString<PluginHttpRequest>(requestJson)`),
 * 改动字段名/默认值会立即破坏 JS 契约,勿动。
 *
 * 与 `fetch` 的差异(有意):只支持字符串 body,不做 FormData/Blob/流式;
 * 上传体积由调用方(脚本超时 + 插件源码上限)间接约束,出站防护见 [PluginHttpGateway]。
 */
@Serializable
data class PluginHttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/**
 * 出站请求的窄结果:只回传 JS 用得上的三样东西。
 *
 * 不暴露 `okhttp3.Response`:插件代码不应拿到连接/拦截器/底层流句柄。
 * `headers` 的 key 为**小写**(OkHttp 5 的规范形式,与浏览器 `fetch` 的 `headers` 迭代一致),
 * 同名多值合并成 `", "` 拼接(JS 侧看到的是 `{ name: value }`)。
 */
data class PluginHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)
