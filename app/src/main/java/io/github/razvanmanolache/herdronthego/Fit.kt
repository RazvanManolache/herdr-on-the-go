package io.github.razvanmanolache.herdronthego

/** Phone-width fitting: how many monospace columns fit the chat. */
object FitColumns {
    const val MIN = 20
    const val MAX = 120
    const val FALLBACK = 52
    /** Heartbeat while fitted; the server reaps fits unheard from in minutes. */
    const val HEARTBEAT_MS = 60_000L

    /**
     * [widthPx] is the full screen width, [paddingPx] everything the chat
     * line loses (screen padding, card insets), [charPx] the measured
     * advance of one monospace glyph at the current font size.
     */
    fun phoneCols(widthPx: Int, paddingPx: Int, charPx: Float): Int {
        if (widthPx <= paddingPx || charPx <= 0f) return FALLBACK
        return ((widthPx - paddingPx) / charPx).toInt().coerceIn(MIN, MAX)
    }
}
