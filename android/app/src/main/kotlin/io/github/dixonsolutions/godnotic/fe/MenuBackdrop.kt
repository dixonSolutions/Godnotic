package io.github.dixonsolutions.godnotic.fe

/** Opacity of the in-game menu backdrop; live games remain visible. */
internal object MenuBackdrop {
    fun color(paused: Boolean): Int = if (paused) 0xB3000000.toInt() else 0
}
