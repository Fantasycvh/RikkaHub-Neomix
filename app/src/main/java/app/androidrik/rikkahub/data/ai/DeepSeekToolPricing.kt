package app.androidrik.rikkahub.data.ai

/**
 * DeepSeek Tool API 刊例价（CNY；2026-11-13 官网价目，配料 01 §7）。
 *
 * **金额只在展示层按当前价估算**（配料 03 §6 的落账原则）：`usage_record` 只存
 * 次数/页数，价格调整不会让历史账目失真。TTS/ASR 处于限时 5 折期（截止时间官网
 * 未公布），价格可能调整。
 */
object DeepSeekToolPricing {
    /** 搜索（文字/图片）：¥5 / 千次，成功且有结果才计费 */
    const val SEARCH_PER_1K_CALLS = 5.0

    /** 网页抓取：¥5 / 千次，取到内容才计费 */
    const val FETCH_PER_1K_CALLS = 5.0

    /** 文件解析 / OCR：¥1.5 / 千页，按成功页数 */
    const val OCR_PER_1K_PAGES = 1.5

    /** 语音合成：限时 5 折 ¥1.5 / 万字符（刊例 3.0）；中文字符算 2 */
    const val TTS_PER_10K_CHARS = 1.5

    /** 语音识别：限时 5 折 ¥0.39 / 小时（刊例 0.78）；按秒不足 1 秒按 1 秒 */
    const val ASR_PER_HOUR = 0.39

    /** 单次搜索/图片搜索的估算金额（¥） */
    const val SEARCH_PER_CALL = SEARCH_PER_1K_CALLS / 1000

    /** 单次抓取的估算金额（¥） */
    const val FETCH_PER_CALL = FETCH_PER_1K_CALLS / 1000

    /** 单页 OCR 的估算金额（¥） */
    const val OCR_PER_PAGE = OCR_PER_1K_PAGES / 1000
}
