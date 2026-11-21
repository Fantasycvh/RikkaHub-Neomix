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

/**
 * 压缩完成报告（design-context-boundaries-and-overflow-recovery §3.2 / OpenHands #4 轻量版）：
 * 发送窗口 token 的**前后估算**——两侧同一估算器（`estimateWindowTokens`，usage 优先 +
 * 本地兜底）保证同口径；无服务商计量，对外一律标「估算」。
 *
 * 语义边界（§3.2）：检查点写入与窗口减少是两个独立信号；[savedTokens] 为 0 时如实报
 * （前 Y → 后 Z 原样展示），不做「无变化」判定——那是计划层（空计划）的职责。
 */
data class CompressionSavings(
    val beforeTokens: Int,
    val afterTokens: Int,
) {
    val savedTokens: Int get() = (beforeTokens - afterTokens).coerceAtLeast(0)
}

/**
 * 手动压缩的范围预览（#2294 主需求）：执行前给用户确认「这次会压掉什么」。
 *
 * 由 ChatService.previewCompressionPlan 用与执行**同一段**切分代码（planCompressionWindow）
 * 产出——预览与执行的切分一致性由同一确定性函数保证。estimatedTokens 为本地估算
 * （与压缩路径同源 estimateTokenCount），对外标「估算」；片段 summaryAsText 截断 120 字。
 */
data class CompressionPreview(
    /** 本次将压缩的条数 */
    val toCompressCount: Int,
    /** 保留原文的条数 */
    val keptCount: Int,
    /** 被压缩前缀的 token 估算（非精确计量） */
    val estimatedTokens: Int,
    /** 起始边界消息片段（被压缩区域的最早一条，含 role 前缀） */
    val firstSnippet: String,
    /** 结束边界消息片段（被压缩区域的最晚一条，含 role 前缀） */
    val lastSnippet: String,
)
