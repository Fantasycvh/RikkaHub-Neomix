package app.androidrik.rikkahub.data.ai.compression

/** 压缩阶段（手动压缩对话框据此显示阶段进度，自动/中途压缩只用日志） */
enum class CompressionProgressStage {
    /** 切分计划、组装提示词 */
    PREPARING,

    /** 分块并行摘要：current/total 为已完成块数 */
    CHUNK,

    /** 覆盖率闸校验 */
    VALIDATING,

    /** 覆盖率不足，整篇重摘 */
    SINGLE_PASS,

    /** 自动摘要未通过，生成截断兜底 */
    FALLBACK,
}

data class CompressionProgress(
    val stage: CompressionProgressStage,
    val current: Int = 0,
    val total: Int = 0,
)
