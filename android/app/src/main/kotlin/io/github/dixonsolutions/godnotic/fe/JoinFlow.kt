package io.github.dixonsolutions.godnotic.fe

/**
 * When the join/load card may be on screen.
 *
 * A join starts with the native screens visible (the browser that launched it).
 * The card belongs over the engine's surface even then: the old rule dropped it
 * whenever the overlay was visible, which left the opaque native screen over the
 * preview. Once the join dialog is up that dialog is the join screen, so the
 * card steps aside instead of stacking under it.
 *
 * A stage must last [SETTLE_MS] before the card appears, so a join that answers
 * at once does not flash a loading screen for a few frames.
 */
internal object JoinFlow {
    private val busy = setOf("connecting", "downloading", "loading", "signon", "preview")

    const val SETTLE_MS = 300L

    fun isBusy(stage: String): Boolean = stage in busy

    fun settled(stageSince: Long, now: Long): Boolean = now - stageSince >= SETTLE_MS

    fun card(stage: String, joinDialog: Boolean, settled: Boolean): Boolean =
        isBusy(stage) && !joinDialog && settled

    /** The preview is the live world: the card lets it show through. */
    fun transparent(stage: String): Boolean = stage == "preview"
}
