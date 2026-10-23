package app.androidrik.rikkahub.ui.floating

/** Device-local preferred size in dp; temporary viewport/IME limits must never overwrite it. */
internal data class FloatingPanelSize(val width: Float = 560f, val height: Float = 520f) {
    fun bounded(maxWidth: Float, maxHeight: Float): FloatingPanelSize {
        val availableWidth = maxWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        val availableHeight = maxHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        return FloatingPanelSize(
            (width.takeIf { it.isFinite() } ?: 560f).coerceIn(minOf(320f, availableWidth), availableWidth),
            (height.takeIf { it.isFinite() } ?: 520f).coerceIn(minOf(240f, availableHeight), availableHeight),
        )
    }

    fun resized(dx: Float, dy: Float, maxWidth: Float, maxHeight: Float): FloatingPanelSize =
        FloatingPanelSize(width + dx, height + dy).bounded(maxWidth, maxHeight)
}
