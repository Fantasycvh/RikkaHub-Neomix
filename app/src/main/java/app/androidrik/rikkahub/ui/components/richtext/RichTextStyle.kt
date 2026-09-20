package app.androidrik.rikkahub.ui.components.richtext

/**
 * 禁用连字特性（`calt`/`liga`/`clig`）。
 *
 * 等宽字体开启连字会把 `!=` `>=` `->` `=>` 等渲染成单字形，代码辨识度下降（对齐上游 7b92f89e / #1623）。
 *
 * **凡是渲染代码/行内代码的地方都要带上它**——漏一处就会与其它处不一致，而这正是 #1920
 * （内联 code span 漏了、只有代码块修过）的成因。故集中在此单一定义，不逐处内联字面量。
 *
 * 用途：`SpanStyle(fontFeatureSettings = …)` 或 `LocalTextStyle.current.copy(fontFeatureSettings = …)`。
 * `highlight` 模块的 `Highlighter` 因其为独立 Gradle 模块、共享需新增依赖边，仍保留自身副本。
 */
internal const val CODE_FONT_FEATURES = "'calt' 0, 'liga' 0, 'clig' 0"
