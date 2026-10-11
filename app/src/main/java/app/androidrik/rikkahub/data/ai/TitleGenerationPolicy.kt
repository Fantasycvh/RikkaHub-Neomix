package app.androidrik.rikkahub.data.ai

/**
 * 「这次要不要生成标题」的判定（**纯函数**：把开关、手动、标题现状三条判据从 `ChatService` 里拿出来，
 * 好单测——`ChatService` 依赖 Android，测不到）。
 *
 * 三条语义，按优先级：
 * 1. `force`（会话列表/抽屉的「重新生成标题」、Web API）：**永远生成**——开关管的是后台自动行为，
 *    不是用户显式动作（与记忆整理的「整理一下」同一条原则）；
 * 2. 开关关着：自动路径**不生成**（也就不会因为"没配标题模型"而弹提示——根本没尝试）；
 * 3. 否则只在标题为空时生成（一次对话只自动起一次名字，之后不覆盖用户改过的标题）。
 */
internal fun shouldGenerateTitle(
    force: Boolean,
    autoEnabled: Boolean,
    currentTitle: String,
): Boolean = when {
    force -> true
    !autoEnabled -> false
    currentTitle.isBlank() -> true
    else -> false
}
