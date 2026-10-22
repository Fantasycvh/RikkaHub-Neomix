package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.plugin.api.PluginCapability
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JS 插件清单(对标 BiliPai 的 `.bilipai.js`:单文件、自描述)。
 *
 * 形态:**一个 `.js` 文件,开头用块注释携带 JSON 清单**,其余是脚本。
 * 不引入模块系统(无 import/require)、不引入构建步骤——与 BiliPai 一致的取舍:
 * 单文件便于分享,代价是只能有"单例级"状态(每次求值都重建 WebView,状态不跨调用)。
 *
 * 文件长这样(清单以 `@rikka-plugin` 标记开头,见 [MANIFEST_START]):
 *
 *     @rikka-plugin
 *     { "id": "sample.upper", "name": "大写输出", "target": "output" }
 *     function onOutput({ text }) { return text.toUpperCase(); }
 */
@Serializable
data class JsPluginManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "Unknown",
    val target: JsTarget = JsTarget.OUTPUT,
    /** 允许出站的域名白名单(支持 `*.example.com`);为空表示不需要网络。 */
    val networkHosts: List<String> = emptyList(),
    /** 单次钩子求值超时;夹在 [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS]。 */
    val timeoutMs: Long = 1500,
    val storage: Boolean = false,
    /** Declarative tools are dispatched through onTool({ name, arguments, ctx }). */
    val tools: List<JsToolDefinition> = emptyList(),
) {
    val hooks: Set<JsHook> get() = target.hooks

    val capabilities: Set<PluginCapability>
        get() = buildSet {
            hooks.forEach { hook ->
                when (hook) {
                    JsHook.ON_INPUT -> add(PluginCapability.PROMPT_TRANSFORM)
                    JsHook.ON_OUTPUT -> add(PluginCapability.OUTPUT_TRANSFORM)
                }
            }
            if (networkHosts.isNotEmpty()) add(PluginCapability.NETWORK)
            if (storage) add(PluginCapability.STORAGE)
            if (tools.isNotEmpty()) add(PluginCapability.TOOL_REGISTER)
        }

    fun validate(): List<String> = buildList {
        if (!ID_REGEX.matches(id)) {
            add("id 必须匹配 ${ID_REGEX.pattern}(小写字母/数字/下划线/点/中划线,1-64 字符)")
        }
        if (id.startsWith(RESERVED_PREFIX)) add("id 不能使用保留前缀 '$RESERVED_PREFIX'")
        if (name.isBlank()) add("name 不能为空")
        if (target == JsTarget.NONE && tools.isEmpty()) add("target none 必须声明 tools")
        if (tools.size > 16) add("tools 最多 16 个")
        if (tools.map { it.name }.distinct().size != tools.size) add("tools name 不能重复")
        tools.forEachIndexed { index, tool ->
            tool.validate().forEach { add("tools[$index]: $it") }
        }
        networkHosts.forEach { host ->
            if (!HOST_REGEX.matches(host)) {
                add("networkHosts 非法: '$host'(只写域名,可带 '*.' 前缀,不要带 scheme/路径)")
            }
        }
        if (timeoutMs !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            add("timeoutMs 必须在 $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS 之间")
        }
    }

    companion object {
        val ID_REGEX = Regex("^[a-z0-9_.-]{1,64}$")
        val HOST_REGEX = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
        const val RESERVED_PREFIX = "builtin."
        const val MIN_TIMEOUT_MS = 300L
        const val MAX_TIMEOUT_MS = 5000L
        const val MAX_SOURCE_BYTES = 256 * 1024
        const val MANIFEST_START = "/* @rikka-plugin"
        const val MANIFEST_END = "*/"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 从脚本源码解析清单;返回 failure 时 message 可直接展示给用户。 */
        fun parse(source: String): Result<JsPluginDefinition> = runCatching {
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES) {
                "JS 源码超过 ${MAX_SOURCE_BYTES / 1024} KB 上限"
            }
            val start = source.indexOf(MANIFEST_START)
            require(start >= 0) { "缺少清单头:文件开头需有 '/* @rikka-plugin' 注释块" }
            val bodyStart = start + MANIFEST_START.length
            val end = source.indexOf(MANIFEST_END, bodyStart)
            require(end > bodyStart) { "清单块未闭合(缺少 '*/')" }
            val manifestJson = source.substring(bodyStart, end).trim()
            val manifest = json.decodeFromString<JsPluginManifest>(manifestJson)
            val errors = manifest.validate()
            require(errors.isEmpty()) { errors.joinToString("; ") }
            JsPluginDefinition(manifest = manifest, source = source)
        }
    }
}

@Serializable
enum class JsTarget {
    @SerialName("input")
    INPUT,

    @SerialName("output")
    OUTPUT,

    @SerialName("both")
    BOTH,

    @SerialName("none")
    NONE;

    val hooks: Set<JsHook>
        get() = when (this) {
            INPUT -> setOf(JsHook.ON_INPUT)
            OUTPUT -> setOf(JsHook.ON_OUTPUT)
            BOTH -> setOf(JsHook.ON_INPUT, JsHook.ON_OUTPUT)
            NONE -> emptySet()
        }
}

@Serializable
enum class JsHook(val jsName: String) {
    ON_INPUT("onInput"),
    ON_OUTPUT("onOutput"),
}

/** 落盘的完整 JS 插件:清单 + 源码。 */
@Serializable
data class JsPluginDefinition(
    val manifest: JsPluginManifest,
    val source: String,
) {
    val id: String get() = manifest.id
}
