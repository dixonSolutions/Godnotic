package io.github.dixonsolutions.godnotic.fe

/** Sizing policy for the native server detail panel's map picture. */
internal object ServerDetails {
    /** Keep the image wide while leaving room for details and actions in either orientation. */
    fun imageMaxHeightPx(widthPx: Int, heightPx: Int): Int =
        minOf(widthPx * 9 / 16, heightPx / 3).coerceAtLeast(0)
}
