package app.androidrik.rikkahub.data.model

/**
 * 读取遥测的来源归因。
 *
 * **由调用点显式传入**，绝不作为模型工具的参数/枚举暴露；未知的历史读取保持
 * `readCount - chatReadCount` 的 legacy 语义，不凭空归类。
 */
enum class MemoryReadSource {
    CHAT,
    CONSOLIDATION,
}
