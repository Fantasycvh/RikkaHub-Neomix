package app.androidrik.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
) {
    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    /**
     * endpoint 没写协议时补上 https://，给不需要兼容旧行为的配置用。
     *
     * 显式写了 http:// 的保持原样（兼容备份等旧配置）；[RemoteFileStore] 的素材上传另行严格要求 HTTPS，
     * 显式 http 的配置会被它判为不可用。
     */
    fun withHttpsByDefault(): S3Config =
        if (endpoint.isBlank() || "://" in endpoint) this else copy(endpoint = "https://$endpoint")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }

}
