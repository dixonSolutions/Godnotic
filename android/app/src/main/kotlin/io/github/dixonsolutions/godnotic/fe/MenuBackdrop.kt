package io.github.dixonsolutions.godnotic.fe

/**
 * The in-match menu over the game. Only a local game pauses behind it; an online
 * match (or a local one that does not pause) keeps running, drawn and audible.
 */
internal object MenuBackdrop {
    /** The backdrop behind the in-match menu page: a paused game is dimmed, a live one is not. */
    fun color(paused: Boolean): Int = if (paused) 0xB3000000.toInt() else 0

    /**
     * What `fe screen` tells the engine about a visible native screen: 2 = opaque (the
     * engine skips drawing and idles), 0 = see-through (it goes on drawing). A dialog over
     * the match and the menu over a match that is not paused are see-through.
     */
    fun cover(visible: Boolean, overGame: Boolean, inMatchMenu: Boolean, paused: Boolean): String = when {
        !visible || overGame -> "0"
        inMatchMenu && !paused -> "0"
        else -> "2"
    }
}
