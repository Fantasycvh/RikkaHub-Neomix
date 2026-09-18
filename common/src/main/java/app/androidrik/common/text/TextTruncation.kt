package app.androidrik.common.text

/**
 * 按 UTF-16 单元数截断，且**不切断代理对**。
 *
 * 为什么需要专门的函数：`String.take(n)` 按 Char 计数，恰好切在 emoji/增补平面字符的中间时
 * 会留下一个**孤立的高代理**——它在显示与序列化时都是无效字符（JSON 里会变成问号或乱码）。
 * 反向（低代理收尾同理）不处理，因为截断只可能发生在右边界。
 *
 * 两处调用（消息引用快照、拼装预览的段正文折叠）都依赖这个行为；放在 common 而非各自复制，
 * 是为了让「不要切坏字符」这条约束只有一处实现（本仓对共享件的既有偏好）。
 *
 * @param maxChars 上限（UTF-16 单元数）。返回长度可能比它少 1（为避免切坏代理对而回退）
 */
fun String.truncateSafely(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val lastKept = this[maxChars - 1]
    // 高代理收尾 ⇒ 该对的后半被切掉了，退回一个单元
    return if (lastKept.isHighSurrogate()) substring(0, maxChars - 1) else substring(0, maxChars)
}
